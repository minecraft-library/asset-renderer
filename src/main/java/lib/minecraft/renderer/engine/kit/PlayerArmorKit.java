package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorSlot;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.equipment.ShellPart;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.tensor.Box;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Dresses a player in armour, in three dimensions over its own body boxes and in two over a canvas.
 * <p>
 * The player holds no {@link Shell}. Its rows are its own body boxes in the render scope's frame -
 * the frame they are drawn in - so nothing crosses a frame on the way, and it is always dressed in
 * {@link ArmorForm#ADULT}. {@link EntityArmorKit} is the other wearer and starts from a shell
 * instead; what the two share is in {@link ArmorKit}.
 * <p>
 * The armor texture is a 64x32 atlas whose UV layout matches the top half of the vanilla 64x64
 * player skin - the base layer plus the head's overlay, which the helmet's second box really does read
 * on both paths - so {@link HumanoidPart#textures(PixelBuffer, boolean) textures} and
 * {@link HumanoidPart#crop(PixelBuffer, Face, boolean) crop} work directly on the armor
 * texture. Armor pieces whose texture region is transparent (e.g. the head area of a leggings
 * layer) produce invisible geometry that the depth buffer or alpha compositing discards
 * naturally.
 */
@UtilityClass
public class PlayerArmorKit {

    // ---------------------------------------------------------------------------------------
    // 3D armor (triangles for ModelEngine rasterization).
    // ---------------------------------------------------------------------------------------

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
    private static @NotNull List<ShellPart> bodyRows(@NotNull Map<HumanoidPart, Box> bodyPositions) {
        List<ShellPart> rows = new ArrayList<>();

        for (HumanoidPart part : HumanoidPart.CACHED_VALUES) {
            Box bounds = bodyPositions.get(part);
            if (bounds == null) continue;
            Set<ArmorSlot> slots = ArmorForm.playerSlots(part);
            if (slots.isEmpty()) continue;

            rows.add(new ShellPart.Body(part, false, slots, bounds));

            EnumSet<ArmorSlot> second = EnumSet.noneOf(ArmorSlot.class);
            for (ArmorSlot slot : slots)
                if (slot.keepsChildren()) second.add(slot);
            if (!second.isEmpty())
                rows.add(new ShellPart.Body(part, true, Set.copyOf(second), bounds));
        }

        return rows;
    }

    // ---------------------------------------------------------------------------------------
    // 2D armor (composites south-facing face crops onto a canvas).
    // ---------------------------------------------------------------------------------------

    /**
     * Composites the 2D front-facing armor and trim sprites for one body part into the canvas rectangle
     * its layout row names. The slot determines whether to use the humanoid (layer 1) or
     * humanoid_leggings (layer 2) texture atlas.
     *
     * @param target the target buffer
     * @param row the body part whose south face to crop, and the canvas rectangle to blit it into
     * @param slot the armor slot that determines the texture layer
     * @param piece the armor piece to render
     * @param item the equipped item identity, for the pack-rule (CIT) texture override; empty leaves the
     *     slot on its equipment-model texture
     * @param context the texture context for pack-aware texture resolution
     */
    public static void compositeSlot2D(
        @NotNull PixelBuffer target,
        @NotNull PlayerOptions.Type.BodyPart2D row,
        @NotNull ArmorSlot slot,
        @NotNull ArmorPiece piece,
        @NotNull Optional<ItemContext> item,
        @NotNull RendererContext context
    ) {
        // The target buffer owns the coverage mask (enabled by the caller when the armor is enchanted);
        // stamp the armor / trim sprite coverage into it so the enchantment foil lands on the armor,
        // not the bare skin. Absent when the caller records no mask - then stampMaskScaled is a no-op.
        PixelMask mask = target.mask().orElse(null);
        Optional<PixelBuffer> armorTexture =
            ArmorKit.resolveArmorTexture(context, piece, ArmorForm.ADULT.layerType(slot), item);
        armorTexture.ifPresent(tex -> blit2D(target, mask, row, tex));

        piece.trim().ifPresent(trim -> ArmorForm.ADULT.trimLayer(slot)
            .flatMap(layer -> ArmorKit.resolveTrimTexture(context, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTex -> blit2D(target, mask, row, trimTex)));
    }

    /**
     * Crops one sheet's south face for a layout row, blits it into that row's rectangle and stamps the
     * same coverage into the glint mask. The armor sheet and the trim sheet are drawn this way in that
     * order, and the two passes differ in nothing but the sheet.
     */
    private static void blit2D(
        @NotNull PixelBuffer target, @Nullable PixelMask mask,
        @NotNull PlayerOptions.Type.BodyPart2D row, @NotNull PixelBuffer sheet) {
        PixelBuffer face = row.part().crop(sheet, Face.SOUTH, false);
        target.blitScaled(face, row.x(), row.y(), row.w(), row.h());
        stampMaskScaled(mask, face, row);
    }

    /**
     * Marks the glint mask over the destination rectangle wherever the scaled source {@code face}
     * has a non-transparent texel, mirroring {@code blitScaled}'s nearest-neighbour mapping. This is
     * the 2D analogue of the 3D rasterizer's per-pixel glint marking - it records exactly the armor /
     * trim coverage so the foil never lands on the bare skin underneath. No-op when {@code mask} is
     * {@code null}.
     */
    private static void stampMaskScaled(
        @Nullable PixelMask mask, @NotNull PixelBuffer face,
        @NotNull PlayerOptions.Type.BodyPart2D row) {
        if (mask == null) return;
        int fw = face.width();
        int fh = face.height();
        int w = row.w();
        int h = row.h();
        if (fw <= 0 || fh <= 0 || w <= 0 || h <= 0) return;
        for (int dy = 0; dy < h; dy++) {
            int sy = Math.min(fh - 1, dy * fh / h);
            for (int dx = 0; dx < w; dx++) {
                int sx = Math.min(fw - 1, dx * fw / w);
                if (ColorMath.alpha(face.getPixel(sx, sy)) != 0)
                    mask.mark(row.x() + dx, row.y() + dy);
            }
        }
    }

}
