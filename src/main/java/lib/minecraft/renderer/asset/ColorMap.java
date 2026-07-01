package lib.minecraft.renderer.asset;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (256 KiB uncompressed - 65536 pixels
 * at 4 bytes each - though pack-sourced PNGs are typically a few KiB on disk so the serialized form
 * stays small).
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class ColorMap {

    /**
     * The namespaced colormap texture id.
     */
    private final @NotNull String id;

    /**
     * The id of the texture pack this colormap was sourced from.
     */
    private final @NotNull String packId;

    /**
     * Which biome colormap this instance represents.
     */
    private final @NotNull Type type;

    /**
     * The raw 256x256 colormap pixels as a flat ARGB byte array, 4 bytes per pixel in row-major order.
     */
    private final byte @NotNull [] pixels;

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
