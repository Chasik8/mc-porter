package ic2cextras.asm;

/** Values read by IC2 Classic code patched by {@link VentTransformer}. Keep this class free of game references. */
public final class Hooks {

    /** Replaces the constant 40.0 in ItemReactorVentSteam: 40 / steamReactorMultiplier. */
    public static double ventDivisor = 20.0;

    /** Set once the transformer actually patched the vent class. */
    public static boolean ventPatched = false;

    private Hooks() {}

    public static double getVentDivisor() {
        return ventDivisor;
    }
}
