package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.rewrite.engine.Rewriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostProcessTest {
    private static Rewriter.FileResult file(String path, String text) {
        return new Rewriter.FileResult(path, text, Map.of(), List.of(), false);
    }

    @Test
    void relocatesPortedClassesOnly() {
        Rewriter.FileResult a = file("com/ex/core/block/Barrel.java", """
                package com.ex.core.block;

                import com.ex.core.tile.BarrelTile;
                import com.ex.core.util.Helper;

                public class Barrel {
                    com.ex.core.tile.BarrelTile t;
                    Helper h;
                }
                """);
        Rewriter.FileResult b = file("com/ex/core/tile/BarrelTile.java", "package com.ex.core.tile;\n\npublic class BarrelTile {}\n");
        List<PostProcess.Relocation> rel = List.of(PostProcess.Relocation.parse("com.ex.=addon.ported."));
        Map<String, String> table = PostProcess.relocationTable(List.of(a, b), rel);
        assertEquals(Map.of("com.ex.core.block.Barrel", "addon.ported.core.block.Barrel",
                "com.ex.core.tile.BarrelTile", "addon.ported.core.tile.BarrelTile"), table);
        Rewriter.FileResult r = PostProcess.relocate(a, table);
        assertEquals("addon/ported/core/block/Barrel.java", r.path());
        assertTrue(r.text().contains("package addon.ported.core.block;"));
        assertTrue(r.text().contains("import addon.ported.core.tile.BarrelTile;"));
        assertTrue(r.text().contains("addon.ported.core.tile.BarrelTile t;"));
        // not ported: unchanged, stays a compile error to fix, never redirected
        assertTrue(r.text().contains("import com.ex.core.util.Helper;"));
        assertEquals(3, r.applied().get("porter:relocate"));
        // deterministic
        assertEquals(r.text(), PostProcess.relocate(a, table).text());
    }

    @Test
    void appliesStubSuperclassAndConstructor() {
        PostProcess.Stub stub = PostProcess.parseStub("addon.ported.tile.BarrelTile", """
                // porter:stub
                package addon.ported.tile;

                import addon.gen.tile.BarrelTileBase;

                public class BarrelTile extends BarrelTileBase {
                    public BarrelTile() {
                    }
                }
                """);
        assertEquals("addon.gen.tile.BarrelTileBase", stub.superclass());
        Rewriter.FileResult f = file("addon/ported/tile/BarrelTile.java", """
                package addon.ported.tile;

                import com.ex.core.BaseTileEntity;

                public class BarrelTile extends BaseTileEntity implements Runnable {
                    int x;

                    public BarrelTile(BlockPos pos, BlockState state) {
                        super(pos, state);
                    }
                }
                """);
        Rewriter.FileResult r = PostProcess.stubContract(f, Map.of(stub.fqn(), stub));
        String t = r.text();
        assertTrue(t.contains("public class BarrelTile extends BarrelTileBase /* porter:stub-contract was: extends BaseTileEntity */ implements Runnable"), t);
        assertTrue(t.contains("import addon.gen.tile.BarrelTileBase;"), t);
        assertFalse(t.contains("import com.ex.core.BaseTileEntity;"), t);
        assertTrue(t.contains("public BarrelTile() {"), t);
        assertTrue(t.contains("// TODO(porter:stub-contract) constructor signature differs"), t);
        assertEquals(1, r.todos().size());
        assertEquals("stub-contract", r.todos().get(0).reason());
        // idempotent on the contract: a second pass adds nothing
        Rewriter.FileResult again = PostProcess.stubContract(r, Map.of(stub.fqn(), stub));
        assertEquals(1, again.text().split("public BarrelTile\\(\\)", -1).length - 1);
    }

    @Test
    void cleansImportsLeftByRewrittenUses() {
        Rewriter.FileResult f = file("a/B.java", """
                package a;

                // TODO(porter:removed) import old.world.Effects
                import old.world.Effects;
                // TODO(porter:no-rule) import old.util.StackUtil
                import old.util.StackUtil;
                // TODO(porter:no-rule) import old.util.Keep
                import old.util.Keep;
                import java.util.List;
                import java.util.List;
                import neu.util.StackUtil;

                class B {
                    // Effects only in a comment
                    Object x = StackUtil.getOrCreate();
                    Keep k;
                    List<String> l;
                }
                """);
        Rewriter.FileResult r = PostProcess.cleanImports(f);
        String t = r.text();
        assertFalse(t.contains("old.world.Effects"), t);
        assertFalse(t.contains("import old.util.StackUtil;"), t);
        assertTrue(t.contains("import neu.util.StackUtil;"), t);
        assertTrue(t.contains("import old.util.Keep;"), t);
        assertTrue(t.contains("// TODO(porter:no-rule) import old.util.Keep"), t);
        assertEquals(1, t.split("import java.util.List;", -1).length - 1, t);
        assertEquals(3, r.applied().get("porter:import-cleanup"));
    }

    @Test
    void sharedStubConstructorWithParameters() {
        PostProcess.Stub stub = PostProcess.parseStub("a.MugItem", """
                package a;

                import gen.MugItemSharedItemBase;

                public class MugItem extends MugItemSharedItemBase {
                    public MugItem(String registryName, String unlocalizedName, String textureName) {
                        super(registryName, unlocalizedName, textureName);
                    }
                }
                """);
        Rewriter.FileResult f = file("a/MugItem.java", "package a;\n\npublic class MugItem extends HostDrink {\n    public MugItem(String name, IDrinkableFluid fluid) {\n        super(name, fluid);\n    }\n}\n");
        String t = PostProcess.stubContract(f, Map.of(stub.fqn(), stub)).text();
        assertTrue(t.contains("public MugItem(String registryName, String unlocalizedName, String textureName) {"), t);
        assertTrue(t.contains("super(registryName, unlocalizedName, textureName);"), t);
        assertEquals(List.of("String", "int[]"), PostProcess.paramTypes("final String a, int... b"));
        // the 1.19.2 superclass is ported too: kept, contract noted
        Rewriter.FileResult kept = PostProcess.stubContract(f, Map.of(stub.fqn(), stub), java.util.Set.of("a.HostDrink"));
        assertTrue(kept.text().contains("public class MugItem extends HostDrink {"), kept.text());
        assertTrue(kept.text().contains("// TODO(porter:stub-contract) superclass a.HostDrink is ported too"), kept.text());
        assertFalse(kept.text().contains("import gen.MugItemSharedItemBase;"), kept.text());
    }
}
