/**
 * The camera subsystem: a baked {@code display.*} pose applied after the caller's model transform
 * during rasterization.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Camera Camera} is the complete camera - a pose
 * (extrinsics) paired with its {@link lib.minecraft.renderer.engine.camera.Lens Lens} (intrinsics) -
 * that a {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer} renders through. Three
 * factories build one: {@code fromPose(rotation, lens)} (a {@code display.*} GUI pose via vanilla's
 * {@code rotationXYZ}), {@code fromTransform(rotation, translation, scale)} (a full display transform
 * such as a block icon's authored {@code display.gui}, its isotropic scale on an orthographic lens) and
 * {@code identity(lens)}.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Projection Projection} is the catalog that assembles
 * those primitives into named cameras: the {@code VANILLA_ISO} / {@code VANILLA_GUI_ITEM} shipped
 * baselines plus the textbook axonometric / perspective / oblique families, each resolving to a single
 * {@link lib.minecraft.renderer.engine.camera.Camera Camera} (pose + lens + lighting pose).
 * {@link lib.minecraft.renderer.engine.camera.Projection#VANILLA_ISO VANILLA_ISO} is the facing-neutral
 * {@code [30, 225, 0]} iso display pose shared by blocks, players, and entities; each renderer turns its
 * subject to face the camera with its own model-to-world
 * {@link lib.minecraft.renderer.engine.camera.Placement Placement} rather than baking the facing into
 * the pose.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Placement Placement} is the model-to-world half of the
 * pipeline - the per-subject facing / chirality / anchor that seats geometry in the world frame the
 * camera then views. {@link lib.minecraft.renderer.engine.raster.Rasterizer Rasterizer} composes the three as
 * {@code pose x placement x modelTransform}.
 *
 * <p><b>Parity.</b> Everything here is part of a render, and the pipeline dump serialises loaded data
 * without calling a renderer, so the package declares the {@code engine-renders} claim as a demotion
 * of the dump's verdict on a change made here.
 *
 * @see lib.minecraft.renderer.engine.camera.Camera
 * @see lib.minecraft.renderer.engine.camera.Lens
 * @see lib.minecraft.renderer.engine.camera.Projection
 * @see lib.minecraft.renderer.engine.camera.Placement
 * @see lib.minecraft.renderer.engine.raster.Rasterizer
 */
@Parity(claim = "engine-renders", mode = Mode.DEMOTE, scope = Scope.SUBTREE)
package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Scope;
