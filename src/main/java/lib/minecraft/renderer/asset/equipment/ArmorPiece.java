package lib.minecraft.renderer.asset.equipment;

import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The configuration for a single equipped armor slot: which material texture to render, an
 * optional trim, an optional leather dye colour, and whether the piece carries an enchantment glint.
 *
 * @param material the armor material that selects the base texture atlas
 * @param trim the trim overlaid on the base layer, or empty for an untrimmed piece
 * @param dyeColor the ARGB leather dye applied to the {@link ArmorMaterial#LEATHER} base layer, or
 *     empty for vanilla's default leather tint; ignored for non-leather materials
 * @param enchanted whether the piece carries the enchantment glint overlay
 */
@Parity(claim = "option-surface")
public record ArmorPiece(
    @NotNull ArmorMaterial material,
    @NotNull Optional<ArmorTrim> trim,
    @NotNull Optional<Integer> dyeColor,
    boolean enchanted
) {

    /**
     * Creates an untrimmed, undyed, unenchanted armor piece.
     *
     * @param material the armor material
     * @return the armor piece
     */
    public static @NotNull ArmorPiece of(@NotNull ArmorMaterial material) {
        return new ArmorPiece(material, Optional.empty(), Optional.empty(), false);
    }

    /**
     * Creates a trimmed, undyed, unenchanted armor piece.
     *
     * @param material the armor material
     * @param color the trim colour palette
     * @param pattern the trim pattern shape
     * @return the armor piece
     */
    public static @NotNull ArmorPiece of(
        @NotNull ArmorMaterial material,
        @NotNull ArmorTrim.Color color,
        @NotNull ArmorTrim.Pattern pattern
    ) {
        return new ArmorPiece(material, Optional.of(new ArmorTrim(pattern, color)), Optional.empty(), false);
    }

    /**
     * Creates a dyed leather armor piece, untrimmed and unenchanted.
     *
     * @param dyeColor the ARGB leather dye colour
     * @return the armor piece
     */
    public static @NotNull ArmorPiece dyedLeather(int dyeColor) {
        return new ArmorPiece(ArmorMaterial.LEATHER, Optional.empty(), Optional.of(dyeColor), false);
    }

}
