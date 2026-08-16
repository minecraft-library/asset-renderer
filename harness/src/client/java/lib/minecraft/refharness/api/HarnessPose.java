package lib.minecraft.refharness.api;

import dev.simplified.annotations.UtilityClass;
import org.joml.Quaternionf;

/** The presentation poses every harness subject is rendered at. */
@UtilityClass
public final class HarnessPose {

    /**
     * The iso pose, {@code rotationXYZ(210°, 45°, 0°)}.
     *
     * <p>Empirically locked via a 24-step yaw sweep plus a 576-frame pitch x roll sweep over a cow,
     * together with the chirality fix the render chain applies: yaw 135 was the corner-correct value,
     * but JOML's {@code getEulerAnglesXYZ} returns the principal Y in {@code [-90°, 90°]}, which
     * decomposes {@code (30°, 135°, 0°)} into the equivalent {@code (210°, 45°, 180°)}. The pitch x
     * roll sweep was therefore running at yaw 45, and pitch 210 with roll 0 is the right corner with
     * correct face winding once chirality is compensated.
     *
     * <p>Composed with the humanoid {@code scale(-1, -1, 1)} chirality the vanilla living-entity
     * submit chain applies, it presents a Y-down vanilla model upright and front-facing.
     */
    public static final Quaternionf ISO = new Quaternionf().rotationXYZ(
        (float) Math.toRadians(210.0),
        (float) Math.toRadians(45.0),
        0f);
}
