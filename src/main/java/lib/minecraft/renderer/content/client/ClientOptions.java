package lib.minecraft.renderer.content.client;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;

/**
 * Configuration for a single {@link ClientAcquisition} run. Controls the target Minecraft version,
 * the cache root, additional texture pack directories, whether to force a re-download of an
 * existing cached client jar, and whether to extract the vanilla pack to disk rather than hold it in
 * memory.
 */
@Getter
@ClassBuilder
public class ClientOptions {

    /**
     * The target Minecraft client version; defaults to the hardcoded 26.1 build.
     */
    private final @NotNull String version = "26.1";

    /**
     * The cache root directory. Defaults to {@code ./cache/asset-renderer}.
     */
    private final @NotNull File cacheRoot = new File("cache/asset-renderer");

    /**
     * Additional texture pack directories or zip files to load on top of vanilla, in ascending
     * priority order - later entries win overlay merges. Each is assigned a render priority of
     * {@code 1..N} (vanilla is {@code 0}) and materialised through the pack acquirer during a run.
     */
    private final @NotNull ConcurrentList<File> texturePacks = Concurrent.newList();

    /**
     * When true, re-download the client jar even if a cached copy exists.
     */
    private final boolean forceDownload = false;

    /**
     * When true, extract the client jar's asset tree to disk under {@link #vanillaPackRoot()} and read
     * the vanilla pack from there, rather than reading it from the jar held in memory.
     */
    private final boolean extractAssets = false;

    /**
     * Builds an options instance with every field at its default (version {@code 26.1}, cache
     * root {@code ./cache/asset-renderer}, no extra texture packs, no forced re-download, the vanilla
     * pack read in memory).
     *
     * @return the default options
     */
    public static @NotNull ClientOptions defaults() {
        return builder().build();
    }

    /**
     * The {@code <cacheRoot>/vanilla/<version>} directory these options resolve to - where the client
     * jar is cached, beside the extracted pack root when there is one.
     *
     * @return the per-version cache directory
     */
    public @NotNull Path vanillaRoot() {
        return this.cacheRoot.toPath().resolve("vanilla").resolve(this.version);
    }

    /**
     * The {@code <cacheRoot>/vanilla/<version>/pack} directory the client jar's asset tree is extracted
     * into when {@link #isExtractAssets()} asks for an extraction.
     *
     * @return the extracted vanilla pack root
     */
    public @NotNull Path vanillaPackRoot() {
        return this.vanillaRoot().resolve("pack");
    }

}
