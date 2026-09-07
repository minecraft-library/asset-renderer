package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The capture-then-compile tail every tier builder shares - container steps, stride riding,
 * hover, the style period, age scoping, toggles and the final snapshot into the portable style
 * value.
 *
 * <p>Each tier contributes only its limb vocabulary over the shared capture engine; the tail
 * verbs return the concrete builder through {@link #self()}, so a chain reads the same in any
 * verb order.
 */
@Parity(subject = Subject.ENTITY)
abstract sealed class PoseBuilder<B extends PoseBuilder<B>>
    permits HumanoidPose.Builder, QuadrupedPose.Builder, CustomPose.Builder {

    /**
     * The capture engine the tier selectors and the tail verbs write into.
     */
    final @NotNull PoseScript.Capture capture = new PoseScript.Capture();
    private final @NotNull String styleId;
    private final @NotNull List<String> toggles = new ArrayList<>();
    private @NotNull Optional<Age> age = Optional.of(Age.ADULT);

    PoseBuilder(@NotNull String styleId) {
        this.styleId = styleId;
    }

    /**
     * Answers this builder as its concrete type, so the tail verbs chain covariantly.
     *
     * @return this builder
     */
    abstract @NotNull B self();

    /**
     * Stances one bone by its mesh name - the escape every tier shares, for a part outside the
     * roster its vocabulary names. The name is taken literally, so the stance lands on that bone
     * and climbs to no articulation above it, and a name the target row does not declare refuses
     * on a strict install and drops with a recorded line on a tolerant one.
     *
     * <p>The stance carries the limb-length aim convention: an aim solve treats the bone as
     * resting pointing down its length and takes the quarter-turn pitch offset. A bone that aims
     * its facing direction wants a tier's own head selector instead.
     *
     * @param bone the bone name, as the mesh names it
     * @param stance the stance lambda
     * @return this builder
     */
    public final @NotNull B bone(@NotNull String bone, @NotNull UnaryOperator<LimbStance> stance) {
        this.capture.stance(bone, PoseScript.AimAxis.DOWN, stance);
        return this.self();
    }

    /**
     * Captures one container step - the same verb surface addressed at the whole figure's
     * seat, one ordered step per call.
     *
     * @param step the step lambda
     * @return this builder
     */
    public final @NotNull B container(@NotNull UnaryOperator<LimbStance> step) {
        this.capture.step(step);
        return this.self();
    }

    /**
     * Rides the universal stride drivers, so the shipped walking math runs live under the
     * built style; the additive {@code -By} verbs are the writes that compose with it,
     * while an absolute rotation over a striding channel refuses at compile.
     *
     * @return this builder
     */
    public final @NotNull B keepStride() {
        this.capture.keepStride();
        return this.self();
    }

    /**
     * Lifts the whole figure on a container step and dips it once per period - a nonzero
     * bob makes the style move, a zero bob keeps the lift still.
     *
     * @param liftPixels how far the figure lifts, in model pixels
     * @param bobPixels the dip-and-return excursion around the lift
     * @return this builder
     */
    public final @NotNull B hover(double liftPixels, double bobPixels) {
        this.capture.hover(liftPixels, bobPixels);
        return this.self();
    }

    /**
     * Declares the style's own excursion period; unset rides the catalog period.
     *
     * @param seconds the period in seconds
     * @return this builder
     */
    public final @NotNull B period(double seconds) {
        this.capture.period(seconds);
        return this.self();
    }

    /**
     * Restricts the style to one age; {@link Age#ADULT} is the default, because a custom
     * write lands on the adult body pose and a folded baby form would render it half-posed.
     *
     * @param age the age the style applies to
     * @return this builder
     */
    public final @NotNull B age(@NotNull Age age) {
        this.age = Optional.of(age);
        return this.self();
    }

    /**
     * Opts the style into every age - the author's explicit acceptance that a baby subject
     * renders the folded form under it.
     *
     * @return this builder
     */
    public final @NotNull B allAges() {
        this.age = Optional.empty();
        return this.self();
    }

    /**
     * Adds appearance bone toggles the style entails, appended in call order.
     *
     * @param toggles the toggle names
     * @return this builder
     */
    public final @NotNull B toggles(@NotNull String... toggles) {
        this.toggles.addAll(List.of(toggles));
        return this.self();
    }

    /**
     * Snapshots the captured script into the portable style value - the tier's last-touch
     * hook runs first, the source inventory is inferred from content, and a reserved id
     * refuses.
     *
     * @return the built style
     * @throws IllegalArgumentException if the style id is one the universal rows answer
     */
    public final @NotNull BuiltStyle build() {
        this.finishCapture(this.capture);
        return BuiltStyle.built(this.styleId, this.capture.script(), this.toggles, this.age);
    }

    /**
     * Adjusts the capture just before the script snapshots; the base leaves it untouched.
     *
     * @param capture the capture about to snapshot
     */
    void finishCapture(@NotNull PoseScript.Capture capture) {}

}
