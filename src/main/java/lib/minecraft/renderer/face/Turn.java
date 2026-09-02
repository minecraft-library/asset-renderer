package lib.minecraft.renderer.face;

import dev.simplified.annotations.EnumLookup;
import dev.simplified.annotations.KeyField;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * An axis-preserving turn of the cube - one of the eight ways to negate a subset of the three axes.
 * <p>
 * Every frame relation the renderer actually performs is a member of this group. A shell's unwrap is
 * authored in vanilla's Y-down model frame while the boxes built from it are upright; a mirrored cube
 * reads the opposite side's strip; a shading normal is flipped into the kit's tuned light frame. All
 * of them pair each face either with itself or with its own opposite, so all of them are
 * {@code diag(+-1, +-1, +-1)} and a ninety-degree turn appears nowhere.
 * <p>
 * <b>The set is not restricted to rotations, and it must not be.</b> Four of the relations in use are
 * reflections - the two cube mirrors, the shading flip and the cape's face assignment - so a
 * rotation-only abstraction could express none of them. The eight members are closed under
 * {@link #then}, which is what lets a mirrored shell cube read {@code HALF_X.then(MIRROR_X)} rather
 * than needing a ninth relation minted for the combination.
 * <p>
 * Naming an axis says the turn <em>negates</em> it: {@link #MIRROR_X} negates X alone, {@link #HALF_X}
 * negates the other two and so is the half turn about X. {@link #NONE} and the three {@code HALF_}
 * members are the proper rotations; the other four {@link #reflects reflect}.
 */
@EnumLookup
public enum Turn {

    /** The identity - every face and every point unmoved. */
    NONE(false, false, false),
    /** The sagittal mirror, negating X alone - the cube {@code mirror} flag's face swap. */
    MIRROR_X(true, false, false),
    /** Negates Y alone. */
    MIRROR_Y(false, true, false),
    /** Negates Z alone. */
    MIRROR_Z(false, false, true),
    /** The half turn about X, negating Y and Z - the model-frame to upright-frame relation. */
    HALF_X(false, true, true),
    /** The half turn about Y, negating X and Z. */
    HALF_Y(true, false, true),
    /** The half turn about Z, negating X and Y. */
    HALF_Z(true, true, false),
    /** The point inversion, negating all three. */
    INVERT(true, true, true);

    private final boolean negatesX;
    private final boolean negatesY;
    private final boolean negatesZ;

    /**
     * Which axes this turn negates, as one bit each. Composition is the exclusive or of two masks,
     * since negating an axis twice restores it, and the eight members take the eight mask values
     * between them - so every mask an exclusive or can produce names a member.
     */
    @KeyField
    private final int mask;

    Turn(boolean negatesX, boolean negatesY, boolean negatesZ) {
        this.negatesX = negatesX;
        this.negatesY = negatesY;
        this.negatesZ = negatesZ;
        this.mask = (negatesX ? 1 : 0) | (negatesY ? 2 : 0) | (negatesZ ? 4 : 0);
    }

    /**
     * Applies this turn to a point. Negation is exact in IEEE-754, so no component is ever rounded
     * and an axis this turn leaves alone passes through as the identical value.
     *
     * @param point the point to turn
     * @return the turned point
     */
    public @NotNull Vector3f apply(@NotNull Vector3f point) {
        return new Vector3f(
            this.negatesX ? -point.x() : point.x(),
            this.negatesY ? -point.y() : point.y(),
            this.negatesZ ? -point.z() : point.z());
    }

    /**
     * The face this turn maps a face onto.
     *
     * @param face the face to turn
     * @return the face on the same axis, swapped for its opposite when this turn negates that axis
     */
    public @NotNull Face apply(@NotNull Face face) {
        return negates(face.axis()) ? face.opposite() : face;
    }

    /**
     * The turn equivalent to performing this one and then {@code next}.
     *
     * @param next the turn to apply after this one
     * @return the single member of the group with the same effect
     */
    public @NotNull Turn then(@NotNull Turn next) {
        return Objects.requireNonNull(ofMask(this.mask ^ next.mask),
            "The group is closed, so a composed mask names a member");
    }

    /**
     * Whether this turn reverses handedness - true when it negates an odd number of axes, which is
     * exactly when its determinant is {@code -1}. The three {@code HALF_} members and {@link #NONE}
     * are proper rotations; the other four reflect.
     *
     * @return {@code true} when this turn is a reflection rather than a rotation
     */
    public boolean reflects() {
        return Integer.bitCount(this.mask) % 2 == 1;
    }

    /** Whether this turn negates the named axis ({@code 0=x}, {@code 1=y}, {@code 2=z}). */
    private boolean negates(int axis) {
        return switch (axis) {
            case 0 -> this.negatesX;
            case 1 -> this.negatesY;
            default -> this.negatesZ;
        };
    }

}
