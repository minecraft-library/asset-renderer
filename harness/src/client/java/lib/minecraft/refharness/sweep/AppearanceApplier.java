package lib.minecraft.refharness.sweep;

import java.lang.reflect.Method;

import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
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
        if (appearance.baby() && !setBaby(entity))
            LOG.warn("AppearanceApplier: {} has no baby form", EntityType.getKey(type));
        return entity;
    }

    /**
     * Ages the entity down.
     *
     * <p>Most ageable mobs share a public supertype setter. The zombie and piglin lines redeclare
     * theirs outside that supertype, with no shared interface between them, so those need the
     * reflective call - which is why the check is ordered rather than one or the other.
     *
     * @param entity the subject to age down
     * @return whether the entity had a baby form
     */
    private static boolean setBaby(Entity entity) {
        if (entity instanceof AgeableMob ageable) {
            ageable.setBaby(true);
            return true;
        }
        try {
            Method setBaby = entity.getClass().getMethod("setBaby", boolean.class);
            setBaby.invoke(entity, true);
            return true;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }
}
