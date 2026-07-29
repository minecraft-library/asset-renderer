package lib.minecraft.renderer.option.spec;

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
    LEGGINGS("leggings"),
    /** Helmet - armor layer 1. */
    HELMET("helmet"),
    /** Chestplate - armor layer 1, painted over the leggings waist on the torso. */
    CHESTPLATE("chestplate"),
    /** Boots - armor layer 1, painted over the leggings on the lower legs. */
    BOOTS("boots");

    /**
     * The vanilla slot name ({@code leggings} / {@code helmet} / {@code chestplate} / {@code boots}),
     * matching the item-trim path stem {@code trims/items/{key}_trim}. The armor compositors key off
     * the enum constant itself rather than this string; the item-icon trim overlay is its one reader.
     */
    private final @NotNull String key;

}
