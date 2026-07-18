package lib.minecraft.renderer.pipeline.pack.item;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import lib.minecraft.renderer.option.ItemModelContext;
import lib.minecraft.renderer.asset.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Regression coverage: a legacy {@code overrides} pack must NOT clobber the native items-tree's tints
 * or a block-item's inventory projection, and the legacy scan must not touch a modern
 * (format &gt;= 46) pack. Exercises {@link ItemModelTreeLoader#load} end-to-end over a vanilla-native
 * + legacy-override stack.
 */
@DisplayName("Phase 7 legacy override merge preserves native tree tints + block-item projection")
class Phase7LegacyMergeTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a legacy override on a tinted item keeps the native dye tint AND applies the override frame")
    void legacyOverridePreservesNativeTint() throws IOException {
        // Vanilla native tree: select(trim_material) whose fallback dye-tints the default leather helmet.
        Path vanilla = tmp.resolve("vanilla");
        write(vanilla.resolve("assets/minecraft/items/leather_helmet.json"),
            "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\",\"cases\":[],"
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
        assertThat(tree.resolve(ItemModelContext.gui()).modelId().orElse("<none>"), is("minecraft:item/leather_helmet"));
        ItemModelContext cmd1 = new ItemModelContext("gui", false, false, null, null, 0f, 0f, 1f, null);
        assertThat(tree.resolve(cmd1).modelId().orElse("<none>"), is("minecraft:item/custom_helmet"));
    }

    @Test
    @DisplayName("a legacy override on a block item is dropped, preserving the inventory-model projection")
    void legacyOverridePreservesBlockItemProjection() throws IOException {
        Path vanilla = tmp.resolve("vanilla");
        write(vanilla.resolve("assets/minecraft/items/piston.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/piston_inventory\"}}");

        Path legacy = tmp.resolve("legacypack");
        write(legacy.resolve("assets/minecraft/models/item/piston.json"),
            "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/fancy_piston\"}]}");

        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(legacyStack(vanilla, legacy));
        var blockItems = ItemModelTreeLoader.deriveBlockItemModels(trees);
        assertThat("block-item inventory projection preserved (legacy override skipped)",
            blockItems.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
        assertThat("piston tree stays the plain native block model",
            trees.get("minecraft:piston").root(), instanceOf(ItemModelNode.Model.class));
    }

    @Test
    @DisplayName("a modern pack's models/item overrides are NOT scanned (only pre-46 packs map)")
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
        return MCMeta.parse("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"x\"}}",
            new ResourceId("pack", "pack"));
    }

    private static ResourcePack pack(PackId id, Path root, MCMeta meta) {
        return new ResourcePack(id, new PackContainer.Directory(root), meta,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
