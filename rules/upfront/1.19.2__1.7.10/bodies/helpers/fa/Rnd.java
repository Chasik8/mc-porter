// porter:helper (generic) - RandomSource backed by java.util.Random.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.gen.util.RandomSource;
import java.util.Random;

public final class Rnd {
    private Rnd() {}

    public static RandomSource of(final Random r) {
        return new RandomSource() {
            @Override
            public boolean nextBoolean() {
                return r.nextBoolean();
            }

            @Override
            public double nextDouble() {
                return r.nextDouble();
            }

            @Override
            public float nextFloat() {
                return r.nextFloat();
            }

            @Override
            public int nextInt() {
                return r.nextInt();
            }

            @Override
            public int nextInt(int bound) {
                return r.nextInt(bound);
            }
        };
    }
}
