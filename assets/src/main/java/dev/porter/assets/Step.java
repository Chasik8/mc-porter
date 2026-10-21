package dev.porter.assets;

/** One conversion step between two neighbouring {@link AssetVersion} nodes (always newer → older). */
public interface Step {
    AssetVersion from();

    AssetVersion to();

    /** Short human description of what changes at this boundary (shown in the report). */
    String description();

    void apply(AssetTree tree, Context ctx);
}
