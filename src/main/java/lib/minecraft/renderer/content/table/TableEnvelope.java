package lib.minecraft.renderer.content.table;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import org.jetbrains.annotations.NotNull;

/**
 * The envelope header every shipped table carries, minted here and validated by
 * {@link ResourceDocument}.
 *
 * <p>An envelope is three members ahead of the payload: the {@code //} provenance line naming the
 * generator flow, the regeneration task and the file's declared ordering source; the {@code format}
 * discriminator a reader selects its arm by; and the {@code source_version} the table was walked
 * out of.
 *
 * <p>The flow name and the ordering argument are the only two literals a generator owns that reach
 * shipped bytes - the flow twice, as {@code tooling.<flow>} and as the regen task, and the ordering
 * source once. Renaming a flow or editing an ordering string therefore rewrites the header of every
 * table that flow emits, and has to be diffed.
 */
@UtilityClass
public final class TableEnvelope {

    /** The grammar every table but the two entity tables declares. */
    private static final int DEFAULT_FORMAT = 2;

    /**
     * Mints a fresh envelope root at the default format.
     *
     * @param flow the generator flow's own name, stamped into the provenance line twice
     * @param orderingSource the declared ordering source stamped into the header
     * @param sourceVersion the client version the table is walked out of
     * @return the envelope root, ready for its payload member
     */
    public static @NotNull JsonTree mint(@NotNull String flow, @NotNull String orderingSource,
        @NotNull String sourceVersion) {
        return mint(flow, orderingSource, sourceVersion, DEFAULT_FORMAT);
    }

    /**
     * Mints a fresh envelope root at the named format.
     *
     * @param flow the generator flow's own name, stamped into the provenance line twice
     * @param orderingSource the declared ordering source stamped into the header
     * @param sourceVersion the client version the table is walked out of
     * @param format the grammar the table's readers select their arms by
     * @return the envelope root, ready for its payload member
     */
    public static @NotNull JsonTree mint(@NotNull String flow, @NotNull String orderingSource,
        @NotNull String sourceVersion, int format) {
        return JsonTree.object()
            .put("//", "tooling." + flow + " · regen: ./gradlew " + flow + " · order: " + orderingSource)
            .putInt("format", format)
            .put("source_version", sourceVersion);
    }

}
