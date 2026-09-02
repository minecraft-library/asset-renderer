package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A typed render condition parsed from a {@code when} object: {@code gate.test(appearance)} reports
 * whether the gated overlay / layer renders for a given {@link AppearanceOptions}. An absent {@code when}
 * is modelled as no gate (an {@code Optional.empty()} on the owning row), meaning unconditional.
 *
 * <p>The two arms split on what they compare. {@link Selected} names an {@link Axis} option and
 * matches whether it is the one selected, which is every shipped {@code when} form but one: the
 * {@code age} and {@code size} armor alternates, the creeper's {@code charged} swirl, and the
 * sheep's {@code flag} row, whose {@code value: false} is the expected polarity. {@link TintedGate}
 * compares a resolved colour instead, because vanilla's own branch does.
 */
public sealed interface AppearanceGate permits AppearanceGate.Selected, AppearanceGate.TintedGate {

    /**
     * Reports whether the gated row renders for the given appearance.
     *
     * @param appearance the render-axis selections
     * @return {@code true} when the row should render
     */
    boolean test(@NotNull AppearanceOptions appearance);

    /**
     * Renders when the axis option this row names is - or is not - the one selected.
     *
     * @param option the axis option the row names
     * @param expected whether the row renders when the option is selected ({@code true}) or when it
     *     is not ({@code false} - the sheep's un-sheared body layer, gated off once it is sheared)
     */
    record Selected(@NotNull Axis option, boolean expected) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return this.option.selectedIn(appearance) == this.expected;
        }
    }

    /**
     * Renders only once the row's tint axis selects a colour that differs from the row's own baked
     * tint - the sheep wool undercoat, gated on {@code wool_color}. Vanilla writes that as a dye
     * comparison its layer returns early on ({@code woolColor == WHITE}), and the row's baked tint is
     * the value that very dye resolves to, so the comparison travels as a colour rather than as a dye
     * name and the gate needs no notion of which dye an axis starts at.
     *
     * @param axis the tint axis whose selection activates the row, resolved from the row's
     *     {@code tint_by} token at load - or empty for a row naming an axis nothing owns, which no
     *     selection can ever activate
     * @param defaultArgb the row's baked tint - the colour a selection has to differ from
     */
    record TintedGate(@NotNull Optional<TintAxis> axis, int defaultArgb) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return this.axis
                .flatMap(held -> appearance.tint(held).map(held::resolve))
                .filter(argb -> argb != this.defaultArgb)
                .isPresent();
        }
    }
}
