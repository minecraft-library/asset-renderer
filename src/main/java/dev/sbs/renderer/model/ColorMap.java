package dev.sbs.renderer.model;

import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A 256x256 biome colormap, stored as a raw ARGB byte array (1 MiB uncompressed worst case, but
 * pack-sourced PNGs are typically a few KiB on disk so the serialized form stays small).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "renderer_color_maps")
public class ColorMap implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "pack_id", nullable = false)
    private @NotNull String packId = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private @NotNull Type type = Type.GRASS;

    @Lob
    @Column(name = "pixels", nullable = false)
    private byte @NotNull [] pixels = new byte[0];

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ColorMap that = (ColorMap) o;
        return Objects.equals(this.getId(), that.getId())
            && Objects.equals(this.getPackId(), that.getPackId())
            && this.getType() == that.getType()
            && java.util.Arrays.equals(this.getPixels(), that.getPixels());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(this.getId(), this.getPackId(), this.getType());
        result = 31 * result + java.util.Arrays.hashCode(this.getPixels());
        return result;
    }

    /**
     * Identifies which biome colormap a row represents.
     */
    public enum Type {

        /** The grass colormap at {@code assets/minecraft/textures/colormap/grass.png}. */
        GRASS,

        /** The foliage colormap. */
        FOLIAGE,

        /** The dry foliage colormap. */
        DRY_FOLIAGE

    }

}
