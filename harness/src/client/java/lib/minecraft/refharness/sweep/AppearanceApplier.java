package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the live entity one {@link Appearance} describes.
 *
 * <p>Two mechanisms, and which one an axis uses is vanilla's choice rather than this harness's. An
 * axis vanilla persists is applied by reconstructing the entity through the deserialiser a world load
 * runs, because the setters behind those axes are private and the NBT round-trip is the public path.
 * An axis vanilla exposes as a setter is applied by calling it after construction.
 */
final class AppearanceApplier {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private AppearanceApplier() {}

    /**
     * Builds one subject, rotation-zeroed and ready to render.
     *
     * @param ctx the sweep context, for the level entity construction needs
     * @param type the entity type to build
     * @param appearance what it should look like
     * @return the entity, or {@code null} when vanilla declined to build one
     */
    static Entity build(SweepContext ctx, EntityType<?> type, Appearance appearance) {
        Entity entity = appearance.coat()
            .map(coat -> EntityType.loadEntityRecursive(type, coat.toPayload(), ctx.level(),
                EntitySpawnReason.LOAD, EntityProcessor.NOP))
            .orElseGet(() -> type.create(ctx.level(), EntitySpawnReason.LOAD));
        if (entity == null) return null;

        EntitySubjects.zeroRotations(entity);
        if (appearance.baby() && !ageDown(entity))
            LOG.warn("AppearanceApplier: {} did not take a baby form", EntityType.getKey(type));
        return entity;
    }

    /**
     * Whether vanilla gives this entity a baby form.
     *
     * <p>Asked by aging the entity down and reading back whether it took, because the question cannot
     * be answered by looking: {@code Mob} declares {@code setBaby} for every mob and does nothing in
     * the base implementation, so anything that merely checks the method is there answers yes for all
     * of them. The entity is left aged down and is discarded by the caller.
     *
     * @param entity the subject to test
     * @return whether it can be aged down
     */
    static boolean supportsBaby(Entity entity) {
        return ageDown(entity);
    }

    /**
     * Ages the entity down, and reports whether it took.
     *
     * <p>One virtual call covers every case. The ageable line and the zombie and piglin lines declare
     * their own setters with no interface in common, but all of them are mobs and all of them override
     * the one {@code Mob} declares - so dispatch does the work reflection would otherwise have to.
     *
     * @param entity the subject to age down
     * @return whether the entity now reports itself a baby
     */
    private static boolean ageDown(Entity entity) {
        if (!(entity instanceof Mob mob)) return false;
        mob.setBaby(true);
        return ((LivingEntity) mob).isBaby();
    }
}
