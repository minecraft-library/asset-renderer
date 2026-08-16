package lib.minecraft.refharness.sweep;

import dev.simplified.annotations.UtilityClass;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Preparation every sweep applies to a freshly constructed entity before it is rendered. */
@UtilityClass
final class EntitySubjects {

    /**
     * Forces the entity's rotation state to zero before render-state extraction, so its pose comes
     * purely from the harness iso rotation rather than from whatever yaw and pitch it initialised
     * with. Position does not matter - the renderer anchors at the origin - but initialisation
     * sometimes sets it to something unexpected, so it is snapped too.
     *
     * @param entity the entity to zero
     */
    static void zeroRotations(Entity entity) {
        entity.snapTo(0, 0, 0, 0f, 0f);
        entity.setDeltaMovement(0, 0, 0);
        if (entity instanceof LivingEntity living) {
            living.setYBodyRot(0f);
            living.setYHeadRot(0f);
            living.yBodyRotO = 0f;
            living.yHeadRotO = 0f;
            living.yRotO = 0f;
            living.xRotO = 0f;
        }
    }
}
