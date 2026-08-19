package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Vanilla's redstone-wire tint table, indexed by power level.
 * <p>
 * A hand-transcribed vanilla table like {@link Biome.Vanilla}, kept beside it rather than in the
 * shipped block-tint JSON because the tooling drops {@code minecraft:redstone_wire} as a dynamic
 * source and emits no row for it.
 */
@UtilityClass
public class RedstoneTint {

    /**
     * Vanilla redstone-wire ARGB tints indexed by power level {@code 0..15}, transcribed byte-for-byte
     * from {@code net.minecraft.world.level.block.RedstoneWireBlock.COLORS} - the 16-step gradient the
     * wire renderer applies to {@code redstone_dust_dot} / {@code redstone_dust_line0/1}.
     * Package-private so {@code RedstoneTintTest} can pin the table content.
     */
    static final int @NotNull [] VALUES = {
        0xFF4B0000, 0xFF6F0000, 0xFF790000, 0xFF820000,
        0xFF8A0000, 0xFF940000, 0xFF9D0000, 0xFFA50000,
        0xFFAE0000, 0xFFB70000, 0xFFBF0000, 0xFFC90000,
        0xFFD20000, 0xFFDA0000, 0xFFE30000, 0xFFEC0000
    };

    /**
     * Answers the vanilla tint for a redstone wire at the given power.
     *
     * @param power the redstone wire power level, {@code 0..15}
     * @return the vanilla ARGB tint
     * @throws IllegalArgumentException if {@code power} is outside {@code [0, 15]}
     */
    public static int vanilla(int power) {
        if (power < 0 || power >= VALUES.length)
            throw new IllegalArgumentException("Redstone power '%d' is outside [0, 15]".formatted(power));
        return VALUES[power];
    }

}
