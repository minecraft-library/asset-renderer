package lib.minecraft.renderer.content.index;

import dev.simplified.image.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.diagnostic.Substitutions;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.support.RecordingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the substituting wrapper {@link RendererContext#withMissingTexture()} mints: an
 * unresolved id draws the checkerboard and is reported once through {@link Substitutions#texture}
 * rather than once per face, and an id served with no pixels draws it too, reported once through
 * {@link Substitutions#unreadableTexture} instead. Read bare, the context beneath answers a miss empty
 * through the lookup and the frame alike, and a texture a pack supplies is handed back untouched
 * either way.
 * <p>
 * The reporting set is the channel's, static and living as long as the process, so every id below is
 * unique to the test that names it and no test asserts that nothing has been reported yet.
 */
@DisplayName("A substituting context draws the checkerboard and reports once")
class RendererContextWithMissingTextureTest {

    @Test
    @DisplayName("a miss substitutes the sprite and reports the id once")
    void reportsAnUnresolvedIdOnce() {
        RendererContext context = RendererContext.builder().build().withMissingTexture();
        String id = "minecraft:block/missing_texture_test_reported_once";

        String first = errDuring(() -> assertThat(
            context.resolveTexture(id).orElseThrow() == MissingSprite.sprite(), is(true)));
        String second = errDuring(() -> context.resolveTexture(id));

        assertThat(first, containsString("Missing texture '" + id + "' - drawing the checkerboard"));
        assertThat("the ninetieth face does not re-report", second, is(emptyString()));
    }

    @Test
    @DisplayName("a second distinct id still reports")
    void reportsEachDistinctIdSeparately() {
        RendererContext context = RendererContext.builder().build().withMissingTexture();
        String first = "minecraft:block/missing_texture_test_distinct_one";
        String second = "minecraft:block/missing_texture_test_distinct_two";

        errDuring(() -> context.resolveTexture(first));
        String output = errDuring(() -> Flipbook.atTick(context.resolveTexture(second), context.findFlipbook(second), 3));

        assertThat(output, containsString("Missing texture '" + second + "'"));
    }

    @Test
    @DisplayName("an unreadable texture substitutes the sprite and reports itself as unreadable, once")
    void reportsAnUnreadableIdInItsOwnWords() {
        RendererContext context = unreadable().withMissingTexture();
        String id = "minecraft:block/missing_texture_test_unreadable_once";

        String first = errDuring(() -> assertThat(
            context.resolveTexture(id).orElseThrow() == MissingSprite.sprite(), is(true)));
        String second = errDuring(() -> context.resolveTexture(id));

        assertThat(first, containsString("Unreadable texture '" + id + "' - drawing the checkerboard"));
        assertThat("an unreadable texture is not reported as a missing one", first, not(containsString("Missing texture")));
        assertThat("the ninetieth face does not re-report", second, is(emptyString()));
    }

    @Test
    @DisplayName("an unreadable texture's frame is the sprite, drawn still")
    void anUnreadableFrameIsTheStillSprite() {
        RendererContext context = unreadable().withMissingTexture();
        String id = "minecraft:block/missing_texture_test_unreadable_frame";

        errDuring(() -> assertThat(
            Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), 3).orElseThrow() == MissingSprite.sprite(),
            is(true)));
    }

    @Test
    @DisplayName("a miss's frame is the sprite")
    void aMissFrameIsTheSprite() {
        RendererContext context = RendererContext.builder().build().withMissingTexture();
        String id = "minecraft:block/missing_texture_test_absent_frame";

        errDuring(() -> assertThat(
            Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), 7).orElseThrow(), sameInstance(MissingSprite.sprite())));
    }

    @Test
    @DisplayName("read bare, a texture no pack supplies answers empty through the lookup and the frame alike")
    void aMissIsEmptyWhenNotSubstituting() {
        // The wrapper's answer, not a property of the id: an absent id draws the checkerboard through the
        // wrapper and is empty here. The substitution is in the wrapper alone, so the context keeps its
        // three states for every reader that reads it bare - fluid, portal and window chrome - which sees
        // exactly this empty and refuses it at its own call site.
        RendererContext context = RendererContext.builder().build();
        String id = "minecraft:block/missing_texture_test_bare_miss";

        assertThat(context.resolveTexture(id).isEmpty(), is(true));
        assertThat(Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), 7).isEmpty(), is(true));
    }

    @Test
    @DisplayName("a texture a pack does supply is handed back untouched, substituting or bare")
    void aHitIsUntouched() {
        String id = "minecraft:block/missing_texture_test_present";
        PixelBuffer fixture = PixelBuffer.ofStraight(new int[]{0xFF102030, 0xFF405060}, 2, 1);
        RendererContext context = RendererContext.builder().textures(Map.of(id, fixture)).build();
        RendererContext textures = context.withMissingTexture();

        assertThat(textures.resolveTexture(id).orElseThrow(), sameInstance(fixture));
        assertThat(Flipbook.atTick(textures.resolveTexture(id), textures.findFlipbook(id), 0).orElseThrow(), sameInstance(fixture));
        assertThat(context.resolveTexture(id).orElseThrow(), sameInstance(fixture));
        assertThat(Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), 0).orElseThrow(), sameInstance(fixture));
    }

    @Test
    @DisplayName("the tick arm resolves through the context exactly once")
    void theTickArmIsReached() {
        String id = "minecraft:block/missing_texture_test_tick_arm";
        PixelBuffer fixture = PixelBuffer.ofStraight(new int[]{0xFF102030, 0xFF405060}, 2, 1);
        RecordingContext context = RecordingContext.over(RendererContext.builder().textures(Map.of(id, fixture)).build());
        RendererContext textures = context.withMissingTexture();

        Flipbook.atTick(textures.resolveTexture(id), textures.findFlipbook(id), 4);

        assertThat(context.getResolved(), contains(id));
    }

    /**
     * A context serving every id with no pixels - the cheapest stand-in for a pack whose files do not
     * decode.
     *
     * @return the context
     */
    private static RendererContext unreadable() {
        return RendererContext.builder().textures(textureId -> Possible.empty()).build();
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static String errDuring(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
