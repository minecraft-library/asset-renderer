package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import org.jetbrains.annotations.NotNull;

/**
 * Internal control-flow signal a rule parser throws when a condition fails to parse - the fail-closed
 * polarity: an unparseable condition rejects the WHOLE rule rather than silently
 * dropping a filter and over-matching. Caught at the top of each parse, logged via
 * {@link RuleDiagnostics}, and turned into a skipped rule. Never escapes the rule layer.
 */
@Getter(value = AccessLevel.PACKAGE, style = NamingStyle.FLUENT)
final class RuleRejection extends RuntimeException {

    /** The property key that failed to parse. */
    private final transient @NotNull String key;

    /** The offending value. */
    private final transient @NotNull String value;

    /**
     * Constructs a rejection naming the offending key, value, and reason.
     *
     * @param key the property key that failed to parse
     * @param value the offending value
     * @param reason the human-readable reason
     */
    RuleRejection(@NotNull String key, @NotNull String value, @NotNull String reason) {
        super(reason);
        this.key = key;
        this.value = value;
    }

}
