package lib.minecraft.renderer;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.texture.MissingTexture;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.support.StubRendererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the substituting lookups {@link BlockRenderer} reads every face texture through: a miss
 * draws the checkerboard, a hit is handed back untouched, and the port's own refusing arm still
 * refuses.
 * <p>
 * The stub carries no block, so a whole render never reaches a texture call - the seam is exercised at
 * the helper the renderer calls rather than through the renderer. The renderer-level proof that all
 * twelve sites substitute is the slow suite, which runs against the real indexes.
 */
@DisplayName("BlockRenderer missing-texture substitution")
class BlockRendererMissingTextureTest {

    private static final String ABSENT = "minecraft:block/block_renderer_missing_texture_absent";
    private static final String PRESENT = "minecraft:block/block_renderer_missing_texture_present";

    private static final PixelBuffer FIXTURE = PixelBuffer.of(new int[]{0xFF102030, 0xFF405060}, 2, 1);

    @Test
    @DisplayName("substituting, a texture no pack supplies draws the checkerboard")
    void aMissSubstitutesTheSprite() {
        StubRendererContext context = StubRendererContext.builder().build();

        assertThat(MissingTexture.texture(context, ABSENT, true), sameInstance(MissingTexture.sprite()));
        assertThat(MissingTexture.textureAtTick(context, ABSENT, 7, true), sameInstance(MissingTexture.sprite()));
    }

    @Test
    @DisplayName("not substituting, a texture no pack supplies raises through both arms")
    void aMissRaisesWhenNotSubstituting() {
        // The caller's own answer, not a property of the id: the same absent id draws above and raises
        // here, which is what lets one texture reference mean two things to two renders.
        StubRendererContext context = StubRendererContext.builder().build();

        assertThrows(RenderException.class, () -> MissingTexture.texture(context, ABSENT, false));
        assertThrows(RenderException.class, () -> MissingTexture.textureAtTick(context, ABSENT, 7, false));
    }

    @Test
    @DisplayName("a texture a pack does supply is handed back untouched, either way")
    void aHitIsUntouched() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        assertThat(MissingTexture.texture(context, PRESENT, true), sameInstance(FIXTURE));
        assertThat(MissingTexture.textureAtTick(context, PRESENT, 0, true), sameInstance(FIXTURE));
        assertThat(MissingTexture.texture(context, PRESENT, false), sameInstance(FIXTURE));
        assertThat(MissingTexture.textureAtTick(context, PRESENT, 0, false), sameInstance(FIXTURE));
    }

    @Test
    @DisplayName("the face resolver never answers empty, on either arm")
    void theFaceResolverIsTotal() {
        // Empty is the third answer neither arm may give. A model's element walk DROPS a face it gets
        // empty for, so a render that asked to be refused would come out holed instead, and one that
        // asked for the checkerboard would lose it.
        StubRendererContext context = StubRendererContext.builder().build();

        assertThat(MissingTexture.faces(context, 0, true).apply(ABSENT),
            is(Optional.of(MissingTexture.sprite())));
        assertThrows(RenderException.class, () -> MissingTexture.faces(context, 0, false).apply(ABSENT));
    }

    @Test
    @DisplayName("the tick arm resolves through the port exactly once")
    void theTickArmIsReached() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        MissingTexture.textureAtTick(context, PRESENT, 4, true);

        assertThat(context.getResolved(), contains(PRESENT));
    }

    @Test
    @DisplayName("the port's requiring arm still refuses an absent texture")
    void thePortStillRefuses() {
        // The substitution is at the twelve call sites, never in the two require* defaults, so every
        // caller outside the block and item renderers - fluid, portal, player, elytra, equipment -
        // still raises on a miss. Nothing else in the suite asserts this, so removing it would let
        // the seam drift upstream unnoticed.
        StubRendererContext context = StubRendererContext.builder().build();

        assertThrows(RenderException.class, () -> context.requireTexture(ABSENT));
        assertThrows(RenderException.class, () -> context.requireTextureAtTick(ABSENT, 0));
    }

    @Test
    @DisplayName("a resolving id never reaches the substitute")
    void aResolvingIdIsNotSubstituted() {
        StubRendererContext context = StubRendererContext.builder()
            .texturesById(Map.of(PRESENT, FIXTURE))
            .build();

        assertThat(MissingTexture.texture(context, PRESENT, true) == MissingTexture.sprite(), is(false));
    }

}
