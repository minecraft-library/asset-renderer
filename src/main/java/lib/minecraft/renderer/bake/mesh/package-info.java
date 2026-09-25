/**
 * Emitting a subject's triangles - the walk from a decoded model, mesh or algorithmic shape to the
 * draw list a rasterizer consumes.
 *
 * <p>Each kit reads one kind of subject.
 * {@link lib.minecraft.renderer.bake.mesh.BlockGeometryKit BlockGeometryKit} walks the element list a
 * block or item model ships, and the relative bone tree a block entity ships instead.
 * {@link lib.minecraft.renderer.bake.mesh.EntityGeometryKit EntityGeometryKit} assembles an
 * {@link lib.minecraft.renderer.asset.mesh.EntityMesh EntityMesh} in vanilla's own model frame and
 * fits it to the canvas, measuring the bounds that fit is taken from.
 * {@link lib.minecraft.renderer.bake.mesh.FluidGeometryKit FluidGeometryKit} builds the one fluid cube
 * vanilla draws in code, with its sloped top and flow-turned sides.
 * {@link lib.minecraft.renderer.bake.mesh.ShieldKit ShieldKit} builds the shield item's plate and handle
 * at its gui pose, and carries a patterned banner or shield onto the flat item slab.
 * {@link lib.minecraft.renderer.bake.mesh.PlayerAssembly PlayerAssembly} is the player in three
 * dimensions - the boxes a body scope is built from and the cape seated on its torso, the light they
 * are read under and the raster that finishes them.
 *
 * <p>{@link lib.minecraft.renderer.bake.mesh.BoneKit BoneKit} is the bone-chain arithmetic the block and
 * entity kits both drive - the ancestor anchors, the per-cube transform, the grown cube bounds and the
 * unwrap rules - held once because its float result depends on the order of its operations. Each kit
 * keeps its own frame-specific emit stage.
 *
 * <p>{@link lib.minecraft.renderer.bake.mesh.DisplayCamera DisplayCamera} is the camera a model's
 * authored {@code display.gui} transform bakes to, read as a pose and a lens.
 *
 * <p>A type that yields no draw list does not belong here.
 *
 * <p><b>Parity.</b> Every member declares its own claims; the package declares none.
 */
package lib.minecraft.renderer.bake.mesh;
