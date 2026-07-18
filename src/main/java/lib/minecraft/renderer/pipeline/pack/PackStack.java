package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ordered resource-pack stack: vanilla at priority 0, then user packs in ascending priority,
 * higher winning. Owns the texture index and the resolution rule the renderer consults - a
 * namespace-first, pack-id-second dispatch over {@code namespace:path} ids, followed by a within-pack
 * root walk in which the last existing copy wins.
 *
 * <p>Acquisition assembles the stack without a texture index ({@link #of}); the pipeline scans the
 * stack into an index and re-derives the stack through {@link #withTextureIndex}. Both spellings share
 * the pack list, id map, and namespace union; only the index differs.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class PackStack {

    private final @NotNull ConcurrentList<ResourcePack> ascending;
    private final @NotNull Map<PackId, ResourcePack> byId;
    private final @NotNull Set<String> namespaces;
    private final @NotNull ConcurrentMap<ResourceId, ResolvedTexture> textureIndex;
    private final @NotNull RuleSet rules;
    private final @NotNull Set<String> loggedAmbiguities = ConcurrentHashMap.newKeySet();

    private final @NotNull ImageFactory imageFactory = new ImageFactory();

    /**
     * Per-stack memoisation cache of decoded {@link PixelBuffer}s keyed by the resolved
     * {@code (PackId, ResourceId)}, populated lazily on the first {@link #pixels(ResourceId)} of each
     * texture, so a stack-wide and a pack-restricted lookup that land on the same file share one buffer.
     */
    private final @NotNull ConcurrentMap<PixelKey, PixelBuffer> pixelCache = Concurrent.newMap();

    /**
     * Builds a stack from packs in ascending-priority order; the first must be the vanilla pack. The
     * texture index starts empty - {@link #withTextureIndex} attaches it once the pipeline has scanned
     * the stack.
     *
     * @param ascending the packs, vanilla first, then user packs ascending
     * @return the assembled stack
     * @throws PipelineException if the stack is empty or does not lead with the vanilla pack
     */
    public static @NotNull PackStack of(@NotNull ConcurrentList<ResourcePack> ascending) {
        if (ascending.isEmpty())
            throw new PipelineException("Pack stack is empty; the vanilla pack is required at priority 0");
        if (!ascending.getFirst().id().equals(PackId.VANILLA))
            throw new PipelineException("Pack stack must lead with the vanilla pack, got '%s'", ascending.getFirst().id());

        LinkedHashMap<PackId, ResourcePack> byId = new LinkedHashMap<>();
        LinkedHashSet<String> namespaces = new LinkedHashSet<>();
        for (ResourcePack pack : ascending) {
            byId.put(pack.id(), pack);
            namespaces.addAll(pack.namespaces());
        }
        return new PackStack(ascending, Map.copyOf(byId), Set.copyOf(namespaces), Concurrent.newMap(), RuleSet.empty(PackId.VANILLA));
    }

    /**
     * Returns a copy of this stack carrying the given texture index.
     *
     * @param index the scanned texture index, keyed by namespaced id
     * @return the indexed stack
     */
    public @NotNull PackStack withTextureIndex(@NotNull ConcurrentMap<ResourceId, ResolvedTexture> index) {
        return new PackStack(this.ascending, this.byId, this.namespaces, index, this.rules);
    }

    /**
     * Returns a copy of this stack carrying the given merged rule set.
     *
     * @param rules the merged pack rules (CIT / CTM / colour overrides / glint)
     * @return the stack carrying the rules
     */
    public @NotNull PackStack withRules(@NotNull RuleSet rules) {
        return new PackStack(this.ascending, this.byId, this.namespaces, this.textureIndex, rules);
    }

    /**
     * The merged pack rule payload the renderer consults - CIT rules, CTM rules, per-key colour
     * overrides, and the global glint policy, folded across the stack at acquisition time.
     *
     * @return the merged rules
     */
    public @NotNull RuleSet rules() {
        return this.rules;
    }

    /**
     * The vanilla base pack's on-disk root - the {@code <cacheRoot>/vanilla/<version>} directory the
     * client jar was extracted into.
     *
     * @return the vanilla pack root
     * @throws PipelineException if the vanilla pack is not directory-backed
     */
    public @NotNull Path vanillaRoot() {
        if (vanilla().container() instanceof PackContainer.Directory dir) return dir.root();
        throw new PipelineException("Vanilla pack '%s' is not directory-backed", vanilla().id());
    }

    /**
     * The vanilla base pack at priority 0.
     *
     * @return the vanilla pack
     */
    public @NotNull ResourcePack vanilla() {
        return this.ascending.getFirst();
    }

    /**
     * Every pack in ascending-priority order (vanilla first, higher priority later).
     *
     * @return the ascending pack list
     */
    public @NotNull ConcurrentList<ResourcePack> ascending() {
        return this.ascending;
    }

    /**
     * Looks up a pack by its id.
     *
     * @param id the pack id
     * @return the pack, or empty when no pack in the stack has that id
     */
    public @NotNull Optional<ResourcePack> byId(@NotNull PackId id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    /**
     * The loaded pack ids.
     *
     * @return the set of pack ids in the stack
     */
    public @NotNull Set<PackId> packIds() {
        return this.byId.keySet();
    }

    /**
     * The union of every pack's namespaces.
     *
     * @return the namespace union across the stack
     */
    public @NotNull Set<String> namespaces() {
        return this.namespaces;
    }

    /**
     * The scanned texture index, keyed by resolved namespaced id.
     *
     * @return the texture index
     */
    public @NotNull ConcurrentMap<ResourceId, ResolvedTexture> textureIndex() {
        return this.textureIndex;
    }

    /**
     * The number of packs in the stack, including vanilla.
     *
     * @return the pack count
     */
    public int size() {
        return this.ascending.size();
    }

    /**
     * Looks up the index row for an id directly (no dispatch, no root walk) - the metadata carrier the
     * animation-sidecar lookup reads.
     *
     * @param id the namespaced texture id
     * @return the index row, or empty when no pack indexed it
     */
    public @NotNull Optional<ResolvedTexture> indexed(@NotNull ResourceId id) {
        return Optional.ofNullable(this.textureIndex.get(id));
    }

    /**
     * Resolves a texture id to the winning pack's on-disk PNG, applying the namespace-first /
     * pack-id-second dispatch then the within-pack root walk.
     *
     * <p>When the id's prefix is a live namespace the content-addressed index decides the winner; when
     * it is instead a loaded pack id the lookup is restricted to that pack's namespaces in
     * primary-then-{@code minecraft}-then-sorted order. A prefix that is both logs an ambiguity once
     * and takes the namespace. An unknown prefix resolves to empty - no fallback.
     *
     * @param id the namespaced texture id
     * @return the resolved texture, or empty when nothing supplies it
     */
    public @NotNull Optional<ResolvedTexture> resolve(@NotNull ResourceId id) {
        return dispatch(id);
    }

    /**
     * Resolves a texture id restricted to one pack (the {@code resolveTexture(PackId, ResourceId)}
     * escape hatch), bypassing the namespace-first dispatch.
     *
     * @param pack the pack to restrict resolution to
     * @param id the namespaced texture id (its path is searched across the pack's namespaces)
     * @return the resolved texture, or empty when the pack does not supply it
     */
    public @NotNull Optional<ResolvedTexture> resolveIn(@NotNull PackId pack, @NotNull ResourceId id) {
        return byId(pack).flatMap(p -> probeInPack(p, id.name()));
    }

    /**
     * Resolves a texture id to its decoded {@link PixelBuffer}, memoising the decode on the resolved
     * {@code (PackId, ResourceId)} so a stack-wide and a pack-restricted lookup that land on the same
     * file share one buffer. Runs the namespace-first dispatch then decodes the winning PNG once.
     *
     * @param id the namespaced texture id
     * @return the decoded texture, or empty when nothing supplies it
     */
    public @NotNull Optional<PixelBuffer> pixels(@NotNull ResourceId id) {
        Optional<ResolvedTexture> indexed = indexed(id);
        if (indexed.isPresent()) {
            PixelBuffer cached = this.pixelCache.get(new PixelKey(indexed.get().pack(), id));
            if (cached != null) return Optional.of(cached);
        }
        return decode(resolve(id));
    }

    /** Decodes a resolved texture, memoising on the resolved {@code (PackId, ResourceId)} key. */
    private @NotNull Optional<PixelBuffer> decode(@NotNull Optional<ResolvedTexture> resolved) {
        return resolved.map(texture -> {
            PixelKey key = new PixelKey(texture.pack(), texture.id());
            PixelBuffer cached = this.pixelCache.get(key);
            if (cached != null) return cached;

            // PixelBuffer.wrap handles every BufferedImage layout the vanilla 1.21 pack ships -
            // INT_ARGB, INT_RGB, INT_BGR, 4BYTE_ABGR, 3BYTE_BGR, BYTE_INDEXED, BYTE_GRAY, BYTE_BINARY
            // (IndexColorModel), and TYPE_CUSTOM with ComponentColorModel of TYPE_GRAY (2-band
            // tRNS-keyed grayscale) - without applying the sRGB-gamma transform that would inflate
            // raw byte values on calibrated-gray sources.
            PixelBuffer buffer = this.imageFactory.fromByteArray(texture.bytes()).toPixelBuffer();
            this.pixelCache.put(key, buffer);
            return buffer;
        });
    }

    /** The namespace-first / pack-id-second dispatch: an index lookup or a live pack probe, both baked. */
    private @NotNull Optional<ResolvedTexture> dispatch(@NotNull ResourceId id) {
        String prefix = id.namespace();
        if (this.namespaces.contains(prefix)) {
            if (isLoadedPackId(prefix)) logAmbiguityOnce(prefix);
            return indexed(id);
        }
        if (isLoadedPackId(prefix))
            return byId(new PackId(prefix)).flatMap(pack -> probeInPack(pack, id.name()));
        return Optional.empty();
    }

    /**
     * Probes one pack for a texture path across its namespaces (primary, then minecraft, then sorted),
     * baking the within-pack root walk (base first, overlays after, last existing copy winning) and the
     * {@code .png.mcmeta} sidecar beside the winner - the same merged form the index carries.
     */
    private @NotNull Optional<ResolvedTexture> probeInPack(@NotNull ResourcePack pack, @NotNull String path) {
        PackContainer container = pack.container();
        for (String namespace : searchOrder(pack)) {
            String relativePath = PackPaths.texturesDir(namespace) + "/" + path + ".png";
            String winning = null;
            for (PackRoot root : pack.roots()) {
                String candidate = root.prefix() + relativePath;
                if (container.exists(candidate)) winning = candidate;
            }
            if (winning != null) {
                ResourceId id = new ResourceId(namespace, path);
                return Optional.of(new ResolvedTexture(pack.id(), id, container, winning, readSidecar(container, winning, id)));
            }
        }
        return Optional.empty();
    }

    /** Reads the whole {@code <file>.png.mcmeta} sidecar next to a PNG, bound to the same pack+root. */
    private static @NotNull Optional<MCMeta> readSidecar(@NotNull PackContainer container, @NotNull String pngEntry, @NotNull ResourceId id) {
        return container.bytes(pngEntry + ".mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), id));
    }

    /** The within-pack namespace search order: primary namespace, then {@code minecraft}, then the rest sorted. */
    private static @NotNull List<String> searchOrder(@NotNull ResourcePack pack) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        pack.primaryNamespace().ifPresent(order::add);
        order.add("minecraft");
        pack.namespaces().stream().sorted().forEach(order::add);
        return new ArrayList<>(order);
    }

    private boolean isLoadedPackId(@NotNull String prefix) {
        return PackId.ALPHABET.matcher(prefix).matches() && this.byId.containsKey(new PackId(prefix));
    }

    private void logAmbiguityOnce(@NotNull String prefix) {
        if (this.loggedAmbiguities.add(prefix))
            System.err.printf("Resolution ambiguity: prefix '%s' is both a live namespace and a loaded pack id; "
                + "resolving as a namespace (use resolveIn for pack-restricted lookup)%n", prefix);
    }

    /** The decoded-pixel cache key: the resolved pack plus the resolved texture id. */
    private record PixelKey(@NotNull PackId pack, @NotNull ResourceId id) {}

}
