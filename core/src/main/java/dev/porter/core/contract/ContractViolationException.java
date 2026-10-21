package dev.porter.core.contract;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown by typed loaders/writers when a document does not satisfy its contract. */
public class ContractViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<ValidationIssue> issues;

    public ContractViolationException(String what, List<ValidationIssue> issues) {
        super(what + " violates its contract:\n  "
                + issues.stream().map(ValidationIssue::toString).collect(Collectors.joining("\n  ")));
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
