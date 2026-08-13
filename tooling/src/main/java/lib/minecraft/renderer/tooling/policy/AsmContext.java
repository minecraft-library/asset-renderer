package lib.minecraft.renderer.tooling.policy;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The frame a policy consultation sees.
 *
 * <p>{@code anchorClass} is deliberately a STRING, not a {@code ClassNode} - a node would
 * invite instruction-walking inside the policy; purity by type starvation. {@code session}
 * is present for {@code options()} (version-gated facts); {@code session.cache()} use inside
 * a policy is banned ({@code PolicyPurityTest}).
 *
 * <p>{@code subjectId} stays NON-NULL for the keyless rows too - a session-lifetime fact has no
 * roster id, and {@link #keyless} stamping {@link #KEYLESS_SUBJECT} is the one spelling of that,
 * so a keyless consultation is a single greppable construct rather than an id invented per site.
 *
 * @param session the live session (options access only)
 * @param subjectId the roster id under scrutiny ({@code minecraft:wolf}, {@code minecraft:bed_head}), or {@link #KEYLESS_SUBJECT}
 * @param anchorClass the renderer / BER / layer class in play, as an internal name
 * @param diagnostics the consulting resolver's scope
 */
public record AsmContext(
    @NotNull ToolingSession session,
    @NotNull String subjectId,
    @Nullable String anchorClass,
    @NotNull Diagnostics diagnostics
) {

    /**
     * The subject id a keyless frame carries. Angle brackets are the JVM's own reserved-name
     * bracket and no resource id spells them, so the sentinel cannot collide with a roster id and
     * a policy testing it is testing exactly the keyless case.
     */
    public static final @NotNull String KEYLESS_SUBJECT = "<keyless>";

    /**
     * Builds the frame for a consultation with no subject - a session-lifetime row - stamping the
     * reserved sentinel id and no anchor class.
     *
     * @param session the live session
     * @param diagnostics the consulting resolver's scope
     * @return the keyless frame
     */
    public static @NotNull AsmContext keyless(@NotNull ToolingSession session, @NotNull Diagnostics diagnostics) {
        return keyless(session, null, diagnostics);
    }

    /**
     * Builds the frame for a consultation with no subject but a class in play - a row a resolver
     * reaches per renderer rather than per subject.
     *
     * @param session the live session
     * @param anchorClass the renderer / BER / layer class in play, as an internal name
     * @param diagnostics the consulting resolver's scope
     * @return the keyless frame anchored at that class
     */
    public static @NotNull AsmContext keyless(
        @NotNull ToolingSession session,
        @Nullable String anchorClass,
        @NotNull Diagnostics diagnostics
    ) {
        return new AsmContext(session, KEYLESS_SUBJECT, anchorClass, diagnostics);
    }

}
