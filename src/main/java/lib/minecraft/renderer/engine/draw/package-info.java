/**
 * The draw list a rasterizer consumes - the triangles a render hands it, and what each one declares
 * about how it is drawn.
 *
 * <p>{@link lib.minecraft.renderer.engine.draw.VisibleTriangle VisibleTriangle} is one entry of the
 * list: three positions and three UVs, the texture it samples, its tint, its normal, its shade scalar
 * and the surface it is drawn as. That surface is
 * {@link lib.minecraft.renderer.engine.draw.SurfaceTraits SurfaceTraits} - the four flags a builder
 * decides per box, back-face culling, translucency, the glint mask and directional light - beside the
 * {@link lib.minecraft.renderer.engine.draw.PassDeclaration PassDeclaration} the whole pass was
 * submitted under, which says how a surviving fragment is shaded, blended, scaled in opacity,
 * depth-written, ordered and sampled past the sheet's edge.
 *
 * <p>{@link lib.minecraft.renderer.engine.draw.GeometryLayer GeometryLayer} is the one
 * {@link lib.minecraft.renderer.engine.layer.Layer Layer} kind whose target is a triangle list. Every
 * layer appends to one shared sink and the
 * {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer} draws the combined list once, so
 * the order layers append in is the order coplanar ties and translucent surfaces resolve in.
 *
 * <p>These types are read far beyond the rasterizer - by every kit that emits a triangle, every
 * renderer that splices a layer and the pass that relights a folded stack - which is what keeps the
 * draw list apart from the raster arithmetic, whose one reader is the rasterizer itself.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code port}, {@code content}, {@code bake}, {@code screen} or the root package.
 */
package lib.minecraft.renderer.engine.draw;
