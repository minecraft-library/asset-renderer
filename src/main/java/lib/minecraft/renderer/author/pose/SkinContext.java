package lib.minecraft.renderer.author.pose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A context wrapper answering the rig's reserved skin id from a caller's decoded sheet - every
 * other lookup forwards to the wrapped context untouched.
 *
 * <p>The reserved id is answered ahead of the delegate, and only that id: no shipped row names
 * it and no pack can, vanilla resource paths carrying no {@code $}. The forwarding mixin
 * deliberately leaves {@link RendererContext#findFlipbook findFlipbook} on its default
 * resolution, so the wrapped skin has no animation sidecar and
 * {@link RendererContext#resolveTextureAtTick resolveTextureAtTick} answers it unchanged at
 * every tick.
 *
 * @param delegate the wrapped context every other lookup reaches
 * @param skin the decoded caller skin the reserved id answers with
 */
@Parity(subject = Subject.ENTITY)
record SkinContext(
    @NotNull RendererContext delegate,
    @NotNull PixelBuffer skin
) implements RendererContext.Forwarding {

    /**
     * Answers {@link PlayerRig#SKIN_TEXTURE_ID} with the caller's skin; every other id reaches
     * the delegate.
     *
     * @param textureId the namespaced texture identifier
     * @return the caller's skin for the reserved id, else the delegate's answer
     */
    @Override
    public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
        return PlayerRig.SKIN_TEXTURE_ID.equals(textureId)
            ? Optional.of(this.skin)
            : this.delegate.resolveTexture(textureId);
    }

}
