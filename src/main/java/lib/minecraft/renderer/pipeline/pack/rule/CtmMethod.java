package lib.minecraft.renderer.pipeline.pack.rule;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The OptiFine / MCPatcher Connected Textures method (07-optifine §3) - all fifteen grammar constants,
 * with the {@code overlay_*} family first-class rather than folded to an {@code UNSUPPORTED} sentinel
 * (07-optifine defect #6). CTM renders nothing in this headless renderer (locked decision 4), so a
 * method is parse-and-store only; the constant exists to validate tile counts and pin the no-neighbor
 * table on {@link CtmNeighborResolver}.
 */
public enum CtmMethod {

    /** Standard 47-tile connected map. */
    CTM,
    /** Reduced 5-tile connected map. */
    CTM_COMPACT,
    /** 4-tile horizontal connection. */
    HORIZONTAL,
    /** 4-tile vertical connection. */
    VERTICAL,
    /** 7-tile horizontal-then-vertical connection. */
    HORIZONTAL_VERTICAL,
    /** 7-tile vertical-then-horizontal connection. */
    VERTICAL_HORIZONTAL,
    /** 1-tile top-connection variant. */
    TOP,
    /** Weighted random pick from the tile list. */
    RANDOM,
    /** Position-indexed pick from a {@code width x height} grid. */
    REPEAT,
    /** Always the single tile. */
    FIXED,
    /** Base-preserving overlay whose tiles composite on top. */
    OVERLAY,
    /** Overlay driven by 47-tile connectivity. */
    OVERLAY_CTM,
    /** Overlay driven by a weighted random pick. */
    OVERLAY_RANDOM,
    /** Overlay driven by a {@code width x height} grid. */
    OVERLAY_REPEAT,
    /** Overlay of a single fixed tile. */
    OVERLAY_FIXED;

    /**
     * Parses a {@code method=} value. Returns empty for an unrecognised token so the CTM parser can
     * reject the whole rule (fail-closed, D3.3) instead of falling back to a base-replacing method.
     *
     * @param raw the raw method value
     * @return the parsed method, or empty when unrecognised
     */
    public static @NotNull Optional<CtmMethod> parse(@NotNull String raw) {
        return Optional.ofNullable(switch (raw.trim().toLowerCase()) {
            case "ctm", "glass" -> CTM;
            case "ctm_compact" -> CTM_COMPACT;
            case "horizontal" -> HORIZONTAL;
            case "vertical" -> VERTICAL;
            case "horizontal+vertical", "horizontal_vertical" -> HORIZONTAL_VERTICAL;
            case "vertical+horizontal", "vertical_horizontal" -> VERTICAL_HORIZONTAL;
            case "top" -> TOP;
            case "random" -> RANDOM;
            case "repeat" -> REPEAT;
            case "fixed" -> FIXED;
            case "overlay" -> OVERLAY;
            case "overlay_ctm" -> OVERLAY_CTM;
            case "overlay_random" -> OVERLAY_RANDOM;
            case "overlay_repeat" -> OVERLAY_REPEAT;
            case "overlay_fixed" -> OVERLAY_FIXED;
            default -> null;
        });
    }

    /**
     * The tile count this method's grammar requires (07-optifine §5), for the fail-closed per-method
     * validation. Methods with no fixed count ({@link #RANDOM}, {@link #OVERLAY_RANDOM}, {@link #OVERLAY})
     * return {@code -1} - the parser then requires at least one tile.
     *
     * @param repeatWidth the {@code width} for {@link #REPEAT} / {@link #OVERLAY_REPEAT}
     * @param repeatHeight the {@code height} for {@link #REPEAT} / {@link #OVERLAY_REPEAT}
     * @return the required tile count, or {@code -1} for the count-agnostic methods
     */
    public int expectedTileCount(int repeatWidth, int repeatHeight) {
        return switch (this) {
            case CTM, OVERLAY_CTM -> 47;
            case CTM_COMPACT -> 5;
            case HORIZONTAL, VERTICAL -> 4;
            case HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL -> 7;
            case TOP, FIXED, OVERLAY_FIXED -> 1;
            case REPEAT, OVERLAY_REPEAT -> repeatWidth * repeatHeight;
            case RANDOM, OVERLAY_RANDOM, OVERLAY -> -1;
        };
    }

}
