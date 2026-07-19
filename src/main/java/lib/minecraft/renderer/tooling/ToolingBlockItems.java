package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import dev.simplified.gson.node.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingPipeline;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockItemAliasWalk;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;

import java.nio.file.Path;

/**
 * Entry point of the {@code blockItems} Gradle task - walks {@code Items.<clinit>} for the blocks
 * that share another block's item ({@code white_wall_banner} -> {@code white_banner},
 * {@code skeleton_wall_skull} -> {@code skeleton_skull}, filled cauldrons -> {@code cauldron}) and
 * writes the secondary-to-standing alias map read at runtime so an aliased block's inventory icon
 * poses through its standing block item's {@code display.gui}.
 */
public final class ToolingBlockItems {

    /** The bundled resource directory. */
    private static final Path RESOURCE_DIR = Path.of("src", "main", "resources", "lib", "minecraft", "renderer");

    private ToolingBlockItems() {
    }

    /**
     * Runs the flow. ERROR-severity diagnostics fail the run (non-zero exit);
     * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
     *
     * @param args ignored - all paths are fixed
     */
    public static void main(String[] args) {
        try (ToolingSession session = ToolingPipeline.openSession("blockItems", Diagnostics.Output.CONSOLE)) {
            BlockRegistryIndex index = BlockRegistryIndex.build(session);
            JsonTree root = session.envelope("secondary block ids sorted; each maps to its standing block's item id");
            BlockItemAliasWalk.run(session, index, root);
            Path out = RESOURCE_DIR.resolve("block_items.json");
            root.write(out);
            session.diagnostics().info("wrote %s", out.toAbsolutePath());
            failOnStrictGate(session);
        }
    }

    /** The strict gate: ERROR always fails; {@code strict=warn} adds WARN. */
    private static void failOnStrictGate(ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics();
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling.strict", "").trim());
        int errors = diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("blockItems flow recorded %d ERROR / %d WARN entries%s",
                errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
