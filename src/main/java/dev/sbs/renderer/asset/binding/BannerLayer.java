package dev.sbs.renderer.asset.binding;

import org.jetbrains.annotations.NotNull;

/**
 * A single layer in a banner / shield composition: one {@link BannerPattern pattern} tinted
 * with one {@link DyeColor colour}. Callers build an ordered list of layers to describe a
 * full banner design.
 *
 * @param pattern the pattern descriptor
 * @param color the dye colour applied to this layer's grayscale mask
 */
public record BannerLayer(@NotNull BannerPattern pattern, @NotNull DyeColor color) {}
