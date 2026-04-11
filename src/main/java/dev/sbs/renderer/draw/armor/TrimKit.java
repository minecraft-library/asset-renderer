package dev.sbs.renderer.draw.armor;

import dev.sbs.renderer.engine.TextureEngine;
import dev.simplified.image.PixelBuffer;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Generates armor trim overlay textures by applying Minecraft's paletted-permutation algorithm.
 * <p>
 * Vanilla trim textures ship as grayscale patterns ({@code trims/items/helmet_trim.png}, etc.)
 * whose pixel values act as indices into a palette key strip
 * ({@code trims/color_palettes/trim_palette.png}). Each trim material provides a same-sized
 * colour strip ({@code trims/color_palettes/amethyst.png}, etc.) that maps palette-key entries
 * to final ARGB colours. The permutation replaces every pixel whose grayscale value matches a
 * palette-key entry with the corresponding material colour; non-matching pixels are left
 * transparent, producing a ready-to-composite overlay.
 * <p>
 * The algorithm is verified against the MC 26.1 deobfuscated client source
 * ({@code net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations}).
 */
@UtilityClass
public class TrimKit {

    private static final @NotNull String PALETTE_KEY_ID = "minecraft:trims/color_palettes/trim_palette";
    private static final @NotNull String TRIM_TEXTURE_PREFIX = "minecraft:trims/items/";
    private static final @NotNull String TRIM_INFIX = "_trim_";

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
     * @param engine the texture engine for pack-aware texture resolution
     * @param textureRef the full texture reference (e.g.
     *     {@code "minecraft:trims/items/chestplate_trim_amethyst"})
     * @return the permuted trim overlay, or empty when the reference doesn't match or required
     *     textures are missing
     */
    public static @NotNull Optional<PixelBuffer> resolveFromTextureRef(
        @NotNull TextureEngine engine,
        @NotNull String textureRef
    ) {
        if (!textureRef.startsWith(TRIM_TEXTURE_PREFIX)) return Optional.empty();

        String suffix = textureRef.substring(TRIM_TEXTURE_PREFIX.length());
        int trimIdx = suffix.indexOf(TRIM_INFIX);
        if (trimIdx < 0) return Optional.empty();

        String armorSlot = suffix.substring(0, trimIdx);
        String material = suffix.substring(trimIdx + TRIM_INFIX.length());

        return resolve(engine, armorSlot, material);
    }

    /**
     * Resolves and permutes a trim overlay for the given armor slot and material. Returns empty
     * when any of the three required textures (base trim pattern, palette key, material palette)
     * cannot be found in the active pack stack.
     *
     * @param engine the texture engine for pack-aware texture resolution
     * @param armorSlot the armor slot key ({@code helmet}, {@code chestplate}, {@code leggings},
     *     {@code boots})
     * @param material the trim material key ({@code amethyst}, {@code copper}, {@code diamond},
     *     etc.)
     * @return the permuted trim overlay, or empty when a required texture is missing
     */
    public static @NotNull Optional<PixelBuffer> resolve(
        @NotNull TextureEngine engine,
        @NotNull String armorSlot,
        @NotNull String material
    ) {
        String baseTrimId = "minecraft:trims/items/" + armorSlot + "_trim";
        String materialPaletteId = "minecraft:trims/color_palettes/" + material;

        Optional<PixelBuffer> baseTrim = engine.tryResolveTexture(baseTrimId);
        Optional<PixelBuffer> paletteKey = engine.tryResolveTexture(PALETTE_KEY_ID);
        Optional<PixelBuffer> materialPalette = engine.tryResolveTexture(materialPaletteId);

        if (baseTrim.isEmpty() || paletteKey.isEmpty() || materialPalette.isEmpty())
            return Optional.empty();

        return Optional.of(permute(baseTrim.get(), paletteKey.get(), materialPalette.get()));
    }

    /**
     * Applies the paletted-permutation algorithm to produce a coloured trim overlay from a
     * grayscale base pattern.
     * <p>
     * For each pixel in the base texture whose grayscale value (lowest 8 bits) matches an entry
     * in the palette key strip, the corresponding material colour is written to the output at
     * full opacity. Non-matching pixels remain transparent.
     *
     * @param baseTrim the grayscale trim pattern texture
     * @param paletteKey the palette key strip (grayscale, one row)
     * @param materialPalette the material colour strip (RGB, same width as the key)
     * @return the permuted ARGB overlay
     */
    static @NotNull PixelBuffer permute(
        @NotNull PixelBuffer baseTrim,
        @NotNull PixelBuffer paletteKey,
        @NotNull PixelBuffer materialPalette
    ) {
        int paletteSize = Math.min(paletteKey.getWidth(), materialPalette.getWidth());
        int[] keyGrays = new int[paletteSize];
        int[] materialColors = new int[paletteSize];

        for (int i = 0; i < paletteSize; i++) {
            keyGrays[i] = paletteKey.getPixel(i, 0) & 0xFF;
            materialColors[i] = materialPalette.getPixel(i, 0) | 0xFF000000;
        }

        int w = baseTrim.getWidth();
        int h = baseTrim.getHeight();
        int[] pixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = baseTrim.getPixel(x, y);
                if ((pixel >>> 24) == 0) continue; // skip fully transparent pixels
                int gray = pixel & 0xFF;

                for (int i = 0; i < paletteSize; i++) {
                    if (keyGrays[i] == gray) {
                        pixels[y * w + x] = materialColors[i];
                        break;
                    }
                }
            }
        }

        return PixelBuffer.of(pixels, w, h);
    }

}
