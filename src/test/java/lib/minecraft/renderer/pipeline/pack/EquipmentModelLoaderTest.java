package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
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
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link EquipmentModelLoader}: the parse of each vanilla {@code equipment/*.json} shape
 * (flat material, dyeable leather base plus overlay, elytra wings, empty-dyeable overlay), the
 * unknown-layer-type / malformed-file drops, and the replace-per-asset-id merge across packs.
 */
@DisplayName("EquipmentModelLoader equipment/*.json")
class EquipmentModelLoaderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("iron.json: flat untinted layers, texture minecraft:iron, path matches the armor convention")
    void parsesFlatMaterial() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/iron.json"), """
            {"layers":{
              "humanoid":[{"texture":"minecraft:iron"}],
              "humanoid_leggings":[{"texture":"minecraft:iron"}],
              "humanoid_baby":[{"texture":"minecraft:iron"}],
              "horse_body":[{"texture":"minecraft:iron"}],
              "nautilus_body":[{"texture":"minecraft:iron"}]
            }}""");

        EquipmentModel iron = load(van).get(new ResourceId("minecraft", "iron"));
        List<EquipmentModel.Layer> humanoid = iron.getLayers(LayerType.HUMANOID);
        assertThat(humanoid.size(), is(1));
        assertThat(humanoid.getFirst().texture(), is(new ResourceId("minecraft", "iron")));
        assertThat(humanoid.getFirst().dyeable().isPresent(), is(false));
        assertThat(humanoid.getFirst().usePlayerTexture(), is(false));
        assertThat(iron.getLayers(LayerType.HUMANOID_LEGGINGS).size(), is(1));
        assertThat(iron.getLayers(LayerType.NAUTILUS_BODY).size(), is(1));
        assertThat(humanoid.getFirst().textureLocation(LayerType.HUMANOID).id(), is("minecraft:entity/equipment/humanoid/iron"));
    }

    @Test
    @DisplayName("leather.json: dyeable base (0xFFA06540) then untinted overlay, in list order")
    void parsesDyeableBaseAndOverlay() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/leather.json"), """
            {"layers":{"humanoid":[
              {"dyeable":{"color_when_undyed":-6265536},"texture":"minecraft:leather"},
              {"texture":"minecraft:leather_overlay"}
            ]}}""");

        List<EquipmentModel.Layer> layers = load(van).get(new ResourceId("minecraft", "leather")).getLayers(LayerType.HUMANOID);
        assertThat(layers.size(), is(2));

        EquipmentModel.Layer base = layers.getFirst();
        assertThat(base.texture(), is(new ResourceId("minecraft", "leather")));
        assertThat(base.dyeable().isPresent(), is(true));
        assertThat(base.dyeable().get().colorWhenUndyed().get(), is(0xFFA06540));

        EquipmentModel.Layer overlay = layers.getLast();
        assertThat(overlay.texture(), is(new ResourceId("minecraft", "leather_overlay")));
        assertThat(overlay.dyeable().isPresent(), is(false));
    }

    @Test
    @DisplayName("elytra.json: wings layer carries use_player_texture true")
    void parsesElytraWings() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/elytra.json"), """
            {"layers":{"wings":[{"texture":"minecraft:elytra","use_player_texture":true}]}}""");

        EquipmentModel.Layer wing = load(van).get(new ResourceId("minecraft", "elytra")).getLayers(LayerType.WINGS).getFirst();
        assertThat(wing.usePlayerTexture(), is(true));
        assertThat(wing.textureLocation(LayerType.WINGS).id(), is("minecraft:entity/equipment/wings/elytra"));
    }

    @Test
    @DisplayName("an empty dyeable object marks a dyed-only overlay (no undyed fallback)")
    void parsesEmptyDyeable() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/wolf_scute.json"), """
            {"layers":{"wolf_body":[
              {"texture":"minecraft:wolf_armor"},
              {"dyeable":{},"texture":"minecraft:wolf_armor_overlay"}
            ]}}""");

        List<EquipmentModel.Layer> layers = load(van).get(new ResourceId("minecraft", "wolf_scute")).getLayers(LayerType.WOLF_BODY);
        EquipmentModel.Layer overlay = layers.getLast();
        assertThat(overlay.dyeable().isPresent(), is(true));
        assertThat("dyeable:{} carries no undyed fallback", overlay.dyeable().get().colorWhenUndyed().isPresent(), is(false));
    }

    @Test
    @DisplayName("an unknown asset id is absent from the index; an unknown layer type is dropped")
    void missingAndUnknownLayer() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/iron.json"), """
            {"layers":{"humanoid":[{"texture":"minecraft:iron"}],"future_slot":[{"texture":"minecraft:iron"}]}}""");

        Map<ResourceId, EquipmentModel> assets = load(van);
        assertThat("the loader reports what it parsed; the MISSING default is the context's",
            assets.containsKey(new ResourceId("minecraft", "nonexistent")), is(false));

        EquipmentModel iron = assets.get(new ResourceId("minecraft", "iron"));
        assertThat(iron.getLayers(LayerType.HUMANOID).size(), is(1));
        assertThat("the unrecognised future_slot layer is dropped", iron.layers().size(), is(1));
    }

    @Test
    @DisplayName("a malformed file is skipped, not thrown")
    void malformedSkipped() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/broken.json"), "{ this is not valid json ");
        write(van.resolve("assets/minecraft/equipment/iron.json"), """
            {"layers":{"humanoid":[{"texture":"minecraft:iron"}]}}""");

        Map<ResourceId, EquipmentModel> assets = load(van);
        assertThat(assets.containsKey(new ResourceId("minecraft", "broken")), is(false));
        assertThat(assets.get(new ResourceId("minecraft", "iron")).getLayers(LayerType.HUMANOID).size(), is(1));
    }

    @Test
    @DisplayName("a higher pack replaces a lower pack's asset wholesale")
    void higherPackReplaces() throws IOException {
        Path van = tmp.resolve("vanilla");
        write(van.resolve("assets/minecraft/equipment/iron.json"), """
            {"layers":{"humanoid":[{"texture":"minecraft:iron"}]}}""");
        Path user = tmp.resolve("user");
        write(user.resolve("assets/minecraft/equipment/iron.json"), """
            {"layers":{"humanoid":[{"texture":"minecraft:custom_iron"}]}}""");

        PackStack stack = PackStack.of(Concurrent.newList(
            pack(PackId.VANILLA, van, Set.of("minecraft")),
            pack(new PackId("userpack"), user, Set.of("minecraft"))));

        EquipmentModel.Layer layer = EquipmentModelLoader.load(stack)
            .get(new ResourceId("minecraft", "iron")).getLayers(LayerType.HUMANOID).getFirst();
        assertThat(layer.texture(), is(new ResourceId("minecraft", "custom_iron")));
    }

    private static Map<ResourceId, EquipmentModel> load(Path vanillaRoot) {
        return EquipmentModelLoader.load(PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot, Set.of("minecraft")))));
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
