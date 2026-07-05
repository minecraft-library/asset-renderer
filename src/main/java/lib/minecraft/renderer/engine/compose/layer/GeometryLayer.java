package lib.minecraft.renderer.engine.compose.layer;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;

/**
 * Contributor of 3D geometry to a single shared rasterization pass - a {@link Layer} over the shared
 * triangle sink.
 * <p>
 * Unlike a 2D {@link ImageLayer}, a geometry layer does not produce its own pixel frame. It appends
 * triangles to a {@code sink} that every layer shares, and the renderer rasterizes the combined list
 * once into a shared depth buffer. This is required: the rasterizer's back-to-front translucent sort,
 * its GL_LEQUAL last-drawn-wins depth test, and its emissive depth-write skip are all coupled across
 * the whole triangle list, so layers cannot be rendered to independent frames and stacked.
 * <p>
 * Layers capture whatever per-render inputs they need (textures, transforms, scene values) at
 * construction; the contract is intentionally context-free so a single {@code GeometryLayer} type
 * serves every 3D renderer (entity, block, fluid) without a renderer-specific context object.
 * <p>
 * <b>Emission order is load-bearing.</b> The order in which layers append - and the order in which a
 * layer appends within itself - determines coplanar depth tie-breaks and translucent ordering.
 * Layers must append in a deterministic order and must not sort or filter the shared sink. Each layer
 * owns the {@link SurfaceTraits} of the triangles it contributes (a body layer is opaque, an armor
 * layer is glinted, an eye-overlay layer is emissive).
 */
@FunctionalInterface
public interface GeometryLayer extends Layer<ConcurrentList<VisibleTriangle>> {

}
