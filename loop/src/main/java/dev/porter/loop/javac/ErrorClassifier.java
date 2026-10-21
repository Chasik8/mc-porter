package dev.porter.loop.javac;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies javac errors into {@link ErrorClass} and extracts the symbol. Works on both the plain message text
 * (default javac/Gradle output, javac 8..25 wording) and raw diagnostic keys ({@code -XDrawDiagnostics}).
 * Deterministic and table-driven: the first matching rule wins.
 */
public final class ErrorClassifier {
    private record TextRule(Pattern pattern, String code, ErrorClass cls) {
    }

    /** Message-text rules, first match wins. {@code code} is the javac key the message belongs to. */
    private static final List<TextRule> TEXT = List.of(
            t("^cannot find symbol", "compiler.err.cant.resolve", null),
            t("^package [\\w.]+ does not exist", "compiler.err.doesnt.exist", ErrorClass.MISSING_CLASS),
            t("^cannot access ", "compiler.err.cant.access", ErrorClass.MISSING_CLASS),
            t("^static import only from classes and interfaces", "compiler.err.static.imp.only.classes.and.interfaces", ErrorClass.MISSING_CLASS),
            t("^method does not override or implement a method from a supertype", "compiler.err.method.does.not.override.superclass", ErrorClass.NO_OVERRIDE),
            t("is not abstract and does not override abstract method", "compiler.err.does.not.override.abstract", ErrorClass.NO_OVERRIDE),
            t(" in [\\w.$<>]+ cannot (override|implement) ", "compiler.err.override.meth", ErrorClass.NO_OVERRIDE),
            t("^(method|constructor) [\\w$<>]+ in (class|interface|enum|record) [\\w.$<>,? ]+ cannot be applied to given types",
                    "compiler.err.cant.apply.symbol", ErrorClass.SIGNATURE_MISMATCH),
            t("^no suitable (method|constructor) found for ", "compiler.err.cant.apply.symbols", ErrorClass.SIGNATURE_MISMATCH),
            t("^non-static (method|variable) .* cannot be referenced from a static context", "compiler.err.non-static.cant.be.ref", ErrorClass.SIGNATURE_MISMATCH),
            t("does not take parameters$", "compiler.err.cant.apply.diamond", ErrorClass.SIGNATURE_MISMATCH),
            t("^wrong number of type arguments", "compiler.err.wrong.number.type.args", ErrorClass.SIGNATURE_MISMATCH),
            t("^(reference to [\\w$]+ is ambiguous)", "compiler.err.ref.ambiguous", ErrorClass.SIGNATURE_MISMATCH),
            t("^incompatible types", "compiler.err.prob.found.req", ErrorClass.INCOMPATIBLE_TYPES),
            t("^bad operand types? for ", "compiler.err.operator.cant.be.applied", ErrorClass.INCOMPATIBLE_TYPES),
            t("^inconvertible types", "compiler.err.prob.found.req", ErrorClass.INCOMPATIBLE_TYPES),
            t("^incomparable types", "compiler.err.incomparable.types", ErrorClass.INCOMPATIBLE_TYPES),
            t("cannot be dereferenced$", "compiler.err.cant.deref", ErrorClass.INCOMPATIBLE_TYPES),
            t("^unexpected type", "compiler.err.type.found.req", ErrorClass.INCOMPATIBLE_TYPES),
            t("^array required, but ", "compiler.err.array.req.but.found", ErrorClass.INCOMPATIBLE_TYPES),
            t("^(no )?interface expected here", "compiler.err.intf.expected.here", ErrorClass.INCOMPATIBLE_TYPES),
            t("^for-each not applicable to expression type", "compiler.err.foreach.not.applicable.to.type", ErrorClass.INCOMPATIBLE_TYPES),
            t("^lossy conversion|possible lossy conversion", "compiler.err.possible.loss.of.precision", ErrorClass.INCOMPATIBLE_TYPES),
            // Parser errors.
            t("^(class, interface, enum, or record|class, interface, or enum) expected", "compiler.err.expected4", ErrorClass.JAVA_SYNTAX),
            t("^(illegal start of (expression|type|statement)|not a statement|reached end of file while parsing"
                    + "|unclosed (string|character|comment|text block)( literal)?|empty character literal|illegal character"
                    + "|illegal escape character|'else' without 'if'|orphaned (case|default)|malformed floating-point literal"
                    + "|invalid method declaration; return type required|variable declaration not allowed here"
                    + "|'(try|catch|finally)' without .*|illegal line end in character literal|integer number too large"
                    + "|repeated modifier|illegal combination of modifiers|modifier .* not allowed here"
                    + "|'void' type not allowed here|enum types may not be instantiated|<identifier> expected)",
                    "compiler.err.syntax", ErrorClass.JAVA_SYNTAX),
            t("^'?.+'? expected$", "compiler.err.expected", ErrorClass.JAVA_SYNTAX),
            // Everything else that is common; listed so the derived code is precise.
            t("^unreported exception ", "compiler.err.unreported.exception.need.to.catch.or.throw", ErrorClass.OTHER),
            t("might not have been initialized", "compiler.err.var.might.not.have.been.initialized", ErrorClass.OTHER),
            t("is already defined in ", "compiler.err.already.defined", ErrorClass.OTHER),
            t("has (private|protected) access in ", "compiler.err.report.access", ErrorClass.OTHER),
            t("is not public in .*; cannot be accessed from outside package", "compiler.err.not.def.public.cant.access", ErrorClass.OTHER),
            t("^missing return statement", "compiler.err.missing.ret.stmt", ErrorClass.OTHER),
            t("^unreachable statement", "compiler.err.unreachable.stmt", ErrorClass.OTHER),
            t("is abstract; cannot be instantiated", "compiler.err.abstract.cant.be.instantiated", ErrorClass.OTHER),
            t("^cannot inherit from final", "compiler.err.cant.inherit.from.final", ErrorClass.OTHER),
            t("^cannot assign a value to final variable", "compiler.err.cant.assign.val.to.var", ErrorClass.OTHER),
            t("^call to (super|this) must be first statement", "compiler.err.call.must.be.first.stmt.in.ctor", ErrorClass.OTHER));

    /** Raw diagnostic key prefix -> class (for -XDrawDiagnostics output). */
    private static final Map<String, ErrorClass> RAW = Map.ofEntries(
            Map.entry("compiler.err.doesnt.exist", ErrorClass.MISSING_CLASS),
            Map.entry("compiler.err.cant.access", ErrorClass.MISSING_CLASS),
            Map.entry("compiler.err.method.does.not.override.superclass", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.does.not.override.abstract", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.override.meth", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.override.static", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.override.incompatible.ret", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.override.weaker.access", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.override.meth.doesnt.throw", ErrorClass.NO_OVERRIDE),
            Map.entry("compiler.err.cant.apply.symbol", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.cant.apply.symbols", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.cant.apply.diamond", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.non-static.cant.be.ref", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.wrong.number.type.args", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.ref.ambiguous", ErrorClass.SIGNATURE_MISMATCH),
            Map.entry("compiler.err.prob.found.req", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.operator.cant.be.applied", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.operator.cant.be.applied.1", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.incomparable.types", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.cant.deref", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.type.found.req", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.array.req.but.found", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.intf.expected.here", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.no.intf.expected.here", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.possible.loss.of.precision", ErrorClass.INCOMPATIBLE_TYPES),
            Map.entry("compiler.err.foreach.not.applicable.to.type", ErrorClass.INCOMPATIBLE_TYPES));

    private static final List<String> RAW_SYNTAX = List.of("compiler.err.expected", "compiler.err.illegal.", "compiler.err.not.stmt",
            "compiler.err.premature.eof", "compiler.err.unclosed.", "compiler.err.else.without.if", "compiler.err.orphaned",
            "compiler.err.invalid.meth.decl.ret.type.req", "compiler.err.empty.char.lit", "compiler.err.malformed.fp.lit",
            "compiler.err.variable.not.allowed", "compiler.err.int.number.too.large", "compiler.err.repeated.modifier",
            "compiler.err.mod.not.allowed.here", "compiler.err.try.without.catch");

    private static final Pattern SYM = Pattern.compile("^(class|interface|enum|record|method|variable|constructor|package|static)\\s+(.+)$");
    private static final Pattern LOC_VAR = Pattern.compile("^variable \\S+ of type (.+)$");
    private static final Pattern LOC_TYPE = Pattern.compile("^(class|interface|enum|record|package)\\s+(.+)$");
    private static final Pattern METHOD_SIG = Pattern.compile("^([\\w$<>]+)\\((.*)\\)$");
    private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;");

    private final Path projectRoot;
    private final Function<Path, SourceFacts> sources;

    /**
     * @param projectRoot used to make file paths relative and to read sources for import resolution (may be null)
     * @param sources     source reader (cache), or null to read files directly
     */
    public ErrorClassifier(Path projectRoot, Function<Path, SourceFacts> sources) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.sources = sources == null ? SourceFacts::read : sources;
    }

    public ErrorClassifier() {
        this(null, null);
    }

    private static TextRule t(String regex, String code, ErrorClass cls) {
        return new TextRule(Pattern.compile(regex), code, cls);
    }

    public List<ClassifiedError> classifyAll(List<JavacDiagnostic> diags) {
        List<ClassifiedError> out = new ArrayList<>();
        for (JavacDiagnostic d : diags) {
            if (d.isError()) {
                out.add(classify(d));
            }
        }
        return out;
    }

    public ClassifiedError classify(JavacDiagnostic d) {
        String rel = relative(d.file());
        SourceFacts src = facts(d.file());
        String sourceLine = d.sourceLine() != null ? d.sourceLine() : (src == null ? null : src.line(d.line()));
        if (d.code() != null) {
            return classifyRaw(d, rel, src, sourceLine);
        }
        String msg = d.message();
        for (TextRule r : TEXT) {
            if (r.pattern().matcher(msg).find()) {
                if (r.code().equals("compiler.err.cant.resolve")) {
                    return cantResolve(d, rel, src, sourceLine, d.detail("symbol"), d.detail("location"));
                }
                return withSymbol(d, rel, r.cls(), r.code(), src, sourceLine);
            }
        }
        return new ClassifiedError(d, rel, ErrorClass.OTHER, "compiler.err.unknown", null, null, null, null, null);
    }

    private ClassifiedError classifyRaw(JavacDiagnostic d, String rel, SourceFacts src, String sourceLine) {
        String code = d.code();
        if (code.startsWith("compiler.err.cant.resolve")) {
            // args: kindname.class, Foo, , , (compiler.misc.location: kindname.class, Bar, null)
            // with args: kindname.method, putInt, <typeargs>, String,int, (compiler.misc.location...)
            String msg = d.message();
            int loc = msg.indexOf("(compiler.misc.location");
            String head = loc < 0 ? msg : msg.substring(0, loc);
            String[] a = head.split(",", 4);
            String kind = a.length > 0 ? a[0].replace("kindname.", "").trim() : "";
            String name = a.length > 1 ? a[1].trim() : "";
            String params = a.length > 3 ? a[3].trim().replaceAll(",\\s*$", "") : "";
            String symbol = kind + " " + name + ("method".equals(kind) ? "(" + params + ")" : "");
            String location = null;
            Matcher lm = Pattern.compile("compiler\\.misc\\.location(?:\\.1)?: kindname\\.(\\w+), ([^,)]+)(?:, ([^,)]+))?").matcher(d.message());
            if (lm.find()) {
                location = lm.group(3) != null && !"null".equals(lm.group(3)) && "variable".equals(lm.group(1))
                        ? "variable " + lm.group(2) + " of type " + lm.group(3)
                        : lm.group(1) + " " + lm.group(2);
            }
            return cantResolve(d, rel, src, sourceLine, symbol, location);
        }
        ErrorClass cls = null;
        for (Map.Entry<String, ErrorClass> e : RAW.entrySet()) {
            if (code.equals(e.getKey())) {
                cls = e.getValue();
            }
        }
        if (cls == null) {
            cls = RAW_SYNTAX.stream().anyMatch(code::startsWith) ? ErrorClass.JAVA_SYNTAX : ErrorClass.OTHER;
        }
        return withSymbol(d, rel, cls, code, src, sourceLine);
    }

    /** "cannot find symbol" family: the symbol line decides between class and member. */
    private ClassifiedError cantResolve(JavacDiagnostic d, String rel, SourceFacts src, String sourceLine, String symbolLine,
            String location) {
        String code = "compiler.err.cant.resolve" + (location != null ? ".location" : "");
        if (symbolLine == null) {
            return new ClassifiedError(d, rel, ErrorClass.MISSING_CLASS, code, null, null, null, null, importedName(sourceLine));
        }
        Matcher sm = SYM.matcher(symbolLine);
        String kind = sm.matches() ? sm.group(1) : "class";
        String name = sm.matches() ? sm.group(2).trim() : symbolLine.trim();
        String owner = ownerFrom(location, src);
        switch (kind) {
            case "class", "interface", "enum", "record" -> {
                String fqn = importedName(sourceLine);
                if (fqn == null || !fqn.endsWith("." + name)) {
                    fqn = location != null && location.startsWith("package ") ? location.substring(8).trim() + "." + name
                            : (src == null ? name : src.qualify(name));
                }
                return new ClassifiedError(d, rel, ErrorClass.MISSING_CLASS, code + ".class", "class", null, null, null, fqn);
            }
            case "package" -> {
                return new ClassifiedError(d, rel, ErrorClass.MISSING_CLASS, code + ".package", "package", null, null, null, name);
            }
            case "method", "constructor" -> {
                Matcher mm = METHOD_SIG.matcher(name);
                String member = mm.matches() ? mm.group(1) : name;
                List<String> args = mm.matches() ? splitTypes(mm.group(2)) : null;
                String symbol = (owner == null ? "" : owner) + "#" + member + "(" + (args == null ? "" : String.join(",", args)) + ")";
                return new ClassifiedError(d, rel, ErrorClass.MISSING_METHOD, code + ".method", "method", owner, member, args,
                        owner == null ? null : symbol);
            }
            default -> {
                // variable: a capitalised name used as a qualifier is a missing class (Blocks.STONE without import),
                // anything else a missing field/local.
                if (Character.isUpperCase(name.charAt(0)) && !name.equals(name.toUpperCase()) && sourceLine != null
                        && Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\.").matcher(sourceLine).find()) {
                    String fqn = src == null ? name : src.qualify(name);
                    return new ClassifiedError(d, rel, ErrorClass.MISSING_CLASS, code + ".class", "class", null, null, null, fqn);
                }
                return new ClassifiedError(d, rel, ErrorClass.MISSING_METHOD, code + ".field", "field", owner, name, null,
                        owner == null ? null : owner + "#" + name);
            }
        }
    }

    private ClassifiedError withSymbol(JavacDiagnostic d, String rel, ErrorClass cls, String code, SourceFacts src, String sourceLine) {
        String msg = d.message();
        Matcher m;
        switch (cls) {
            case MISSING_CLASS -> {
                String fqn = importedName(sourceLine);
                if ((m = Pattern.compile("^package ([\\w.]+) does not exist").matcher(msg)).find()) {
                    return new ClassifiedError(d, rel, cls, code, fqn != null ? "class" : "package", null, null, null,
                            fqn != null ? fqn : m.group(1));
                }
                String cf = d.detail("class file for");
                for (String det : d.details()) {
                    Matcher cm = Pattern.compile("^class file for ([\\w.$]+) not found").matcher(det);
                    if (cm.find()) {
                        cf = cm.group(1);
                    }
                }
                if (cf == null && (m = Pattern.compile("^cannot access ([\\w.$]+)").matcher(msg)).find()) {
                    cf = src == null ? m.group(1) : src.qualify(m.group(1));
                }
                return new ClassifiedError(d, rel, cls, code, "class", null, null, null, cf != null ? cf : fqn);
            }
            case SIGNATURE_MISMATCH -> {
                if ((m = Pattern.compile("^(method|constructor) ([\\w$<>]+) in (?:class|interface|enum|record) ([\\w.$]+)").matcher(msg)).find()) {
                    String owner = src == null ? m.group(3) : src.qualify(m.group(3));
                    String member = "constructor".equals(m.group(1)) ? "<init>" : m.group(2);
                    List<String> found = splitTypes(stripNoArgs(d.detail("found")));
                    List<String> req = splitTypes(stripNoArgs(d.detail("required")));
                    return new ClassifiedError(d, rel, cls, code, "constructor".equals(m.group(1)) ? "constructor" : "method", owner,
                            member, found, owner + "#" + member + "(" + String.join(",", req) + ")");
                }
                if ((m = Pattern.compile("^no suitable (method|constructor) found for ([\\w$<>]+)\\((.*)\\)").matcher(msg)).find()) {
                    boolean ctor = "constructor".equals(m.group(1));
                    String owner = null;
                    for (String det : d.details()) {
                        Matcher om = Pattern.compile("^(?:method|constructor) ([\\w.$<>]+?)\\.(?:<init>|[\\w$]+)\\(").matcher(det);
                        if (om.find()) {
                            owner = src == null ? om.group(1) : src.qualify(om.group(1));
                            break;
                        }
                    }
                    String member = ctor ? "<init>" : m.group(2);
                    if (ctor) {
                        owner = src == null ? m.group(2) : src.qualify(m.group(2));
                    }
                    List<String> args = splitTypes(m.group(3));
                    return new ClassifiedError(d, rel, cls, code, ctor ? "constructor" : "method", owner, member, args,
                            owner == null ? null : owner + "#" + member + "(" + String.join(",", args) + ")");
                }
                if ((m = Pattern.compile("^non-static method ([\\w$<>]+)\\((.*?)\\) cannot be referenced").matcher(msg)).find()) {
                    return new ClassifiedError(d, rel, cls, code, "method", null, m.group(1), splitTypes(m.group(2)), null);
                }
                return new ClassifiedError(d, rel, cls, code, null, null, null, null, null);
            }
            case INCOMPATIBLE_TYPES -> {
                if ((m = Pattern.compile("^incompatible types: ([\\w.$<>\\[\\], ?]+) cannot be converted to ([\\w.$<>\\[\\], ?]+)$").matcher(msg)).find()) {
                    String found = src == null ? m.group(1) : src.qualify(m.group(1));
                    return new ClassifiedError(d, rel, cls, code, "class", null, null, null, found);
                }
                return new ClassifiedError(d, rel, cls, code, null, null, null, null, null);
            }
            case NO_OVERRIDE -> {
                if ((m = Pattern.compile("does not override abstract method ([\\w$<>]+)\\((.*?)\\) in ([\\w.$]+)").matcher(msg)).find()) {
                    String owner = src == null ? m.group(3) : src.qualify(m.group(3));
                    List<String> args = splitTypes(m.group(2));
                    return new ClassifiedError(d, rel, cls, code, "method", owner, m.group(1), args,
                            owner + "#" + m.group(1) + "(" + String.join(",", args) + ")");
                }
                if ((m = Pattern.compile("^([\\w$<>]+)\\((.*?)\\) in [\\w.$]+ cannot (?:override|implement) [\\w$<>]+\\(.*?\\) in ([\\w.$]+)").matcher(msg)).find()) {
                    String owner = src == null ? m.group(3) : src.qualify(m.group(3));
                    List<String> args = splitTypes(m.group(2));
                    return new ClassifiedError(d, rel, cls, code, "method", owner, m.group(1), args,
                            owner + "#" + m.group(1) + "(" + String.join(",", args) + ")");
                }
                // @Override without a super method: find the declaration at or below the error line.
                if (src != null) {
                    for (int l = d.line(); l <= Math.min(d.line() + 3, src.lineCount()); l++) {
                        Matcher dm = Pattern.compile("([\\w$]+)\\s*\\(([^)]*)\\)?").matcher(src.line(l).replaceAll("@\\w+(\\([^)]*\\))?", ""));
                        while (dm.find()) {
                            String name = dm.group(1);
                            if (!List.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new").contains(name)) {
                                List<String> params = paramTypes(dm.group(2) == null ? "" : dm.group(2));
                                String owner = src.supertypes().isEmpty() ? null : src.qualify(src.supertypes().get(0));
                                return new ClassifiedError(d, rel, cls, code, "method", owner, name, params,
                                        owner == null ? null : owner + "#" + name + "(" + String.join(",", params) + ")");
                            }
                        }
                    }
                }
                return new ClassifiedError(d, rel, cls, code, "method", null, null, null, null);
            }
            default -> {
                return new ClassifiedError(d, rel, cls, code, null, null, null, null, null);
            }
        }
    }

    private static String stripNoArgs(String s) {
        return s == null || s.equals("no arguments") ? "" : s;
    }

    /** "int,String" or "int, java.lang.String" -> list; "" -> empty list; null -> empty list. */
    static List<String> splitTypes(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** Declaration parameter list "final int a, Foo<Bar> b" -> [int, Foo<Bar>]. */
    private static List<String> paramTypes(String decl) {
        List<String> out = new ArrayList<>();
        for (String p : splitTypes(decl)) {
            String[] words = p.replaceAll("@\\w+\\s*", "").replace("final ", "").trim().split("\\s+");
            if (words.length >= 2) {
                out.add(String.join(" ", Arrays.copyOf(words, words.length - 1)));
            }
        }
        return out;
    }

    private static String importedName(String sourceLine) {
        if (sourceLine == null) {
            return null;
        }
        Matcher m = IMPORT_LINE.matcher(sourceLine);
        return m.find() ? m.group(1) : null;
    }

    private static String ownerFrom(String location, SourceFacts src) {
        if (location == null) {
            return null;
        }
        Matcher v = LOC_VAR.matcher(location);
        String t;
        if (v.matches()) {
            t = v.group(1).trim();
        } else {
            Matcher c = LOC_TYPE.matcher(location);
            if (!c.matches() || "package".equals(c.group(1))) {
                return null;
            }
            t = c.group(2).trim();
        }
        t = t.replaceAll("<.*", "");
        return src == null ? t : src.qualify(t);
    }

    private SourceFacts facts(String file) {
        Path p = resolve(file);
        return p == null ? null : sources.apply(p);
    }

    private Path resolve(String file) {
        try {
            Path p = Path.of(file);
            if (!p.isAbsolute() && projectRoot != null) {
                p = projectRoot.resolve(p);
            }
            return p.toFile().isFile() ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String relative(String file) {
        try {
            Path p = Path.of(file).toAbsolutePath().normalize();
            if (projectRoot != null && p.startsWith(projectRoot)) {
                return projectRoot.relativize(p).toString().replace('\\', '/');
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return file.replace('\\', '/');
    }
}
