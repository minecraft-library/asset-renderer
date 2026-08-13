package lib.minecraft.renderer.tooling.policy;

import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

/**
 * The policy SPI - consulted AFTER generic detection misses, never before, never instead.
 *
 * <p>Concrete policies are flow-local package-private enums; this package holds the SPI
 * only - zero concrete facts live here. Policies never fetch: no ClassKit, no ClassNodeCache,
 * no ASM imports.
 */
public interface NavigationPolicy {

    /**
     * Consults this policy for the given frame.
     *
     * @param context the consultation frame
     * @return where to look, a declared fact, or {@link Navigation.None} when generic
     *     detection stands
     */
    @NotNull Navigation navigate(@NotNull AsmContext context);

    /**
     * Consults this policy and narrows the answer to a bytecode coordinate.
     *
     * <p>The narrowing a consumer would otherwise write itself, so a row that answers the wrong arm
     * raises one phrasing everywhere rather than a phrasing per site - and raises rather than falling
     * back, an arm disagreeing with its consumer being a contradiction in the roster rather than a
     * jar that drifted.
     *
     * @param context the consultation frame
     * @return the coordinate the row answers with
     * @throws ToolingException if the row answers any other arm
     */
    default Navigation.@NotNull At requireAt(@NotNull AsmContext context) {
        Navigation navigation = navigate(context);
        if (navigation instanceof Navigation.At coordinate) return coordinate;
        throw arm(navigation, "a bytecode coordinate");
    }

    /**
     * Consults this policy and narrows the answer to a coordinate plus its replay steps.
     *
     * @param context the consultation frame
     * @return the coordinate and trace the row answers with
     * @throws ToolingException if the row answers any other arm
     */
    default Navigation.@NotNull Dataflow requireDataflow(@NotNull AsmContext context) {
        Navigation navigation = navigate(context);
        if (navigation instanceof Navigation.Dataflow coordinate) return coordinate;
        throw arm(navigation, "a coordinate and trace");
    }

    /**
     * Builds the failure of a row answering an arm its consumer cannot read.
     *
     * @param answered the arm the row answered with
     * @param expected what the consumer required, as a noun phrase
     * @return the failure to throw
     */
    private @NotNull ToolingException arm(@NotNull Navigation answered, @NotNull String expected) {
        return new ToolingException("Policy '%s.%s' answers '%s' where %s was required",
            getClass().getSimpleName(), this, answered.getClass().getSimpleName(), expected);
    }

}
