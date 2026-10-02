package lib.minecraft.renderer.content.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.content.table.EntityTables;
import lib.minecraft.renderer.exception.ContentException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;

/**
 * The memoized entity index, built by joining the three shipped entity tables {@link EntityTables}
 * reads through {@link EntityIndexBuilder} into the {@link Entity} map the renderer consumes.
 *
 * <p>The geometry join, the mesh surgery, the axes pivot, and the cross-entity grouping are
 * {@link EntityIndexBuilder}'s concern and the resource reads are {@link EntityTables}'; what this
 * holds is the one assembled map every caller shares.
 */
@UtilityClass
public final class EntityModelLoader {

    /** Guards {@link #memo}, so concurrent first callers assemble the index once. */
    private static final @NotNull Object MEMO_LOCK = new Object();

    /** The memoized index, held softly so memory pressure can reclaim it between renders. */
    private static @Nullable SoftReference<ConcurrentMap<String, Entity>> memo;

    /**
     * Reads the entity model catalog through {@link EntityTables}, then hands the three raw reads to
     * {@link EntityIndexBuilder} for the join, surgery, pivot, and grouping.
     *
     * <p>The assembled map is memoized behind a soft reference: every caller shares one instance,
     * re-assembled only after memory pressure reclaims it.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws ContentException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        synchronized (MEMO_LOCK) {
            ConcurrentMap<String, Entity> held = memo == null ? null : memo.get();
            if (held != null) return held;
            ConcurrentMap<String, Entity> built = assemble();
            memo = new SoftReference<>(built);
            return built;
        }
    }

    /** One whole assembly of the index - the {@link EntityTables} read, then the join. */
    private static @NotNull ConcurrentMap<String, Entity> assemble() {
        return EntityTables.read()
            .map(tables -> EntityIndexBuilder.assemble(tables.geometries(), tables.models(), tables.poses().poses()))
            .orElseGet(Concurrent::newMap);
    }
}
