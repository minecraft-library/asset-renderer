package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.renderer.parity.Parity;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Selects the tile a matched CTM rule contributes for a given neighbor occupancy - the isolated
 * (no-neighbor) branch of Connected Textures, resolved for the single subject this headless renderer
 * draws.
 *
 * <p>Only {@link CtmNeighborhood#ISOLATED} is ever supplied, so only the no-connection resolution is
 * implemented: for every non-overlay method the isolated tile is {@code tiles[0]} - the OptiFine
 * template's all-borders / standalone tile, which coincides with grid cell {@code (0, 0)} for
 * {@code repeat} and the single tile for {@code fixed}/{@code top}. {@code random} instead takes a
 * deterministic pick over the tile list; overlay methods composite only on a neighbor transition and
 * so contribute nothing to an isolated subject (they resolve to empty and never replace the base).
 *
 * <p>The {@code random} pick is seeded from {@code variantSeed} rather than a world block position
 * (which an icon has none of) - a deliberate, documented divergence from OptiFine's position seeding;
 * per-tile {@code weights=} are not modelled, so the pick is uniform over the tile list.
 *
 * <p>The {@code switch} over {@link CtmNeighborhood} is exhaustive against its sealed permits: a
 * future {@code Connected} occupancy would force a transition-table branch here at compile time.
 */
@Parity(claim = "index-resolution")
@UtilityClass
public class CtmNeighborResolver {

    /**
     * Selects the isolated tile a matched non-overlay rule contributes, or empty for an overlay rule,
     * a rule with no tiles, or a {@code random} rule whose seed is folded over an empty list.
     *
     * @param rule the matched CTM rule
     * @param neighborhood the neighbor occupancy - always {@link CtmNeighborhood#ISOLATED} today
     * @param variantSeed the deterministic seed for a {@code random} pick (a world-positionless
     *     convention, e.g. the subject id hash)
     * @return the selected tile reference, or empty when the rule contributes nothing
     */
    public static @NotNull Optional<TileRef> select(
        @NotNull CtmRule rule, @NotNull CtmNeighborhood neighborhood, long variantSeed) {
        if (rule.method().isOverlay() || rule.tiles().isEmpty()) return Optional.empty();
        return switch (neighborhood) {
            case CtmNeighborhood.Isolated ignored -> isolatedTile(rule, variantSeed);
        };
    }

    /**
     * The no-neighbor slot - {@code tiles[0]} for every method except {@code random}'s seeded pick.
     */
    private static @NotNull Optional<TileRef> isolatedTile(@NotNull CtmRule rule, long variantSeed) {
        if (rule.method() == CtmMethod.RANDOM)
            return Optional.of(rule.tiles().get(Math.floorMod(variantSeed, rule.tiles().size())));
        return Optional.of(rule.tiles().getFirst());
    }

}
