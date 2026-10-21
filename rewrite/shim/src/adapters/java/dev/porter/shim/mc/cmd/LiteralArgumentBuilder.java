// porter:adapter (generic) - brigadier LiteralArgumentBuilder.
package dev.porter.shim.mc.cmd;

public class LiteralArgumentBuilder<S> extends ArgumentBuilder<S, LiteralArgumentBuilder<S>> {
    private final String literal;

    protected LiteralArgumentBuilder(String literal) { this.literal = literal; }

    public static <S> LiteralArgumentBuilder<S> literal(String name) { return new LiteralArgumentBuilder<>(name); }

    public String getLiteral() { return literal; }

    @Override
    protected LiteralArgumentBuilder<S> getThis() { return this; }
}
