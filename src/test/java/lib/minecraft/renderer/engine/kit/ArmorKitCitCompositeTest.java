package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.engine.texture.Textures;
import lib.minecraft.renderer.face.SkinFace;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorTrim;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies {@link ArmorKit}'s per-layer texture resolution against the CIT override: a hit
 * {@link CitResult} retextures each layer through {@code textureFor("layerN")}, while
 * {@link CitResult#NONE} (and the empty-items-map default) falls through to the equipment model's own
 * texture paths unchanged - the byte-neutral proof for the dormant seam.
 */
class ArmorKitCitCompositeTest {

    @Test
    @DisplayName("a hit CitResult retextures layer0 (base) and layer1 (overlay) via textureFor")
    void hitUsesTextureForPerLayer() {
        ConcurrentMap<String, ResourceId> subs = Concurrent.newMap();
        subs.put("layer1", new ResourceId("minecraft", "cit/overlay"));
        CitResult hit = new CitResult(Optional.of(new ResourceId("minecraft", "cit/base")), subs, Optional.empty(), GlintPolicy.DEFAULT);

        RecordingContext ctx = new RecordingContext(leatherLayers(), hit);
        buildHelmet(ctx, Map.of(ArmorTrim.Slot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.resolved, equalTo(List.of("minecraft:cit/base", "minecraft:cit/overlay")));
    }

    @Test
    @DisplayName("a NONE override falls through to the equipment model's own layer paths")
    void noneFallsThroughToModel() {
        RecordingContext ctx = new RecordingContext(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of(ArmorTrim.Slot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.resolved, equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("the empty-items default never consults the override and resolves the model paths")
    void emptyItemsMapStaysOnModel() {
        RecordingContext ctx = new RecordingContext(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of());

        assertThat(ctx.resolved, equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("a single flat layer with no override fast-returns the one model texture")
    void singleFlatLayerFastReturn() {
        List<EquipmentModel.Layer> iron = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
        RecordingContext ctx = new RecordingContext(iron, CitResult.NONE);

        ArmorKit.buildHumanoidArmor3D(headBounds(), Optional.of(ArmorPiece.of(ArmorMaterial.IRON)),
            Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), new Textures(ctx));

        assertThat(ctx.resolved, equalTo(List.of("minecraft:entity/equipment/humanoid/iron")));
    }

    @Test
    @DisplayName("entity armor sits on its bone bounds - the 180-about-X round trip preserves position")
    void entityArmorPositionPreserved() {
        List<EquipmentModel.Layer> iron = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
        RecordingContext ctx = new RecordingContext(iron, CitResult.NONE);
        // A head bone whose y-range sits well away from the origin, so a stray single (non-round-trip)
        // turn about X would land the armor near -3..-2 instead of on the bone.
        Map<String, Vector3f[]> bones = Map.of("head",
            new Vector3f[]{ new Vector3f(0, 2, 0), new Vector3f(1, 3, 1) });

        ConcurrentList<VisibleTriangle> armor = ArmorKit.buildEntityArmor3D(bones,
            Optional.of(ArmorPiece.of(ArmorMaterial.IRON)),
            Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), new Textures(ctx));

        assertThat(armor.isEmpty(), equalTo(false));
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (VisibleTriangle triangle : armor)
            for (Vector3f vertex : List.of(triangle.position0(), triangle.position1(), triangle.position2())) {
                minY = Math.min(minY, vertex.y());
                maxY = Math.max(maxY, vertex.y());
            }
        // The armor box spans the bone's own [2, 3] y-range (plus the small inflate), not a flipped
        // negative range - the double turn cancels on position and only re-seats the texture.
        assertThat(minY > 1.5f, equalTo(true));
        assertThat(maxY < 3.5f, equalTo(true));
    }

    private static void buildHelmet(@NotNull RecordingContext ctx, @NotNull Map<ArmorTrim.Slot, ItemContext> items) {
        ArmorKit.buildHumanoidArmor3D(headBounds(), Optional.of(ArmorPiece.of(ArmorMaterial.LEATHER)),
            Optional.empty(), Optional.empty(), Optional.empty(), items, new Textures(ctx));
    }

    private static @NotNull List<EquipmentModel.Layer> leatherLayers() {
        return List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "leather"),
                Optional.of(new EquipmentModel.Dyeable(Optional.of(0xFFA06540))), false),
            new EquipmentModel.Layer(new ResourceId("minecraft", "leather_overlay"), Optional.empty(), false));
    }

    private static @NotNull Map<SkinFace, Vector3f[]> headBounds() {
        return Map.of(SkinFace.HEAD, new Vector3f[]{ new Vector3f(0, 0, 0), new Vector3f(1, 1, 1) });
    }

    /**
     * A minimal context recording each resolved texture id, serving fixed equipment layers and a fixed
     * CIT override so the resolution order and per-layer id selection are observable.
     */
    private static final class RecordingContext implements RendererContext {

        private final @NotNull List<String> resolved = new ArrayList<>();
        private final @NotNull List<EquipmentModel.Layer> layers;
        private final @NotNull CitResult cit;

        private RecordingContext(@NotNull List<EquipmentModel.Layer> layers, @NotNull CitResult cit) {
            this.layers = layers;
            this.cit = cit;
        }

        @Override
        public @NotNull Optional<PixelBuffer> resolveTexture(@NotNull String textureId) {
            this.resolved.add(textureId);
            return Optional.of(PixelBuffer.create(64, 32));
        }

        @Override
        public @NotNull List<EquipmentModel.Layer> resolveEquipmentLayers(@NotNull ResourceId assetId, @NotNull LayerType layerType) {
            return this.layers;
        }

        @Override
        public @NotNull CitResult resolveArmorTextureOverride(
            @NotNull ArmorMaterial material, @NotNull LayerType layerType, @NotNull ItemContext item) {
            return this.cit;
        }

        @Override
        public @NotNull Optional<Block> findBlock(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<ColorMap> findColorMap(ColorMap.@NotNull Type type) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Entity> findEntity(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<Item> findItem(@NotNull String id) {
            return Optional.empty();
        }

    }

}
