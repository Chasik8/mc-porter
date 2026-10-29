// porter:adapter (generic) - a 1.19.2 block that is a metadata variant of one 1.7.10 block.
package dev.porter.shim.mc;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/**
 * Stands in for a 1.19.2 {@code Block} constant whose 1.7.10 counterpart is one metadata subtype of a shared block
 * (BatBox, MFE and MFSU are three metas of one block). The proxy is never registered: {@link ItemLikes},
 * {@link BlockState#of(Block, int)} and {@link BlockState#is(Block)} unwrap it to the host block plus its meta, so
 * stacks, recipes, drops, filters and block checks that take the holder get the right subtype. A holder whose meta
 * is 0 needs no proxy: use the host block itself.
 */
public final class MetaBlock extends Block {

    private static final Map<Block, Map<Integer, MetaBlock>> CACHE = new HashMap<Block, Map<Integer, MetaBlock>>();

    public final Block host;
    public final int meta;

    private MetaBlock(Block host, int meta) {
        super(Material.iron);
        this.host = host;
        this.meta = meta;
    }

    /** the holder value for {@code host} at {@code meta}; {@code host} itself for meta 0. Never null for a non-null host. */
    public static synchronized Block of(Block host, int meta) {
        if (host == null) return null;
        if (host instanceof MetaBlock) return of(((MetaBlock) host).host, ((MetaBlock) host).meta);
        if (meta == 0) return host;
        Map<Integer, MetaBlock> m = CACHE.get(host);
        if (m == null) CACHE.put(host, m = new HashMap<Integer, MetaBlock>());
        MetaBlock b = m.get(meta);
        if (b == null) m.put(meta, b = new MetaBlock(host, meta));
        return b;
    }

    /** the real block behind a holder (the argument itself when it is not a proxy). */
    public static Block host(Block b) {
        return b instanceof MetaBlock ? ((MetaBlock) b).host : b;
    }

    /** the metadata a holder stands for (0 when it is not a proxy). */
    public static int meta(Block b) {
        return b instanceof MetaBlock ? ((MetaBlock) b).meta : 0;
    }

    /** true when a world block {@code actual} with metadata {@code actualMeta} is what {@code holder} stands for. */
    public static boolean matches(Block holder, Block actual, int actualMeta) {
        if (holder == null) return false;
        if (holder instanceof MetaBlock) {
            MetaBlock p = (MetaBlock) holder;
            return actual == p.host && (actualMeta & 15) == p.meta;
        }
        return actual == holder;
    }
}
