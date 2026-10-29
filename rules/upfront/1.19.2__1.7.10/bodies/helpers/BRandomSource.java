package dev.porter.shim.gen.util;

import java.util.Random;

/** The mirror {@code RandomSource} over a java.util.Random (a 1.7.10 world's {@code rand} is one). */
public final class BRandomSource implements RandomSource {
    private final Random rand;

    public BRandomSource(Random rand) {
        this.rand = rand == null ? new Random() : rand;
    }

    @Override
    public boolean nextBoolean() {
        return rand.nextBoolean();
    }

    @Override
    public double nextDouble() {
        return rand.nextDouble();
    }

    @Override
    public float nextFloat() {
        return rand.nextFloat();
    }

    @Override
    public int nextInt() {
        return rand.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return rand.nextInt(bound);
    }
}
