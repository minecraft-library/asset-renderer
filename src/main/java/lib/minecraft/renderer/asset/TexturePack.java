package lib.minecraft.renderer.asset;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.pipeline.pack.PackMeta;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * A registered texture pack - vanilla or a user-supplied override.
 * <p>
 * The {@code priority} field orders pack lookups at render time; the highest priority pack wins
 * for any texture id collision. Vanilla has priority {@code 0}; user packs load at higher
 * priorities as they are supplied to the renderer.
 * <p>
 * {@code meta} carries the parsed {@code pack.mcmeta} - declared format, description, and
 * overlay entries. Each pack owns its own mcmeta 1-to-1; vanilla's comes from the extracted
 * client jar, user packs from their own pack root.
 * <p>
 * {@code assetRoots} carries the on-disk directories making up this pack: index zero is the
 * pack's base root, followed by every overlay subtree whose declared {@code formats} range
 * matched the pack's own declared {@code pack_format}, in {@code pack.mcmeta} declaration order.
 * Loaders walk the list bottom-up so later roots override earlier ones, matching vanilla's
 * overlay resolution semantics.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class TexturePack {

    /**
     * The pack id - {@code vanilla} for the extracted client jar, or the user pack's own id.
     */
    private final @NotNull String id;

    /**
     * The pack's resource namespace.
     */
    private final @NotNull String namespace;

    /**
     * The parsed {@code pack.mcmeta} - declared format, description, and overlay entries.
     */
    private final @NotNull PackMeta meta;

    /**
     * The on-disk asset roots making up this pack: the base root first, followed by every matched
     * overlay subtree in {@code pack.mcmeta} declaration order.
     */
    private final @NotNull ConcurrentList<Path> assetRoots;

    /**
     * The lookup priority; the highest-priority pack wins on a texture id collision ({@code 0} for vanilla).
     */
    private final int priority;

}
