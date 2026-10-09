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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Regression pins on {@link ItemModelTreeLoader#load} over a vanilla-native plus legacy-override
 * stack: a legacy {@code overrides} pack applies on top of the native items tree while leaving its
 * <b>tints</b> and a block item's <b>inventory projection</b> intact, and the legacy scan never
 * reaches a modern (format &gt;= 46) pack.
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
