package lib.minecraft.renderer.asset.equipment;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.face.HumanoidPart;
import lib.minecraft.renderer.face.Turn;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.option.spec.ArmorSlot;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * One box a slot's armour draws, resolved ahead of the render into the frame its own producer works
 * in.
 *
 * <p>A row answers three questions and nothing else: which slots draw it, what box it occupies once a
 * slot's deformation is applied, and where it reads its faces from on whichever sheet it is textured
 * with. That is the whole of what an armour build needs, so both builds walk a flat list of rows and
 * ask each one, rather than one of them walking a bone hierarchy while the other walks a part table.
 *
 * <p><b>The two arms share the shape of the data and never the arithmetic.</b> A worn shell's box is
 * its cube's, grown by the slot's deformation summed with the cube's own and taken through its bone's
 * scale; the player's is its own body box inflated by a scalar the slot is calibrated for in the skin
 * renderer's frame. Neither is the other in different units - {@code 0.015} is half a Minecraft pixel
 * in the body scopes and a fifth of that in the skull scope, whose frame is a different scale
 * entirely - so each arm keeps its own expression and the interface unifies only what they are asked.
 */
public sealed interface ShellPart {

    /**
     * The name this row traces under in the per-pixel dump.
     *
     * @return the trace name
     */
    @NotNull String trace();

    /**
     * The slots whose armour draws this row.
     *
     * @return the covering slots
     */
    @NotNull Set<ArmorSlot> slots();

    /**
     * This row's box as one slot wears it, in the frame the row's own producer works in.
     *
     * @param slot the armour slot
     * @return the grown box
     */
    @NotNull Box boxFor(@NotNull ArmorSlot slot);

    /**
     * Where this row reads each of its faces from on a sheet.
     *
     * @param sheet the armour or trim sheet to read
     * @return this row's per-face texture supplier against that sheet
     */
    @NotNull FaceTextures textures(@NotNull PixelBuffer sheet);

    /**
     * Whether a slot's armour draws this row.
     *
     * @param slot the armour slot
     * @return {@code true} when that slot's armour draws it
     */
    default boolean coveredBy(@NotNull ArmorSlot slot) {
        return slots().contains(slot);
    }

    /**
     * One cube of a worn armour shell, resolved into the shell's own frame when the shell is indexed.
     *
     * <p><b>The row carries operands, not a finished box, and that is arithmetic rather than taste.</b>
     * The upper corner is {@code ((origin - g) + size) + g + g}, which does not separate into a box plus
     * an expansion because float addition does not associate. What can be resolved ahead of the slot is
     * what rides here: the anchored, scaled {@link #origin}, the scaled {@link #size}, and both of the
     * growths a slot picks between - each the shell's layer deformation summed with this cube's own and
     * taken through its bone's scale, in that order.
     *
     * @param trace the shell bone this cube belongs to
     * @param unwrap where the cube reads its faces from on the shell's sheet
     * @param slots the slots whose armour draws this cube
     * @param origin the cube's lower corner in shell space, its bone's anchor plus its own scaled origin
     * @param size the cube's extent, scaled by its bone
     * @param innerGrowth the growth this cube takes on the innermost armour layer
     * @param outerGrowth the growth this cube takes on the outer armour layer
     */
    record Mesh(
        @NotNull String trace,
        @NotNull Unwrap.Atlas unwrap,
        @NotNull Set<ArmorSlot> slots,
        @NotNull Vector3f origin,
        @NotNull Vector3f size,
        @NotNull Vector3f innerGrowth,
        @NotNull Vector3f outerGrowth
    ) implements ShellPart {

        /**
         * The frame a shell's unwrap is authored in, relative to the upright frame its boxes are built
         * in. A shell states its strips in vanilla's Y-down model frame, so a render-frame face reads
         * its texture through the face this turn maps it onto - up with down and north with south
         * swapped, the two sides left where they are.
         */
        private static final @NotNull Turn MODEL_FRAME = Turn.HALF_X;

        /** {@inheritDoc} */
        @Override
        public @NotNull Box boxFor(@NotNull ArmorSlot slot) {
            Vector3f growth = slot.onLayer(this.innerGrowth, this.outerGrowth);
            Vector3f min = this.origin.subtract(growth);
            Vector3f max = min.add(this.size).add(growth).add(growth);
            return Box.of(min, max);
        }

        /**
         * {@inheritDoc}
         *
         * <p>The unwrap is the cube's rather than a table's because a shell states where each of its
         * boxes sits on its sheet, and the two shells state different things - the baby's head is a
         * nine-wide box at the sheet's origin where the adult's is eight-wide, and its feet and waist
         * have no counterpart in the skin layout at all. On the adult shell the two agree box for box,
         * the helmet's second box included: that shell IS the skin unwrap, mirrored left limbs and all.
         */
        @Override
        public @NotNull FaceTextures textures(@NotNull PixelBuffer sheet) {
            return face -> this.unwrap.crop(sheet, MODEL_FRAME.apply(face));
        }
    }

    /**
     * One box of the player's own body, dressed in the adult shell's answers rather than in its boxes.
     *
     * <p>The player is not handed a shell mesh - its armour is its own six body boxes inflated by the
     * amount each slot is calibrated for in the skin renderer's normalized frame, and textured through
     * the part's own skin rectangles. The second box a helmet draws is a row of its own here, reading
     * the part's <em>overlay</em> rectangle at the slot's second inflation, so it is a peer of the layer
     * box rather than a branch inside it.
     *
     * @param part the body part this box dresses
     * @param overlay whether this is the second box a slot draws over the part rather than the layer box
     * @param slots the slots whose armour draws this box
     * @param bounds the part's box in the render scope's own frame
     */
    record Body(
        @NotNull HumanoidPart part,
        boolean overlay,
        @NotNull Set<ArmorSlot> slots,
        @NotNull Box bounds
    ) implements ShellPart {

        /** {@inheritDoc} */
        @Override
        public @NotNull String trace() {
            return this.part.name().toLowerCase(Locale.ROOT) + (this.overlay ? "_overlay" : "");
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Box boxFor(@NotNull ArmorSlot slot) {
            return this.bounds.expand(this.overlay ? slot.skinOverlayInflate() : slot.skinInflate());
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull FaceTextures textures(@NotNull PixelBuffer sheet) {
            return this.part.textures(sheet, this.overlay);
        }
    }

}
