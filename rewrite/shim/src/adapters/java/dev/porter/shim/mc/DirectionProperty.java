// porter:adapter (generic) - 1.19.2 DirectionProperty over the shim Direction.
package dev.porter.shim.mc;

import dev.porter.shim.Direction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class DirectionProperty extends EnumProperty<Direction> {
    private DirectionProperty(String name, Collection<Direction> values) { super(name, Direction.class, values); }

    public static DirectionProperty create(String name) { return new DirectionProperty(name, Arrays.asList(Direction.values())); }

    public static DirectionProperty create(String name, Predicate<Direction> filter) {
        List<Direction> v = new ArrayList<>();
        for (Direction d : Direction.values()) if (filter.test(d)) v.add(d);
        return new DirectionProperty(name, v);
    }

    public static DirectionProperty create(String name, Direction... values) { return new DirectionProperty(name, Arrays.asList(values)); }

    public static DirectionProperty create(String name, Collection<Direction> values) { return new DirectionProperty(name, values); }
}
