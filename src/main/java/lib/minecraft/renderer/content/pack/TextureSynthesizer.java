package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.PalettedPermutationSource;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.engine.texture.Palette;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lazily generates paletted-permutation sprites on a texture-resolution miss. Sits
 * BEHIND {@code resolveTexture} - only ids no pack supplies reach it - so no existing resolve path
 * changes, and a file a pack ships shadows a permutation under the same id even when the texture cannot
 * be read. A registry built from the merged {@code atlases/*.json}
 * {@link PalettedPermutationSource sources} maps each synthetic id {@code <base>_<permutation>} to its
 * {@code (base pattern, palette key, material palette)} inputs; on a hit the sprite is permuted (the
 * generalisation of {@link TrimKit} - its first client, the armor-trim item overlays - so trim icons
 * stay byte-identical by construction) and memoised for the context's lifetime.
 *
 * <p>Sources concatenate across packs ascending (vanilla atlas semantics - additive, not replace), so
 * a higher pack adds permutations without dropping the vanilla trim set. A vanilla-only stack ships
 * only the trim atlas, and every vanilla trim reference is served by the item renderer's explicit
 * {@link TrimKit} branch before resolution, so this synthesiser never fires there - inert on vanilla.
 *
 * <p><b>Parity.</b> Reached only across the pipeline context, which is wiring, so no producer root
 * reaches it. It sits behind a texture miss and its first client is the armour-trim item overlay,
 * so what it can move is what an item draws.
 */
@Parity(claim = "texture-synthesis")
@Parity(subject = Subject.ITEM)
public final class TextureSynthesizer {

    /**
     * The empty synthesiser - no sources, answering absent for every id (test / stub contexts).
     */
    public static final @NotNull TextureSynthesizer EMPTY = new TextureSynthesizer(List.of());

    private final @NotNull ConcurrentMap<String, Entry> registry;
    private final @NotNull ConcurrentMap<String, PixelBuffer> cache = Concurrent.newMap();

    /**
     * Builds a synthesiser from the merged paletted-permutation sources. Sources arrive ascending by
     * pack, so a later source declaring the same {@code <base>_<permutation>} id takes the key.
     *
     * @param sources the concatenated {@code atlases/*.json} paletted-permutation sources
     */
    public TextureSynthesizer(@NotNull List<PalettedPermutationSource> sources) {
        this.registry = sources.stream()
            .flatMap(source -> source.textures()
                .stream()
                .map(TextureSynthesizer::normalize)
                .flatMap(baseId -> source.permutations()
                    .entrySet()
                    .stream()
                    .map(permutation -> Map.entry(
                        baseId + "_" + permutation.getKey(),
                        new Entry(baseId, normalize(source.paletteKey()), normalize(permutation.getValue()))))))
            .collect(Concurrent.toUnmodifiableMap((a, b) -> b));
    }

    /**
     * Synthesises the texture for an id the pack stack does not serve.
     * <p>
     * A registered permutation that cannot be produced is there and yields nothing, so it answers empty
     * rather than absent, whichever input it lacks: vanilla's paletted-permutation loader draws its
     * missing sprite for the permutation as a whole in either case.
     *
     * @param id the resolved (namespace-qualified) texture id the pack stack does not serve
     * @param resolver the stack-wide texture resolver for the base / palette / material inputs
     * @return the synthesised sprite; empty when the id is a registered permutation that cannot be
     *     produced - its sources are cyclic, or an input is not present; absent when no permutation is
     *     registered under the id
     */
    public @NotNull Possible<PixelBuffer> synthesize(@NotNull ResourceId id, @NotNull Function<String, Possible<PixelBuffer>> resolver) {
        Entry entry = this.registry.get(id.id());
        if (entry == null) return Possible.absent();

        PixelBuffer cached = this.cache.get(id.id());
        if (cached != null) return Possible.of(cached);

        // A synthesis input that is itself a registered synth id would re-enter synthesis through the
        // resolver (which consults this synthesizer on a miss) with no termination - a self- or
        // cyclically-referential atlas source. Real inputs are always concrete PNGs (never synth ids),
        // so refuse to resolve a registry-key input and abort cleanly to empty.
        if (isSynthId(entry.base()) || isSynthId(entry.paletteKey()) || isSynthId(entry.materialId()))
            return Possible.empty();

        Possible<PixelBuffer> base = resolver.apply(entry.base());
        Possible<PixelBuffer> palette = resolver.apply(entry.paletteKey());
        Possible<PixelBuffer> material = resolver.apply(entry.materialId());
        if (!base.isPresent() || !palette.isPresent() || !material.isPresent()) return Possible.empty();

        PixelBuffer permuted = Palette.permute(base.get(), palette.get(), material.get());
        this.cache.put(id.id(), permuted);
        return Possible.of(permuted);
    }

    /**
     * Whether an input id is itself a registered synthetic id (a cyclic-source guard).
     */
    private boolean isSynthId(@NotNull String id) {
        return this.registry.containsKey(id);
    }

    /**
     * Prepends the {@code minecraft:} default namespace to a bare id so registry keys are fully qualified.
     */
    private static @NotNull String normalize(@NotNull String id) {
        return id.indexOf(':') < 0 ? ResourceId.DEFAULT_NAMESPACE + ':' + id : id;
    }

    /**
     * A registered synthetic-id's inputs.
     */
    private record Entry(@NotNull String base, @NotNull String paletteKey, @NotNull String materialId) {}

}
