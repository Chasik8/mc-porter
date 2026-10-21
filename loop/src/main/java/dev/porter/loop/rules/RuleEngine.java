package dev.porter.loop.rules;

import dev.porter.loop.javac.ClassifiedError;

/**
 * The small interface through which the loop asks for a rule-based fix of one compile error.
 *
 * <p>Implementations: {@link TextRuleEngine} (built in: reads rule YAML via {@code RuleIo}, applies the simple
 * selector/replace shapes at text level) and {@link RewriterRuleEngine} (adapter slot for the rewriter's Spoon engine
 * once its API is merged). Implementations must be deterministic and must never guess: when a rule's applicability
 * cannot be shown, return {@link FixAttempt#none} with the reason.
 */
public interface RuleEngine {
    /**
     * @param error the classified error
     * @param file  the source file the error is in (current text)
     * @return a fix (new full file text) or the reason why none applies
     */
    FixAttempt fix(ClassifiedError error, SourceText file);

    /** Number of rules this engine can use (for reports). */
    int ruleCount();

    /** Current text of one source file. */
    record SourceText(String relPath, String text) {
    }
}
