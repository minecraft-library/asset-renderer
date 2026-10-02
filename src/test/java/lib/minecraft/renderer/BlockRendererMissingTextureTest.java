package lib.minecraft.renderer;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.support.RecordingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the substituting context {@link BlockRenderer} reads every face texture through: a miss
 * draws the checkerboard, a hit is handed back untouched, and the plain context answers empty.
 * <p>
 * The in-memory context carries no block, so a whole render never reaches a texture call - the seam is
 * exercised at the two contexts the renderer picks between rather than through the renderer. The
 * renderer-level proof that all twelve sites substitute is the slow suite, which runs against the real
 * indexes.
 */
@DisplayName("BlockRenderer missing-texture substitution")
class BlockRendererMissingTextureTest {

    private static final String ABSENT = "minecraft:block/block_renderer_missing_texture_absent";
    private static final String PRESENT = "minecraft:block/block_renderer_missing_texture_present";

    private static final PixelBuffer FIXTURE = PixelBuffer.of(new int[]{0xFF102030, 0xFF405060}, 2, 1);

    /**
     * The frame a context answers for a texture at a tick - what every tick-sampling call site asks.
     *
     * @param context the context the texture resolves through
     * @param textureId the texture id
     * @param tick the animation tick
     * @return the frame, or empty when the context does not resolve the texture
     */
    private static Optional<PixelBuffer> frame(RendererContext context, String textureId, int tick) {
        return Flipbook.atTick(context.resolveTexture(textureId), context.findFlipbook(textureId), tick);
    }

    @Test
    @DisplayName("substituting, a texture no pack supplies draws the checkerboard")
    void aMissSubstitutesTheSprite() {
        RendererContext textures = RendererContext.builder().build().withMissingTexture();

        assertThat(textures.resolveTexture(ABSENT).orElseThrow(), sameInstance(MissingSprite.sprite()));
        assertThat(frame(textures, ABSENT, 7).orElseThrow(), sameInstance(MissingSprite.sprite()));
    }

    @Test
    @DisplayName("not substituting, a texture no pack supplies answers empty through both arms")
    void aMissIsEmptyWhenNotSubstituting() {
        // The caller's own answer, not a property of the id: the same absent id draws above and is
        // empty here, which is what lets one texture reference mean two things to two renders. The
        // substitution is in the wrapper alone, so every caller outside the block and item renderers -
        // fluid, portal, player, elytra, equipment - reads exactly this empty and refuses it at its
        // own call site. Nothing else in the suite asserts that, so removing it would let the seam
        // drift upstream unnoticed.
        RendererContext context = RendererContext.builder().build();

        assertThat(context.resolveTexture(ABSENT).isEmpty(), is(true));
        assertThat(frame(context, ABSENT, 7).isEmpty(), is(true));
    }

    @Test
    @DisplayName("a texture a pack does supply is handed back untouched, either way")
    void aHitIsUntouched() {
        RendererContext context = RendererContext.builder()
            .textures(Map.of(PRESENT, FIXTURE))
            .build();

        RendererContext textures = context.withMissingTexture();

        assertThat(textures.resolveTexture(PRESENT).orElseThrow(), sameInstance(FIXTURE));
        assertThat(frame(textures, PRESENT, 0).orElseThrow(), sameInstance(FIXTURE));
        assertThat(context.resolveTexture(PRESENT).orElseThrow(), sameInstance(FIXTURE));
        assertThat(frame(context, PRESENT, 0).orElseThrow(), sameInstance(FIXTURE));
    }

    @Test
    @DisplayName("the substituting frame is never empty, and the plain one is for a miss")
    void theSubstitutingFrameIsTotal() {
        // Empty is the answer the substituting arm may never give. A model's element walk DROPS a face
        // it gets empty for, so a render that asked for the checkerboard would come out holed instead,
        // and one that asked to be refused must see the empty to refuse it.
        RendererContext context = RendererContext.builder().build();

        assertThat(frame(context.withMissingTexture(), ABSENT, 0).orElseThrow(), sameInstance(MissingSprite.sprite()));
        assertThat(frame(context, ABSENT, 0).isEmpty(), is(true));
    }

    @Test
    @DisplayName("the tick arm resolves through the context exactly once")
    void theTickArmIsReached() {
        RecordingContext context = RecordingContext.over(RendererContext.builder()
            .textures(Map.of(PRESENT, FIXTURE))
            .build());

        frame(context.withMissingTexture(), PRESENT, 4);

        assertThat(context.getResolved(), contains(PRESENT));
    }

    @Test
    @DisplayName("a resolving id never reaches the substitute")
    void aResolvingIdIsNotSubstituted() {
        RendererContext context = RendererContext.builder()
            .textures(Map.of(PRESENT, FIXTURE))
            .build();

        assertThat(context.withMissingTexture().resolveTexture(PRESENT).orElseThrow() == MissingSprite.sprite(), is(false));
    }

}
