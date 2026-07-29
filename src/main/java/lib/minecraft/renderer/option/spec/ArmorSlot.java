package lib.minecraft.renderer.option.spec;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * One of the four slots a humanoid wears armor in.
 * <p>
 * <b>Declaration order is the back-to-front composite order</b> and is the contract every compositor
 * reads: {@link #LEGGINGS} is vanilla armor layer 2, the innermost, so it is declared first and
 * iterating {@link #values()} paints it before the three layer-1 pieces. That is what puts the
 * chestplate over the leggings waist on the torso and the boots over the leggings on the lower legs.
 * <p>
 * The slot is a property of the armor rather than of the trim system - it selects which sheet a piece
 * is composited from, which parts of the shell it covers, and which of the shell's two deformations
 * it wears. The trim pattern texture it also names is one use among many.
 */
@Getter
@RequiredArgsConstructor
public enum ArmorSlot {

    /** Leggings - armor layer 2, painted first so layer-1 pieces composite over it. */
    LEGGINGS("leggings") {
        @Override
        public @NotNull Vector3f grow(@NotNull Entity.HumanoidArmor shell) {
            return shell.innerGrow();
        }

        @Override
        public float skinInflate() {
            return LEGGINGS_INFLATE;
        }
    },
    /** Helmet - armor layer 1. */
    HELMET("helmet") {
        @Override
        public boolean keepsChildren() {
            return true;
        }
    },
    /** Chestplate - armor layer 1, painted over the leggings waist on the torso. */
    CHESTPLATE("chestplate"),
    /** Boots - armor layer 1, painted over the leggings on the lower legs. */
    BOOTS("boots");

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
     * The vanilla slot name ({@code leggings} / {@code helmet} / {@code chestplate} / {@code boots}),
     * matching the item-trim path stem {@code trims/items/{key}_trim}. The armor compositors key off
     * the enum constant itself rather than this string; the item-icon trim overlay is its one reader.
     */
    private final @NotNull String key;

    /**
     * Which of a shell's two deformations this slot wears - the leggings the inner one, the other
     * three the outer. Vanilla registers an armor set with exactly two, so the choice is the slot's
     * rather than the shell's.
     *
     * @param shell the shell being worn
     * @return the per-side growth this slot applies to the shell's cubes
     */
    public @NotNull Vector3f grow(@NotNull Entity.HumanoidArmor shell) {
        return shell.outerGrow();
    }

    /**
     * The per-side inflation this slot applies to the player's own body boxes, in the skin renderer's
     * normalized frame.
     *
     * @return the inflation in model units
     */
    public float skinInflate() {
        return ARMOR_INFLATE;
    }

    /**
     * Whether this slot's armor covers the <em>children</em> of the parts it names as well as the
     * parts themselves. True for the helmet alone, which is what puts the head's overlay box on a
     * helmet and nothing else.
     *
     * @return {@code true} when a named part's descendants are covered too
     */
    public boolean keepsChildren() {
        return false;
    }

}
