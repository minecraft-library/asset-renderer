package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Identifies which biome colormap drives a block face's tint, or flags that the tint comes
 * from a hardcoded constant on the block DTO.
 *
 * <p>Each constant carries every answer the tint resolution asks of a source - the pack
 * {@code color.properties} key prefix, the colormap it samples, the colour it falls back to and
 * whether the biome's grass modifier reaches it - so resolving a tint reads this table rather
 * than switching on the constant. A source carrying no {@link #packKeyPrefix() key prefix}
 * carries no biome channel at all, which is what separates {@link #NONE} and {@link #CONSTANT}
 * from the four that resolve against a biome.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(claim = "asset-layer")
public enum TintSource {

    /**
     * The face is not biome-tinted.
     */
    NONE(Optional.empty(), Optional.empty(), ColorMath.WHITE, false),

    /**
     * Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc. The one
     * source the biome's grass colour modifier reaches.
     */
    GRASS(Optional.of("grass."), Optional.of("grass"), ColorMath.WHITE, true),

    /**
     * Sample the foliage colormap. Applies to most leaves.
     */
    FOLIAGE(Optional.of("foliage."), Optional.of("foliage"), ColorMath.WHITE, false),

    /**
     * Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes.
     */
    DRY_FOLIAGE(Optional.of("dryfoliage."), Optional.of("dry_foliage"), ColorMath.WHITE, false),

    /**
     * Use the biome's water colour override when present, or the engine-level default
     * {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap; biomes either carry an
     * explicit {@code water_color} value or inherit the default, which is why this is the one
     * source whose {@link #defaultArgb() default} is a colour rather than white.
     */
    WATER(Optional.of("water."), Optional.empty(), 0xFF3F76E4, false),

    /**
     * Use the constant ARGB carried on the block's tint binding directly. Applies to redstone
     * wire, stems, etc.
     */
    CONSTANT(Optional.empty(), Optional.empty(), ColorMath.WHITE, false);

    /**
     * The {@code color.properties} key prefix a pack addresses this source's per-biome override
     * under ({@code grass.}, {@code water.}), empty for a source with no biome channel. Note
     * {@code dryfoliage.} carries no separator inside the word where the colormap name does.
     */
    private final @NotNull Optional<String> packKeyPrefix;

    /**
     * The colormap this source samples, named as it appears under
     * {@code textures/colormap/<name>.png}, empty for a source that samples none.
     */
    private final @NotNull Optional<String> colorMapName;

    /**
     * The ARGB this source resolves to when neither a pack nor the biome answered and no
     * colormap is registered.
     */
    private final int defaultArgb;

    /**
     * Whether the biome's {@code GrassColorModifier} post-processes this source's colour.
     * Vanilla runs it on the grass tint alone.
     */
    private final boolean grassModified;

}
