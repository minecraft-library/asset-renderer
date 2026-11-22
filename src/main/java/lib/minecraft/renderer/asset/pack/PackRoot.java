package lib.minecraft.renderer.asset.pack;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

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

    /** The characters the client's overlay codec holds a directory name to. */
    private static final @NotNull Pattern OVERLAY_DIRECTORY = Pattern.compile("[-_a-zA-Z0-9.]+");

    /**
     * Whether a pack may declare a directory as an overlay: one name of the letters, digits,
     * {@code -}, {@code _} and {@code .} the client's overlay codec accepts, so no separator and
     * nothing a platform reads as one. A name of dots alone is refused where the client's rule lets it
     * through, since it names the pack root or the directory holding the pack rather than a directory
     * the pack ships.
     *
     * @param directory the directory as the pack declares it
     * @return {@code true} when the name is one an overlay root is built from
     */
    public static boolean isOverlayDirectory(@NotNull String directory) {
        return OVERLAY_DIRECTORY.matcher(directory).matches() && !directory.chars().allMatch(c -> c == '.');
    }

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
