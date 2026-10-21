package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.List;

/** Patchouli multiblock API: makeMultiblock is recorded, registerMultiblock is an intercept. */
public final class PatchouliBuiltins implements Builtins {
    static final String API = "vazkii.patchouli.api.PatchouliAPI";
    static final String IAPI = "vazkii.patchouli.api.PatchouliAPI$IPatchouliAPI";
    private final Facts facts;

    public PatchouliBuiltins(Facts facts) {
        this.facts = facts;
    }

    @Override
    public boolean isIntercept(String owner, String name) {
        return name.equals("registerMultiblock") && owner != null && owner.startsWith("vazkii.patchouli.api.");
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        if (c.target() == null && API.equals(c.owner()) && c.name().equals("get")) {
            return new VObj(IAPI, "get", List.of());
        }
        if (c.target() instanceof VObj api && api.type.equals(IAPI)) {
            if (c.name().equals("makeMultiblock")) {
                return new VObj("vazkii.patchouli.api.IMultiblock", "makeMultiblock", c.args());
            }
            if (c.name().equals("registerMultiblock") && c.argc() == 2) {
                facts.events.add(new Facts.Event("patchouli-multiblock", "registerMultiblock", c.args(), c.site(),
                        in.activeConditions(), in.currentRoot));
                return c.arg(1);
            }
        }
        return NOT_HANDLED;
    }
}
