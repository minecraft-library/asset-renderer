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
 * {@code HorseMarkingLayer} composites over the body.
 * <p>
 * Each carries the pair vanilla declares for it, an adult ref and the aged-down one drawn over the
 * baby mesh, which has its own UV layout - the two components of the record
 * {@code HorseMarkingLayer} binds every marking to.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum HorseMarking {

    /**
     * The unmarked coat - the default, drawing no overlay at all.
     */
    NONE(null, null),

    /**
     * White stockings up the lower legs and a blaze down the face; the coat is otherwise untouched.
     */
    WHITE("horse/horse_markings_white", "horse/horse_markings_white_baby"),

    /**
     * A broad white field over the back and withers, with a wide white patch across the face.
     */
    WHITE_FIELD("horse/horse_markings_whitefield", "horse/horse_markings_whitefield_baby"),

    /**
     * Pale speckling scattered over the body, densest along the topline.
     */
    WHITE_DOTS("horse/horse_markings_whitedots", "horse/horse_markings_whitedots_baby"),

    /**
     * Heavy dark speckling over the back, flanks and legs - the leopard-spotted coat.
     */
    BLACK_DOTS("horse/horse_markings_blackdots", "horse/horse_markings_blackdots_baby");

    /** The marking-overlay texture ref drawn over the coat, or {@code null} for {@link #NONE}. */
    private final @Nullable String overlayTexture;

    /** The aged-down overlay texture ref, or {@code null} for {@link #NONE}. */
    private final @Nullable String babyOverlayTexture;

    /**
     * The marking-overlay texture ref drawn over the adult coat (e.g.
     * {@code horse/horse_markings_white}), or empty for {@link #NONE}, which draws no overlay.
     *
     * @return the overlay texture ref, or empty when this marking draws nothing
     */
    public @NotNull Optional<String> overlayTexture() {
        return Optional.ofNullable(this.overlayTexture);
    }

    /**
     * The overlay texture ref drawn over the baby coat (e.g.
     * {@code horse/horse_markings_white_baby}), or empty for {@link #NONE}. A separate sheet rather
     * than the same one rescaled - the baby mesh carries its own UV layout.
     *
     * @return the aged-down overlay texture ref, or empty when this marking draws nothing
     */
    public @NotNull Optional<String> babyOverlayTexture() {
        return Optional.ofNullable(this.babyOverlayTexture);
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
