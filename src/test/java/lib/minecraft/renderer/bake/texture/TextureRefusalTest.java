package lib.minecraft.renderer.bake.texture;

import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.exception.RendererException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link TextureRefusal}: the pixels of a texture lookup that holds some are handed back,
 * and each of the two answers that hold none is refused in its own words - an id no pack serves as
 * unregistered, a file that yields no pixels as one that could not be decoded.
 * <p>
 * The unregistered wording is pinned byte-for-byte, for a caller outside the renderer that matches it.
 */
@DisplayName("A texture refusal is worded by the state the lookup answered")
class TextureRefusalTest {

    private static final String ID = "minecraft:block/texture_refusal_test";

    private static final PixelBuffer PIXELS = PixelBuffer.create(2, 2);

    @Test
    @DisplayName("a lookup holding pixels hands them back")
    void presentIsHandedBack() {
        assertThat(TextureRefusal.require(Possible.of(PIXELS), ID), is(sameInstance(PIXELS)));
    }

    @Test
    @DisplayName("an id no pack serves is refused as unregistered")
    void absentIsUnregistered() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.absent(), ID));

        assertThat(refusal.getMessage(), is("No texture registered for id '" + ID + "'"));
    }

    @Test
    @DisplayName("a served id with no pixels is refused as undecodable")
    void emptyIsUndecodable() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.empty(), ID));

        assertThat(refusal.getMessage(), is("Texture '" + ID + "' could not be decoded"));
    }

    @Test
    @DisplayName("a reader's own refusal words the unserved id, and the undecodable one keeps its wording")
    void aReaderWordsOnlyTheUnservedId() {
        RenderException own = new RenderException("Window chrome sprite '%s' does not resolve", ID);

        assertThat(assertThrows(RenderException.class, () -> TextureRefusal.require(Possible.absent(), ID, () -> own)),
            is(sameInstance(own)));
        assertThat(assertThrows(RenderException.class, () -> TextureRefusal.require(Possible.empty(), ID, () -> own)).getMessage(),
            is("Texture '" + ID + "' could not be decoded"));
    }

    @Test
    @DisplayName("the refusal is a renderer exception, which a batch caller skips")
    void theRefusalIsSkippable() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> TextureRefusal.require(Possible.empty(), ID));

        assertThat(refusal, is(instanceOf(RendererException.class)));
    }

}
