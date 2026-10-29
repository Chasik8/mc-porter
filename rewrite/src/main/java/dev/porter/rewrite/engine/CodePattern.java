package dev.porter.rewrite.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import spoon.Launcher;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.support.compiler.VirtualFile;

/**
 * A parsed {@code match.pattern} (RFC 006): Java code with {@code $name} placeholders, either one expression or one or
 * more statements. The code is parsed by Spoon inside a synthetic method:
 * <ul>
 *   <li>{@code $x} used as an expression is a parameter of that method: it matches any expression;</li>
 *   <li>{@code $s;} standing alone as a statement becomes {@code $s();}: it matches any one statement;</li>
 *   <li>{@code T $v = ...} declares a local: {@code $v} matches the name of the local declared there, and later uses
 *       of {@code $v} must read or write that local.</li>
 * </ul>
 */
public final class CodePattern {
    private static final Pattern PH = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");
    /** "$s;" alone as a statement: after start, ';', '{', '}', ')' or 'else'. */
    private static final Pattern STMT_PH = Pattern.compile("(^|[;{})]|\\belse)(\\s*)(\\$[A-Za-z_][A-Za-z0-9_]*)\\s*;");
    /** "T $v =", "T $v;", "T $v :" (a local declared in the pattern). */
    private static final Pattern LOCAL_PH = Pattern.compile("[\\w>\\]]\\s+(\\$[A-Za-z_][A-Za-z0-9_]*)\\s*[=;:]");
    static final String ROOT = "porterPatternRoot";

    private final String source;
    private final boolean statements;
    private final List<CtStatement> stmts;
    private final CtExpression<?> expression;
    private final Set<String> statementHoles;
    private final Set<String> localHoles;
    private final Set<String> expressionHoles;

    private CodePattern(String source, boolean statements, List<CtStatement> stmts, CtExpression<?> expression,
            Set<String> statementHoles, Set<String> localHoles, Set<String> expressionHoles) {
        this.source = source;
        this.statements = statements;
        this.stmts = stmts;
        this.expression = expression;
        this.statementHoles = statementHoles;
        this.localHoles = localHoles;
        this.expressionHoles = expressionHoles;
    }

    /** Parses a pattern; throws {@link IllegalArgumentException} when Spoon cannot model it. */
    public static CodePattern parse(String text) {
        String t = text.trim();
        boolean statements = t.endsWith(";") || t.endsWith("}");
        Set<String> stmtHoles = new LinkedHashSet<>();
        String code = t;
        if (statements) {
            Matcher m = STMT_PH.matcher(code);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                if (")".equals(m.group(1)) && !closesControlHeader(code, m.start(1))) {
                    continue; // "(T) $e;" is a cast of an expression, not a statement after "if (c)"
                }
                stmtHoles.add(m.group(3));
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + m.group(3) + "();"));
            }
            m.appendTail(sb);
            code = sb.toString();
        }
        Set<String> locals = new LinkedHashSet<>();
        Matcher lm = LOCAL_PH.matcher(code);
        while (lm.find()) {
            locals.add(lm.group(1));
        }
        Set<String> exprHoles = new LinkedHashSet<>();
        Matcher pm = PH.matcher(code);
        while (pm.find()) {
            String n = pm.group();
            if (!stmtHoles.contains(n) && !locals.contains(n)) {
                exprHoles.add(n);
            }
        }
        StringBuilder src = new StringBuilder("class PorterPattern {\n    void porterPattern(");
        int i = 0;
        for (String h : exprHoles) {
            src.append(i++ == 0 ? "" : ", ").append("Object ").append(h);
        }
        src.append(") {\n");
        src.append(statements ? code : "Object " + ROOT + " = " + code + ";").append("\n    }\n}\n");
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.getEnvironment().setCommentEnabled(false);
        l.getEnvironment().setAutoImports(false);
        l.getEnvironment().setShouldCompile(false);
        l.addInputResource(new VirtualFile(src.toString(), "PorterPattern.java"));
        try {
            l.buildModel();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("pattern does not parse: " + e.getMessage(), e);
        }
        CtClass<?> c = l.getFactory().Class().get("PorterPattern");
        CtMethod<?> m = c == null ? null : c.getMethodsByName("porterPattern").stream().findFirst().orElse(null);
        CtBlock<?> body = m == null ? null : m.getBody();
        if (body == null || body.getStatements().isEmpty()) {
            throw new IllegalArgumentException("pattern does not parse: " + text);
        }
        if (statements) {
            return new CodePattern(text, true, new ArrayList<>(body.getStatements()), null, stmtHoles, locals, exprHoles);
        }
        if (!(body.getStatements().get(0) instanceof CtLocalVariable<?> lv) || lv.getDefaultExpression() == null
                || body.getStatements().size() != 1) {
            throw new IllegalArgumentException("pattern is not one expression: " + text);
        }
        return new CodePattern(text, false, List.of(), lv.getDefaultExpression(), stmtHoles, locals, exprHoles);
    }

    /** The ')' at {@code close} ends the header of if/while/for/switch/synchronized (so a statement follows). */
    private static boolean closesControlHeader(String code, int close) {
        int depth = 0;
        int i = close;
        for (; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && --depth == 0) {
                break;
            }
        }
        if (i < 0) {
            return false;
        }
        int e = i;
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) {
            e--;
        }
        int s = e;
        while (s > 0 && Character.isLetter(code.charAt(s - 1))) {
            s--;
        }
        return switch (code.substring(s, e)) {
            case "if", "while", "for", "switch", "synchronized" -> true;
            default -> false;
        };
    }

    public String source() {
        return source;
    }

    public boolean statements() {
        return statements;
    }

    public List<CtStatement> stmts() {
        return stmts;
    }

    public CtExpression<?> expression() {
        return expression;
    }

    public boolean statementHole(String name) {
        return statementHoles.contains(name);
    }

    public boolean localHole(String name) {
        return localHoles.contains(name);
    }

    public boolean expressionHole(String name) {
        return expressionHoles.contains(name);
    }
}
