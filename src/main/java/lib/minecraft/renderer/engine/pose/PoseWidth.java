package lib.minecraft.renderer.engine.pose;

import dev.simplified.annotations.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The numeric width an operation computes at, and therefore where its value rounds.
 */
@RequiredArgsConstructor
public enum PoseWidth {

    /** Single precision - the width a bone channel is finally stored at. */
    FLOAT("f"),

    /** Double precision - what a value crosses into between an {@code f2d} and a {@code d2f}. */
    DOUBLE("d"),

    /** Integral - an unrolled loop's index, a switch case, or a deliberate truncating divide. */
    INT("i");

    /** The letter a Java literal of this width is written with, and so is spelled with here. */
    private final @NotNull String suffix;

    /**
     * Spells one literal at this width, suffixed the way a Java literal of it is.
     *
     * <p>The value arrives as a {@code double} because that is the one carrier wide enough for
     * all three widths, so the suffix is what says which of them was meant.
     *
     * @param value the literal value
     * @return the spelled literal
     */
    public @NotNull String literal(double value) {
        return value + this.suffix;
    }

}
