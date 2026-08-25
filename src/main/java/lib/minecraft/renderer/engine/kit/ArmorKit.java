package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.asset.equipment.ArmorPiece;
import lib.minecraft.renderer.asset.equipment.ArmorSlot;
import lib.minecraft.renderer.asset.equipment.ArmorTrim;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.ShellPart;
import lib.minecraft.renderer.asset.pack.rule.CitResult;
import lib.minecraft.renderer.asset.pack.rule.ItemContext;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.tensor.Box;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

/**
 * What both wearers of armour share: the walk from a list of rows to textured triangles, and the
 * texture resolution under it.
 * <p>
 * The two wearers are {@link EntityArmorKit}, which starts from the
 * {@link lib.minecraft.renderer.asset.equipment.Shell Shell} an entity is dressed in, and
 * {@link PlayerArmorKit}, which holds no shell and dresses the player's own body boxes. They differ
 * in exactly three arguments at {@link #buildArmor3D}, and nothing below that call branches on which
 * of the two it is serving - which is why this is a kit of its own rather than a pair of arms inside
 * either.
 * <p>
 * Two texture layers correspond to the vanilla equipment paths:
 * <ul>
 * <li><b>Layer 1</b> ({@code entity/equipment/humanoid/{material}}) - helmet, chestplate,
 * arms, boots</li>
 * <li><b>Layer 2</b> ({@code entity/equipment/humanoid_leggings/{material}}) - leggings
 * (waist + legs)</li>
 * </ul>
 */
@UtilityClass
public class ArmorKit {

    /**
     * One armor box ready to build: the row it was resolved from, and its corners in the frame it is
     * drawn in. The row travels rather than a resolved crop because a slot's boxes are textured twice -
     * once from the armor sheet and once from the trim - and each crop is a read of the same row
     * against a different sheet.
     *
     * @param row the row the box was resolved from, which answers for its texture and its trace name
     * @param bounds the box in the frame it is drawn in
     */
    private record SlotBox(
        @NotNull ShellPart row,
        @NotNull Box bounds
    ) {}

    /**
     * Builds one wearer's armor from a flat list of rows: each equipped slot's rows of that list,
     * resolved into the frame they are drawn in and textured through the form's answers for that slot.
     *
     * <p>Both wearers reach this, and they differ in exactly three arguments.
     * {@link EntityArmorKit} hands a worn shell's own cubes, the crossing into the render frame, and
     * that shell's form; {@link PlayerArmorKit} hands the player's own body boxes, the identity, and
     * the adult form it is always dressed in. Nothing downstream of here branches on which of the two
     * it is serving.
     */
    static @NotNull ConcurrentList<VisibleTriangle> buildArmor3D(
        @NotNull List<ShellPart> rows,
        @NotNull UnaryOperator<Box> intoFrame,
        @NotNull ArmorForm form,
        @NotNull Map<ArmorSlot, ArmorPiece> equipped,
        @NotNull Map<ArmorSlot, ItemContext> items,
        @NotNull RendererContext context
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();

        for (Map.Entry<ArmorSlot, ArmorPiece> entry : inCompositeOrder(equipped).entrySet()) {
            ArmorSlot slot = entry.getKey();
            addSlot3D(triangles, resolveBoxes(rows, slot, intoFrame), form, slot, entry.getValue(),
                Optional.ofNullable(items.get(slot)), context);
        }

        return triangles;
    }

    /**
     * The rows one slot draws, each resolved to the box that slot wears it at and then into the frame
     * it is drawn in.
     *
     * <p>The slot picks which of the two deformations it wears - the leggings the inner one, the other
     * three the outer - and keeps only the rows vanilla keeps for it. A worn shell's row is grown by
     * that deformation plus its own {@code CubeDeformation.extend} (a leg's {@code -0.1}, a helmet's
     * second box), which is the sum vanilla's mesh builder performs in the same order; the player's is
     * its body box inflated by the scalar the slot is calibrated for. Row order is the list's own -
     * for a shell the mesh's bone order and then each bone's cube order, so a part and the overlay box
     * parented to it stay adjacent, which is what decides a coplanar tie.
     */
    private static @NotNull List<SlotBox> resolveBoxes(
        @NotNull List<ShellPart> rows, @NotNull ArmorSlot slot,
        @NotNull UnaryOperator<Box> intoFrame) {
        List<SlotBox> boxes = new ArrayList<>();

        for (ShellPart row : rows) {
            if (!row.coveredBy(slot)) continue;
            boxes.add(new SlotBox(row, intoFrame.apply(row.boxFor(slot))));
        }

        return boxes;
    }

    /**
     * The worn pieces in {@link ArmorSlot} declaration order, so the emission order is this kit's
     * rather than whatever iteration order the caller's map happens to have.
     */
    static @NotNull Map<ArmorSlot, ArmorPiece> inCompositeOrder(
        @NotNull Map<ArmorSlot, ArmorPiece> equipped) {
        Map<ArmorSlot, ArmorPiece> ordered = new EnumMap<>(ArmorSlot.class);
        ordered.putAll(equipped);
        return ordered;
    }

    /**
     * Adds one slot's armor around boxes already resolved into the frame they are drawn in, so nothing
     * is grown or inflated here.
     *
     * <p>The sheet and the trim atlas are the form's answers for the slot - a worn shell hands its own
     * form, and the player is dressed in the adult one - and every box is textured twice when the piece
     * carries a trim, once from each sheet in that order.
     */
    private static void addSlot3D(
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        @NotNull List<SlotBox> boxes,
        @NotNull ArmorForm form,
        @NotNull ArmorSlot slot,
        @NotNull ArmorPiece piece,
        @NotNull Optional<ItemContext> item,
        @NotNull RendererContext context
    ) {
        Optional<PixelBuffer> armorTexture =
            resolveArmorTexture(context, piece, form.layerType(slot), item);
        if (armorTexture.isEmpty()) return;

        for (SlotBox box : boxes)
            triangles.addAll(buildBox3D(box, armorTexture.get()));

        if (piece.trim().isEmpty()) return;
        ArmorTrim trim = piece.trim().get();
        form.trimLayer(slot)
            .flatMap(layer -> resolveTrimTexture(context, layer, trim.pattern(), trim.color()))
            .ifPresent(trimTexture -> {
                for (SlotBox box : boxes)
                    triangles.addAll(buildBox3D(box, trimTexture));
            });
    }

    /**
     * Builds one box of armor, read through its own row against the sheet it is textured with.
     *
     * <p>A worn shell's row reads its cube's own unwrap, turned into the model frame that unwrap
     * addresses; the player's reads its body part's own skin rectangles, which resolve that turn when
     * the part is declared. On the adult shell the two agree box for box, the helmet's second box
     * included: that shell IS the skin unwrap, mirrored left limbs and all.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> buildBox3D(
        @NotNull SlotBox box, @NotNull PixelBuffer texture) {
        return BlockGeometryKit.buildBox(
            box.bounds(), box.row().textures(texture), ColorMath.WHITE,
            SurfaceTraits.WORN_SHELL,
            RendererDebug.tracingPixels() ? box.row().trace() : null);
    }

    /**
     * Resolves the composited armor texture for a slot: the piece's {@link ArmorPiece#material()
     * material} asset composited under the given layer type by {@link EquipmentKit#composite}, dyed
     * by the piece's {@link ArmorPiece#dyeColor() dye}.
     *
     * <p>A present {@code item} first consults the pack-rule (CIT) override
     * ({@link RendererContext#resolveArmorTextureOverride}); a matching
     * rule replaces each layer's texture ({@code layer0} the base, {@code layerN} the overlays) before
     * the equipment-model path. On a vanilla stack with no item the override is {@link CitResult#NONE}
     * and every layer resolves through the model.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param piece the armor piece
     * @param layerType the equipment layer the slot maps to ({@link LayerType#HUMANOID} or
     *     {@link LayerType#HUMANOID_LEGGINGS})
     * @param item the equipped item identity, for the pack-rule override; empty leaves the layers on
     *     the equipment model
     * @return the composited texture, or empty when the asset ships no layers or none resolve
     */
    static @NotNull Optional<PixelBuffer> resolveArmorTexture(
        @NotNull RendererContext context,
        @NotNull ArmorPiece piece,
        @NotNull LayerType layerType,
        @NotNull Optional<ItemContext> item
    ) {
        CitResult cit = item
            .map(itemContext -> context.resolveArmorTextureOverride(piece.material(), layerType, itemContext))
            .orElse(CitResult.NONE);
        return EquipmentKit.composite(context, piece.material().assetId(), layerType,
            piece.dyeColor(), cit, OptionalInt.empty());
    }

    /**
     * Resolves and permutes a 3D entity-armor trim texture. Only the base pattern's id is this path's
     * own - the {@code trims/entity/{layer}} atlas ({@code humanoid} or {@code humanoid_leggings})
     * rather than {@link TrimKit}'s item-slot {@code trims/items/} stem - so it builds that and hands
     * the rest to the resolve both trim paths share.
     *
     * @param context the texture context for pack-aware texture resolution
     * @param layer the entity trim layer ({@code humanoid} or {@code humanoid_leggings})
     * @param pattern the trim pattern supplying the grayscale base texture key
     * @param color the trim material supplying the colour palette key
     * @return the permuted trim overlay, or empty when any of the three source textures is missing
     */
    static @NotNull Optional<PixelBuffer> resolveTrimTexture(
        @NotNull RendererContext context,
        @NotNull String layer,
        @NotNull ArmorTrim.Pattern pattern,
        @NotNull ArmorTrim.Color color
    ) {
        return TrimKit.permuteFrom(context,
            "minecraft:trims/entity/" + layer + "/" + pattern.getKey(), color.getKey());
    }

}
