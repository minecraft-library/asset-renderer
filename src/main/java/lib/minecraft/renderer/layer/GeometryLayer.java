package lib.minecraft.renderer.layer;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import org.jetbrains.annotations.NotNull;

/**
 * Contributor of 3D geometry to a single shared rasterization pass.
 * <p>
 * Unlike a 2D {@link ImageLayer}, a geometry layer does not produce its own pixel frame. It appends
 * triangles to a {@code sink} that every layer shares, and the renderer rasterizes the combined list
 * once into a shared depth buffer. This is required: the rasterizer's back-to-front translucent sort,
 * its GL_LEQUAL last-drawn-wins depth test, and its emissive depth-write skip are all coupled across
 * the whole triangle list, so layers cannot be rendered to independent frames and stacked.
 * <p>
 * <b>Emission order is load-bearing.</b> The order in which layers append - and the order in which a
 * layer appends within itself - determines coplanar depth tie-breaks and translucent ordering.
 * Layers must append in a deterministic order and must not sort or filter the shared sink. Each layer
 * owns the {@link SurfaceTraits} of the triangles it contributes (a body layer is opaque, an armor
 * layer is glinted, an eye-overlay layer is emissive).
 */
@FunctionalInterface
public interface GeometryLayer {

    /**
     * Appends this layer's triangles to {@code sink} in emission order.
     *
     * @param sink shared triangle list every layer appends to, rasterized together in one pass
     * @param scene immutable per-render scene state
     */
    void contribute(@NotNull ConcurrentList<VisibleTriangle> sink, @NotNull SceneContext scene);
}
