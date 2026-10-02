package lib.minecraft.renderer.diagnostic;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The per-file diagnostic surface for rejected rules - a fail-closed rule
 * degrades to vanilla output, but never silently: the pack, file, key, and value that caused the
 * rejection are named so a pack author can see why a rule did not load. Logs to {@code System.err},
 * matching the pack layer's ambiguity logging.
 */
@UtilityClass
@Parity(claim = "pack-resolution")
@Parity(claim = "pipeline-reads")
public class RuleDiagnostics {

    /**
     * Logs a rejected rule, naming the pack, source file, offending key / value, and reason.
     *
     * @param pack the owning pack's id
     * @param file the source {@code .properties} id
     * @param key the property key that failed to parse
     * @param value the offending value
     * @param reason the human-readable reason
     */
    public static void reject(
        @NotNull String pack, @NotNull String file,
        @NotNull String key, @NotNull String value, @NotNull String reason
    ) {
        System.err.printf(
            "Rule rejected [pack=%s file=%s]: key='%s' value='%s' - %s%n",
            pack, file, key, value, reason);
    }

}
