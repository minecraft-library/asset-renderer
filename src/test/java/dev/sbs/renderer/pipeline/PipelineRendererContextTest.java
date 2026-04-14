package dev.sbs.renderer.pipeline;

import com.google.gson.Gson;

import dev.sbs.renderer.geometry.Biome;
import dev.sbs.renderer.asset.Block;
import dev.sbs.renderer.asset.pack.ColorMap;
import dev.sbs.renderer.asset.Item;
import dev.sbs.renderer.asset.pack.Texture;
import dev.sbs.renderer.asset.pack.TexturePack;
import dev.sbs.renderer.asset.pack.AnimationData;
import dev.sbs.renderer.asset.model.BlockModelData;
import dev.sbs.renderer.asset.model.ItemModelData;
import dev.sbs.renderer.pipeline.loader.ColorMapLoader;
import dev.sbs.renderer.pipeline.loader.TexturePackLoader;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link PipelineRendererContext}.
 * <p>
 * The fixtures build a real on-disk pack layout in a temporary directory and synthesise a
 * minimal {@link AssetPipeline.Result} around it, so the tests exercise the same code paths
 * a production pipeline run would hit without needing to download the client jar.
 */
@DisplayName("PipelineRendererContext wraps a pipeline result into a rendering context")
class PipelineRendererContextTest {

    @TempDir
    static Path packRoot;

    private static PipelineRendererContext context;
    private static AssetPipeline.Result result;

    @BeforeAll
    static void buildFixture() throws IOException {
        // Lay out a minimal vanilla-style texture pack in the temp directory.
        Path texturesDir = packRoot.resolve("assets/minecraft/textures");
        Path blockDir = texturesDir.resolve("block");
        Path colormapDir = texturesDir.resolve("colormap");
        Files.createDirectories(blockDir);
        Files.createDirectories(colormapDir);

        // Write a 4x4 opaque red PNG as the fixture texture.
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                image.setRGB(x, y, 0xFFFF0000);
        ImageIO.write(image, "PNG", blockDir.resolve("fixture.png").toFile());

        // Drop a sibling .png.mcmeta sidecar so the texture scanner has something to parse for
        // the animation pass-through tests. Mixes a bare-integer frame and an explicit object
        // frame so both branches of the parser are exercised in the same fixture.
        Files.writeString(
            blockDir.resolve("fixture.png.mcmeta"),
            "{\"animation\":{\"frametime\":4,\"interpolate\":true,\"frames\":[0,1,2,{\"index\":3,\"time\":8}]}}"
        );

        // Tiny 4x4 grass colormap with a recognisable corner pixel so the colormap loader has
        // something to parse and the context test can verify pass-through.
        BufferedImage grassMap = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        grassMap.setRGB(0, 0, 0xFF7FB238);
        ImageIO.write(grassMap, "PNG", colormapDir.resolve("grass.png").toFile());

        // Scan the fixture pack to derive a realistic TexturePack + texture list. Using the
        // real TexturePackLoader / ColorMapLoader keeps the test honest about the exact id
        // format and resource shape the context normalises against.
        //
        // VanillaTintsLoader is intentionally NOT called here: it now parses BlockColors from
        // a real client jar, which the unit test does not have. Tint entries are synthesised
        // directly so the context wiring (Block.tintTarget population, colorMap pass-through)
        // can still be exercised in isolation. The end-to-end loader is exercised by the
        // slowTest against the cached vanilla 26.1 jar.
        TexturePack vanillaPack = TexturePackLoader.loadVanilla(packRoot);
        ConcurrentList<Texture> textures = TexturePackLoader.scanTextures(packRoot, vanillaPack.getId());
        ConcurrentList<ColorMap> colorMaps = ColorMapLoader.load();
        ConcurrentMap<String, Block.Tint> blockTints = Concurrent.newMap();
        blockTints.put("minecraft:grass_block", new Block.Tint(Biome.TintTarget.GRASS, Optional.empty()));
        blockTints.put("minecraft:oak_leaves", new Block.Tint(Biome.TintTarget.FOLIAGE, Optional.empty()));
        blockTints.put("minecraft:spruce_leaves", new Block.Tint(Biome.TintTarget.CONSTANT, Optional.of(0xFF619961)));

        // Synthetic model maps. Each model references the fixture texture so resolveTexture
        // has a meaningful lookup target. Gson is used in place of reflective setters because
        // the DTOs are already Gson-friendly and the JSON form matches what the production
        // pipeline feeds through ModelResolver.
        Gson gson = GsonSettings.defaults().create();
        ConcurrentMap<String, BlockModelData> blockModels = Concurrent.newMap();
        blockModels.put(
            "minecraft:block/stone",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", BlockModelData.class)
        );
        // A second model with a real elements list whose face bindings reference #variables in
        // the textures map. Drives the direction-key flattening test.
        blockModels.put(
            "minecraft:block/cube_test",
            gson.fromJson(
                "{\"textures\":{\"all\":\"minecraft:block/fixture\",\"top\":\"minecraft:block/fixture\"},"
                    + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                    + "\"down\":{\"texture\":\"#all\"},"
                    + "\"up\":{\"texture\":\"#top\"},"
                    + "\"north\":{\"texture\":\"#all\"},"
                    + "\"south\":{\"texture\":\"#all\"},"
                    + "\"west\":{\"texture\":\"#all\"},"
                    + "\"east\":{\"texture\":\"#all\"}}}]}",
                BlockModelData.class
            )
        );
        // Real vanilla-named blocks used by the tint pass-through tests. The bundled
        // block_tints.json table maps grass_block -> GRASS and spruce_leaves -> CONSTANT 0xFF619961.
        blockModels.put(
            "minecraft:block/grass_block",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", BlockModelData.class)
        );
        blockModels.put(
            "minecraft:block/spruce_leaves",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", BlockModelData.class)
        );

        ConcurrentMap<String, ItemModelData> itemModels = Concurrent.newMap();
        itemModels.put(
            "minecraft:item/stick",
            gson.fromJson("{\"textures\": {\"layer0\": \"minecraft:block/fixture\"}}", ItemModelData.class)
        );
        // Leather helmet to verify OverlayResolver wires a Leather overlay onto the materialised
        // Item during PipelineRendererContext.of.
        itemModels.put(
            "minecraft:item/leather_helmet",
            gson.fromJson(
                "{\"textures\": {\"layer0\": \"minecraft:item/leather_helmet\","
                    + "\"layer1\": \"minecraft:item/leather_helmet_overlay\"}}",
                ItemModelData.class
            )
        );

        result = new AssetPipeline.Result(packRoot, vanillaPack, textures, colorMaps, blockTints, blockModels, itemModels, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap());
        context = PipelineRendererContext.of(result);
    }

    @Test
    @DisplayName("findBlock strips the ':block/' segment from the model id")
    void findBlockDerivesEntityId() {
        Optional<Block> stone = context.findBlock("minecraft:stone");
        assertThat(stone.isPresent(), is(true));
        assertThat(stone.get().getId(), equalTo("minecraft:stone"));
        assertThat(stone.get().getNamespace(), equalTo("minecraft"));
        assertThat(stone.get().getName(), equalTo("stone"));
        assertThat(stone.get().getModel(), notNullValue());
        assertThat(stone.get().getTextures().get("all"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("findBlock returns empty for unknown ids")
    void findBlockMissing() {
        assertThat(context.findBlock("minecraft:unknown").isPresent(), is(false));
    }

    @Test
    @DisplayName("findItem strips the ':item/' segment from the model id")
    void findItemDerivesEntityId() {
        Optional<Item> stick = context.findItem("minecraft:stick");
        assertThat(stick.isPresent(), is(true));
        assertThat(stick.get().getId(), equalTo("minecraft:stick"));
        assertThat(stick.get().getNamespace(), equalTo("minecraft"));
        assertThat(stick.get().getName(), equalTo("stick"));
        assertThat(stick.get().getTextures().get("layer0"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("findItem returns empty for unknown ids")
    void findItemMissing() {
        assertThat(context.findItem("minecraft:unknown").isPresent(), is(false));
    }

    @Test
    @DisplayName("leather helmet materialises with a Leather overlay carrying the default dye color")
    void leatherHelmetGetsOverlayAtPipelineTime() {
        Optional<Item> leatherHelmet = context.findItem("minecraft:leather_helmet");
        assertThat(leatherHelmet.isPresent(), is(true));
        assertThat(leatherHelmet.get().getOverlay().isPresent(), is(true));
        assertThat(leatherHelmet.get().getOverlay().get(), instanceOf(Item.Overlay.Leather.class));
        Item.Overlay.Leather leather = (Item.Overlay.Leather) leatherHelmet.get().getOverlay().get();
        assertThat(leather.baseTexture(), equalTo("minecraft:item/leather_helmet"));
        assertThat(leather.overlayTexture(), equalTo("minecraft:item/leather_helmet_overlay"));
        assertThat(leather.defaultColor(), equalTo(Item.Overlay.LEATHER_DEFAULT_ARGB));
    }

    @Test
    @DisplayName("non-overlay items materialise with empty overlay")
    void nonOverlayItemsGetEmptyOverlay() {
        Optional<Item> stick = context.findItem("minecraft:stick");
        assertThat(stick.isPresent(), is(true));
        assertThat(stick.get().getOverlay().isPresent(), is(false));
    }

    @Test
    @DisplayName("resolveTexture reads the PNG from disk and returns a matching PixelBuffer")
    void resolveTextureLoadsFromDisk() {
        Optional<PixelBuffer> buffer = context.resolveTexture("minecraft:block/fixture");
        assertThat(buffer.isPresent(), is(true));
        assertThat(buffer.get().width(), equalTo(4));
        assertThat(buffer.get().height(), equalTo(4));
        assertThat(buffer.get().getPixel(0, 0), equalTo(0xFFFF0000));
    }

    @Test
    @DisplayName("resolveTexture caches the loaded buffer on subsequent calls")
    void resolveTextureCachesResult() {
        PixelBuffer first = context.resolveTexture("minecraft:block/fixture").orElseThrow();
        PixelBuffer second = context.resolveTexture("minecraft:block/fixture").orElseThrow();
        assertThat(second, sameInstance(first));
    }

    @Test
    @DisplayName("resolveTexture normalises unnamespaced ids to minecraft:")
    void resolveTextureNormalisesNamespace() {
        Optional<PixelBuffer> namespaced = context.resolveTexture("minecraft:block/fixture");
        Optional<PixelBuffer> bare = context.resolveTexture("block/fixture");
        assertThat(namespaced.isPresent(), is(true));
        assertThat(bare.isPresent(), is(true));
        assertThat(bare.get(), sameInstance(namespaced.get()));
    }

    @Test
    @DisplayName("resolveTexture returns empty for unknown ids")
    void resolveTextureMissing() {
        assertThat(context.resolveTexture("minecraft:block/missing").isPresent(), is(false));
    }

    @Test
    @DisplayName("colorMap returns the GRASS map loaded from the bundled resource")
    void colorMapReturnsLoadedGrass() {
        Optional<ColorMap> grass = context.colorMap(ColorMap.Type.GRASS);
        assertThat(grass.isPresent(), is(true));
        assertThat(grass.get().getType(), equalTo(ColorMap.Type.GRASS));
        assertThat(grass.get().getPackId(), equalTo("vanilla"));
        // 256x256 ARGB pixels = 262144 bytes from the bundled colormap resource.
        assertThat(grass.get().getPixels().length, equalTo(256 * 256 * 4));
    }

    @Test
    @DisplayName("colorMap returns all three types from the bundled resource")
    void colorMapReturnsAllTypes() {
        assertThat(context.colorMap(ColorMap.Type.FOLIAGE).isPresent(), is(true));
        assertThat(context.colorMap(ColorMap.Type.DRY_FOLIAGE).isPresent(), is(true));
    }

    @Test
    @DisplayName("Bundled entity models are loaded and findEntity resolves them")
    void findEntityLoaded() {
        assertThat(context.findEntity("minecraft:zombie").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:skeleton").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:creeper").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:nonexistent").isPresent(), is(false));
    }

    @Test
    @DisplayName("activePacks contains only the vanilla pack entry")
    void activePacksContainsVanillaOnly() {
        ConcurrentList<TexturePack> packs = context.activePacks();
        assertThat(packs.size(), equalTo(1));
        assertThat(packs.getFirst().getId(), equalTo("vanilla"));
        assertThat(packs.getFirst().getRootPath(), not(equalTo("")));
    }

    @Test
    @DisplayName("Block.textures is populated with direction keys when the model has element faces")
    void blockTexturesFlattenElementFaces() {
        Optional<Block> cube = context.findBlock("minecraft:cube_test");
        assertThat(cube.isPresent(), is(true));

        // Direction keys derived from element[0].faces, with #variable references resolved.
        ConcurrentMap<String, String> textures = cube.get().getTextures();
        assertThat(textures.get("down"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("up"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("north"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("south"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("west"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("east"), equalTo("minecraft:block/fixture"));

        // Original variable bindings survive so the all/side/particle fallback chain still
        // works for blocks whose models do not expose element faces.
        assertThat(textures.get("all"), equalTo("minecraft:block/fixture"));
        assertThat(textures.get("top"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("Blocks without element faces leave the textures map unflattened")
    void blockWithoutElementsKeepsRawTextures() {
        Optional<Block> stone = context.findBlock("minecraft:stone");
        assertThat(stone.isPresent(), is(true));
        ConcurrentMap<String, String> textures = stone.get().getTextures();
        assertThat(textures.containsKey("down"), is(false));
        assertThat(textures.get("all"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("animationFor returns the parsed mcmeta sidecar with mixed-form frames")
    void animationForReturnsParsedMcmeta() {
        Optional<AnimationData> animation = context.animationFor("minecraft:block/fixture");
        assertThat(animation.isPresent(), is(true));

        AnimationData a = animation.get();
        assertThat(a.getFrametime(), equalTo(4));
        assertThat(a.isInterpolate(), is(true));
        assertThat(a.getFrames().size(), equalTo(4));

        // Bare-integer frames carry the -1 sentinel so AnimationKit can fall back to frametime.
        assertThat(a.getFrames().get(0).index(), equalTo(0));
        assertThat(a.getFrames().get(0).time(), equalTo(-1));
        assertThat(a.getFrames().get(1).index(), equalTo(1));
        assertThat(a.getFrames().get(1).time(), equalTo(-1));

        // Object-form frames carry their explicit per-frame duration override.
        assertThat(a.getFrames().get(3).index(), equalTo(3));
        assertThat(a.getFrames().get(3).time(), equalTo(8));
    }

    @Test
    @DisplayName("animationFor returns empty for textures without an mcmeta sidecar")
    void animationForReturnsEmptyForUnknownTextures() {
        assertThat(context.animationFor("minecraft:block/missing").isPresent(), is(false));
    }

    @Test
    @DisplayName("Texture.animation is populated by the scanner for sidecar-equipped PNGs")
    void textureAnimationFieldIsPopulated() {
        Texture fixture = result.getTextures().stream()
            .filter(t -> t.getId().equals("minecraft:block/fixture"))
            .findFirst()
            .orElseThrow();
        assertThat(fixture.getAnimation().isPresent(), is(true));
        assertThat(fixture.getAnimation().get().getFrametime(), equalTo(4));
    }

    @Test
    @DisplayName("Synthetic block tint map is wired through to AssetPipeline.Result")
    void blockTintsExposedFromResult() {
        ConcurrentMap<String, Block.Tint> tints = result.getBlockTints();
        assertThat(tints.size(), equalTo(3));
        assertThat(tints.containsKey("minecraft:grass_block"), is(true));
        assertThat(tints.containsKey("minecraft:oak_leaves"), is(true));
        assertThat(tints.containsKey("minecraft:spruce_leaves"), is(true));
    }

    @Test
    @DisplayName("Block.tint.target is populated for known vanilla colormap-tinted blocks")
    void blockTintTargetPopulatedFromVanillaTintsTable() {
        Block grassBlock = context.findBlock("minecraft:grass_block").orElseThrow();
        assertThat(grassBlock.getTint().target(), equalTo(Biome.TintTarget.GRASS));
        assertThat(grassBlock.getTint().constant().isPresent(), is(false));
    }

    @Test
    @DisplayName("Block.tint.constant is populated for known vanilla constant-tinted blocks")
    void blockTintConstantPopulatedFromVanillaTintsTable() {
        Block spruceLeaves = context.findBlock("minecraft:spruce_leaves").orElseThrow();
        assertThat(spruceLeaves.getTint().target(), equalTo(Biome.TintTarget.CONSTANT));
        assertThat(spruceLeaves.getTint().constant().isPresent(), is(true));
        assertThat(spruceLeaves.getTint().constant().get(), equalTo(0xFF619961));
    }

    @Test
    @DisplayName("Untinted blocks (not in the tints table) keep tint.target=NONE")
    void blockTintTargetDefaultsForUntintedBlocks() {
        Block stone = context.findBlock("minecraft:stone").orElseThrow();
        assertThat(stone.getTint().target(), equalTo(Biome.TintTarget.NONE));
        assertThat(stone.getTint().constant().isPresent(), is(false));
    }

}
