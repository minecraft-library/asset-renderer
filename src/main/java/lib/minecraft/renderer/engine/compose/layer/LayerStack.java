package lib.minecraft.renderer.engine.compose.layer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ordered, slot-keyed collection of render layers.
 * <p>
 * Built-in layers are added under a {@link LayerSlot} that fixes their default render order;
 * consumer code can splice extra layers relative to a known slot with {@link #addBefore} /
 * {@link #addAfter}, or append after every slot with {@link #append}. {@link #ordered()} flattens
 * the stack into render order: by slot order first, then by insertion order for ties, so a stack
 * built only from built-in slots follows the renderer's default pass sequence.
 *
 * @param <L> the layer type held by this stack ({@link ImageLayer} or {@link GeometryLayer})
 */
public final class LayerStack<L> {

    /**
     * One stored layer and its sort keys.
     *
     * @param <L> the layer type
     * @param key the primary render-order key (the slot {@linkplain Enum#ordinal() ordinal}, offset by
     *        {@code -0.5} / {@code +0.5} for before / after insertions)
     * @param seq the monotonic insertion sequence, breaking ties among equal keys
     * @param layer the stored layer
     */
    private record Entry<L>(double key, int seq, @NotNull L layer) {}

    /**
     * Backing entries in insertion order; render order is derived on demand by {@link #ordered()}.
     */
    private final @NotNull List<Entry<L>> entries = new ArrayList<>();

    /**
     * Monotonic counter stamped onto each entry to preserve insertion order as a tie-breaker.
     */
    private int seq;

    /**
     * Adds a layer at the given slot's render order.
     *
     * @param slot the slot whose order this layer takes
     * @param layer the layer to add
     * @param <S> the slot enum type
     * @return this stack, for chaining
     */
    public @NotNull <S extends Enum<S> & LayerSlot> LayerStack<L> append(@NotNull S slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.ordinal(), this.seq++, layer));
        return this;
    }

    /**
     * Adds a layer that renders just before the given slot.
     *
     * @param slot the slot to render before
     * @param layer the layer to add
     * @param <S> the slot enum type
     * @return this stack, for chaining
     */
    public @NotNull <S extends Enum<S> & LayerSlot> LayerStack<L> addBefore(@NotNull S slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.ordinal() - 0.5, this.seq++, layer));
        return this;
    }

    /**
     * Adds a layer that renders just after the given slot.
     *
     * @param slot the slot to render after
     * @param layer the layer to add
     * @param <S> the slot enum type
     * @return this stack, for chaining
     */
    public @NotNull <S extends Enum<S> & LayerSlot> LayerStack<L> addAfter(@NotNull S slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.ordinal() + 0.5, this.seq++, layer));
        return this;
    }

    /**
     * Returns the layers flattened into render order: by slot order, then insertion order for ties.
     *
     * @return the ordered layers
     */
    public @NotNull ConcurrentList<L> ordered() {
        return this.entries.stream()
            .sorted(Comparator.<Entry<L>>comparingDouble(Entry::key).thenComparingInt(Entry::seq))
            .map(Entry::layer)
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Returns whether this stack holds no layers.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * Returns the number of layers in this stack.
     *
     * @return the layer count
     */
    public int size() {
        return this.entries.size();
    }

}
