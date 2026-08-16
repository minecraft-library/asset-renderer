package lib.minecraft.renderer.tooling.walk;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The stream of commit events a fold emits - one event per commit, carrying the committing
 * node and the payload the cells held at that moment. A find terminal here halts at the first
 * commit event whatever its payload, exactly where the replaced break stopped; a probe belongs
 * outside that halt decision.
 *
 * @param <C> the commit node type
 * @param <G> the folded value type
 */
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class CommitWalk<C extends AbstractInsnNode, G> {

    /**
     * One commit event. A gather chain's payload is the buffered list; a latch chain's is the
     * single held value. Both faces are always answerable - {@link #values()} on a latch chain
     * is the value as a unit list, {@link #value()} on a gather chain is the newest buffered
     * entry - and the post-commit usage convention makes the intended face statically known at
     * each site.
     */
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Commit<C extends AbstractInsnNode, G> {

        private final @NotNull AbstractInsnNode node;
        private final @NotNull List<Object> values;
        private final @Nullable Object single;

        static @NotNull Commit<?, ?> gathered(@NotNull AbstractInsnNode node, @NotNull List<Object> values) {
            return new Commit<>(node, values, values.isEmpty() ? null : values.getLast());
        }

        static @NotNull Commit<?, ?> latched(@NotNull AbstractInsnNode node, @Nullable Object single) {
            return new Commit<>(node, single == null ? List.of() : List.of(single), single);
        }

        /** The committing node. */
        @SuppressWarnings("unchecked")
        public @NotNull C node() {
            return (C) this.node;
        }

        /** The payload as a list - the gathered buffer, oldest first. */
        @SuppressWarnings("unchecked")
        public @NotNull List<G> values() {
            return (List<G>) this.values;
        }

        /** The payload as a single value - the latched or promoted one, or {@code null} when nothing was held. */
        @SuppressWarnings("unchecked")
        public @Nullable G value() {
            return (G) this.single;
        }

    }

    private final @NotNull Descriptor descriptor;

    private @NotNull Walk<Commit<C, G>> events() {
        return new Walk<>(this.descriptor);
    }

    /**
     * The first commit event, or {@code null} when the walk commits nothing. The walk halts at
     * the event itself, whatever the payload holds.
     *
     * @return the first commit event, or {@code null}
     */
    public @Nullable Commit<C, G> first() {
        return events().first();
    }

    /**
     * The first non-null probe answer over the commit events.
     *
     * @param probe the per-event probe
     * @return the first non-null probe answer, or {@code null}
     */
    public <R> @Nullable R firstNotNull(@NotNull Function<? super Commit<C, G>, @Nullable R> probe) {
        return events().firstNotNull(probe);
    }

    /**
     * Whether the walk commits at all.
     *
     * @return {@code true} when at least one commit event is emitted
     */
    public boolean any() {
        return events().any();
    }

    /**
     * The number of commit events.
     *
     * @return the event count
     */
    public long count() {
        return events().count();
    }

    /**
     * Projects each commit event through the function.
     *
     * @param fn the projection; a {@code null} answer drops the event
     * @return the projected walk
     */
    public <R> @NotNull Walk<R> map(@NotNull Function<? super Commit<C, G>, ? extends R> fn) {
        return events().map(fn);
    }

    /**
     * Projects each commit event, dropping the ones the function answers {@code null} for.
     *
     * @param fn the partial projection
     * @return the projected walk
     */
    public <R> @NotNull Walk<R> mapNotNull(@NotNull Function<? super Commit<C, G>, @Nullable R> fn) {
        return events().mapNotNull(fn);
    }

    /**
     * Collects the commit events in encounter order, a later key overwriting an earlier one.
     * An event whose key or value projects to {@code null} is skipped - the reset still ran.
     *
     * @param keyFn the key projection over the commit node
     * @param valueFn the value projection over the payload
     * @return the collected map; empty, never {@code null}
     */
    public <K, V> @NotNull Map<K, V> toMap(@NotNull Function<? super C, @Nullable K> keyFn, @NotNull Function<? super List<G>, @Nullable V> valueFn) {
        return toMap(keyFn, (node, values) -> valueFn.apply(values));
    }

    /**
     * Collects the commit events with the value projected from both the commit node and the
     * payload - for the site whose fallback reads the node when the payload is empty.
     *
     * @param keyFn the key projection over the commit node
     * @param valueFn the value projection over node and payload
     * @return the collected map; empty, never {@code null}
     */
    public <K, V> @NotNull Map<K, V> toMap(@NotNull Function<? super C, @Nullable K> keyFn, @NotNull BiFunction<? super C, ? super List<G>, @Nullable V> valueFn) {
        Map<K, V> out = new LinkedHashMap<>();
        events().forEach(event -> {
            K key = keyFn.apply(event.node());
            V value = key == null ? null : valueFn.apply(event.node(), event.values());
            if (key != null && value != null) out.put(key, value);
        });
        return out;
    }

    /**
     * Runs the action for every commit event and answers how the walk ended. The action sees
     * the payload as committed - a held {@code null} arrives as a {@code null}
     * {@link Commit#value()}, so a memo write stays a real write.
     *
     * @param action the per-event action
     * @return how the walk ended
     */
    public @NotNull Exit forEach(@NotNull Consumer<? super Commit<C, G>> action) {
        return events().forEach(action);
    }

    /**
     * Which half of a looked-up source is absent, or {@code null} when the source resolved.
     *
     * @return the missing half, or {@code null}
     */
    public @Nullable Missing missing() {
        return this.descriptor.source.missing();
    }

}
