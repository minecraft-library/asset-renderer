package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the static atlas's animation pin, which has to be made twice.
 * <p>
 * The port answers "does this texture animate" through two doors a wrapper can move independently:
 * the concrete context derives {@code findAnimation} from {@code findMeta}, while the forwarding mixin
 * hands {@code findMeta} straight to the delegate. A wrapper pinning only the derived one says nothing
 * animates through one and hands back a populated animation section through the other.
 * <p>
 * Nothing reads the second door today, so no render can see the contradiction and no rendered atlas
 * would fail if the pin came undone. That is exactly why it is asserted here rather than left to a
 * sweep: this is the only thing that would notice.
 */
@DisplayName("The static atlas pins animation at both doors")
class StaticTextureContextTest {

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

    }

    private static final @NotNull RendererContext ANIMATING =
        new Animating(StubRendererContext.builder().build());

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
        RendererContext staticContext = new AtlasRenderer.StaticTextureContext(ANIMATING);

        assertThat("the derived answer", staticContext.findAnimation(ID).isPresent(), is(false));
        assertThat("the sidecar's own section", staticContext.findMeta(ID).flatMap(MCMeta::animation).isPresent(), is(false));
    }

    @Test
    @DisplayName("every other section survives the pin")
    void onlyAnimationIsRemoved() {
        // The pin is against animation and nothing else: emptying the whole sidecar would take the gui
        // scaling and villager hat with it, which the static atlas has no reason to refuse.
        MCMeta pinned = new AtlasRenderer.StaticTextureContext(ANIMATING).findMeta(ID).orElseThrow();

        assertThat(pinned.id(), is(FULL.id()));
        assertThat(pinned.texture(), is(FULL.texture()));
        assertThat(pinned.gui(), is(FULL.gui()));
        assertThat(pinned.villager(), is(FULL.villager()));
        assertThat(pinned.animation().isPresent(), is(false));
    }

    @Test
    @DisplayName("a texture with no sidecar at all stays absent rather than becoming a blank one")
    void anAbsentSidecarStaysAbsent() {
        RendererContext bare = StubRendererContext.builder().build();

        assertThat(new AtlasRenderer.StaticTextureContext(bare).findMeta(ID).isPresent(), is(false));
    }

}
