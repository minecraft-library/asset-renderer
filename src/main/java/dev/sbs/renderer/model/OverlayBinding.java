package dev.sbs.renderer.model;

import dev.simplified.persistence.JpaModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Binds an item id to a pair of base + overlay textures plus a colour source, enabling the item
 * renderer to produce tinted variants of leather armour, potions, spawn eggs, firework stars, and
 * tipped arrows without scattering the logic through every item entry.
 */
@Getter
@Entity
@Table(name = "renderer_overlay_bindings")
public class OverlayBinding implements JpaModel {

    @Id
    @Column(name = "id", nullable = false)
    private @NotNull String id = "";

    @Column(name = "item_id", nullable = false)
    private @NotNull String itemId = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private @NotNull Kind kind = Kind.LEATHER;

    @Column(name = "base_texture", nullable = false)
    private @NotNull String baseTexture = "";

    @Column(name = "overlay_texture", nullable = false)
    private @NotNull String overlayTexture = "";

    @Column(name = "default_color")
    private @NotNull Optional<Integer> defaultColor = Optional.empty();

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        OverlayBinding that = (OverlayBinding) o;
        return Objects.equals(this.getId(), that.getId())
            && Objects.equals(this.getItemId(), that.getItemId())
            && this.getKind() == that.getKind()
            && Objects.equals(this.getBaseTexture(), that.getBaseTexture())
            && Objects.equals(this.getOverlayTexture(), that.getOverlayTexture())
            && Objects.equals(this.getDefaultColor(), that.getDefaultColor());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getId(), this.getItemId(), this.getKind(), this.getBaseTexture(), this.getOverlayTexture(), this.getDefaultColor());
    }

    /**
     * The overlay kind determines which colour source and blend rule the item renderer applies.
     */
    public enum Kind {

        /** Leather armour: base layer tinted by a user-selected colour, shine layer left alone. */
        LEATHER,

        /** Potions: potion overlay tinted by potion colour, base glass bottle left alone. */
        POTION,

        /** Firework star: center star tinted. */
        FIREWORK,

        /** Spawn eggs: two-colour tint over a shared base sprite. */
        SPAWN_EGG,

        /** Tipped arrows: arrow head tinted by potion colour. */
        TIPPED_ARROW

    }

}
