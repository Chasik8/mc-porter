import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import cpw.mods.fml.common.Loader;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * Headless check of a `porter generate` NEI integration (no Minecraft client, no NEI GUI): loads the generated NEI
 * handler classes against the real NotEnoughItems/MC classes, lets every handler build its recipe list, and runs item
 * lookups (recipes of an output, usages of an input, of a machine, of a structure block).
 *
 * <p>References are resolved by a stand-in resolver (one fake Item per registry name, every OreDictionary name and
 * fluid exists), because no game registry runs outside FML's launcher; what really resolves in a pack is reported on a server by
 * {@code <Mod>NeiData.report()}. Java 8 source; classpath = the mod's dev classes + MC 1.7.10 (MCP names) + the
 * deobfuscated NEI/CodeChickenCore/CodeChickenLib jars.
 *
 * <p>Usage: {@code java NeiCheck <generated nei package> [...more packages]}; exit code 1 if a list is wrong.
 */
public final class NeiCheck {
    static final Map<String, Item> ITEMS = new HashMap<String, Item>();
    static int failures;

    public static void main(String[] args) throws Exception {
        // Without FML's launcher no registry can run (Loader needs the LaunchClassLoader). FluidRegistry only needs
        // Blocks.water/lava and a Loader with no active mod: give it two plain blocks and an empty Loader, then water,
        // lava and the stand-in fluids are real Forge fluids.
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object loader = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Loader.class);
        Field instance = Loader.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, loader);
        setStatic(Blocks.class, "water", new Block(Material.water) {
        }.setBlockName("water"));
        setStatic(Blocks.class, "lava", new Block(Material.lava) {
        }.setBlockName("lava"));
        // FluidContainerRegistry registers the vanilla buckets and bottles
        for (String i : new String[] {"water_bucket", "lava_bucket", "bucket", "glass_bottle"}) {
            setStatic(Items.class, i, item("minecraft:" + i));
        }
        setStatic(Items.class, "potionitem", new net.minecraft.item.ItemPotion());
        System.out.println("FluidRegistry: " + FluidRegistry.getRegisteredFluids().keySet() + ", containers: "
                + FluidContainerRegistry.getRegisteredFluidContainerData().length);
        for (String pkg : args) {
            check(pkg);
        }
        System.out.println(failures == 0 ? "NEI CHECK PASSED" : "NEI CHECK FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(String pkg) throws Exception {
        System.out.println("== " + pkg);
        Class<?> model = Class.forName(pkg + ".NeiModel");
        Class<?> resolverType = Class.forName(pkg + ".NeiModel$Resolver");
        Object resolver = Proxy.newProxyInstance(NeiCheck.class.getClassLoader(), new Class<?>[] {resolverType},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        if (m.getName().equals("item")) {
                            return new ItemStack(item(a[0] + ":" + a[1]), (Integer) a[3], (Integer) a[2]);
                        }
                        if (m.getName().equals("ore")) {
                            return new ArrayList<ItemStack>(Collections.singletonList(
                                    new ItemStack(item("ore:" + a[0]), (Integer) a[1], 0)));
                        }
                        if (m.getName().equals("fluid")) {
                            String name = (String) a[0];
                            if (!FluidRegistry.isFluidRegistered(name)) {
                                FluidRegistry.registerFluid(new Fluid(name));
                            }
                            return new FluidStack(FluidRegistry.getFluid(name), (Integer) a[1]);
                        }
                        if (m.getName().equals("text")) {
                            return a[1];
                        }
                        throw new UnsupportedOperationException(m.getName());
                    }
                });
        model.getField("resolver").set(null, resolver);

        List<Class<?>> handlers = new ArrayList<Class<?>>();
        Class<?> config = null;
        for (String name : classesOf(pkg)) {
            if (name.endsWith("NeiHandler")) {
                handlers.add(Class.forName(pkg + "." + name));
            } else if (name.startsWith("NEI") && name.endsWith("Config")) {
                config = Class.forName(pkg + "." + name);
            }
        }
        if (config == null || !Class.forName("codechicken.nei.api.IConfigureNEI").isAssignableFrom(config)) {
            fail("no NEI*Config implementing IConfigureNEI in " + pkg);
        } else {
            System.out.println("config " + config.getName() + " loads, implements IConfigureNEI");
        }
        Class<?> data = null;
        for (String name : classesOf(pkg)) {
            if (name.endsWith("NeiData")) {
                data = Class.forName(pkg + "." + name);
            }
        }
        List<?> categories = (List<?>) data.getMethod("categories").invoke(null);
        List<?> structures = (List<?>) data.getMethod("structures").invoke(null);
        System.out.println("model: " + categories.size() + " recipe types, " + structures.size() + " structures, "
                + ((List<?>) data.getMethod("hidden").invoke(null)).size() + " hidden");
        System.out.print(data.getMethod("report").invoke(null));

        for (Class<?> h : handlers) {
            Object handler = h.newInstance();
            String id = (String) h.getMethod("handlerId").invoke(handler);
            String title = (String) h.getMethod("getRecipeName").invoke(handler);
            int perPage = (Integer) h.getMethod("recipiesPerPage").invoke(handler);
            Object all = h.getMethod("getRecipeHandler", String.class, Object[].class).invoke(handler, id, new Object[0]);
            List<?> pages = (List<?>) h.getField("arecipes").get(all);
            int expected;
            Object category = field(handler, "category");
            if (category != null) {
                expected = ((List<?>) category.getClass().getMethod("available").invoke(category)).size();
            } else {
                expected = 0;
                for (Object s : (List<?>) field(handler, "structures")) {
                    int sx = s.getClass().getField("sizeX").getInt(s);
                    int sy = s.getClass().getField("sizeY").getInt(s);
                    int sz = s.getClass().getField("sizeZ").getInt(s);
                    expected += 1 + (sx <= 9 && sz <= 6 ? sy : 0);
                }
            }
            System.out.println(h.getSimpleName() + " [" + id + "] \"" + title + "\": " + pages.size() + " pages ("
                    + expected + " expected, " + perPage + " per page)");
            if (pages.size() != expected || pages.isEmpty()) {
                fail(h.getSimpleName() + ": " + pages.size() + " pages, expected " + expected);
                continue;
            }
            // every page renders its stacks
            int stacks = 0;
            for (int i = 0; i < pages.size(); i++) {
                stacks += ((List<?>) h.getMethod("getIngredientStacks", int.class).invoke(all, i)).size();
                stacks += ((List<?>) h.getMethod("getOtherStacks", int.class).invoke(all, i)).size();
                stacks += h.getMethod("getResultStack", int.class).invoke(all, i) != null ? 1 : 0;
            }
            System.out.println("  " + stacks + " positioned stacks on " + pages.size() + " pages");
            // lookups: first result (R), first ingredient (U)
            Object first = pages.get(0);
            Object result = first.getClass().getMethod("getResult").invoke(first);
            if (result != null) {
                ItemStack out = (ItemStack) result.getClass().getField("item").get(result);
                int n = lookup(h, handler, "getRecipeHandler", out);
                System.out.println("  R " + name(out) + ": " + n + " page(s)");
                if (n == 0) {
                    fail(h.getSimpleName() + ": no recipe for its own output " + name(out));
                }
            }
            List<?> ings = (List<?>) first.getClass().getMethod("getIngredients").invoke(first);
            if (!ings.isEmpty()) {
                ItemStack in = (ItemStack) ings.get(0).getClass().getField("item").get(ings.get(0));
                int n = lookup(h, handler, "getUsageHandler", in);
                System.out.println("  U " + name(in) + ": " + n + " page(s)");
                if (n == 0) {
                    fail(h.getSimpleName() + ": no usage for its own input " + name(in));
                }
            }
            for (Object o : (List<?>) first.getClass().getMethod("getOtherStacks").invoke(first)) {
                ItemStack other = (ItemStack) o.getClass().getField("item").get(o);
                int n = lookup(h, handler, "getUsageHandler", other);
                System.out.println("  U " + name(other) + " (other stack): " + n + " page(s)");
            }
        }
        if (handlers.isEmpty()) {
            fail("no NEI handler classes in " + pkg);
        }
    }

    static int lookup(Class<?> h, Object handler, String method, ItemStack stack) throws Exception {
        Object r = h.getMethod(method, String.class, Object[].class).invoke(handler, "item", new Object[] {stack});
        return ((List<?>) h.getField("arecipes").get(r)).size();
    }

    static void setStatic(Class<?> c, String name, Object value) throws Exception {
        Field f = c.getField(name);
        Field mods = Field.class.getDeclaredField("modifiers");
        mods.setAccessible(true);
        mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        f.set(null, value);
    }

    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        return null;
    }

    static Item item(String name) {
        Item i = ITEMS.get(name);
        if (i == null) {
            i = new Item().setUnlocalizedName(name);
            ITEMS.put(name, i);
        }
        return i;
    }

    static String name(ItemStack s) {
        return s.stackSize + "x" + s.getItem().getUnlocalizedName().substring(5) + "@" + s.getItemDamage();
    }

    static List<String> classesOf(String pkg) throws Exception {
        List<String> out = new ArrayList<String>();
        URL u = NeiCheck.class.getClassLoader().getResource(pkg.replace('.', '/'));
        if (u == null || !"file".equals(u.getProtocol())) {
            throw new IllegalStateException("package " + pkg + " is not a classes directory on the classpath: " + u);
        }
        File dir = new File(URLDecoder.decode(u.getPath(), "UTF-8"));
        String[] names = dir.list();
        java.util.Arrays.sort(names);
        for (String f : names) {
            if (f.endsWith(".class") && !f.contains("$")) {
                out.add(f.substring(0, f.length() - 6));
            }
        }
        return out;
    }

    static void fail(String msg) {
        failures++;
        System.out.println("FAIL " + msg);
    }
}
