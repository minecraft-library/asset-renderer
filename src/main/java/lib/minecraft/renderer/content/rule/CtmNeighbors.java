package lib.minecraft.renderer.content.rule;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.rule.CtmMethod;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.asset.rule.TileRef;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Selects the tile a matched CTM rule contributes to an isolated block - the no-neighbor branch of
 * Connected Textures, which is the only branch this renderer resolves: it draws a single subject with
 * nothing beside it, so no face ever has a neighbor to connect to.
 *
 * <p>For every non-overlay method the isolated tile is {@code tiles[0]} - the OptiFine template's
 * all-borders / standalone tile, which coincides with grid cell {@code (0, 0)} for {@code repeat} and
 * the single tile for {@code fixed}/{@code top}. {@code random} instead takes a deterministic pick over
 * the tile list; overlay methods composite only on a neighbor transition and so contribute nothing to
 * an isolated subject (they resolve to empty and never replace the base).
 *
 * <p>The {@code random} pick is seeded from {@code variantSeed} rather than a world block position
 * (which an icon has none of) - a deliberate, documented divergence from OptiFine's position seeding;
 * per-tile {@code weights=} are not modelled, so the pick is uniform over the tile list.
 */
@Parity(claim = "index-resolution")
@UtilityClass
@Parity(claim = "pack-rule-layer")
@Parity(subject = {Subject.BLOCK, Subject.ITEM, Subject.MENU})
public class CtmNeighbors {

    /**
     * Selects the isolated tile a matched non-overlay rule contributes, or empty for an overlay rule,
     * a rule with no tiles, or a {@code random} rule whose seed is folded over an empty list.
     *
     * @param rule the matched CTM rule
     * @param variantSeed the deterministic seed for a {@code random} pick (a world-positionless
     *     convention, e.g. the subject id hash)
     * @return the selected tile reference, or empty when the rule contributes nothing
     */
    public static @NotNull Optional<TileRef> select(@NotNull CtmRule rule, long variantSeed) {
        if (rule.method().isOverlay() || rule.tiles().isEmpty()) return Optional.empty();
        return isolatedTile(rule, variantSeed);
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
