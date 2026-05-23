package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.exception.ToolingException;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Tooling-side analogue of {@link lib.minecraft.renderer.pipeline.PipelineRendererContext}
 * for the entity-models pipeline. Constructed once per {@link ToolingEntityModels} run by
 * {@link #of(Path, PipelineOptions)}, owns the open {@link ZipFile} and the
 * {@link ClassNodeCache} so every resolver pass that reads the same renderer / model /
 * layer class hits the cache instead of re-reading the jar entry.
 *
 * <p>The context is the cross-cutting state holder (zip / cache / diagnostics / options)
 * threaded through every resolver in the entity-tooling pipeline. The discovery /
 * per-entity walk / JSON-emission orchestration lives on {@link ToolingEntityModels}'s
 * {@code main} and consumes the context as its single argument.
 *
 * <p>Use through try-with-resources so the underlying jar is closed deterministically.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class ToolingEntityContext implements AutoCloseable {

    private final @NotNull Path clientJar;
    private final @NotNull PipelineOptions options;
    private final @NotNull ZipFile zip;
    private final @NotNull ClassNodeCache classNodes;
    private final @NotNull Diagnostics diagnostics;

    /**
     * Opens the supplied client jar and builds a context wrapping it. The caller owns the
     * returned context and must close it (try-with-resources) so the jar handle is released.
     *
     * @param clientJar the deobfuscated client jar on disk
     * @param options the pipeline options for the current run
     * @return a fresh context backed by an open {@link ZipFile} and an empty
     *     {@link ClassNodeCache}
     * @throws ToolingException if the jar cannot be opened
     */
    public static @NotNull ToolingEntityContext of(@NotNull Path clientJar, @NotNull PipelineOptions options) {
        try {
            ZipFile zip = new ZipFile(clientJar.toFile());
            return new ToolingEntityContext(clientJar, options, zip, new ClassNodeCache(), new Diagnostics());
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to open client jar '%s'", clientJar);
        }
    }

    @Override
    public void close() throws IOException {
        this.zip.close();
    }

}
