package dev.sbs.renderer.asset.binding;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A dye colour used for banner / shield pattern tinting, wool / concrete / glass tinting, and
 * any other vanilla "colour from one of the sixteen dyes" shape.
 * <p>
 * Two flavours are supported:
 * <ul>
 * <li><b>{@link Vanilla}</b> - the fixed enum of the sixteen vanilla dye colours, each carrying
 * the {@code textureDiffuseColor} constant from {@code net.minecraft.world.item.DyeColor}
 * (stable since 1.8). Callers normally reference these directly (e.g.
 * {@code DyeColor.Vanilla.RED}).</li>
 * <li><b>{@link Custom}</b> - a record for arbitrary ARGB values when a caller needs a tint
 * colour outside the vanilla palette (modded content, custom packs). Construct via
 * {@link #of(int)}.</li>
 * </ul>
 * Colour values are the vanilla {@code textureDiffuseColor} used by banners, shields, and
 * leather-armour dyes - not the firework or map-colour variants.
 */
public sealed interface DyeColor {

    /**
     * The packed ARGB colour. Alpha is forced opaque ({@code 0xFF}) since dyes carry no
     * transparency semantics.
     *
     * @return the ARGB value
     */
    int argb();

    /**
     * Creates a {@link Custom} dye colour from a packed RGB int. The alpha channel is forced to
     * {@code 0xFF}.
     *
     * @param rgb the packed RGB value
     * @return a new custom dye colour
     */
    static @NotNull DyeColor of(int rgb) {
        return new Custom(0xFF000000 | (rgb & 0xFFFFFF));
    }

    /**
     * Looks up a vanilla dye by enum name (case-sensitive), e.g. {@code "RED"}.
     *
     * @param name the enum constant name
     * @return the matching vanilla dye, or {@code null} if unknown
     */
    static @Nullable DyeColor ofName(@NotNull String name) {
        return Vanilla.ofName(name);
    }

    /**
     * The sixteen vanilla dye colours with their canonical {@code textureDiffuseColor} ARGB
     * values as shipped by {@code net.minecraft.world.item.DyeColor} in MC 26.1. These values
     * have been stable across every version since 1.8 and drive both banner/shield pattern
     * rendering and leather-armour dye tinting.
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum Vanilla implements DyeColor {

        WHITE     (0xFFF9FFFE),
        ORANGE    (0xFFF9801D),
        MAGENTA   (0xFFC74EBD),
        LIGHT_BLUE(0xFF3AB3DA),
        YELLOW    (0xFFFED83D),
        LIME      (0xFF80C71F),
        PINK      (0xFFF38BAA),
        GRAY      (0xFF474F52),
        LIGHT_GRAY(0xFF9D9D97),
        CYAN      (0xFF169C9C),
        PURPLE    (0xFF8932B8),
        BLUE      (0xFF3C44AA),
        BROWN     (0xFF835432),
        GREEN     (0xFF5E7C16),
        RED       (0xFFB02E26),
        BLACK     (0xFF1D1D21);

        private final int argb;

        /**
         * Looks up a vanilla dye by enum name.
         *
         * @param name the enum constant name (case-sensitive)
         * @return the matching vanilla dye, or {@code null} if unknown
         */
        public static @Nullable Vanilla ofName(@NotNull String name) {
            for (Vanilla dye : values())
                if (dye.name().equals(name)) return dye;
            return null;
        }

    }

    /**
     * An arbitrary caller-supplied dye colour. Used for modded content or custom packs that
     * need a tint outside the vanilla sixteen.
     *
     * @param argb the packed ARGB value
     */
    record Custom(int argb) implements DyeColor {

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Custom custom = (Custom) o;
            return this.argb == custom.argb;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.argb);
        }

    }

}
