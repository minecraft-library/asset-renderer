package lib.minecraft.renderer;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.option.spec.SkinOptions;
import lib.minecraft.renderer.option.spec.TextureOptions;
import lib.minecraft.renderer.parity.PinSet;
import lib.minecraft.renderer.parity.Pins;
import lib.minecraft.renderer.parity.RenderDigest;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Byte-identity pin for the {@link ModelEngine#rasterizeFitted} auto-fit path, whose only production
 * consumer is {@link PlayerRenderer} (its 3D body render at {@code PlayerRenderer.rasterize3D}). No
 * other byte-pinned test covers this method: {@link ModelEngineParallelismTest} exercises the plain
 * {@code rasterize} block path, and {@code TestPlayerRender} is a visual sweep that asserts nothing.
 *
 * <p>Both fitted arms are covered so a refactor that unifies the lens fork can prove it:
 * <ul>
 * <li><b>Orthographic arm</b> - a FULL body under {@link Projection#VANILLA_ISO} (the shipped iso
 *     player pose, {@link Lens.Kind#ORTHOGRAPHIC}), the 3D-fit {@code scale(fit).translate(-centre)}
 *     bake.</li>
 * <li><b>Perspective arm</b> - a SKULL under {@link Projection#PORTRAIT}
 *     ({@link Lens.Kind#PERSPECTIVE}), the 2D post-projection {@code Fit2D} path.</li>
 * <li><b>The armoured skin path</b> - the same FULL body in a full iron set. The other two carry no
 *     armour at all, so nothing pinned the player's <em>own</em> armour build: neither the boxes
 *     {@code ArmorKit.buildHumanoidArmor3D} adds around each body part, nor the order it adds them in,
 *     nor which of the shell's parts each slot reaches. It is the only byte gate on that path.</li>
 * </ul>
 * Each case asserts render-twice determinism (parallel Pass 1 + tiled Pass 2 must be stable) then
 * pins a CRC32 over the ARGB pixels. A future rasterization-math change that silently drifts the
 * fitted output breaks the pin.
 *
 * <p>Tagged {@code slow} because it boots the full asset pipeline; run with
 * {@code ./gradlew slowTest}. Uses the offline, pack-resolvable vanilla skin so no
 * network is required once the client jar is cached.
 */
@Tag("slow")
@DisplayName("ModelEngine.rasterizeFitted (player auto-fit) byte-identity pin")
class PlayerRasterizeFittedGoldenTest {

    private static final File CACHE_ROOT = new File("cache/it");

    /** Offline, pack-resolvable default skin texture id (matches {@code TestPlayerRender}). */
    private static final String SKIN_ID = "minecraft:entity/player/wide/steve";

    private static final String ARTIFACT = "pin.player-crc";

    /**
     * The three cases and the render each pins, which is what a re-baseliner needs to reproduce one.
     * Declared here rather than inferred, so the pin cannot be captured under a key nothing reads.
     */
    private static final PinSet PINS = PinSet.of(ARTIFACT, Map.of(
        "full_vanilla_iso",
        "Type.FULL, THREE_D, VANILLA_ISO, canvas 256, ssaa 1, aa off, steve skin",
        "skull_portrait",
        "Type.SKULL, THREE_D, PORTRAIT, canvas 256, ssaa 1, aa off, steve skin",
        "armored_full_vanilla_iso",
        "Type.FULL + ArmorMaterial.IRON helmet/chestplate/leggings/boots, THREE_D, VANILLA_ISO, "
            + "canvas 256, ssaa 1, aa off, steve skin"));

    private static PlayerRenderer playerRenderer;

    @BeforeAll
    static void bootstrapPipeline() {
        ClientAssets result = ClientAcquisition.acquire(
            ClientOptions.builder()
                .version("26.1")
                .cacheRoot(CACHE_ROOT)
                .build()
        );
        playerRenderer = new PlayerRenderer(PipelineRendererContext.of(result));
    }

    @Test
    @DisplayName("FULL body under VANILLA_ISO (orthographic 3D-fit arm) is deterministic and pinned")
    void orthographicFittedArmIsPinned() {
        PlayerOptions options = PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .output(OutputOptions.builder()
                .projection(Projection.VANILLA_ISO)
                .canvasSize(256)
                .supersample(1)
                .antiAlias(false)
                .build())
            .skin(SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(SKIN_ID)).build()).build())
            .build();
        assertDeterministicAndPinned(options, "full_vanilla_iso");
    }

    @Test
    @DisplayName("SKULL under PORTRAIT (perspective Fit2D arm) is deterministic and pinned")
    void perspectiveFittedArmIsPinned() {
        PlayerOptions options = PlayerOptions.builder()
            .type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .output(OutputOptions.builder()
                .projection(Projection.PORTRAIT)
                .canvasSize(256)
                .supersample(1)
                .antiAlias(false)
                .build())
            .skin(SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(SKIN_ID)).build()).build())
            .build();
        assertDeterministicAndPinned(options, "skull_portrait");
    }

    @Test
    @DisplayName("FULL body in a full iron set is deterministic and pinned - the armoured skin path")
    void armoredFittedArmIsPinned() {
        ArmorPiece iron = ArmorPiece.of(ArmorMaterial.IRON);
        PlayerOptions options = PlayerOptions.builder()
            .type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .output(OutputOptions.builder()
                .projection(Projection.VANILLA_ISO)
                .canvasSize(256)
                .supersample(1)
                .antiAlias(false)
                .build())
            .skin(SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(SKIN_ID)).build()).build())
            .armor(ArmorOptions.builder()
                .helmet(Optional.of(iron))
                .chestplate(Optional.of(iron))
                .leggings(Optional.of(iron))
                .boots(Optional.of(iron))
                .build())
            .build();
        assertDeterministicAndPinned(options, "armored_full_vanilla_iso");
    }

    private void assertDeterministicAndPinned(PlayerOptions options, String key) {
        int[] first = RenderDigest.firstFramePixels(playerRenderer.render(options));
        int[] second = RenderDigest.firstFramePixels(playerRenderer.render(options));
        // Before the pin, always: a flaky parallel path must fail on a different message than a
        // drifted value, or a re-baseline gets reached for when the fix is a determinism bug.
        assertThat("fitted raster must be deterministic across invocations",
            second, equalTo(first));

        long actual = RenderDigest.crc32(first);
        PINS.crc32(key, actual);
        PINS.requireBaseline();
        assertThat("fitted rasterization output CRC32; if intentional, re-baseline it: "
                + Pins.rebaselineCommand(ARTIFACT),
            actual, is(Pins.crc32(ARTIFACT, key)));
    }

}
