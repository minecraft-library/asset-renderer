package lib.minecraft.renderer;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.support.StubRendererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the substituting context {@link BlockRenderer} reads every face texture through: a miss
 * draws the checkerboard, a hit is handed back untouched, and the plain context still refuses.
 * <p>
 * The stub carries no block, so a whole render never reaches a texture call - the seam is exercised at
 * the two contexts the renderer picks between rather than through the renderer. The renderer-level
 * proof that all twelve sites substitute is the slow suite, which runs against the real indexes.
 */
@DisplayName("BlockRenderer missing-texture substitution")
class BlockRendererMissingTextureTest {

    private static final String ABSENT = "minecraft:block/block_renderer_missing_texture_absent";
    private static final String PRESENT = "minecraft:block/block_renderer_missing_texture_present";

    private static final PixelBuffer FIXTURE = PixelBuffer.of(new int[]{0xFF102030, 0xFF405060}, 2, 1);

    @Test
    @DisplayName("substituting, a texture no pack supplies draws the checkerboard")
    void aMissSubstitutesTheSprite() {
        RendererContext textures = StubRendererContext.builder().build().withMissingTexture();

        assertThat(textures.requireTexture(ABSENT), sameInstance(MissingSprite.sprite()));
        assertThat(textures.requireTextureAtTick(ABSENT, 7), sameInstance(MissingSprite.sprite()));
    }

    @Test
    @DisplayName("not substituting, a texture no pack supplies raises through both arms")
    void aMissRaisesWhenNotSubstituting() {
        // The caller's own answer, not a property of the id: the same absent id draws above and raises
        // here, which is what lets one texture reference mean two things to two renders. The
        // substitution is in the wrapper alone and never in the two require* defaults, so every caller
        // outside the block and item renderers - fluid, portal, player, elytra, equipment - reaches
        // exactly this. Nothing else in the suite asserts that, so removing it would let the seam drift
        // upstream unnoticed.
        StubRendererContext context = StubRendererContext.builder().build();

        assertThrows(RenderException.class, () -> context.requireTexture(ABSENT));
        assertThrows(RenderException.class, () -> context.requireTextureAtTick(ABSENT, 7));
    }

    @Test
    @DisplayName("a texture a pack does supply is handed back untouched, either way")
    void aHitIsUntouched() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        RendererContext textures = context.withMissingTexture();

        assertThat(textures.requireTexture(PRESENT), sameInstance(FIXTURE));
        assertThat(textures.requireTextureAtTick(PRESENT, 0), sameInstance(FIXTURE));
        assertThat(context.requireTexture(PRESENT), sameInstance(FIXTURE));
        assertThat(context.requireTextureAtTick(PRESENT, 0), sameInstance(FIXTURE));
    }

    @Test
    @DisplayName("the face resolver never answers empty, on either arm")
    void theFaceResolverIsTotal() {
        // Empty is the third answer neither arm may give. A model's element walk DROPS a face it gets
        // empty for, so a render that asked to be refused would come out holed instead, and one that
        // asked for the checkerboard would lose it. Every call site wraps the answer below in
        // Optional.of, so a resolver is total exactly when the arm it reads through is.
        StubRendererContext context = StubRendererContext.builder().build();

        assertThat(context.withMissingTexture().requireTextureAtTick(ABSENT, 0),
            sameInstance(MissingSprite.sprite()));
        assertThrows(RenderException.class, () -> context.requireTextureAtTick(ABSENT, 0));
    }

    @Test
    @DisplayName("the tick arm resolves through the port exactly once")
    void theTickArmIsReached() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        context.withMissingTexture().requireTextureAtTick(PRESENT, 4);

        assertThat(context.getResolved(), contains(PRESENT));
    }

    @Test
    @DisplayName("a resolving id never reaches the substitute")
    void aResolvingIdIsNotSubstituted() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        assertThat(context.withMissingTexture().requireTexture(PRESENT) == MissingSprite.sprite(), is(false));
    }

}
