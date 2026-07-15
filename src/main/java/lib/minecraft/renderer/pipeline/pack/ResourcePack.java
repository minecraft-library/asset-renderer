package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/**
 * One logical resource pack in the stack: its identity, byte access, parsed metadata, active roots,
 * namespaces, and detected capabilities.
 *
 * <p>The stack is vanilla at priority 0 then user packs ascending, higher winning - the same
 * ordering every downstream merge assumes. After acquisition
 * the container is always a materialized {@link PackContainer.Directory} (zip and {@code .cats}
 * sources extract to the same tree shape), so the render hot path never touches an archive.
 *
 * @param id the normalized identity
 * @param container read-only byte access to the pack's materialized tree
 * @param meta the parsed root {@code pack.mcmeta}, or {@link MCMeta#EMPTY} when absent
 * @param roots the active roots, base first then matched overlays in declaration order
 * @param namespaces the directories under {@code assets/} across the active roots
 * @param capabilities the detected content layers
 */
public record ResourcePack(
    @NotNull PackId id,
    @NotNull PackContainer container,
    @NotNull MCMeta meta,
    @NotNull ConcurrentList<PackRoot> roots,
    @NotNull Set<String> namespaces,
    @NotNull Set<Capability> capabilities
) {

    /**
     * The pack's primary namespace - the one whose {@link PackId#normalize} equals this pack's id
     * ({@code hypixel_skyblock} for {@code hypixel-skyblock}). Front-runs the within-pack search
     * order for pack-restricted lookups.
     *
     * @return the primary namespace, or empty when no namespace normalizes to this id
     */
    public @NotNull Optional<String> primaryNamespace() {
        return this.namespaces.stream()
            .filter(ns -> PackId.normalize(ns).filter(this.id::equals).isPresent())
            .findFirst();
    }

    /**
     * Whether this pack carries the given capability.
     *
     * @param capability the capability to test
     * @return {@code true} when detected on this pack
     */
    public boolean has(@NotNull Capability capability) {
        return this.capabilities.contains(capability);
    }

}
