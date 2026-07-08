package lib.minecraft.renderer.option;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A tropical-fish pattern - one of the twelve vanilla {@code TropicalFish.Pattern} values, each
 * fixing both the {@link Shape body shape} it renders on and the pattern-overlay texture drawn over
 * the body. The six {@link Shape#SMALL} patterns use the {@code tropical_a_pattern_*} textures on the
 * small body; the six {@link Shape#LARGE} patterns use {@code tropical_b_pattern_*} on the large body.
 * Names and shape/index mappings mirror {@code TropicalFishPatternLayer}'s per-pattern texture
 * constants.
 */
@Getter
@Accessors(fluent = true)
public enum TropicalFishPattern {

    KOB(Shape.SMALL, 1),
    SUNSTREAK(Shape.SMALL, 2),
    SNOOPER(Shape.SMALL, 3),
    DASHER(Shape.SMALL, 4),
    BRINELY(Shape.SMALL, 5),
    SPOTTY(Shape.SMALL, 6),
    FLOPPER(Shape.LARGE, 1),
    STRIPEY(Shape.LARGE, 2),
    GLITTER(Shape.LARGE, 3),
    BLOCKFISH(Shape.LARGE, 4),
    BETTY(Shape.LARGE, 5),
    CLAYFISH(Shape.LARGE, 6);

    /** The body shape this pattern renders on (small = {@code tropical_a}, large = {@code tropical_b}). */
    private final @NotNull Shape shape;

    /** The 1-based pattern index within the shape ({@code 1}..{@code 6}). */
    private final int index;

    /** The pattern-overlay texture ref drawn over the body (e.g. {@code fish/tropical_a_pattern_1}). */
    private final @NotNull String overlayTexture;

    TropicalFishPattern(@NotNull Shape shape, int index) {
        this.shape = shape;
        this.index = index;
        this.overlayTexture = "fish/tropical_%s_pattern_%d".formatted(shape.letter(), index);
    }

    /**
     * Looks up a pattern by name (case-insensitive), e.g. {@code "kob"}.
     *
     * @param name the pattern name
     * @return the matching pattern, or {@code null} when the name is not a vanilla pattern
     */
    public static @Nullable TropicalFishPattern ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAPattern) {
            return null;
        }
    }

    /**
     * The two tropical-fish body shapes - the small ({@code tropical_a}) and large ({@code tropical_b})
     * meshes, each carrying the {@code tropical_<letter>} texture-atlas letter.
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum Shape {
        SMALL("a"),
        LARGE("b");

        /** The texture-atlas letter for this shape ({@code "a"} small, {@code "b"} large). */
        private final @NotNull String letter;
    }
}
