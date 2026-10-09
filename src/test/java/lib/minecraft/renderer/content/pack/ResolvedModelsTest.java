package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.container.PackContainer;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins for the {@link ResolvedModels} attributed multi-namespace merge: namespace-qualified model
 * ids, cross-pack raw-later-wins-then-inherit, the per-slot display walk with each file's hand fill,
 * the {@code gui_light} walk and its refusal of a word vanilla does not read, {@link ModelTexture}
 * object-form retention, the pack-attributed {@code rendersNothing} diagnostic,
 * and {@code filter.block} erasure. The whole {@code models/} tree is read: a model outside
 * {@code block/} and {@code item/} is found and parents others while the two indexed sets stay as their
 * subtrees give them, an absent parent resolves to the missing model, and a cyclic, malformed or
 * typed-read-rejected model leaves its id absent.
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

    /**
     * Pins that block and item models resolve as one namespace, as vanilla lists every model file
     * into one map: an item model naming a block parent inherits its elements, textures and display
     * slots, and keeps its own slots on top.
     *
     * @throws IOException if a fixture pack cannot be written
     */
    @Test
    @DisplayName("an item model resolves its block parent's elements, textures and display")
    void anItemModelResolvesItsBlockParent() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/y.json"),
            "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}],"
                + "\"textures\":{\"all\":\"minecraft:block/y\"},\"display\":{\"ground\":{\"rotation\":[0,90,0]}}}");
        write(van.resolve("assets/minecraft/models/item/x.json"),
            "{\"parent\":\"minecraft:block/y\",\"display\":{\"thirdperson_righthand\":{\"rotation\":[0,0,0],\"translation\":[0,1,0]}}}");

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")))));
        ModelData x = models.items().get("minecraft:item/x");
        assertThat("the block parent's elements", x.getElements().size(), is(1));
        assertThat("the block parent's texture", x.getTextures().get("all").sprite(), is("minecraft:block/y"));
        assertThat("the block parent's ground slot",
            x.getDisplay().get("ground").getRotation(), is(new EulerRotation(0f, 90f, 0f)));
        assertThat("its own held slot", x.getDisplay().containsKey("thirdperson_righthand"), is(true));
        assertThat("its left hand filled from its own right hand",
            x.getDisplay().containsKey("thirdperson_lefthand"), is(true));
        assertThat("the block model keeps its own slots alone",
            models.blocks().get("minecraft:block/y").getDisplay().keySet(), is(Set.of("ground")));
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
    @DisplayName("the gui_light is the nearest file's to declare one, and a chain declaring none lights side")
    void guiLightTakesTheNearestDeclaration() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/block.json"), "{\"gui_light\":\"side\"}");
        write(van.resolve("assets/minecraft/models/block/flat_lit.json"),
            "{\"parent\":\"minecraft:block/block\",\"gui_light\":\"front\"}");
        write(van.resolve("assets/minecraft/models/block/inherits.json"), "{\"parent\":\"minecraft:block/flat_lit\"}");
        write(van.resolve("assets/minecraft/models/block/plain.json"), "{\"textures\":{\"all\":\"minecraft:block/stone\"}}");

        ConcurrentMap<String, ModelData> blocks = blocks(van);
        assertThat("a file's own light wins", blocks.get("minecraft:block/flat_lit").getGuiLight(), is(ModelData.GuiLight.FRONT));
        assertThat("a child inherits the nearest", blocks.get("minecraft:block/inherits").getGuiLight(), is(ModelData.GuiLight.FRONT));
        assertThat("the parent keeps its own", blocks.get("minecraft:block/block").getGuiLight(), is(ModelData.GuiLight.SIDE));
        assertThat("no light anywhere is side", blocks.get("minecraft:block/plain").getGuiLight(), is(ModelData.GuiLight.SIDE));
    }

    @Test
    @DisplayName("builtin/generated lights front where nothing below it names a light, and the missing model names none")
    void generatedLightsFront() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/item/generated.json"), "{\"parent\":\"builtin/generated\"}");
        write(van.resolve("assets/minecraft/models/item/flat.json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/flat\"}}");
        write(van.resolve("assets/minecraft/models/item/side_sprite.json"),
            "{\"parent\":\"builtin/generated\",\"gui_light\":\"side\",\"textures\":{\"layer0\":\"minecraft:item/side\"}}");
        write(van.resolve("assets/minecraft/models/item/orphan.json"), "{\"parent\":\"minecraft:item/nowhere\"}");

        ResolvedModels[] models = new ResolvedModels[1];
        stderrOf(() -> models[0] = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft"))))));
        ConcurrentMap<String, ModelData> items = models[0].items();
        assertThat(items.get("minecraft:item/generated").getGuiLight(), is(ModelData.GuiLight.FRONT));
        assertThat(items.get("minecraft:item/flat").getGuiLight(), is(ModelData.GuiLight.FRONT));
        assertThat("a light declared below generated wins", items.get("minecraft:item/side_sprite").getGuiLight(), is(ModelData.GuiLight.SIDE));
        assertThat("the missing model a lost parent resolves to names none",
            items.get("minecraft:item/orphan").getGuiLight(), is(ModelData.GuiLight.SIDE));
    }

    @Test
    @DisplayName("a light other than front or side fails every model whose chain reaches it, a child naming its own included")
    void aMisspelledLightFailsTheChain() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/top_lit.json"), "{\"gui_light\":\"top\"}");
        write(van.resolve("assets/minecraft/models/block/shouted.json"), "{\"gui_light\":\"FRONT\"}");
        write(van.resolve("assets/minecraft/models/block/numbered.json"), "{\"gui_light\":5}");
        write(van.resolve("assets/minecraft/models/block/top_child.json"),
            "{\"parent\":\"minecraft:block/top_lit\",\"gui_light\":\"side\"}");
        write(van.resolve("assets/minecraft/models/block/good.json"), "{\"gui_light\":\"front\"}");

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft"))))));

        assertThat(models[0].blocks().keySet(), is(Set.of("minecraft:block/good")));
        assertThat(output, containsString("Failed to load model 'minecraft:block/top_lit'"));
        assertThat(output, containsString("Invalid gui_light 'top'"));
        assertThat("vanilla matches the word exactly", output, containsString("Invalid gui_light 'FRONT'"));
        assertThat(output, containsString("Invalid gui_light '5'"));
        assertThat(output, containsString("Failed to load model 'minecraft:block/top_child'"));
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

    @Test
    @DisplayName("a model outside block/ and item/ is found under its whole path, and neither indexed set holds it")
    void anOutsideModelIsFoundAndNotIndexed() throws IOException {
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/custom/x.json"), "{\"textures\":{\"layer0\":\"testns:item/x\"}}");
        write(user.resolve("assets/testns/models/top.json"), "{\"textures\":{\"layer0\":\"testns:item/top\"}}");

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, tmp.resolve("vanilla"), Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns")))));

        assertThat(models.find("testns:custom/x").orElseThrow().getTextures().get("layer0").sprite(), is("testns:item/x"));
        assertThat("a direct child of models/ keys by its bare stem",
            models.find("testns:top").orElseThrow().getTextures().get("layer0").sprite(), is("testns:item/top"));
        assertThat(models.blocks().containsKey("testns:custom/x"), is(false));
        assertThat(models.items().containsKey("testns:custom/x"), is(false));
        assertThat(models.items().containsKey("testns:top"), is(false));
    }

    @Test
    @DisplayName("the lookup answers a model that draws present, a loaded one declaring nothing to draw as an item empty, and an unloaded id absent")
    void theLookupTellsAModelDrawingNothingFromAnUnloadedOne() throws IOException {
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/item/flat.json"), "{\"textures\":{\"layer0\":\"testns:item/flat\"}}");
        write(user.resolve("assets/testns/models/block/cube.json"),
            "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"north\":{\"texture\":\"#side\"}}}]}");
        write(user.resolve("assets/testns/models/item/particle.json"), "{\"textures\":{\"particle\":\"testns:item/flat\"}}");
        write(user.resolve("assets/testns/models/item/bare.json"), "{}");
        write(user.resolve("assets/testns/models/block/faceless.json"), "{\"textures\":{\"all\":\"testns:block/x\"}}");

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, tmp.resolve("vanilla"), Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns")))));

        // A layer, and an element face naming a texture - one whose reference resolves nowhere still
        // declares something, which vanilla draws as its missing texture.
        assertThat(models.find("testns:item/flat").getState(), is(Possible.State.PRESENT));
        assertThat(models.find("testns:block/cube").getState(), is(Possible.State.PRESENT));

        // A particle alone, an empty body and a block binding with no element to draw it on.
        for (String blank : List.of("testns:item/particle", "testns:item/bare", "testns:block/faceless")) {
            assertThat(blank, models.find(blank).getState(), is(Possible.State.EMPTY));
            assertThat(blank + " is loaded", models.all().containsKey(blank), is(true));
        }

        assertThat(models.find("testns:item/unshipped").getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("a .geo.json loads as an empty model under its .geo stem, which the lookup answers empty, with no blank-model report")
    void aGeoFileLoadsAsAnEmptyModel() throws IOException {
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/entity/thing.geo.json"),
            "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.thing\"},\"bones\":[]}]}");
        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, tmp.resolve("vanilla"), Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(stack));

        // Loaded, and declaring nothing to draw: the lookup answers it empty rather than absent, and the
        // model itself is the one the whole-tree map holds.
        assertThat(models[0].find("testns:entity/thing.geo").getState(), is(Possible.State.EMPTY));
        ModelData geo = models[0].all().get("testns:entity/thing.geo");
        assertThat(geo.getElements().isEmpty(), is(true));
        assertThat(geo.getTextures().isEmpty(), is(true));
        assertThat(geo.getDisplay().isEmpty(), is(true));
        assertThat("nothing is reported for it", output, not(containsString("thing.geo")));
    }

    @Test
    @DisplayName("the block and item sets of a pack with outside files are the sets its two subtrees alone give, in the same order")
    void outsideFilesLeaveTheIndexedSetsAsTheyAre() throws IOException {
        String[] indexed = {
            "block/a", "block/b", "block/nested/c", "block/z", "item/d", "item/nested/d", "item/e", "item/f"
        };
        Path whole = tmp.resolve("whole");
        Path subtrees = tmp.resolve("subtrees");
        for (String path : indexed) {
            String json = "{\"textures\":{\"all\":\"minecraft:" + path + "\",\"layer0\":\"minecraft:" + path + "\"}}";
            write(whole.resolve("assets/minecraft/models/" + path + ".json"), json);
            write(subtrees.resolve("assets/minecraft/models/" + path + ".json"), json);
        }
        write(whole.resolve("assets/minecraft/models/custom/g.json"), "{\"textures\":{\"layer0\":\"minecraft:custom/g\"}}");
        write(whole.resolve("assets/minecraft/models/blockish/h.json"), "{\"textures\":{\"layer0\":\"minecraft:blockish/h\"}}");
        write(whole.resolve("assets/minecraft/models/top.json"), "{\"textures\":{\"layer0\":\"minecraft:top\"}}");

        ResolvedModels withOutside = ResolvedModels.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, whole, Set.of("minecraft")))));
        ResolvedModels without = ResolvedModels.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, subtrees, Set.of("minecraft")))));

        assertThat(List.copyOf(withOutside.blocks().keySet()), is(List.copyOf(without.blocks().keySet())));
        assertThat(List.copyOf(withOutside.items().keySet()), is(List.copyOf(without.items().keySet())));
        assertThat(withOutside.blocks(), is(without.blocks()));
        assertThat(withOutside.items(), is(without.items()));
        assertThat("the whole tree holds the indexed sets and the outside files",
            withOutside.all().keySet().containsAll(Set.of("minecraft:custom/g", "minecraft:blockish/h", "minecraft:top",
                "minecraft:block/a", "minecraft:item/nested/d")), is(true));
    }

    @Test
    @DisplayName("a later pack wins an outside id, and filter.block erases an outside file")
    void outsideFilesMergeAndFilterAsTheSubtreesDo() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/custom/shared.json"), "{\"textures\":{\"layer0\":\"minecraft:custom/lower\"}}");
        write(van.resolve("assets/minecraft/models/custom/hidden.json"), "{\"textures\":{\"layer0\":\"minecraft:custom/hidden\"}}");

        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/models/custom/shared.json"), "{\"textures\":{\"layer0\":\"minecraft:custom/higher\"}}");
        MCMeta filtering = MCMetaParser.parse(
            "{\"pack\":{\"pack_format\":84},\"filter\":{\"block\":[{\"path\":\"custom/hidden\"}]}}",
            new ResourceId("userpack", "pack"));
        ResourcePack filterPack = new ResourcePack(new PackId("userpack"), new PackContainer.Directory(user),
            filtering, Concurrent.newList(PackRoot.BASE).toUnmodifiable(), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft")), filterPack)));

        assertThat(models.find("minecraft:custom/shared").orElseThrow().getTextures().get("layer0").sprite(),
            is("minecraft:custom/higher"));
        assertThat("hidden by filter.block", models.find("minecraft:custom/hidden").isAbsent(), is(true));
    }

    @Test
    @DisplayName("an item model whose parent sits at a namespace's root inherits that parent's gui display")
    void anItemModelInheritsAnOutsideParent() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/item/generated.json"), "{\"parent\":\"builtin/generated\"}");
        Path user = tmp.resolve("user");
        write(user.resolve("assets/gui_model/models/generic_x2.json"),
            "{\"parent\":\"minecraft:item/generated\",\"display\":{\"gui\":{\"rotation\":[0,0,0],\"scale\":[2,2,2]}}}");
        write(user.resolve("assets/testns/models/item/gem.json"),
            "{\"parent\":\"gui_model:generic_x2\",\"textures\":{\"layer0\":\"testns:item/gem\"}}");

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("gui_model", "testns")))));

        ModelData gem = models.items().get("testns:item/gem");
        assertThat(gem.getDisplay().get("gui").getScaleX(), is(2f));
        assertThat(gem.getTextures().get("layer0").sprite(), is("testns:item/gem"));
        assertThat("the chain ends at builtin/generated, so it stays flat", gem.getElements().isEmpty(), is(true));
    }

    @Test
    @DisplayName("a parent no pack ships resolves to the missing cube, reported once naming the child and the parent")
    void anAbsentParentResolvesToTheMissingModel() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/item/generated.json"), "{\"parent\":\"builtin/generated\"}");
        write(van.resolve("assets/minecraft/models/item/flat.json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/flat\"}}");
        Path user = tmp.resolve("user");
        write(user.resolve("assets/testns/models/item/orphan.json"),
            "{\"parent\":\"testns:item/nowhere\",\"textures\":{\"layer0\":\"testns:item/orphan\"}}");
        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("testns"))));

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(stack));

        ModelData orphan = models[0].items().get("testns:item/orphan");
        assertThat("the missing model's one cube", orphan.getElements().size(), is(1));
        assertThat(orphan.getElements().getFirst().getFaces().size(), is(6));
        assertThat(orphan.resolveTextureReference("#missingno"), is(Optional.of("minecraft:missingno")));
        assertThat(orphan.resolveTextureReference("#particle"), is(Optional.of("minecraft:missingno")));
        assertThat("its own layer survives", orphan.getTextures().get("layer0").sprite(), is("testns:item/orphan"));
        assertThat("the missing model carries no display", orphan.getDisplay().isEmpty(), is(true));

        ModelData missing = models[0].find("minecraft:builtin/missing").orElseThrow();
        assertThat(missing.getElements().size(), is(1));
        assertThat(missing.resolveTextureReference("#particle"), is(Optional.of("minecraft:missingno")));

        assertThat("builtin/generated ends the chain rather than standing for a missing parent",
            models[0].items().get("minecraft:item/flat").getElements().isEmpty(), is(true));
        List<String> reports = output.lines().filter(line -> line.contains("names parent")).toList();
        assertThat(reports.size(), is(1));
        assertThat(reports.getFirst(), containsString("testns:item/orphan"));
        assertThat(reports.getFirst(), containsString("testns:item/nowhere"));
        assertThat(reports.getFirst(), containsString("userpack"));
    }

    @Test
    @DisplayName("a parent cycle drops every model whose chain reaches it, one report each, and nothing overflows")
    void aParentCycleDropsEveryModelReachingIt() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/a.json"), "{\"parent\":\"minecraft:block/b\"}");
        write(van.resolve("assets/minecraft/models/block/b.json"), "{\"parent\":\"block/a\"}");
        write(van.resolve("assets/minecraft/models/block/c.json"), "{\"parent\":\"minecraft:block/a\",\"textures\":{\"all\":\"minecraft:block/c\"}}");
        write(van.resolve("assets/minecraft/models/block/d.json"), "{\"textures\":{\"all\":\"minecraft:block/d\"}}");
        // A cycle through a model outside block/ and item/ forms only once the whole tree is read.
        write(van.resolve("assets/minecraft/models/item/loop.json"), "{\"parent\":\"minecraft:custom/loop\"}");
        write(van.resolve("assets/minecraft/models/custom/loop.json"), "{\"parent\":\"minecraft:item/loop\"}");

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft"))))));

        assertThat(models[0].blocks().keySet(), is(Set.of("minecraft:block/d")));
        assertThat(models[0].items().containsKey("minecraft:item/loop"), is(false));
        assertThat(models[0].find("minecraft:custom/loop").isAbsent(), is(true));
        List<String> reports = output.lines().filter(line -> line.contains("cyclic")).toList();
        assertThat(reports.size(), is(5));
        for (String id : List.of("block/a'", "block/b'", "block/c'", "item/loop'", "custom/loop'"))
            assertThat(id + " is reported once", reports.stream().filter(line -> line.contains(id)).count(), is(1L));
    }

    @Test
    @DisplayName("a merged chain the typed read rejects is absent, taking its children with it, and the rest load")
    void aModelTheTypedReadRejectsIsAbsent() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/bad.json"), "{\"elements\":5}");
        write(van.resolve("assets/minecraft/models/block/bad_child.json"),
            "{\"parent\":\"minecraft:block/bad\",\"textures\":{\"all\":\"minecraft:block/bad_child\"}}");
        write(van.resolve("assets/minecraft/models/block/good.json"), "{\"textures\":{\"all\":\"minecraft:block/good\"}}");

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft"))))));

        assertThat(models[0].blocks().keySet(), is(Set.of("minecraft:block/good")));
        assertThat(output, containsString("Failed to load model 'minecraft:block/bad'"));
        assertThat(output, containsString("Failed to load model 'minecraft:block/bad_child'"));
    }

    @Test
    @DisplayName("a malformed higher copy leaves its id absent rather than falling back to the lower pack's copy")
    void aMalformedTopFileShadowsTheLowerCopy() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/block/x.json"), "{\"textures\":{\"all\":\"minecraft:block/x\"}}");
        write(van.resolve("assets/minecraft/models/block/y.json"), "{\"textures\":{\"all\":\"minecraft:block/y\"}}");
        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/models/block/x.json"), "{\"textures\":");

        ResolvedModels[] models = new ResolvedModels[1];
        String output = stderrOf(() -> models[0] = ResolvedModels.load(PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))))));

        assertThat(models[0].blocks().containsKey("minecraft:block/x"), is(false));
        assertThat(models[0].blocks().containsKey("minecraft:block/y"), is(true));
        assertThat(output, containsString("Failed to load model 'minecraft:block/x' from pack 'userpack'"));
    }

    @Test
    @DisplayName("find reads a bare id as a minecraft: one")
    void findQualifiesABareId() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/models/item/x.json"), "{\"textures\":{\"layer0\":\"minecraft:item/x\"}}");

        ResolvedModels models = ResolvedModels.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, van, Set.of("minecraft")))));

        assertThat(models.find("item/x").orElseThrow(), is(sameInstance(models.items().get("minecraft:item/x"))));
        assertThat(models.find("minecraft:item/x").orElseThrow(), is(sameInstance(models.items().get("minecraft:item/x"))));
        assertThat(models.find("item/absent").isAbsent(), is(true));
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

    /**
     * Runs an action with {@code System.err} captured, since {@link ResolvedModels} reports there rather
     * than to a {@code Diagnostics} sink.
     *
     * @param action the action to run
     * @return everything the action printed to {@code System.err}
     */
    private static String stderrOf(Runnable action) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.err.flush();
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

}
