package lib.minecraft.renderer.content.index;

import lib.minecraft.renderer.engine.frame.StripFit;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The strips fitted under one context, each held by the key it was fitted for.
 *
 * <p>A {@link RendererContext} that holds definitions carries one, so a strip lives as long as the
 * definitions it was fitted over and no longer. The key is the fitting side's own, compared by its
 * {@code equals} and {@code hashCode}.
 *
 * <p>Safe to ask from any thread. A strip is fitted outside any lock and published only where no other
 * thread published one first, so two threads asking for one key together may both fit it, and both
 * answer the strip that was published.
 */
public final class StripMemo {

    /** Every strip fitted under this memo, by the key it was fitted for. */
    private final @NotNull ConcurrentHashMap<Object, StripFit> fits = new ConcurrentHashMap<>();

    /**
     * Answers the strip held for a key, fitting it the first time the key is asked for.
     *
     * @param key what the strip is held under
     * @param fitting fits the strip where none is held
     * @return the strip held for the key
     */
    public @NotNull StripFit fit(@NotNull Object key, @NotNull Supplier<StripFit> fitting) {
        StripFit held = this.fits.get(key);
        if (held != null) return held;

        StripFit fitted = fitting.get();
        StripFit raced = this.fits.putIfAbsent(key, fitted);
        return raced == null ? fitted : raced;
    }

}
