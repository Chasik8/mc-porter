package dev.porter.loop.rules;

/**
 * Result of {@link RuleEngine#fix}.
 *
 * @param ruleId  the rule that fired, or null
 * @param newText the new full text of the file, or null when no fix applies
 * @param before  the changed line before the fix (for the candidate log)
 * @param after   the same line after the fix
 * @param reason  why nothing was applied: {@code no-rule}, {@code ambiguous}, {@code not-applicable:<why>}, or null
 */
public record FixAttempt(String ruleId, String newText, String before, String after, String reason) {

    public static FixAttempt none(String reason) {
        return new FixAttempt(null, null, null, null, reason);
    }

    public static FixAttempt applied(String ruleId, String newText, String before, String after) {
        return new FixAttempt(ruleId, newText, before, after, null);
    }

    public boolean isApplied() {
        return newText != null;
    }
}
