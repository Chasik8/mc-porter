package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.List;
import java.util.Set;

/** Vanilla data-generation recipe builders and Ingredient.of, recorded; save(...) is an intercept. */
public final class RecipeBuiltins implements Builtins {
    static final String PKG = "net.minecraft.data.recipes.";
    static final Set<String> BUILDERS = Set.of("ShapedRecipeBuilder", "ShapelessRecipeBuilder",
            "SimpleCookingRecipeBuilder", "SingleItemRecipeBuilder", "UpgradeRecipeBuilder");
    private final Facts facts;

    public RecipeBuiltins(Facts facts) {
        this.facts = facts;
    }

    private static String simple(String owner) {
        return owner == null ? "" : owner.substring(owner.lastIndexOf('.') + 1);
    }

    @Override
    public boolean isIntercept(String owner, String name) {
        return name.equals("save") && owner != null && owner.startsWith(PKG) && BUILDERS.contains(simple(owner));
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        String owner = c.owner();
        Object t = c.target();
        if (t == null && owner != null && owner.startsWith(PKG) && BUILDERS.contains(simple(owner))) {
            return new VObj(owner, c.name(), c.args());
        }
        if (t == null && "net.minecraft.world.item.crafting.Ingredient".equals(owner)
                && (c.name().equals("of") || c.name().equals("fromValues"))) {
            return new VObj(owner, c.name(), c.args());
        }
        if (t instanceof VObj b && b.type.startsWith(PKG) && BUILDERS.contains(simple(b.type))) {
            if (c.name().equals("save")) {
                List<Object> args = c.argc() > 1 ? List.of(b, c.arg(1)) : List.of(b);
                facts.events.add(new Facts.Event("vanilla-recipe-builder", simple(b.type), args, c.site(),
                        in.activeConditions(), in.currentRoot));
                return V.NULL;
            }
            b.calls.add(new V.Call(c.name(), List.copyOf(c.args())));
            return b;
        }
        return NOT_HANDLED;
    }
}
