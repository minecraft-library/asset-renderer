package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.pipeline.pack.Capability;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import lib.minecraft.renderer.pipeline.pack.PackContainer;
import lib.minecraft.renderer.pipeline.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.PackRoot;
import lib.minecraft.renderer.pipeline.pack.PackStack;
import lib.minecraft.renderer.pipeline.pack.ResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Exercises the {@link ItemDefinitionLoader} pack-stack merge: namespace-qualified item ids, the
 * multi-namespace scan, and the namespace-agnostic block-item filter (only {@code <ns>:block/...}
 * references index as block items).
 */
@DisplayName("ItemDefinitionLoader pack-stack merge")
class ItemDefinitionLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a vanilla block item indexes under its minecraft id -> block model id")
    void namespaceQualifiedBlockItem() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/items/piston.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/piston_inventory\"}}");

        ConcurrentMap<String, String> defs = ItemDefinitionLoader.load(stack(van, Set.of("minecraft")));
        assertThat(defs.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
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

        ConcurrentMap<String, String> defs = ItemDefinitionLoader.load(stack);
        assertThat(defs.get("minecraft:piston"), is("minecraft:block/piston_inventory"));
        assertThat(defs.get("testns:gizmo"), is("testns:block/gizmo"));
    }

    @Test
    @DisplayName("only block-model references index; a namespaced item-model reference is skipped")
    void blockItemFilterIsNamespaceAgnostic() throws IOException {
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/items/blockitem.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"testns:block/thing\"}}");
        write(user.resolve("assets/testns/items/spriteitem.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"testns:item/thing\"}}");

        // A minimal vanilla base so PackStack.of is satisfied; it ships no items.
        Path van = tmp.resolve("vanilla");
        Files.createDirectories(van.resolve("assets/minecraft"));

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ConcurrentMap<String, String> defs = ItemDefinitionLoader.load(stack);
        assertThat("block-model ref indexes", defs.get("testns:blockitem"), is("testns:block/thing"));
        assertThat("item-model ref skipped", defs.containsKey("testns:spriteitem"), is(false));
    }

    private static PackStack stack(Path vanillaRoot, Set<String> namespaces) {
        return PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, namespaces)));
    }

    private static ResourcePack pack(PackId id, Path root, Set<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), namespaces, Set.of(Capability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
