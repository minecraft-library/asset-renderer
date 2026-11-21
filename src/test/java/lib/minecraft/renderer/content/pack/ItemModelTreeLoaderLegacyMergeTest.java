package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression pins on {@link ItemModelTreeLoader#load} over a vanilla-native plus legacy-override
 * stack: a legacy {@code overrides} pack applies on top of the native items tree while leaving its
 * <b>tints</b> and a block item's <b>inventory projection</b> intact, the legacy scan never
 * reaches a modern (format &gt;= 46) pack, and the bake refusal searches a mapped definition once per
 * node however many gate groups share its fallback.
 */
@DisplayName("legacy override merge preserves the native tree's tints and block-item projection")
class ItemModelTreeLoaderLegacyMergeTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a legacy override on a tinted item keeps the native dye tint AND applies the override frame")
    void legacyOverridePreservesNativeTint() throws IOException {
        // Vanilla native tree: select(trim_material) whose fallback dye-tints the default leather helmet.
        // It carries one trim case, because a select with none fails to parse, as vanilla's does.
        Path vanilla = tmp.resolve("vanilla");
        write(vanilla.resolve("assets/minecraft/items/leather_helmet.json"),
            "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":\"minecraft:iron\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/leather_helmet_iron_trim\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/leather_helmet\","
                + "\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}}}");

        // Legacy pack (pack_format 15) overrides the same item with a custom_model_data CIT frame.
        Path legacy = tmp.resolve("legacypack");
        write(legacy.resolve("assets/minecraft/models/item/leather_helmet.json"),
            "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/leather_helmet\"},"
                + "\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/custom_helmet\"}]}");

        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(legacyStack(vanilla, legacy));

        // The dye tint survives (deriveTints walks the mapped tree's fallback into the native branch).
        var tints = ItemModelTreeLoader.deriveTints(trees);
        assertThat("native dye tint preserved under the legacy override",
            tints.get("minecraft:leather_helmet"), contains(instanceOf(LayerTint.Dye.class)));

        // Neutral -> native default; cmd=1 -> the override frame.
        ItemModelTree tree = trees.get("minecraft:leather_helmet");
        assertThat(ItemModelContext.gui().resolve(tree).modelId().orElse("<none>"), is("minecraft:item/leather_helmet"));
        ItemModelContext cmd1 = new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.of(1f), Optional.empty());
        assertThat(cmd1.resolve(tree).modelId().orElse("<none>"), is("minecraft:item/custom_helmet"));
    }

    @Test
    @DisplayName("a legacy override on a block item applies, and the neutral walk keeps the inventory-model projection")
    void legacyOverridePreservesBlockItemProjection() throws IOException {
        Path vanilla = tmp.resolve("vanilla");
        write(vanilla.resolve("assets/minecraft/items/piston.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/piston_inventory\"}}");

        Path legacy = tmp.resolve("legacypack");
        write(legacy.resolve("assets/minecraft/models/item/piston.json"),
            "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/fancy_piston\"}]}");

        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(legacyStack(vanilla, legacy));
        ItemModelTree tree = trees.get("minecraft:piston");
        assertThat("the override wraps the native tree", tree.root(), instanceOf(ItemModelNode.RangeDispatch.class));

        // The neutral walk reads custom_model_data 0, under the override's threshold of 1, so it
        // falls back to the native block model and the projection holds through the walk alone.
        var blockItems = ItemModelTreeLoader.deriveBlockItemModels(trees);
        assertThat("block-item inventory projection preserved through the override's fallback",
            blockItems.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
        ItemModelContext cmd1 = new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.of(1f), Optional.empty());
        assertThat("cmd=1 selects the override frame",
            cmd1.resolve(tree).modelId().orElse("<none>"), is("minecraft:item/fancy_piston"));
    }

    @Test
    @DisplayName("a modern pack's models/item overrides are not scanned, only a pre-46 pack's mapping")
    void modernPackOverridesNotScanned() throws IOException {
        Path vanilla = tmp.resolve("vanilla");
        Files.createDirectories(vanilla.resolve("assets/minecraft"));

        Path modern = tmp.resolve("modernpack");
        write(modern.resolve("assets/minecraft/models/item/gadget.json"),
            "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/gadget_cmd1\"}]}");

        List<ResourcePack> packs = new ArrayList<>();
        packs.add(pack(PackId.VANILLA, vanilla, MCMeta.EMPTY));
        packs.add(pack(new PackId("modernpack"), modern, metaFormat(60)));
        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(PackStack.of(Concurrent.adoptList(packs).toUnmodifiable()));

        assertThat("a format-60 pack is not legacy-scanned", trees.containsKey("minecraft:gadget"), is(false));
    }

    /**
     * The mapper hands each gate group the tree built so far twice over - as the group's own fallback
     * and as its gate's other branch - so a file of many groups is a graph whose nodes each stand
     * under several parents. The bake refusal searches every leaf of it, and has to do so once per
     * node rather than once per path to it.
     */
    @Test
    @DisplayName("a legacy file of many gate groups is searched for unbakeable leaves once per node")
    void manyGateGroupsAreSearchedOncePerNode() throws IOException {
        Path vanilla = tmp.resolve("vanilla");
        Files.createDirectories(vanilla.resolve("assets/minecraft"));

        // Every way of leaving out, clearing or setting three gates but the empty one is a group of its own.
        String[] gates = {"pulling", "damaged", "cast"};
        List<String> overrides = new ArrayList<>();
        for (int code = 1; code <= 20; code++) {
            StringBuilder predicate = new StringBuilder();
            for (int gate = 0, rest = code; gate < gates.length; gate++, rest /= 3)
                if (rest % 3 != 0) predicate.append('"').append(gates[gate]).append("\":").append(rest % 3 - 1).append(',');
            overrides.add("{\"predicate\":{" + predicate + "\"custom_model_data\":" + code + "},\"model\":\"item/gadget_" + code + "\"}");
        }

        Path legacy = tmp.resolve("legacypack");
        write(legacy.resolve("assets/minecraft/models/item/gadget.json"),
            "{\"parent\":\"item/generated\",\"overrides\":[" + String.join(",", overrides) + "]}");

        PackStack bare = legacyStack(vanilla, legacy);
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(stack);

        Map<String, ItemModelTree> baked = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> ItemModelTreeLoader.refuseUnbakeable(trees, ResolvedModels.load(stack), SpriteAtlas.load(stack, SpriteAtlas.ITEMS)));

        assertThat(baked.get("minecraft:gadget").isRejected(), is(false));
        assertThat("and a stack matching no override still walks through every group to the item's own model",
            ItemModelContext.gui().resolve(baked.get("minecraft:gadget")).modelId().orElse("<none>"), is("minecraft:item/gadget"));
    }

    private PackStack legacyStack(Path vanilla, Path legacy) {
        List<ResourcePack> packs = new ArrayList<>();
        packs.add(pack(PackId.VANILLA, vanilla, MCMeta.EMPTY));
        packs.add(pack(new PackId("legacypack"), legacy, metaFormat(15)));
        return PackStack.of(Concurrent.adoptList(packs).toUnmodifiable());
    }

    private static MCMeta metaFormat(int format) {
        return MCMetaParser.parse("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"x\"}}",
            new ResourceId("pack", "pack"));
    }

    private static ResourcePack pack(PackId id, Path root, MCMeta meta) {
        return new ResourcePack(id, new PackContainer.Directory(root), meta,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
