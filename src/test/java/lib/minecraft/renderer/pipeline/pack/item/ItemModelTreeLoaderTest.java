package lib.minecraft.renderer.pipeline.pack.item;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.asset.pack.item.ItemModelTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the {@link ItemModelTreeLoader} pack-stack merge and its two derived projections: the
 * block-item inventory-model map, which projects a root plain model reference and nothing else, and
 * the neutral-walk tint capture. Also covers namespace-qualified item ids, the multi-namespace scan,
 * and the namespace-agnostic block-item filter.
 */
@DisplayName("ItemModelTreeLoader pack-stack merge + projections")
class ItemModelTreeLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a vanilla block item projects under its minecraft id -> block model id")
    void namespaceQualifiedBlockItem() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/piston.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/piston_inventory\"}}");

        ConcurrentMap<String, ItemModelTree> trees = ItemModelTreeLoader.load(stack(van, Set.of("minecraft")));
        ConcurrentMap<String, String> defs = ItemModelTreeLoader.deriveBlockItemModels(trees);
        assertThat(defs.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
        assertThat(trees.get("minecraft:piston").root(), instanceOf(ItemModelNode.Model.class));
    }

    @Test
    @DisplayName("scans every namespace and keys item ids by their owning namespace")
    void multiNamespaceItemDef() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/piston.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/piston_inventory\"}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/items/gizmo.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"testns:block/gizmo\"}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ConcurrentMap<String, String> defs = ItemModelTreeLoader.deriveBlockItemModels(ItemModelTreeLoader.load(stack));
        assertThat(defs.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
        assertThat(defs.get("testns:gizmo"), is("testns:block/gizmo"));
    }

    @Test
    @DisplayName("only block-model references project; a namespaced item-model reference is skipped")
    void blockItemFilterIsNamespaceAgnostic() throws IOException {
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/items/blockitem.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"testns:block/thing\"}}");
        write(user.resolve("assets/testns/items/spriteitem.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"testns:item/thing\"}}");

        Path van = tmp.resolve("vanilla");
        Files.createDirectories(van.resolve("assets/minecraft"));

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ConcurrentMap<String, String> defs = ItemModelTreeLoader.deriveBlockItemModels(ItemModelTreeLoader.load(stack));
        assertThat("block-model ref projects", defs.get("testns:blockitem"), is("testns:block/thing"));
        assertThat("item-model ref skipped", defs.containsKey("testns:spriteitem"), is(false));
    }

    @Test
    @DisplayName("a dispatch-rooted item is not a block-item override even when it resolves to a block model")
    void dispatchRootedBlockItemNotProjected() throws IOException {
        // Mirrors vanilla beehive.json: select(block_state) -> fallback block/beehive_empty. Only a
        // root plain model reference projects, so a dispatch root never does however it resolves.
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/beehive.json"),
            "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:block_state\","
                + "\"block_state_property\":\"honey_level\","
                + "\"cases\":[{\"when\":\"5\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/beehive_honey\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/beehive_empty\"}}}");

        ConcurrentMap<String, String> defs = ItemModelTreeLoader.deriveBlockItemModels(ItemModelTreeLoader.load(stack(van, Set.of("minecraft"))));
        assertThat("dispatch-rooted item not projected", defs.containsKey("minecraft:beehive"), is(false));
    }

    @Test
    @DisplayName("tints ride the neutral-walk branch: select(trim_material) fallback carries the dye")
    void tintsFromNeutralBranch() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/leather_boots.json"),
            "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":\"minecraft:iron\",\"model\":{\"type\":\"minecraft:model\","
                + "\"model\":\"minecraft:item/leather_boots_iron_trim\",\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/leather_boots\","
                + "\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}}}");

        Map<String, List<LayerTint>> tints = ItemModelTreeLoader.deriveTints(ItemModelTreeLoader.load(stack(van, Set.of("minecraft"))));
        assertThat(tints.get("minecraft:leather_boots"), contains(instanceOf(LayerTint.Dye.class)));
    }

    private static PackStack stack(Path vanillaRoot, Set<String> namespaces) {
        return PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, namespaces)));
    }

    private static ResourcePack pack(PackId id, Path root, Set<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), namespaces, Set.of(PackCapability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
