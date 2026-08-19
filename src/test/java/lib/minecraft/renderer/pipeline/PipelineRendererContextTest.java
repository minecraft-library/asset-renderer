package lib.minecraft.renderer.pipeline;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.AnimationData;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.BlockTag;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResolvedTexture;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.engine.texture.TextureSynthesizer;
import lib.minecraft.renderer.pipeline.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.pipeline.index.BlockIndexBuilder;
import lib.minecraft.renderer.pipeline.index.ItemIndexBuilder;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pipeline.pack.BlockStateLoader.BlockStates;
import lib.minecraft.renderer.pipeline.pack.ColorMapLoader;
import lib.minecraft.renderer.pipeline.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.pipeline.pack.TextureIndexer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit tests for {@link PipelineRendererContext}.
 * <p>
 * The fixtures build a real on-disk pack layout in a temporary directory and synthesise a
 * minimal {@link ClientAssets} around it, so the tests exercise the same code paths
 * a production pipeline run would hit without needing to download the client jar.
 */
@DisplayName("PipelineRendererContext wraps a pipeline result into a rendering context")
class PipelineRendererContextTest {

    @TempDir
    static Path packRoot;

    /** Context under test, built directly from the synthetic stack + indexes via its constructor. */
    private static PipelineRendererContext context;

    /** The synthetic pack stack the context is built over; probed directly by pass-through tests. */
    private static PackStack stack;

    /** The synthetic block-tint map, probed by the tint pass-through test. */
    private static ConcurrentMap<String, Block.Tint> blockTints;

    /**
     * Stages a minimal on-disk pack (fixture PNG + animation sidecar + grass colormap) in the temp
     * directory, scans it with the real {@link TextureIndexer} / {@link ColorMapLoader}, synthesises
     * the remaining {@link ClientAssets} maps by hand, and wraps the whole thing in the
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

        // Vanilla-shaped 256x256 colormaps for all three biome types so the stack-resolved
        // ColorMapLoader finds them like any other texture. A recognisable grass
        // corner pixel makes the pass-through visible; foliage / dry_foliage stay blank.
        BufferedImage grassMap = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        grassMap.setRGB(0, 0, 0xFF7FB238);
        ImageIO.write(grassMap, "PNG", colormapDir.resolve("grass.png").toFile());
        ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "PNG", colormapDir.resolve("foliage.png").toFile());
        ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "PNG", colormapDir.resolve("dry_foliage.png").toFile());

        // Assemble a real vanilla ResourcePack over the fixture directory and scan it into the
        // texture index with the real TextureIndexer / ColorMapLoader, keeping the test honest about
        // the exact id format and resolution shape the context resolves against.
        //
        // BlockTintsLoader is intentionally NOT called here. Tint entries are synthesised directly
        // so the context wiring (Block.tintTarget population, findColorMap pass-through) can be
        // exercised against a chosen table rather than against the shipped one - which is the point,
        // since a test that loaded the real block_tints.json would assert on whatever the last
        // `blockTints` run emitted. The end-to-end loader is exercised by the slowTest.
        ResourcePack vanillaPack = new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(packRoot), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Set.of("minecraft"), Set.of(PackCapability.VANILLA_CORE));
        stack = PackStack.of(Concurrent.newList(vanillaPack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(vanillaPack))));
        ConcurrentMap<Block.TintTarget, ColorMap> colorMaps = ColorMapLoader.load(stack);
        blockTints = Concurrent.newMap();
        blockTints.put("minecraft:grass_block", new Block.Tint(Block.TintTarget.GRASS, Optional.empty()));
        blockTints.put("minecraft:oak_leaves", new Block.Tint(Block.TintTarget.FOLIAGE, Optional.empty()));
        blockTints.put("minecraft:spruce_leaves", new Block.Tint(Block.TintTarget.CONSTANT, Optional.of(new Color(0xFF619961, true))));

        // Synthetic model maps. Each model references the fixture texture so resolveTexture
        // has a meaningful lookup target. Gson is used in place of reflective setters because
        // the DTOs are already Gson-friendly and the JSON form matches what the production
        // pipeline feeds through ResolvedModels.
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
        // Real vanilla-named blocks used by the tint pass-through tests. The synthetic table above
        // maps grass_block -> GRASS and spruce_leaves -> CONSTANT 0xFF619961.
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
        // flag plumbing through ItemIndexBuilder can be asserted in isolation; vanilla's real set is
        // the 7 intrinsically-foil items (enchanted_book, nether_star, ...).
        ConcurrentSet<String> glintItems = Concurrent.newSet("minecraft:stick");

        // The context is built directly via its @RequiredArgsConstructor (of() takes real ClientAssets;
        // this test drives synthetic maps), running the same index loaders of() runs over the stack.
        ConcurrentMap<String, ItemModelTree> itemTrees = Concurrent.newMap();
        ConcurrentMap<String, BlockTag> blockTags = Concurrent.newMap();

        BlockModelLoader.LoadResult beResult = BlockModelLoader.load(stack);
        ConcurrentMap<String, Block.Entity> blockEntities = beResult.models();
        BlockTables blockTables = new BlockTables(
            blockModels, blockTints, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            blockEntities, beResult.variants(), itemTrees, itemModels);
        ConcurrentMap<String, Block> blockIndex = BlockIndexBuilder.load(
            blockTables, new BlockStates(Concurrent.newMap(), Concurrent.newMap()), blockTags);
        ConcurrentMap<String, Item> itemIndex = ItemIndexBuilder.load(itemTints, glintItems, itemModels, itemTrees, blockEntities);
        ConcurrentMap<String, Entity> entityIndex = EntityModelLoader.load();
        TextureSynthesizer synthesizer = new TextureSynthesizer(PalettedPermutationLoader.load(stack));

        context = new PipelineRendererContext(
            stack, blockIndex, itemIndex, itemTrees, itemModels, entityIndex, colorMaps,
            blockTags, Concurrent.newMap(), Concurrent.newMap(), blockEntities, synthesizer,
            Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
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
    @DisplayName("resolveEquipmentLayers is total - an unshipped asset id yields MISSING's empty layers, never a throw")
    void equipmentLookupIsTotal() {
        // The equipment index is the pipeline's own map and the MISSING default is applied here, at the
        // one seam that serves it. That totality is what an armour render relies on: a material naming
        // an asset the stack does not ship draws nothing rather than failing the whole frame.
        assertThat(context.resolveEquipmentLayers(new ResourceId("minecraft", "unshipped"), LayerType.HUMANOID),
            is(empty()));
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
        Optional<ColorMap> grass = context.findColorMap(Block.TintTarget.GRASS);
        assertThat(grass.isPresent(), is(true));
        assertThat(grass.get().type(), equalTo(Block.TintTarget.GRASS));
        assertThat(grass.get().packId(), equalTo("vanilla"));
        // 256x256 ARGB pixels = 262144 bytes from the bundled colormap resource.
        assertThat(grass.get().pixels().length, equalTo(256 * 256 * 4));
    }

    @Test
    @DisplayName("findColorMap returns all three types from the bundled resource")
    void findColorMapReturnsAllTypes() {
        assertThat(context.findColorMap(Block.TintTarget.FOLIAGE).isPresent(), is(true));
        assertThat(context.findColorMap(Block.TintTarget.DRY_FOLIAGE).isPresent(), is(true));
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
    @DisplayName("the pack stack resolves the vanilla pack and nothing else")
    void stackResolvesVanillaOnly() {
        Optional<ResourcePack> vanilla = stack.byId(PackId.VANILLA);
        assertThat(vanilla.isPresent(), is(true));
        assertThat(vanilla.get().roots().isEmpty(), is(false));
        assertThat(stack.byId(new PackId("nonexistent")).isPresent(), is(false));
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
        assertThat(a.frames().getFirst().index(), equalTo(0));
        assertThat(a.frames().getFirst().time(), equalTo(-1));
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
    @DisplayName("findMeta hands back the whole sidecar, leaving each section to its caller")
    void findMetaReturnsTheWholeSidecar() {
        Optional<MCMeta> meta = context.findMeta("minecraft:block/fixture");
        assertThat(meta.isPresent(), is(true));
        assertThat("the section findAnimation adapts is present on the document itself",
            meta.get().animation().isPresent(), is(true));
        assertThat("a section the fixture declares nothing for reads empty",
            meta.get().villager().isPresent(), is(false));
        assertThat("a texture shipping no sidecar reads empty",
            context.findMeta("minecraft:block/missing").isPresent(), is(false));
    }

    @Test
    @DisplayName("ResolvedTexture carries the whole mcmeta sidecar for sidecar-equipped PNGs")
    void textureAnimationFieldIsPopulated() {
        ResolvedTexture fixture = stack.textureIndex().get(ResourceId.parse("minecraft:block/fixture"));
        assertThat(fixture, is(notNullValue()));
        assertThat(fixture.meta().isPresent(), is(true));
        assertThat(fixture.meta().get().animation().isPresent(), is(true));
        assertThat(fixture.meta().get().animation().get().frametime(), equalTo(4));
    }

    @Test
    @DisplayName("Block.tint.target is populated for known vanilla colormap-tinted blocks")
    void blockTintTargetPopulatedFromTheTintTable() {
        Block grassBlock = context.findBlock("minecraft:grass_block").orElseThrow();
        assertThat(grassBlock.tint().target(), equalTo(Block.TintTarget.GRASS));
        assertThat(grassBlock.tint().constant().isPresent(), is(false));
    }

    @Test
    @DisplayName("Block.tint.constant is populated for known vanilla constant-tinted blocks")
    void blockTintConstantPopulatedFromTheTintTable() {
        Block spruceLeaves = context.findBlock("minecraft:spruce_leaves").orElseThrow();
        assertThat(spruceLeaves.tint().target(), equalTo(Block.TintTarget.CONSTANT));
        assertThat(spruceLeaves.tint().constant().isPresent(), is(true));
        assertThat(spruceLeaves.tint().constant().get().getRGB(), equalTo(0xFF619961));
    }

    @Test
    @DisplayName("Untinted blocks (not in the synthetic tint table) keep tint.target=NONE")
    void blockTintTargetDefaultsForUntintedBlocks() {
        Block stone = context.findBlock("minecraft:stone").orElseThrow();
        assertThat(stone.tint().target(), equalTo(Block.TintTarget.NONE));
        assertThat(stone.tint().constant().isPresent(), is(false));
    }

}
