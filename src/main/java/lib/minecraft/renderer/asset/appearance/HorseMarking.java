package lib.minecraft.renderer.asset.appearance;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * A horse marking - one of the five vanilla {@code Markings} values drawn as a same-geometry
 * translucent overlay over the coat colour. {@link #NONE} (the default) draws nothing; the four
 * patterned markings each carry the {@code horse/horse_markings_*} overlay texture
 * {@code HorseMarkingLayer} composites over the body. Names and texture mappings mirror
 * {@code HorseMarkingLayer}'s per-marking texture constants.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum HorseMarking {

    NONE(null),
    WHITE("horse/horse_markings_white"),
    WHITE_FIELD("horse/horse_markings_whitefield"),
    WHITE_DOTS("horse/horse_markings_whitedots"),
    BLACK_DOTS("horse/horse_markings_blackdots");

    /** The marking-overlay texture ref drawn over the coat, or {@code null} for {@link #NONE}. */
    private final @Nullable String overlayTexture;

    /**
     * The marking-overlay texture ref drawn over the coat (e.g. {@code horse/horse_markings_white}),
     * or empty for {@link #NONE}, which draws no overlay. The baby mesh binds the {@code _baby}
     * sibling of this ref (its own UV layout).
     *
     * @return the overlay texture ref, or empty when this marking draws nothing
     */
    public @NotNull Optional<String> overlayTexture() {
        return Optional.ofNullable(this.overlayTexture);
    }

    /**
     * Looks up a marking by name (case-insensitive), e.g. {@code "white_dots"}.
     *
     * @param name the marking name
     * @return the matching marking, or {@code null} when the name is not a vanilla marking
     */
    public static @Nullable HorseMarking ofName(@Nullable String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAMarking) {
            return null;
        }
    }
}
