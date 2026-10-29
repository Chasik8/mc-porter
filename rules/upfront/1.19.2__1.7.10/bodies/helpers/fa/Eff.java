// porter:helper (generic) - generated MobEffect shell <-> 1.7.10 Potion.
package dev.porter.shim.gen.helpers.fa;

import dev.porter.shim.gen.world.effect.MobEffect;
import net.minecraft.potion.Potion;

public final class Eff {
    private Eff() {}

    public static MobEffect wrap(Potion p) {
        if (p == null) {
            return null;
        }
        Object c = GenAttach.get(p, "effect");
        if (c instanceof MobEffect) {
            return (MobEffect) c;
        }
        MobEffect e = new MobEffect();
        GenAttach.put(e, "potion", p);
        GenAttach.put(p, "effect", e);
        return e;
    }

    public static Potion potion(Object effect) {
        Object v = GenAttach.get(effect, "potion");
        return v instanceof Potion ? (Potion) v : null;
    }

    public static int id(Object effect) {
        Potion p = potion(effect);
        if (p == null) {
            throw new IllegalArgumentException("effect is not bound to a 1.7.10 potion: " + effect);
        }
        return p.id;
    }
}
