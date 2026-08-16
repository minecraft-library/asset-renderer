package lib.minecraft.renderer.option.spec;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The item-icon decoration inputs vanilla composes onto the GUI sprite: a colour tint, armor-trim
 * slot/colour, leather/potion/firework tints, banner base dye, and banner pattern layers.
 */
@Getter
@ClassBuilder(style = NamingStyle.LOMBOK)
public class ItemDecoration {

    /**
     * Optional ARGB tint applied to colour-overlay items (leather armour, spawn eggs). Empty
     * (default) uses the item's intrinsic tint.
     */
    private final @NotNull Optional<Integer> tintColor = Optional.empty();

    /**
     * The armor slot whose trim pattern to composite on top of the base item layers. When both
     * this and {@link #trimColor} are present, the renderer resolves the trim texture via
     * paletted permutation and composites it as an overlay.
     */
    private final @NotNull Optional<ArmorSlot> trimSlot = Optional.empty();

    /**
     * The trim colour that selects the palette for the trim overlay. Use the {@code _DARKER}
     * variants when the trim material matches the armor material (e.g.
     * {@link ArmorTrim.Color#IRON_DARKER} for an iron trim on iron armor) so the pattern stays
     * visible. Ignored when {@link #trimSlot} is absent.
     */
    private final @NotNull Optional<ArmorTrim.Color> trimColor = Optional.empty();

    /**
     * ARGB override colour for the leather-armour tint layer. Empty (default) uses vanilla's
     * default leather colour.
     */
    private final @NotNull Optional<Integer> leatherColor = Optional.empty();

    /**
     * ARGB override colour for potion contents (the liquid overlay). Empty (default) uses the
     * effect's registered colour.
     */
    private final @NotNull Optional<Integer> potionColor = Optional.empty();

    /**
     * ARGB override colour for firework stars. Empty (default) uses the star's own colour.
     */
    private final @NotNull Optional<Integer> fireworkColor = Optional.empty();

    /**
     * The base dye colour (banner field / shield base) for banner and shield items. Defaults
     * to white when absent.
     */
    private final @NotNull Optional<DyeColor> baseDye = Optional.empty();

    /**
     * Ordered list of pattern layers composited on top of the base dye for banner and shield
     * items. Empty for plain banners / shields.
     */
    private final @NotNull ConcurrentList<BannerLayer> bannerLayers = Concurrent.newList();

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a
     * few fields changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull ItemDecorationBuilder mutate() {
        return this.toBuilder();
    }

    /**
     * Builds an instance with every decoration input unset.
     *
     * @return the default undecorated options
     */
    public static @NotNull ItemDecoration defaults() {
        return builder().build();
    }
}
