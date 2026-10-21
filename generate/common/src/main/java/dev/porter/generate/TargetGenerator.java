package dev.porter.generate;

/**
 * A generator for one target platform version, discovered with {@link java.util.ServiceLoader}
 * ({@code META-INF/services/dev.porter.generate.TargetGenerator}).
 */
public interface TargetGenerator {
    /** Minecraft version this generator targets, e.g. {@code 1.7.10}. */
    String version();

    /** Platform version as used in IR/contracts, e.g. {@code forge-1.7.10}. */
    String platform();

    /** Fills {@code ctx.out} (and logs decisions); must be deterministic. */
    void generate(GenContext ctx);
}
