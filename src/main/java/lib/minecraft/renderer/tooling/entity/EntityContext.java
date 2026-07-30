package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;

/**
 * What one entity resolver is resolving, and everything it may consult while doing so - the live
 * session, the session-lifetime indexes, the subject, and the diagnostics scope this resolver
 * reports under.
 *
 * <p>A resolver takes this instead of the values it happens to need, so a value threaded from the
 * registry walk to a leaf no longer widens every constructor in between. The narrowing is
 * {@link #scope}, which is what a parent calls once per child rather than each child taking a
 * pre-narrowed {@code Diagnostics}.
 *
 * @param session the live session
 * @param indexes the session-lifetime values every subject reads
 * @param subject the entity being resolved
 * @param diagnostics the scope this resolver's entries are recorded under
 */
record EntityContext(
    @NotNull ToolingSession session,
    @NotNull EntityIndexes indexes,
    @NotNull EntitySubject subject,
    @NotNull Diagnostics diagnostics
) {

    /**
     * The same context reporting under a child diagnostics scope.
     *
     * @param tag the child scope name
     * @return the narrowed context
     */
    @NotNull EntityContext scope(@NotNull String tag) {
        return new EntityContext(this.session, this.indexes, this.subject, this.diagnostics.child(tag));
    }

    /**
     * The session's sole jar cache.
     *
     * @return the cache every bytecode walk reads through
     */
    @NotNull ClassNodeCache cache() {
        return this.session.cache();
    }

}
