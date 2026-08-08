package lib.minecraft.renderer.asset.pack;

/**
 * A content layer a pack carries, orthogonal to its {@link PackContainer} kind.
 *
 * <p>A pack may carry several at once - defrosted is a zip container with both {@link #VANILLA_CORE}
 * and {@link #OPTIFINE_RULES}; a {@code .cats} carries {@link #VANILLA_CORE} and
 * {@link #CATHARSIS_CONVENTIONS}. The detection signals and the semantics each capability gates are
 * owned by the rule layer; this pack layer only records the detected set on each
 * {@link ResourcePack}.
 */
public enum PackCapability {

    /** Standard {@code assets/<ns>/} content - the vanilla resource-pack layout. */
    VANILLA_CORE,
    /** An {@code optifine/} or {@code mcpatcher/} rule tree (CIT / CTM / color.properties). */
    OPTIFINE_RULES,
    /** Catharsis conventions - {@code config.catharsis.json}, {@code assets/skyblock/items/}, catharsis overlays. */
    CATHARSIS_CONVENTIONS

}
