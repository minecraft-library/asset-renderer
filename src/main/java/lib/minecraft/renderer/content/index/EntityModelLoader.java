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
import java.util.Map;

/**
 * The memoized entity index, built by joining the three shipped entity tables {@link EntityTables}
 * reads through {@link EntityIndexBuilder} into the {@link Entity} map the renderer consumes.
 *
 * <p>The geometry join, the mesh surgery, the axes pivot, and the cross-entity grouping are
 * {@link EntityIndexBuilder}'s concern and the resource reads are {@link EntityTables}'; what this
 * holds is the one assembled map every caller shares.
 *
 * <p>Two readers answer out of it. {@link #load()} answers the rows this renderer draws, which is
 * what every walker over the corpus reads. {@link #loadAll()} answers those beside the rows of the
 * registered types vanilla draws nothing for, each a row whose body mesh holds no bone, which the
 * production context holds so it can answer those types empty rather than not knowing them. The two
 * split on {@link Entity#drawsNothing()}, the predicate that context answers empty by, so the split
 * and the lookup cannot disagree.
 */
@UtilityClass
public final class EntityModelLoader {

    /**
     * Guards {@link #memo}, so concurrent first callers assemble the index once.
     */
    private static final @NotNull Object MEMO_LOCK = new Object();

    /**
     * The memoized index, held softly so memory pressure can reclaim it between renders.
     */
    private static @Nullable SoftReference<Assembly> memo;

    /**
     * Reads the entity model catalog through {@link EntityTables}, then hands the three raw reads to
     * {@link EntityIndexBuilder} for the join, surgery, pivot, and grouping, and answers the rows this
     * renderer draws: every assembled row whose body mesh holds a bone.
     *
     * <p>The assembled map is memoized behind a soft reference: every caller shares one instance,
     * re-assembled only after memory pressure reclaims it.
     *
     * @return the drawn definitions keyed by namespaced entity id (empty when the geometry resource is
     *     absent)
     * @throws ContentException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        return assembly().drawn();
    }

    /**
     * Answers every assembled row, the rows of the registered types vanilla draws nothing for
     * included - the rows a context holds so it can answer those types empty rather than not knowing
     * them.
     *
     * <p>Memoized beside {@link #load()}, over the same assembly.
     *
     * @return every definition keyed by namespaced entity id (empty when the geometry resource is
     *     absent)
     * @throws ContentException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> loadAll() {
        return assembly().all();
    }

    /**
     * The memoized assembly, built on the first call and after memory pressure reclaims it.
     */
    private static @NotNull Assembly assembly() {
        synchronized (MEMO_LOCK) {
            Assembly held = memo == null ? null : memo.get();
            if (held != null) return held;
            Assembly built = Assembly.of(assemble());
            memo = new SoftReference<>(built);
            return built;
        }
    }

    /**
     * One whole assembly of the index - the {@link EntityTables} read, then the join.
     */
    private static @NotNull ConcurrentMap<String, Entity> assemble() {
        return EntityTables.read()
            .map(tables -> EntityIndexBuilder.assemble(tables.geometries(), tables.models(), tables.poses().poses()))
            .orElseGet(Concurrent::newMap);
    }

    /**
     * One assembly of the index, held whole and as the rows it draws.
     *
     * @param all every assembled row, in file order
     * @param drawn the rows whose body mesh holds a bone, in file order
     */
    private record Assembly(@NotNull ConcurrentMap<String, Entity> all, @NotNull ConcurrentMap<String, Entity> drawn) {

        /**
         * Splits one assembly on {@link Entity#drawsNothing()}.
         *
         * @param all every assembled row
         * @return the assembly, holding the drawn rows beside the whole
         */
        static @NotNull Assembly of(@NotNull ConcurrentMap<String, Entity> all) {
            ConcurrentMap<String, Entity> drawn = all.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().drawsNothing())
                .collect(Concurrent.toLinkedMap(Map.Entry::getKey, Map.Entry::getValue));
            return new Assembly(all, drawn);
        }

    }

}
