package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

/**
 * The subset of Optifine/MCPatcher CTM methods supported by the initial module build.
 * <p>
 * Only methods that can be resolved without knowing the block's neighbors are supported.
 * Neighbor-based methods are recognized by the parser and mapped to {@link #UNSUPPORTED}; those
 * rules still load but fall back to the first tile at render time, and a future PR can plug in
 * real matching without a schema change.
 */
public enum CtmMethod {

    /** Always uses {@code tiles[0]}. */
    FIXED,

    /** Deterministic pick from the tile list seeded on the block id (optionally weighted). */
    RANDOM,

    /** Position-indexed pick from a width-by-height tile grid; without position data falls back to {@code tiles[0]}. */
    REPEAT,

    /** Composites {@code tiles[0]} over the vanilla base texture via alpha blending. */
    OVERLAY,

    /** Composites {@code tiles[0]} over the vanilla base texture, ignoring position entirely. */
    OVERLAY_FIXED,

    /** A recognized but neighbor-dependent method; resolved as {@link #FIXED} until CTM gains a BlockContext. */
    UNSUPPORTED;

    /**
     * Parses an Optifine {@code method=} value into a {@link CtmMethod}. Unknown or
     * neighbor-dependent methods map to {@link #UNSUPPORTED} so rules that use them still load
     * without breaking the pack.
     *
     * @param methodString the raw string value from the properties file
     * @return the parsed method
     */
    public static @NotNull CtmMethod parse(@NotNull String methodString) {
        return switch (methodString.trim().toLowerCase()) {
            case "fixed" -> FIXED;
            case "random" -> RANDOM;
            case "repeat" -> REPEAT;
            case "overlay" -> OVERLAY;
            case "overlay_fixed" -> OVERLAY_FIXED;
            default -> UNSUPPORTED;
        };
    }

}
