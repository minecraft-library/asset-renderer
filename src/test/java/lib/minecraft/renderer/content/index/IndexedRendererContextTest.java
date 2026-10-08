package lib.minecraft.renderer.content.index;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.index.BlockIndexBuilder.BlockTables;
import lib.minecraft.renderer.content.pack.BlockStateLoader.ApplyDto;
import lib.minecraft.renderer.content.pack.BlockStateLoader.BlockStates;
import lib.minecraft.renderer.content.pack.BlockTag;
import lib.minecraft.renderer.content.pack.ColorMapLoader;
import lib.minecraft.renderer.content.pack.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.ResolvedTexture;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.TintSource;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
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
import java.util.Map;
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
 * Unit tests for {@link IndexedRendererContext}.
 * <p>
 * The fixtures build a real on-disk pack layout in a temporary directory and synthesise a
 * minimal {@link ClientAssets} around it, so the tests exercise the same code paths
 * a production pipeline run would hit without needing to download the client jar.
 */
@DisplayName("IndexedRendererContext wraps a pipeline result into a rendering context")
class IndexedRendererContextTest {

    @TempDir
    static Path packRoot;

    /**
     * A fixture texture the pack ships with no sidecar beside it.
     */
    private static final String STATIC = "minecraft:block/fixture_static";

    /**
     * Context under test, built directly from the synthetic stack + indexes via its constructor.
     */
    private static IndexedRendererContext context;

    /**
     * The synthetic pack stack the context is built over; probed directly by pass-through tests.
     */
    private static PackStack stack;

    /**
     * The synthetic block-tint map, probed by the tint pass-through test.
     */
    private static ConcurrentMap<String, Block.Tint> blockTints;

    /**
     * Stages a minimal on-disk pack (fixture PNG + animation sidecar, a sidecar-less copy, grass colormap) in the temp
     * directory, scans it with the real {@link TextureIndexer} / {@link ColorMapLoader}, synthesises
     * the remaining {@link ClientAssets} maps by hand, and wraps the whole thing in the
     * {@link IndexedRendererContext} under test.
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

        // The same pixels again with no sidecar beside them: a texture that is served and plays nothing.
        ImageIO.write(image, "PNG", blockDir.resolve("fixture_static.png").toFile());

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
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        stack = PackStack.of(Concurrent.newList(vanillaPack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(vanillaPack))));
        ConcurrentMap<TintSource, ColorMap> colorMaps = ColorMapLoader.load(stack);
        blockTints = Concurrent.newMap();
        blockTints.put("minecraft:grass_block", new Block.Tint(TintSource.GRASS, Optional.empty()));
        blockTints.put("minecraft:oak_leaves", new Block.Tint(TintSource.FOLIAGE, Optional.empty()));
        blockTints.put("minecraft:spruce_leaves", new Block.Tint(TintSource.CONSTANT, Optional.of(new Color(0xFF619961, true))));

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
        // Blocks the index keeps no row for. Air and water bind only a particle, so neither declares
        // anything to draw, where broken declares a face whose reference resolves nowhere. Each is
        // registered below - air and broken through the block defaults, water, cave_air and
        // bubble_column through a blockstate, the last two naming air's and water's models.
        blockModels.put(
            "minecraft:block/air",
            gson.fromJson("{\"textures\": {\"particle\": \"minecraft:missingno\"}}", ModelData.class)
        );
        blockModels.put(
            "minecraft:block/water",
            gson.fromJson("{\"textures\": {\"particle\": \"minecraft:block/water_still\"}}", ModelData.class)
        );
        blockModels.put(
            "minecraft:block/broken",
            gson.fromJson("{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                + "\"faces\":{\"north\":{\"texture\":\"#missing\"}}}]}", ModelData.class)
        );
        ConcurrentMap<String, ConcurrentMap<String, String>> blockDefaults = Concurrent.newMap();
        blockDefaults.put("minecraft:air", Concurrent.newMap());
        blockDefaults.put("minecraft:broken", Concurrent.newMap());
        ConcurrentMap<String, ConcurrentMap<String, ApplyDto>> blockstates = Concurrent.newMap();
        blockstates.put("minecraft:water", Concurrent.newMap(Map.entry("", apply("minecraft:block/water"))));
        blockstates.put("minecraft:cave_air", Concurrent.newMap(Map.entry("", apply("minecraft:block/air"))));
        blockstates.put("minecraft:bubble_column", Concurrent.newMap(Map.entry("", apply("minecraft:block/water"))));

        ConcurrentMap<String, ModelData> itemModels = Concurrent.newMap();
        itemModels.put(
            "minecraft:item/stick",
            gson.fromJson("{\"textures\": {\"layer0\": \"minecraft:block/fixture\"}}", ModelData.class)
        );
        // Leather helmet to verify the item-definition tints flow onto the materialised Item
        // during IndexedRendererContext.of.
        itemModels.put(
            "minecraft:item/leather_helmet",
            gson.fromJson(
                "{\"textures\": {\"layer0\": \"minecraft:item/leather_helmet\","
                    + "\"layer1\": \"minecraft:item/leather_helmet_overlay\"}}",
                ModelData.class
            )
        );
        // Air's item model binds only a particle; generated is a template no definition registers.
        itemModels.put(
            "minecraft:item/air",
            gson.fromJson("{\"textures\": {\"particle\": \"minecraft:missingno\"}}", ModelData.class)
        );
        itemModels.put("minecraft:item/generated", gson.fromJson("{}", ModelData.class));

        // Per-layer tints parsed from the item definitions (here a single dye tint on layer0,
        // matching leather_helmet's vanilla `tints: [{dye, default -6265536}]`).
        ConcurrentMap<String, ConcurrentList<LayerTint>> itemTints = Concurrent.newMap();
        itemTints.put("minecraft:leather_helmet", Concurrent.newUnmodifiableList(new LayerTint.Dye(0xFFA06540)));

        // Always-glinted item set. Synthetic: marks the fixture stick as foil so the alwaysGlinted
        // flag plumbing through ItemIndexBuilder can be asserted in isolation; vanilla's real set is
        // the 7 intrinsically-foil items (enchanted_book, nether_star, ...).
        ConcurrentSet<String> glintItems = Concurrent.newSet("minecraft:stick");

        // The whole-tree map the model lookup reads: both indexed sets plus a model outside them.
        ConcurrentMap<String, ModelData> allModels = Concurrent.newMap();
        allModels.putAll(blockModels);
        allModels.putAll(itemModels);
        allModels.put(
            "minecraft:custom/outside",
            gson.fromJson("{\"textures\": {\"layer0\": \"minecraft:block/fixture\"}}", ModelData.class)
        );

        // The context is built directly via its @RequiredArgsConstructor (of() takes real ClientAssets;
        // this test drives synthetic maps), running the same index loaders of() runs over the stack.
        // Item definitions, each read through the real deserializer: air's names its blank model,
        // nothing's root is minecraft:empty with no model of its name, modded's root is a mod's node
        // type, and refused is a definition the loader refused.
        ConcurrentMap<String, ItemModelTree> itemTrees = Concurrent.newMap();
        itemTrees.put("minecraft:air", tree(gson, "minecraft:air", "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/air\"}"));
        itemTrees.put("minecraft:nothing", tree(gson, "minecraft:nothing", "{\"type\":\"minecraft:empty\"}"));
        itemTrees.put("minecraft:modded", tree(gson, "minecraft:modded", "{\"type\":\"hplus:fancy\"}"));
        itemTrees.put("minecraft:refused", ItemModelTree.rejected(ResourceId.parse("minecraft:refused")));
        ConcurrentMap<String, BlockTag> blockTags = Concurrent.newMap();

        BlockModelLoader.LoadResult beResult = BlockModelLoader.load(stack);
        ConcurrentMap<String, Block.BlockEntity> blockEntities = beResult.models();
        BlockTables blockTables = new BlockTables(
            blockModels, blockTints, Concurrent.newMap(), blockDefaults, Concurrent.newMap(),
            blockEntities, beResult.variants(), itemTrees, itemModels);
        IndexRows<Block> blockRows = BlockIndexBuilder.load(
            blockTables, new BlockStates(blockstates, Concurrent.newMap()), blockTags, stack);
        IndexRows<Item> itemRows = ItemIndexBuilder.load(itemTints, glintItems, itemModels, itemTrees, blockEntities);
        ConcurrentMap<String, Entity> entityIndex = EntityModelLoader.loadAll();
        TextureSynthesizer synthesizer = new TextureSynthesizer(PalettedPermutationLoader.load(stack));

        context = new IndexedRendererContext(
            stack, blockRows.rows(), blockRows.drawsNothing(), itemRows.rows(), itemRows.drawsNothing(), itemTrees,
            new ResolvedModels(blockModels, itemModels, allModels), entityIndex, colorMaps,
            blockTags, Concurrent.newMap(), Concurrent.newMap(), blockEntities, synthesizer,
            Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
    }

    @Test
    @DisplayName("findBlock strips the ':block/' segment from the model id")
    void findBlockDerivesEntityId() {
        Possible<Block> stone = context.findBlock("minecraft:stone");
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
    @DisplayName("findBlock answers a row present, a registered block that draws nothing empty, and every other id absent")
    void findBlockTellsABlockDrawingNothingFromAnUnknownOne() {
        assertThat(context.findBlock("minecraft:stone").getState(), is(Possible.State.PRESENT));

        // Air registers through the block defaults and keeps no row; cave_air registers through a
        // blockstate naming air's model, with no model file of its own.
        assertThat(context.findBlock("minecraft:air").getState(), is(Possible.State.EMPTY));
        assertThat(context.findBlock("minecraft:cave_air").getState(), is(Possible.State.EMPTY));

        // Water's model declares nothing either, but a fluid renderer draws it, and bubble_column
        // draws from the same model; a face resolving nowhere is a face, so broken declares something.
        assertThat(context.findBlock("minecraft:water").getState(), is(Possible.State.ABSENT));
        assertThat(context.findBlock("minecraft:bubble_column").getState(), is(Possible.State.ABSENT));
        assertThat(context.findBlock("minecraft:broken").getState(), is(Possible.State.ABSENT));
        assertThat(context.findBlock("minecraft:unknown").getState(), is(Possible.State.ABSENT));

        // The block-entity lookup derives from it, so a block drawing nothing carries no entry.
        assertThat(context.findBlockEntityEntry("minecraft:air").getState(), is(Possible.State.EMPTY));
        assertThat(context.findBlockEntityEntry("minecraft:water").getState(), is(Possible.State.ABSENT));

        // A wrapper forwards the lookup, so it answers the context's own states.
        RendererContext wrapped = context.withMissingTexture();
        assertThat(wrapped.findBlock("minecraft:air").getState(), is(Possible.State.EMPTY));
        assertThat(wrapped.findBlock("minecraft:water").getState(), is(Possible.State.ABSENT));
        assertThat(wrapped.findBlockEntityEntry("minecraft:cave_air").getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("findItem answers a row present, a registered item that draws nothing empty, and every other id absent")
    void findItemTellsAnItemDrawingNothingFromAnUnknownOne() {
        assertThat(context.findItem("minecraft:stick").getState(), is(Possible.State.PRESENT));

        // Air's definition registers it and its model declares nothing; nothing has no model of its
        // name, and its definition's root is minecraft:empty.
        assertThat(context.findItem("minecraft:air").getState(), is(Possible.State.EMPTY));
        assertThat(context.findItem("minecraft:nothing").getState(), is(Possible.State.EMPTY));

        // A mod's node type at the root reads as a refused definition does, drawing vanilla's missing
        // item model rather than nothing, so neither joins; a template is not registered.
        assertThat(context.findItemTree("minecraft:modded").map(ItemModelTree::isRejected), is(Possible.of(true)));
        assertThat(context.findItem("minecraft:modded").getState(), is(Possible.State.ABSENT));
        assertThat(context.findItem("minecraft:refused").getState(), is(Possible.State.ABSENT));
        assertThat(context.findItem("minecraft:generated").getState(), is(Possible.State.ABSENT));
        assertThat(context.findItem("minecraft:unknown").getState(), is(Possible.State.ABSENT));

        RendererContext wrapped = context.withMissingTexture();
        assertThat(wrapped.findItem("minecraft:air").getState(), is(Possible.State.EMPTY));
        assertThat(wrapped.findItem("minecraft:modded").getState(), is(Possible.State.ABSENT));
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
        Possible<Item> stick = context.findItem("minecraft:stick");
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
    @DisplayName("findItemModel answers an item model, a block model and a model outside both, a bare id read as minecraft:")
    void findItemModelAnswersTheWholeTree() {
        assertThat(context.findItemModel("minecraft:item/stick").isPresent(), is(true));
        assertThat(context.findItemModel("minecraft:block/faced_test_block").isPresent(), is(true));
        assertThat(context.findItemModel("minecraft:custom/outside").isPresent(), is(true));
        assertThat(context.findItemModel("block/faced_test_block").orElseThrow(),
            is(sameInstance(context.findItemModel("minecraft:block/faced_test_block").orElseThrow())));
        assertThat(context.findItemModel("minecraft:block/unknown").isPresent(), is(false));
    }

    @Test
    @DisplayName("findItemModel answers a loaded model declaring nothing to draw as an item empty, and an unloaded id absent")
    void findItemModelTellsAModelDrawingNothingFromAnUnloadedOne() {
        // Air's item model and block model bind a particle alone, generated is an item template with no
        // binding at all, and stone's fixture binds a block texture to no element - none of which an
        // item draws.
        for (String blank : List.of("minecraft:item/air", "minecraft:block/air", "minecraft:item/generated", "minecraft:block/stone"))
            assertThat(blank, context.findItemModel(blank).getState(), is(Possible.State.EMPTY));

        assertThat(context.findItemModel("minecraft:item/stick").getState(), is(Possible.State.PRESENT));
        assertThat(context.findItemModel("minecraft:block/unknown").getState(), is(Possible.State.ABSENT));
        assertThat(context.withMissingTexture().findItemModel("item/air").getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("findItemTree answers a definition present, one rooted at minecraft:empty empty, a refused one present, and no definition absent")
    void findItemTreeTellsADefinitionDrawingNothingFromNoDefinition() {
        assertThat(context.findItemTree("minecraft:air").getState(), is(Possible.State.PRESENT));
        assertThat(context.findItemTree("minecraft:nothing").getState(), is(Possible.State.EMPTY));

        // A refused definition and a mod's root both root at the absent node, and stay present: their
        // rejected tree is what draws vanilla's missing item model.
        assertThat(context.findItemTree("minecraft:refused").map(ItemModelTree::isRejected), is(Possible.of(true)));
        assertThat(context.findItemTree("minecraft:modded").map(ItemModelTree::isRejected), is(Possible.of(true)));
        assertThat(context.findItemTree("minecraft:stick").getState(), is(Possible.State.ABSENT));
        assertThat(context.withMissingTexture().findItemTree("minecraft:nothing").getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("leather helmet materialises with its item-definition dye tint on layer0")
    void leatherHelmetGetsTintsAtPipelineTime() {
        Possible<Item> leatherHelmet = context.findItem("minecraft:leather_helmet");
        assertThat(leatherHelmet.isPresent(), is(true));
        assertThat(leatherHelmet.get().tints(), hasSize(1));
        assertThat(leatherHelmet.get().tints().getFirst(), instanceOf(LayerTint.Dye.class));
        LayerTint.Dye dye = (LayerTint.Dye) leatherHelmet.get().tints().getFirst();
        assertThat(dye.defaultColor(), equalTo(0xFFA06540));
    }

    @Test
    @DisplayName("non-tinted items materialise with empty tints")
    void nonTintedItemsGetEmptyTints() {
        Possible<Item> stick = context.findItem("minecraft:stick");
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
        Possible<PixelBuffer> buffer = context.resolveTexture("minecraft:block/fixture");
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
        Possible<PixelBuffer> namespaced = context.resolveTexture("minecraft:block/fixture");
        Possible<PixelBuffer> bare = context.resolveTexture("block/fixture");
        assertThat(namespaced.isPresent(), is(true));
        assertThat(bare.isPresent(), is(true));
        assertThat(bare.get(), sameInstance(namespaced.get()));
    }

    @Test
    @DisplayName("resolveTexture answers absent for unknown ids")
    void resolveTextureMissing() {
        assertThat(context.resolveTexture("minecraft:block/missing").isAbsent(), is(true));
    }

    @Test
    @DisplayName("findColorMap returns the GRASS map loaded from the bundled resource")
    void findColorMapReturnsLoadedGrass() {
        Possible<ColorMap> grass = context.findColorMap(TintSource.GRASS);
        assertThat(grass.isPresent(), is(true));
        assertThat(grass.get().type(), equalTo(TintSource.GRASS));
        assertThat(grass.get().packId(), equalTo("vanilla"));
        // 256x256 ARGB pixels = 262144 bytes from the bundled colormap resource.
        assertThat(grass.get().pixels().length, equalTo(256 * 256 * 4));
    }

    @Test
    @DisplayName("findColorMap returns all three types from the bundled resource")
    void findColorMapReturnsAllTypes() {
        assertThat(context.findColorMap(TintSource.FOLIAGE).isPresent(), is(true));
        assertThat(context.findColorMap(TintSource.DRY_FOLIAGE).isPresent(), is(true));
    }

    @Test
    @DisplayName("findColorMap answers empty for a target naming no colormap, and absent for one the context holds none of")
    void findColorMapTellsNoColormapFromAMissingOne() {
        // The fixture holds all three colormaps, so a target answering empty here names none at all.
        assertThat(context.findColorMap(TintSource.WATER).getState(), is(Possible.State.EMPTY));
        assertThat(context.findColorMap(TintSource.NONE).getState(), is(Possible.State.EMPTY));
        assertThat(context.findColorMap(TintSource.CONSTANT).getState(), is(Possible.State.EMPTY));

        IndexedRendererContext withoutColormaps = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
        assertThat(withoutColormaps.findColorMap(TintSource.GRASS).getState(), is(Possible.State.ABSENT));
        assertThat(withoutColormaps.findColorMap(TintSource.FOLIAGE).getState(), is(Possible.State.ABSENT));
        assertThat(withoutColormaps.findColorMap(TintSource.WATER).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("findBlockEntityEntry answers a table entry, empty for a known block carrying none, and absent for an unknown id")
    void findBlockEntityEntryTellsAPlainBlockFromAnUnknownOne() {
        assertThat(context.findBlockEntityEntry("minecraft:oak_sign").isPresent(), is(true));
        assertThat(context.findBlock("minecraft:stone").isPresent(), is(true));
        assertThat(context.findBlockEntityEntry("minecraft:stone").getState(), is(Possible.State.EMPTY));
        assertThat(context.findBlockEntityEntry("minecraft:nonexistent").getState(), is(Possible.State.ABSENT));

        // A wrapper forwards the lookup, so its three answers are the context's own.
        RendererContext wrapped = context.withMissingTexture();
        assertThat(wrapped.findBlockEntityEntry("minecraft:oak_sign").isPresent(), is(true));
        assertThat(wrapped.findBlockEntityEntry("minecraft:stone").getState(), is(Possible.State.EMPTY));
        assertThat(wrapped.findBlockEntityEntry("minecraft:nonexistent").getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("an in-memory context derives findBlockEntityEntry from the block it holds")
    void inMemoryBlockEntityEntryDerivesFromTheBlock() {
        Block stone = context.findBlock("minecraft:stone").orElseThrow();
        Block.BlockEntity sign = context.findBlockEntityEntry("minecraft:oak_sign").orElseThrow();
        Block signed = new Block(
            new ResourceId("minecraft", "signed"), stone.model(), stone.textures(), stone.variants(),
            stone.multipart(), stone.tags(), stone.tint(), Optional.of(sign), stone.source(),
            stone.defaultState(), stone.itemBlockId(), stone.iconGui(), stone.modelIcon(), stone.flipbooks());
        RendererContext inMemory = RendererContext.builder()
            .blocks(Map.of("minecraft:stone", stone, "minecraft:signed", signed))
            .build();

        assertThat(inMemory.findBlockEntityEntry("minecraft:signed").orElseThrow(), is(sameInstance(sign)));
        assertThat(inMemory.findBlockEntityEntry("minecraft:stone").getState(), is(Possible.State.EMPTY));
        assertThat(inMemory.findBlockEntityEntry("minecraft:oak_sign").getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("resolveConnectedTexture answers absent for every face on a stack shipping no CTM rules")
    void resolveConnectedTextureIsAbsentWithoutRules() {
        Face.forEach(face -> assertThat(
            context.resolveConnectedTexture("minecraft:stone", Map.of(), "minecraft:block/fixture", face).getState(),
            is(Possible.State.ABSENT)));
    }

    @Test
    @DisplayName("Bundled entity models are loaded and findEntity resolves them")
    void findEntityLoaded() {
        assertThat(context.findEntity("minecraft:zombie").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:skeleton").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:creeper").isPresent(), is(true));
        assertThat(context.findEntity("minecraft:nonexistent").getState(), is(Possible.State.ABSENT));
        // A type vanilla draws that this renderer holds no row for is absent, not empty.
        assertThat(context.findEntity("minecraft:oak_boat").getState(), is(Possible.State.ABSENT));
        assertThat(context.findEntity("minecraft:experience_orb").getState(), is(Possible.State.ABSENT));
        // The types vanilla binds to its no-op renderer ship a row naming no mesh, and are empty.
        for (String id : EntityMeshlessRowTest.DRAWING_NOTHING)
            assertThat(id + " ships a row that draws nothing", context.findEntity(id).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("findEntity answers a drawn row present, a row whose body mesh holds no bone empty, and an id it holds no row for absent")
    void findEntityTellsARowDrawingNothingFromAnUnknownId() {
        Entity zombie = context.findEntity("minecraft:zombie").orElseThrow();
        // The rows the shipped table carries for the types vanilla binds to its no-op renderer,
        // assembled from their own JSON as the loader assembles them.
        ConcurrentMap<String, Entity> rows = EntityMeshlessRowTest.drawingNothing();
        rows.put("minecraft:zombie", zombie);
        IndexedRendererContext entities = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            rows, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());

        assertThat(entities.findEntity("minecraft:zombie").orElseThrow(), is(sameInstance(zombie)));
        for (String id : EntityMeshlessRowTest.DRAWING_NOTHING)
            assertThat(id + " is known and draws nothing", entities.findEntity(id).getState(), is(Possible.State.EMPTY));
        assertThat(entities.findEntity("minecraft:oak_boat").getState(), is(Possible.State.ABSENT));
        assertThat(entities.findEntity("minecraft:experience_orb").getState(), is(Possible.State.ABSENT));
        assertThat(entities.findEntity("minecraft:nonexistent").getState(), is(Possible.State.ABSENT));

        // A wrapper forwards the lookup, so its three answers are the context's own; one supplying
        // rows of its own passes every other id through as the context answers it.
        RendererContext wrapped = entities.withMissingTexture();
        assertThat(wrapped.findEntity("minecraft:marker").getState(), is(Possible.State.EMPTY));
        assertThat(wrapped.findEntity("minecraft:nonexistent").getState(), is(Possible.State.ABSENT));
        RendererContext withCustom = entities.withEntities(Map.of("custom:zombie", zombie));
        assertThat(withCustom.findEntity("minecraft:marker").getState(), is(Possible.State.EMPTY));
        assertThat(withCustom.findEntity("custom:zombie").orElseThrow(), is(sameInstance(zombie)));
    }

    @Test
    @DisplayName("withEntities answers its own rows and passes every other id through as the context answers it")
    void withEntitiesAnswersItsRowsAndPassesTheRestThrough() {
        Entity zombie = context.findEntity("minecraft:zombie").orElseThrow();
        RendererContext withCustom = RendererContext.builder().build()
            .withEntities(Map.of("custom:zombie", zombie));

        assertThat(withCustom.findEntity("custom:zombie").orElseThrow(), is(sameInstance(zombie)));
        assertThat(withCustom.findEntity("minecraft:zombie").getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("the keyed lookups answer present for a key the context holds and absent for any other, never empty")
    void keyedLookupsAnswerPresentOrAbsent() {
        ConcurrentMap<String, ItemModelTree> trees = Concurrent.newMap();
        ResourceId refusedId = new ResourceId("minecraft", "refused");
        trees.put(refusedId.id(), ItemModelTree.rejected(refusedId));
        ConcurrentMap<String, Integer> potions = Concurrent.newMap();
        potions.put("minecraft:strength", 0xFFFFC700);
        ConcurrentMap<String, BannerPattern> patterns = Concurrent.newMap();
        BannerPattern creeper = new BannerPattern("minecraft:creeper", "minecraft:creeper", "block.minecraft.banner.creeper");
        patterns.put("minecraft:creeper", creeper);
        IndexedRendererContext tables = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), trees,
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), potions, patterns,
            Concurrent.newMap(), new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());

        // A definition the loader refused is held, and answers present as its rejected tree.
        assertThat(tables.findItemTree(refusedId.id()).map(ItemModelTree::isRejected), is(Possible.of(true)));
        assertThat(tables.findItemTree("minecraft:stick").getState(), is(Possible.State.ABSENT));
        assertThat(tables.findPotionEffectColor("minecraft:strength"), is(Possible.of(0xFFFFC700)));
        assertThat(tables.findPotionEffectColor("minecraft:weakness").getState(), is(Possible.State.ABSENT));
        assertThat(tables.findBannerPattern("minecraft:creeper").orElseThrow(), is(sameInstance(creeper)));
        assertThat(tables.findBannerPattern("minecraft:flow").getState(), is(Possible.State.ABSENT));
        // The fixture stack ships no color.properties, so no key is there.
        assertThat(tables.findColorOverride("grass.plains").getState(), is(Possible.State.ABSENT));
        assertThat(context.findItemModel("minecraft:block/unknown").getState(), is(Possible.State.ABSENT));

        RendererContext inMemory = RendererContext.builder().colorOverrides(Map.of("grass.plains", 0xFF123456)).build();
        assertThat(inMemory.findColorOverride("grass.plains"), is(Possible.of(0xFF123456)));
        assertThat(inMemory.findColorOverride("grass.forest").getState(), is(Possible.State.ABSENT));
        assertThat(inMemory.findPotionEffectColor("minecraft:strength").getState(), is(Possible.State.ABSENT));
        assertThat(inMemory.findBannerPattern("minecraft:creeper").getState(), is(Possible.State.ABSENT));
        assertThat(inMemory.findItemTree("minecraft:stick").getState(), is(Possible.State.ABSENT));
        assertThat(inMemory.findItemModel("minecraft:item/stick").getState(), is(Possible.State.ABSENT));
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
        Possible<Block> cube = context.findBlock("minecraft:faced_test_block");
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
        Possible<Block> stone = context.findBlock("minecraft:stone");
        assertThat(stone.isPresent(), is(true));
        ConcurrentMap<String, String> textures = stone.get().textures();
        assertThat(textures.containsKey("down"), is(false));
        assertThat(textures.get("all"), equalTo("minecraft:block/fixture"));
    }

    @Test
    @DisplayName("findAnimation returns the parsed mcmeta sidecar with mixed-form frames")
    void findAnimationReturnsParsedMcmeta() {
        Possible<MCMeta.Animation> animation = context.findAnimation("minecraft:block/fixture");
        assertThat(animation.isPresent(), is(true));

        MCMeta.Animation a = animation.get();
        assertThat(a.frametime(), equalTo(4));
        assertThat(a.interpolate(), is(true));
        assertThat(a.frames().size(), equalTo(4));

        // Bare-integer frames carry the -1 sentinel so the table can fall back to frametime.
        assertThat(a.frames().getFirst().index(), equalTo(0));
        assertThat(a.frames().getFirst().time(), equalTo(-1));
        assertThat(a.frames().get(1).index(), equalTo(1));
        assertThat(a.frames().get(1).time(), equalTo(-1));

        // Object-form frames carry their explicit per-frame duration override.
        assertThat(a.frames().get(3).index(), equalTo(3));
        assertThat(a.frames().get(3).time(), equalTo(8));
    }

    @Test
    @DisplayName("a served texture shipping no sidecar answers every metadata view empty")
    void aSidecarlessTextureAnswersItsViewsEmpty() {
        assertThat("the texture is served", context.resolveTexture(STATIC).isPresent(), is(true));
        assertThat(context.findMeta(STATIC).getState(), is(Possible.State.EMPTY));
        assertThat(context.findAnimation(STATIC).getState(), is(Possible.State.EMPTY));
        assertThat(context.findFlipbook(STATIC).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("an id nothing serves answers every metadata view absent")
    void anUnservedIdAnswersItsViewsAbsent() {
        assertThat("nothing serves the id", context.resolveTexture("minecraft:block/missing").isAbsent(), is(true));
        assertThat(context.findMeta("minecraft:block/missing").isAbsent(), is(true));
        assertThat(context.findAnimation("minecraft:block/missing").isAbsent(), is(true));
        assertThat(context.findFlipbook("minecraft:block/missing").isAbsent(), is(true));
    }

    @Test
    @DisplayName("findMeta hands back the whole sidecar, leaving each section to its caller")
    void findMetaReturnsTheWholeSidecar() {
        Possible<MCMeta> meta = context.findMeta("minecraft:block/fixture");
        assertThat(meta.isPresent(), is(true));
        assertThat("the section findAnimation adapts is present on the document itself",
            meta.get().animation().isPresent(), is(true));
        assertThat("a section the fixture declares nothing for reads empty",
            meta.get().villager().isPresent(), is(false));
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
        assertThat(grassBlock.tint().target(), equalTo(TintSource.GRASS));
        assertThat(grassBlock.tint().constant().isPresent(), is(false));
    }

    @Test
    @DisplayName("Block.tint.constant is populated for known vanilla constant-tinted blocks")
    void blockTintConstantPopulatedFromTheTintTable() {
        Block spruceLeaves = context.findBlock("minecraft:spruce_leaves").orElseThrow();
        assertThat(spruceLeaves.tint().target(), equalTo(TintSource.CONSTANT));
        assertThat(spruceLeaves.tint().constant().isPresent(), is(true));
        assertThat(spruceLeaves.tint().constant().get().getRGB(), equalTo(0xFF619961));
    }

    @Test
    @DisplayName("Untinted blocks (not in the synthetic tint table) keep tint.target=NONE")
    void blockTintTargetDefaultsForUntintedBlocks() {
        Block stone = context.findBlock("minecraft:stone").orElseThrow();
        assertThat(stone.tint().target(), equalTo(TintSource.NONE));
        assertThat(stone.tint().constant().isPresent(), is(false));
    }

    /**
     * Builds a single-variant blockstate apply naming one model, unrotated.
     *
     * @param modelId the full namespaced model id
     * @return the apply
     */
    private static @NotNull ApplyDto apply(@NotNull String modelId) {
        return new ApplyDto(modelId, 0, 0, false, Concurrent.newUnmodifiableList());
    }

    /**
     * Reads an item definition's {@code model} object through the deserializer the loader registers.
     *
     * @param gson the configured Gson
     * @param itemId the item id the definition is for
     * @param model the definition's {@code model} object
     * @return the parsed tree
     */
    private static @NotNull ItemModelTree tree(@NotNull Gson gson, @NotNull String itemId, @NotNull String model) {
        return new ItemModelTree(ResourceId.parse(itemId), gson.fromJson(model, ItemModelNode.class));
    }

}
