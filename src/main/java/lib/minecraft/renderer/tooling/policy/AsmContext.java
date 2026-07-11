package lib.minecraft.renderer.tooling.policy;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The frame a policy consultation sees (SPINE 5.5).
 *
 * <p>{@code anchorClass} is deliberately a STRING, not a {@code ClassNode} - a node would
 * invite instruction-walking inside the policy; purity by type starvation. {@code session}
 * is present for {@code options()} (version-gated facts); {@code session.cache()} use inside
 * a policy is banned ({@code PolicyPurityTest}).
 *
 * @param session the live session (options access only)
 * @param subjectId the roster id under scrutiny ({@code minecraft:wolf}, {@code minecraft:bed_head})
 * @param anchorClass the renderer / BER / layer class in play, as an internal name
 * @param diagnostics the consulting resolver's scope
 */
public record AsmContext(
    @NotNull ToolingSession session,
    @NotNull String subjectId,
    @Nullable String anchorClass,
    @NotNull Diagnostics diagnostics
) {}
