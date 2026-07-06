package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the synthetic {@link Source}s the entity pipeline feeds to {@code GeometryParser}, one
 * factory per recipe so the twelve-argument positional {@code Source} constructor is written once
 * and the per-recipe divergences are named rather than buried in copy-pasted call sites.
 *
 * <p>Every entity source shares {@code YAxis.DOWN}, a {@code 0f} inventory rotation, the resolver's
 * texture-dimension overrides, and a {@code null} {@code refParam}; the recipes differ only in the
 * int-parameter table, the float-parameter seeding, the inflate source, and the MeshTransformer
 * scale:
 * <ul>
 *   <li><b>{@link #bodySource}</b> - primary / variant / baby bodies: seeded float params, {@code 0f}
 *       inflate, the resolver's applied MeshTransformer scale.</li>
 *   <li><b>{@link #shapeSource}</b> - as body, but carrying the resolver's default inflate.</li>
 *   <li><b>{@link #overlaySource}</b> - composite overlays: <b>un-seeded</b> float params and a fixed
 *       {@code 1f} scale (the overlay factory takes no body-scale float argument), the resolver's
 *       default inflate.</li>
 *   <li><b>{@link #equipmentSource}</b> - as body plus inflate, adding a zeroed int-parameter table
 *       for factories whose first argument is a primitive {@code boolean} / {@code int}.</li>
 * </ul>
 */
@UtilityClass
public final class EntitySourceFactory {

    /**
     * Builds a body source (primary / variant / baby): seeded float params, {@code 0f} inflate, the
     * resolver's applied MeshTransformer scale.
     *
     * @param res the resolved layer factory
     * @param id the geometry-source id to emit under
     * @return the source
     */
    public static @NotNull Source bodySource(@NotNull EntityLayerDefinitionResolver.Result res, @NotNull String id) {
        return source(res, id, null, seededParams(res), 0f, res.appliedMeshTransformerScale());
    }

    /**
     * Builds a shape-axis body source (tropical fish large): as {@link #bodySource} but carrying the
     * resolver's default inflate.
     *
     * @param res the resolved layer factory
     * @param id the geometry-source id to emit under
     * @return the source
     */
    public static @NotNull Source shapeSource(@NotNull EntityLayerDefinitionResolver.Result res, @NotNull String id) {
        return source(res, id, null, seededParams(res), res.defaultInflate(), res.appliedMeshTransformerScale());
    }

    /**
     * Builds a composite-overlay source: <b>un-seeded</b> float params (the overlay factory takes no
     * body-scale float argument) and a fixed {@code 1f} scale, carrying the resolver's default inflate.
     *
     * @param res the resolved layer factory
     * @param id the geometry-source id to emit under
     * @return the source
     */
    public static @NotNull Source overlaySource(@NotNull EntityLayerDefinitionResolver.Result res, @NotNull String id) {
        return source(res, id, null, new float[8], res.defaultInflate(), 1f);
    }

    /**
     * Builds an equipment source: as {@link #bodySource} plus the resolver's default inflate, adding a
     * zeroed int-parameter table when the factory's first argument is a primitive {@code boolean} /
     * {@code int} (binding it to {@code false} / {@code 0}) so the linear walk follows the adult arm
     * rather than folding an untaken baby-transformer branch.
     *
     * @param res the resolved layer factory
     * @param id the geometry-source id to emit under
     * @return the source
     */
    public static @NotNull Source equipmentSource(@NotNull EntityLayerDefinitionResolver.Result res, @NotNull String id) {
        int[] intParams = res.targetDesc().startsWith("(Z") || res.targetDesc().startsWith("(I") ? new int[8] : null;
        return source(res, id, intParams, seededParams(res), res.defaultInflate(), res.appliedMeshTransformerScale());
    }

    /**
     * Assembles a {@link Source} with the entity-pipeline constants ({@code YAxis.DOWN}, {@code 0f}
     * inventory rotation, the resolver's texture overrides, {@code null} refParam) plus the recipe's
     * varying parameters.
     */
    private static @NotNull Source source(
        @NotNull EntityLayerDefinitionResolver.Result res,
        @NotNull String id,
        int @Nullable [] intParams,
        float @NotNull [] floatParams,
        float inflate,
        float meshTransformerScale
    ) {
        return new Source(res.targetClass() + ".class", res.targetMethod(), id, YAxis.DOWN, 0f,
            res.texWidthOverride(), res.texHeightOverride(), intParams, floatParams, inflate, meshTransformerScale, null);
    }

    /**
     * Builds an 8-slot float parameter table with slot 0 seeded from the resolver's captured factory
     * float argument (e.g. donkey / mule base body scale), or all-zero when it captured none.
     */
    private static float @NotNull [] seededParams(@NotNull EntityLayerDefinitionResolver.Result res) {
        float[] params = new float[8];
        if (res.defaultFloatParam() != null) params[0] = res.defaultFloatParam();
        return params;
    }
}
