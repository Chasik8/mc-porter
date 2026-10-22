package dev.porter.port.start;

/** A problem the player can fix; the message is shown as written (plain language, no stack trace). */
public final class Fail extends RuntimeException {
    public Fail(String message) {
        super(message);
    }

    public Fail(String message, Throwable cause) {
        super(message, cause);
    }
}
