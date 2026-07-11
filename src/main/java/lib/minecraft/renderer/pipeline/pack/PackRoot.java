package lib.minecraft.renderer.pipeline.pack;

import org.jetbrains.annotations.NotNull;

/**
 * One active root inside a pack's container - a prefix into the container's path space, not an
 * absolute path.
 *
 * <p>The base root is the empty prefix; an active overlay is its directory name plus a trailing
 * slash ({@code "overlay_1/"}). Modelling both as prefixes lets vanilla {@code overlays.entries} and
 * Catharsis {@code fabric:overlays} share one resolution mechanism - only the activation predicate
 * differs. Loaders walk a pack's roots base-first so later overlays win.
 *
 * @param prefix the container-relative prefix - {@code ""} for the base root, {@code "<dir>/"} for
 *     an overlay
 */
public record PackRoot(@NotNull String prefix) {

    /** The base root of any pack - the empty prefix. */
    public static final @NotNull PackRoot BASE = new PackRoot("");

    /**
     * Builds an overlay root from its directory name, appending the path separator when absent.
     *
     * @param directory the overlay subdirectory name
     * @return the overlay root prefix
     */
    public static @NotNull PackRoot overlay(@NotNull String directory) {
        return new PackRoot(directory.endsWith("/") ? directory : directory + "/");
    }

    /**
     * Whether this is the base (empty-prefix) root.
     *
     * @return {@code true} for the base root
     */
    public boolean isBase() {
        return this.prefix.isEmpty();
    }

}
