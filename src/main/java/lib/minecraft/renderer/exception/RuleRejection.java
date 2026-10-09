package lib.minecraft.renderer.exception;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a pack rule's condition fails to parse - the fail-closed polarity: an unparseable
 * condition rejects the WHOLE rule rather than silently dropping a filter and over-matching. A rule
 * parser catches it at the top of each parse, logs the key, value and reason, and skips the rule, so it
 * never escapes the parse that raised it.
 *
 * <p>A {@link ContentException} because a rejected rule is a pack read that could not be completed, and
 * its own type because the parser answers it differently from every other one - by dropping that rule
 * rather than failing the load.
 *
 * @see ContentException
 */
@Getter(style = NamingStyle.FLUENT)
@Parity(claim = "pack-resolution")
@Parity(claim = "pipeline-reads")
public final class RuleRejection extends ContentException {

    /** The property key that failed to parse. */
    private final transient @NotNull String key;

    /** The offending value. */
    private final transient @NotNull String value;

    /**
     * Constructs a new {@code RuleRejection} naming the offending key, value, and reason.
     *
     * @param key the property key that failed to parse
     * @param value the offending value
     * @param reason the human-readable reason
     */
    public RuleRejection(@NotNull String key, @NotNull String value, @NotNull String reason) {
        super(reason);
        this.key = key;
        this.value = value;
    }

}
