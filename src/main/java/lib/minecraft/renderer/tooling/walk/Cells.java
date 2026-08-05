package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Declared cells for the multi-cell folds - typed handles a walk site creates per invocation,
 * attaches with {@code feed}, reads from recognizer hooks, and lets the engine reset at commit.
 * A hook writes no walk state outside the chain's own cells; a cell deliberately left live
 * across commits is declared with {@code clearing(subset)} on the commit rather than managed by
 * hand.
 */
public final class Cells {

    private Cells() {}

    /** What every cell shares: the engine-owned clear and an optional decode-consume face. */
    public abstract static sealed class Cell<G> permits Window, ListCell, Latch, Flag, Slots {

        /** Empties the cell. Engine-invoked at commit; callable from a hook that manages its own arming. */
        public abstract void clear();

        /**
         * The decode-consume face {@code feed} drives: a non-null decode is pushed into the
         * cell and consumes the element. Cells without a decoder answer {@code null} for every
         * node and are attach-only - feeding one registers it for the commit reset without
         * adding a consume stage.
         *
         * @param node the yielded instruction
         * @return the decoded value, or {@code null} when this node feeds nothing
         */
        @Nullable G decode(@NotNull AbstractInsnNode node) {
            return null;
        }

        /**
         * Accepts a decoded value. Only invoked with what {@link #decode} answered non-null.
         *
         * @param value the decoded value
         */
        void push(@NotNull G value) {
        }

        /** This cell's content, formatted for the state dump. */
        @NotNull String describe() {
            return getClass().getSimpleName();
        }

    }

    /**
     * A bounded FIFO of decoded values: push always, evict the oldest past capacity. The
     * two-deep sliding window is {@code window(dec, 2)} read positionally -
     * {@code values().getFirst()} is the older entry, {@code values().getLast()} the newer.
     */
    public static final class Window<G> extends Cell<G> {

        private final @NotNull Function<AbstractInsnNode, @Nullable G> decoder;
        private final int keep;
        private final @NotNull Deque<G> entries = new ArrayDeque<>();

        private Window(@NotNull Function<AbstractInsnNode, @Nullable G> decoder, int keep) {
            this.decoder = decoder;
            this.keep = keep;
        }

        @Override
        @Nullable G decode(@NotNull AbstractInsnNode node) {
            return this.decoder.apply(node);
        }

        @Override
        void push(@NotNull G value) {
            this.entries.addLast(value);
            if (this.entries.size() > this.keep)
                this.entries.removeFirst();
        }

        /** The retained values, oldest first. */
        public @NotNull List<G> values() {
            return List.copyOf(this.entries);
        }

        /** The current entry count. */
        public int size() {
            return this.entries.size();
        }

        @Override
        public void clear() {
            this.entries.clear();
        }

        @Override
        @NotNull String describe() {
            return "window=" + List.copyOf(this.entries);
        }

    }

    /** An ordered append list written from hooks. */
    public static final class ListCell<G> extends Cell<G> {

        private final @NotNull List<G> entries = new ArrayList<>();

        private ListCell() {}

        /**
         * Appends a value.
         *
         * @param value the value to append
         */
        public void add(@NotNull G value) {
            this.entries.add(value);
        }

        /** The appended values, in append order. */
        public @NotNull List<G> values() {
            return List.copyOf(this.entries);
        }

        @Override
        public void clear() {
            this.entries.clear();
        }

        @Override
        @NotNull String describe() {
            return "list=" + List.copyOf(this.entries);
        }

    }

    /** A single sticky value written from hooks - last write wins until cleared. */
    public static final class Latch<G> extends Cell<G> {

        private @Nullable G value;

        private Latch() {}

        /**
         * Latches a value, replacing any prior one.
         *
         * @param value the value to hold
         */
        public void set(@NotNull G value) {
            this.value = value;
        }

        /** The held value, or {@code null} when unset. */
        public @Nullable G get() {
            return this.value;
        }

        @Override
        public void clear() {
            this.value = null;
        }

        @Override
        @NotNull String describe() {
            return "latch=" + this.value;
        }

    }

    /** A boolean latch - unset until raised, cleared by the engine like any cell. */
    public static final class Flag extends Cell<Boolean> {

        private boolean raised;

        private Flag() {}

        /** Raises the flag. */
        public void set() {
            this.raised = true;
        }

        /** Whether the flag is raised. */
        public boolean get() {
            return this.raised;
        }

        @Override
        public void clear() {
            this.raised = false;
        }

        @Override
        @NotNull String describe() {
            return "flag=" + this.raised;
        }

    }

    /**
     * A local-variable slot table for the store-then-load protocol: a value is produced, parked
     * in a slot on the store, and read back at the load once the argument it belongs to is
     * finally pushed. Driven explicitly from the site's recognizer hooks.
     */
    public static final class Slots<T> extends Cell<T> {

        private final @NotNull Map<Integer, T> bindings = new LinkedHashMap<>();

        private Slots() {}

        /**
         * Binds a value to a slot, replacing any previous binding.
         *
         * @param slot the local-variable slot index
         * @param value the value to bind
         */
        public void store(int slot, @NotNull T value) {
            this.bindings.put(slot, value);
        }

        /**
         * The value bound to a slot, or {@code null} when unbound.
         *
         * @param slot the local-variable slot index
         * @return the bound value, or {@code null}
         */
        public @Nullable T load(int slot) {
            return this.bindings.get(slot);
        }

        /**
         * Clears specific slots, leaving the rest bound.
         *
         * @param slots the slot indices to unbind
         */
        public void clear(int @NotNull ... slots) {
            for (int slot : slots) this.bindings.remove(slot);
        }

        @Override
        public void clear() {
            this.bindings.clear();
        }

        @Override
        @NotNull String describe() {
            return "slots=" + this.bindings;
        }

    }

    /**
     * Creates a bounded decode window.
     *
     * @param decoder the per-node decoder; a non-null answer is pushed and consumes the node
     * @param keep the retention capacity; overflow evicts the oldest
     * @return the window cell
     */
    public static <G> @NotNull Window<G> window(@NotNull Function<AbstractInsnNode, @Nullable G> decoder, int keep) {
        return new Window<>(decoder, keep);
    }

    /**
     * Creates an ordered append list.
     *
     * @return the list cell
     */
    public static <G> @NotNull ListCell<G> list() {
        return new ListCell<>();
    }

    /**
     * Creates a single-value latch.
     *
     * @return the latch cell
     */
    public static <G> @NotNull Latch<G> latch() {
        return new Latch<>();
    }

    /**
     * Creates a boolean flag.
     *
     * @return the flag cell
     */
    public static @NotNull Flag flag() {
        return new Flag();
    }

    /**
     * Creates a slot table.
     *
     * @return the slots cell
     */
    public static <T> @NotNull Slots<T> slots() {
        return new Slots<>();
    }

}
