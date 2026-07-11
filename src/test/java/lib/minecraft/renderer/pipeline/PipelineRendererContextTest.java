package lib.minecraft.renderer.pipeline;

import com.google.gson.Gson;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.rule.CtmMethod;
import lib.minecraft.renderer.asset.rule.CtmResolution;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.pipeline.loader.ColorMapLoader;
import lib.minecraft.renderer.pipeline.loader.TextureIndexer;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.IndexedTexture;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link PipelineRendererContext}.
 * <p>
 * The fixtures build a real on-disk pack layout in a temporary directory and synthesise a
 * minimal {@link Pipeline.Result} around it, so the tests exercise the same code paths
 * a production pipeline run would hit without needing to download the client jar.
 */
@DisplayName("PipelineRendererContext wraps a pipeline result into a rendering context")
class PipelineRendererContextTest {

    @TempDir
    static Path packRoot;

    /** Context under test, wrapping the synthetic {@link #result} via {@link PipelineRendererContext#of}. */
    private static PipelineRendererContext context;

    /** Hand-assembled pipeline result the context is built from; also probed directly by pass-through tests. */
    private static Pipeline.Result result;

    /**
     * Stages a minimal on-disk pack (fixture PNG + animation sidecar + grass colormap) in the temp
     * directory, scans it with the real {@link TextureIndexer} / {@link ColorMapLoader}, synthesises
     * the remaining {@link Pipeline.Result} maps by hand, and wraps the whole thing in the
     * {@link PipelineRendererContext} under test.
     *
     * @throws IOException if writing the fixture PNGs or sidecar fails
     */
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

        // Assemble a real vanilla ResourcePack over the fixture directory and scan it into the
        // texture index with the real TextureIndexer / ColorMapLoader, keeping the test honest about
        // the exact id format and resolution shape the context resolves against.
        //
        // VanillaTintsLoader is intentionally NOT called here: it now parses BlockColors from
        // a real client jar, which the unit test does not have. Tint entries are synthesised
        // directly so the context wiring (Block.tintTarget population, findColorMap pass-through)
        // can still be exercised in isolation. The end-to-end loader is exercised by the
        // slowTest against the cached vanilla 26.1 jar.
        ResourcePack vanillaPack = new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(packRoot), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
        PackStack stack = PackStack.of(Concurrent.newList(vanillaPack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(vanillaPack))));
        ConcurrentMap<ColorMap.Type, ColorMap> colorMaps = ColorMapLoader.load();
        ConcurrentMap<String, Block.Tint> blockTints = Concurrent.newMap();
        blockTints.put("minecraft:grass_block", new Block.Tint(Block.TintTarget.GRASS, Optional.empty()));
        blockTints.put("minecraft:oak_leaves", new Block.Tint(Block.TintTarget.FOLIAGE, Optional.empty()));
        blockTints.put("minecraft:spruce_leaves", new Block.Tint(Block.TintTarget.CONSTANT, Optional.of(0xFF619961)));

        // Synthetic model maps. Each model references the fixture texture so resolveTexture
        // has a meaningful lookup target. Gson is used in place of reflective setters because
        // the DTOs are already Gson-friendly and the JSON form matches what the production
        // pipeline feeds through ModelResolver.
        Gson gson = GsonSettings.defaults().create();
        ConcurrentMap<String, ModelData> blockModels = Concurrent.newMap();
        blockModels.put(
            "minecraft:block/stone",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", ModelData.class)
        );
        // A second model with a real elements list whose face bindings reference #variables in
        // the textures map. Drives the direction-key flattening test.
        blockModels.put(
            "minecraft:block/faced_test_block",
            gson.fromJson(
                "{\"textures\":{\"all\":\"minecraft:block/fixture\",\"top\":\"minecraft:block/fixture\"},"
                    + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                    + "\"down\":{\"texture\":\"#all\"},"
                    + "\"up\":{\"texture\":\"#top\"},"
                    + "\"north\":{\"texture\":\"#all\"},"
                    + "\"south\":{\"texture\":\"#all\"},"
                    + "\"west\":{\"texture\":\"#all\"},"
                    + "\"east\":{\"texture\":\"#all\"}}}]}",
                ModelData.class
            )
        );
        // Real vanilla-named blocks used by the tint pass-through tests. The bundled
        // block_tints.json table maps grass_block -> GRASS and spruce_leaves -> CONSTANT 0xFF619961.
        blockModels.put(
            "minecraft:block/grass_block",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", ModelData.class)
        );
        blockModels.put(
            "minecraft:block/spruce_leaves",
            gson.fromJson("{\"textures\": {\"all\": \"minecraft:block/fixture\"}}", ModelData.class)
        );

        ConcurrentMap<String, ModelData> itemModels = Concurrent.newMap();
        itemModels.put(
            "minecraft:item/stick",
            gson.fromJson("{\"textures\": {\"layer0\": \"minecraft:block/fixture\"}}", ModelData.class)
        );
        // Leather helmet to verify the item-definition tints flow onto the materialised Item
        // during PipelineRendererContext.of.
        itemModels.put(
            "minecraft:item/leather_helmet",
            gson.fromJson(
                "{\"textures\": {\"layer0\": \"minecraft:item/leather_helmet\","
                    + "\"layer1\": \"minecraft:item/leather_helmet_overlay\"}}",
                ModelData.class
            )
        );

        // Per-layer tints parsed from the item definitions (here a single dye tint on layer0,
        // matching leather_helmet's vanilla `tints: [{dye, default -6265536}]`).
        ConcurrentMap<String, List<LayerTint>> itemTints = Concurrent.newMap();
        itemTints.put("minecraft:leather_helmet", List.of(new LayerTint.Dye(0xFFA06540)));

        // Always-glinted item set. Synthetic: marks the fixture stick as foil so the alwaysGlinted
        // flag plumbing through ItemIndexLoader can be asserted in isolation; vanilla's real set is
        // the 7 intrinsically-foil items (enchanted_book, nether_star, ...).
        ConcurrentSet<String> glintItems = Concurrent.newSet("minecraft:stick");

        result = new Pipeline.Result(
            packRoot,
            stack,
            colorMaps, blockTints, blockModels, itemModels,
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), itemTints, glintItems,
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), Concurrent.newList(), Concurrent.newList(),
            Concurrent.newMap()
        );
        context = PipelineRendererContext.of(result);
    }

    @Test
    @DisplayName("findBlock strips the ':block/' segment from the model id")
    void findBlockDerivesEntityId() {
        Optional<Block> stone = context.findBlock("minecraft:stone");
        assertThat(stone.isPresent(), is(true));
        assertThat(stone.get().id().id(), equalTo("minecraft:stone"));
        assertThat(stone.get().id().namespace(), equalTo("minecraft"));
        assertThat(stone.get().id().name(), equalTo("stone"));
        assertThat(stone.get().model(), notNullValue());
        assertThat(stone.get().textures().get("all"), equalTo("minecraft:block/fixture"));
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
        assertThat(stick.get().id().id(), equalTo("minecraft:stick"));
        assertThat(stick.get().id().namespace(), equalTo("minecraft"));
        assertThat(stick.get().id().name(), equalTo("stick"));
        assertThat(stick.get().textures().get("layer0"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("findItem returns empty for unknown ids")
    void findItemMissing() {
        assertThat(context.findItem("minecraft:unknown").isPresent(), is(false));
    }

    @Test
    @DisplayName("leather helmet materialises with its item-definition dye tint on layer0")
    void leatherHelmetGetsTintsAtPipelineTime() {
        Optional<Item> leatherHelmet = context.findItem("minecraft:leather_helmet");
        assertThat(leatherHelmet.isPresent(), is(true));
        assertThat(leatherHelmet.get().tints(), hasSize(1));
        assertThat(leatherHelmet.get().tints().getFirst(), instanceOf(LayerTint.Dye.class));
        LayerTint.Dye dye = (LayerTint.Dye) leatherHelmet.get().tints().getFirst();
        assertThat(dye.defaultColor(), equalTo(0xFFA06540));
    }

    @Test
    @DisplayName("non-tinted items materialise with empty tints")
    void nonTintedItemsGetEmptyTints() {
        Optional<Item> stick = context.findItem("minecraft:stick");
        assertThat(stick.isPresent(), is(true));
        assertThat(stick.get().tints().isEmpty(), is(true));
    }

    @Test
    @DisplayName("items in the glint set materialise with alwaysGlinted=true, others false")
    void glintItemsGetAlwaysGlintedFlag() {
        // The fixture marks stick as always-glinted; leather_helmet is not in the set.
        assertThat(context.findItem("minecraft:stick").orElseThrow().alwaysGlinted(), is(true));
        assertThat(context.findItem("minecraft:leather_helmet").orElseThrow().alwaysGlinted(), is(false));
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
    @DisplayName("findColorMap returns the GRASS map loaded from the bundled resource")
    void findColorMapReturnsLoadedGrass() {
        Optional<ColorMap> grass = context.findColorMap(ColorMap.Type.GRASS);
        assertThat(grass.isPresent(), is(true));
        assertThat(grass.get().type(), equalTo(ColorMap.Type.GRASS));
        assertThat(grass.get().packId(), equalTo("vanilla"));
        // 256x256 ARGB pixels = 262144 bytes from the bundled colormap resource.
        assertThat(grass.get().pixels().length, equalTo(256 * 256 * 4));
    }

    @Test
    @DisplayName("findColorMap returns all three types from the bundled resource")
    void findColorMapReturnsAllTypes() {
        assertThat(context.findColorMap(ColorMap.Type.FOLIAGE).isPresent(), is(true));
        assertThat(context.findColorMap(ColorMap.Type.DRY_FOLIAGE).isPresent(), is(true));
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
    @DisplayName("findPack resolves the vanilla pack and nothing else")
    void findPackResolvesVanillaOnly() {
        Optional<ResourcePack> vanilla = context.findPack(PackId.VANILLA);
        assertThat(vanilla.isPresent(), is(true));
        assertThat(vanilla.get().roots().isEmpty(), is(false));
        assertThat(context.findPack(new PackId("nonexistent")).isPresent(), is(false));
    }

    @Test
    @DisplayName("resolveCtm walks rules in sort order and returns the first applicable resolution")
    void resolveCtmReturnsFirstApplicableRule() {
        // Two rules: the first applies to a different block, the second matches our query.
        // resolveCtm walks in declaration order (which mirrors weight-descending sort upstream)
        // and returns the second's resolution.
        CtmRule miss = new CtmRule(
            "miss.properties", 10, CtmMethod.FIXED,
            Concurrent.newList("minecraft:cobblestone"),
            Concurrent.newList(),
            Concurrent.newList("pack:block/cobblestone_custom"),
            Concurrent.newList(),
            java.util.EnumSet.of(CtmRule.Face.ALL)
        );
        CtmRule hit = new CtmRule(
            "hit.properties", 5, CtmMethod.FIXED,
            Concurrent.newList("minecraft:stone"),
            Concurrent.newList(),
            Concurrent.newList("pack:block/stone_custom"),
            Concurrent.newList(),
            java.util.EnumSet.of(CtmRule.Face.ALL)
        );

        Pipeline.Result resultWithCtm = new Pipeline.Result(
            packRoot,
            result.getStack(),
            result.getColorMaps(), result.getBlockTints(),
            result.getBlockModels(), result.getItemModels(),
            result.getBlockVariants(), result.getBlockMultiparts(),
            result.getItemDefinitions(), result.getItemTints(), result.getGlintItems(), result.getBlockTags(),
            result.getPotionEffectColors(), result.getBannerPatterns(),
            result.getColorOverrides(), result.getCitRules(),
            Concurrent.newList(miss, hit),
            result.getBlockDefaultStateKeys()
        );
        PipelineRendererContext ctx = PipelineRendererContext.of(resultWithCtm);

        Optional<CtmResolution> resolution = ctx.resolveCtm("minecraft:stone", "minecraft:block/stone", CtmRule.Face.UP);
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("pack:block/stone_custom"));
        assertThat(resolution.get().overlayTextureId().isPresent(), is(false));

        // Block id with no matching rule returns empty.
        Optional<CtmResolution> miss2 = ctx.resolveCtm("minecraft:gravel", "minecraft:block/gravel", CtmRule.Face.UP);
        assertThat(miss2.isPresent(), is(false));
    }

    @Test
    @DisplayName("Block.textures is populated with direction keys when the model has element faces")
    void blockTexturesFlattenElementFaces() {
        Optional<Block> cube = context.findBlock("minecraft:faced_test_block");
        assertThat(cube.isPresent(), is(true));

        // Direction keys derived from element[0].faces, with #variable references resolved.
        ConcurrentMap<String, String> textures = cube.get().textures();
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
        ConcurrentMap<String, String> textures = stone.get().textures();
        assertThat(textures.containsKey("down"), is(false));
        assertThat(textures.get("all"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("findAnimation returns the parsed mcmeta sidecar with mixed-form frames")
    void findAnimationReturnsParsedMcmeta() {
        Optional<AnimationData> animation = context.findAnimation("minecraft:block/fixture");
        assertThat(animation.isPresent(), is(true));

        AnimationData a = animation.get();
        assertThat(a.frametime(), equalTo(4));
        assertThat(a.interpolate(), is(true));
        assertThat(a.frames().size(), equalTo(4));

        // Bare-integer frames carry the -1 sentinel so AnimationKit can fall back to frametime.
        assertThat(a.frames().get(0).index(), equalTo(0));
        assertThat(a.frames().get(0).time(), equalTo(-1));
        assertThat(a.frames().get(1).index(), equalTo(1));
        assertThat(a.frames().get(1).time(), equalTo(-1));

        // Object-form frames carry their explicit per-frame duration override.
        assertThat(a.frames().get(3).index(), equalTo(3));
        assertThat(a.frames().get(3).time(), equalTo(8));
    }

    @Test
    @DisplayName("findAnimation returns empty for textures without an mcmeta sidecar")
    void findAnimationReturnsEmptyForUnknownTextures() {
        assertThat(context.findAnimation("minecraft:block/missing").isPresent(), is(false));
    }

    @Test
    @DisplayName("IndexedTexture carries the whole mcmeta sidecar for sidecar-equipped PNGs")
    void textureAnimationFieldIsPopulated() {
        IndexedTexture fixture = result.getStack().textureIndex().get(ResourceId.parse("minecraft:block/fixture"));
        assertThat(fixture, is(notNullValue()));
        assertThat(fixture.meta().isPresent(), is(true));
        assertThat(fixture.meta().get().animation().isPresent(), is(true));
        assertThat(fixture.meta().get().animation().get().frametime(), equalTo(4));
    }

    @Test
    @DisplayName("Synthetic block tint map is wired through to Pipeline.Result")
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
        assertThat(grassBlock.tint().target(), equalTo(Block.TintTarget.GRASS));
        assertThat(grassBlock.tint().constant().isPresent(), is(false));
    }

    @Test
    @DisplayName("Block.tint.constant is populated for known vanilla constant-tinted blocks")
    void blockTintConstantPopulatedFromVanillaTintsTable() {
        Block spruceLeaves = context.findBlock("minecraft:spruce_leaves").orElseThrow();
        assertThat(spruceLeaves.tint().target(), equalTo(Block.TintTarget.CONSTANT));
        assertThat(spruceLeaves.tint().constant().isPresent(), is(true));
        assertThat(spruceLeaves.tint().constant().get(), equalTo(0xFF619961));
    }

    @Test
    @DisplayName("Untinted blocks (not in the tints table) keep tint.target=NONE")
    void blockTintTargetDefaultsForUntintedBlocks() {
        Block stone = context.findBlock("minecraft:stone").orElseThrow();
        assertThat(stone.tint().target(), equalTo(Block.TintTarget.NONE));
        assertThat(stone.tint().constant().isPresent(), is(false));
    }

}
