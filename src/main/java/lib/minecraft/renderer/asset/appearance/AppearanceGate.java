package lib.minecraft.renderer.asset.appearance;

import lib.minecraft.renderer.option.AppearanceOptions;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A typed render condition parsed from a {@code when} object: {@code gate.test(appearance)} reports
 * whether the gated overlay / layer renders for a given {@link AppearanceOptions}. An absent {@code when}
 * is modelled as no gate (an {@code Optional.empty()} on the owning row), meaning unconditional.
 *
 * <p>Each arm names the exact vanilla branch its condition expresses. The eight arms mirror the eight
 * {@code when} forms the tooling emits.
 */
public sealed interface AppearanceGate
    permits AppearanceGate.StateGate, AppearanceGate.FlagGate, AppearanceGate.ChargedGate,
    AppearanceGate.TintedGate, AppearanceGate.EquipmentGate,
    AppearanceGate.CollarColorGate, AppearanceGate.AgeGate, AppearanceGate.SizeGate {

    /**
     * Reports whether the gated row renders for the given appearance.
     *
     * @param appearance the render-axis selections
     * @return {@code true} when the row should render
     */
    boolean test(@NotNull AppearanceOptions appearance);

    /**
     * Renders when a behavioural {@code state} axis selection equals {@link #value} (wolf
     * {@code tame} / {@code angry}). The default ({@code wild}) state leaves the axis unset.
     *
     * @param value the state token that activates the row
     */
    record StateGate(@NotNull String value) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.getState().filter(this.value::equals).isPresent();
        }
    }

    /**
     * Renders when a boolean flag axis holds {@link #value}. The sole 26.1 flag is {@code sheared}
     * ({@code value == false} = the un-sheared body layer, gated off once the entity is sheared).
     *
     * @param flag the flag axis token
     * @param value the flag value that activates the row
     */
    record FlagGate(@NotNull String flag, boolean value) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            boolean state = "sheared".equals(this.flag) && appearance.isSheared();
            return state == this.value;
        }
    }

    /** Renders only for a charged (lightning-struck) entity - the creeper energy swirl. */
    record ChargedGate() implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.isCharged();
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

    /**
     * Renders only when the {@code equipment} axis selects {@link #slot} (a saddle, body armor).
     *
     * @param slot the equipment slot that activates the row
     */
    record EquipmentGate(@NotNull String slot) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.equipmentMaterial(this.slot).isPresent();
        }
    }

    /**
     * Renders only when a collar is worn - the wolf / cat collar branch
     * ({@code collarColor != null}), which their renderers fill for a tamed subject alone.
     */
    record CollarColorGate() implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.collarTint().isPresent();
        }
    }

    /**
     * Renders when the {@code age} axis selects {@link #value} - the aged-down worn-armor shell the
     * six wearers vanilla registers a second armor set for are dressed in.
     *
     * @param value the age that activates the row
     */
    record AgeGate(@NotNull Age value) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.getAge() == this.value;
        }
    }

    /**
     * Renders when the {@code size} axis selects {@link #value} - the shell a small armor stand
     * wears, which is a different mesh rather than the full-size one drawn smaller.
     *
     * @param value the size that activates the row
     */
    record SizeGate(@NotNull Size value) implements AppearanceGate {
        @Override
        public boolean test(@NotNull AppearanceOptions appearance) {
            return appearance.getSize().filter(this.value::equals).isPresent();
        }
    }
}
