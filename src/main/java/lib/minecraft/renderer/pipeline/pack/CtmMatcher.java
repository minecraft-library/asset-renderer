package lib.minecraft.renderer.pipeline.pack;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Pure function that resolves a {@link CtmRule} for a given block id and face into a
 * {@link CtmResolution}.
 * <p>
 * Two entry points: {@link #resolve(CtmRule, String, String)} for context-free methods
 * (FIXED / RANDOM / REPEAT / OVERLAY / OVERLAY_FIXED) and unsupported / neighbor-based methods
 * called without a pattern (falls back to {@code tiles[0]}); and
 * {@link #resolveWithPattern(CtmRule, String, String, NeighborPattern)} for neighbor-based
 * methods (CTM, CTM_COMPACT, HORIZONTAL, VERTICAL, TOP, HORIZONTAL_VERTICAL,
 * VERTICAL_HORIZONTAL) when the caller has computed a {@link NeighborPattern}.
 * <p>
 * <b>Atlas-layout note:</b> the per-method tile index returned for neighbor-based methods reflects
 * the validated connectivity pattern (diagonals masked by their cardinals for CTM-family methods)
 * and is deterministic, but the index ordering does not match the Optifine 12x4 spritesheet
 * layout cell-for-cell. When a renderer hook lands, it owns the translation from this matcher's
 * tile index to the pack's actual atlas pixel coordinates. For packs that ship 47 individual tile
 * paths in {@code tiles=...} (rather than a single spritesheet), the matcher's index is used
 * directly against the list and the result is whatever string the pack supplied at that index;
 * verifying that against the visual expectation is the integrator's job.
 */
@UtilityClass
public class CtmMatcher {

    /**
     * Resolves the rule against a block id and face, returning the tile (and optional overlay)
     * that the renderer should sample. Convenience overload for callers without a
     * {@link NeighborPattern}; neighbor-based methods fall back to {@code tiles[0]}.
     *
     * @param rule the rule to resolve
     * @param blockId the block id being rendered, used as the random seed
     * @param baseTextureId the vanilla base texture id for the face
     * @return the resolution, or empty when the rule has no tiles
     */
    public static @NotNull Optional<CtmResolution> resolve(
        @NotNull CtmRule rule,
        @NotNull String blockId,
        @NotNull String baseTextureId
    ) {
        if (rule.tiles().isEmpty()) return Optional.empty();

        return switch (rule.method()) {
            case FIXED, REPEAT, UNSUPPORTED, CTM, CTM_COMPACT, HORIZONTAL, VERTICAL, TOP, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL ->
                Optional.of(new CtmResolution(rule.tiles().getFirst(), Optional.empty()));
            case RANDOM -> Optional.of(new CtmResolution(pickRandom(rule, blockId), Optional.empty()));
            case OVERLAY, OVERLAY_FIXED -> Optional.of(new CtmResolution(baseTextureId, Optional.of(rule.tiles().getFirst())));
        };
    }

    /**
     * Resolves the rule with neighbor information, picking the appropriate tile from the rule's
     * {@code tiles} list based on the supplied {@link NeighborPattern}.
     * <p>
     * Context-free methods ignore the pattern and delegate to {@link #resolve}. Neighbor-based
     * methods compute a tile index per their layout and return {@code rule.tiles().get(index)},
     * clamped to the list bounds when the index would overflow (rare but possible if a pack ships
     * fewer tiles than the layout expects - we fall back to the last available tile).
     *
     * @param rule the rule to resolve
     * @param blockId the block id being rendered
     * @param baseTextureId the vanilla base texture id for the face
     * @param pattern the validated neighbor pattern for this face
     * @return the resolution, or empty when the rule has no tiles
     */
    public static @NotNull Optional<CtmResolution> resolveWithPattern(
        @NotNull CtmRule rule,
        @NotNull String blockId,
        @NotNull String baseTextureId,
        @NotNull NeighborPattern pattern
    ) {
        if (rule.tiles().isEmpty()) return Optional.empty();

        if (!rule.method().isNeighborBased()) return resolve(rule, blockId, baseTextureId);

        int index = switch (rule.method()) {
            case CTM -> ctmTileIndex(pattern);
            case CTM_COMPACT -> ctmCompactTileIndex(pattern);
            case HORIZONTAL -> horizontalTileIndex(pattern);
            case VERTICAL -> verticalTileIndex(pattern);
            case TOP -> topTileIndex(pattern);
            case HORIZONTAL_VERTICAL -> horizontalVerticalTileIndex(pattern);
            case VERTICAL_HORIZONTAL -> verticalHorizontalTileIndex(pattern);
            default -> 0;
        };
        int clamped = Math.min(index, rule.tiles().size() - 1);
        return Optional.of(new CtmResolution(rule.tiles().get(clamped), Optional.empty()));
    }

    /**
     * 47-tile CTM lookup. Validates the pattern by masking each diagonal off when either of its
     * adjacent cardinals is unset (vanilla Optifine semantics: NE only counts when both N and E
     * connect), then folds the validated 8-bit value through {@link #CTM_TILE_TABLE} to get a
     * tile index in {@code [0, 46]}.
     * <p>
     * The table is built once at class-load via {@link #buildCtmTileTable} - one entry per valid
     * validated pattern, dense-numbered in the order the patterns appear when iterating
     * 0..255. This produces a stable, deterministic mapping that any caller can rely on; whether
     * it matches a particular pack's atlas layout is the caller's translation problem.
     */
    private static int ctmTileIndex(@NotNull NeighborPattern pattern) {
        int validated = validateCtmPattern(pattern.bits());
        return CTM_TILE_TABLE[validated];
    }

    /**
     * Masks diagonal bits off when either adjacent cardinal is unset. Returns the validated
     * 8-bit value.
     */
    static int validateCtmPattern(int raw) {
        boolean n = (raw & NeighborPattern.N) != 0;
        boolean e = (raw & NeighborPattern.E) != 0;
        boolean s = (raw & NeighborPattern.S) != 0;
        boolean w = (raw & NeighborPattern.W) != 0;
        int validated = (n ? NeighborPattern.N : 0)
            | (e ? NeighborPattern.E : 0)
            | (s ? NeighborPattern.S : 0)
            | (w ? NeighborPattern.W : 0);
        if ((raw & NeighborPattern.NE) != 0 && n && e) validated |= NeighborPattern.NE;
        if ((raw & NeighborPattern.SE) != 0 && s && e) validated |= NeighborPattern.SE;
        if ((raw & NeighborPattern.SW) != 0 && s && w) validated |= NeighborPattern.SW;
        if ((raw & NeighborPattern.NW) != 0 && n && w) validated |= NeighborPattern.NW;
        return validated;
    }

    /** 256-entry lookup mapping each raw 8-bit pattern (post-validation) to a tile index 0..46. */
    private static final int @NotNull [] CTM_TILE_TABLE = buildCtmTileTable();

    private static int @NotNull [] buildCtmTileTable() {
        int[] table = new int[256];
        // Walk every raw pattern, validate it, and assign each distinct validated value the next
        // available index. This produces a dense 0..46 mapping; the exact value for a given
        // pattern is whatever index it gets on first encounter during the 0..255 walk.
        java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<>();
        int next = 0;
        for (int raw = 0; raw < 256; raw++) {
            int validated = validateCtmPattern(raw);
            Integer existing = seen.get(validated);
            if (existing == null) {
                existing = next++;
                seen.put(validated, existing);
            }
            table[raw] = existing;
        }
        return table;
    }

    /**
     * 5-tile CTM_COMPACT lookup. Reduces the 47-tile space to five categories:
     * <ul>
     * <li>0: solo (no neighbors)</li>
     * <li>1: vertical edge - exactly one of N/S connected, E/W both clear</li>
     * <li>2: horizontal edge - exactly one of E/W connected, N/S both clear</li>
     * <li>3: interior - all four cardinals connected (centre of a flat field)</li>
     * <li>4: corner - any other configuration (catch-all for partial connections)</li>
     * </ul>
     */
    private static int ctmCompactTileIndex(@NotNull NeighborPattern pattern) {
        int cardinals = pattern.bits() & (NeighborPattern.N | NeighborPattern.E | NeighborPattern.S | NeighborPattern.W);
        if (cardinals == 0) return 0;
        if (cardinals == (NeighborPattern.N | NeighborPattern.E | NeighborPattern.S | NeighborPattern.W)) return 3;
        boolean n = (cardinals & NeighborPattern.N) != 0;
        boolean s = (cardinals & NeighborPattern.S) != 0;
        boolean e = (cardinals & NeighborPattern.E) != 0;
        boolean w = (cardinals & NeighborPattern.W) != 0;
        if ((n || s) && !e && !w) return 1;
        if ((e || w) && !n && !s) return 2;
        return 4;
    }

    /**
     * 4-tile HORIZONTAL lookup. Index ordering: 0=none, 1=W only, 2=E only, 3=both.
     */
    private static int horizontalTileIndex(@NotNull NeighborPattern pattern) {
        boolean w = (pattern.bits() & NeighborPattern.W) != 0;
        boolean e = (pattern.bits() & NeighborPattern.E) != 0;
        if (!w && !e) return 0;
        if (w && !e) return 1;
        if (!w) return 2;
        return 3;
    }

    /**
     * 4-tile VERTICAL lookup. Index ordering: 0=none, 1=S only, 2=N only, 3=both.
     */
    private static int verticalTileIndex(@NotNull NeighborPattern pattern) {
        boolean n = (pattern.bits() & NeighborPattern.N) != 0;
        boolean s = (pattern.bits() & NeighborPattern.S) != 0;
        if (!n && !s) return 0;
        if (s && !n) return 1;
        if (!s) return 2;
        return 3;
    }

    /**
     * 2-tile TOP lookup. Index ordering: 0=no top connection, 1=top connected.
     */
    private static int topTileIndex(@NotNull NeighborPattern pattern) {
        return (pattern.bits() & NeighborPattern.N) != 0 ? 1 : 0;
    }

    /**
     * 7-tile HORIZONTAL_VERTICAL lookup. Optifine evaluates HORIZONTAL first; only when the
     * horizontal result would be the "no horizontal connection" tile (index 0) does the vertical
     * axis contribute additional indices.
     * <ul>
     * <li>0..3: HORIZONTAL result when at least one horizontal neighbor connects</li>
     * <li>4..6: VERTICAL extension when no horizontal neighbors connect (S only / N only / both)</li>
     * </ul>
     */
    private static int horizontalVerticalTileIndex(@NotNull NeighborPattern pattern) {
        int h = horizontalTileIndex(pattern);
        if (h != 0) return h;
        int v = verticalTileIndex(pattern);
        return v == 0 ? 0 : 3 + v;
    }

    /**
     * 7-tile VERTICAL_HORIZONTAL lookup. Mirror of {@link #horizontalVerticalTileIndex} with axis
     * priority swapped: VERTICAL first, HORIZONTAL contributes only when no vertical neighbors
     * connect.
     */
    private static int verticalHorizontalTileIndex(@NotNull NeighborPattern pattern) {
        int v = verticalTileIndex(pattern);
        if (v != 0) return v;
        int h = horizontalTileIndex(pattern);
        return h == 0 ? 0 : 3 + h;
    }

    private static @NotNull String pickRandom(@NotNull CtmRule rule, @NotNull String blockId) {
        int tileCount = rule.tiles().size();
        int seed = Math.floorMod(blockId.hashCode(), Math.max(1, totalWeight(rule, tileCount)));
        int cursor = 0;
        for (int i = 0; i < tileCount; i++) {
            int weight = i < rule.weights().size() ? rule.weights().get(i) : 1;
            cursor += weight;
            if (seed < cursor) return rule.tiles().get(i);
        }
        return rule.tiles().get(tileCount - 1);
    }

    private static int totalWeight(@NotNull CtmRule rule, int tileCount) {
        int total = 0;
        for (int i = 0; i < tileCount; i++)
            total += i < rule.weights().size() ? rule.weights().get(i) : 1;
        return Math.max(total, 1);
    }

}
