package lib.minecraft.renderer.content.index;

import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.diagnostic.Substitutions;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the substituting wrapper {@link RendererContext#withMissingTexture()} mints: an
 * unresolved id draws the checkerboard and is reported once through {@link Substitutions#texture}
 * rather than once per face.
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
