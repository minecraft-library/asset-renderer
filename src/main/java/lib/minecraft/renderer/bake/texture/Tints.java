package lib.minecraft.renderer.bake.texture;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.Biome;
import lib.minecraft.renderer.vanilla.RedstoneTint;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The tints a pack's {@code color.properties} can override, sampled against a renderer context - the
 * pack's override first, then the vanilla source it overrides.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public final class Tints {

    /**
     * Samples the biome tint for the given target, reading each answer off the target's own table
     * and the biome's own data.
     * <p>
     * Priority order:
     * <ol>
     * <li>A target carrying no {@link TintSource#packKeyPrefix() key prefix} -
     * {@link TintSource#NONE NONE} and {@link TintSource#CONSTANT CONSTANT} - has no
     * biome channel and answers opaque white; {@code CONSTANT} defers to the block DTO's own
     * constant and should not be routed here.</li>
     * <li>The pack's {@code color.properties} override for this target and biome.</li>
     * <li>The biome's own {@link Biome#colorOverride(TintSource) hardcoded override}
     * (badlands, cherry grove, water).</li>
     * <li>A sample from the target's {@link ColorMap} at {@code (temperature, downfall)}.</li>
     * <li>The target's {@link TintSource#defaultArgb() default} when no colormap is
     * registered - white, or vanilla's water colour for {@code WATER}, which samples none.</li>
     * </ol>
     * Every answer but the last is post-processed by
     * {@link Biome#applyModifier(TintSource, int)}; the default is not, because nothing
     * answered for the modifier to act on.
     *
     * @param context the context the pack override and the colormap are looked up in
     * @param target the tint target
     * @param biome the biome context
     * @return the sampled ARGB colour
     */
    public static int biome(@NotNull RendererContext context, @NotNull TintSource target, @NotNull Biome biome) {
        Optional<String> prefix = target.packKeyPrefix();
        if (prefix.isEmpty()) return ColorMath.WHITE;

        Optional<Integer> packOverride = context.findColorOverride(prefix.get() + biome.localName());
        if (packOverride.isPresent()) return biome.applyModifier(target, packOverride.get());

        Optional<Integer> override = biome.colorOverride(target);
        if (override.isPresent()) return biome.applyModifier(target, override.get());

        Possible<ColorMap> map = context.findColorMap(target);
        if (map.isEmpty()) return target.defaultArgb();

        return biome.applyModifier(target, map.get().sample(biome.temperature(), biome.downfall()));
    }

    /**
     * Resolves the redstone-wire ARGB tint for a power level, consulting the pack's
     * {@code redstone.<power>} {@code color.properties} override before falling back to the bundled
     * vanilla {@link RedstoneTint} table - the same pack-override-then-vanilla shape as
     * {@link #biome}.
     * <p>
     * The vanilla lookup is resolved into a local before the override is consulted, so an
     * out-of-range power is rejected without a pack ever being asked about it.
     *
     * @param context the context the pack override is looked up in
     * @param power the redstone wire power level, {@code 0..15}
     * @return the resolved ARGB tint
     * @throws IllegalArgumentException if {@code power} is outside {@code [0, 15]}
     */
    public static int redstone(@NotNull RendererContext context, int power) {
        int vanilla = RedstoneTint.vanilla(power);
        return context.findColorOverride("redstone." + power).orElse(vanilla);
    }

}
