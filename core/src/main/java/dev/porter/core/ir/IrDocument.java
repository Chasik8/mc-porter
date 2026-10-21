package dev.porter.core.ir;

import java.util.List;
import java.util.stream.Stream;

/** Root of an IR file ({@code contracts/ir.schema.json}). */
public record IrDocument(int schemaVersion, Source source, List<IrNode> nodes) {
    public IrDocument {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** Document at the lowest schemaVersion that can carry these nodes (1 unless an RFC 003 field is used). */
    public IrDocument(Source source, List<IrNode> nodes) {
        this(versionFor(nodes), source, nodes);
    }

    /** 2 if any node uses an RFC 003 field (recipe IO for viewers, hidden objects), else 1. */
    public static int versionFor(List<IrNode> nodes) {
        for (IrNode n : nodes == null ? List.<IrNode>of() : nodes) {
            boolean v2 = switch (n) {
                case IrNode.Recipe r -> r.fluidIngredients() != null || r.fluidResults() != null || r.duration() != null
                        || r.machines() != null || r.notes() != null
                        || r.result() != null && r.result().chance() != null
                        || r.results() != null && r.results().stream().anyMatch(s -> s.chance() != null);
                case IrNode.Item i -> i.hidden() != null;
                case IrNode.Block b -> b.hidden() != null;
                default -> false;
            };
            if (v2) {
                return dev.porter.core.contract.Contract.IR_SCHEMA_VERSION;
            }
        }
        return 1;
    }

    /** @param version platform version the IR was extracted from; modId and tool are optional */
    public record Source(String version, String modId, String tool) {
    }

    /** All nodes of one concept type, e.g. {@code doc.nodes(IrNode.Block.class)}. */
    public <T extends IrNode> Stream<T> nodes(Class<T> type) {
        return nodes.stream().filter(type::isInstance).map(type::cast);
    }
}
