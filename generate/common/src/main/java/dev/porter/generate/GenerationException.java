package dev.porter.generate;

/** The IR cannot be generated with the given options (e.g. the GUI id range is too small). Exit code 1. */
public final class GenerationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GenerationException(String message) {
        super(message);
    }
}
