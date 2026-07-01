/**
 * The camera subsystem: a baked {@code display.*} pose applied after the caller's model transform
 * during rasterization.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Camera Camera} is the value type wrapping the pose
 * matrix a {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine} composes with every render,
 * plus two GUI-pose primitives that build one: {@code fromPose(rotation)} (a {@code display.*} GUI pose
 * via vanilla's {@code rotationXYZ}) and {@code identity()}.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Projection Projection} is the catalog that assembles
 * those primitives into named poses: the {@code VANILLA_BLOCK} / {@code VANILLA_PLAYER} /
 * {@code VANILLA_GUI_ITEM} / {@code VANILLA_ENTITY} baselines plus the textbook axonometric /
 * perspective / oblique families, each resolving to a {@code (Camera, Lens, lightingPose)} triple. It
 * also owns the entity chirality chain (the {@code [210, 45, 0]} harness iso chain with its det=-1 LER
 * chirality, reused by {@link lib.minecraft.renderer.EntityRenderer EntityRenderer}'s bounds / anchor
 * projection).
 *
 * @see lib.minecraft.renderer.engine.camera.Camera
 * @see lib.minecraft.renderer.engine.camera.Projection
 * @see lib.minecraft.renderer.engine.ModelEngine
 */
package lib.minecraft.renderer.engine.camera;
