package lib.minecraft.renderer.pipeline;


import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.pipeline.loader.BlockTintsLoader;
import lib.minecraft.renderer.pipeline.pack.ColorMapLoader;
import lib.minecraft.renderer.pipeline.pack.PackAcquisition;
import lib.minecraft.renderer.pipeline.pack.ResolvedModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * End-to-end asset pipeline integration test that downloads the Minecraft 26.1 client jar,
 * extracts it into a persistent cache directory, runs the full pipeline, and asserts that every
 * critical artefact (block models, item models, texture catalogue, vanilla pack entity) is
 * populated.
 * <p>
 * This test is tagged {@code slow} and is skipped by the default {@code test} task. Run it
 * explicitly with {@code ./gradlew :asset-renderer:slowTest}. The first run downloads ~25MB
 * from {@code piston-data.mojang.com}; subsequent runs reuse the cached copy in
 * {@code asset-renderer/cache/it} and complete in seconds.
 * <p>
 * The cache root is deliberately stable (not a temporary directory) so the extracted client jar
 * survives across sessions - offline vanilla-source lookups depend on having the
 * extracted 26.1 source available on disk after the test runs. {@code .gitignore} already excludes
 * {@code asset-renderer/cache/}, so nothing leaks into commits.
 */
@Tag("slow")
@DisplayName("ClientAcquisition end-to-end integration")
class PipelineIntegrationTest {

    /** Stable, non-temporary cache root so the extracted client jar survives across sessions. */
    private static final File CACHE_ROOT = new File("cache/it");

    /** Client assets (options + vanilla root) shared across every test, built once in {@link #downloadAndExtract()}. */
    private static ClientAssets result;

    /** The compiled pack stack, probed by the stack / texture-index assertions. */
    private static PackStack stack;

    /** The resolved block + item model sets, probed by the model-count assertions. */
    private static ResolvedModels models;

    /** The loaded block-tint table, probed by the {@code block_tints.json} assertion. */
    private static Map<String, Block.Tint> blockTints;

    /** The stack-resolved biome colormaps, probed by the colormap byte-parity assertion. */
    private static ConcurrentMap<ColorMap.Type, ColorMap> colorMaps;

    /** Extracted pack root ({@code cache/it/.../vanilla}) that the on-disk assertions probe. */
    private static Path packRoot;

    /**
     * Runs the full 26.1 pipeline once for the class - downloads and extracts the client jar (or
     * reuses the cached copy) and publishes the shared {@link #result} / {@link #packRoot}.
     */
    @BeforeAll
    static void downloadAndExtract() {
        ClientOptions options = ClientOptions.builder()
            .version("26.1")
            .cacheRoot(CACHE_ROOT)
            .build();

        result = ClientAcquisition.acquire(options);
        packRoot = result.vanillaRoot();
        stack = PackAcquisition.acquire(result);
        models = ResolvedModels.load(stack);
        blockTints = BlockTintsLoader.load();
        colorMaps = ColorMapLoader.load(stack);
    }

    @Test
    @DisplayName("extracts the client jar assets to a real directory")
    void extractsClientJarAssets() {
        assertThat("pack root exists", Files.isDirectory(packRoot), is(true));
        assertThat("assets/minecraft subtree exists",
            Files.isDirectory(packRoot.resolve("assets/minecraft")), is(true));
        assertThat("assets/minecraft/models/block exists",
            Files.isDirectory(packRoot.resolve("assets/minecraft/models/block")), is(true));
        assertThat("assets/minecraft/models/item exists",
            Files.isDirectory(packRoot.resolve("assets/minecraft/models/item")), is(true));
        assertThat("assets/minecraft/textures/block exists",
            Files.isDirectory(packRoot.resolve("assets/minecraft/textures/block")), is(true));
    }

    @Test
    @DisplayName("populates the vanilla pack at the base of the stack")
    void populatesVanillaPack() {
        ResourcePack vanilla = stack.vanilla();
        assertThat(vanilla.id(), equalTo(PackId.VANILLA));
        assertThat(vanilla.namespaces(), hasItem("minecraft"));
        assertThat(vanilla.container(), instanceOf(PackContainer.Directory.class));
        assertThat(((PackContainer.Directory) vanilla.container()).root().toString(), containsString("vanilla"));
        assertThat(stack.ascending().getFirst(), is(vanilla));
    }

    @Test
    @DisplayName("loads every block model under models/block")
    void loadsBlockModels() {
        assertThat("block model count", models.blocks().size(), is(greaterThan(500)));
        assertThat(models.blocks(), hasKey("minecraft:block/grass_block"));
        assertThat(models.blocks(), hasKey("minecraft:block/cobblestone"));
        assertThat(models.blocks(), hasKey("minecraft:block/stone"));

        ModelData grass = models.blocks().get("minecraft:block/grass_block");
        assertThat("grass block model resolved", grass, is(notNullValue()));
    }

    @Test
    @DisplayName("loads every item model under models/item")
    void loadsItemModels() {
        assertThat("item model count", models.items().size(), is(greaterThan(500)));
        assertThat(models.items(), hasKey("minecraft:item/diamond_sword"));
        assertThat(models.items(), hasKey("minecraft:item/iron_pickaxe"));
        assertThat(models.items(), hasKey("minecraft:item/apple"));

        ModelData sword = models.items().get("minecraft:item/diamond_sword");
        assertThat("diamond sword model resolved", sword, is(notNullValue()));
    }

    @Test
    @DisplayName("catalogues every texture under assets/minecraft/textures")
    void cataloguesTextures() {
        assertThat("texture catalogue is populated", stack.textureIndex().size(), is(greaterThan(500)));

        ResolvedTexture grassTop = stack.textureIndex().get(ResourceId.parse("minecraft:block/grass_block_top"));
        assertThat("grass_block_top texture catalogued", grassTop, is(notNullValue()));
        PixelBuffer grassBuffer = new ImageFactory().fromByteArray(grassTop.bytes()).toPixelBuffer();
        assertThat(grassBuffer.width(), is(greaterThanOrEqualTo(16)));
        assertThat(grassBuffer.height(), is(greaterThanOrEqualTo(16)));
        assertThat(grassTop.path(), allOf(
            containsString("block"),
            containsString("grass_block_top"),
            containsString(".png")
        ));
    }

    @Test
    @DisplayName("extracts the enchanted glint textures (item and armor)")
    void extractsGlintTextures() {
        Path glintItem = packRoot.resolve("assets/minecraft/textures/misc/enchanted_glint_item.png");
        Path glintArmor = packRoot.resolve("assets/minecraft/textures/misc/enchanted_glint_armor.png");
        assertThat("enchanted_glint_item.png present", Files.isRegularFile(glintItem), is(true));
        assertThat("enchanted_glint_armor.png present", Files.isRegularFile(glintArmor), is(true));
    }

    @Test
    @DisplayName("extracts the grass/foliage colormaps")
    void extractsColormaps() {
        Path grass = packRoot.resolve("assets/minecraft/textures/colormap/grass.png");
        Path foliage = packRoot.resolve("assets/minecraft/textures/colormap/foliage.png");
        assertThat("colormap/grass.png present", Files.isRegularFile(grass), is(true));
        assertThat("colormap/foliage.png present", Files.isRegularFile(foliage), is(true));
    }

    @Test
    @DisplayName("VanillaTintsLoader loads the bundled block_tints.json into Block.Tint entries")
    void parsesBlockColors() {
        var tints = blockTints;

        // Print the full table once so the slowTest log captures what the bundled JSON contains
        // - useful when refreshing the snapshot via the generateVanillaTints Gradle task and
        // verifying nothing silently dropped.
        System.out.println("Loaded " + tints.size() + " Block.Tint entries from block_tints.json:");
        tints.forEach((blockId, tint) -> {
            String constant = tint.constant()
                .map(v -> String.format(" 0x%08X", v.argb()))
                .orElse("");
            System.out.println("  " + blockId + " -> " + tint.target() + constant);
        });

        assertThat("at least 15 tints loaded", tints.size(), is(greaterThanOrEqualTo(15)));

        var grassBlock = tints.get("minecraft:grass_block");
        assertThat(grassBlock, is(notNullValue()));
        assertThat(grassBlock.target(), equalTo(Block.TintTarget.GRASS));

        var oakLeaves = tints.get("minecraft:oak_leaves");
        assertThat(oakLeaves, is(notNullValue()));
        assertThat(oakLeaves.target(), equalTo(Block.TintTarget.FOLIAGE));

        var spruceLeaves = tints.get("minecraft:spruce_leaves");
        assertThat(spruceLeaves, is(notNullValue()));
        assertThat(spruceLeaves.target(), equalTo(Block.TintTarget.CONSTANT));
        assertThat(spruceLeaves.constant().isPresent(), is(true));
        assertThat("spruce constant ARGB", spruceLeaves.constant().get().argb(), equalTo(0xFF619961));

        var leafLitter = tints.get("minecraft:leaf_litter");
        assertThat(leafLitter, is(notNullValue()));
        assertThat(leafLitter.target(), equalTo(Block.TintTarget.DRY_FOLIAGE));
    }

    @Test
    @DisplayName("stack-resolved colormaps byte-match the bundled color_maps.json LUT (D10)")
    void colormapsByteMatchBundledLut() {
        // The byte-parity probe: sha256 of the raw
        // big-endian ARGB bytes of each bundled colormap. The re-point resolves vanilla's own
        // extracted PNG through the stack and must decode to the identical bytes.
        assertThat(sha256(colorMaps.get(ColorMap.Type.GRASS).pixels()),
            equalTo("99ac9a2db44c6ed14da168bad2f66001535fd8b6290a2255bc8aa251d16afcc4"));
        assertThat(sha256(colorMaps.get(ColorMap.Type.FOLIAGE).pixels()),
            equalTo("64c43c6b59f7da4ae1c8f56a332c6e21a6d0789dd0272c2cc32c809bc2e0da50"));
        assertThat(sha256(colorMaps.get(ColorMap.Type.DRY_FOLIAGE).pixels()),
            equalTo("04fe97199d0400e161c1413077735b8dff765d86999890d76953681bee86708f"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
