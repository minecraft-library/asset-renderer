/**
 * The camera subsystem: a baked {@code display.*} pose applied after the caller's model transform
 * during rasterization.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Camera Camera} is the complete camera - a pose
 * (extrinsics) paired with its {@link lib.minecraft.renderer.engine.camera.Lens Lens} (intrinsics) -
 * that a {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine} renders through. Two factories
 * build one: {@code fromPose(rotation, lens)} (a {@code display.*} GUI pose via vanilla's
 * {@code rotationXYZ}) and {@code identity(lens)}.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Projection Projection} is the catalog that assembles
 * those primitives into named cameras: the {@code VANILLA_BLOCK} / {@code VANILLA_PLAYER} /
 * {@code VANILLA_GUI_ITEM} / {@code VANILLA_ENTITY} baselines plus the textbook axonometric /
 * perspective / oblique families, each resolving to a single
 * {@link lib.minecraft.renderer.engine.camera.Camera Camera} (pose + lens + lighting pose). It also
 * owns the entity chirality chain (the {@code [210, 45, 0]} harness
 * iso chain with its det=-1 LER chirality, reused by
 * {@link lib.minecraft.renderer.EntityRenderer EntityRenderer}'s bounds / anchor projection).
 *
 * @see lib.minecraft.renderer.engine.camera.Camera
 * @see lib.minecraft.renderer.engine.camera.Projection
 * @see lib.minecraft.renderer.engine.ModelEngine
 */
package lib.minecraft.renderer.engine.camera;
