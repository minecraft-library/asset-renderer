package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ordered resource-pack stack: vanilla at priority 0, then user packs in ascending priority,
 * higher winning. Owns the pack-addressed lookups the resolution rule consults; the texture index
 * and the {@code resolve(ResourceId)} rule that spans it are wired in with the acquisition/resolution
 * layer.
 */
public final class PackStack {

    private final @NotNull ConcurrentList<ResourcePack> ascending;
    private final @NotNull Map<PackId, ResourcePack> byId;
    private final @NotNull Set<String> namespaces;

    private PackStack(@NotNull ConcurrentList<ResourcePack> ascending, @NotNull Map<PackId, ResourcePack> byId, @NotNull Set<String> namespaces) {
        this.ascending = ascending;
        this.byId = byId;
        this.namespaces = namespaces;
    }

    /**
     * Builds a stack from packs in ascending-priority order; the first must be the vanilla pack.
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
        return new PackStack(ascending, Map.copyOf(byId), Set.copyOf(namespaces));
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
     * The number of packs in the stack, including vanilla.
     *
     * @return the pack count
     */
    public int size() {
        return this.ascending.size();
    }

}
