// porter:adapter (generic) - 1.19.2 NetworkRegistry.newSimpleChannel on a 1.7.10 SimpleNetworkWrapper.
package dev.porter.shim.mc;

import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.util.ResourceLocation;

public final class NetworkRegistry {
    private NetworkRegistry() {}

    /** the protocol version and its predicates are ignored (1.7.10 has no channel version negotiation). */
    public static SimpleChannel newSimpleChannel(ResourceLocation name, Supplier<String> version, Predicate<String> client,
            Predicate<String> server) {
        return new SimpleChannel(channelName(name.toString()));
    }

    /** FML 1.7.10 channel names are at most 20 characters: a longer name is cut and made unique with a hash. */
    public static String channelName(String full) {
        if (full.length() <= 20) return full;
        return full.substring(0, 11) + "~" + String.format("%08x", full.hashCode());
    }
}
