// porter:helper (generic) - generated BlockBehaviour.Properties shell backed by the adapter builder.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.mc.BlockBehaviour;

public final class GenProps {
    private GenProps() {}

    public static <T> T bind(T gen, BlockBehaviour.Properties real) {
        GenAttach.put(gen, "props", real);
        return gen;
    }

    public static BlockBehaviour.Properties real(Object gen) {
        Object v = GenAttach.get(gen, "props");
        if (v instanceof BlockBehaviour.Properties) {
            return (BlockBehaviour.Properties) v;
        }
        BlockBehaviour.Properties p = new BlockBehaviour.Properties();
        GenAttach.put(gen, "props", p);
        return p;
    }
}
