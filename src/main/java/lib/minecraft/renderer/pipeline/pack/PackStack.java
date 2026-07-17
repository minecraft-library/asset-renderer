package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.rule.RuleSet;
import org.jetbrains.annotations.NotNull;

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
public final class PackStack {

    private final @NotNull ConcurrentList<ResourcePack> ascending;
    private final @NotNull Map<PackId, ResourcePack> byId;
    private final @NotNull Set<String> namespaces;
    private final @NotNull ConcurrentMap<ResourceId, IndexedTexture> textureIndex;
    private final @NotNull RuleSet rules;
    private final @NotNull Set<String> loggedAmbiguities = ConcurrentHashMap.newKeySet();

    private PackStack(@NotNull ConcurrentList<ResourcePack> ascending, @NotNull Map<PackId, ResourcePack> byId,
                      @NotNull Set<String> namespaces, @NotNull ConcurrentMap<ResourceId, IndexedTexture> textureIndex,
                      @NotNull RuleSet rules) {
        this.ascending = ascending;
        this.byId = byId;
        this.namespaces = namespaces;
        this.textureIndex = textureIndex;
        this.rules = rules;
    }

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
    public @NotNull PackStack withTextureIndex(@NotNull ConcurrentMap<ResourceId, IndexedTexture> index) {
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
     * overrides, and the global glint policy, folded across the stack by {@link RuleSet#merge}.
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
    public @NotNull ConcurrentMap<ResourceId, IndexedTexture> textureIndex() {
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
    public @NotNull Optional<IndexedTexture> indexed(@NotNull ResourceId id) {
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
        return dispatch(id).flatMap(this::locate);
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
        return byId(pack).flatMap(p -> probeInPack(p, id.name())).flatMap(this::locate);
    }

    /** The namespace-first / pack-id-second dispatch producing an index or probed row (no root walk yet). */
    private @NotNull Optional<IndexedTexture> dispatch(@NotNull ResourceId id) {
        String prefix = id.namespace();
        if (this.namespaces.contains(prefix)) {
            if (isLoadedPackId(prefix)) logAmbiguityOnce(prefix);
            return indexed(id);
        }
        if (isLoadedPackId(prefix))
            return byId(new PackId(prefix)).flatMap(pack -> probeInPack(pack, id.name()));
        return Optional.empty();
    }

    /** Probes one pack for a texture path across its namespaces (primary, then minecraft, then sorted). */
    private @NotNull Optional<IndexedTexture> probeInPack(@NotNull ResourcePack pack, @NotNull String path) {
        for (String namespace : searchOrder(pack)) {
            String relativePath = "assets/" + namespace + "/textures/" + path + ".png";
            boolean present = pack.roots().stream().anyMatch(root -> pack.container().exists(root.prefix() + relativePath));
            if (present)
                return Optional.of(new IndexedTexture(new ResourceId(namespace, path), pack.id(), relativePath, 0, 0, Optional.empty()));
        }
        return Optional.empty();
    }

    /** Walks the owning pack's roots base-first, keeping the last existing copy. */
    private @NotNull Optional<ResolvedTexture> locate(@NotNull IndexedTexture row) {
        ResourcePack pack = this.byId.get(row.pack());
        if (pack == null) return Optional.empty();

        PackContainer container = pack.container();
        String winning = null;
        for (PackRoot root : pack.roots()) {
            String candidate = root.prefix() + row.relativePath();
            if (container.exists(candidate)) winning = candidate;
        }
        return winning == null ? Optional.empty() : Optional.of(new ResolvedTexture(row.pack(), row.id(), container, winning));
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

}
