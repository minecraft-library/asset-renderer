package lib.minecraft.renderer.call.request;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.draw.PassDeclaration;
import lib.minecraft.renderer.engine.math.Matrix4f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.fixture.CompilerFixtures;
import lib.minecraft.renderer.fixture.RegistrarFixtures;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two appearance selections whose caller spelling names a third state: the carried block, where
 * {@code "none"} drops what an unset selection draws, and an equipment slot, where a blank material
 * equips the layer's default rather than leaving the slot unequipped.
 *
 * <p>Each is read as a {@link Possible} - absent where the caller said nothing, empty where the caller
 * said none, present with the value named - and each pair of value-less states draws differently, so
 * a reader folding the two would move a render.
 *
 * <p>A selected carried block does more than draw. It swaps in the pose and passes of the row's carried
 * form ahead of any filled slot's equipment form, and steps aside where a shape form has swapped its
 * passes in or a size form a pose of its own; and it admits the {@code carried} gate a style source
 * carries. Neither the swap nor the gate happens while the selection is unset or {@code none}.
 *
 * <p>The large shape form's passes go through the same gate filter as the row's own, so a pass gated
 * off drops from the large body as it would from the small one.
 */
@DisplayName("the carried-block and equipment selections tell unset, none and a value apart")
class AppearanceSelectionTest {

    @Test
    @DisplayName("the carried block answers absent unset, empty for none and present for a block id")
    void theCarriedBlockAnswersThreeStates() {
        assertEquals(Possible.absent(), AppearanceOptions.builder().build().carriedBlock());
        assertEquals(Possible.empty(), AppearanceOptions.builder().carried("none").build().carriedBlock());
        assertEquals(Possible.of("minecraft:poppy"),
            AppearanceOptions.builder().carried("minecraft:poppy").build().carriedBlock());
    }

    @Test
    @DisplayName("unset draws the fixed decorations alone, none draws no block, and a block id draws both kinds")
    void theCarriedBlockResolvesTheBlockOverlays() {
        Entity carrier = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(new EntityMesh())
            .axes(CompilerFixtures.noAxes())
            .blockOverlays(Concurrent.newUnmodifiableList(
                new Entity.BlockOverlayLayer("minecraft:carved_pumpkin", null, Matrix4f.IDENTITY, false),
                new Entity.BlockOverlayLayer("", "right_arm", Matrix4f.IDENTITY, true)))
            .build();

        assertEquals(List.of("minecraft:carved_pumpkin"),
            blockIds(AppearanceOptions.builder().build(), carrier),
            "an unset selection keeps the fixed decoration and holds no block");
        assertEquals(List.of(),
            blockIds(AppearanceOptions.builder().carried("none").build(), carrier),
            "none drops the fixed decoration with the held block");
        assertEquals(List.of("minecraft:carved_pumpkin", "minecraft:poppy"),
            blockIds(AppearanceOptions.builder().carried("minecraft:poppy").build(), carrier),
            "a block id keeps the fixed decoration and holds the block named");
    }

    @Test
    @DisplayName("a selected carried block swaps in the carried form's pose and passes, and keeps the row's mesh")
    void aSelectedCarriedBlockSwapsInTheCarriedForm() {
        EntityPose body = pose(0f);
        EntityPose raised = pose(-0.5f);
        EntityPose other = pose(0.25f);
        Entity.OverlayLayer apart = pass(other);
        Entity row = holder(body, apart);
        Entity carrier = withForms(row, Optional.of(reposed(row, raised)), Entity.Variation.none());

        Entity held = AppearanceOptions.builder().carried("minecraft:grass_block").build().resolve(carrier);
        assertSame(raised, held.pose(), "a selected block swaps the carried form's pose in for the body's");
        assertSame(raised, held.overlays().getFirst().pose(), "and its pass that shared the body's pose");
        assertSame(other, held.overlays().getLast().pose(), "a pass posed apart keeps its own");
        assertSame(row.model(), held.model(), "the mesh drawn is the row's own");
        assertEquals("minecraft:grass_block", held.blockOverlays().getLast().blockId(),
            "and the selectable row draws the block named");

        for (AppearanceOptions empty : List.of(AppearanceOptions.builder().build(),
            AppearanceOptions.builder().carried("none").build())) {
            Entity resolved = empty.resolve(carrier);
            assertSame(body, resolved.pose(), "with no block held the body keeps its own pose");
            assertSame(body, resolved.overlays().getFirst().pose(), "and so does the pass sharing it");
        }
    }

    @Test
    @DisplayName("the carried form answers ahead of a filled slot's, and the first filled slot in the axis's order wins")
    void theCarriedFormAnswersAheadOfTheFilledSlots() {
        EntityPose body = pose(0f);
        Entity row = holder(body, pass(pose(0.25f)));
        Entity carried = reposed(row, pose(-0.5f));
        Entity saddled = reposed(row, pose(0.5f));
        Entity armoured = reposed(row, pose(0.75f));
        Entity definition = withForms(row, Optional.of(carried), new Entity.Variation<>(
            Concurrent.newUnmodifiableLinkedMap(Map.entry("saddle", saddled), Map.entry("body", armoured)),
            Optional.empty()));

        assertSame(armoured.pose(), AppearanceOptions.builder().equipment(Map.of("body", "iron")).build()
            .resolve(definition).pose(), "a filled slot swaps its form in");
        assertSame(saddled.pose(), AppearanceOptions.builder().equipment(Map.of("body", "", "saddle", "")).build()
            .resolve(definition).pose(), "of two filled slots the first in the axis's order answers");
        assertSame(carried.pose(), AppearanceOptions.builder().carried("minecraft:poppy")
                .equipment(Map.of("saddle", "")).build().resolve(definition).pose(),
            "a selected block answers ahead of every filled slot");
        assertSame(saddled.pose(), AppearanceOptions.builder().carried("none")
                .equipment(Map.of("saddle", "")).build().resolve(definition).pose(),
            "and a block of none leaves the filled slot to answer");
        assertSame(body, AppearanceOptions.builder().equipment(Map.of("chest", "")).build()
            .resolve(definition).pose(), "a filled slot the axis does not name re-poses nothing");
    }

    @Test
    @DisplayName("a selected carried block leaves a large shape's passes and the row's pose in force")
    void aSelectedCarriedBlockStepsAsideForTheLargeShape() {
        EntityPose body = pose(0f);
        Entity row = holder(body, pass(pose(0.25f)));
        Entity large = row.mutate()
            .model(new EntityMesh())
            .overlays(Concurrent.newUnmodifiableList(pass(body)))
            .build();
        Entity definition = withForms(withLargeShape(row, large), Optional.of(reposed(row, pose(-0.5f))),
            Entity.Variation.none());

        Entity held = AppearanceOptions.builder().carried("minecraft:poppy").pattern(TropicalFishPattern.FLOPPER)
            .build().resolve(definition);
        assertSame(large.overlays(), held.overlays(),
            "the large form's passes stay in force, not the small row's carried ones");
        assertSame(body, held.pose(), "and so does the row's pose, which the shape fold leaves alone");
    }

    @Test
    @DisplayName("a large shape's passes go through the gate filter: one gated off drops and an ungated one stays")
    void aLargeShapesPassesGoThroughTheGateFilter() {
        EntityPose body = pose(0f);
        Entity.OverlayLayer ungated = pass(body);
        Entity.OverlayLayer swirl = RegistrarFixtures.pass(new EntityMesh(), Optional.empty(),
            PassDeclaration.DEFAULT, 0xFFFFFFFF, false, Optional.empty(), Optional.empty(),
            Optional.of(new AppearanceGate.Selected(Flag.CHARGED, true)), Optional.empty(), body);
        Entity row = holder(body, pass(pose(0.25f)));
        Entity definition = withLargeShape(row, row.mutate()
            .model(new EntityMesh())
            .overlays(Concurrent.newUnmodifiableList(ungated, swirl))
            .build());

        assertEquals(List.of(ungated), List.copyOf(AppearanceOptions.builder().pattern(TropicalFishPattern.FLOPPER)
                .build().resolve(definition).overlays()),
            "the large body drops the pass its gate turns off and keeps the ungated one");
        assertEquals(List.of(ungated, swirl), List.copyOf(AppearanceOptions.builder()
                .pattern(TropicalFishPattern.FLOPPER).charged(true).build().resolve(definition).overlays()),
            "and draws the gated pass once its flag is selected");
    }

    @Test
    @DisplayName("the carried flag is selected exactly while a block is selected")
    void theCarriedFlagIsSelectedWhileABlockIsSelected() {
        assertTrue(AppearanceOptions.builder().carried("minecraft:poppy").build().selects(Flag.CARRIED),
            "a selected block selects what only a held block moves");
        assertFalse(AppearanceOptions.builder().build().selects(Flag.CARRIED),
            "an unset selection holds no block");
        assertFalse(AppearanceOptions.builder().carried("none").build().selects(Flag.CARRIED),
            "none holds no block either");
        assertTrue(AppearanceOptions.builder().charged(true).build().selects(Flag.CHARGED),
            "every other flag answers its own selection");
        assertFalse(AppearanceOptions.builder().carried("minecraft:poppy").build().selects(Flag.CHARGED),
            "a held block selects no other flag");
    }

    @Test
    @DisplayName("an equipment slot answers absent unequipped, empty for a blank material and present for a named one")
    void anEquipmentSlotAnswersThreeStates() {
        AppearanceOptions appearance = AppearanceOptions.builder()
            .equipment(Map.of("saddle", "", "body", "iron", "chest", "  "))
            .build();

        assertEquals(Possible.absent(), appearance.equipmentMaterial("head"));
        assertEquals(Possible.empty(), appearance.equipmentMaterial("saddle"));
        assertEquals(Possible.empty(), appearance.equipmentMaterial("chest"),
            "a value of whitespace alone is as blank as an empty one");
        assertEquals(Possible.of("iron"), appearance.equipmentMaterial("body"));
    }

    @Test
    @DisplayName("an equipment layer draws its default for a blank material and nothing for an unequipped slot")
    void anEquipmentLayerReadsTheSlotsState() {
        ResourceId saddle = ResourceId.parse("minecraft:saddle");
        ResourceId iron = ResourceId.parse("minecraft:iron");
        Entity.EquipmentOverlay layer = new Entity.EquipmentOverlay("saddle", new EntityMesh(), EntityPose.NONE,
            LayerType.PIG_SADDLE,
            Concurrent.newUnmodifiableMap(Map.of(Entity.EquipmentOverlay.UNSELECTED, saddle, "iron", iron)),
            Optional.empty());

        assertEquals(Optional.of(saddle), layer.assetFor(Possible.empty()));
        assertEquals(Optional.of(iron), layer.assetFor(Possible.of("iron")));
        assertEquals(Optional.empty(), layer.assetFor(Possible.of("gold")),
            "a material the layer names no asset for draws nothing");
        assertEquals(Optional.empty(), layer.assetFor(Possible.absent()),
            "an unequipped slot draws nothing, though the layer carries a default");
    }

    /** A pose writing one bone channel, distinct by identity from every other this builds. */
    private static @NotNull EntityPose pose(float armPitch) {
        return new EntityPose(Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("right_arm", Map.of(PoseChannel.X_ROT, new PoseExpr.Constant(armPitch)))),
            Concurrent.newUnmodifiableList(), Optional.empty());
    }

    /** One pass drawing an empty mesh under the given pose. */
    private static @NotNull Entity.OverlayLayer pass(@NotNull EntityPose pose) {
        return RegistrarFixtures.overlay(new EntityMesh(), pose);
    }

    /**
     * A row posed by {@code body} holding a fixed block and a selectable one, drawing a pass sharing
     * its pose ahead of the given one.
     */
    private static @NotNull Entity holder(@NotNull EntityPose body, @NotNull Entity.OverlayLayer apart) {
        return Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(new EntityMesh())
            .axes(CompilerFixtures.noAxes())
            .pose(body)
            .overlays(Concurrent.newUnmodifiableList(pass(body), apart))
            .blockOverlays(Concurrent.newUnmodifiableList(
                new Entity.BlockOverlayLayer("minecraft:carved_pumpkin", null, Matrix4f.IDENTITY, false),
                new Entity.BlockOverlayLayer("", "right_arm", Matrix4f.IDENTITY, true)))
            .build();
    }

    /** The row re-posed by {@code wearer}, as the index builds a carried or an equipment form. */
    private static @NotNull Entity reposed(@NotNull Entity row, @NotNull EntityPose wearer) {
        return row.mutate()
            .pose(wearer)
            .overlays(row.overlays().stream()
                .map(pass -> pass.pose() == row.pose() ? pass.withPose(wearer) : pass)
                .collect(Concurrent.toUnmodifiableList()))
            .build();
    }

    /** The row carrying {@code large} as its large shape form. */
    private static @NotNull Entity withLargeShape(@NotNull Entity row, @NotNull Entity large) {
        Entity.Axes axes = row.axes();
        return row.mutate()
            .axes(new Entity.Axes(axes.baby(), new Entity.Variation<>(
                Concurrent.newUnmodifiableMap(Map.of(Entity.SHAPE_LARGE, large)), Optional.empty()),
                axes.state(), axes.size(), axes.variant(), axes.carried(), axes.equipment()))
            .build();
    }

    /** The row carrying the given carried and equipment forms. */
    private static @NotNull Entity withForms(@NotNull Entity row, @NotNull Optional<Entity> carried,
                                             @NotNull Entity.Variation<String, Entity> equipment) {
        Entity.Axes axes = row.axes();
        return row.mutate()
            .axes(new Entity.Axes(axes.baby(), axes.shape(), axes.state(), axes.size(), axes.variant(), carried,
                equipment))
            .build();
    }

    /**
     * The block ids an appearance draws over one definition, in overlay order.
     */
    private static @NotNull List<String> blockIds(@NotNull AppearanceOptions appearance, @NotNull Entity entity) {
        return appearance.resolve(entity).blockOverlays().stream()
            .map(Entity.BlockOverlayLayer::blockId)
            .toList();
    }

}
