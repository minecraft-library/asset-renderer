package lib.minecraft.renderer.tooling.run;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

/**
 * The single-table write every flow ends on - the bundled resource directory, plus the diagnostics
 * line naming the table it wrote.
 *
 * <p>The line names the table by file name alone. A flow's diagnostics log is digested to tell one
 * run from another, so a path in it would make that digest a function of where the checkout lives
 * and of which output root the run wrote to, rather than of what the flow did.
 */
@UtilityClass
public class TableWriter {

    /**
     * Writes a finished envelope into the bundled resource directory and records which table it was.
     *
     * @param run the run whose diagnostics record the write
     * @param root the envelope root to write
     * @param fileName the table's file name
     */
    public static void write(@NotNull ToolingRun run, @NotNull JsonTree root, @NotNull String fileName) {
        root.write(OutputRoot.resolve(fileName));
        run.diagnostics().info("wrote %s", fileName);
    }

}
