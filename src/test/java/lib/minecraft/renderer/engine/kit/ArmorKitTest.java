package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.equipment.ArmorMaterial;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorSlot;
import lib.minecraft.renderer.asset.equipment.ArmorTrim;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.GlintPolicy;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.camera.RenderFrame;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.support.StubRendererContext;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;

/**
 * Coverage of {@link ArmorKit}'s texture resolution and of the shell it builds. Per-layer resolution is
 * held against the CIT override: a hit {@link CitResult} retextures each layer through
 * {@code textureFor("layerN")}, while {@link CitResult#NONE} (and the empty-items-map default) falls
 * through to the equipment model's own texture paths unchanged.
 * <p>
 * The geometry half pins the vertical span a built helmet occupies - the adult shell's grown head
 * overlay, that span doubling with the {@link RenderFrame}'s model scale, and the baby shell's own head
 * box, which shares no span with it - alongside the {@code humanoid_baby} sheet a baby's armour is read
 * from and the fact that a baby draws no trim. Both trim resolve paths, the entity one and the item one,
 * are held to the same three ids in the same order, differing only in the base id.
 */
class ArmorKitTest {

    @Test
    @DisplayName("a hit CitResult retextures layer0 (base) and layer1 (overlay) via textureFor")
    void hitUsesTextureForPerLayer() {
        ConcurrentMap<String, ResourceId> subs = Concurrent.newMap();
        subs.put("layer1", new ResourceId("minecraft", "cit/overlay"));
        CitResult hit = new CitResult(Optional.of(new ResourceId("minecraft", "cit/base")), subs, Optional.empty(), GlintPolicy.DEFAULT);

        StubRendererContext ctx = recording(leatherLayers(), hit);
        buildHelmet(ctx, Map.of(ArmorSlot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:cit/base", "minecraft:cit/overlay")));
    }

    @Test
    @DisplayName("a NONE override falls through to the equipment model's own layer paths")
    void noneFallsThroughToModel() {
        StubRendererContext ctx = recording(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of(ArmorSlot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("the empty-items default never consults the override and resolves the model paths")
    void emptyItemsMapStaysOnModel() {
        StubRendererContext ctx = recording(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of());

        assertThat(ctx.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("a single flat layer with no override fast-returns the one model texture")
    void singleFlatLayerFastReturn() {
        StubRendererContext ctx = recording(ironLayer(), CitResult.NONE);

        ArmorKit.buildHumanoidArmor3D(headBounds(),
            Map.of(ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.IRON)), Map.of(), ctx);

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:entity/equipment/humanoid/iron")));
    }

    @Test
    @DisplayName("adult armor wears the generic humanoid mesh")
    void adultArmorUsesGenericMesh() {
        float[] span = helmetYSpan(genericShell(), 1f);

        // The generic head box spans y [-8, 0] in model units; a helmet keeps that part AND its
        // children, so the outer span is the head's overlay box, grown a further half unit on top of
        // the layer-1 deformation - [-9.5, 1.5].
        assertThat((double) span[0], closeTo(-9.5d, 1e-4d));
        assertThat((double) span[1], closeTo(1.5d, 1e-4d));
    }

    @Test
    @DisplayName("adult armor scales with the render frame")
    void adultArmorFollowsRenderFrame() {
        float[] span = helmetYSpan(genericShell(), 2f);

        // Doubling the render's model scale doubles the shell with the body it dresses.
        assertThat((double) span[0], closeTo(-19d, 1e-4d));
        assertThat((double) span[1], closeTo(3d, 1e-4d));
    }

    @Test
    @DisplayName("a baby wears its own shell, not the adult one")
    void babyArmorWearsBabyShell() {
        float[] span = helmetYSpan(babyShell(), 1f);

        // The baby shell's head is a nine-wide box hung off a pivot at y 15, spanning y [8, 16] in
        // model units, and its outer deformation grows half a unit on that axis. Nothing about that
        // span exists on the adult shell, whose head sits at the origin.
        assertThat((double) span[0], closeTo(7.5d, 1e-4d));
        assertThat((double) span[1], closeTo(16.5d, 1e-4d));
    }

    @Test
    @DisplayName("a baby draws its armor from the baby sheet and never a trim")
    void babyArmorReadsBabySheet() {
        StubRendererContext ctx = recording(ironLayer(), CitResult.NONE);

        ArmorKit.buildEntityArmor3D(babyShell(), RenderFrame.IDENTITY,
            Map.of(ArmorSlot.HELMET, trimmed()), Map.of(), ctx);

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:entity/equipment/humanoid_baby/iron")));
    }

    @Test
    @DisplayName("an adult draws its trim from the humanoid atlas")
    void adultArmorReadsTrimAtlas() {
        StubRendererContext ctx = recording(ironLayer(), CitResult.NONE);

        ArmorKit.buildEntityArmor3D(genericShell(), RenderFrame.IDENTITY,
            Map.of(ArmorSlot.HELMET, trimmed()), Map.of(), ctx);

        assertThat(ctx.getResolved().contains("minecraft:trims/entity/humanoid/coast"), equalTo(true));
    }

    @Test
    @DisplayName("both trim paths resolve the same triple in the same order, differing only in the base id")
    void trimPathsDifferOnlyInTheBaseId() {
        // The two paths share one resolve, so what has to be pinned is that only the first of the three
        // ids is the caller's. The entity half is reachable from a render; the item half is reachable
        // from no sweep and no other test, which is why it is asserted here rather than measured.
        StubRendererContext entity = recording(List.of(), CitResult.NONE);
        ArmorKit.resolveTrimTexture(entity, LayerType.HUMANOID.getId(),
            ArmorTrim.Pattern.COAST, ArmorTrim.Color.COPPER);

        StubRendererContext item = recording(List.of(), CitResult.NONE);
        TrimKit.resolve(item,
            ArmorSlot.CHESTPLATE.getKey(), ArmorTrim.Color.COPPER.getKey());

        StubRendererContext parsed = recording(List.of(), CitResult.NONE);
        TrimKit.resolveFromTextureRef(parsed, "minecraft:trims/items/chestplate_trim_copper");

        assertThat(entity.getResolved(), equalTo(List.of(
            "minecraft:trims/entity/humanoid/coast",
            "minecraft:trims/color_palettes/trim_palette",
            "minecraft:trims/color_palettes/copper")));
        assertThat(item.getResolved(), equalTo(List.of(
            "minecraft:trims/items/chestplate_trim",
            "minecraft:trims/color_palettes/trim_palette",
            "minecraft:trims/color_palettes/copper")));
        // The filename round trip lands on the same three ids as the (slot, material) call it parses to.
        assertThat(parsed.getResolved(), equalTo(item.getResolved()));
    }

    /**
     * A context recording each resolved texture id, serving fixed equipment layers and a fixed CIT
     * override so the resolution order and per-layer id selection are observable.
     */
    private static @NotNull StubRendererContext recording(
        @NotNull List<EquipmentModel.Layer> layers, @NotNull CitResult cit) {
        return StubRendererContext.builder()
            .equipmentLayers(layers)
            .armorOverride(cit)
            .everyTexture(() -> PixelBuffer.create(64, 32))
            .build();
    }

    /** The one flat layer an iron piece resolves to. */
    private static @NotNull List<EquipmentModel.Layer> ironLayer() {
        return List.of(new EquipmentModel.Layer(new ResourceId("minecraft", "iron"), Optional.empty(), false));
    }

    /** An iron helmet carrying a trim, so the trim pass is reached for whichever form is dressed. */
    private static @NotNull ArmorPiece trimmed() {
        return ArmorPiece.of(ArmorMaterial.IRON, ArmorTrim.Color.COPPER, ArmorTrim.Pattern.COAST);
    }

    /**
     * The shell vanilla dresses an unremarkable humanoid in, read through the loaded index rather than
     * restated here, so these spans measure the shipped mesh and its shipped deformations.
     */
    private static @NotNull Shell genericShell() {
        return EntityModelLoader.load()
            .get("minecraft:zombie").humanoidArmor().orElseThrow();
    }

    /** The shell that same wearer's baby is dressed in. */
    private static @NotNull Shell babyShell() {
        return genericShell().forAppearance(AppearanceOptions.builder().age(Age.BABY).build());
    }

    /**
     * The {@code [min, max]} y-extent of the iron-helmet triangles built for one shell at one render
     * scale.
     */
    private static float[] helmetYSpan(@NotNull Shell shell, float modelScale) {
        StubRendererContext ctx = recording(ironLayer(), CitResult.NONE);

        ConcurrentList<VisibleTriangle> armor = ArmorKit.buildEntityArmor3D(shell,
            new RenderFrame(Vector3f.ZERO, 1f, modelScale),
            Map.of(ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.IRON)), Map.of(), ctx);

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

    private static void buildHelmet(@NotNull StubRendererContext ctx, @NotNull Map<ArmorSlot, ItemContext> items) {
        ArmorKit.buildHumanoidArmor3D(headBounds(),
            Map.of(ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.LEATHER)), items, ctx);
    }

    private static @NotNull List<EquipmentModel.Layer> leatherLayers() {
        return List.of(
            new EquipmentModel.Layer(new ResourceId("minecraft", "leather"),
                Optional.of(new EquipmentModel.Dyeable(Optional.of(0xFFA06540))), false),
            new EquipmentModel.Layer(new ResourceId("minecraft", "leather_overlay"), Optional.empty(), false));
    }

    private static @NotNull Map<HumanoidPart, Box> headBounds() {
        return Map.of(HumanoidPart.HEAD, new Box(0f, 0f, 0f, 1f, 1f, 1f));
    }

}
