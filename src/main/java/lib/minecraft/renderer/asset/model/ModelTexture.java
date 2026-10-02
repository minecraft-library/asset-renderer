package lib.minecraft.renderer.asset.model;

import lib.minecraft.renderer.content.json.ModelTextureAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * One value of a model's {@code textures} map - a sprite reference plus the 26.1 sampler flags it
 * may carry in the object form {@code {"sprite": ..., "force_translucent": true}}.
 * <p>
 * The {@link ModelTextureAdapter} registered for the type reads both authored shapes: a bare string
 * flattens to {@code ModelTexture(sprite, false)}, the object form to
 * {@code {sprite, force_translucent}}. A model's {@code textures} map is therefore a single
 * {@code Map<String, ModelTexture>} whichever shape each slot was written in, with no side channel.
 * <p>
 * {@link #forceTranslucent} is consumed by {@link ModelData#resolveForceTranslucentRefs}, which forces
 * the flagged face refs into the translucent pass.
 *
 * @param sprite the resolved sprite id (the object form's {@code sprite} value, or the bare string)
 * @param forceTranslucent whether the object form flagged {@code force_translucent}
 */
public record ModelTexture(@NotNull String sprite, boolean forceTranslucent) {}
