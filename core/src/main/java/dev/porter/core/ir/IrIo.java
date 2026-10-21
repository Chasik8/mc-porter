package dev.porter.core.ir;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.contract.ContractViolationException;
import dev.porter.core.json.Json;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Reads and writes IR files; both directions are validated against {@code ir.schema.json}. */
public final class IrIo {
    private IrIo() {
    }

    /**
     * Reads and validates an IR file ({@code *.ir.json} or {@code *.ir.yaml}).
     *
     * @throws ContractViolationException if the file is not valid IR
     */
    public static IrDocument read(Path file) {
        List<JsonNode> docs = ContractIo.readValidated(file, Contract.IR);
        if (docs.size() != 1) {
            throw new IllegalArgumentException(file + ": expected one IR document, found " + docs.size());
        }
        return fromTree(docs.get(0));
    }

    /** Binds an already validated tree. */
    public static IrDocument fromTree(JsonNode tree) {
        return Json.json().treeToValue(tree, IrDocument.class);
    }

    /** Validates and writes an IR document as JSON. */
    public static void write(Path file, IrDocument doc) {
        ContractIo.writeJson(file, doc, Contract.IR);
    }
}
