package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Pins for the {@link ResolvedModels} attributed multi-namespace merge: namespace-qualified model
 * ids, cross-pack raw-later-wins-then-inherit, the per-slot display walk with each file's hand fill,
 * {@link ModelTexture} object-form retention, the pack-attributed {@code rendersNothing} diagnostic,
 * and {@code filter.block} erasure.
 */
@DisplayName("ResolvedModels attributed multi-namespace merge")
class ResolvedModelsTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("scans every namespace, keying model ids by their owning namespace")
    void multiNamespaceModelScan() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/item/apple.json"), "{\"textures\":{\"layer0\":\"minecraft:item/apple\"}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/item/gizmo.json"), "{\"textures\":{\"layer0\":\"testns:item/gizmo\"}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ConcurrentMap<String, ModelData> items = ResolvedModels.load(stack).items();
        assertThat(items.containsKey("minecraft:item/apple"), is(true));
        assertThat(items.containsKey("testns:item/gizmo"), is(true));
        assertThat(items.get("testns:item/gizmo").getTextures().get("layer0").sprite(), is("testns:item/gizmo"));
    }

    @Test
    @DisplayName("a higher pack's child inherits a parent that lives only in the base pack")
    void crossPackParentInheritance() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/parent_tpl.json"),
            "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}]}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/block/child.json"),
            "{\"parent\":\"minecraft:block/parent_tpl\",\"textures\":{\"all\":\"testns:block/x\"}}");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ModelData child = ResolvedModels.load(stack).blocks().get("testns:block/child");
        assertThat("inherited the vanilla-only parent's elements", child.getElements().isEmpty(), is(false));
        assertThat(child.getTextures().get("all").sprite(), is("testns:block/x"));
    }

    @Test
    @DisplayName("a display resolves per slot: a slot the child leaves out comes from its parent, one it declares is its own")
    void displayInheritsPerSlot() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/block.json"),
            "{\"display\":{\"gui\":{\"rotation\":[30,225,0]},\"thirdperson_righthand\":{\"rotation\":[75,45,0]}}}");
        write(van.resolve("assets/minecraft/models/block/stairs.json"),
            "{\"parent\":\"minecraft:block/block\",\"display\":{\"gui\":{\"rotation\":[30,135,0]}}}");
        write(van.resolve("assets/minecraft/models/block/bare.json"),
            "{\"parent\":\"minecraft:block/block\",\"display\":{}}");

        ConcurrentMap<String, ModelData> blocks = blocks(van);
        ConcurrentMap<String, ModelTransform> stairs = blocks.get("minecraft:block/stairs").getDisplay();
        assertThat("the child's own gui wins", stairs.get("gui").getRotation(), is(new EulerRotation(30f, 135f, 0f)));
        assertThat("the parent's held slot is inherited",
            stairs.get("thirdperson_righthand").getRotation(), is(new EulerRotation(75f, 45f, 0f)));
        assertThat("an empty display declares no slot, so every slot is the parent's",
            blocks.get("minecraft:block/bare").getDisplay().get("gui").getRotation(), is(new EulerRotation(30f, 225f, 0f)));
    }

    @Test
    @DisplayName("a file's left hand takes its own right hand before an ancestor's left hand is looked at")
    void aFilesHandFillShadowsAnAncestor() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/base.json"),
            "{\"display\":{\"thirdperson_lefthand\":{\"rotation\":[0,0,90]},\"firstperson_righthand\":{\"rotation\":[0,45,0]}}}");
        write(van.resolve("assets/minecraft/models/block/held.json"),
            "{\"parent\":\"minecraft:block/base\",\"display\":{\"thirdperson_righthand\":{\"rotation\":[75,45,0]}}}");

        ConcurrentMap<String, ModelData> blocks = blocks(van);
        ConcurrentMap<String, ModelTransform> held = blocks.get("minecraft:block/held").getDisplay();
        assertThat("the child's left hand is its own right hand, not the parent's left hand",
            held.get("thirdperson_lefthand").getRotation(), is(new EulerRotation(75f, 45f, 0f)));
        assertThat("a hand pair the child leaves out whole is filled in the parent and inherited",
            held.get("firstperson_lefthand").getRotation(), is(new EulerRotation(0f, 45f, 0f)));
        assertThat("a parent-less file fills its own hands too",
            blocks.get("minecraft:block/base").getDisplay().get("firstperson_lefthand").getRotation(),
            is(new EulerRotation(0f, 45f, 0f)));
    }

    @Test
    @DisplayName("a model with no display anywhere up its chain has none")
    void noDisplayPassesThrough() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/plain.json"), "{\"textures\":{\"all\":\"minecraft:block/stone\"}}");
        write(van.resolve("assets/minecraft/models/block/child.json"), "{\"parent\":\"minecraft:block/plain\"}");

        assertThat(blocks(van).get("minecraft:block/child").getDisplay().isEmpty(), is(true));
    }

    @Test
    @DisplayName("object-form {sprite, force_translucent} parses to a ModelTexture carrying the flag")
    void objectFormTextureRetained() throws IOException {
        Path van = tmp.resolve("vanilla");
        // Parent-less object-form model: proves the parse runs for every model, not only parented ones.
        write(van.resolve("assets/minecraft/models/block/glass.json"),
            "{\"textures\":{\"all\":{\"sprite\":\"minecraft:block/glass\",\"force_translucent\":true},"
                + "\"plain\":\"minecraft:block/stone\"}}");

        PackStack stack = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft"))));
        ModelData glass = ResolvedModels.load(stack).blocks().get("minecraft:block/glass");

        // Object form parses to a ModelTexture carrying the flag; string form to (sprite, false).
        assertThat(glass.getTextures().get("all"), is(new ModelTexture("minecraft:block/glass", true)));
        assertThat(glass.getTextures().get("plain"), is(new ModelTexture("minecraft:block/stone", false)));
    }

    /**
     * Asserts the diagnostic names the pack a nothing-rendering model came from, reading it off
     * captured {@code System.err} because {@link ResolvedModels} prints there rather than to a
     * {@code Diagnostics} sink; the capture stops working the day it takes one.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("a non-vanilla winner that renders nothing is diagnosed by pack name")
    void attributionNamesWinningPack() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/stone.json"),
            "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}],"
                + "\"textures\":{\"all\":\"minecraft:block/stone\"}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/block/hollow.json"), "{}"); // no elements, no textures -> renders nothing

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ResolvedModels.load(stack).blocks();
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output, containsString("userpack"));
        assertThat(output, containsString("testns:block/hollow"));
        // A vanilla-origin model never triggers the diagnostic, even were it empty.
        assertThat(output, not(containsString("minecraft:block/stone")));
    }

    @Test
    @DisplayName("filter.block erases matching model ids from lower packs before the higher merges")
    void filterBlockErasesModels() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/keepme.json"), "{\"textures\":{\"all\":\"minecraft:block/keepme\"}}");
        write(van.resolve("assets/minecraft/models/block/hideme.json"), "{\"textures\":{\"all\":\"minecraft:block/hideme\"}}");

        Path user = tmp.resolve("user");
        Files.createDirectories(user.resolve("assets/testns"));
        MCMeta filtering = MCMetaParser.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"block/hideme\"}]}}",
            new ResourceId("userpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("userpack"), new PackContainer.Directory(user),
            filtering, Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableTreeSet("testns"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));

        PackStack stack = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft")), filterPack));
        ConcurrentMap<String, ModelData> blocks = ResolvedModels.load(stack).blocks();

        assertThat(blocks.containsKey("minecraft:block/keepme"), is(true));
        assertThat("hidden by filter.block", blocks.containsKey("minecraft:block/hideme"), is(false));
    }

    @Test
    @DisplayName("a malformed force_translucent flag degrades to false instead of crashing")
    void objectFormMalformedFlagIsSafe() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/weird.json"),
            "{\"textures\":{"
                + "\"a\":{\"sprite\":\"minecraft:block/a\",\"force_translucent\":\"yes\"},"
                + "\"b\":{\"sprite\":\"minecraft:block/b\",\"force_translucent\":null},"
                + "\"c\":{\"sprite\":\"minecraft:block/c\",\"force_translucent\":true}}}");

        PackStack stack = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft"))));
        ModelData weird = ResolvedModels.load(stack).blocks().get("minecraft:block/weird"); // must not throw

        assertThat("string flag -> false", weird.getTextures().get("a"), is(new ModelTexture("minecraft:block/a", false)));
        assertThat("null flag -> false", weird.getTextures().get("b"), is(new ModelTexture("minecraft:block/b", false)));
        assertThat("boolean true -> true", weird.getTextures().get("c"), is(new ModelTexture("minecraft:block/c", true)));
    }

    @Test
    @DisplayName("filter.block matches as an unanchored substring, mirroring vanilla asPredicate/find")
    void filterBlockSubstringMatchesVanilla() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/keepme.json"), "{\"textures\":{\"all\":\"minecraft:block/keepme\"}}");
        write(van.resolve("assets/minecraft/models/block/target.json"), "{\"textures\":{\"all\":\"minecraft:block/target\"}}");

        Path user = tmp.resolve("user");
        Files.createDirectories(user.resolve("assets/testns"));
        // "target" is a substring of the model name "block/target"; an anchored full match would miss it.
        MCMeta filtering = MCMetaParser.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"target\"}]}}",
            new ResourceId("userpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("userpack"), new PackContainer.Directory(user),
            filtering, Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableTreeSet("testns"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));

        PackStack stack = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft")), filterPack));
        ConcurrentMap<String, ModelData> blocks = ResolvedModels.load(stack).blocks();

        assertThat(blocks.containsKey("minecraft:block/keepme"), is(true));
        assertThat("substring pattern hides block/target", blocks.containsKey("minecraft:block/target"), is(false));
    }

    private static ResourcePack pack(PackId id, Path root, Set<String> namespaces) {
        return new ResourcePack(id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableTreeSet(namespaces),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
    }

    /**
     * Resolves the block models of a vanilla-only stack over one fixture root.
     *
     * @param van the fixture pack root
     * @return the resolved block models
     */
    private static ConcurrentMap<String, ModelData> blocks(Path van) {
        return ResolvedModels.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft"))))).blocks();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
