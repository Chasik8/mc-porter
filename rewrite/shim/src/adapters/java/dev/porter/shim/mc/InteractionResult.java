// porter:adapter (generic) - 1.19.2 InteractionResult.
package dev.porter.shim.mc;

public enum InteractionResult {
    SUCCESS,
    CONSUME,
    CONSUME_PARTIAL,
    PASS,
    FAIL;

    public boolean consumesAction() { return this == SUCCESS || this == CONSUME || this == CONSUME_PARTIAL; }

    public boolean shouldSwing() { return this == SUCCESS; }

    public boolean shouldAwardStats() { return this == SUCCESS || this == CONSUME; }

    public static InteractionResult sidedSuccess(boolean client) { return client ? SUCCESS : CONSUME; }
}
