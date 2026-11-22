package lib.minecraft.renderer;

import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.store.diff.RenderDigest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A texture named by URL is fetched once for the {@link PlayerRenderer} a caller holds, however many
 * renders ask for it and however each spells its URL, and the renderer holds no more fetched textures
 * than its cache's capacity.
 * <p>
 * Each render draws on a renderer built for it over a context recording its stand-ins, and that
 * renderer reads the cache of fetched textures the caller's renderer holds. The cache keeps a texture
 * under the hash its URL names, which is all of the URL the fetch reads, and the kind of sheet it is
 * read as. The fetch here is a stand-in that answers one sheet and records every URL it is asked for,
 * so nothing in this suite reaches the texture server.
 */
@DisplayName("A URL texture is fetched once for the renderer a caller holds, and the cache is bounded")
class PlayerRendererSkinCacheTest {

    /** A texture URL as the session API spells one. */
    private static final @NotNull String URL = "https://textures.minecraft.net/texture/5f1c0a";

    /** The sheet every fetch here answers. */
    private static final @NotNull PixelBuffer SHEET = sheet();

    /** That sheet as the PNG bytes a fetch answers. */
    private static final byte @NotNull [] PNG = ImageFactory.toByteArray(Timeline.still(SHEET), ImageFormat.PNG);

    @Test
    @DisplayName("two renders of a URL skin draw the fetched sheet, fetched once")
    void twoRendersFetchOnce() {
        List<String> fetched = new CopyOnWriteArrayList<>();
        PlayerRenderer renderer = new PlayerRenderer(RendererContext.builder().build(), recording(fetched));
        int[] supplied = RenderDigest.firstFramePixels(renderer.render(skull(TextureOptions.builder()
            .bytes(Optional.of(PNG))
            .build())).image());
        PlayerOptions named = skull(TextureOptions.builder().url(Optional.of(URL)).build());

        assertThat("the first render draws the fetched sheet",
            RenderDigest.firstFramePixels(renderer.render(named).image()), is(supplied));
        assertThat("and so does the second",
            RenderDigest.firstFramePixels(renderer.render(named).image()), is(supplied));
        assertThat("the sheet was fetched once, by the URL the request named", fetched, contains(URL));
    }

    @Test
    @DisplayName("URLs spelled differently that name one texture hash are one fetched sheet")
    void oneHashIsOneSheetHoweverItsUrlIsSpelled() {
        List<String> fetched = new CopyOnWriteArrayList<>();
        PlayerRenderer.FetchedTextures cache = new PlayerRenderer.FetchedTextures(recording(fetched));
        PixelBuffer first = cache.read("skin", URL);

        for (String spelling : List.of("http://textures.minecraft.net/texture/5f1c0a", "https://example.invalid/a/b/5f1c0a", "5f1c0a"))
            assertThat(spelling, cache.read("skin", spelling), is(sameInstance(first)));

        assertThat("one fetch serves every spelling", fetched, contains(URL));
    }

    @Test
    @DisplayName("a skin, a cape and an elytra sheet naming one hash are fetched and held apart")
    void kindsNamingOneHashAreHeldApart() {
        List<String> fetched = new CopyOnWriteArrayList<>();
        PlayerRenderer.FetchedTextures cache = new PlayerRenderer.FetchedTextures(recording(fetched));
        PixelBuffer skin = cache.read("skin", URL);
        PixelBuffer cape = cache.read("cape", URL);
        PixelBuffer elytra = cache.read("elytra", URL);

        assertThat(fetched, hasSize(3));
        assertThat(cache.read("skin", URL), is(sameInstance(skin)));
        assertThat(cache.read("cape", URL), is(sameInstance(cape)));
        assertThat(cache.read("elytra", URL), is(sameInstance(elytra)));
        assertThat("and no read of a held sheet fetched again", fetched, hasSize(3));
    }

    @Test
    @DisplayName("the cache holds its capacity of sheets and lets the one read longest ago go")
    void theCacheIsBoundedByItsCapacity() {
        List<String> fetched = new CopyOnWriteArrayList<>();
        PlayerRenderer.FetchedTextures cache = new PlayerRenderer.FetchedTextures(recording(fetched));
        int capacity = PlayerRenderer.FetchedTextures.CAPACITY;

        for (int i = 0; i < capacity; i++)
            cache.read("skin", url(i));
        assertThat(fetched, hasSize(capacity));

        // Reading the oldest makes the second-oldest the one read longest ago, so one more sheet lets
        // that one go and keeps the oldest.
        cache.read("skin", url(0));
        cache.read("skin", url(capacity));
        assertThat("a full cache fetches only what it does not hold", fetched, hasSize(capacity + 1));

        cache.read("skin", url(0));
        cache.read("skin", url(capacity));
        assertThat("the sheet read again and the newest are both still held", fetched, hasSize(capacity + 1));

        cache.read("skin", url(1));
        assertThat("the sheet read longest ago was let go, and is fetched again", fetched, hasSize(capacity + 2));
        assertThat(fetched.getLast(), is(url(1)));
    }

    @Test
    @DisplayName("a fetch that fails is not kept, so the next read fetches again")
    void aFailedFetchIsNotKept() {
        List<String> fetched = new CopyOnWriteArrayList<>();
        Function<String, byte[]> failingOnce = url -> {
            fetched.add(url);
            if (fetched.size() == 1)
                throw new ContentException("no texture");

            return PNG;
        };
        PlayerRenderer.FetchedTextures cache = new PlayerRenderer.FetchedTextures(failingOnce);

        assertThrows(ContentException.class, () -> cache.read("skin", URL));
        PixelBuffer sheet = cache.read("skin", URL);
        assertThat("the second read fetched, and its sheet is kept", cache.read("skin", URL), is(sameInstance(sheet)));
        assertThat(fetched, hasSize(2));
    }

    /**
     * A fetch that answers the one sheet for any URL and records each URL it is asked for.
     *
     * @param fetched the URLs asked for, in order
     * @return the fetch
     */
    private static @NotNull Function<String, byte[]> recording(@NotNull List<String> fetched) {
        return url -> {
            fetched.add(url);
            return PNG;
        };
    }

    /**
     * A texture URL naming a hash of its own for each index.
     *
     * @param index the index
     * @return the URL
     */
    private static @NotNull String url(int index) {
        return "https://textures.minecraft.net/texture/" + Integer.toHexString(0x100000 + index);
    }

    /**
     * Builds the skin sheet a fetch answers.
     *
     * @return a 64 by 64 sheet of one colour
     */
    private static @NotNull PixelBuffer sheet() {
        PixelBuffer sheet = PixelBuffer.straight(64, 64);
        sheet.fill(0xFF3366CC);
        return sheet;
    }

    /**
     * Builds a flat skull over the given skin source, at a small canvas.
     *
     * @param skin the skin source
     * @return the options
     */
    private static @NotNull PlayerOptions skull(@NotNull TextureOptions skin) {
        return PlayerOptions.builder()
            .type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.TWO_D)
            .skin(SkinOptions.builder().skin(skin).build())
            .output(OutputOptions.builder().canvasSize(32).build())
            .build();
    }

}
