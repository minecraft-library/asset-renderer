package lib.minecraft.renderer.compose;

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
 * built only from built-in slots reproduces the renderer's historic pass sequence exactly.
 *
 * @param <L> the layer type held by this stack ({@link ImageLayer} or {@link GeometryLayer})
 */
public final class LayerStack<L> {

    private record Entry<L>(double key, int seq, @NotNull L layer) {}

    private final @NotNull List<Entry<L>> entries = new ArrayList<>();
    private int seq;

    /**
     * Adds a layer at the given slot's render order.
     *
     * @param slot the slot whose order this layer takes
     * @param layer the layer to add
     * @return this stack, for chaining
     */
    public @NotNull LayerStack<L> append(@NotNull LayerSlot slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.order(), this.seq++, layer));
        return this;
    }

    /**
     * Adds a layer that renders just before the given slot.
     *
     * @param slot the slot to render before
     * @param layer the layer to add
     * @return this stack, for chaining
     */
    public @NotNull LayerStack<L> addBefore(@NotNull LayerSlot slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.order() - 0.5, this.seq++, layer));
        return this;
    }

    /**
     * Adds a layer that renders just after the given slot.
     *
     * @param slot the slot to render after
     * @param layer the layer to add
     * @return this stack, for chaining
     */
    public @NotNull LayerStack<L> addAfter(@NotNull LayerSlot slot, @NotNull L layer) {
        this.entries.add(new Entry<>(slot.order() + 0.5, this.seq++, layer));
        return this;
    }

    /**
     * Returns the layers flattened into render order: by slot order, then insertion order for ties.
     *
     * @return the ordered layers
     */
    public @NotNull List<L> ordered() {
        List<Entry<L>> sorted = new ArrayList<>(this.entries);
        sorted.sort(Comparator.<Entry<L>>comparingDouble(Entry::key).thenComparingInt(Entry::seq));
        List<L> result = new ArrayList<>(sorted.size());
        for (Entry<L> entry : sorted) result.add(entry.layer());
        return result;
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
