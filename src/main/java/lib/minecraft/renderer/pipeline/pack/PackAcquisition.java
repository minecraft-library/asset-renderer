package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.pack.FormatRange.FormatVersion;
import lib.minecraft.renderer.pipeline.pack.PackIdDeriver.Assignment;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the vanilla pack root plus the user-supplied pack sources into a resolved {@link PackStack}:
 * detect each container by content, derive stable ids across the supply order (collisions resolved
 * loudly), materialize zip / {@code .cats} sources into {@code <cacheRoot>/packs/<id>/} with a
 * provenance sidecar (directory sources stay in place), resolve overlay roots against the
 * renderer-target format, detect capabilities, and assemble the stack with vanilla at priority 0.
 *
 * <p>Materialization is extract-to-directory (locked decision 7): after acquisition every pack is a
 * plain {@link PackContainer.Directory}, so the render path never reads an archive. Re-extraction is
 * skipped when the provenance records the same source mtime and heuristic version.
 */
public final class PackAcquisition {

    private PackAcquisition() {}

    /**
     * Acquires the full pack stack.
     *
     * @param userSources the user pack sources in supply (ascending priority) order
     * @param cacheRoot the renderer cache root ({@code packs/<id>/} lives under it)
     * @param vanillaPackRoot the extracted vanilla pack root (the base pack at priority 0)
     * @return the resolved stack, vanilla first
     * @throws PipelineException if a source is unreadable or a pack's metadata is malformed
     */
    public static @NotNull PackStack acquire(@NotNull List<Path> userSources, @NotNull Path cacheRoot, @NotNull Path vanillaPackRoot) {
        ResourcePack vanilla = vanillaPack(vanillaPackRoot);
        FormatVersion target = rendererTarget(vanilla);

        List<PackContainer> containers = new ArrayList<>();
        List<PackNameSources> naming = new ArrayList<>();
        for (Path source : userSources) {
            PackContainer container = PackContainer.detect(source);
            containers.add(container);
            naming.add(new PackNameSources(source, licenseTitleLine(container), description(container)));
        }
        ConcurrentList<Assignment> assignments = PackIdDeriver.assign(naming);

        List<ResourcePack> packs = new ArrayList<>();
        packs.add(vanilla);
        for (int i = 0; i < userSources.size(); i++)
            packs.add(userPack(userSources.get(i), containers.get(i), assignments.get(i), cacheRoot, target));

        return PackStack.of(Concurrent.adoptList(packs).toUnmodifiable());
    }

    /** Builds the vanilla base pack from its already-extracted tree (in place, no materialization). */
    private static @NotNull ResourcePack vanillaPack(@NotNull Path vanillaPackRoot) {
        if (!Files.isDirectory(vanillaPackRoot))
            throw new PipelineException("Vanilla pack root '%s' does not exist or is not a directory", vanillaPackRoot);
        PackContainer container = new PackContainer.Directory(vanillaPackRoot);
        MCMeta meta = readMeta(container, PackId.VANILLA);
        ConcurrentList<PackRoot> roots = resolveRoots(vanillaPackRoot, meta, rendererTargetFrom(meta));
        return new ResourcePack(PackId.VANILLA, container, meta, roots,
            namespaces(vanillaPackRoot, roots), detectCapabilities(container, meta));
    }

    /** Materializes one user pack and assembles its {@link ResourcePack}. */
    private static @NotNull ResourcePack userPack(@NotNull Path source, @NotNull PackContainer container,
                                                  @NotNull Assignment assignment, @NotNull Path cacheRoot, @NotNull FormatVersion target) {
        PackId id = assignment.id();
        long sourceMtime = lastModified(source);
        Path root = materialize(container, source, id, cacheRoot, sourceMtime);
        writeProvenance(assignment, source, sourceMtime, cacheRoot, container);

        PackContainer materialized = new PackContainer.Directory(root);
        MCMeta meta = readMeta(materialized, id);
        ConcurrentList<PackRoot> roots = resolveRoots(root, meta, target);
        return new ResourcePack(id, materialized, meta, roots, namespaces(root, roots), detectCapabilities(container, meta));
    }

    /**
     * Extracts a zip / {@code .cats} container into {@code <cacheRoot>/packs/<id>/}, or returns a
     * directory source in place. Reuses an existing extraction whose provenance matches the source
     * mtime and heuristic version.
     */
    private static @NotNull Path materialize(@NotNull PackContainer container, @NotNull Path source,
                                             @NotNull PackId id, @NotNull Path cacheRoot, long sourceMtime) {
        if (container instanceof PackContainer.Directory dir) return dir.root();

        Path dest = cacheRoot.resolve("packs").resolve(id.value());
        if (upToDate(dest.resolve(".pack.provenance.json"), sourceMtime)) return dest;

        try {
            if (Files.isDirectory(dest)) deleteTree(dest);
            Files.createDirectories(dest);
            container.entries("").forEach(entry -> writeEntry(container, entry, dest, id));
            // Ensure the authoritative pack.mcmeta lands even when it is an outer .cats.zip decoy
            // (decoys are not enumerated by entries()).
            container.bytes("pack.mcmeta").ifPresent(bytes -> writeBytes(dest.resolve("pack.mcmeta"), bytes, dest, id));
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to materialize pack '%s' into '%s'", id, dest);
        }
        return dest;
    }

    /** Writes one container entry into the destination tree, guarding against zip-slip. */
    private static void writeEntry(@NotNull PackContainer container, @NotNull String entry, @NotNull Path dest, @NotNull PackId id) {
        container.bytes(entry).ifPresent(bytes -> writeBytes(dest.resolve(entry), bytes, dest, id));
    }

    private static void writeBytes(@NotNull Path target, byte @NotNull [] bytes, @NotNull Path dest, @NotNull PackId id) {
        Path normalized = target.normalize();
        if (!normalized.startsWith(dest))
            throw new PipelineException("Pack '%s' contains an entry escaping its root: '%s'", id, target);
        try {
            Files.createDirectories(normalized.getParent());
            Files.write(normalized, bytes);
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to write pack '%s' entry '%s'", id, normalized);
        }
    }

    /** Whether an extraction is current: provenance exists, records this heuristic version, and is not older than the source. */
    private static boolean upToDate(@NotNull Path provenanceFile, long sourceMtime) {
        if (!Files.isRegularFile(provenanceFile)) return false;
        try {
            PackProvenance provenance = PackProvenance.parse(Files.readString(provenanceFile));
            return provenance.heuristicVersion() == PackIdDeriver.HEURISTIC_VERSION
                && provenance.sourceModifiedMillis() >= sourceMtime;
        } catch (IOException | PipelineException ex) {
            return false;
        }
    }

    /** Writes the provenance sidecar - inside the extracted tree for archives, beside it for in-place directory sources. */
    private static void writeProvenance(@NotNull Assignment assignment, @NotNull Path source, long sourceMtime,
                                        @NotNull Path cacheRoot, @NotNull PackContainer container) {
        PackProvenance provenance = PackProvenance.of(assignment, source, sourceMtime);
        Path packsDir = cacheRoot.resolve("packs");
        Path file = container instanceof PackContainer.Directory
            ? packsDir.resolve(assignment.id().value() + ".provenance.json")
            : packsDir.resolve(assignment.id().value()).resolve(".pack.provenance.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, provenance.toJson());
        } catch (IOException ex) {
            throw new PipelineException(ex, "Failed to write provenance for pack '%s'", assignment.id());
        }
    }

    /** Resolves the active roots: base first, then every overlay whose format range contains the target and whose directory exists. */
    private static @NotNull ConcurrentList<PackRoot> resolveRoots(@NotNull Path packRoot, @NotNull MCMeta meta, @NotNull FormatVersion target) {
        ArrayList<PackRoot> roots = new ArrayList<>();
        roots.add(PackRoot.BASE);
        meta.pack().ifPresent(pack -> {
            for (MCMeta.Overlay overlay : pack.overlays()) {
                if (!overlay.formats().contains(target)) continue;
                if (Files.isDirectory(packRoot.resolve(overlay.directory()))) roots.add(PackRoot.overlay(overlay.directory()));
            }
        });
        return Concurrent.adoptList(roots).toUnmodifiable();
    }

    /** The namespaces (directories under {@code assets/}) across a pack's active roots. */
    private static @NotNull Set<String> namespaces(@NotNull Path packRoot, @NotNull ConcurrentList<PackRoot> roots) {
        TreeSet<String> namespaces = new TreeSet<>();
        for (PackRoot root : roots) {
            Path assets = packRoot.resolve(root.prefix()).resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (var entries = Files.list(assets)) {
                entries.filter(Files::isDirectory).forEach(dir -> namespaces.add(dir.getFileName().toString()));
            } catch (IOException ex) {
                throw new PipelineException(ex, "Failed to enumerate namespaces under '%s'", assets);
            }
        }
        return Set.copyOf(namespaces);
    }

    /** Detects the capabilities a container carries from the 03 §1 signal table (path checks over the container). */
    private static @NotNull Set<Capability> detectCapabilities(@NotNull PackContainer container, @NotNull MCMeta meta) {
        LinkedHashSet<Capability> capabilities = new LinkedHashSet<>();
        if (container.entries("").anyMatch(p -> p.startsWith("assets/") || p.contains("/assets/")))
            capabilities.add(Capability.VANILLA_CORE);
        if (container.entries("").anyMatch(p -> p.contains("/optifine/") || p.contains("/mcpatcher/")))
            capabilities.add(Capability.OPTIFINE_RULES);
        if (container.entries("").anyMatch(PackAcquisition::isCatharsisSignal))
            capabilities.add(Capability.CATHARSIS_CONVENTIONS);
        return Set.copyOf(capabilities);
    }

    private static boolean isCatharsisSignal(@NotNull String path) {
        // The full-segment form avoids matching a pack's own <ns>_skyblock/items tree (e.g. hypixel_skyblock).
        return path.endsWith("config.catharsis.json") || path.contains("assets/skyblock/items/");
    }

    /** The renderer-target format for overlay activation: the game's current format, taken from the vanilla pack's declared floor. */
    private static @NotNull FormatVersion rendererTarget(@NotNull ResourcePack vanilla) {
        return rendererTargetFrom(vanilla.meta());
    }

    private static @NotNull FormatVersion rendererTargetFrom(@NotNull MCMeta meta) {
        return meta.pack().map(pack -> pack.formats().min()).orElse(new FormatVersion(0, 0));
    }

    private static @NotNull MCMeta readMeta(@NotNull PackContainer container, @NotNull PackId id) {
        return container.bytes("pack.mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), new ResourceId(id.value(), "pack")))
            .orElse(MCMeta.EMPTY);
    }

    private static @NotNull Optional<String> description(@NotNull PackContainer container) {
        return container.bytes("pack.mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), new ResourceId("probe", "pack")))
            .flatMap(MCMeta::pack)
            .map(pack -> pack.description().plain())
            .filter(plain -> !plain.isBlank());
    }

    private static @NotNull Optional<String> licenseTitleLine(@NotNull PackContainer container) {
        return container.bytes("LICENSE")
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .flatMap(text -> text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst());
    }

    private static long lastModified(@NotNull Path source) {
        try {
            return Files.getLastModifiedTime(source).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static void deleteTree(@NotNull Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        } catch (java.io.UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

}
