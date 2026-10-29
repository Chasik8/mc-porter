// porter:adapter (the mod) - 1.19.2 RecipeType: 1.7.10 has no recipe types, only the vanilla managers (CraftingManager, FurnaceRecipes).
package dev.porter.shim.mc;

public final class RecipeType<T> {
    public static final RecipeType<Object> CRAFTING = new RecipeType<>("crafting");
    public static final RecipeType<Object> SMELTING = new RecipeType<>("smelting");
    public static final RecipeType<Object> BLASTING = new RecipeType<>("blasting");
    public static final RecipeType<Object> SMOKING = new RecipeType<>("smoking");

    private final String name;

    private RecipeType(String name) { this.name = name; }

    @Override
    public String toString() { return name; }
}
