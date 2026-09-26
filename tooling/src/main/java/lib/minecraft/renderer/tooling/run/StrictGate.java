package lib.minecraft.renderer.tooling.run;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.ToolingException;
import org.jetbrains.annotations.NotNull;

/**
 * The gate a flow ends on: an ERROR always fails the run, and
 * {@code -Dasset.tooling.strict=warn} opts WARN into the same gate.
 */
@UtilityClass
public class StrictGate {

    /**
     * Applies the strict gate to everything the flow recorded. The flow names itself - the gate
     * reads the diagnostics root's own path rather than taking it as an argument.
     *
     * @param run the run whose diagnostics the gate reads
     * @throws ToolingException if the flow recorded a diagnostic the gate fails on
     */
    public static void apply(@NotNull ToolingRun run) {
        boolean warnStrict = "warn".equalsIgnoreCase(System.getProperty("asset.tooling.strict", "").trim());
        Diagnostics diagnostics = run.diagnostics();
        int errors = diagnostics.count(Diagnostics.Severity.ERROR);
        int warns = diagnostics.count(Diagnostics.Severity.WARN);
        if (errors > 0 || (warnStrict && warns > 0))
            throw new ToolingException("%s flow recorded %d ERROR / %d WARN entries%s",
                diagnostics.path(), errors, warns, warnStrict ? " (strict=warn)" : "");
    }

}
