package dev.porter.port.upfront;

import dev.porter.port.upfront.model.SymbolKey;
import java.util.List;

/**
 * One document of the bodies DB (spec 2.7): the Java body of one generated shim member, keyed by the 1.19.2 symbol.
 * {@code body} is the text between the {@code porter:body} and {@code porter:end} markers (statements, no braces);
 * {@code imports} are fully qualified names added to the generated file; {@code verified} is
 * {@code compile}, {@code scenario} or {@code none}. {@code setBody} (YAML {@code set-body}) is the body of the setter that a field key's Ext shim also has
 * ({@code // porter:body <key> set}); empty keeps the generated one.
 */
public record BodyEntry(SymbolKey key, String body, List<String> imports, String verified, String setBody) {
    /** An entry without a setter body. */
    public BodyEntry(SymbolKey key, String body, List<String> imports, String verified) {
        this(key, body, imports, verified, "");
    }

    public BodyEntry {
        setBody = setBody == null ? "" : setBody;
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        body = body == null ? "" : body;
        imports = imports == null ? List.of() : List.copyOf(imports);
        verified = verified == null ? "none" : verified;
        if (!List.of("compile", "scenario", "none").contains(verified)) {
            throw new IllegalArgumentException("verified must be compile|scenario|none for " + key + ": " + verified);
        }
    }
}
