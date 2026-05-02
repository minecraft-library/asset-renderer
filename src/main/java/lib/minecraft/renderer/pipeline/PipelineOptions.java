package lib.minecraft.renderer.pipeline;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Configuration for a single {@link Pipeline} run. Controls the target Minecraft version,
 * the cache root, additional texture pack directories, and whether to force a re-download of an
 * existing cached client jar.
 */
@Getter
@Builder(toBuilder = true, access = AccessLevel.PUBLIC)
public class PipelineOptions {

    /** The target Minecraft client version; defaults to the hardcoded 26.1 build. */
    @lombok.Builder.Default
    private final @NotNull String version = "26.1";

    /**
     * The pinned tag of {@code Mojang/bedrock-samples} the entity pipeline reads geometry
     * and textures from. The runtime entity-texture cache lives at
     * {@code <cacheRoot>/bedrock/<bedrockRef>/textures/entity/}, and the tooling task that
     * regenerates {@code entity_models.json} / {@code entity_geometry.json} reads from the same
     * pinned snapshot so derived JSONs and on-disk PNGs stay in lockstep across runs.
     */
    @lombok.Builder.Default
    private final @NotNull String bedrockRef = "v1.26.10.4";

    /** The cache root directory. Defaults to {@code ./cache/asset-renderer}. */
    @lombok.Builder.Default
    private final @NotNull File cacheRoot = new File("cache/asset-renderer");

    /** Additional texture pack directories or zip files to load on top of vanilla. */
    @lombok.Builder.Default
    private final @NotNull ConcurrentList<File> texturePacks = Concurrent.newList();

    /** When true, re-download the client jar even if a cached copy exists. */
    @lombok.Builder.Default
    private final boolean forceDownload = false;

    /**
     * When true, re-download the bedrock-samples archive and re-extract its entity texture
     * subtree even when a cached copy exists. Independent of {@link #forceDownload} so a
     * regeneration of the bedrock side does not also force a fresh client jar pull.
     */
    @lombok.Builder.Default
    private final boolean forceBedrockDownload = false;

    public @NotNull PipelineOptionsBuilder mutate() {
        return this.toBuilder();
    }

    public static @NotNull PipelineOptions defaults() {
        return builder().build();
    }

}
