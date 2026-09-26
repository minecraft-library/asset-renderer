package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.engine.draw.GeometryLayer;
import lib.minecraft.renderer.engine.draw.VisibleTriangle;
import lib.minecraft.renderer.engine.geometry.Box;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.ArmorPiece;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.request.PlayerOptions;
import lib.minecraft.renderer.slot.PlayerSlot3D;
import lib.minecraft.renderer.vanilla.equipment.ArmorForm;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Dresses a player in armour, in three dimensions over its own body boxes.
 * <p>
 * The player holds no {@link Shell}. Its rows are its own body boxes in the render scope's frame -
 * the frame they are drawn in - so nothing crosses a frame on the way, and it is always dressed in
 * {@link ArmorForm#ADULT}. {@link EntityArmorKit} is the other wearer and starts from a shell
 * instead; what the two share is in {@link ArmorKit}. The flat form of the same armour is
 * {@link PlayerSprite#compositeSlot2D}.
 * <p>
 * Armor pieces whose texture region is transparent (e.g. the head area of a leggings layer) produce
 * invisible geometry that the depth buffer or alpha compositing discards naturally.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class PlayerArmorKit {

    /**
     * Builds all armor and trim triangles for a humanoid body.
     *
     * @param bodyPositions map from body part to the box it is seated in
     * @param equipped the worn pieces keyed by slot; an unworn slot is absent
     * @param items the equipped item identity per slot, for the pack-rule (CIT) texture override; empty
     *     leaves each slot on its equipment-model texture
     * @param context the texture context for pack-aware texture resolution
     * @return the armor + trim triangles, empty when no armor is equipped
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildHumanoidArmor3D(
        @NotNull Map<HumanoidPart, Box> bodyPositions,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull RendererContext context
    ) {
        // The player's rows are its own body boxes in the render scope's frame, which is the frame they
        // are drawn in, so nothing crosses a frame on the way - and the player is always dressed adult.
        return ArmorKit.buildArmor3D(bodyRows(bodyPositions), UnaryOperator.identity(), ArmorForm.ADULT,
            equipped, items, context);
    }

    /**
     * The player's own body as armor rows - one per box a slot could draw over it, in the body's own
     * part order.
     *
     * <p>The player is dressed in the adult shell's answers rather than in its boxes: which slots reach
     * a part is that shell's part table read back through the body box each bone dresses, while the box
     * itself is the player's own, in the scope's frame. A part no slot reaches contributes no row.
     *
     * <p>The second box a helmet draws over the head is a row of its own here rather than a branch
     * inside the first, which is what makes it the peer of the layer box that the shell's {@code hat}
     * cube already is on the mesh path. It is emitted directly after the box it sits over, and it is
     * covered by exactly the slots that keep a named part's children - the helmet alone.
     */
    private static @NotNull List<WornBox> bodyRows(@NotNull Map<HumanoidPart, Box> bodyPositions) {
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
     * Appends the worn-armor layer for a player scope: the scope's own
     * {@link PlayerOptions.Type}'s {@code boxes} handed to {@link #buildHumanoidArmor3D}
     * with the four equipped slots. Shared by the SKULL / BUST / FULL 3D renderers so the append and
     * armor call live here once.
     *
     * @param stack the geometry layer stack to append the armor layer to
     * @param type the player render scope
     * @param options the render options carrying the equipped armor
     * @param context the renderer context the armour textures resolve through
     */
    public static void appendArmor(@NotNull LayerStack<GeometryLayer> stack, @NotNull PlayerOptions.Type type,
                                   @NotNull PlayerOptions options, @NotNull RendererContext context) {
        stack.append(PlayerSlot3D.ARMOR, sink -> sink.addAll(buildHumanoidArmor3D(
            type.lattice().boxes(), options.getArmor().equipped(),
            options.getArmor().getItems(), context)));
    }

}
