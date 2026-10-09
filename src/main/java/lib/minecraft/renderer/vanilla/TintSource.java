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
 * <p>Each constant carries every answer the tint resolution asks of a source - whether it resolves
 * against a biome at all, the colormap it samples, the colour it falls back to and whether the
 * biome's grass modifier reaches it - so resolving a tint reads this table rather than switching
 * on the constant. {@link #NONE} and {@link #CONSTANT} carry no biome channel; the other four
 * resolve against a biome.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(claim = "asset-layer")
public enum TintSource {

    /**
     * The face is not biome-tinted.
     */
    NONE(false, Optional.empty(), ColorMath.WHITE, false),

    /**
     * Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc. The one
     * source the biome's grass colour modifier reaches.
     */
    GRASS(true, Optional.of("grass"), ColorMath.WHITE, true),

    /**
     * Sample the foliage colormap. Applies to most leaves.
     */
    FOLIAGE(true, Optional.of("foliage"), ColorMath.WHITE, false),

    /**
     * Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes.
     */
    DRY_FOLIAGE(true, Optional.of("dry_foliage"), ColorMath.WHITE, false),

    /**
     * Use the biome's water colour override when present, or the engine-level default
     * {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap; biomes either carry an
     * explicit {@code water_color} value or inherit the default, which is why this is the one
     * source whose {@link #defaultArgb() default} is a colour rather than white.
     */
    WATER(true, Optional.empty(), 0xFF3F76E4, false),

    /**
     * Use the constant ARGB carried on the block's tint binding directly. Applies to redstone
     * wire, stems, etc.
     */
    CONSTANT(false, Optional.empty(), ColorMath.WHITE, false);

    /**
     * Whether this source resolves against a biome - false for {@link #NONE} and {@link #CONSTANT},
     * which carry no biome channel.
     */
    private final boolean biomeTinted;

    /**
     * The colormap this source samples, named as it appears under
     * {@code textures/colormap/<name>.png}, empty for a source that samples none.
     */
    private final @NotNull Optional<String> colorMapName;

    /**
     * The ARGB this source resolves to when the biome does not answer and it samples no
     * colormap - the engine default for {@link #WATER}, which names none. A source naming a colormap
     * answers it only on a context built without that colormap, since a context loaded from a pack
     * stack that cannot supply it loads as the vanilla pack alone, as vanilla's resource reload does.
     */
    private final int defaultArgb;

    /**
     * Whether the biome's {@code GrassColorModifier} post-processes this source's colour.
     * Vanilla runs it on the grass tint alone.
     */
    private final boolean grassModified;

}
