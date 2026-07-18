package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.pack.Capability;
import lib.minecraft.renderer.asset.MCMeta;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.Capability;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
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
 * Exercises the {@link BlockStateLoader} pack-stack merge: the normative first-entry rule on both
 * weighted {@code variants} arrays and multipart {@code apply} arrays, namespace-qualified block ids,
 * whole-file variants&harr;multipart replacement, and {@code filter.block} erasure.
 */
@DisplayName("BlockStateLoader pack-stack merge")
class BlockStateLoaderTest {

    private static final ConcurrentMap<String, ModelData> NO_MODELS = Concurrent.newMap();

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a weighted variants array takes its first entry (FirstVariantRandomSource parity)")
    void variantWeightedFirstEntry() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/thing.json"),
            "{\"variants\":{\"\":[{\"model\":\"minecraft:block/a\"},{\"model\":\"minecraft:block/b\"}]}}");

        BlockStateLoader.BlockStates result = load(van);
        Block.Variant variant = result.variants().get("minecraft:thing").get("");
        assertThat(variant.modelId(), is("minecraft:block/a"));
    }

    @Test
    @DisplayName("a multipart apply array takes its first entry (same normative rule)")
    void multipartApplyFirstEntry() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/wire.json"),
            "{\"multipart\":[{\"apply\":[{\"model\":\"minecraft:block/a\"},{\"model\":\"minecraft:block/b\"}]}]}");

        BlockStateLoader.BlockStates result = load(van);
        Block.Multipart multipart = result.multiparts().get("minecraft:wire");
        assertThat(multipart.parts().getFirst().apply().modelId(), is("minecraft:block/a"));
    }

    @Test
    @DisplayName("scans every namespace, keying block ids by their owning namespace")
    void multiNamespaceBlockstate() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/stone.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/blockstates/gadget.json"), "{\"variants\":{\"\":{\"model\":\"testns:block/gadget\"}}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack, NO_MODELS);
        assertThat(result.variants().containsKey("minecraft:stone"), is(true));
        assertThat(result.variants().get("testns:gadget").get("").modelId(), is("testns:block/gadget"));
    }

    @Test
    @DisplayName("a higher pack's blockstate fully replaces a lower's, flipping variants to multipart")
    void wholeFileReplaceFlipsFormat() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/flip.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/old\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/flip.json"),
            "{\"multipart\":[{\"apply\":{\"model\":\"minecraft:block/new\"}}]}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack, NO_MODELS);
        assertThat("variant form dropped", result.variants().containsKey("minecraft:flip"), is(false));
        assertThat(result.multiparts().get("minecraft:flip").parts().getFirst().apply().modelId(), is("minecraft:block/new"));
    }

    @Test
    @DisplayName("filter.block erases matching block ids from lower packs before the higher merges")
    void filterBlockErasesBlockstates() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/keepme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/keepme\"}}}");
        write(van.resolve("assets/minecraft/blockstates/hideme.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/hideme\"}}}");

        Path user = tmp.resolve("user");
        Files.createDirectories(user.resolve("assets/minecraft"));
        MCMeta filtering = MCMeta.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"hideme\"}]}}",
            new ResourceId("userpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("userpack"), new PackContainer.Directory(user),
            filtering, Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Set.of("minecraft"), Set.of(Capability.VANILLA_CORE));

        PackStack stack = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft")), filterPack));
        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack, NO_MODELS);

        assertThat(result.variants().containsKey("minecraft:keepme"), is(true));
        assertThat("hidden by filter.block", result.variants().containsKey("minecraft:hideme"), is(false));
    }

    @Test
    @DisplayName("a valid-but-empty higher-pack file shadows the lower entry (topmost-file-wins)")
    void validButEmptyHigherFileShadowsLower() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/foo.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/foo\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/foo.json"), "{\"comment\":\"disabled\"}"); // valid JSON, no variants/multipart

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack, NO_MODELS);
        assertThat("higher empty file shadows the lower variant", result.variants().containsKey("minecraft:foo"), is(false));
        assertThat(result.multiparts().containsKey("minecraft:foo"), is(false));
    }

    @Test
    @DisplayName("a malformed higher-pack file falls back to the lower entry (not a shadow)")
    void malformedHigherFileFallsBackToLower() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/bar.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/bar\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/bar.json"), "{ this is not valid json"); // malformed

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack, NO_MODELS);
        assertThat("malformed higher file falls back to lower", result.variants().get("minecraft:bar").get("").modelId(), is("minecraft:block/bar"));
    }

    private static BlockStateLoader.BlockStates load(Path vanillaRoot) {
        return BlockStateLoader.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, Set.of("minecraft")))), NO_MODELS);
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
