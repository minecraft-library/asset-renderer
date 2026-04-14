package dev.sbs.renderer.pipeline;


import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.model.Texture;
import dev.sbs.renderer.model.asset.BlockModelData;
import dev.sbs.renderer.model.asset.ItemModelData;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * survives across sessions - the renderer grep tasks in this session depend on having the
 * extracted 26.1 source available on disk after the test runs. {@code .gitignore} already excludes
 * {@code asset-renderer/cache/}, so nothing leaks into commits.
 */
@Tag("slow")
@DisplayName("AssetPipeline end-to-end integration")
class AssetPipelineIntegrationTest {

    private static final File CACHE_ROOT = new File("cache/it");
    private static AssetPipeline.Result result;
    private static Path packRoot;

    @BeforeAll
    static void downloadAndExtract() {
        AssetPipelineOptions options = AssetPipelineOptions.builder()
            .version("26.1")
            .cacheRoot(CACHE_ROOT)
            .build();

        AssetPipeline pipeline = new AssetPipeline(new HttpFetcher());
        result = pipeline.run(options);
        packRoot = result.getPackRoot();
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
    @DisplayName("populates the vanilla texture pack entity")
    void populatesVanillaPack() {
        assertThat(result.getVanillaPack(), is(notNullValue()));
        assertThat(result.getVanillaPack().getId(), equalTo("vanilla"));
        assertThat(result.getVanillaPack().getNamespace(), equalTo("minecraft"));
        assertThat(result.getVanillaPack().getRootPath(), containsString("vanilla"));
        assertThat(result.getVanillaPack().getPriority(), is(0));
    }

    @Test
    @DisplayName("loads every block model under models/block")
    void loadsBlockModels() {
        assertThat("block model count", result.getBlockModels().size(), is(greaterThan(500)));
        assertThat(result.getBlockModels(), hasKey("minecraft:block/grass_block"));
        assertThat(result.getBlockModels(), hasKey("minecraft:block/cobblestone"));
        assertThat(result.getBlockModels(), hasKey("minecraft:block/stone"));

        BlockModelData grass = result.getBlockModels().get("minecraft:block/grass_block");
        assertThat("grass block model resolved", grass, is(notNullValue()));
    }

    @Test
    @DisplayName("loads every item model under models/item")
    void loadsItemModels() {
        assertThat("item model count", result.getItemModels().size(), is(greaterThan(500)));
        assertThat(result.getItemModels(), hasKey("minecraft:item/diamond_sword"));
        assertThat(result.getItemModels(), hasKey("minecraft:item/iron_pickaxe"));
        assertThat(result.getItemModels(), hasKey("minecraft:item/apple"));

        ItemModelData sword = result.getItemModels().get("minecraft:item/diamond_sword");
        assertThat("diamond sword model resolved", sword, is(notNullValue()));
    }

    @Test
    @DisplayName("catalogues every texture under assets/minecraft/textures")
    void cataloguesTextures() {
        assertThat("texture catalogue is populated", result.getTextures().size(), is(greaterThan(500)));

        Texture grassTop = result.getTextures().stream()
            .filter(t -> t.getId().equals("minecraft:block/grass_block_top"))
            .findFirst()
            .orElse(null);
        assertThat("grass_block_top texture catalogued", grassTop, is(notNullValue()));
        assertThat(grassTop.getWidth(), is(greaterThanOrEqualTo(16)));
        assertThat(grassTop.getHeight(), is(greaterThanOrEqualTo(16)));
        assertThat(grassTop.getRelativePath(), allOf(
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
        var tints = result.getBlockTints();

        // Print the full table once so the slowTest log captures what the bundled JSON contains
        // - useful when refreshing the snapshot via the generateVanillaTints Gradle task and
        // verifying nothing silently dropped.
        System.out.println("Loaded " + tints.size() + " Block.Tint entries from block_tints.json:");
        tints.forEach((blockId, tint) -> {
            String constant = tint.constant()
                .map(v -> String.format(" 0x%08X", v))
                .orElse("");
            System.out.println("  " + blockId + " -> " + tint.target() + constant);
        });

        assertThat("at least 15 tints loaded", tints.size(), is(greaterThanOrEqualTo(15)));

        var grassBlock = tints.get("minecraft:grass_block");
        assertThat(grassBlock, is(notNullValue()));
        assertThat(grassBlock.target(), equalTo(Biome.TintTarget.GRASS));

        var oakLeaves = tints.get("minecraft:oak_leaves");
        assertThat(oakLeaves, is(notNullValue()));
        assertThat(oakLeaves.target(), equalTo(Biome.TintTarget.FOLIAGE));

        var spruceLeaves = tints.get("minecraft:spruce_leaves");
        assertThat(spruceLeaves, is(notNullValue()));
        assertThat(spruceLeaves.target(), equalTo(Biome.TintTarget.CONSTANT));
        assertThat(spruceLeaves.constant().isPresent(), is(true));
        assertThat("spruce constant ARGB", spruceLeaves.constant().get(), equalTo(0xFF619961));

        var leafLitter = tints.get("minecraft:leaf_litter");
        assertThat(leafLitter, is(notNullValue()));
        assertThat(leafLitter.target(), equalTo(Biome.TintTarget.DRY_FOLIAGE));
    }

}
