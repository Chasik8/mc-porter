package dev.porter.loop.javac;

import java.util.List;

/**
 * A javac error with its class and the symbol it is about.
 *
 * @param diagnostic the parsed diagnostic
 * @param file       file relative to the project root ('/' separators)
 * @param errorClass classifier result
 * @param code       javac diagnostic key (raw output) or the key derived from the message text
 * @param symbolKind class | package | method | field | constructor | null
 * @param owner      owner type of a member (qualified when the imports allow it), or null
 * @param member     member name (method/field; {@code <init>} for constructors), or null
 * @param argTypes   argument types javac reported (found/required/symbol), or null when unknown
 * @param symbol     {@code Owner#name(Params)}, {@code Owner#field} or a class/package name; null when unknown
 */
public record ClassifiedError(JavacDiagnostic diagnostic, String file, ErrorClass errorClass, String code,
        String symbolKind, String owner, String member, List<String> argTypes, String symbol) {

    public int line() {
        return diagnostic.line();
    }

    /**
     * What a fix is about, independent of lines and of the edited text: file, class, code and the symbol without
     * its argument list (or the message when there is no symbol). Loop protection counts errors per key: a fix
     * whose key has as many errors after it as before did not help ("same error after a fix").
     */
    public String key() {
        String what = symbol != null ? symbol.replaceAll("\\(.*$", "")
                : (member != null ? member : diagnostic.message().replaceAll("\\d+", "#"));
        return file + "|" + errorClass.id() + "|" + code + "|" + what;
    }

    /** Identity of one error occurrence: {@link #key()} plus the echoed source line (stable under line shifts). */
    public String fingerprint() {
        String what = symbol != null ? symbol : diagnostic.message().replaceAll("\\d+", "#");
        String src = diagnostic.sourceLine() == null ? "" : diagnostic.sourceLine().strip();
        return file + "|" + errorClass.id() + "|" + code + "|" + what + "|" + src;
    }
}
