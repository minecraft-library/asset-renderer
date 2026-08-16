package lib.minecraft.renderer.client;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;

/**
 * Configuration for a single {@link ClientAcquisition} run. Controls the target Minecraft version,
 * the cache root, additional texture pack directories, and whether to force a re-download of an
 * existing cached client jar.
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
     * Builds an options instance with every field at its default (version {@code 26.1}, cache
     * root {@code ./cache/asset-renderer}, no extra texture packs, no forced re-download).
     *
     * @return the default options
     */
    public static @NotNull ClientOptions defaults() {
        return builder().build();
    }

    /**
     * The {@code <cacheRoot>/vanilla/<version>} pack root these options resolve to - the directory the
     * client jar is extracted into and the vanilla base pack is read from.
     *
     * @return the vanilla pack root path
     */
    public @NotNull Path vanillaRoot() {
        return this.cacheRoot.toPath().resolve("vanilla").resolve(this.version);
    }

}
