package dev.porter.rewrite.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Decompiler artefact fix applied to the source text before parsing: CFR prints an {@code invokedynamic} lambda it
 * cannot resugar as a pseudo call that is not Java,
 *
 * <pre>
 * LambdaMetafactory.metafactory(null, null, null, (Lpkg/Sam;)V, lambda$init$0(int pkg.Button ), (Lpkg/Button;)V)((Owner)this, (int)index)
 * </pre>
 *
 * i.e. the SAM descriptor, the implementation method with its parameter types, the instantiated descriptor, then the
 * captured arguments. It becomes a lambda that calls the implementation method:
 * {@code (cfr$0 -> ((Owner)this).cfr$lambda$init$0((int)index, cfr$0))}; the decompiled synthetic methods
 * ({@code lambda$...}) are renamed {@code cfr$lambda$...} in the file, as javac generates those names again. The SAM parameter count comes from the
 * descriptor; when one more value is captured than the implementation method takes besides the SAM parameters, the
 * first captured value is the receiver, otherwise the call is unqualified (a static method of the same class).
 * Captured values are locals that were effectively final in the original source, so the lambda compiles. Calls whose
 * implementation is not a plain method name (constructor references) are left unchanged.
 */
public final class CfrLambdas {
    private static final String CALL = "LambdaMetafactory.metafactory(";

    private CfrLambdas() {
    }

    /** The text with every resolvable CFR metafactory pseudo call replaced by a lambda (same text when none). */
    public static String fix(String text) {
        if (!text.contains(CALL)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        int at;
        while ((at = text.indexOf(CALL, from)) >= 0) {
            int argsStart = at + CALL.length();
            int argsEnd = close(text, argsStart - 1);
            int capEnd = argsEnd >= 0 && argsEnd + 1 < text.length() && text.charAt(argsEnd + 1) == '(' ? close(text, argsEnd + 1) : -1;
            String lambda = capEnd < 0 ? null : lambda(split(text.substring(argsStart, argsEnd)), split(text.substring(argsEnd + 2, capEnd)));
            if (lambda == null) {
                out.append(text, from, argsStart);
                from = argsStart;
                continue;
            }
            out.append(text, from, at).append(lambda);
            from = capEnd + 1;
        }
        // javac names the synthetic methods of the new lambdas lambda$<method>$<n> too: the decompiled ones are renamed
        return SYNTHETIC.matcher(out.append(text.substring(from)).toString()).replaceAll("cfr\\$$1");
    }

    private static final java.util.regex.Pattern SYNTHETIC = java.util.regex.Pattern.compile("(?<![\\w$])(lambda\\$[\\w$]+)");

    private static String lambda(List<String> meta, List<String> captured) {
        if (meta.size() != 6) {
            return null;
        }
        String impl = meta.get(4);
        int p = impl.indexOf('(');
        if (p <= 0 || !impl.endsWith(")")) {
            return null;
        }
        String name = impl.substring(0, p).trim();
        if (!isIdentifier(name)) {
            return null;
        }
        String params = impl.substring(p + 1, impl.length() - 1).trim();
        int implParams = params.isEmpty() ? 0 : params.split("\\s+").length;
        int samParams = descriptorParams(meta.get(3));
        if (samParams < 0) {
            return null;
        }
        int bound = implParams - samParams;
        String receiver;
        List<String> args = new ArrayList<>();
        if (captured.size() == bound + 1) {
            receiver = "(" + captured.get(0) + ").";
            args.addAll(captured.subList(1, captured.size()));
        } else if (captured.size() == bound) {
            receiver = "";
            args.addAll(captured);
        } else {
            return null;
        }
        List<String> lambdaParams = new ArrayList<>();
        for (int i = 0; i < samParams; i++) {
            lambdaParams.add("cfr$" + i);
        }
        args.addAll(lambdaParams);
        return "((" + String.join(", ", lambdaParams) + ") -> " + receiver + name + "(" + String.join(", ", args) + "))";
    }

    /** Number of parameters of a method descriptor {@code (...)R}, -1 when it is not one. */
    static int descriptorParams(String d) {
        d = d.trim();
        if (!d.startsWith("(")) {
            return -1;
        }
        int n = 0;
        int i = 1;
        while (i < d.length() && d.charAt(i) != ')') {
            char c = d.charAt(i);
            if (c == '[') {
                i++;
                continue;
            }
            if (c == 'L') {
                int semi = d.indexOf(';', i);
                if (semi < 0) {
                    return -1;
                }
                i = semi + 1;
            } else if ("ZBCSIJFD".indexOf(c) >= 0) {
                i++;
            } else {
                return -1;
            }
            n++;
        }
        return i < d.length() ? n : -1;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Index of the parenthesis closing the one at {@code open} (string and char literals skipped), -1 if none. */
    private static int close(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
                if (i < 0) {
                    return -1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipLiteral(String s, int i) {
        char q = s.charAt(i);
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == q) {
                return j;
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    /** Top-level comma-separated parts (parentheses and braces; brackets are not counted: descriptors hold {@code [}), trimmed. */
    private static List<String> split(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int e = skipLiteral(s, i);
                i = e < 0 ? s.length() : e;
            } else if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isEmpty() || !parts.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }
}
