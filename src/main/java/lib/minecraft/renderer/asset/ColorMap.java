package lib.minecraft.renderer.asset;

import dev.simplified.annotations.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (256 KiB uncompressed - 65536 pixels
 * at 4 bytes each - though pack-sourced PNGs are typically a few KiB on disk so the serialized form
 * stays small).
 * <p>
 * Equality compares {@code pixels} by element rather than by reference identity.
 *
 * @param id the namespaced colormap texture id
 * @param packId the id of the texture pack this colormap was sourced from
 * @param type which biome colormap this instance represents
 * @param pixels the raw 256x256 colormap pixels as a flat ARGB byte array, 4 bytes per pixel in
 *     row-major order
 */
@EqualsAndHashCode
public record ColorMap(
    @NotNull String id,
    @NotNull String packId,
    @NotNull Type type,
    byte @NotNull [] pixels
) {

    /**
     * Identifies which biome colormap a {@link ColorMap} holds.
     */
    public enum Type {

        /**
         * The grass colormap at {@code assets/minecraft/textures/colormap/grass.png}, sampled for
         * grass blocks, tall grass, ferns, and other grass-tinted foliage.
         */
        GRASS,

        /**
         * The foliage colormap at {@code assets/minecraft/textures/colormap/foliage.png}, sampled
         * for most leaves.
         */
        FOLIAGE,

        /**
         * The dry-foliage colormap, sampled for pale-oak foliage and a handful of dry biomes.
         */
        DRY_FOLIAGE

    }

}
