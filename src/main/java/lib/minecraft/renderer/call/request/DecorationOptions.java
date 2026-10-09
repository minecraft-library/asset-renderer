package lib.minecraft.renderer.call.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The item-icon decoration inputs vanilla composes onto the GUI sprite: a colour tint, armor-trim
 * slot/colour, leather/potion/firework tints, banner base dye, and banner pattern layers.
 */
@Getter
@ClassBuilder
public class DecorationOptions {

    /**
     * Optional ARGB colour standing in for an item's own tint where its definition declares none.
     * It fills tintindex 0 - {@code layer0} of a flat sprite, and the tintindex-0 faces of a model
     * built from elements or from a block model - and it is the fallback of a definition's dye,
     * potion, firework and map-colour tints. A layer or face at any other tintindex, or at none,
     * never takes it. Empty (default) leaves every item its own colours.
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
     * Builds an instance with every decoration input unset.
     *
     * @return the default undecorated options
     */
    public static @NotNull DecorationOptions defaults() {
        return builder().build();
    }
}
