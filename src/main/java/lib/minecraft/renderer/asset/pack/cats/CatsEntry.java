package lib.minecraft.renderer.asset.pack.cats;

import dev.simplified.util.compression.Compression;
import org.jetbrains.annotations.NotNull;

/**
 * One file record in a {@link CatsIndex}.
 *
 * @param path the full {@code /}-separated path
 * @param offset the byte offset within the data region (relative to the header end)
 * @param size the stored byte length within the data region
 * @param compression the {@link Compression} the stored bytes carry ({@link Compression#NONE} or {@link Compression#GZIP})
 */
public record CatsEntry(@NotNull String path, int offset, int size, @NotNull Compression compression) {}
