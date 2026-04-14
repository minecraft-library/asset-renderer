package dev.sbs.renderer.model;

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
public class OverlayBinding {

    private @NotNull String id = "";

    private @NotNull String itemId = "";

    private @NotNull Kind kind = Kind.LEATHER;

    private @NotNull String baseTexture = "";

    private @NotNull String overlayTexture = "";

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
