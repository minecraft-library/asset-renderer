package lib.minecraft.renderer.pose.author;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

/**
 * The named full-body silhouettes - twenty classic armor-stand poses plus the T-pose and the
 * stand's own at-ease rest, each carrying its six whole-degree rotation triples as data.
 *
 * <p>A preset is a TOTAL stamp: applying one writes all six limb rotation triples at once, so
 * every application lands on a coherent silhouette, and each verb after it adjusts one limb -
 * stamp total, then adjust. Values follow vanilla sign conventions: negative arm pitch raises
 * the arm forward and up, arm roll is the sideways spread, arm yaw turns it around its length.
 *
 * <p>The angles are ground truth for a pose's SHAPE - which channels move, signs, magnitudes.
 * Limb pivots differ between rigs, so a few values read slightly differently away from the
 * armor stand and want a small tune against a render.
 */
@Getter(style = NamingStyle.FLUENT)
@RequiredArgsConstructor
@Parity(subject = Subject.ENTITY)
public enum Preset {

    /**
     * Bolt upright, every channel zero - stiffer than {@link #AT_EASE}.
     */
    ATTENTION(Triple.ZERO, Triple.ZERO, Triple.ZERO, Triple.ZERO, Triple.ZERO, Triple.ZERO),

    /**
     * Mid-stride, arms and legs swung in opposition.
     */
    WALKING(Triple.ZERO, Triple.ZERO,
        new Triple(20, 0, 10), new Triple(-20, 0, -10), new Triple(-20, 0, 0), new Triple(20, 0, 0)),

    /**
     * A driving stride, the opposition of {@link #WALKING} at double the swing.
     */
    RUNNING(Triple.ZERO, Triple.ZERO,
        new Triple(-40, 0, 10), new Triple(40, 0, -10), new Triple(40, 0, 0), new Triple(-40, 0, 0)),

    /**
     * The right arm level at the horizon, head turned along it.
     */
    POINTING(new Triple(0, 20, 0), Triple.ZERO,
        new Triple(-90, 18, 0), new Triple(0, 0, -10), Triple.ZERO, Triple.ZERO),

    /**
     * Guarding behind a raised left forearm, weight back.
     */
    BLOCKING(Triple.ZERO, Triple.ZERO,
        new Triple(-20, -20, 0), new Triple(-50, 50, 0), new Triple(-20, 0, 0), new Triple(20, 0, 0)),

    /**
     * A forward thrust, torso leaning into the extended right arm.
     */
    LUNGEING(Triple.ZERO, new Triple(15, 0, 0),
        new Triple(-60, -10, 0), new Triple(10, 0, -10), new Triple(-15, 0, 0), new Triple(30, 0, 0)),

    /**
     * A raised fist, chin lifted.
     */
    WINNING(new Triple(-15, 0, 0), Triple.ZERO,
        new Triple(-120, -10, 0), new Triple(10, 0, -10), Triple.ZERO, new Triple(15, 0, 0)),

    /**
     * Seated - thighs forward, shins dropped, arms resting ahead.
     */
    SITTING(Triple.ZERO, Triple.ZERO,
        new Triple(-80, 20, 0), new Triple(-80, -20, 0), new Triple(-90, 10, 0), new Triple(-90, -10, 0)),

    /**
     * One leg extended behind, the opposite arm reaching high.
     */
    ARABESQUE(new Triple(-15, 0, 0), new Triple(10, 0, 0),
        new Triple(-140, -10, 0), new Triple(70, 0, -10), Triple.ZERO, new Triple(75, 0, 0)),

    /**
     * An archer's draw, one leg raised behind.
     */
    CUPID(Triple.ZERO, new Triple(10, 0, 0),
        new Triple(-90, -10, 0), new Triple(-75, 0, 10), Triple.ZERO, new Triple(75, 0, 0)),

    /**
     * A relaxed stand, head turned aside, one hip cocked.
     */
    CONFIDENT(new Triple(-10, 20, 0), new Triple(-2, 0, 0),
        new Triple(5, 0, 0), new Triple(5, 0, 0), new Triple(16, 2, 10), new Triple(0, -10, -4)),

    /**
     * The right hand raised to the brow, heels together.
     */
    SALUTE(Triple.ZERO, new Triple(5, 0, 0),
        new Triple(-124, -51, -35), new Triple(29, 0, 25), new Triple(0, -4, -2), new Triple(0, 4, 2)),

    /**
     * Flat on the back, arms raised stiffly upward.
     */
    DEATH(new Triple(-85, 0, 0), new Triple(-90, 0, 0),
        new Triple(-90, 10, 0), new Triple(-90, -10, 0), Triple.ZERO, Triple.ZERO),

    /**
     * The left hand buried in the face, head bowed into it.
     */
    FACEPALM(new Triple(45, -4, 1), new Triple(10, 0, 0),
        new Triple(18, -14, 0), new Triple(-72, 24, 47), new Triple(25, -2, 0), new Triple(-4, -6, -2)),

    /**
     * Sprawled back on one arm, legs folded to the side.
     */
    LAZING(new Triple(14, -12, 6), new Triple(5, 0, 0),
        new Triple(-40, 20, 0), new Triple(-4, -20, -10), new Triple(-88, 71, 0), new Triple(-88, 46, 0)),

    /**
     * A shrug caught mid-question, one arm flung high.
     */
    CONFUSED(new Triple(0, 30, 0), new Triple(0, 13, 0),
        new Triple(-22, 31, 10), new Triple(145, 22, -49), new Triple(6, -20, 0), new Triple(-6, 0, 0)),

    /**
     * Hands clasped before the waist, a slight bow.
     */
    FORMAL(new Triple(4, 0, 0), new Triple(4, 0, 0),
        new Triple(30, 22, -20), new Triple(30, -20, 21), new Triple(0, 0, 5), new Triple(0, 0, -5)),

    /**
     * Head hung low, shoulders slumped.
     */
    SAD(new Triple(63, 0, 0), new Triple(10, 0, 0),
        new Triple(-5, 0, 5), new Triple(-5, 0, -5), new Triple(-5, -10, 5), new Triple(-5, 16, -5)),

    /**
     * Arms and legs thrown wide, face lifted.
     */
    JOYOUS(new Triple(-11, 0, 0), new Triple(-4, 0, 0),
        new Triple(0, 0, 100), new Triple(0, 0, -100), new Triple(-8, 0, 60), new Triple(-8, 0, -60)),

    /**
     * Leaning back, one arm reaching for the sky.
     */
    STARGAZING(new Triple(-22, 25, 0), new Triple(-4, 10, 0),
        new Triple(-153, 34, -3), new Triple(4, 18, 0), new Triple(-4, 17, 2), new Triple(6, 24, 0)),

    /**
     * Arms straight out sideways - right roll positive, left negative, all else zero.
     */
    T_POSE(Triple.ZERO, Triple.ZERO,
        new Triple(0, 0, 90), new Triple(0, 0, -90), Triple.ZERO, Triple.ZERO),

    /**
     * The armor stand's own rest - arms hung at slight angles, legs splayed a degree.
     */
    AT_EASE(Triple.ZERO, Triple.ZERO,
        new Triple(-15, 0, 10), new Triple(-10, 0, -10), new Triple(1, 0, 1), new Triple(-1, 0, -1));

    /**
     * One limb's whole-degree rotation triple.
     *
     * @param pitchDegrees the pitch the limb lands at
     * @param yawDegrees the yaw the limb lands at
     * @param rollDegrees the roll the limb lands at
     */
    public record Triple(double pitchDegrees, double yawDegrees, double rollDegrees) {

        /**
         * The resting triple - no rotation on any axis.
         */
        public static final @NotNull Triple ZERO = new Triple(0, 0, 0);

    }

    /**
     * The head triple.
     */
    private final @NotNull Triple head;

    /**
     * The torso triple.
     */
    private final @NotNull Triple body;

    /**
     * The right arm triple.
     */
    private final @NotNull Triple rightArm;

    /**
     * The left arm triple.
     */
    private final @NotNull Triple leftArm;

    /**
     * The right leg triple.
     */
    private final @NotNull Triple rightLeg;

    /**
     * The left leg triple.
     */
    private final @NotNull Triple leftLeg;

}
