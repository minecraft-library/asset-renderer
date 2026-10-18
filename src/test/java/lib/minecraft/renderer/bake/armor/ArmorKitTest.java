package lib.minecraft.renderer.bake.armor;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import dev.simplified.image.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.ArmorPiece;
import lib.minecraft.renderer.call.request.ArmorTrim;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.camera.FitFrame;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.equipment.ArmorForm;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of {@link ArmorKit}'s texture resolution and of the shell it builds. Per-layer resolution is
 * held against the CIT override: a hit {@link CitResult} retextures each layer through
 * {@code textureFor("layerN")}, while {@link CitResult#NONE} (and the empty-items-map default) falls
 * through to the equipment model's own texture paths unchanged.
 * <p>
 * The geometry half pins the vertical span a built helmet occupies - the adult shell's grown head
 * overlay, that span doubling with the {@link FitFrame}'s model scale, and the baby shell's own head
 * box, which shares no span with it - alongside the {@code humanoid_baby} sheet a baby's armour is read
 * from and the fact that a baby draws no trim. Both trim resolve paths, the entity one and the item one,
 * are held to the same three ids in the same order, differing only in the base id.
 * <p>
 * The player half pins that an unarmoured player answers a new, empty list its caller can still add
 * to, under an armour layer its layer stack still holds, and that an armoured one answers, triangle for
 * triangle, what a walk over every one of its body rows builds - item-less, since what is worn decides
 * that and the pack-rule identities do not.
 */
class ArmorKitTest {

    @Test
    @DisplayName("a hit CitResult retextures layer0 (base) and layer1 (overlay) via textureFor")
    void hitUsesTextureForPerLayer() {
        ConcurrentMap<String, ResourceId> subs = Concurrent.newMap();
        subs.put("layer1", new ResourceId("minecraft", "cit/overlay"));
        CitResult hit = new CitResult(Possible.of(new ResourceId("minecraft", "cit/base")), subs, Possible.empty(), GlintPolicy.DEFAULT);

        RecordingContext ctx = recording(leatherLayers(), hit);
        buildHelmet(ctx, Map.of(ArmorSlot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:cit/base", "minecraft:cit/overlay")));
    }

    @Test
    @DisplayName("a NONE override falls through to the equipment model's own layer paths")
    void noneFallsThroughToModel() {
        RecordingContext ctx = recording(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of(ArmorSlot.HELMET, ItemContext.ofItem("minecraft:leather_helmet")));

        assertThat(ctx.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("the empty-items default never consults the override and resolves the model paths")
    void emptyItemsMapStaysOnModel() {
        RecordingContext ctx = recording(leatherLayers(), CitResult.NONE);
        buildHelmet(ctx, Map.of());

        assertThat(ctx.getResolved(), equalTo(List.of(
            "minecraft:entity/equipment/humanoid/leather",
            "minecraft:entity/equipment/humanoid/leather_overlay")));
    }

    @Test
    @DisplayName("a single flat layer with no override fast-returns the one model texture")
    void singleFlatLayerFastReturn() {
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);

        PlayerArmorKit.buildHumanoidArmor3D(headBounds(),
            Map.of(ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.IRON)), Map.of(), ctx);

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:entity/equipment/humanoid/iron")));
    }

    @Test
    @DisplayName("an unarmoured player answers an empty list a caller can still add to, resolving nothing")
    void unarmouredPlayerAnswersAnEmptyMutableList() {
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);

        ConcurrentList<VisibleTriangle> armor = PlayerArmorKit.buildHumanoidArmor3D(
            PlayerOptions.Type.FULL.lattice().boxes(), Map.of(), Map.of(), ctx);

        assertThat(armor.isEmpty(), equalTo(true));
        assertThat(ctx.getResolved().isEmpty(), equalTo(true));

        // The armoured answer is a list the caller owns, so the unarmoured one takes an add as well.
        VisibleTriangle worn = PlayerArmorKit.buildHumanoidArmor3D(headBounds(),
            Map.of(ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.IRON)), Map.of(),
            recording(ironLayer(), CitResult.NONE)).getFirst();
        armor.add(worn);
        assertThat(armor.size(), equalTo(1));

        // And the list is the caller's alone: the next unarmoured call answers a list of its own, which
        // the add above leaves empty.
        ConcurrentList<VisibleTriangle> again = PlayerArmorKit.buildHumanoidArmor3D(
            PlayerOptions.Type.FULL.lattice().boxes(), Map.of(), Map.of(), ctx);
        assertThat("each call answers a list of its own", again, not(sameInstance(armor)));
        assertThat("which an earlier caller's add leaves empty", again.isEmpty(), equalTo(true));
    }

    @Test
    @DisplayName("an unarmoured player's layer stack still holds its armour layer, which adds nothing")
    void unarmouredPlayerKeepsItsArmourLayer() {
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);
        LayerStack<GeometryLayer> stack = new LayerStack<>();

        PlayerArmorKit.appendArmor(stack, PlayerOptions.Type.FULL, PlayerOptions.defaults(), ctx);

        // A caller's geometry decorator is handed this stack, so the layer is there to see even when
        // nothing is worn.
        assertThat("the armour layer", stack.size(), equalTo(1));
        ConcurrentList<VisibleTriangle> sink = Concurrent.newList();
        stack.ordered().getFirst().contribute(sink);
        assertThat("adds nothing", sink.isEmpty(), equalTo(true));
        assertThat("and resolves nothing", ctx.getResolved().isEmpty(), equalTo(true));
    }

    @Test
    @DisplayName("an armoured player keeps every triangle the unconditional walk over its body rows builds")
    void armouredPlayerKeepsTheUnconditionalTriangles() {
        Map<HumanoidPart, Box> body = PlayerOptions.Type.FULL.lattice().boxes();

        // Every fixture is item-less: a pack-rule identity is not what decides whether armour is worn.
        for (Map<ArmorSlot, ArmorPiece> equipped : wornMatrix()) {
            String worn = equipped.keySet().toString();
            RecordingContext built = recording(ironLayer(), CitResult.NONE);
            RecordingContext walked = recording(ironLayer(), CitResult.NONE);

            ConcurrentList<VisibleTriangle> actual =
                PlayerArmorKit.buildHumanoidArmor3D(body, equipped, Map.of(), built);
            ConcurrentList<VisibleTriangle> expected =
                unconditionalHumanoidArmor3D(body, equipped, Map.of(), walked);

            assertThat(worn, expected.isEmpty(), equalTo(false));
            assertThat(worn, geometry(actual), equalTo(geometry(expected)));
            assertThat(worn, built.getResolved(), equalTo(walked.getResolved()));
        }

        // All four slots over the full body draw ten boxes - the helmet's two over the head, the
        // chestplate's torso and arms, the leggings' torso and legs, the boots' legs - of twelve
        // triangles each.
        Map<ArmorSlot, ArmorPiece> everySlot = Map.of(
            ArmorSlot.HELMET, ArmorPiece.of(ArmorMaterial.IRON),
            ArmorSlot.CHESTPLATE, ArmorPiece.of(ArmorMaterial.IRON),
            ArmorSlot.LEGGINGS, ArmorPiece.of(ArmorMaterial.IRON),
            ArmorSlot.BOOTS, ArmorPiece.of(ArmorMaterial.IRON));
        assertThat(PlayerArmorKit.buildHumanoidArmor3D(body, everySlot, Map.of(),
            recording(ironLayer(), CitResult.NONE)).size(), equalTo(120));
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
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);

        EntityArmorKit.buildEntityArmor3D(babyShell(), FitFrame.IDENTITY,
            Map.of(ArmorSlot.HELMET, trimmed()), Map.of(), ctx);

        assertThat(ctx.getResolved(), equalTo(List.of("minecraft:entity/equipment/humanoid_baby/iron")));
    }

    @Test
    @DisplayName("an adult draws its trim from the humanoid atlas")
    void adultArmorReadsTrimAtlas() {
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);

        EntityArmorKit.buildEntityArmor3D(genericShell(), FitFrame.IDENTITY,
            Map.of(ArmorSlot.HELMET, trimmed()), Map.of(), ctx);

        assertThat(ctx.getResolved().contains("minecraft:trims/entity/humanoid/coast"), equalTo(true));
    }

    @Test
    @DisplayName("a scaled wearer's shell seats at the feet anchor")
    void shellSeatsAtTheFeetAnchor() {
        // The generator bakes a scaled wearer's own bone pivots about the same anchor, declared
        // by the parser itself in the same build rather than read from EntityMesh.FEET_ANCHOR - so
        // the two agreeing is what puts a shell on a body rather than beside it, and nothing
        // compares them. The tooling's half is pinned by its exact-float parse of the ghast; this
        // is the renderer's half, written as a literal so an edit to EntityMesh.FEET_ANCHOR moves a
        // test rather than a render.
        Shell giant = EntityModelLoader.load()
            .get("minecraft:giant").humanoidArmor().orElseThrow();

        assertThat(giant.meshScale(), equalTo(6f));
        assertThat(giant.meshOffset().y(), equalTo(-120.08f));
        // The identity scale seats at the origin, which is why the term is invisible on most wearers.
        assertThat(genericShell().meshOffset().y(), equalTo(0f));
    }

    @Test
    @DisplayName("both trim paths resolve the same triple in the same order, differing only in the base id")
    void trimPathsDifferOnlyInTheBaseId() {
        // The two paths share one resolve, so what has to be pinned is that only the first of the three
        // ids is the caller's. The entity half is reachable from a render; the item half is reachable
        // from no sweep and no other test, which is why it is asserted here rather than measured.
        RecordingContext entity = recording(List.of(), CitResult.NONE);
        ArmorKit.resolveTrimTexture(entity, LayerType.HUMANOID.getId(),
            ArmorTrim.Pattern.COAST, ArmorTrim.Color.COPPER);

        RecordingContext item = recording(List.of(), CitResult.NONE);
        TrimKit.resolve(item,
            ArmorSlot.CHESTPLATE.getKey(), ArmorTrim.Color.COPPER.getKey());

        RecordingContext parsed = recording(List.of(), CitResult.NONE);
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
    private static @NotNull RecordingContext recording(
        @NotNull List<EquipmentModel.Layer> layers, @NotNull CitResult cit) {
        return RecordingContext.over(RendererContext.builder()
                .textures(id -> Possible.of(PixelBuffer.create(64, 32)))
                .build())
            .answeringEquipment(layers)
            .answeringArmorOverride(cit);
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
        return AppearanceOptions.builder().age(Age.BABY).build().shell(genericShell());
    }

    /**
     * The {@code [min, max]} y-extent of the iron-helmet triangles built for one shell at one render
     * scale.
     */
    private static float[] helmetYSpan(@NotNull Shell shell, float modelScale) {
        RecordingContext ctx = recording(ironLayer(), CitResult.NONE);

        ConcurrentList<VisibleTriangle> armor = EntityArmorKit.buildEntityArmor3D(shell,
            new FitFrame(Vector3f.ZERO, 1f, modelScale),
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

    private static void buildHelmet(@NotNull RecordingContext ctx, @NotNull Map<ArmorSlot, ItemContext> items) {
        PlayerArmorKit.buildHumanoidArmor3D(headBounds(),
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

    /** Each slot worn alone, then all four worn together and trimmed. */
    private static @NotNull List<Map<ArmorSlot, ArmorPiece>> wornMatrix() {
        List<Map<ArmorSlot, ArmorPiece>> matrix = new ArrayList<>();
        ArmorSlot.forEach(slot -> matrix.add(Map.of(slot, ArmorPiece.of(ArmorMaterial.IRON))));
        matrix.add(Map.of(
            ArmorSlot.HELMET, trimmed(), ArmorSlot.CHESTPLATE, trimmed(),
            ArmorSlot.LEGGINGS, trimmed(), ArmorSlot.BOOTS, trimmed()));
        return matrix;
    }

    /**
     * A frozen copy of the player build that walks its body rows whatever is worn and hands them to
     * the shared builder with the identity frame and the adult form - the triangles an armoured call
     * keeps answering.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> unconditionalHumanoidArmor3D(
        @NotNull Map<HumanoidPart, Box> bodyPositions,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull RendererContext context
    ) {
        return ArmorKit.buildArmor3D(frozenBodyRows(bodyPositions), UnaryOperator.identity(), ArmorForm.ADULT,
            equipped, items, context);
    }

    /** A frozen copy of the player's body rows, one per box a slot could draw, in body part order. */
    private static @NotNull List<WornBox> frozenBodyRows(@NotNull Map<HumanoidPart, Box> bodyPositions) {
        List<WornBox> rows = new ArrayList<>();

        HumanoidPart.forEach(part -> {
            Box bounds = bodyPositions.get(part);
            if (bounds == null) return;
            ConcurrentSet<ArmorSlot> slots = PlayerLattice.playerSlots(part);
            if (slots.isEmpty()) return;

            rows.add(new WornBox.Body(part, false, slots, bounds));

            ConcurrentSet<ArmorSlot> second = slots.stream()
                .filter(ArmorSlot::keepsChildren)
                .collect(Concurrent.toUnmodifiableSet());
            if (!second.isEmpty())
                rows.add(new WornBox.Body(part, true, second, bounds));
        });

        return rows;
    }

    /**
     * Every field of each triangle a render reads, with the sheet it is textured from reduced to its
     * size, since two builds through two contexts resolve equal sheets as distinct buffers.
     */
    private static @NotNull List<List<Object>> geometry(@NotNull List<VisibleTriangle> triangles) {
        return triangles.stream()
            .map(triangle -> List.<Object>of(
                triangle.position0(), triangle.position1(), triangle.position2(),
                triangle.uv0(), triangle.uv1(), triangle.uv2(),
                triangle.texture().width(), triangle.texture().height(), triangle.tintArgb(),
                triangle.normal(), triangle.shading(), triangle.traits(),
                Optional.ofNullable(triangle.debugTag())))
            .toList();
    }

}
