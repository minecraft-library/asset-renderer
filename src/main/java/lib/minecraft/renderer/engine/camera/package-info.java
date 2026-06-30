/**
 * The camera subsystem: a baked {@code display.*} pose applied after the caller's model transform
 * during rasterization.
 *
 * <p>{@link lib.minecraft.renderer.engine.camera.Camera Camera} is a value type wrapping the pose
 * matrix a {@link lib.minecraft.renderer.engine.ModelEngine ModelEngine} composes with every render.
 * Factories pick the named vanilla pose - {@code forBlockIcon()} ({@code [30, 225, 0]}),
 * {@code forPlayerIcon()} ({@code [30, 45, 0]}), {@code forEntityIcon()} (the {@code [210, 45, 0]}
 * harness iso chain with its LER chirality), and {@code withGuiPose(rotation)} for model-authored
 * overrides. The shared {@code entityIsoChain()} builder is the single source of truth for the
 * entity iso prefix, reused by {@link lib.minecraft.renderer.EntityRenderer EntityRenderer}'s
 * bounds / anchor projection.
 *
 * @see lib.minecraft.renderer.engine.camera.Camera
 * @see lib.minecraft.renderer.engine.ModelEngine
 */
package lib.minecraft.renderer.engine.camera;
