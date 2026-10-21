package dev.porter.testkit.runner;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/** IR nodes the testkit needs (multiblock for build_multiblock, menu for GUI checks), read from IR files/dirs. */
public final class IrIndex {
    private final Map<String, JsonNode> multiblocks = new LinkedHashMap<>();
    private final Map<String, JsonNode> menus = new LinkedHashMap<>();

    public static IrIndex read(List<Path> paths) {
        IrIndex ix = new IrIndex();
        if (paths == null) {
            return ix;
        }
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(f -> Contract.bySuffix(f.getFileName().toString()).orElse(null) == Contract.IR).sorted().forEach(ix::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                ix.add(p);
            }
        }
        return ix;
    }

    private void add(Path file) {
        for (JsonNode doc : ContractIo.readValidated(file, Contract.IR)) {
            for (JsonNode n : doc.path("nodes")) {
                String kind = n.path("kind").asString();
                if (kind.equals("multiblock")) {
                    multiblocks.put(n.path("id").asString(), n);
                } else if (kind.equals("menu")) {
                    menus.put(n.path("id").asString(), n);
                }
            }
        }
    }

    public JsonNode multiblock(String id) {
        return multiblocks.get(id);
    }

    public JsonNode menu(String id) {
        return menus.get(id);
    }
}
