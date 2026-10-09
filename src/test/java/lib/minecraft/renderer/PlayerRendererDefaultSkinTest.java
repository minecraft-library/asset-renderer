package lib.minecraft.renderer;

import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The skin a {@link PlayerRenderer} draws when its options name no skin source.
 *
 * <p>Every sweep, driver and pin passes a skin id, so none of them reaches the fallback; this is the
 * one test that does. It renders the same body twice, once with no skin source and once naming the
 * wide-arm Steve sheet, and holds the two to the same pixels. Reads the client assets through
 * {@link ClientAssetsExtension}, which abandons the class where nothing has extracted the client yet.
 */
@DisplayName("PlayerRenderer default skin")
@ExtendWith(ClientAssetsExtension.class)
class PlayerRendererDefaultSkinTest {

    /** The sheet the fallback resolves - the shipped wide-arm Steve. */
    private static final String STEVE_ID = "minecraft:entity/player/wide/steve";

    @Test
    @DisplayName("a render with no skin source draws the wide-arm Steve sheet")
    void noSkinSourceDrawsWideSteve() {
        PlayerRenderer renderer = new PlayerRenderer(ClientAssetsExtension.context());
        PlayerOptions bare = body().build();
        PlayerOptions named = body()
            .skin(SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(STEVE_ID)).build()).build())
            .build();

        assertThat("a bare render must draw the default Steve sheet",
            RenderDigest.firstFramePixels(renderer.render(bare).image()),
            equalTo(RenderDigest.firstFramePixels(renderer.render(named).image())));
    }

    /**
     * Returns a full-body 3D render at a small canvas, the skin left for the caller to set.
     *
     * @return the options builder
     */
    private static PlayerOptions.Builder body() {
        return PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .output(OutputOptions.builder()
                .canvasSize(128)
                .supersample(1)
                .antiAlias(false)
                .build());
    }

}
