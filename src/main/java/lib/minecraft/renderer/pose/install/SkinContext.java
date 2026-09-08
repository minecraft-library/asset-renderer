package lib.minecraft.renderer.pose.install;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A context wrapper answering the rig's reserved skin id from a caller's decoded sheet - every
 * other lookup forwards to the wrapped context untouched.
 *
 * <p>The reserved id is answered ahead of the delegate, and only that id - no shipped row names it.
 * Its pixels and its metadata both stop here: a decoded sheet is pixels and nothing else, so a
 * sidecar for that id could only have come from the delegate and would describe a texture this
 * context does not serve. Answering the sidecar doors empty is what makes that impossible rather
 * than merely unlikely, and it is why the wrapper stays correct however the reserved id is spelled.
 *
 * <p>{@link RendererContext#findFlipbook findFlipbook} follows from the same pin: the forwarding
 * mixin deliberately leaves it on its default resolution, which reads the two lookups pinned here,
 * so the wrapped skin has no flipbook and
 * {@link RendererContext#resolveTextureAtTick resolveTextureAtTick} answers it unchanged at every
 * tick.
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

    /**
     * Answers no sidecar for {@link PlayerRig#SKIN_TEXTURE_ID}, so the reserved id's pixels and its
     * metadata come from the same place; every other id reaches the delegate.
     *
     * @param textureId the namespaced texture identifier
     * @return empty for the reserved id, else the delegate's answer
     */
    @Override
    public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
        return PlayerRig.SKIN_TEXTURE_ID.equals(textureId)
            ? Optional.empty()
            : this.delegate.findMeta(textureId);
    }

    /**
     * Answers no animation for {@link PlayerRig#SKIN_TEXTURE_ID}, pinned beside the sidecar it is
     * derived from so the two cannot disagree; every other id reaches the delegate.
     *
     * @param textureId the namespaced texture identifier
     * @return empty for the reserved id, else the delegate's answer
     */
    @Override
    public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
        return PlayerRig.SKIN_TEXTURE_ID.equals(textureId)
            ? Optional.empty()
            : this.delegate.findAnimation(textureId);
    }

}
