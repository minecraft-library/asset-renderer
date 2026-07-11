package lib.minecraft.renderer.tooling.geometry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One geometry parse to perform - the reworked legacy {@code blockentity/Source} (SPINE
 * 2.10): a factory coordinate plus the parameter-substitution hooks that let the parser
 * evaluate a branch-parameterised factory at one concrete variant.
 *
 * <p>Deltas vs the legacy record: the class coordinate is a JVM internal name, never a zip
 * entry path [X15]; the inflate pre-seed is the 3-component {@code grow} (the lossy /3
 * scalar average dies - decision 24); {@code subjectId} is provenance (the first requesting
 * subject, powering diagnostics + the {@code source} twin), not an output key - keys are
 * minted by the manifest; the legacy {@code inventoryYRotation} is gone (GUI facts are
 * block-models data, not geometry). Texture-dimension overrides ride as components per
 * doc-12 S7 (the skull-wrapper unwrap stamps {@code texture_size} through them).
 *
 * @param factoryClass the factory class's JVM internal name [X15]
 * @param factoryMethod the factory method to parse
 * @param subjectId the first requesting subject (provenance - {@code minecraft:wolf})
 * @param yAxis the Y axis convention used by the source bytecode
 * @param texWidthOverride overrides the texture width when the parsed method does not call
 *     {@code LayerDefinition.create} itself (doc-12 S7), or {@code null} to read bytecode
 * @param texHeightOverride overrides the texture height (doc-12 S7), or {@code null}
 * @param paramIntValues int values to substitute for parameter slots when evaluating
 *     branches inside the parsed method, or {@code null} to disable
 * @param paramFloatValues float values to substitute for {@code FLOAD} parameter slots when
 *     evaluating arithmetic - {@code null} disables float substitution AND arithmetic
 *     evaluation entirely (the legacy literal-stack-only walk); when non-null, {@code FLOAD
 *     slot} pushes the substituted value when in range, otherwise the non-literal marker
 * @param grow the 3-component {@code CubeDeformation} pre-seed the parser enters the walk
 *     with, so a factory receiving its inflate as an argument still emits grown cubes
 *     ({@code {0,0,0}} = none)
 * @param appliedMeshTransformerScale the {@code MeshTransformer.scaling} factor captured
 *     from a {@code LayerDefinitions}-level {@code .apply} chain that doesn't appear inline
 *     in the factory body; {@code 1f} = no external scale
 * @param refParam binds an object-reference parameter slot to a concrete enum constant so
 *     the parser can evaluate {@code if (param == Enum.CONST)} branches, or {@code null}
 */
public record GeometryRequest(
    @NotNull String factoryClass,
    @NotNull String factoryMethod,
    @NotNull String subjectId,
    @NotNull YAxis yAxis,
    @Nullable Integer texWidthOverride,
    @Nullable Integer texHeightOverride,
    int @Nullable [] paramIntValues,
    float @Nullable [] paramFloatValues,
    float @NotNull [] grow,
    float appliedMeshTransformerScale,
    @Nullable RefParam refParam
) {

    /** The no-grow pre-seed. */
    public static final float @NotNull [] NO_GROW = {0f, 0f, 0f};

    /**
     * Binds an object-reference parameter slot to a concrete enum constant, letting the
     * parser resolve a method's {@code if (param == Owner.VALUE)} branch dispatch at a
     * chosen variant (hanging-sign CEILING / WALL).
     *
     * @param slot the JVM local-variable slot holding the reference parameter
     * @param ownerInternal the enum class's JVM internal name
     * @param value the enum constant field name the slot is bound to
     */
    public record RefParam(int slot, @NotNull String ownerInternal, @NotNull String value) {}

    // ------------------------------------------------------------------------------------
    // static factories - the absorbed EntitySourceFactory recipes (SPINE 2.10). Every
    // entity recipe shares YAxis.DOWN and a null refParam; they differ only in the int
    // table, the float-param seeding, the grow source, and the MeshTransformer scale.
    // ------------------------------------------------------------------------------------

    /**
     * A body request (primary / variant / baby): seeded float params, no grow, the
     * resolver's applied MeshTransformer scale.
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param subjectId the requesting subject
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param floatParam the captured factory float argument seeded into slot 0, or {@code null}
     * @param appliedMeshTransformerScale the captured external scale ({@code 1f} = none)
     * @return the request
     */
    public static @NotNull GeometryRequest body(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String subjectId,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @Nullable Float floatParam,
        float appliedMeshTransformerScale
    ) {
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, YAxis.DOWN,
            texWidthOverride, texHeightOverride, null, seededParams(floatParam), NO_GROW,
            appliedMeshTransformerScale, null);
    }

    /**
     * A shape / size-axis body request (tropical fish large, pufferfish small / medium): as
     * {@link #body} but carrying a grow pre-seed.
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param subjectId the requesting subject
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param floatParam the captured factory float argument seeded into slot 0, or {@code null}
     * @param grow the 3-component grow pre-seed
     * @param appliedMeshTransformerScale the captured external scale ({@code 1f} = none)
     * @return the request
     */
    public static @NotNull GeometryRequest shape(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String subjectId,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @Nullable Float floatParam,
        float @NotNull [] grow,
        float appliedMeshTransformerScale
    ) {
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, YAxis.DOWN,
            texWidthOverride, texHeightOverride, null, seededParams(floatParam), grow,
            appliedMeshTransformerScale, null);
    }

    /**
     * A composite-overlay request: un-seeded float params (the overlay factory takes no
     * body-scale float argument) and a fixed {@code 1f} scale, carrying a grow pre-seed.
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param subjectId the requesting subject
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param grow the 3-component grow pre-seed
     * @return the request
     */
    public static @NotNull GeometryRequest overlay(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String subjectId,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        float @NotNull [] grow
    ) {
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, YAxis.DOWN,
            texWidthOverride, texHeightOverride, null, new float[8], grow, 1f, null);
    }

    /**
     * An equipment request: as {@link #body} plus a grow pre-seed, adding a zeroed
     * int-parameter table when the factory's first argument is a primitive {@code boolean} /
     * {@code int} (binding it to {@code false} / {@code 0}) so the linear walk follows the
     * adult arm rather than folding an untaken baby-transformer branch (happy_ghast harness).
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param factoryDesc the factory descriptor (first-argument primitive detection)
     * @param subjectId the requesting subject
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param floatParam the captured factory float argument seeded into slot 0, or {@code null}
     * @param grow the 3-component grow pre-seed
     * @param appliedMeshTransformerScale the captured external scale ({@code 1f} = none)
     * @return the request
     */
    public static @NotNull GeometryRequest equipment(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String factoryDesc,
        @NotNull String subjectId,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @Nullable Float floatParam,
        float @NotNull [] grow,
        float appliedMeshTransformerScale
    ) {
        int[] intParams = factoryDesc.startsWith("(Z") || factoryDesc.startsWith("(I") ? new int[8] : null;
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, YAxis.DOWN,
            texWidthOverride, texHeightOverride, intParams, seededParams(floatParam), grow,
            appliedMeshTransformerScale, null);
    }

    /**
     * A block-primary request: a block-entity model mesh at a chosen branch variant. Unlike
     * the entity recipes it carries an explicit {@code yAxis} (the pivot-band heuristic emits
     * {@code UP} for block-space-authored meshes) and leaves float substitution DISABLED
     * ({@code paramFloatValues == null} - block factories never take a body-scale float, so the
     * legacy literal-stack-only walk applies), while the int-parameter table (banner / sign
     * {@code withStick}) and the reference-parameter binding (hanging-sign attachment enum)
     * drive the branch evaluation that splits one factory into its variants.
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param subjectId the requesting subject
     * @param yAxis the source-frame Y convention (pivot-band heuristic)
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param paramIntValues the int-parameter substitution table, or {@code null}
     * @param refParam the reference-parameter enum binding, or {@code null}
     * @return the request
     */
    public static @NotNull GeometryRequest blockGeometry(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String subjectId,
        @NotNull YAxis yAxis,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        int @Nullable [] paramIntValues,
        @Nullable RefParam refParam
    ) {
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, yAxis,
            texWidthOverride, texHeightOverride, paramIntValues, null, NO_GROW, 1f, refParam);
    }

    /**
     * Builds the 8-slot float parameter table with slot 0 seeded from the captured factory
     * float argument (donkey / mule base body scale), or all-zero when none was captured.
     */
    private static float @NotNull [] seededParams(@Nullable Float floatParam) {
        float[] params = new float[8];
        if (floatParam != null) params[0] = floatParam;
        return params;
    }

}
