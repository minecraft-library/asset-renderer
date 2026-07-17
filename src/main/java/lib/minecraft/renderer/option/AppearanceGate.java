package lib.minecraft.renderer.option;

import org.jetbrains.annotations.NotNull;

/**
 * A typed render condition parsed from a {@code when} object: {@code gate.test(appearance)} reports
 * whether the gated overlay / layer renders for a given {@link EntityAppearance}. An absent {@code when}
 * is modelled as no gate (an {@code Optional.empty()} on the owning row), meaning unconditional.
 *
 * <p>This retires the boolean zoo ({@code shearable} / {@code requires_tint} / {@code requires_charged})
 * that the runtime overlay record previously carried, and the untruthful {@code state == "tame"} collar
 * fiction, by naming the exact vanilla branch each condition expresses. The seven arms mirror the seven
 * {@code when} forms the tooling emits.
 */
public sealed interface AppearanceGate
    permits AppearanceGate.StateGate, AppearanceGate.FlagGate, AppearanceGate.ChargedGate,
    AppearanceGate.TintedGate, AppearanceGate.EquipmentGate, AppearanceGate.MarkingsGate,
    AppearanceGate.CollarColorGate {

    /**
     * Reports whether the gated row renders for the given appearance.
     *
     * @param appearance the render-axis selections
     * @return {@code true} when the row should render
     */
    boolean test(@NotNull EntityAppearance appearance);

    /**
     * Renders when a behavioural {@code state} axis selection equals {@link #value} (wolf
     * {@code tame} / {@code angry}). The default ({@code wild}) state leaves the axis unset.
     *
     * @param value the state token that activates the row
     */
    record StateGate(@NotNull String value) implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
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
        public boolean test(@NotNull EntityAppearance appearance) {
            boolean state = "sheared".equals(this.flag) && appearance.isSheared();
            return state == this.value;
        }
    }

    /** Renders only for a charged (lightning-struck) entity - the creeper energy swirl. */
    record ChargedGate() implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
            return appearance.isCharged();
        }
    }

    /**
     * Renders only once the row's tint axis holds a selected colour (the sheep wool undercoat, gated
     * on {@code wool_color}). Carries the {@link #tintBy} axis token so the gate is self-contained.
     *
     * @param tintBy the tint axis token whose selection activates the row
     */
    record TintedGate(@NotNull String tintBy) implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
            return TintAxis.ofToken(this.tintBy).flatMap(appearance::tint).isPresent();
        }
    }

    /**
     * Renders only when the {@code equipment} axis selects {@link #slot} (a saddle, body armor).
     *
     * @param slot the equipment slot that activates the row
     */
    record EquipmentGate(@NotNull String slot) implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
            return appearance.equipmentMaterial(this.slot).isPresent();
        }
    }

    /** Renders only when the {@code markings} axis selects a non-default marking (the horse coat marking). */
    record MarkingsGate() implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
            return appearance.getMarkings().overlayTexture().isPresent();
        }
    }

    /**
     * Renders only when a collar colour is supplied - the truthful wolf / cat collar branch
     * ({@code collarColor != null}), replacing the legacy {@code state == "tame"} fiction (D42).
     */
    record CollarColorGate() implements AppearanceGate {
        @Override
        public boolean test(@NotNull EntityAppearance appearance) {
            return appearance.tint(TintAxis.COLLAR).isPresent();
        }
    }
}
