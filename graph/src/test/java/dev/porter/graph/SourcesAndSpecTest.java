package dev.porter.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.graph.load.Sources;
import dev.porter.graph.query.SymbolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourcesAndSpecTest {
    @Test
    void bundledManifestCoversTheChain() {
        Sources s = Sources.bundled();
        assertEquals(List.of("1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.7.10"), s.versions().stream().map(Sources.Version::id).toList());
        for (Sources.Version v : s.versions()) {
            assertTrue(v.jar().sha1().matches("[0-9a-f]{40}"), v.id());
            assertTrue(v.jar().url().startsWith("https://"), v.id());
            v.mappings().forEach(m -> assertTrue(m.sha1().matches("[0-9a-f]{40}") && m.url().startsWith("https://"), m.name()));
        }
        // the stitch point has both name worlds
        Sources.Version v1165 = s.version("1.16.5").orElseThrow();
        assertTrue(v1165.has("proguard") && v1165.has("tsrg") && v1165.has("mcp-csv"));
        assertTrue(s.version("1.19.2").orElseThrow().has("parchment"));
    }

    @Test
    void specParsing() {
        SymbolSpec c = SymbolSpec.parse("net.minecraft.world.level.Level");
        assertNull(c.member());
        SymbolSpec m = SymbolSpec.parse("net/minecraft/world/level/Level#getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;");
        assertEquals("net.minecraft.world.level.Level", m.owner());
        assertEquals("getBlockEntity", m.member());
        assertTrue(m.desc().startsWith("(Lnet/minecraft/core/BlockPos;)"));
        SymbolSpec p = SymbolSpec.parse("Level#setBlock(BlockPos, BlockState, int)");
        assertEquals(List.of("BlockPos", "BlockState", "int"), p.paramTypes());
        assertEquals(List.of(), SymbolSpec.parse("A#b()").paramTypes());
    }
}
