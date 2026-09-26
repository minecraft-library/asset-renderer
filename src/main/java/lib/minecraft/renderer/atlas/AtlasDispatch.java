package lib.minecraft.renderer.atlas;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The block ids an atlas block pass hands to a renderer other than the block one.
 * <p>
 * Each set names a subject whose vanilla block model carries only a {@code particle} texture - or,
 * for {@code end_gateway}, no block-model file at all - so the standard block-model path produces a
 * blank tile. The atlas intercepts these ids and dispatches them to the dedicated renderer that
 * bakes the tile off its textures instead.
 */
@UtilityClass
@Parity(subject = Subject.ATLAS)
@Parity(claim = "atlas-unhashable", mode = Mode.SUPPRESS)
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class AtlasDispatch {

    /**
     * Block ids that render through {@code FluidRenderer} instead of {@code BlockRenderer}.
     * Vanilla {@code block/water.json} + {@code block/lava.json} carry only a {@code particle}
     * texture - they have no elements, so the standard block-model path produces a blank tile.
     * The atlas dispatches these ids to the flat fluid face so each fluid emits a still-texture icon.
     */
    public static final @NotNull Set<String> FLUID_BLOCK_IDS = Set.of(
        "minecraft:water", "minecraft:lava"
    );

    /**
     * Block ids that render through {@code PortalRenderer} instead of {@code BlockRenderer}.
     * Vanilla {@code block/end_portal.json} carries only a {@code particle} texture and
     * {@code end_gateway} has no block-model file at all, so the standard block-model path
     * produces a blank tile for both. The atlas dispatches these ids to the flat portal face so
     * each portal emits a baked parallax star-field tile.
     */
    public static final @NotNull Set<String> PORTAL_BLOCK_IDS = Set.of(
        "minecraft:end_portal", "minecraft:end_gateway"
    );

}
