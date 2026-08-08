package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A narrowed walk - the element type is pinned and the method set is filters, projections and
 * terminals. No fold stage exists at this tier, so nothing filterable can sit upstream of a
 * stateful cell; folds attach only at the {@link AsmWalker} root. Terminals are eager: scalar
 * misses answer {@code null}, containers answer empty, and the find family answers its ordinary
 * miss silently on a missing source where {@link #missing()} carries the reason.
 *
 * @param <T> the element type flowing to the terminals
 */
public sealed class Walk<T> permits AsmWalker {

    final @NotNull Descriptor descriptor;

    Walk(@NotNull Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    private <R> @NotNull Walk<R> narrowed(@NotNull Function<Object, @Nullable Object> step) {
        return new Walk<>(this.descriptor.with(new Descriptor.Narrow(step)));
    }

    /**
     * Keeps only the elements the predicate accepts.
     *
     * @param test the typed refinement
     * @return the filtered walk
     */
    @SuppressWarnings("unchecked")
    public @NotNull Walk<T> where(@NotNull Predicate<? super T> test) {
        return narrowed(v -> test.test((T) v) ? v : null);
    }

    /**
     * Projects each element through the function.
     *
     * @param fn the projection; a {@code null} answer drops the element
     * @return the projected walk
     */
    @SuppressWarnings("unchecked")
    public <R> @NotNull Walk<R> map(@NotNull Function<? super T, ? extends R> fn) {
        return narrowed(v -> fn.apply((T) v));
    }

    /**
     * Projects each element, dropping the ones the function answers {@code null} for.
     *
     * @param fn the partial projection
     * @return the projected walk
     */
    @SuppressWarnings("unchecked")
    public <R> @NotNull Walk<R> mapNotNull(@NotNull Function<? super T, @Nullable R> fn) {
        return narrowed(v -> fn.apply((T) v));
    }

    /**
     * Projects field and method instructions to their member name; every other element is
     * dropped. The extraction is by node shape at run time - the post-match usage convention
     * makes the arm statically known at each site.
     *
     * @return the name walk
     */
    public @NotNull Walk<String> names() {
        return narrowed(v -> switch (v) {
            case FieldInsnNode fi -> fi.name;
            case MethodInsnNode mi -> mi.name;
            default -> null;
        });
    }

    /**
     * Re-narrows to the given node class, dropping every other element.
     *
     * @param type the node class to narrow to
     * @return the narrowed walk
     */
    public <N extends AbstractInsnNode> @NotNull Walk<N> ofType(@NotNull Class<N> type) {
        return narrowed(v -> type.isInstance(v) ? v : null);
    }

    // ------------------------------------------------------------------------------------
    // find family - short-circuiting
    // ------------------------------------------------------------------------------------

    private static final class Capture {
        @Nullable Object value;
    }

    /**
     * The first element, or {@code null} when the walk yields none.
     *
     * @return the first element, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public @Nullable T first() {
        Capture capture = new Capture();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                capture.value = value;
                return Drive.Verdict.HALT;
            }
        });
        return (T) capture.value;
    }

    /**
     * The first element the predicate accepts, or {@code null} when none is.
     *
     * @param test the acceptance predicate
     * @return the first accepted element, or {@code null}
     */
    public @Nullable T first(@NotNull Predicate<? super T> test) {
        return where(test).first();
    }

    /**
     * The scoped search: each element is tested against {@code matcher} before {@code passthrough},
     * so a node can match even where the passthrough would refuse it. A node neither accepts is
     * the scope's edge - the walk aborts with {@code null}.
     *
     * @param matcher the acceptance predicate; its first hit is the answer
     * @param passthrough {@code true} to keep walking past a non-matching element
     * @return the first match inside the scope, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public @Nullable T first(@NotNull Predicate<? super T> matcher, @NotNull Predicate<? super T> passthrough) {
        Capture capture = new Capture();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                if (matcher.test((T) value)) {
                    capture.value = value;
                    return Drive.Verdict.HALT;
                }
                return passthrough.test((T) value) ? Drive.Verdict.CONTINUE : Drive.Verdict.HALT;
            }
        });
        return (T) capture.value;
    }

    /**
     * The first non-null probe answer, or {@code null} when every probe declines.
     *
     * @param probe the per-element probe
     * @return the first non-null probe answer, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <R> @Nullable R firstNotNull(@NotNull Function<? super T, @Nullable R> probe) {
        Capture capture = new Capture();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                R answer = probe.apply((T) value);
                if (answer == null) return Drive.Verdict.CONTINUE;
                capture.value = answer;
                return Drive.Verdict.HALT;
            }
        });
        return (R) capture.value;
    }

    /**
     * Whether the walk yields any element.
     *
     * @return {@code true} when at least one element survives
     */
    public boolean any() {
        return first() != null;
    }

    /**
     * Whether any element satisfies the predicate.
     *
     * @param test the acceptance predicate
     * @return {@code true} when at least one element is accepted
     */
    public boolean any(@NotNull Predicate<? super T> test) {
        return first(test) != null;
    }

    // ------------------------------------------------------------------------------------
    // total family - encounter order, run to the boundary
    // ------------------------------------------------------------------------------------

    /**
     * The last element before the walk's boundary - the overwrite-to-boundary read, total by
     * meaning and named plainly.
     *
     * @return the last element, or {@code null} when the walk yields none
     */
    @SuppressWarnings("unchecked")
    public @Nullable T last() {
        Capture capture = new Capture();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                capture.value = value;
                return Drive.Verdict.CONTINUE;
            }
        });
        return (T) capture.value;
    }

    /**
     * The number of elements the walk yields.
     *
     * @return the element count
     */
    public long count() {
        long[] count = {0L};
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                count[0]++;
                return Drive.Verdict.CONTINUE;
            }
        });
        return count[0];
    }

    /**
     * Every element, in encounter order.
     *
     * @return the elements; empty, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public @NotNull List<T> toList() {
        List<Object> out = new ArrayList<>();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                out.add(value);
                return Drive.Verdict.CONTINUE;
            }
        });
        return (List<T>) out;
    }

    /**
     * Every distinct element, in first-encounter order.
     *
     * @return the elements; empty, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public @NotNull Set<T> toSet() {
        Set<Object> out = new LinkedHashSet<>();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                out.add(value);
                return Drive.Verdict.CONTINUE;
            }
        });
        return (Set<T>) out;
    }

    /**
     * Collects entries in encounter order, a later key overwriting an earlier one. An entry
     * whose key or value projects to {@code null} is skipped.
     *
     * @param keyFn the key projection
     * @param valueFn the value projection
     * @return the collected map; empty, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public <K, V> @NotNull Map<K, V> toMap(@NotNull Function<? super T, @Nullable K> keyFn, @NotNull Function<? super T, @Nullable V> valueFn) {
        Map<K, V> out = new LinkedHashMap<>();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                K key = keyFn.apply((T) value);
                V mapped = key == null ? null : valueFn.apply((T) value);
                if (key != null && mapped != null) out.put(key, mapped);
                return Drive.Verdict.CONTINUE;
            }
        });
        return out;
    }

    /**
     * Collects entries in encounter order, the first binding of a key winning. An entry whose
     * key or value projects to {@code null} is skipped.
     *
     * @param keyFn the key projection
     * @param valueFn the value projection
     * @return the collected map; empty, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public <K, V> @NotNull Map<K, V> toMapFirstWins(@NotNull Function<? super T, @Nullable K> keyFn, @NotNull Function<? super T, @Nullable V> valueFn) {
        Map<K, V> out = new LinkedHashMap<>();
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                K key = keyFn.apply((T) value);
                V mapped = key == null ? null : valueFn.apply((T) value);
                if (key != null && mapped != null) out.putIfAbsent(key, mapped);
                return Drive.Verdict.CONTINUE;
            }
        });
        return out;
    }

    /**
     * Folds the elements strictly left to right.
     *
     * @param seed the initial accumulator
     * @param op the fold step
     * @return the final accumulator
     */
    @SuppressWarnings("unchecked")
    public <A> A reduce(A seed, @NotNull BiFunction<A, ? super T, A> op) {
        Object[] acc = {seed};
        Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                acc[0] = op.apply((A) acc[0], (T) value);
                return Drive.Verdict.CONTINUE;
            }
        });
        return (A) acc[0];
    }

    /**
     * Runs the action for every element and answers how the walk ended - the value-less total
     * terminal, for sites whose effects live in the action.
     *
     * @param action the per-element action
     * @return how the walk ended
     */
    @SuppressWarnings("unchecked")
    public @NotNull Exit forEach(@NotNull Consumer<? super T> action) {
        return Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override public Drive.@NotNull Verdict value(@NotNull Object value) {
                action.accept((T) value);
                return Drive.Verdict.CONTINUE;
            }
        });
    }

    /**
     * Which half of a looked-up source is absent, or {@code null} when the source resolved.
     * A descriptor query - no traversal happens.
     *
     * @return the missing half, or {@code null}
     */
    public @Nullable Missing missing() {
        return this.descriptor.source.missing();
    }

}
