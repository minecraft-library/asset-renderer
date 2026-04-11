package dev.sbs.renderer.model.asset;

import org.jetbrains.annotations.NotNull;

/**
 * A single blockstate variant entry, specifying which model to use and what whole-block rotation
 * to apply. Parsed from blockstate JSON files like {@code assets/minecraft/blockstates/furnace.json}.
 * <p>
 * The {@code x} and {@code y} rotations are multiples of 90 degrees applied to the entire model
 * before rendering. These are distinct from element-level rotations in the model JSON.
 *
 * @param modelId the namespaced model reference (e.g. {@code "minecraft:block/furnace"})
 * @param x the whole-model X rotation in degrees (0, 90, 180, or 270)
 * @param y the whole-model Y rotation in degrees (0, 90, 180, or 270)
 * @param uvlock whether UVs should be locked to the block grid during rotation
 */
public record BlockStateVariant(
    @NotNull String modelId,
    int x,
    int y,
    boolean uvlock
) {

    /**
     * Returns {@code true} when this variant applies no rotation to the model.
     */
    public boolean hasRotation() {
        return this.x != 0 || this.y != 0;
    }

}
