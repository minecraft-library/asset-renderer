package lib.minecraft.renderer.engine.compose.layer;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage for {@link LayerStack}'s render-order keying - the arithmetic that decides which layer
 * contributes first.
 * <p>
 * The stored key is read straight off the slot: {@link LayerStack#append} keeps the slot's bare
 * {@linkplain Enum#ordinal() ordinal}, {@link LayerStack#addBefore} that ordinal minus one half and
 * {@link LayerStack#addAfter} that ordinal plus one half, with a stack-wide monotonic counter breaking
 * ties among equal keys. Order is load-bearing at every consumer - a {@link GeometryLayer} stack's
 * emission order decides coplanar depth tie-breaks and the translucent sort, an {@link ImageLayer}
 * stack's decides what gets painted over - so the keying is worth pinning to the ordinal rather than
 * to a reading of it.
 * <p>
 * Two consequences of that arithmetic are pinned here because nothing in the source states them. A
 * layer added with {@code append} lands <b>at</b> its slot, between anything spliced before that slot
 * and anything spliced after it, so {@code append} is not a way to reach the end of the stack. And
 * {@code addAfter(slot)} and {@code addBefore(next)} on two adjacent slots compute the identical
 * {@code double}, so those two spellings are indistinguishable, fall to insertion order, and leave no
 * key between them for a third layer to take.
 * <p>
 * {@link LayerStack}'s own two doc sentences disagree about the first of those: the class doc offers
 * {@code append} as the way to add "after every slot", while {@code append}'s own doc says it "adds a
 * layer at the given slot's render order". The code matches the method doc - it stores
 * {@code slot.ordinal()} unmodified - and every production caller uses it that way, building a stack
 * by adding each built-in layer under its own slot. These tests pin the code, which is the method
 * doc's reading.
 */
@DisplayName("LayerStack - render-order keying and insertion-order ties")
class LayerStackTest {

    @Test
    @DisplayName("append lands at the slot, between a before-splice and an after-splice on that same slot")
    void appendLandsAtTheSlotRatherThanAfterIt() {
        LayerStack<NamedLayer> stack = new LayerStack<>();
        stack.append(Slot.MIDDLE, new NamedLayer("at"));
        stack.addBefore(Slot.MIDDLE, new NamedLayer("before"));
        stack.addAfter(Slot.MIDDLE, new NamedLayer("after"));

        // Keys 1, 0.5 and 1.5. The appended layer takes the bare ordinal, so it sorts between its own
        // slot's two splices even though it was added first. An append that meant "after every slot"
        // would have put "at" last.
        assertThat(orderedNames(stack), is(List.of("before", "at", "after")));
    }

    @Test
    @DisplayName("an append under a later slot still follows a layer spliced after an earlier slot")
    void appendUnderALaterSlotFollowsAnEarlierAfterSplice() {
        LayerStack<NamedLayer> stack = new LayerStack<>();
        stack.append(Slot.TOP, new NamedLayer("top"));
        stack.addAfter(Slot.BASE, new NamedLayer("after-base"));
        stack.append(Slot.BASE, new NamedLayer("base"));

        // Keys 2, 0.5 and 0. The flattening is a total order over the keys and owes nothing to the
        // order the three calls were made in.
        assertThat(orderedNames(stack), is(List.of("base", "after-base", "top")));
    }

    @Test
    @DisplayName("layers sharing a key keep insertion order, counted stack-wide rather than per slot")
    void equalKeysKeepInsertionOrderAcrossSlots() {
        LayerStack<NamedLayer> stack = new LayerStack<>();
        stack.append(Slot.TOP, new NamedLayer("top-1"));
        stack.append(Slot.MIDDLE, new NamedLayer("middle-1"));
        stack.append(Slot.TOP, new NamedLayer("top-2"));
        stack.append(Slot.MIDDLE, new NamedLayer("middle-2"));

        // The tie-break counter is one field on the stack that every add increments, so the two TOP
        // layers hold 0 and 2 and still order correctly against each other across the interleave.
        assertThat(orderedNames(stack), is(List.of("middle-1", "middle-2", "top-1", "top-2")));
    }

    @Test
    @DisplayName("addAfter and addBefore on adjacent slots compute one key, so the spellings collide")
    void afterAndBeforeOnAdjacentSlotsCollide() {
        LayerStack<NamedLayer> afterFirst = new LayerStack<>();
        afterFirst.addAfter(Slot.BASE, new NamedLayer("after-base"));
        afterFirst.addBefore(Slot.MIDDLE, new NamedLayer("before-middle"));

        LayerStack<NamedLayer> beforeFirst = new LayerStack<>();
        beforeFirst.addBefore(Slot.MIDDLE, new NamedLayer("before-middle"));
        beforeFirst.addAfter(Slot.BASE, new NamedLayer("after-base"));

        // 0 + 0.5 and 1 - 0.5 are the same double exactly - both offsets are representable and the
        // ordinals are small integers - so nothing distinguishes the two spellings and the flattened
        // order is whichever was added first. A caller wanting to sit between a BASE after-splice and
        // a MIDDLE before-splice has no key to ask for.
        assertThat(orderedNames(afterFirst), is(List.of("after-base", "before-middle")));
        assertThat(orderedNames(beforeFirst), is(List.of("before-middle", "after-base")));
    }

    @Test
    @DisplayName("a splice before the first slot takes a negative key and renders ahead of everything")
    void spliceBeforeTheFirstSlotSortsAheadOfEveryAppend() {
        LayerStack<NamedLayer> stack = new LayerStack<>();
        stack.append(Slot.BASE, new NamedLayer("base"));
        stack.addBefore(Slot.BASE, new NamedLayer("pre"));

        // -0.5 is stored as it falls out of the arithmetic and is not clamped at zero, so the first
        // slot is spliceable in both directions like any other.
        assertThat(orderedNames(stack), is(List.of("pre", "base")));
    }

    /**
     * Flattens a stack and reads each layer's name, so an assertion states a render order rather than a
     * list of identities.
     *
     * @param stack the stack to flatten
     * @return the layer names in render order
     */
    private static @NotNull List<String> orderedNames(@NotNull LayerStack<NamedLayer> stack) {
        return stack.ordered().stream().map(NamedLayer::name).toList();
    }

    /**
     * Three adjacent slots, which is the fewest that can tell an {@code append} apart from a splice on
     * either side of it and still leave a neighbouring pair for the collision case.
     */
    private enum Slot implements LayerSlot {

        BASE,
        MIDDLE,
        TOP

    }

    /**
     * A layer identified only by its name.
     *
     * <p>{@link LayerStack} places its element type under no bound, so the stack's ordering owes nothing
     * to what a layer does and this fixture contributes nothing. {@link Layer#contribute} folds into a
     * shared accumulator that a pure ordering test has no reason to build.
     *
     * @param name the identity an ordering assertion reads
     */
    private record NamedLayer(@NotNull String name) {}

}
