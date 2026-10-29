package dev.porter.port;

import dev.porter.port.javac.Symbols;

/**
 * The normalised error classes of a run: javac errors by distinct symbol/message (the compile stage's
 * {@code symbols.json} keys) and the precheck's own classes ({@code rules:*}, {@code edit:*}, {@code rewrite:*}).
 * One line per class: {@code class TAB count TAB example}; these lines are what status/RECURRING.md counts.
 */
final class ErrorClasses {
    private ErrorClasses() {
    }

    static String line(String cls, int count, String example) {
        return cls.replace('\t', ' ') + "\t" + count + "\t" + (example == null ? "" : example.replace('\t', ' ')) + "\n";
    }

    static String tsv(Symbols.Result r) {
        StringBuilder b = new StringBuilder();
        for (Symbols.Entry e : r.symbols()) {
            b.append(line("javac:" + e.key(), e.count(), e.files().isEmpty() ? "" : e.files().get(0)));
        }
        return b.toString();
    }
}
