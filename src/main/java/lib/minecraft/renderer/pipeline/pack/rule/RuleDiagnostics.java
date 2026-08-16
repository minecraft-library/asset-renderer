package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import org.jetbrains.annotations.NotNull;

/**
 * The per-file diagnostic surface for rejected rules - a fail-closed rule
 * degrades to vanilla output, but never silently: the pack, file, key, and value that caused the
 * rejection are named so a pack author can see why a rule did not load. Logs to {@code System.err},
 * matching the pack layer's ambiguity logging.
 */
@UtilityClass
public class RuleDiagnostics {

    /**
     * Logs a rejected rule, naming the pack, source file, offending key / value, and reason.
     *
     * @param pack the owning pack
     * @param file the source {@code .properties} id
     * @param rejection the rejection carrying the key, value, and reason
     */
    public static void reject(@NotNull PackId pack, @NotNull ResourceId file, @NotNull RuleRejection rejection) {
        System.err.printf(
            "Rule rejected [pack=%s file=%s]: key='%s' value='%s' - %s%n",
            pack, file.id(), rejection.key(), rejection.value(), rejection.getMessage());
    }

}
