package dev.sbs.renderer.biome;

/**
 * Identifies which biome colormap drives a block face's tint, or flags that the tint comes from
 * a hardcoded constant on the block DTO.
 */
public enum BiomeTintTarget {

    /** The face is not biome-tinted. */
    NONE,

    /** Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc. */
    GRASS,

    /** Sample the foliage colormap. Applies to most leaves. */
    FOLIAGE,

    /** Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes. */
    DRY_FOLIAGE,

    /** Use the block's {@code tintConstant} field directly. Applies to redstone wire, stems, etc. */
    CONSTANT

}
