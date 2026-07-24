package lib.minecraft.renderer.option;

import lib.minecraft.renderer.option.spec.DyeColor;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A dye-tint axis - one independent dimension along which a render selects a {@link DyeColor} to tint
 * a target (the body base tint, a named overlay, or the wearer's equipment). Each overlay axis owns
 * the {@code tint_by} token an overlay names in the model form ({@code entity_models.json}) to source
 * its multiplicative tint from the render's {@link DyeColor} selection, mirroring vanilla's
 * per-{@code RenderState} colour fields (sheep {@code getWoolColor}, tropical fish {@code baseColor} /
 * {@code patternColor}).
 *
 * <p>The selections live together on {@code EntityAppearance} as one {@code TintAxis -> DyeColor} map
 * rather than a loose {@link Optional} field per axis, so a new dye-driven dimension is one enum
 * constant here plus its {@code tint_by} emission in the tooling - never a new appearance field and a
 * new hard-coded token branch in the renderer.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum TintAxis {

    /** The body's base tint (tropical fish {@code baseColor}); overrides the model {@code base_tint}. */
    BASE("base_color"),

    /** A pattern overlay's tint (tropical fish {@code patternColor}). */
    PATTERN("pattern_color"),

    /**
     * A wool overlay's tint (sheep {@code getWoolColor}). The one axis whose target does not draw the
     * selected dye's own colour: vanilla's render state fills it from {@code ColorLerper.Type.SHEEP}
     * rather than from {@code getTextureDiffuseColor}.
     */
    WOOL("wool_color") {
        @Override
        public int resolve(@NotNull DyeColor dye) {
            return dye.woolArgb();
        }
    },

    /** A collar overlay's tint (wolf / cat collar dye). */
    COLLAR("collar_color"),

    /**
     * The wearer's equipment dye (wolf armor). Unlike the overlay axes this one names no
     * {@code tint_by} target: it is the dye an equipment asset's own dyeable layers tint by, so it
     * reaches the render through the equipment composite rather than through an overlay. A dyeable
     * layer with no undyed fallback (the armadillo-scute overlay) draws only once this is selected.
     */
    EQUIPMENT("equipment_color");

    /** The {@code tint_by} token this axis is named by in the model form (e.g. {@code "wool_color"}). */
    private final @NotNull String token;

    /**
     * The ARGB a selected dye tints this axis' target with. Vanilla reads most dye targets straight
     * off {@code DyeColor.getTextureDiffuseColor()}, which is the default here; an axis whose render
     * state fills its colour from somewhere else overrides it. Owning the mapping on the axis is what
     * keeps the renderer free of a per-token branch - it holds a {@link TintAxis} already and asks it.
     *
     * @param dye the selected dye
     * @return the ARGB to tint by
     */
    public int resolve(@NotNull DyeColor dye) {
        return dye.argb();
    }

    /**
     * Resolves a {@code tint_by} token to its axis.
     *
     * @param token the {@code tint_by} token from an overlay
     * @return the matching axis, or empty when no axis owns the token
     */
    public static @NotNull Optional<TintAxis> ofToken(@Nullable String token) {
        if (token == null)
            return Optional.empty();

        for (TintAxis axis : values()) {
            if (axis.token.equals(token))
                return Optional.of(axis);
        }

        return Optional.empty();
    }
}
