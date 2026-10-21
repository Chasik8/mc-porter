package dev.porter.loop.javac;

import java.util.Locale;

/** The loop classifier's classes (candidate.schema.json {@code errorClass}). */
public enum ErrorClass {
    /** A type or package does not exist (cannot find symbol: class, package does not exist, class file not found). */
    MISSING_CLASS,
    /** A method or field (any member) does not exist on the receiver type. */
    MISSING_METHOD,
    /** The member exists but not with these parameters / type arguments / static-ness. */
    SIGNATURE_MISMATCH,
    /** Types do not fit (assignment, return, operands, dereference of a primitive, class vs interface). */
    INCOMPATIBLE_TYPES,
    /** Override/implementation problems (@Override without a super method, abstract method not implemented). */
    NO_OVERRIDE,
    /** The parser failed: the code is not syntactically valid Java. */
    JAVA_SYNTAX,
    /** Anything else (exceptions, definite assignment, access, duplicates ...). */
    OTHER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ErrorClass byId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
