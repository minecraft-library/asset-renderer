package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

/**
 * The Optifine / MCPatcher CTM method that drives a {@link CtmRule}'s tile-selection logic.
 * <p>
 * Methods split into two families:
 * <ul>
 * <li><b>Context-free</b> ({@link #FIXED}, {@link #RANDOM}, {@link #REPEAT}, {@link #OVERLAY},
 * {@link #OVERLAY_FIXED}) - resolvable from just {@code (rule, blockId, baseTextureId)} via
 * {@link CtmMatcher#resolve}.</li>
 * <li><b>Neighbor-based</b> ({@link #CTM}, {@link #CTM_COMPACT}, {@link #HORIZONTAL},
 * {@link #VERTICAL}, {@link #TOP}, {@link #HORIZONTAL_VERTICAL}, {@link #VERTICAL_HORIZONTAL}) -
 * pick a tile by inspecting the face's surrounding cells. Resolvable through
 * {@link CtmMatcher#resolveWithPattern} when the caller supplies a {@link NeighborPattern};
 * the convenience overload {@link CtmMatcher#resolve} returns {@code tiles[0]} as a fallback for
 * callers without neighbor data.</li>
 * </ul>
 * Unrecognised method strings parse to {@link #UNSUPPORTED} so a malformed pack still loads.
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

    /** Standard 47-tile connected texture map driven by the 8 surrounding neighbors per face. */
    CTM,

    /** Reduced 5-tile connected texture map (centre / edge / corner / inner-corner / no-neighbour). */
    CTM_COMPACT,

    /** 4-tile horizontal connection: {@code none / left / right / both}. */
    HORIZONTAL,

    /** 4-tile vertical connection: {@code none / up / down / both}. */
    VERTICAL,

    /** 2-tile top-only connection: {@code no-top / top}. */
    TOP,

    /** Combined 7-tile horizontal-then-vertical connection. */
    HORIZONTAL_VERTICAL,

    /** Combined 7-tile vertical-then-horizontal connection. */
    VERTICAL_HORIZONTAL,

    /** Method string was unrecognised; falls back to {@link #FIXED} semantics in the matcher. */
    UNSUPPORTED;

    /**
     * Parses an Optifine {@code method=} value into a {@link CtmMethod}. Recognised method names
     * map to their concrete enum value; unknown strings map to {@link #UNSUPPORTED} so rules with
     * malformed methods still load.
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
            case "ctm" -> CTM;
            case "ctm_compact" -> CTM_COMPACT;
            case "horizontal" -> HORIZONTAL;
            case "vertical" -> VERTICAL;
            case "top" -> TOP;
            case "horizontal+vertical", "horizontal_vertical" -> HORIZONTAL_VERTICAL;
            case "vertical+horizontal", "vertical_horizontal" -> VERTICAL_HORIZONTAL;
            default -> UNSUPPORTED;
        };
    }

    /**
     * Returns whether this method requires a {@link NeighborPattern} to resolve. Context-free
     * methods ignore the pattern; neighbor-based methods consult it for tile selection.
     *
     * @return {@code true} for the seven neighbor-based methods, {@code false} otherwise
     */
    public boolean isNeighborBased() {
        return switch (this) {
            case CTM, CTM_COMPACT, HORIZONTAL, VERTICAL, TOP, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL -> true;
            default -> false;
        };
    }

}
