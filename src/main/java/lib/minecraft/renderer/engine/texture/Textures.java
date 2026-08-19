package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Pack-aware texture resolution service - the texture subsystem every renderer and engine composes
 * ({@code ModelEngine} and {@code RasterEngine} each hold one; the 2D / 3D scene contexts carry it
 * to their layers) to resolve a texture identifier through the active pack stack:
 * {@code resolveTexture} / {@code resolveTextureAtTick} (animation strip extraction via
 * {@link AnimationKit AnimationKit}) and the {@code minecraft:entity/} entity-texture prefix
 * ({@code resolveEntityTextureAtTick}). A model's own {@code #variable} chains are walked by
 * {@code ModelData}, which holds the bindings they resolve against.
 *
 * <p>Stateless beyond its {@link RendererContext}. All methods are idempotent and thread-safe
 * provided the underlying context is too.
 *
 * @see RendererContext
 */
@Getter
@RequiredArgsConstructor
public class Textures {

    private final @NotNull RendererContext context;

    /**
     * Resolves a texture identifier through the active pack stack, throwing if no pack provides it.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture
     * @throws RenderException if no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId).orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Resolves a texture identifier, returning empty instead of throwing when the pack stack has
     * no match. Useful for optional overlays where the caller wants a graceful fallback.
     *
     * @param textureId the namespaced texture identifier
     * @return the decoded texture, or empty if unknown
     */
    public @NotNull Optional<PixelBuffer> tryResolveTexture(@NotNull String textureId) {
        return this.context.resolveTexture(textureId);
    }

    /**
     * Resolves an entity texture ref against the vanilla pack at {@code minecraft:entity/<ref>} at a
     * specific animation tick, wrapping {@link #tryResolveTextureAtTick} with the
     * {@code minecraft:entity/} prefix. Centralises the {@code minecraft:entity/} prefix idiom the
     * entity renderer's base / overlay / collar / equipment / family-member paths all share. A
     * sidecar-less entity texture (every vanilla entity) returns its buffer unchanged, so
     * {@code tick 0} is byte-identical to the raw lookup; a sidecar-carrying texture samples the frame
     * for {@code tick} (frame-0-at-default when static).
     *
     * @param ref the entity texture sub-path (without the {@code minecraft:entity/} prefix or the
     *     {@code .png} suffix)
     * @param tick the current animation tick (free-running, signed)
     * @return the resolved frame, or empty when the pack has no match
     */
    public @NotNull Optional<PixelBuffer> resolveEntityTextureAtTick(@NotNull String ref, int tick) {
        return tryResolveTextureAtTick("minecraft:entity/" + ref, tick);
    }

    /**
     * Returns the parsed {@code .mcmeta} animation sidecar for the given texture, if any. Wraps
     * {@link RendererContext#findAnimation(String)}.
     *
     * @param textureId the namespaced texture identifier
     * @return the animation metadata, or empty when the texture has no sidecar
     */
    private @NotNull Optional<AnimationData> findAnimation(@NotNull String textureId) {
        return this.context.findAnimation(textureId);
    }

    /**
     * Resolves a texture and returns the specific animation frame that should be displayed at
     * the given tick. For textures without an {@code .mcmeta} sidecar the source buffer is
     * returned unchanged; for animated textures {@link AnimationKit#sampleFrame} extracts the
     * correct strip frame, blending adjacent frames when {@link AnimationData#interpolate()}
     * is set.
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick
     * @throws RenderException when no pack provides the texture
     */
    public @NotNull PixelBuffer resolveTextureAtTick(@NotNull String textureId, int tick) {
        return tryResolveTextureAtTick(textureId, tick)
            .orElseThrow(() -> new RenderException("No texture registered for id '%s'", textureId));
    }

    /**
     * Like {@link #resolveTextureAtTick} but returns empty instead of throwing when the pack stack
     * has no match - the frame-flattening counterpart of {@link #tryResolveTexture}. For textures
     * without an {@code .mcmeta} sidecar the source buffer is returned unchanged; for animated
     * textures {@link AnimationKit#sampleFrame} extracts the correct strip frame (blending adjacent
     * frames when {@link AnimationData#interpolate()} is set).
     *
     * @param textureId the namespaced texture identifier
     * @param tick the current animation tick (free-running, signed)
     * @return the frame to render at this tick, or empty when the texture is unknown
     */
    public @NotNull Optional<PixelBuffer> tryResolveTextureAtTick(@NotNull String textureId, int tick) {
        Optional<PixelBuffer> strip = tryResolveTexture(textureId);
        if (strip.isEmpty()) return strip;
        Optional<AnimationData> animation = findAnimation(textureId);
        return animation.map(animationData -> AnimationKit.sampleFrame(strip.get(), animationData, tick)).or(() -> strip);
    }

}
