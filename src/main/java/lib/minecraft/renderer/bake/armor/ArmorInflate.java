package lib.minecraft.renderer.bake.armor;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import org.jetbrains.annotations.NotNull;

/**
 * The per-side inflations a worn box sits at in the skin renderer's normalized frame, keyed by the
 * {@link ArmorSlot slot} that wears it. The figures are calibration of the skin composite rather than
 * vocabulary of the armor, which is why they answer a slot rather than sit on one.
 */
@UtilityClass
@Parity(claim = "asset-layer")
@Parity(claim = "option-surface")
public class ArmorInflate {

    /**
     * Per-side inflation in the skin renderer's normalized frame for the layer-1 pieces, so armor sits
     * visibly above the skin geometry. It is not a Minecraft-pixel figure and does not convert into
     * one: the same constant is half a pixel in the full-body and bust scopes and a fifth of that in
     * the skull scope, whose frame is a different scale entirely.
     */
    private static final float ARMOR_INFLATE = 0.015f;

    /**
     * Per-side inflation for the layer-2 leggings. Smaller than {@link #ARMOR_INFLATE} so the leggings
     * sit <em>inside</em> the chestplate on the torso and inside the boots on the lower legs -
     * mirroring vanilla's armor layers. Without the inset the two coplanar torso cubes z-fight and the
     * leggings waist shows through the chestplate.
     */
    private static final float LEGGINGS_INFLATE = 0.008f;

    /**
     * Per-side inflation for the second box the helmet draws over the head - the shell's {@code hat}
     * cube, which carries a further {@code +0.5} {@code CubeDeformation} of its own on top of the
     * layer-1 deformation of {@code 1.0}. So the second box's total growth is one and a half times the
     * first's, and this figure is {@link #ARMOR_INFLATE} in that ratio rather than a second calibration.
     */
    private static final float HELMET_OVERLAY_INFLATE = ARMOR_INFLATE * 1.5f;

    /**
     * Reports the per-side inflation a slot applies to the player's own body boxes, in the skin
     * renderer's normalized frame.
     *
     * @param slot the slot whose armor draws the box
     * @return the inflation in model units
     */
    public static float skinInflate(@NotNull ArmorSlot slot) {
        return switch (slot) {
            case LEGGINGS -> LEGGINGS_INFLATE;
            case HELMET, CHESTPLATE, BOOTS -> ARMOR_INFLATE;
        };
    }

    /**
     * Reports the per-side inflation the second box a slot draws sits at, in the skin renderer's
     * normalized frame. Read only when {@link ArmorSlot#keepsChildren()} says the slot draws one, so
     * the three that do not answer with their own single-box inflation.
     *
     * @param slot the slot whose armor draws the second box
     * @return the second box's inflation in model units
     */
    public static float skinOverlayInflate(@NotNull ArmorSlot slot) {
        return switch (slot) {
            case HELMET -> HELMET_OVERLAY_INFLATE;
            case LEGGINGS, CHESTPLATE, BOOTS -> skinInflate(slot);
        };
    }

}
