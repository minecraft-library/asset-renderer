package lib.minecraft.renderer.tooling.geometry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One geometry parse to perform: a factory coordinate plus the parameter-substitution hooks
 * that let the parser evaluate a branch-parameterised factory at one concrete variant.
 *
 * <p>The class coordinate is a JVM internal name, never a zip entry path; the inflate
 * pre-seed is the 3-component {@code grow} (a lossy scalar average would lose per-axis
 * detail); {@code subjectId} is provenance alone - the first requesting subject, named in
 * every parse diagnostic so a failure points at a subject rather than at a bare factory - and
 * it reaches neither the minted key nor the emitted {@code source} twin, both of which are
 * built from the factory coordinate and the discriminators. Texture-dimension overrides ride
 * as components so the skull-wrapper unwrap can stamp {@code texture_size} through them.
 *
 * @param factoryClass the factory class's JVM internal name
 * @param factoryMethod the factory method to parse
 * @param subjectId the first requesting subject (provenance - {@code minecraft:wolf})
 * @param yAxis the Y axis convention used by the source bytecode
 * @param texWidthOverride overrides the texture width when the parsed method does not call
 *     {@code LayerDefinition.create} itself, or {@code null} to read bytecode
 * @param texHeightOverride overrides the texture height, or {@code null}
 * @param paramIntValues int values to substitute for parameter slots when evaluating
 *     branches inside the parsed method, or {@code null} to disable
 * @param paramFloatValues float values to substitute for {@code FLOAD} parameter slots when
 *     evaluating arithmetic - {@code null} disables float substitution AND arithmetic
 *     evaluation entirely (the literal-stack-only walk); when non-null, {@code FLOAD slot}
 *     pushes the substituted value when in range, otherwise the non-literal marker
 * @param grow the 3-component {@code CubeDeformation} pre-seed the parser enters the walk
 *     with, so a factory receiving its inflate as an argument still emits grown cubes
 *     ({@code {0,0,0}} = none)
 * @param appliedMeshTransformerScale the {@code MeshTransformer.scaling} factor captured
 *     from a {@code LayerDefinitions}-level {@code .apply} chain that doesn't appear inline
 *     in the factory body; {@code 1f} = no external scale
 * @param refParam binds an object-reference parameter slot to a concrete enum constant so
 *     the parser can evaluate {@code if (param == Enum.CONST)} branches, or {@code null}
 * @param poseParam binds a {@code PartPose} parameter slot to a concrete offset so the parser
 *     can fold the factory's reads of it, or {@code null}
 * @param babyTransform the {@link BabyMeshTransform} a {@code LayerDefinitions}-level
 *     {@code .apply} chain wraps the factory result in, or {@code null} for the meshes vanilla
 *     registers untransformed. It rides beside {@code appliedMeshTransformerScale} rather than
 *     folding into it because it is seven values and two operators, not a factor
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
    @Nullable RefParam refParam,
    @Nullable PoseParam poseParam,
    @Nullable BabyMeshTransform babyTransform
) {

    /** The no-grow pre-seed. */
    public static final float @NotNull [] NO_GROW = {0f, 0f, 0f};

    /**
     * Returns a copy carrying the aged-down whole-mesh transformer its registration applies.
     *
     * <p>A copy rather than a parameter on each factory because only two of the six recipes can
     * reach one - the size axis's mesh option and the armor row - and vanilla registers every other
     * mesh untransformed.
     *
     * @param transform the transformer the registration applies, or {@code null} for none
     * @return this request when there is no transformer, else a copy carrying it
     */
    public @NotNull GeometryRequest withBabyTransform(@Nullable BabyMeshTransform transform) {
        if (transform == null) return this;
        return new GeometryRequest(this.factoryClass, this.factoryMethod, this.subjectId, this.yAxis,
            this.texWidthOverride, this.texHeightOverride, this.paramIntValues, this.paramFloatValues,
            this.grow, this.appliedMeshTransformerScale, this.refParam, this.poseParam, transform);
    }

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

    /**
     * Binds a {@code PartPose} parameter slot to a concrete offset, so a factory that seats a part
     * by reading its pose argument folds those reads to literals rather than to the non-literal
     * marker.
     *
     * <p>The pose reaches the mesh through its three offset accessors rather than through
     * arithmetic on a float parameter, so it needs a binding of its own: a factory taking one is
     * building a different mesh at each pose it is called with, which is what separates the baby
     * piglin's armor shell from the generic baby one.
     *
     * @param slot the JVM local-variable slot holding the pose parameter
     * @param offset the 3-component offset the bound pose answers
     */
    public record PoseParam(int slot, float @NotNull [] offset) {}

    // ------------------------------------------------------------------------------------
    // static factories - one per entity recipe shape. Every entity recipe shares
    // YAxis.DOWN and a null refParam; they differ only in the int table, the float-param
    // seeding, the grow source, and the MeshTransformer scale.
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
            appliedMeshTransformerScale, null, null, null);
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
            appliedMeshTransformerScale, null, null, null);
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
            texWidthOverride, texHeightOverride, null, new float[8], grow, 1f, null, null, null);
    }

    /**
     * A worn-armor shell request: an {@link #overlay} that is never grown - the shell's two
     * deformations ride the wearer's row, not the mesh - carrying the pose binding a baby shell's
     * factory seats its arms through.
     *
     * @param factoryClass the factory class's internal name
     * @param factoryMethod the factory method
     * @param subjectId the requesting subject
     * @param texWidthOverride the texture-width override, or {@code null}
     * @param texHeightOverride the texture-height override, or {@code null}
     * @param poseParam the bound pose parameter, or {@code null} when the factory takes none
     * @return the request
     */
    public static @NotNull GeometryRequest armor(
        @NotNull String factoryClass,
        @NotNull String factoryMethod,
        @NotNull String subjectId,
        @Nullable Integer texWidthOverride,
        @Nullable Integer texHeightOverride,
        @Nullable PoseParam poseParam
    ) {
        return new GeometryRequest(factoryClass, factoryMethod, subjectId, YAxis.DOWN,
            texWidthOverride, texHeightOverride, null, new float[8], NO_GROW, 1f, null, poseParam, null);
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
            appliedMeshTransformerScale, null, null, null);
    }

    /**
     * A block-primary request: a block-entity model mesh at a chosen branch variant. Unlike
     * the entity recipes it carries an explicit {@code yAxis} (the pivot-band heuristic emits
     * {@code UP} for block-space-authored meshes) and leaves float substitution DISABLED
     * ({@code paramFloatValues == null} - block factories never take a body-scale float, so the
     * literal-stack-only walk applies), while the int-parameter table (banner / sign
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
            texWidthOverride, texHeightOverride, paramIntValues, null, NO_GROW, 1f, refParam, null, null);
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
