package lib.minecraft.renderer.asset.binding;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The configuration for a single equipped armor slot: which material texture to render, an
 * optional trim colour and pattern, and whether the piece carries an enchantment glint.
 *
 * @param material the armor material that selects the base texture atlas
 * @param trimColor the trim colour palette for paletted permutation, or empty for no trim
 * @param trimPattern the trim pattern shape for 3D entity rendering, or empty for no trim
 * @param enchanted whether to apply the enchantment glint overlay
 */
public record ArmorPiece(
    @NotNull ArmorMaterial material,
    @NotNull Optional<ArmorTrim.Color> trimColor,
    @NotNull Optional<ArmorTrim.Pattern> trimPattern,
    boolean enchanted
) {

    /**
     * Creates an untrimmed, unenchanted armor piece.
     *
     * @param material the armor material
     * @return the armor piece
     */
    public static @NotNull ArmorPiece of(@NotNull ArmorMaterial material) {
        return new ArmorPiece(material, Optional.empty(), Optional.empty(), false);
    }

    /**
     * Creates a trimmed, unenchanted armor piece.
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
        return new ArmorPiece(material, Optional.of(color), Optional.of(pattern), false);
    }

}
