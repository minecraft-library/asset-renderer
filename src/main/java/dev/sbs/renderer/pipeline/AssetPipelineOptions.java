package dev.sbs.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Configuration for a single {@link AssetPipeline} run. Controls the target Minecraft version,
 * the cache root, additional texture pack directories, and whether to force a re-download of an
 * existing cached client jar.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class AssetPipelineOptions {

    /** The target Minecraft client version; defaults to the hardcoded 26.1 build. */
    @lombok.Builder.Default
    private final @NotNull String version = "26.1";

    /** The cache root directory. Defaults to {@code ./cache/asset-renderer}. */
    @lombok.Builder.Default
    private final @NotNull File cacheRoot = new File("cache/asset-renderer");

    /** Additional texture pack directories or zip files to load on top of vanilla. */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<File> texturePacks = Concurrent.newList();

    /** When true, re-download the client jar even if a cached copy exists. */
    @lombok.Builder.Default
    private final boolean forceDownload = false;

    public @NotNull AssetPipelineOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull AssetPipelineOptions defaults() {
        return builder().build();
    }

}
