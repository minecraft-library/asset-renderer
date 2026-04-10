package dev.sbs.renderer.options;

import dev.sbs.renderer.draw.ArmorTrim;
import dev.sbs.renderer.pack.ItemContext;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageFormat;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Configures a single {@code ItemRenderer} invocation. Covers both 2D GUI icons and 3D held-item
 * views, with opt-in glint animation and colour overlay support.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class ItemOptions {

    /** The supported render types for {@code ItemRenderer}. */
    public enum Type {
        /** 3D view as the item appears when held in a player's hand. */
        HELD_3D,
        /** 2D flat GUI icon. */
        GUI_2D
    }

    @lombok.Builder.Default
    private final @NotNull String itemId = "";

    @lombok.Builder.Default
    private final @NotNull Type type = Type.GUI_2D;

    /** When true, compose an animated enchantment glint on top of the rendered item. */
    @lombok.Builder.Default
    private final boolean enchanted = false;

    /** Optional ARGB tint applied to colour-overlay items (leather armour, spawn eggs). */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> tintColor = Optional.empty();

    /**
     * The armor slot whose trim pattern to composite on top of the base item layers. When both
     * this and {@link #trimColor} are present, the renderer resolves the trim texture via
     * paletted permutation and composites it as an overlay.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorTrim.Slot> trimSlot = Optional.empty();

    /**
     * The trim colour that selects the palette for the trim overlay. Use the {@code _DARKER}
     * variants when the trim material matches the armor material (e.g.
     * {@link ArmorTrim.Color#IRON_DARKER} for an iron trim on iron armor) so the pattern stays
     * visible. Ignored when {@link #trimSlot} is absent.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<ArmorTrim.Color> trimColor = Optional.empty();

    /** Primary overlay colour for items that support a two-colour tint (spawn egg primary). */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> overlayPrimary = Optional.empty();

    /** Secondary overlay colour for items that support a two-colour tint (spawn egg secondary). */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> overlaySecondary = Optional.empty();

    /** Override colour for leather armour pieces. */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> leatherColor = Optional.empty();

    /** Override colour for potion contents. */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> potionColor = Optional.empty();

    /** Override colour for firework stars. */
    @lombok.Builder.Default
    private final @NotNull Optional<Integer> fireworkColor = Optional.empty();

    /** Total number of frames produced when the renderer generates animated output. */
    @lombok.Builder.Default
    private final int animationFrames = 60;

    /** Target frame rate for animated output; drives glint scroll speed and loop period. */
    @lombok.Builder.Default
    private final int framesPerSecond = 30;

    /** Whether to render the vanilla-style durability bar when the item has taken damage. */
    @lombok.Builder.Default
    private final boolean showDamageBar = true;

    @lombok.Builder.Default
    private final int outputSize = 256;

    @lombok.Builder.Default
    private final @NotNull ConcurrentList<String> texturePackIds = Concurrent.newList();

    @lombok.Builder.Default
    private final @NotNull ImageFormat outputFormat = ImageFormat.PNG;

    /** The render-time item context used by CIT matching, the damage bar, and stack count overlay. */
    @lombok.Builder.Default
    private final @NotNull ItemContext context = ItemContext.EMPTY;

    public @NotNull ItemOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull ItemOptions defaults() {
        return builder().build();
    }

}
