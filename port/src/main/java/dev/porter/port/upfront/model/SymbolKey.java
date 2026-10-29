package dev.porter.port.upfront.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity of one 1.19.2 symbol, as written in api-inventory, match tables and the bodies DB (contracts/rfc/011).
 *
 * <p>Text form. Owners use mojmap names with dots and {@code $} for nested classes; descriptors are JVM descriptors:
 * <pre>
 * type:net.minecraft.world.item.ItemStack
 * method:net.minecraft.world.level.Level#getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;
 * field:net.minecraft.world.entity.Entity#tickCount:I
 * </pre>
 * For {@link Kind#TYPE} the owner is the class and {@code name} and {@code desc} are empty.
 * Constructors are methods named {@code <init>}.
 */
public record SymbolKey(Kind kind, String owner, String name, String desc) {

    private static final Pattern METHOD_DESC = Pattern.compile(
            "\\((?:\\[*(?:[BCDFIJSZ]|L[^;/]+(?:/[^;/]+)*;))*\\)(?:V|\\[*(?:[BCDFIJSZ]|L[^;/]+(?:/[^;/]+)*;))");
    private static final Pattern FIELD_DESC = Pattern.compile(
            "\\[*(?:[BCDFIJSZ]|L[^;/]+(?:/[^;/]+)*;)");

    public SymbolKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        name = name == null ? "" : name;
        desc = desc == null ? "" : desc;
        if (owner.isEmpty() || owner.indexOf('/') >= 0 || owner.indexOf('#') >= 0 || owner.indexOf(':') >= 0
                || owner.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("owner must be a dotted binary class name: '" + owner + "'");
        }
        switch (kind) {
            case TYPE -> {
                if (!name.isEmpty() || !desc.isEmpty()) {
                    throw new IllegalArgumentException("a TYPE key has no name or descriptor: " + owner);
                }
            }
            case METHOD -> {
                if (name.isEmpty() || name.indexOf('(') >= 0 || name.indexOf('#') >= 0) {
                    throw new IllegalArgumentException("bad method name in " + owner + ": '" + name + "'");
                }
                if (!METHOD_DESC.matcher(desc).matches()) {
                    throw new IllegalArgumentException("bad method descriptor in " + owner + "#" + name + ": " + desc);
                }
            }
            case FIELD -> {
                if (name.isEmpty() || name.indexOf(':') >= 0 || name.indexOf('#') >= 0) {
                    throw new IllegalArgumentException("bad field name in " + owner + ": '" + name + "'");
                }
                if (!FIELD_DESC.matcher(desc).matches()) {
                    throw new IllegalArgumentException("bad field descriptor in " + owner + "#" + name + ": " + desc);
                }
            }
        }
    }

    public static SymbolKey type(String owner) {
        return new SymbolKey(Kind.TYPE, owner, "", "");
    }

    public static SymbolKey method(String owner, String name, String desc) {
        return new SymbolKey(Kind.METHOD, owner, name, desc);
    }

    public static SymbolKey field(String owner, String name, String desc) {
        return new SymbolKey(Kind.FIELD, owner, name, desc);
    }

    /** Parses the text form; throws {@link IllegalArgumentException} on anything else. */
    @JsonCreator
    public static SymbolKey parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("symbol key is null");
        }
        if (text.startsWith("type:")) {
            return type(text.substring("type:".length()));
        }
        if (text.startsWith("method:")) {
            String rest = text.substring("method:".length());
            int hash = rest.indexOf('#');
            if (hash < 0) {
                throw new IllegalArgumentException("method key without '#': " + text);
            }
            String member = rest.substring(hash + 1);
            int paren = member.indexOf('(');
            if (paren < 0) {
                throw new IllegalArgumentException("method key without descriptor: " + text);
            }
            return method(rest.substring(0, hash), member.substring(0, paren), member.substring(paren));
        }
        if (text.startsWith("field:")) {
            String rest = text.substring("field:".length());
            int hash = rest.indexOf('#');
            if (hash < 0) {
                throw new IllegalArgumentException("field key without '#': " + text);
            }
            String member = rest.substring(hash + 1);
            int colon = member.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("field key without descriptor: " + text);
            }
            return field(rest.substring(0, hash), member.substring(0, colon), member.substring(colon + 1));
        }
        throw new IllegalArgumentException("unknown symbol key kind: " + text);
    }

    @Override
    @JsonValue
    public String toString() {
        return switch (kind) {
            case TYPE -> "type:" + owner;
            case METHOD -> "method:" + owner + "#" + name + desc;
            case FIELD -> "field:" + owner + "#" + name + ":" + desc;
        };
    }
}
