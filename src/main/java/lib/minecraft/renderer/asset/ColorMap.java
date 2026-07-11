package lib.minecraft.renderer.asset;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (256 KiB uncompressed - 65536 pixels
 * at 4 bytes each - though pack-sourced PNGs are typically a few KiB on disk so the serialized form
 * stays small).
 *
 * @param id the namespaced colormap texture id
 * @param packId the id of the texture pack this colormap was sourced from
 * @param type which biome colormap this instance represents
 * @param pixels the raw 256x256 colormap pixels as a flat ARGB byte array, 4 bytes per pixel in
 *     row-major order
 */
public record ColorMap(
    @NotNull String id,
    @NotNull String packId,
    @NotNull Type type,
    byte @NotNull [] pixels
) {

    /**
     * {@inheritDoc}
     *
     * <p>Overrides the record's generated {@code equals} so {@code pixels} compares by element
     * ({@link Arrays#equals}) rather than by reference identity.
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ColorMap that = (ColorMap) o;
        return this.type == that.type
            && Objects.equals(this.id, that.id)
            && Objects.equals(this.packId, that.packId)
            && Arrays.equals(this.pixels, that.pixels);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overrides the record's generated {@code hashCode} so {@code pixels} hashes by content
     * ({@link Arrays#hashCode}), staying consistent with {@link #equals}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.id, this.packId, this.type, Arrays.hashCode(this.pixels));
    }

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
