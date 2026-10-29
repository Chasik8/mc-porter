package dev.porter.port.upfront;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.TypePlanner.TypePlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypePlannerTest {
    private static final Concepts CONCEPTS = Concepts.parse("""
            schemaVersion: 1
            concepts:
              - type: net/minecraft/core/Pos
                plan: EXPAND
                shim: dev.porter.shim.Pos
                param: {desc: III, expand: ["$a.x()", "$a.y()", "$a.z()"]}
            types:
              net.minecraft.world.Props: {plan: MIRROR, shim: dev.porter.shim.mc.Props}
            """);

    private final TypePlanner planner = new TypePlanner(CONCEPTS,
            Map.of("net.minecraft.world.Mapped", "net.minecraft.world.MappedOld"),
            Map.of("net.minecraft.world.Hand", "dev.porter.shim.mc.Hand", "net.minecraft.util.Rnd", "java.util.Random"),
            null);

    @Test
    void lineageMapsAndRulesDecide() {
        TypePlan lin = planner.plan("net.minecraft.world.Mapped");
        assertEquals(new TypePlan("net.minecraft.world.Mapped", Plan.MAP, "net.minecraft.world.MappedOld", "lineage", false), lin);
        assertEquals(Plan.MAP, planner.plan("net.minecraft.util.Rnd").plan());
        assertEquals("java.util.Random", planner.plan("net.minecraft.util.Rnd").target());
    }

    @Test
    void existingShimIsMirrored() {
        TypePlan hand = planner.plan("net.minecraft.world.Hand");
        assertEquals(Plan.MIRROR, hand.plan());
        assertEquals("dev.porter.shim.mc.Hand", hand.target());
        TypePlan concept = planner.plan("net.minecraft.core.Pos");
        assertEquals("dev.porter.shim.Pos", concept.target());
        assertEquals("concept", concept.method());
        assertEquals("dev.porter.shim.mc.Props", planner.plan("net.minecraft.world.Props").target());
    }

    @Test
    void unknownTypeGetsAGeneratedMirror() {
        TypePlan t = planner.plan("net.minecraft.world.level.block.Foo");
        assertEquals(Plan.MIRROR, t.plan());
        assertEquals("dev.porter.shim.gen.world.level.block.Foo", t.target());
        assertEquals("new-mirror", t.method());
        assertEquals("dev.porter.shim.gen.forge.fluids.Bar", planner.plan("net.minecraftforge.fluids.Bar").target());
    }

    @Test
    void nestedClassFollowsOuter() {
        TypePlan mapped = planner.plan("net.minecraft.world.Mapped$Inner");
        assertEquals(Plan.MAP, mapped.plan());
        assertEquals("net.minecraft.world.MappedOld$Inner", mapped.target());
        TypePlan gen = planner.plan("net.minecraft.world.level.Foo$Bar$Baz");
        assertEquals("dev.porter.shim.gen.world.level.Foo$Bar$Baz", gen.target());
        assertEquals("outer", gen.method());
    }

    @Test
    void everyTypeGetsAPlan() {
        Map<String, TypePlan> all = planner.plan(List.of("a.B", "a.B$C", "net.minecraft.world.Hand"));
        assertEquals(3, all.size());
    }
}
