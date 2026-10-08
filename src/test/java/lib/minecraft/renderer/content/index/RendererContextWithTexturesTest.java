package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the animation pin {@link RendererContext#withTextures} makes for a substituted texture,
 * which has to be made twice - the static atlas stands on it to flatten every animated texture.
 * <p>
 * The context answers "does this texture animate" through two doors a wrapper can move independently:
 * the concrete context derives {@code findAnimation} from {@code findMeta}, while the forwarding mixin
 * hands {@code findMeta} straight to the delegate. A wrapper pinning only the derived one says nothing
 * animates through one and hands back a populated animation section through the other.
 * <p>
 * Nothing reads the second door today, so no render can see the contradiction and no rendered atlas
 * would fail if the pin came undone. That is exactly why it is asserted here rather than left to a
 * sweep: this is the only thing that would notice.
 * <p>
 * The same wrapper shadows its delegate: a source that serves an id answers for it - an answer holding
 * no pixels included - and only an id the source does not serve reaches the delegate. No shipped source
 * answers empty, so that too is visible here alone.
 */
@DisplayName("A substituted texture pins animation at both doors")
class RendererContextWithTexturesTest {

    private static final String ID = "minecraft:block/static_context_animated";

    /** A sidecar carrying every section, so a pin that empties the whole document is caught too. */
    private static final @NotNull MCMeta FULL = new MCMeta(
        ResourceId.parse(ID),
        Optional.empty(),
        Optional.of(new MCMeta.Animation(2, true, 16, 16, Concurrent.newList())),
        Optional.of(new MCMeta.TextureFlags(true, true)),
        Optional.of(new MCMeta.GuiScaling(
            MCMeta.GuiScaling.Type.NINE_SLICE, 8, 8, new MCMeta.GuiScaling.Border(1, 2, 3, 4), true)),
        Optional.of(new MCMeta.Villager(MCMeta.Villager.Hat.FULL)));

    /**
     * A delegate that animates: it answers a populated sidecar and, exactly as the concrete context
     * does, derives its animation answer from that same sidecar. The derivation is reproduced here
     * rather than stubbed independently, because it is what couples the two doors.
     *
     * @param delegate the stub every other lookup forwards to
     */
    private record Animating(@NotNull RendererContext delegate) implements RendererContext.Forwarding {

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<MCMeta> findMeta(@NotNull String textureId) {
            return Optional.of(FULL);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Optional<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            return findMeta(textureId).flatMap(MCMeta::animation);
        }

        /**
         * {@inheritDoc}
         * <p>
         * Derived off this context's own animation and strip, so the playback table describes the
         * texture this context serves rather than the delegate's.
         */
        @Override
        public @NotNull Optional<Flipbook> findFlipbook(@NotNull String textureId) {
            return Flipbook.of(findAnimation(textureId), () -> resolveTexture(textureId));
        }

    }

    private static final @NotNull RendererContext ANIMATING =
        new Animating(RendererContext.builder().build());

    /** The pixels the substituting source answers with, standing in for a flattened strip frame. */
    private static final @NotNull PixelBuffer FRAME = PixelBuffer.create(16, 16);

    /**
     * Substitutes every texture id with {@link #FRAME}, the shape the static atlas's view takes.
     *
     * @param context the context to wrap
     * @return the substituting context
     */
    private static @NotNull RendererContext substituted(@NotNull RendererContext context) {
        return context.withTextures(textureId -> Possible.of(FRAME));
    }

    /**
     * A recorder over a context serving {@link #ID} as {@link #FRAME}, so a test can see whether the
     * wrapper asked it.
     *
     * @return the recording delegate
     */
    private static @NotNull RecordingContext servingFrame() {
        return RecordingContext.over(RendererContext.builder().textures(Map.of(ID, FRAME)).build());
    }

    @Test
    @DisplayName("a source answering empty for an id answers for it, and the delegate is never asked")
    void anEmptySourceAnswerShadowsTheDelegate() {
        RecordingContext delegate = servingFrame();
        RendererContext wrapped = delegate.withTextures(textureId -> Possible.empty());

        assertThat(wrapped.resolveTexture(ID).getState(), is(Possible.State.EMPTY));
        assertThat("the delegate is not asked for an id the source serves", delegate.getResolved(), is(empty()));
    }

    @Test
    @DisplayName("a source answering absent for an id hands it to the delegate")
    void anAbsentSourceAnswerFallsThrough() {
        RecordingContext delegate = servingFrame();
        RendererContext wrapped = delegate.withTextures(textureId -> Possible.absent());

        assertThat(wrapped.resolveTexture(ID).orElseThrow(), is(sameInstance(FRAME)));
        assertThat(delegate.getResolved(), contains(ID));
    }

    @Test
    @DisplayName("an id the source serves as empty plays none of the delegate's animation")
    void anEmptySourceAnswerPinsAnimation() {
        RendererContext wrapped = ANIMATING.withTextures(textureId -> Possible.empty());

        assertThat("the derived answer", wrapped.findAnimation(ID).isPresent(), is(false));
        assertThat("the playback table", wrapped.findFlipbook(ID).isPresent(), is(false));
        assertThat("the sidecar's own section", wrapped.findMeta(ID).flatMap(MCMeta::animation).isPresent(), is(false));
    }

    @Test
    @DisplayName("the delegate animates through both doors, so the pin has something to close")
    void theDelegateAnimates() {
        // Without this the rows below would pass against a delegate that never animated at all.
        assertThat(ANIMATING.findAnimation(ID).isPresent(), is(true));
        assertThat(ANIMATING.findMeta(ID).flatMap(MCMeta::animation).isPresent(), is(true));
    }

    @Test
    @DisplayName("wrapped, neither door answers an animation")
    void bothDoorsArePinned() {
        RendererContext staticContext = substituted(ANIMATING);

        assertThat("the derived answer", staticContext.findAnimation(ID).isPresent(), is(false));
        assertThat("the sidecar's own section", staticContext.findMeta(ID).flatMap(MCMeta::animation).isPresent(), is(false));
    }

    @Test
    @DisplayName("every other section survives the pin")
    void onlyAnimationIsRemoved() {
        // The pin is against animation and nothing else: emptying the whole sidecar would take the gui
        // scaling and villager hat with it, which the static atlas has no reason to refuse.
        MCMeta pinned = substituted(ANIMATING).findMeta(ID).orElseThrow();

        assertThat(pinned.id(), is(FULL.id()));
        assertThat(pinned.texture(), is(FULL.texture()));
        assertThat(pinned.gui(), is(FULL.gui()));
        assertThat(pinned.villager(), is(FULL.villager()));
        assertThat(pinned.animation().isPresent(), is(false));
    }

    @Test
    @DisplayName("a texture with no sidecar at all stays absent rather than becoming a blank one")
    void anAbsentSidecarStaysAbsent() {
        RendererContext bare = RendererContext.builder().build();

        assertThat(substituted(bare).findMeta(ID).isPresent(), is(false));
    }

}
