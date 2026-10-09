package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.store.diff.RenderDigest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * A skin named by URL is fetched once for the {@link PlayerRenderer} a caller holds, however many
 * renders it makes.
 * <p>
 * Each render draws on a renderer built for it over a context recording its stand-ins, and that
 * renderer reads the cache of fetched textures the caller's renderer holds. The cache is seeded here
 * with a sheet under a URL that names no texture, so a render reading any other cache would go to the
 * network for it - which this suite never does, and which fails on that URL.
 */
@DisplayName("A URL skin is fetched once for the renderer a caller holds")
class PlayerRendererSkinCacheTest {

    /** A skin URL whose texture hash names nothing the texture server holds. */
    private static final @NotNull String URL = "https://textures.minecraft.net/texture/player_renderer_skin_cache_test";

    @Test
    @DisplayName("two renders of a URL skin each draw the sheet the caller's renderer already holds")
    void twoRendersReadTheHeldSheet() throws ReflectiveOperationException {
        PlayerRenderer renderer = new PlayerRenderer(RendererContext.builder().build());
        PixelBuffer sheet = PixelBuffer.create(64, 64);
        sheet.fill(0xFF3366CC);
        ConcurrentMap<String, PixelBuffer> cache = skinCache(renderer);
        cache.put(URL, sheet);

        byte[] png = new ImageFactory().toByteArray(Timeline.still(sheet), ImageFormat.PNG);
        int[] supplied = RenderDigest.firstFramePixels(renderer.render(skull(TextureOptions.builder()
            .bytes(Optional.of(png))
            .build())).image());
        PlayerOptions named = skull(TextureOptions.builder().url(Optional.of(URL)).build());

        assertThat("the first render draws the held sheet",
            RenderDigest.firstFramePixels(renderer.render(named).image()), is(supplied));
        assertThat("and so does the second",
            RenderDigest.firstFramePixels(renderer.render(named).image()), is(supplied));
        assertThat("the cache holds that one sheet and nothing fetched beside it", cache.keySet(), is(Set.of(URL)));
        assertThat(cache.get(URL), is(sameInstance(sheet)));
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

    /**
     * Reads the cache of fetched textures a renderer holds.
     *
     * @param renderer the renderer
     * @return its cache, keyed by URL
     * @throws ReflectiveOperationException if the field cannot be read
     */
    @SuppressWarnings("unchecked")
    private static @NotNull ConcurrentMap<String, PixelBuffer> skinCache(@NotNull PlayerRenderer renderer) throws ReflectiveOperationException {
        Field field = PlayerRenderer.class.getDeclaredField("skinCache");
        field.setAccessible(true);
        return (ConcurrentMap<String, PixelBuffer>) field.get(renderer);
    }

}
