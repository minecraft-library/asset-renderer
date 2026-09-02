package lib.minecraft.refharness;

/**
 * What a sweep asks of the pose freezes - the mesh as authored, the mesh its model poses standing
 * still, or that same mesh walking.
 *
 * <p>Three sweeps' worth of state that used to be two system properties read once per JVM, which is
 * what made a posed run a boot of its own. Nothing about the freezes needs that: both of
 * {@code SkipSetupAnimMixin}'s redirects decide per render and read the value each time, so a run
 * can change gait between sweeps as long as it changes it in the one direction that is safe.
 *
 * <p><b>The order of these constants is the only order a run may visit them in</b>, and
 * {@link HarnessMode#sweeps} sorts by it. A freeze SKIPS {@code setupAnim}; it does not restore what
 * a previous call wrote. Model parts live on long-lived models, so a posed sweep leaves every bone
 * it touched holding a posed value, and a {@link #BIND} sweep after one would draw that rather than
 * the authored pose - silently, and over the seven sub-trees that exist to be the authored pose.
 * Ascending, no sweep can be handed a mesh a later gait mutated.
 */
public enum Gait {

    /**
     * The mesh as authored. {@code setupAnim} is skipped, so every {@code ModelPart} keeps the
     * {@code PartPose} its {@code createBodyLayer} gave it, and the seven static sub-trees are ground
     * truth for exactly that.
     */
    BIND(false, false),

    /**
     * The mesh its model poses at the gait a subject standing still is in. The freezes are off and
     * {@code ageInTicks} is the frame's tick, but the two figures a stride is carried on stay at the
     * zero a subject nothing has moved holds them at.
     */
    IDLE(true, false),

    /**
     * {@link #IDLE} with a stride under it - the same subjects, schedule and canvases, with
     * {@code walkAnimationPos} and {@code walkAnimationSpeed} driven at the amplitude vanilla clamps
     * its own accumulation to. A subject cannot be standing still and walking at one tick, which is
     * why this is a second sub-tree rather than a second reading of the first.
     */
    WALK(true, true);

    private final boolean posed;
    private final boolean walking;

    Gait(boolean posed, boolean walking) {
        this.posed = posed;
        this.walking = walking;
    }

    /**
     * Whether this gait draws the mesh its model poses rather than the mesh as authored, which is
     * what turns the two freezes off.
     *
     * @return true when {@code setupAnim} runs
     */
    public boolean posed() {
        return this.posed;
    }

    /**
     * Whether this gait drives the two render-state figures a stride is carried on.
     *
     * @return true when the subject walks
     */
    public boolean walking() {
        return this.walking;
    }

}
