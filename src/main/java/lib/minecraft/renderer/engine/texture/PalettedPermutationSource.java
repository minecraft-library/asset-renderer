package lib.minecraft.renderer.engine.texture;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * One {@code minecraft:paletted_permutations} entry from an {@code atlases/*.json} source list
 * (05-models.md §6) - the data a {@link TextureSynthesizer} needs to generate a permuted sprite that
 * ships as no PNG (armor-trim item overlays, decorated-pot patterns). Vanilla stitches these at atlas
 * build time; a per-texture renderer instead synthesises each on a resolution miss.
 *
 * @param paletteKey the grayscale palette-key strip id (e.g. {@code minecraft:trims/color_palettes/trim_palette})
 * @param permutations a map from permutation name ({@code amethyst}) to its material colour-strip id
 *     ({@code minecraft:trims/color_palettes/amethyst})
 * @param textures the grayscale base pattern ids (e.g. {@code minecraft:trims/items/chestplate_trim}),
 *     each permuted by every entry in {@link #permutations} to yield {@code <base>_<permutation>}
 */
public record PalettedPermutationSource(
    @NotNull String paletteKey,
    @NotNull Map<String, String> permutations,
    @NotNull List<String> textures
) {}
