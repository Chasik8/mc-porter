package ic2cprofile.extract;

import dev.porter.extract.forge1192.interp.Builtins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.List;

/**
 * IC2C intercepts:
 * <ul>
 *   <li>abstract registry methods of the IC2C recipe API ({@code ic2.api.recipes.registries.*#add*}) — the default
 *       convenience methods run normally and end in these;</li>
 *   <li>{@code IMachineRecipeList#addRecipe} (all overloads) is pre-empted so the raw inputs are kept;</li>
 *   <li>{@code IC2Tags#registerTags(key, registry, entries...)} is pre-empted: IC2C turns these into a runtime data
 *       pack of tag files.</li>
 * </ul>
 */
public final class Ic2cBuiltins implements Builtins {
    static final String API = "ic2.api.recipes.registries.";
    static final String TAGS = "ic2.core.platform.registries.IC2Tags";
    static final String MACHINE_LIST = API + "IMachineRecipeList";
    public static final String CHAIN = "ic2c.ApiChain";
    private final Facts facts;

    public Ic2cBuiltins(Facts facts) {
        this.facts = facts;
    }

    @Override
    public boolean preempts(String owner, String name) {
        return TAGS.equals(owner) && name.equals("registerTags")
                || MACHINE_LIST.equals(owner) && name.equals("addRecipe");
    }

    @Override
    public boolean isIntercept(String owner, String name) {
        return owner != null && owner.startsWith(API) && (name.startsWith("add") || name.startsWith("register"));
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        String owner = c.owner();
        if (TAGS.equals(owner) && c.name().equals("registerTags")) {
            facts.events.add(new Facts.Event("ic2c-tag", "registerTags", c.args(), c.site(), in.activeConditions(),
                    in.currentRoot));
            return V.NULL;
        }
        if (owner != null && owner.startsWith(API) && (c.name().startsWith("add") || c.name().startsWith("register"))) {
            VObj chain = new VObj(CHAIN, owner.substring(API.length()) + "#" + c.name(), c.args());
            facts.events.add(new Facts.Event("ic2c-api", owner.substring(API.length()) + "#" + c.name(),
                    List.of(chain), c.site(), in.activeConditions(), in.currentRoot));
            return chain;
        }
        if (c.target() instanceof VObj ch && ch.type.equals(CHAIN)) {
            ch.calls.add(new V.Call(c.name(), List.copyOf(c.args())));
            return ch;
        }
        return NOT_HANDLED;
    }
}
