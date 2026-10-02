package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.Palette;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Generates armor trim overlay textures by resolving a subject's three trim textures and running
 * {@link Palette#permute} over them.
 * <p>
 * Vanilla trim textures ship as grayscale patterns ({@code trims/items/helmet_trim.png}, etc.)
 * whose pixel values act as indices into a palette key strip
 * ({@code trims/color_palettes/trim_palette.png}). Each trim material provides a same-sized
 * colour strip ({@code trims/color_palettes/amethyst.png}, etc.) that maps palette-key entries
 * to final ARGB colours. The permutation replaces every pixel whose grayscale value matches a
 * palette-key entry with the corresponding material colour; non-matching pixels are left
 * transparent, producing a ready-to-composite overlay.
 */
@Parity(claim = "trim-palette")
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class TrimKit {

    private static final @NotNull String PALETTE_KEY_ID = "minecraft:trims/color_palettes/trim_palette";
    private static final @NotNull String TRIM_TEXTURE_PREFIX = "minecraft:trims/items/";
    private static final @NotNull String TRIM_INFIX = "_trim_";

    /**
     * Prefix for per-material palette textures (e.g. {@code minecraft:trims/color_palettes/amethyst}).
     */
    private static final @NotNull String PALETTE_MATERIAL_PREFIX = "minecraft:trims/color_palettes/";

    /**
     * Returns {@code true} when the texture reference matches the pattern for a material-specific
     * trim overlay ({@code minecraft:trims/items/{slot}_trim_{material}}).
     *
     * @param textureRef the texture reference to test
     * @return whether this is a trim overlay texture that needs paletted permutation
     */
    public static boolean isTrimTexture(@NotNull String textureRef) {
        if (!textureRef.startsWith(TRIM_TEXTURE_PREFIX)) return false;
        String suffix = textureRef.substring(TRIM_TEXTURE_PREFIX.length());
        return suffix.contains(TRIM_INFIX);
    }

    /**
     * Parses a material-specific trim texture reference and generates the overlay via paletted
     * permutation. The reference must match the pattern
     * {@code minecraft:trims/items/{slot}_trim_{material}}.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param textureRef the full texture reference (e.g
     *     {@code "minecraft:trims/items/chestplate_trim_amethyst"})
     * @return the permuted trim overlay, or empty when the reference doesn't match or required
     *     textures are missing
     */
    public static @NotNull Optional<PixelBuffer> resolveFromTextureRef(
        @NotNull RendererContext context,
        @NotNull String textureRef
    ) {
        if (!textureRef.startsWith(TRIM_TEXTURE_PREFIX)) return Optional.empty();

        String suffix = textureRef.substring(TRIM_TEXTURE_PREFIX.length());
        int trimIdx = suffix.indexOf(TRIM_INFIX);
        if (trimIdx < 0) return Optional.empty();

        String armorSlot = suffix.substring(0, trimIdx);
        String material = suffix.substring(trimIdx + TRIM_INFIX.length());

        return resolve(context, armorSlot, material);
    }

    /**
     * Resolves and permutes a trim overlay for the given armor slot and material. Returns empty
     * when any of the three required textures (base trim pattern, palette key, material palette)
     * cannot be found in the active pack stack.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param armorSlot the armor slot key ({@code helmet}, {@code chestplate}, {@code leggings},
     *     {@code boots})
     * @param material the trim material key ({@code amethyst}, {@code copper}, {@code diamond},
     *     etc.)
     * @return the permuted trim overlay, or empty when a required texture is missing
     */
    public static @NotNull Optional<PixelBuffer> resolve(
        @NotNull RendererContext context,
        @NotNull String armorSlot,
        @NotNull String material
    ) {
        return permuteFrom(context, TRIM_TEXTURE_PREFIX + armorSlot + "_trim", material);
    }

    /**
     * Resolves and permutes a trim overlay from an already-built base-pattern id and a material key -
     * the three texture resolves in base / palette-key / material order, the three-way missing guard,
     * and the permutation.
     *
     * <p>The base id is the caller's because it is the only thing the two trim paths differ by: an item
     * icon reads {@code trims/items/{slot}_trim} while a worn shell reads
     * {@code trims/entity/{layer}/{pattern}}. So the palette key both share, and the prefix the
     * material's colour strip sits under, are each declared once - here - rather than once per path.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param baseId the grayscale base pattern's texture id
     * @param material the trim material key supplying the colour palette
     * @return the permuted trim overlay, or empty when any of the three source textures is missing
     */
    public static @NotNull Optional<PixelBuffer> permuteFrom(
        @NotNull RendererContext context,
        @NotNull String baseId,
        @NotNull String material
    ) {
        String materialPaletteId = PALETTE_MATERIAL_PREFIX + material;

        Optional<PixelBuffer> base = context.resolveTexture(baseId);
        Optional<PixelBuffer> paletteKey = context.resolveTexture(PALETTE_KEY_ID);
        Optional<PixelBuffer> materialPalette = context.resolveTexture(materialPaletteId);

        if (base.isEmpty() || paletteKey.isEmpty() || materialPalette.isEmpty())
            return Optional.empty();

        return Optional.of(Palette.permute(base.get(), paletteKey.get(), materialPalette.get()));
    }

}
