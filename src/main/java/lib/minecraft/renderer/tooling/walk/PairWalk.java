package lib.minecraft.renderer.tooling.walk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The stream of an inverted commit's pairs - the key latched earlier, the value decoded at the
 * commit node. The pair is the entry, so the map collectors take no projections.
 *
 * @param <K> the latched key type
 * @param <V> the decoded value type
 */
public final class PairWalk<K, V> {

    private final @NotNull Descriptor descriptor;

    PairWalk(@NotNull Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    private @NotNull Exit collect(@NotNull BiConsumer<? super K, ? super V> action) {
        return Drive.run(this.descriptor, null, new Drive.Sink() {
            @Override
            @SuppressWarnings("unchecked")
            public Drive.@NotNull Verdict pair(@NotNull Object key, @NotNull Object value) {
                action.accept((K) key, (V) value);
                return Drive.Verdict.CONTINUE;
            }
        });
    }

    /**
     * Collects the pairs in encounter order, a later key overwriting an earlier one.
     *
     * @return the collected map; empty, never {@code null}
     */
    public @NotNull Map<K, V> toMap() {
        Map<K, V> out = new LinkedHashMap<>();
        collect(out::put);
        return out;
    }

    /**
     * Collects the pairs in encounter order, the first binding of a key winning.
     *
     * @return the collected map; empty, never {@code null}
     */
    public @NotNull Map<K, V> toMapFirstWins() {
        Map<K, V> out = new LinkedHashMap<>();
        collect(out::putIfAbsent);
        return out;
    }

    /**
     * Runs the action for every pair and answers how the walk ended.
     *
     * @param action the per-pair action
     * @return how the walk ended
     */
    public @NotNull Exit forEach(@NotNull BiConsumer<? super K, ? super V> action) {
        return collect(action);
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
