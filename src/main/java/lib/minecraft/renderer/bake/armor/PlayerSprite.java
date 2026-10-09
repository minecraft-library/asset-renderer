package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.image.pixel.PixelMask;
import lib.minecraft.renderer.bake.armor.PlayerLayout2D.BodyPart2D;
import lib.minecraft.renderer.bake.mesh.PlayerAssembly;
import lib.minecraft.renderer.bake.texture.GlintKit;
import lib.minecraft.renderer.call.request.ArmorPiece;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.slot.PlayerSlot2D;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.frame.ImageLayer;
import lib.minecraft.renderer.engine.frame.RasterPass;
import lib.minecraft.renderer.engine.frame.Timeline;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.engine.layer.Layers;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.equipment.ArmorForm;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import lib.minecraft.renderer.vanilla.mesh.PlayerLattice;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * The flat player - the front-facing composite of a skin, its overlay and its armour onto a canvas.
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
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(claim = "player-geometry")
public class PlayerSprite {

    // ---------------------------------------------------------------------------------------
    // 2D helpers - composite front-facing body parts + armor onto a canvas.
    // ---------------------------------------------------------------------------------------

    /**
     * Blits one already-cropped face into the canvas rectangle its layout row names.
     */
    private static void blitPart(
        @NotNull PixelBuffer frame, @NotNull BodyPart2D row, @NotNull PixelBuffer face) {
        frame.blitScaled(face, row.x(), row.y(), row.w(), row.h());
    }

    /**
     * Renders a 2D front-facing composite for any body type.
     *
     * @param skin the resolved player skin sheet
     * @param options the render options
     * @param context the texture context for pack-aware texture resolution
     * @return the composited frame
     */
    public static @NotNull ImageData render2D(
        @NotNull PixelBuffer skin,
        @NotNull PlayerOptions options,
        @NotNull RendererContext context
    ) {
        int size = options.getOutput().getCanvasSize();

        ConcurrentList<BodyPart2D> parts = PlayerLayout2D.of(options.getType().lattice(), size);

        boolean overlay = options.getSkin().isRenderOverlay();
        boolean enchanted = options.getArmor().hasEnchanted();

        // Compose the front-facing body as an ordered ImageLayer stack folded into the raster target;
        // the pass records the single glint mask (recordMask = enchanted), which the ARMOR / trim
        // composites stamp their coverage into so the foil is confined to the armor (not the bare
        // skin). Body-part rectangles tile the canvas without overlap, so the per-pass order matches
        // the per-part draw order.
        return Timeline.Static.ZERO.bake(
            RasterPass.of(size, size, 1, options.getOutput().isAntiAlias(),
                (target, tick) -> {
                LayerStack<ImageLayer> stack = new LayerStack<>();
                stack.append(PlayerSlot2D.SKIN, frame -> {
                    for (BodyPart2D row : parts)
                        blitPart(frame, row, row.part().crop(skin, Face.SOUTH, false));
                });
                if (overlay)
                    stack.append(PlayerSlot2D.OVERLAY, frame -> {
                        // The head's hat layer is the one overlay a legacy sheet still carries, and it
                        // is drawn from the same rectangle at the same crop - so the wider test only
                        // decides whether the head is reached, never what it draws.
                        for (BodyPart2D row : parts)
                            if (PlayerAssembly.hasOverlay(skin)
                                || (row.part() == HumanoidPart.HEAD && PlayerAssembly.hasHatOverlay(skin)))
                                blitPart(frame, row, row.part().crop(skin, Face.SOUTH, true));
                    });
                stack.append(PlayerSlot2D.ARMOR, frame -> compositeArmor2D(frame, parts, options, context));
                Layers.foldInto(stack, options.getLayerDecorator(), target);
            })
                .withMask(enchanted)
                .finishing(GlintKit.Foil.armor(context, enchanted)));
    }

    /**
     * Composites the whole 2D armour pass - every equipped slot over every body part that slot covers,
     * in {@link ArmorSlot} declaration order.
     *
     * <p><b>The slot is the outer loop, and that is what makes the composite order unconditional.</b>
     * Iterating parts outermost also paints correctly, but only because the six part rectangles tile the
     * canvas without overlap: all fifteen pairs are disjoint, the head sitting above the torso and arms
     * on Y and the two legs beside each other on X. With the slot outermost a later slot paints over an
     * earlier one whatever the rectangles do, which is the contract {@link ArmorSlot}'s declaration
     * order states - layer-2 leggings first, so the chestplate wins on the torso and the boots on the
     * lower legs.
     *
     * <p>{@code equipped()} holds only worn pieces and iterates its {@code EnumMap} in ordinal - so
     * declaration - order, so no slot is tested for absence and none is drawn out of turn.
     */
    private static void compositeArmor2D(
        @NotNull PixelBuffer target,
        @NotNull ConcurrentList<BodyPart2D> parts,
        @NotNull PlayerOptions options,
        @NotNull RendererContext context
    ) {
        for (Map.Entry<ArmorSlot, ArmorPiece> entry : options.getArmor().equipped().entrySet()) {
            ArmorSlot slot = entry.getKey();
            Optional<ItemContext> item = Optional.ofNullable(options.getArmor().getItems().get(slot));

            for (BodyPart2D row : parts)
                if (PlayerLattice.playerSlots(row.part()).contains(slot))
                    compositeSlot2D(target, row, slot, entry.getValue(), item, context);
        }
    }

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
        @NotNull BodyPart2D row,
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
     * order, and the two passes differ in nothing but the sheet. The stand-in sprite is laid across the
     * equipment sheet's declared size before it is cropped, as the 3D path lays it.
     */
    private static void blit2D(
        @NotNull PixelBuffer target, @Nullable PixelMask mask,
        @NotNull BodyPart2D row, @NotNull PixelBuffer sheet) {
        PixelBuffer read = MissingSprite.stretchedTo(sheet, WornBox.Body.SHEET_WIDTH, WornBox.Body.SHEET_HEIGHT);
        PixelBuffer face = row.part().crop(read, Face.SOUTH, false);
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
        @NotNull BodyPart2D row) {
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
