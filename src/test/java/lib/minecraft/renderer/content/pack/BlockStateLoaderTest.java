package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.pack.BlockStateLoader.ApplyDto;
import lib.minecraft.renderer.content.pack.BlockStateLoader.MultipartPart;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the {@link BlockStateLoader} pack-stack merge: the normative first-entry rule on both
 * weighted {@code variants} arrays and multipart {@code apply} arrays, namespace-qualified block ids,
 * model ids read as vanilla reads an identifier, and whole-file variants - multipart replacement,
 * with the lower pack's file standing under a higher pack's file that defines nothing, as under a
 * malformed one, because vanilla's codec refuses both.
 */
@DisplayName("BlockStateLoader pack-stack merge")
class BlockStateLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a weighted variants array takes its first entry (FirstVariantRandomSource parity)")
    void variantWeightedFirstEntry() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/thing.json"),
            "{\"variants\":{\"\":[{\"model\":\"minecraft:block/a\"},{\"model\":\"minecraft:block/b\"}]}}");

        BlockStateLoader.BlockStates result = load(van);
        ApplyDto variant = result.variants().get("minecraft:thing").get("");
        assertThat(variant.model(), is("minecraft:block/a"));
    }

    @Test
    @DisplayName("a weighted variants array is retained whole, in declaration order, beside that first entry")
    void variantWeightedRetainsEveryEntry() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/spin.json"),
            "{\"variants\":{\"\":[{\"model\":\"minecraft:block/s\"},{\"model\":\"minecraft:block/s\",\"y\":90},"
                + "{\"model\":\"minecraft:block/s\",\"y\":180},{\"model\":\"minecraft:block/s\",\"y\":270}]}}");

        ApplyDto variant = load(van).variants().get("minecraft:spin").get("");
        assertThat("first entry still wins the scalar members", variant.y(), is(0));
        assertThat(variant.weighted().size(), is(4));
        assertThat(variant.weighted().stream().map(ApplyDto::y).toList(), is(List.of(0, 90, 180, 270)));
        assertThat("the entries carry no nested list", variant.weighted().getFirst().weighted().isEmpty(), is(true));
    }

    @Test
    @DisplayName("a bare object and a one-entry array both offer no choice, so neither is retained")
    void variantWithoutChoiceRetainsNothing() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/plain.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/p\"}}}");
        write(van.resolve("assets/minecraft/blockstates/lone.json"), "{\"variants\":{\"\":[{\"model\":\"minecraft:block/l\"}]}}");

        BlockStateLoader.BlockStates result = load(van);
        assertThat(result.variants().get("minecraft:plain").get("").weighted().isEmpty(), is(true));
        assertThat(result.variants().get("minecraft:lone").get("").weighted().isEmpty(), is(true));
    }

    @Test
    @DisplayName("a multipart apply array takes its first entry (same normative rule)")
    void multipartApplyFirstEntry() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/wire.json"),
            "{\"multipart\":[{\"apply\":[{\"model\":\"minecraft:block/a\"},{\"model\":\"minecraft:block/b\"}]}]}");

        BlockStateLoader.BlockStates result = load(van);
        ConcurrentList<MultipartPart> multipart = result.multiparts().get("minecraft:wire");
        assertThat(multipart.getFirst().apply().model(), is("minecraft:block/a"));
    }

    @Test
    @DisplayName("a model id written without a namespace reads as minecraft:, on every apply and in any pack namespace")
    void bareModelIdReadsAsMinecraft() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/frame.json"),
            "{\"variants\":{\"map=false\":{\"model\":\"block/frame\"},"
                + "\"map=true\":[{\"model\":\"block/frame_map\"},{\"model\":\"block/frame_map\",\"y\":90}]}}");
        write(van.resolve("assets/minecraft/blockstates/post.json"), "{\"multipart\":[{\"apply\":{\"model\":\"block/post\"}}]}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/blockstates/gadget.json"), "{\"variants\":{\"\":{\"model\":\"block/gadget\"}}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Concurrent.newUnmodifiableSet("minecraft")),
            pack(new PackId("userpack"), user, Concurrent.newUnmodifiableSet("testns"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack);
        assertThat(result.variants().get("minecraft:frame").get("map=false").model(), is("minecraft:block/frame"));
        ApplyDto map = result.variants().get("minecraft:frame").get("map=true");
        assertThat(map.model(), is("minecraft:block/frame_map"));
        assertThat("every weighted entry is read the same way", map.weighted().stream().map(ApplyDto::model).toList(),
            is(List.of("minecraft:block/frame_map", "minecraft:block/frame_map")));
        assertThat(result.multiparts().get("minecraft:post").getFirst().apply().model(), is("minecraft:block/post"));
        assertThat("a bare id names minecraft:, not the namespace whose file names it",
            result.variants().get("testns:gadget").get("").model(), is("minecraft:block/gadget"));
    }

    @Test
    @DisplayName("a model id written with a leading colon reads as minecraft:, on a variant and on a multipart apply")
    void leadingColonModelIdReadsAsMinecraft() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/frame.json"), "{\"variants\":{\"\":{\"model\":\":block/x\"}}}");
        write(van.resolve("assets/minecraft/blockstates/post.json"), "{\"multipart\":[{\"apply\":{\"model\":\":block/x\"}}]}");

        BlockStateLoader.BlockStates result = load(van);
        assertThat(result.variants().get("minecraft:frame").get("").model(), is("minecraft:block/x"));
        assertThat(result.multiparts().get("minecraft:post").getFirst().apply().model(), is("minecraft:block/x"));
    }

    @Test
    @DisplayName("an apply with no model id keeps a blank one rather than naming minecraft: alone")
    void absentModelIdStaysBlank() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/blank.json"), "{\"variants\":{\"\":{\"y\":90}}}");

        assertThat(load(van).variants().get("minecraft:blank").get("").model(), is(""));
    }

    @Test
    @DisplayName("scans every namespace, keying block ids by their owning namespace")
    void multiNamespaceBlockstate() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/stone.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/blockstates/gadget.json"), "{\"variants\":{\"\":{\"model\":\"testns:block/gadget\"}}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Concurrent.newUnmodifiableSet("minecraft")),
            pack(new PackId("userpack"), user, Concurrent.newUnmodifiableSet("testns"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack);
        assertThat(result.variants().containsKey("minecraft:stone"), is(true));
        assertThat(result.variants().get("testns:gadget").get("").model(), is("testns:block/gadget"));
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
            pack(PackId.VANILLA, van, Concurrent.newUnmodifiableSet("minecraft")),
            pack(new PackId("userpack"), user, Concurrent.newUnmodifiableSet("minecraft"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack);
        assertThat("variant form dropped", result.variants().containsKey("minecraft:flip"), is(false));
        assertThat(result.multiparts().get("minecraft:flip").getFirst().apply().model(), is("minecraft:block/new"));
    }

    @Test
    @DisplayName("a higher-pack file with neither variants nor multipart falls back to the lower entry, as vanilla's codec refuses it")
    void higherFileWithNeitherKeyFallsBackToLower() throws IOException {
        BlockStateLoader.BlockStates result = overVanillaFoo("{\"comment\":\"disabled\"}"); // valid JSON, no variants/multipart

        assertThat("the lower variant stands", result.variants().get("minecraft:foo").get("").model(), is("minecraft:block/foo"));
        assertThat(result.multiparts().containsKey("minecraft:foo"), is(false));
    }

    @Test
    @DisplayName("a higher-pack file with an empty variants object falls back to the lower entry")
    void higherFileWithEmptyVariantsFallsBackToLower() throws IOException {
        BlockStateLoader.BlockStates result = overVanillaFoo("{\"variants\":{}}");

        assertThat("the lower variant stands", result.variants().get("minecraft:foo").get("").model(), is("minecraft:block/foo"));
        assertThat(result.multiparts().containsKey("minecraft:foo"), is(false));
    }

    @Test
    @DisplayName("a higher-pack file with an empty multipart list falls back to the lower entry rather than flipping its format")
    void higherFileWithEmptyMultipartFallsBackToLower() throws IOException {
        BlockStateLoader.BlockStates result = overVanillaFoo("{\"multipart\":[]}");

        assertThat("the lower variant stands", result.variants().get("minecraft:foo").get("").model(), is("minecraft:block/foo"));
        assertThat(result.multiparts().containsKey("minecraft:foo"), is(false));
    }

    @Test
    @DisplayName("a higher-pack file whose every entry is dropped falls back to the lower entry, in either format")
    void higherFileWhoseEveryEntryDropsFallsBackToLower() throws IOException {
        BlockStateLoader.BlockStates variants = overVanillaFoo("{\"variants\":{\"\":1,\"lit=true\":\"block/foo_on\"}}");
        assertThat("scalar variant values all drop", variants.variants().get("minecraft:foo").get("").model(), is("minecraft:block/foo"));
        assertThat(variants.multiparts().containsKey("minecraft:foo"), is(false));

        BlockStateLoader.BlockStates parts = overVanillaFoo("{\"multipart\":[3,{\"apply\":[]},{\"when\":{\"lit\":\"true\"}}]}");
        assertThat("apply-less parts all drop", parts.variants().get("minecraft:foo").get("").model(), is("minecraft:block/foo"));
        assertThat(parts.multiparts().containsKey("minecraft:foo"), is(false));
    }

    @Test
    @DisplayName("a file that defines nothing, with no lower pack beneath it, leaves its block in neither map")
    void fileDefiningNothingWithoutALowerPackRegistersNothing() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/neither.json"), "{\"comment\":\"disabled\"}");
        write(van.resolve("assets/minecraft/blockstates/empty_variants.json"), "{\"variants\":{}}");
        write(van.resolve("assets/minecraft/blockstates/empty_multipart.json"), "{\"multipart\":[]}");
        write(van.resolve("assets/minecraft/blockstates/dropped.json"), "{\"variants\":{\"\":1}}");

        BlockStateLoader.BlockStates result = load(van);
        for (String id : List.of("minecraft:neither", "minecraft:empty_variants", "minecraft:empty_multipart", "minecraft:dropped")) {
            assertThat(id + " in variants", result.variants().containsKey(id), is(false));
            assertThat(id + " in multiparts", result.multiparts().containsKey(id), is(false));
        }
    }

    @Test
    @DisplayName("a malformed higher-pack file falls back to the lower entry")
    void malformedHigherFileFallsBackToLower() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/blockstates/bar.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/bar\"}}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/blockstates/bar.json"), "{ this is not valid json"); // malformed

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Concurrent.newUnmodifiableSet("minecraft")),
            pack(new PackId("userpack"), user, Concurrent.newUnmodifiableSet("minecraft"))));

        BlockStateLoader.BlockStates result = BlockStateLoader.load(stack);
        assertThat("malformed higher file falls back to lower", result.variants().get("minecraft:bar").get("").model(), is("minecraft:block/bar"));
    }

    /**
     * Loads a two-pack stack whose lower pack defines {@code minecraft:foo} as one variant naming
     * {@code minecraft:block/foo}, and whose higher pack ships the given text as its own
     * {@code foo.json}.
     *
     * @param higher the higher pack's blockstate file text
     * @return the merged blockstate data
     * @throws IOException if a fixture file cannot be written
     */
    private BlockStateLoader.BlockStates overVanillaFoo(String higher) throws IOException {
        Path van = Files.createTempDirectory(tmp, "vanilla");
        write(van.resolve("assets/minecraft/blockstates/foo.json"), "{\"variants\":{\"\":{\"model\":\"minecraft:block/foo\"}}}");

        Path user = Files.createTempDirectory(tmp, "user");
        write(user.resolve("assets/minecraft/blockstates/foo.json"), higher);

        return BlockStateLoader.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Concurrent.newUnmodifiableSet("minecraft")),
            pack(new PackId("userpack"), user, Concurrent.newUnmodifiableSet("minecraft")))));
    }

    private static BlockStateLoader.BlockStates load(Path vanillaRoot) {
        return BlockStateLoader.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, Concurrent.newUnmodifiableSet("minecraft")))));
    }

    private static ResourcePack pack(PackId id, Path root, ConcurrentSet<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), namespaces,
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
