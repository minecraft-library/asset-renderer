package lib.minecraft.renderer.kit;

import lib.minecraft.renderer.engine.RendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * The 16-entry vanilla redstone-wire-by-power ARGB table, plus a {@link RendererContext}-aware
 * {@link #resolve(RendererContext, int)} that consults a pack's
 * {@code optifine/color.properties} {@code redstone.0..redstone.15} override before falling back
 * to the bundled values.
 * <p>
 * The vanilla table is transcribed from the deobfuscated MC 26.1 client source -
 * {@code net.minecraft.world.level.block.RedstoneWireBlock.COLORS} - which exposes the same
 * 16-step gradient the redstone-wire renderer uses to tint {@code redstone_dust_dot} and
 * {@code redstone_dust_line0/1} based on the wire's {@code power} blockstate property.
 */
@UtilityClass
public class RedstoneKit {

    /**
     * Vanilla redstone-wire ARGB tints indexed by power level {@code 0..15}. Values match
     * {@code RedstoneWireBlock.COLORS} byte-for-byte.
     */
    public static final int @NotNull [] VANILLA = {
        0xFF4B0000, 0xFF6F0000, 0xFF790000, 0xFF820000,
        0xFF8A0000, 0xFF940000, 0xFF9D0000, 0xFFA50000,
        0xFFAE0000, 0xFFB70000, 0xFFBF0000, 0xFFC90000,
        0xFFD20000, 0xFFDA0000, 0xFFE30000, 0xFFEC0000
    };

    /**
     * Resolves the redstone-wire ARGB tint for a given power level, consulting the active pack's
     * {@code redstone.<power>} override before falling back to {@link #VANILLA}.
     *
     * @param context the renderer context whose active packs supply the override
     * @param power the redstone wire power level, {@code 0..15}
     * @return the resolved ARGB tint
     * @throws IllegalArgumentException if {@code power} is outside {@code 0..15}
     */
    public static int resolve(@NotNull RendererContext context, int power) {
        if (power < 0 || power >= VANILLA.length)
            throw new IllegalArgumentException("Redstone power '%d' is outside [0, 15]".formatted(power));
        return context.colorOverride("redstone." + power).orElse(VANILLA[power]);
    }

}
