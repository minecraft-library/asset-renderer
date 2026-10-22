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
 * <p>A selected carried block does more than draw: it swaps in the pose its row names for the wearer,
 * and it admits the {@code carried} gate a style source carries, so neither happens while the
 * selection is unset or {@code none}.
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
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
                Entity.Variation.none(), Entity.Variation.none()))
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
    @DisplayName("a selected carried block swaps in its row's wearer pose, and every pass sharing the body's pose follows it")
    void aSelectedCarriedBlockSwapsItsWearerPose() {
        EntityPose body = pose(0f);
        EntityPose raised = pose(-0.5f);
        EntityPose other = pose(0.25f);
        Entity.OverlayLayer eyes = pass(body);
        Entity.OverlayLayer apart = pass(other);
        Entity carrier = Entity.builder()
            .id(ResourceId.parse("minecraft:test"))
            .model(new EntityMesh())
            .axes(new Entity.Axes(Optional.empty(), Entity.Variation.none(), Entity.Variation.none(),
                Entity.Variation.none(), Entity.Variation.none()))
            .pose(body)
            .overlays(Concurrent.newUnmodifiableList(eyes, apart))
            .blockOverlays(Concurrent.newUnmodifiableList(
                new Entity.BlockOverlayLayer("minecraft:carved_pumpkin", null, Matrix4f.IDENTITY, false),
                new Entity.BlockOverlayLayer("", "right_arm", Matrix4f.IDENTITY, true, Optional.of(raised))))
            .build();

        Entity held = AppearanceOptions.builder().carried("minecraft:grass_block").build().resolve(carrier);
        assertSame(raised, held.pose(), "a selected block swaps the wearer pose in for the body's");
        assertSame(raised, held.overlays().getFirst().pose(), "a pass sharing the body's pose follows it");
        assertSame(other, held.overlays().getLast().pose(), "a pass posed apart keeps its own");
        assertEquals(Optional.of(raised), held.blockOverlays().getLast().wearerPose(),
            "the selected row keeps its wearer pose through the block id it draws");

        for (AppearanceOptions empty : List.of(AppearanceOptions.builder().build(),
            AppearanceOptions.builder().carried("none").build())) {
            Entity resolved = empty.resolve(carrier);
            assertSame(body, resolved.pose(), "with no block held the body keeps its own pose");
            assertSame(body, resolved.overlays().getFirst().pose(), "and so does the pass sharing it");
        }
    }

    @Test
    @DisplayName("the carried token admits a gated style source exactly while a block is selected")
    void theCarriedTokenAdmitsWhileABlockIsSelected() {
        assertTrue(AppearanceOptions.builder().carried("minecraft:poppy").build().admits("carried"),
            "a selected block admits what only a held block moves");
        assertFalse(AppearanceOptions.builder().build().admits("carried"),
            "an unset selection holds no block");
        assertFalse(AppearanceOptions.builder().carried("none").build().admits("carried"),
            "none holds no block either");
        assertTrue(AppearanceOptions.builder().charged(true).build().admits("charged"),
            "a flag token still admits through its flag");
        assertFalse(AppearanceOptions.builder().carried("minecraft:poppy").build().admits("charged"),
            "a held block admits no other token");
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
            Optional.empty(), Optional.empty());

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
        return new Entity.OverlayLayer(new EntityMesh(), Optional.empty(), PassDeclaration.DEFAULT, 0xFFFFFFFF, false,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), pose);
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
