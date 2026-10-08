package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.engine.texture.Palette;
import lib.minecraft.renderer.exception.RenderException;
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
 * <p>
 * Each input is read through the context the caller hands. Where that context stands the
 * checkerboard in for an input no pack supplies, or whose file cannot be decoded, the whole overlay is
 * the checkerboard, as vanilla's paletted permutation draws its missing sprite for a permutation it
 * cannot produce rather than permuting one; where it answers such an input with no pixels, the render
 * is refused.
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
     * @return the permuted trim overlay, or empty when the reference is not a material-specific trim
     *     overlay
     * @throws RenderException if the context answers a required texture with no pixels
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
     * Resolves and permutes a trim overlay for the given armor slot and material, from its three
     * required textures - base trim pattern, palette key, material palette.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param armorSlot the armor slot key ({@code helmet}, {@code chestplate}, {@code leggings},
     *     {@code boots})
     * @param material the trim material key ({@code amethyst}, {@code copper}, {@code diamond},
     *     etc.)
     * @return the permuted trim overlay - never empty, as {@link #permuteFrom} answers it
     * @throws RenderException if the context answers a required texture with no pixels
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
     * the three texture resolves in base / palette-key / material order, and the permutation.
     *
     * <p>The base id is the caller's because it is the only thing the two trim paths differ by: an item
     * icon reads {@code trims/items/{slot}_trim} while a worn shell reads
     * {@code trims/entity/{layer}/{pattern}}. So the palette key both share, and the prefix the
     * material's colour strip sits under, are each declared once - here - rather than once per path.
     *
     * <p>An input the context stands the checkerboard in for makes the overlay the checkerboard as a
     * whole, which is what vanilla draws for a permutation one of whose inputs is missing; permuting
     * the checkerboard would draw a pattern vanilla never does.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param baseId the grayscale base pattern's texture id
     * @param material the trim material key supplying the colour palette
     * @return the permuted trim overlay, or the checkerboard where the context stood it in for an
     *     input - never empty
     * @throws RenderException if the context answers one of the three source textures with no pixels
     */
    public static @NotNull Optional<PixelBuffer> permuteFrom(
        @NotNull RendererContext context,
        @NotNull String baseId,
        @NotNull String material
    ) {
        String materialPaletteId = PALETTE_MATERIAL_PREFIX + material;

        PixelBuffer base = TextureRefusal.require(context.resolveTexture(baseId), baseId);
        PixelBuffer paletteKey = TextureRefusal.require(context.resolveTexture(PALETTE_KEY_ID), PALETTE_KEY_ID);
        PixelBuffer materialPalette = TextureRefusal.require(context.resolveTexture(materialPaletteId), materialPaletteId);

        // A substituting context hands out the one shared sprite for an input it stood in for.
        if (MissingSprite.isSprite(base) || MissingSprite.isSprite(paletteKey) || MissingSprite.isSprite(materialPalette))
            return Optional.of(MissingSprite.sprite());

        return Optional.of(Palette.permute(base, paletteKey, materialPalette));
    }

}
