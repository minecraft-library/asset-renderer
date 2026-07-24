package lib.minecraft.renderer.option.spec;

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
     * The packed ARGB a sheep's wool draws in for this dye, which is not the dye's own colour.
     * Vanilla routes wool through {@code ColorLerper.Type.SHEEP}, a table built by
     * {@code ColorLerper.getModifiedColor(dye, 0.75f)}: each channel scaled to three quarters and
     * floored, except {@code WHITE}, which returns {@code 0xFFE6E6E6} outright rather than a scaled
     * {@code 0xF9FFFE}. Every other dye target - collars, the tropical fish's base and pattern -
     * takes {@link #argb} unmodified.
     *
     * @return the wool ARGB value
     */
    int woolArgb();

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
     *
     * <p>Wool is {@code ColorLerper.Type.SHEEP}'s table - the same dyes put through
     * {@code getModifiedColor(dye, 0.75f)}, so every constant but one derives its wool colour from
     * its dye colour here rather than restating it, and {@code WHITE} is the sole full override,
     * exactly as vanilla writes it. Deriving it is not decoration: it is what lets a reader check the
     * relationship this doc claims against the code, instead of taking sixteen transcribed constants
     * on trust.
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum Vanilla implements DyeColor {

        WHITE     (0xFFF9FFFE, 0xFFE6E6E6),
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

        /**
         * The factor vanilla's wool table scales every dye but white by.
         */
        private static final float WOOL_BRIGHTNESS = 0.75f;

        /**
         * The packed ARGB colour for this dye.
         */
        private final int argb;

        /**
         * The packed ARGB a sheep's wool draws in for this dye.
         */
        private final int woolArgb;

        /**
         * Constructs a dye whose wool colour is its own colour, scaled.
         *
         * @param argb the packed ARGB colour
         */
        Vanilla(int argb) {
            this(argb, scaled(argb));
        }

        /**
         * Scales each colour channel by {@link #WOOL_BRIGHTNESS} and truncates - which is
         * {@code Mth.floor} over the non-negative values a channel can hold - then reassembles them
         * opaque, in the order vanilla's {@code getModifiedColor} does.
         *
         * @param argb the packed ARGB colour to scale
         * @return the scaled ARGB
         */
        private static int scaled(int argb) {
            int red = (int) ((argb >> 16 & 0xFF) * WOOL_BRIGHTNESS);
            int green = (int) ((argb >> 8 & 0xFF) * WOOL_BRIGHTNESS);
            int blue = (int) ((argb & 0xFF) * WOOL_BRIGHTNESS);
            return 0xFF000000 | red << 16 | green << 8 | blue;
        }

        /**
         * Looks up a vanilla dye by enum name.
         *
         * @param name the enum constant name (case-sensitive)
         * @return the matching vanilla dye, or {@code null} if unknown
         */
        public static @Nullable Vanilla ofName(@NotNull String name) {
            for (Vanilla dye : values()) {
                if (dye.name().equals(name))
                    return dye;
            }

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

        /**
         * A custom dye names one colour and vanilla's wool table has no row for it, so wool draws it
         * as given rather than putting it through a scale vanilla never chose for it.
         *
         * @return {@link #argb}
         */
        @Override
        public int woolArgb() {
            return this.argb;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Custom custom = (Custom) o;
            return this.argb == custom.argb;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return Objects.hashCode(this.argb);
        }

    }

}
