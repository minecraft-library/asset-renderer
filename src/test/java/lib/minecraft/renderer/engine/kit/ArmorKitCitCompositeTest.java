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
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
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
    @DisplayName("adult armor wears the generic humanoid mesh")
    void adultArmorUsesGenericMesh() {
        float[] span = helmetYSpan(
            new ArmorKit.EntityArmorFrame(genericShell(), Vector3f.ZERO, 1f, 1f));

        // The generic head box spans y [-8, 0] in model units; a helmet keeps that part AND its
        // children, so the outer span is the head's overlay box, grown a further half unit on top of
        // the layer-1 deformation - [-9.5, 1.5].
        assertThat((double) span[0], closeTo(-9.5d, 1e-4d));
        assertThat((double) span[1], closeTo(1.5d, 1e-4d));
    }

    @Test
    @DisplayName("adult armor scales with the render frame")
    void adultArmorFollowsRenderFrame() {
        float[] span = helmetYSpan(
            new ArmorKit.EntityArmorFrame(genericShell(), Vector3f.ZERO, 1f, 2f));

        // Doubling the render's model scale doubles the shell with the body it dresses.
        assertThat((double) span[0], closeTo(-19d, 1e-4d));
        assertThat((double) span[1], closeTo(3d, 1e-4d));
    }

    @Test
    @DisplayName("a baby wears its own shell, not the adult one")
    void babyArmorWearsBabyShell() {
        float[] span = helmetYSpan(
            new ArmorKit.EntityArmorFrame(babyShell(), Vector3f.ZERO, 1f, 1f));

        // The baby shell's head is a nine-wide box hung off a pivot at y 15, spanning y [8, 16] in
        // model units, and its outer deformation grows half a unit on that axis. Nothing about that
        // span exists on the adult shell, whose head sits at the origin.
        assertThat((double) span[0], closeTo(7.5d, 1e-4d));
        assertThat((double) span[1], closeTo(16.5d, 1e-4d));
    }

    @Test
    @DisplayName("a baby draws its armor from the baby sheet and never a trim")
    void babyArmorReadsBabySheet() {
        List<EquipmentModel.Layer> iron = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
        RecordingContext ctx = new RecordingContext(iron, CitResult.NONE);

        ArmorKit.buildEntityArmor3D(new ArmorKit.EntityArmorFrame(babyShell(), Vector3f.ZERO, 1f, 1f),
            Optional.of(trimmed()), Optional.empty(), Optional.empty(), Optional.empty(),
            Map.of(), new Textures(ctx));

        assertThat(ctx.resolved, equalTo(List.of("minecraft:entity/equipment/humanoid_baby/iron")));
    }

    @Test
    @DisplayName("an adult draws its trim from the humanoid atlas")
    void adultArmorReadsTrimAtlas() {
        List<EquipmentModel.Layer> iron = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
        RecordingContext ctx = new RecordingContext(iron, CitResult.NONE);

        ArmorKit.buildEntityArmor3D(new ArmorKit.EntityArmorFrame(genericShell(), Vector3f.ZERO, 1f, 1f),
            Optional.of(trimmed()), Optional.empty(), Optional.empty(), Optional.empty(),
            Map.of(), new Textures(ctx));

        assertThat(ctx.resolved.contains("minecraft:trims/entity/humanoid/coast"), equalTo(true));
    }

    /** An iron helmet carrying a trim, so the trim pass is reached for whichever form is dressed. */
    private static @NotNull ArmorPiece trimmed() {
        return ArmorPiece.of(ArmorMaterial.IRON, ArmorTrim.Color.COPPER, ArmorTrim.Pattern.COAST);
    }

    /**
     * The shell vanilla dresses an unremarkable humanoid in, read through the loaded index rather than
     * restated here, so these spans measure the shipped mesh and its shipped deformations.
     */
    private static @NotNull Optional<Entity.HumanoidArmor> genericShell() {
        return EntityModelLoader.load(Diagnostics.root("test", Diagnostics.Output.NONE, null))
            .get("minecraft:zombie").humanoidArmor();
    }

    /** The shell that same wearer's baby is dressed in. */
    private static @NotNull Optional<Entity.HumanoidArmor> babyShell() {
        return genericShell().map(Entity.HumanoidArmor::forBaby);
    }

    /**
     * The {@code [min, max]} y-extent of the iron-helmet triangles built for one frame.
     */
    private static float[] helmetYSpan(@NotNull ArmorKit.EntityArmorFrame frame) {
        List<EquipmentModel.Layer> iron = List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
        RecordingContext ctx = new RecordingContext(iron, CitResult.NONE);

        ConcurrentList<VisibleTriangle> armor = ArmorKit.buildEntityArmor3D(frame,
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
        return new float[]{ minY, maxY };
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
