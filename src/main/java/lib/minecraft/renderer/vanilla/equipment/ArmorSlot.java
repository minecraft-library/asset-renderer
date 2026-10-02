package lib.minecraft.renderer.vanilla.equipment;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
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
@Parity(claim = "option-surface")
@EnumLookup
@Getter
@RequiredArgsConstructor
public enum ArmorSlot {

    /** Leggings - armor layer 2, painted first so layer-1 pieces composite over it. */
    LEGGINGS("leggings") {
        @Override
        public <T> @NotNull T onLayer(@NotNull T inner, @NotNull T outer) {
            return inner;
        }
    },
    /** Helmet - armor layer 1, and the one slot that draws a second box over the part it names. */
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
     * The vanilla slot name ({@code leggings} / {@code helmet} / {@code chestplate} / {@code boots}),
     * matching the item-trim path stem {@code trims/items/{key}_trim}. The armor compositors key off
     * the enum constant itself rather than this string; the item-icon trim overlay is its one reader.
     */
    private final @NotNull String key;

    /**
     * Whichever of a pair of per-layer values belongs to the armor layer this slot wears - the leggings
     * the inner one, the other three the outer. Vanilla registers an armor set with exactly two of
     * everything that varies by layer, so the choice is the slot's rather than the shell's, and it is
     * asked of whatever pair is in hand: a shell's own deformations, one of its cubes' growths with that
     * cube's deformation already summed in, or the equipment layer the slot's sheet is composited from.
     * <p>
     * Generic on purpose. The pairs are not all of one type, and the equipment layer is declared in a
     * package this one deliberately does not depend on - so a signature naming it would mint that edge
     * back, and an overload per type would restate one selection once per type it is asked about.
     *
     * @param <T> the type of the paired values
     * @param inner the innermost layer's value
     * @param outer the outer layer's value
     * @return whichever of the two this slot wears
     */
    public <T> @NotNull T onLayer(@NotNull T inner, @NotNull T outer) {
        return outer;
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
