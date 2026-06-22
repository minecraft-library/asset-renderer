package lib.minecraft.renderer.asset;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (1 MiB uncompressed worst case, but
 * pack-sourced PNGs are typically a few KiB on disk so the serialized form stays small).
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
     * Which biome colormap this row represents.
     */
    private final @NotNull Type type;

    /**
     * The raw 256x256 colormap pixels as a flat ARGB byte array.
     */
    private final byte @NotNull [] pixels;

    /**
     * Identifies which biome colormap a row represents.
     */
    public enum Type {

        /**
         * The grass colormap at {@code assets/minecraft/textures/colormap/grass.png}.
         */
        GRASS,

        /**
         * The foliage colormap.
         */
        FOLIAGE,

        /**
         * The dry foliage colormap.
         */
        DRY_FOLIAGE

    }

}
