package lib.minecraft.renderer.engine.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.kit.TrimKit;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lazily generates paletted-permutation sprites on a texture-resolution miss. Sits
 * BEHIND {@code resolveTexture} - only ids no pack supplies reach it - so no existing resolve path
 * changes. A registry built from the merged {@code atlases/*.json}
 * {@link PalettedPermutationSource sources} maps each synthetic id {@code <base>_<permutation>} to its
 * {@code (base pattern, palette key, material palette)} inputs; on a hit the sprite is permuted (the
 * generalisation of {@link TrimKit} - its first client, the armor-trim item overlays - so trim icons
 * stay byte-identical by construction) and memoised for the context's lifetime.
 *
 * <p>Sources concatenate across packs ascending (vanilla atlas semantics - additive, not replace), so
 * a higher pack adds permutations without dropping the vanilla trim set. A vanilla-only stack ships
 * only the trim atlas, and every vanilla trim reference is served by the item renderer's explicit
 * {@link TrimKit} branch before resolution, so this synthesiser never fires there - inert on vanilla.
 */
public final class TextureSynthesizer {

    /** The empty synthesiser - no sources, never synthesises (test / stub contexts). */
    public static final @NotNull TextureSynthesizer EMPTY = new TextureSynthesizer(List.of());

    private final @NotNull Map<String, Entry> registry;
    private final @NotNull ConcurrentMap<String, PixelBuffer> cache = Concurrent.newMap();

    /**
     * Builds a synthesiser from the merged paletted-permutation sources.
     *
     * @param sources the concatenated {@code atlases/*.json} paletted-permutation sources
     */
    public TextureSynthesizer(@NotNull List<PalettedPermutationSource> sources) {
        Map<String, Entry> registry = new HashMap<>();
        for (PalettedPermutationSource source : sources) {
            String paletteKey = normalize(source.paletteKey());
            for (String base : source.textures()) {
                String baseId = normalize(base);
                for (Map.Entry<String, String> permutation : source.permutations().entrySet())
                    registry.put(baseId + "_" + permutation.getKey(), new Entry(baseId, paletteKey, normalize(permutation.getValue())));
            }
        }
        this.registry = Map.copyOf(registry);
    }

    /**
     * Synthesises the texture for a resolution-miss id, or empty when the id is not a registered
     * permutation (the common case) or a required input texture is missing.
     *
     * @param id the resolved (namespace-qualified) texture id that missed the pack stack
     * @param resolver the stack-wide texture resolver for the base / palette / material inputs
     * @return the synthesised sprite, or empty
     */
    public @NotNull Optional<PixelBuffer> synthesize(@NotNull ResourceId id, @NotNull Function<String, Optional<PixelBuffer>> resolver) {
        Entry entry = this.registry.get(id.id());
        if (entry == null) return Optional.empty();

        PixelBuffer cached = this.cache.get(id.id());
        if (cached != null) return Optional.of(cached);

        // A synthesis input that is itself a registered synth id would re-enter synthesis through the
        // resolver (which consults this synthesizer on a miss) with no termination - a self- or
        // cyclically-referential atlas source. Real inputs are always concrete PNGs (never synth ids),
        // so refuse to resolve a registry-key input and abort cleanly to empty.
        if (isSynthId(entry.base()) || isSynthId(entry.paletteKey()) || isSynthId(entry.materialId()))
            return Optional.empty();

        Optional<PixelBuffer> base = resolver.apply(entry.base());
        Optional<PixelBuffer> palette = resolver.apply(entry.paletteKey());
        Optional<PixelBuffer> material = resolver.apply(entry.materialId());
        if (base.isEmpty() || palette.isEmpty() || material.isEmpty()) return Optional.empty();

        PixelBuffer permuted = TrimKit.permute(base.get(), palette.get(), material.get());
        this.cache.put(id.id(), permuted);
        return Optional.of(permuted);
    }

    /** Whether an input id is itself a registered synthetic id (a cyclic-source guard). */
    private boolean isSynthId(@NotNull String id) {
        return this.registry.containsKey(id);
    }

    /** Prepends the {@code minecraft:} default namespace to a bare id so registry keys are fully qualified. */
    private static @NotNull String normalize(@NotNull String id) {
        return id.indexOf(':') < 0 ? ResourceId.DEFAULT_NAMESPACE + ':' + id : id;
    }

    /** A registered synthetic-id's inputs. */
    private record Entry(@NotNull String base, @NotNull String paletteKey, @NotNull String materialId) {}

}
