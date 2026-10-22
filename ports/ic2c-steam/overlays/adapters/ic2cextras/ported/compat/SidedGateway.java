// porter:adapter (IC2C-specific) - one instance per logical side (IC2C's SidedGateway API) on 1.7.10 FML sides:
// the server instance always exists, the client one only in a client JVM.
package ic2cextras.ported.compat;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import java.util.function.Supplier;

public class SidedGateway<T> {
    private final T serverSide;
    private final T clientSide;

    public SidedGateway(Supplier<? extends T> forServer, Supplier<? extends T> forClient) {
        serverSide = forServer.get();
        boolean clientJvm = FMLCommonHandler.instance().getSide() == Side.CLIENT;
        clientSide = clientJvm ? forClient.get() : null;
    }

    /** the instance of the side this thread runs on */
    public T get() {
        boolean clientThread = FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT;
        return get(!(clientThread && clientSide != null));
    }

    /** {@code true}: the server instance; {@code false}: the client one (the server one in a dedicated server) */
    public T get(boolean server) {
        if (server || clientSide == null) return serverSide;
        return clientSide;
    }
}
