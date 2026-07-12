package lib.minecraft.renderer.asset.model;

import org.jetbrains.annotations.NotNull;

/**
 * One value of a model's {@code textures} map - a sprite reference plus the 26.1 sampler flags it
 * may carry in the object form {@code {"sprite": ..., "force_translucent": true}}.
 * <p>
 * Retained but not yet consumed: the render path still derives translucency from texel alpha
 * ({@code BoneKit.faceHasPartialAlpha}), which already classifies every vanilla glass texture, so
 * {@link #forceTranslucent} is a forward hook a later trigger folds into the translucent sort. The
 * object form is captured onto {@link ModelData#textureObjects()} while the flattened sprite string
 * continues to populate {@link ModelData#textures()} unchanged.
 *
 * @param sprite the resolved sprite id (the object form's {@code sprite} value)
 * @param forceTranslucent whether the object form flagged {@code force_translucent}
 */
public record ModelTexture(@NotNull String sprite, boolean forceTranslucent) {}
