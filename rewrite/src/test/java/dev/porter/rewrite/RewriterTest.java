package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.rule.Rule;
import dev.porter.rewrite.engine.NameTable;
import dev.porter.rewrite.engine.Rewriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RewriterTest {
    /** Decompiled 1.17+ Forge code keeps mojmap class names but SRG member names. Synthetic. */
    static final String DECOMPILED = """
            package p;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class A extends BlockEntity {
                int energy;

                public A(BlockPos pos, BlockState state) {
                    super(null, pos, state);
                }

                @Override
                protected void m_183515_(CompoundTag tag) {
                    super.m_183515_(tag);
                    this.energy = tag.m_128451_("energy");
                }

                public Object near(BlockPos pos) {
                    Object w = this.f_58857_;
                    return this.m_58904_().m_7702_(pos);
                }

                public Object far(BlockPos pos) {
                    return this.m_58904_().m_7702_(pos.m_7494_());
                }
            }
            """;

    static Rewriter.Options opts(boolean shim, String pkg) {
        return new Rewriter.Options(Rewriter.Options.defaults().platformPrefixes(), shim, pkg, true, 17, List.of());
    }

    @Test
    void srgNamesAreMatchedThroughTheSymbolGraph() {
        Rewriter.FileResult r = TestSupport.rewrite(TestSupport.publicRules(), TestSupport.srgNames(), DECOMPILED);
        String out = r.text();
        assertTrue(out.contains("import net.minecraft.nbt.NBTTagCompound;"), out);
        assertTrue(out.contains("import net.minecraft.tileentity.TileEntity;"), out);
        assertTrue(out.contains("import dev.porter.shim.BlockPos;"), out);
        assertTrue(out.contains("public class A extends TileEntity {"), out);
        assertTrue(out.contains("public void writeToNBT(NBTTagCompound tag) {"), out);
        assertTrue(out.contains("super.writeToNBT(tag);"), out);
        assertTrue(out.contains("this.energy = tag.getInteger(\"energy\");"), out);
        assertTrue(out.contains("Object w = this.worldObj;"), out);
        assertTrue(out.contains("return this.getWorldObj().getTileEntity(pos.getX(), pos.getY(), pos.getZ());"), out);
        // impure position: falls back to the shim helper; m_7494_ is unknown to the example graph -> TODO
        assertTrue(out.contains("return Worlds.getTileEntity(this.getWorldObj(), pos.m_7494_());"), out);
        assertTrue(out.contains("import dev.porter.shim.Worlds;"), out);
        assertTrue(out.contains("// TODO(porter:no-rule) type net.minecraft.world.level.block.state.BlockState"), out);
        assertTrue(out.contains("// TODO(porter:no-rule) constructor call net.minecraft.world.level.block.entity.BlockEntity#<init>(3 args)"), out);
        assertTrue(out.contains("m_7494_(0 args)"), out);
        assertTrue(r.applied().containsKey("r-1.19.2-1.7.10-blockentity-override-saveadditional"));
        long lines = out.lines().filter(l -> l.contains("TODO(porter:")).count();
        assertEquals(lines, r.todos().size(), out);
    }

    /** Annotation usages are type references too (Spoon gives their type reference no position). */
    @Test
    void annotationTypesAreRewritten(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path y = tmp.resolve("r.rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: r-test-type-onlyin
                from: forge-1.19.2
                to: forge-1.7.10
                kind: mechanical
                match:
                  type: "net.minecraftforge.api.distmarker.OnlyIn"
                replace: "cpw.mods.fml.relauncher.SideOnly"
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - before: "import net.minecraftforge.api.distmarker.OnlyIn;"
                    after: "import cpw.mods.fml.relauncher.SideOnly;"
                """);
        List<Rule> rules = dev.porter.core.rule.RuleIo.read(y);
        String code = """
                package p;

                import net.minecraftforge.api.distmarker.OnlyIn;

                public class A {
                    @OnlyIn(value=Dist.CLIENT)
                    void f() {
                    }

                    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
                    void g() {
                    }
                }
                """;
        String out = TestSupport.rewrite(rules, NameTable.empty(), code).text();
        assertTrue(out.contains("import cpw.mods.fml.relauncher.SideOnly;"), out);
        assertTrue(out.contains("@SideOnly(value=Dist.CLIENT)"), out);
        assertFalse(out.contains("@OnlyIn"), out);
    }

    /** Without the 1.19.2 classpath "Outer.Nested.FIELD" is parsed as a field chain: the nested type is still typed. */
    @Test
    void nestedTypeQualifierIsTypedWithoutClasspath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path y = tmp.resolve("r.rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: r-test-type-ifluidhandler
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  type: "net.minecraftforge.fluids.capability.IFluidHandler"
                replace: "x.IFluidHandler"
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - before: "import net.minecraftforge.fluids.capability.IFluidHandler;"
                    after: "import x.IFluidHandler;"
                """);
        List<Rule> rules = new ArrayList<>(dev.porter.core.rule.RuleIo.read(y));
        rules.addAll(TestSupport.publicRules().stream().filter(r -> r.id().equals("r-1.19.2-1.7.10-fluidaction-execute")).toList());
        assertEquals(2, rules.size());
        String code = """
                package p;

                import net.minecraftforge.fluids.capability.IFluidHandler;

                public class A {
                    int f(IFluidHandler h) {
                        return h.drain(5, IFluidHandler.FluidAction.EXECUTE) == null ? 0 : 1;
                    }
                }
                """;
        String out = TestSupport.rewrite(rules, NameTable.empty(), code).text();
        assertTrue(out.contains("return h.drain(5, true) == null ? 0 : 1;"), out);
        assertTrue(out.contains("import x.IFluidHandler;"), out);
    }

    private static Rule typeRule(java.nio.file.Path tmp, String id, String from, String to, String pre) throws Exception {
        java.nio.file.Path y = tmp.resolve(id + ".rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: ID
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  type: "FROM"
                replace: "TO"
                PRE
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - before: "import FROMDOT;"
                    after: "import TO;"
                """.replace("FROMDOT", from.replace('$', '.')).replace("ID", id).replace("FROM", from).replace("TO", to).replace("PRE", pre == null ? "" : "preconditions: [\"" + pre + "\"]"));
        return dev.porter.core.rule.RuleIo.read(y).get(0);
    }

    /**
     * A static qualifier naming a nested type through a subclass ("Block.Properties.of()" for BlockBehaviour$Properties)
     * is rewritten (before: the qualifier stayed "Block.Properties", only declarations were rewritten).
     */
    @Test
    void nestedTypeAccessedThroughSubclassQualifier(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Rule r = typeRule(tmp, "r-test-props", "net.minecraft.world.level.block.state.BlockBehaviour$Properties", "x.Props", null);
        String behaviour = "package net.minecraft.world.level.block.state;\npublic class BlockBehaviour { public static class Properties {"
                + " public static Properties of() { return new Properties(); } } }\n";
        String block = "package net.minecraft.world.level.block;\npublic class Block extends net.minecraft.world.level.block.state.BlockBehaviour {}\n";
        String code = """
                package p;

                import net.minecraft.world.level.block.Block;

                public class A {
                    Object f(Block.Properties p) {
                        return Block.Properties.of();
                    }
                }
                """;
        Rewriter rw = new Rewriter(List.of(r), NameTable.empty(), TestSupport.facts(), java.util.Map.of(), Rewriter.Options.defaults());
        String out = rw.rewrite(List.of(new Rewriter.SourceFile("p/A.java", code, true),
                new Rewriter.SourceFile("net/minecraft/world/level/block/state/BlockBehaviour.java", behaviour, false),
                new Rewriter.SourceFile("net/minecraft/world/level/block/Block.java", block, false))).files().get(0).text();
        assertFalse(out.contains("Block.Properties.of()"), out);
        assertTrue(out.contains("return Props.of();") || out.contains("return x.Props.of();"), out);
    }

    /** the qualifier of a static field without a member rule follows the type rule (before: "LogicalSide.CLIENT" kept). */
    @Test
    void staticFieldQualifierIsRenamed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Rule r = typeRule(tmp, "r-test-side", "net.minecraftforge.fml.LogicalSide", "cpw.mods.fml.relauncher.Side", null);
        String side = "package net.minecraftforge.fml;\npublic enum LogicalSide { CLIENT, SERVER }\n";
        String code = """
                package p;

                import net.minecraftforge.fml.LogicalSide;

                public class A {
                    boolean client(LogicalSide s) {
                        return s == LogicalSide.CLIENT;
                    }
                }
                """;
        Rewriter rw = new Rewriter(List.of(r), NameTable.empty(), TestSupport.facts(), java.util.Map.of(), Rewriter.Options.defaults());
        String out = rw.rewrite(List.of(new Rewriter.SourceFile("p/A.java", code, true),
                new Rewriter.SourceFile("net/minecraftforge/fml/LogicalSide.java", side, false))).files().get(0).text();
        assertTrue(out.contains("return s == Side.CLIENT;"), out);
        assertTrue(out.contains("boolean client(Side s)"), out);
    }

    /** static import of a member of a type with a type rule: the owner part is renamed (before: TODO only). */
    @Test
    void staticImportOwnerIsRenamed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Rule r = typeRule(tmp, "r-test-subscriber", "net.minecraftforge.fml.common.Mod$EventBusSubscriber", "x.Mod.EventBusSubscriber", null);
        String code = """
                package p;

                import static net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

                public class A {
                }
                """;
        String out = TestSupport.rewrite(List.of(r), NameTable.empty(), code).text();
        assertTrue(out.contains("import static x.Mod.EventBusSubscriber.Bus;"), out);
    }

    /** precondition extends: only the direct superclass reference is redirected. */
    @Test
    void extendsPreconditionOnlyMatchesTheSuperclass(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Rule r = typeRule(tmp, "r-test-root", "net.minecraft.world.level.block.Block", "x.StateBlock", "extends");
        String code = """
                package p;

                import net.minecraft.world.level.block.Block;

                public class A extends Block {
                    Block other;
                    <T extends Block> T same(T t) { return t; }
                }
                """;
        String out = TestSupport.rewrite(List.of(r), NameTable.empty(), code).text();
        assertTrue(out.contains("public class A extends StateBlock") || out.contains("public class A extends x.StateBlock"), out);
        assertTrue(out.contains("    Block other;"), out);
        assertTrue(out.contains("<T extends Block> T same"), "type-parameter bounds are not superclasses: " + out);
    }

    /** an on-demand import of a platform package is dropped (before: kept, a compile error in the target). */
    @Test
    void platformOnDemandImportIsDropped() {
        String code = """
                package p;

                import net.minecraft.world.level.block.*;
                import java.util.*;

                public class A {
                }
                """;
        String block = "package net.minecraft.world.level.block;\npublic class Block {}\n";
        Rewriter rw = new Rewriter(List.of(), NameTable.empty(), TestSupport.facts(), java.util.Map.of(), Rewriter.Options.defaults());
        String out = rw.rewrite(List.of(new Rewriter.SourceFile("p/A.java", code, true),
                new Rewriter.SourceFile("net/minecraft/world/level/block/Block.java", block, false))).files().get(0).text();
        assertFalse(out.contains("import net.minecraft.world.level.block.*;"), out);
        assertTrue(out.contains("import java.util.*;"), out);
    }

    /** not-super: super.m(...) stays a virtual call into the (ported) superclass. */
    @Test
    void notSuperPreconditionKeepsSuperCalls(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path y = tmp.resolve("r.rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: r-test-blockentity-getblockstate
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  call: "net.minecraft.world.level.block.entity.BlockEntity#getBlockState()"
                  bind: { recv: "$t" }
                replace: "Helper.state($t)"
                preconditions: ["not-super"]
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - context: "BlockEntity t"
                    before: "Object s = t.getBlockState();"
                    after: "Object s = Helper.state(t);"
                """);
        List<Rule> rules = dev.porter.core.rule.RuleIo.read(y);
        String code = """
                package p;

                import net.minecraft.world.level.block.entity.BlockEntity;

                public class A extends BlockEntity {
                    Object f(BlockEntity o) {
                        Object a = super.getBlockState();
                        return o.getBlockState();
                    }
                }
                """;
        String out = TestSupport.rewrite(rules, NameTable.empty(), code).text();
        assertTrue(out.contains("Object a = super.getBlockState();"), out);
        assertTrue(out.contains("return Helper.state(o);"), out);
    }

    /** Casts belong to the Spoon expression (and its position): a rewritten call keeps them. */
    @Test
    void rewrittenCallKeepsItsCast() {
        List<Rule> rules = TestSupport.publicRules().stream().filter(r -> r.id().equals("r-1.19.2-1.7.10-mth-clamp-int")).toList();
        assertEquals(1, rules.size());
        String code = """
                package p;

                import net.minecraft.util.Mth;

                public class A {
                    byte f(int x) {
                        return (byte) Mth.clamp(x, 0, 100);
                    }

                    byte g(int x) {
                        return (byte)Mth.clamp((int)x, (int)0, (int)100);
                    }

                    int h(int x) {
                        return ((Integer)Mth.clamp(x, 0, 100)).intValue();
                    }
                }
                """;
        String out = TestSupport.rewrite(rules, NameTable.empty(), code).text();
        assertTrue(out.contains("return (byte) MathHelper.clamp_int(x, 0, 100);"), out);
        assertTrue(out.contains("return (byte)MathHelper.clamp_int((int)x, (int)0, (int)100);"), out);
        assertTrue(out.contains("return ((Integer)MathHelper.clamp_int(x, 0, 100)).intValue();"), out);
    }

    /** "$e.member" in a template: a cast (or operator) binding is parenthesised so the member applies to all of it. */
    @Test
    void memberOnCastBindingIsParenthesised(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path y = tmp.resolve("r.rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: r-test-helper-level
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  call: "net.minecraft.util.helpers.Helper#level(java.lang.Object)"
                  bind: { args: ["$e"] }
                replace: "Helper.level($e.id)"
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - context: "Object s; import net.minecraft.util.helpers.Helper"
                    before: "int n = Helper.level(s);"
                    after: "int n = Helper.level(s.id);"
                """);
        List<Rule> rules = dev.porter.core.rule.RuleIo.read(y);
        String code = """
                package p;

                import net.minecraft.util.helpers.Helper;

                public class A {
                    int f(Object s) {
                        return Helper.level((Object)s) + Helper.level(s);
                    }
                }
                """;
        String out = TestSupport.rewrite(rules, NameTable.empty(), code).text();
        assertTrue(out.contains("return Helper.level(((Object)s).id) + Helper.level(s.id);"), out);
    }

    /** A rule's import may take the simple name of an unmapped platform import (which is invalid in the target). */
    @Test
    void ruleImportDisplacesUnmappedPlatformImport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path y = tmp.resolve("r.rule.yaml");
        java.nio.file.Files.writeString(y, """
                schemaVersion: 1
                id: r-test-helper-get
                from: forge-1.19.2
                to: forge-1.7.10
                kind: adapter
                match:
                  call: "net.minecraft.util.helpers.Helper#get(java.lang.String)"
                  bind: { args: ["$a0"] }
                replace: "Helper.getOrCreate($a0)"
                imports: ["neu.util.Helper"]
                confidence: 1.0
                provenance: { source: manual, ref: "test" }
                tests:
                  - context: "String s; import net.minecraft.util.helpers.Helper"
                    before: "Object o = Helper.get(s);"
                    after: "Object o = Helper.getOrCreate(s);"
                """);
        List<Rule> rules = dev.porter.core.rule.RuleIo.read(y);
        String code = """
                package p;

                import net.minecraft.util.helpers.Helper;

                public class A {
                    Object f(String s) {
                        return Helper.get(s);
                    }
                }
                """;
        Rewriter.FileResult r = TestSupport.rewrite(rules, NameTable.empty(), code);
        String out = PostProcess.cleanImports(r).text();
        assertTrue(out.contains("import neu.util.Helper;"), out);
        assertFalse(out.contains("import net.minecraft.util.helpers.Helper;"), out);
        assertTrue(out.contains("return Helper.getOrCreate(s);"), out);
    }

    @Test
    void deterministic() {
        String a = TestSupport.rewrite(TestSupport.publicRules(), TestSupport.srgNames(), DECOMPILED).text();
        String b = TestSupport.rewrite(TestSupport.publicRules(), TestSupport.srgNames(), DECOMPILED).text();
        assertEquals(a, b);
    }

    @Test
    void untypedReceiverIsNotGuessed() {
        String code = """
                package p;
                import net.minecraft.nbt.CompoundTag;
                class B {
                    void f(Object o, CompoundTag t) {
                        ((CompoundTag) o).putInt("a", 1);
                        unknown().putInt("b", 2);
                        t.putInt("c", 3);
                    }
                }
                """;
        String out = TestSupport.rewrite(TestSupport.publicRules(), NameTable.empty(), code).text();
        assertTrue(out.contains("((NBTTagCompound) o).setInteger(\"a\", 1);"), out);
        assertTrue(out.contains("t.setInteger(\"c\", 3);"), out);
        assertTrue(out.contains("unknown().putInt(\"b\", 2);"), out);
        assertTrue(out.contains("TODO(porter:unresolved-type) cannot prove receiver <untyped> is a net.minecraft.nbt.CompoundTag"), out);
    }

    @Test
    void platformMembersWithoutRulesGetTodosButJdkAndOwnCodeDoNot() {
        String code = """
                package p;
                import net.minecraft.world.entity.player.Player;
                class C {
                    int own() { return 1; }
                    void f(Player p, String s) {
                        p.jumpFromGround();
                        s.length();
                        own();
                    }
                }
                """;
        Rewriter.FileResult r = TestSupport.rewrite(List.of(), NameTable.empty(), code);
        assertTrue(r.text().contains("// TODO(porter:no-rule) net.minecraft.world.entity.player.Player#jumpFromGround(0 args)"), r.text());
        assertTrue(r.text().contains("// TODO(porter:no-rule) import net.minecraft.world.entity.player.Player"), r.text());
        assertFalse(r.todos().stream().anyMatch(t -> t.detail().contains("own") || t.detail().contains("length")), r.todos().toString());
    }

    @Test
    void libraryTypesCannotBePlatformSubtypes() {
        String code = """
                package p;
                import it.unimi.dsi.fastutil.objects.ObjectList;
                class F { void f(ObjectList<String> l) { l.add("x"); } }
                """;
        Rewriter.FileResult r = TestSupport.rewrite(TestSupport.publicRules(), NameTable.empty(), code);
        assertTrue(r.todos().isEmpty(), r.text());
        assertEquals(code, r.text());
    }

    @Test
    void shimCanBeRelocated() {
        Rewriter rw = new Rewriter(TestSupport.publicRules(), NameTable.empty(), TestSupport.facts(), Map.of(), opts(true, "my.mod.porter"));
        Rewriter.Result res = rw.rewrite(List.of(new Rewriter.SourceFile("p/D.java",
                "package p;\nimport net.minecraft.core.BlockPos;\nclass D { Object f(BlockPos p) { return p.above(); } }\n", true)));
        String out = res.files().get(0).text();
        assertTrue(out.contains("import my.mod.porter.BlockPos;"), out);
        assertTrue(res.shimClasses().contains("BlockPos"));
    }

    @Test
    void shimDisabledLeavesTodos() {
        Rewriter rw = new Rewriter(TestSupport.publicRules(), NameTable.empty(), TestSupport.facts(), Map.of(), opts(false, "dev.porter.shim"));
        String out = rw.rewrite(List.of(new Rewriter.SourceFile("p/D.java",
                "package p;\nimport net.minecraft.core.BlockPos;\nclass D { Object f(BlockPos p) { return p.above(); } }\n", true)))
                .files().get(0).text();
        assertTrue(out.contains("TODO(porter:precondition) r-1.19.2-1.7.10-type-blockpos: shim:blockpos (shim disabled)"), out);
    }

    @Test
    void rulesMustHaveUniqueIds() {
        List<Rule> dup = new ArrayList<>(TestSupport.publicRules());
        dup.add(dup.get(0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Rewriter(dup, NameTable.empty(), TestSupport.facts(), Map.of(), Rewriter.Options.defaults()));
        assertTrue(e.getMessage().contains("duplicate"));
    }
}
