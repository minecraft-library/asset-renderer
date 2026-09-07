package lib.minecraft.renderer.support;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.RendererContext;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A pass-through context that answers empty for a named set of texture ids, so a render meets a
 * texture miss while every index and every other texture stays real.
 * <p>
 * It exists because a vanilla-only stack misses nothing: the renderer re-extracts an asset it finds
 * absent on disk, so a texture cannot be made missing by deleting the file. Hiding the id at the
 * lookup is what makes the substitution reachable from a test or a visual driver at all.
 * <p>
 * Overriding {@code resolveTexture} alone hides the id from all four of the port's texture entry
 * points, the other three being defaults built over it. Both sides of the match are canonicalised,
 * because a model's face reference may or may not carry its namespace and a raw string compare would
 * silently hide nothing.
 *
 * @param delegate the real context every other lookup forwards to
 * @param hidden the canonicalised texture ids that answer empty
 */
public record HidingRendererContext(@NotNull RendererContext delegate, @NotNull Set<String> hidden)
    implements RendererContext.Forwarding {

    /**
     * Wraps a context, hiding each of the given texture ids.
     *
     * @param delegate the real context to forward to
     * @param textureIds the texture ids to hide, in any namespaced or bare form
     * @return the wrapping context
     */
    public static @NotNull HidingRendererContext hiding(
        @NotNull RendererContext delegate, @NotNull String... textureIds) {
        return new HidingRendererContext(delegate, Arrays.stream(textureIds)
            .map(id -> ResourceId.parse(id).id())
            .collect(Collectors.toUnmodifiableSet()));
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        if (this.hidden.contains(ResourceId.parse(textureId).id())) return Optional.empty();
        return this.delegate.resolveTexture(textureId);
    }

}
