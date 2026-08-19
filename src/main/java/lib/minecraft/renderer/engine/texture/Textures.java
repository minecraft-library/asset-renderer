package lib.minecraft.renderer.engine.texture;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Function;

/**
 * Pack-aware texture resolution service - the texture subsystem every renderer and engine composes
 * ({@code ModelEngine} and {@code RasterEngine} each hold one; the 2D / 3D scene contexts carry it
 * to their layers) to resolve a texture identifier through the active pack stack:
 * {@code resolveTexture} / {@code resolveTextureAtTick} (animation strip extraction via
 * {@link AnimationKit AnimationKit}) and the {@code minecraft:entity/} entity-texture prefix
 * ({@code resolveEntityTextureAtTick}), plus the static helpers that walk a model's
 * {@code #variable} chains.
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

    /**
     * Walks a {@code #variable} chain until it terminates at a concrete namespaced id or fails
     * to resolve. Handles bare variable names (vanilla shorthand where {@code "texture": "all"}
     * means {@code "texture": "#all"}). Cycle-guarded so a malformed pack cannot hang the caller.
     *
     * @param reference the texture reference, possibly starting with {@code #}
     * @param variables the variable map to resolve against, valued by {@link ModelTexture}
     * @return the resolved namespaced texture id, or the last unresolvable {@code #variable}
     */
    public static @NotNull String resolveTextureReference(@NotNull String reference, @NotNull ConcurrentMap<String, ModelTexture> variables) {
        String current = reference;

        if (!current.startsWith("#") && !current.contains(":") && variables.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return current;
            ModelTexture next = variables.get(current.substring(1));
            if (next == null) return current;
            current = next.sprite();
        }

        return current;
    }

    /**
     * Resolves and loads every unique face texture referenced by a model's elements into a map
     * keyed by the raw {@link ModelFace#getTexture()} reference (including any leading {@code #}).
     * <p>
     * Walks each element's faces, dereferences the {@code #variable} chain via
     * {@link #resolveTextureReference}, skips refs that stay unresolved ({@code #}-prefixed) or
     * blank, and loads each concrete id through the supplied {@code resolve} function exactly
     * once. The caller chooses how a concrete id becomes a {@link PixelBuffer} - block paths pass
     * a tick-aware {@code id -> Optional.of(resolveTextureAtTick(id, 0))}, the entity path passes
     * the context's {@code Optional}-returning lookup - so this helper never decides the
     * resolution strategy. Refs whose {@code resolve} yields an empty {@link Optional} are
     * dropped, leaving the kit to treat them as no-texture faces.
     *
     * @param elements the model elements whose faces reference textures
     * @param textureVars the model's {@code #variable} bindings to resolve refs against
     * @param resolve maps a concrete namespaced texture id to its pixel buffer, or empty to skip
     * @return a new map from raw face ref to its loaded pixel buffer
     */
    public static @NotNull ConcurrentMap<String, PixelBuffer> loadElementFaceTextures(
        @NotNull Iterable<ModelElement> elements,
        @NotNull ConcurrentMap<String, ModelTexture> textureVars,
        @NotNull Function<String, Optional<PixelBuffer>> resolve
    ) {
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || faceTextures.containsKey(ref)) continue;
                String resolvedId = resolveTextureReference(ref, textureVars);
                if (resolvedId.startsWith("#")) continue;
                resolve.apply(resolvedId).ifPresent(buffer -> faceTextures.put(ref, buffer));
            }
        }
        return faceTextures;
    }

    /**
     * Resolves which of a model's element face refs are force-translucent - the raw
     * {@link ModelFace#getTexture()} refs whose texture variable carried {@code force_translucent} in
     * the 26.1 object form. Keys match {@link #loadElementFaceTextures} exactly (the raw ref including
     * any leading {@code #}) and the {@code #variable} chain is walked as in
     * {@link #resolveTextureReference}, so a returned ref is the same key
     * {@link lib.minecraft.renderer.engine.kit.BlockGeometryKit#buildFromElements} looks up. A face is
     * force-translucent when any variable in its deref chain is flagged.
     *
     * @param elements the model's element boxes
     * @param textureVars the model's {@code #variable} bindings, valued by {@link ModelTexture}
     * @return the raw face refs to force into the translucent pass, empty when nothing is flagged
     */
    public static @NotNull ConcurrentSet<String> resolveForceTranslucentRefs(
        @NotNull Iterable<ModelElement> elements,
        @NotNull ConcurrentMap<String, ModelTexture> textureVars
    ) {
        ConcurrentSet<String> refs = Concurrent.newSet();
        if (textureVars.values().stream().noneMatch(ModelTexture::forceTranslucent)) return refs;

        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank() || refs.contains(ref)) continue;
                if (isForceTranslucent(ref, textureVars)) refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Walks the {@code #variable} chain of a face ref, reporting whether any hop resolves to a
     * texture flagged {@code force_translucent}.
     *
     * @param reference the raw face-texture ref, possibly starting with {@code #}
     * @param variables the variable map to resolve against, valued by {@link ModelTexture}
     * @return {@code true} when any variable in the chain carried {@code force_translucent}
     */
    private static boolean isForceTranslucent(
        @NotNull String reference,
        @NotNull ConcurrentMap<String, ModelTexture> variables
    ) {
        String current = reference;
        if (!current.startsWith("#") && !current.contains(":") && variables.containsKey(current))
            current = "#" + current;

        ConcurrentSet<String> visited = Concurrent.newSet();
        while (current.startsWith("#")) {
            if (!visited.add(current)) return false;
            ModelTexture texture = variables.get(current.substring(1));
            if (texture == null) return false;
            if (texture.forceTranslucent()) return true;
            current = texture.sprite();
        }
        return false;
    }

}
