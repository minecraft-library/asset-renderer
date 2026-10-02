package lib.minecraft.renderer.tooling.run;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * The single-table write every flow ends on - the bundled resource directory, plus the diagnostics
 * line recording where the table went.
 */
@UtilityClass
public class TableWriter {

    /**
     * Writes a finished envelope into the bundled resource directory and records where it went.
     *
     * @param run the run whose diagnostics record the write
     * @param root the envelope root to write
     * @param fileName the table's file name
     */
    public static void write(@NotNull ToolingRun run, @NotNull JsonTree root, @NotNull String fileName) {
        Path out = OutputRoot.resolve(fileName);
        root.write(out);
        run.diagnostics().info("wrote %s", out.toAbsolutePath());
    }

}
