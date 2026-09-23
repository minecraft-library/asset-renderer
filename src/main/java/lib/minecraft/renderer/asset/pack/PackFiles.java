package lib.minecraft.renderer.asset.pack;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Read-only byte access over one pack's files - the two questions every pack reader asks, "what
 * entries exist" and "give me these bytes", whatever the pack is stored as. Paths are always
 * {@code /}-separated and relative to the pack root with no leading slash.
 */
public interface PackFiles {

    /**
     * Reads the bytes of one entry.
     *
     * @param path the {@code /}-separated entry path
     * @return the entry's bytes, or empty when no such entry exists
     */
    @NotNull Optional<byte[]> bytes(@NotNull String path);

    /**
     * Enumerates every file entry whose path starts with a prefix.
     *
     * @param prefix the {@code /}-separated path prefix ({@code ""} for the whole pack)
     * @return the matching entry paths, {@code /}-separated and root-relative
     */
    @NotNull Stream<String> entries(@NotNull String prefix);

    /**
     * Whether a file entry exists at a path.
     *
     * @param path the {@code /}-separated entry path
     * @return {@code true} when an entry exists there
     */
    boolean exists(@NotNull String path);

}
