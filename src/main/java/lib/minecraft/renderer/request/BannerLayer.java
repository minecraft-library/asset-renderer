package lib.minecraft.renderer.request;

import lib.minecraft.renderer.asset.BannerPattern;
import org.jetbrains.annotations.NotNull;

/**
 * A single layer in a banner / shield composition: one {@link BannerPattern pattern} tinted
 * with one {@link DyeColor colour}. Callers build an ordered list of layers (back-to-front) to
 * describe a full banner design; the renderer blits each pattern's grayscale mask on top of the
 * accumulated stack, tinted with the layer's colour.
 *
 * @param pattern the pattern descriptor selecting the grayscale mask texture
 * @param color the dye colour that tints this layer's grayscale mask
 */
public record BannerLayer(@NotNull BannerPattern pattern, @NotNull DyeColor color) {}
