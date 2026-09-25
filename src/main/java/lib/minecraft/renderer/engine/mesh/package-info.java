/**
 * Turning a box into triangles, and the stand-in drawn when there is no box to turn.
 *
 * <p>{@link lib.minecraft.renderer.engine.mesh.BoxKit BoxKit} is the one box builder every subject is
 * drawn through. {@code buildBox} emits a box's twelve triangles under the
 * {@link lib.minecraft.renderer.engine.draw.SurfaceTraits SurfaceTraits} and per-pixel trace name the
 * caller declares, and {@code unitCube} is that over the engine's normalized cube. {@code addQuad} is
 * the quad emitter beneath both and beneath the kits that emit their own quads: it splits a quad on
 * the diagonal the caller's {@link lib.minecraft.renderer.engine.geometry.CornerPhase CornerPhase}
 * pins, and carries the shade scalar the caller hands it. Every direction-aware answer - the normal,
 * the winding, the default UV derivation - lives on the face vocabulary in
 * {@link lib.minecraft.renderer.engine.geometry engine.geometry} rather than here.
 *
 * <p>{@link lib.minecraft.renderer.engine.mesh.MissingMesh MissingMesh} is what draws for a subject
 * nothing resolved for at all: a unit cube wearing the
 * {@link lib.minecraft.renderer.engine.texture.MissingSprite MissingSprite} checkerboard on every face,
 * untinted, and the same checkerboard scaled onto a square canvas for an inventory picture. A texture
 * that fails inside a model that resolved never reaches it - that face substitutes its own texels and
 * keeps its geometry.
 *
 * <p>A type no render reaches does not belong here, and neither does one whose code, imports and
 * javadoc aside, names a Minecraft subject - a block, an item, an entity, a {@code minecraft:} id or
 * a vanilla class - or one that imports from {@code vanilla}, {@code asset}, {@code request},
 * {@code port}, {@code content}, {@code bake}, {@code screen} or the root package. A builder that
 * walks one subject's own model into boxes is that subject's, and is not here.
 */
package lib.minecraft.renderer.engine.mesh;
