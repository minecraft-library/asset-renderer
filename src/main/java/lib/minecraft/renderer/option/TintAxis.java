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
 * a target (the body base tint, or a named overlay). Each axis owns the {@code tint_by} token an
 * overlay names in the family form ({@code entity_models.json}) to source its multiplicative tint
 * from the render's {@link DyeColor} selection, mirroring vanilla's per-{@code RenderState} colour
 * fields (sheep {@code getWoolColor}, tropical fish {@code baseColor} / {@code patternColor}).
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

    /** The body's base tint (tropical fish {@code baseColor}); overrides the family {@code base_tint}. */
    BASE("base_color"),

    /** A pattern overlay's tint (tropical fish {@code patternColor}). */
    PATTERN("pattern_color"),

    /** A wool overlay's tint (sheep {@code getWoolColor}). */
    WOOL("wool_color"),

    /** A collar overlay's tint (wolf / cat collar dye). */
    COLLAR("collar_color");

    /** The {@code tint_by} token this axis is named by in the family form (e.g. {@code "wool_color"}). */
    private final @NotNull String token;

    /**
     * Resolves a {@code tint_by} token to its axis.
     *
     * @param token the {@code tint_by} token from an overlay
     * @return the matching axis, or empty when no axis owns the token
     */
    public static @NotNull Optional<TintAxis> ofToken(@Nullable String token) {
        if (token == null) return Optional.empty();
        for (TintAxis axis : values())
            if (axis.token.equals(token)) return Optional.of(axis);
        return Optional.empty();
    }
}
