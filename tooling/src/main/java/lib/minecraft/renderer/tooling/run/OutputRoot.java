package lib.minecraft.renderer.tooling.run;

import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The directory every flow writes its table into, defaulting to the renderer's bundled resource
 * tree.
 *
 * <p>{@code -Dasset.tooling.out} redirects the whole set, which is how an A/B is taken: the clean
 * side into one directory, the changed side into another, then diff. Relative either way, and
 * resolved against the working directory the build pins to the renderer root, so the default names
 * the same place from either build.
 */
@UtilityClass
public class OutputRoot {

    private static final @NotNull Path RESOURCE_DIR = Path.of(
        System.getProperty("asset.tooling.out", "src/main/resources/lib/minecraft/renderer"));

    /**
     * Resolves a file name against the bundled resource directory. Flows that write a second table
     * of their own name it here; the ordinary single-table flow goes through
     * {@link TableWriter#write}.
     *
     * @param fileName the table's file name
     * @return the path the table is written to
     */
    public static @NotNull Path resolve(@NotNull String fileName) {
        return RESOURCE_DIR.resolve(fileName);
    }

}
