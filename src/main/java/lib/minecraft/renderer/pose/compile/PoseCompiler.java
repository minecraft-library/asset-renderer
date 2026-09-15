package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseEvaluator;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.PosePredicate;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Ease;
import lib.minecraft.renderer.pose.author.LimbSelector;
import lib.minecraft.renderer.pose.author.PoseScript;
import lib.minecraft.renderer.pose.author.Rank;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.tensor.Vector3f;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lowers one built style against one target row - every unit conversion, every rest rebase and
 * every content refusal of the authoring surface lives here and nowhere else.
 *
 * <p>The one principle behind the lowering: <b>custom movement is additive on fresh driver
 * fields</b>. A shipped expression instance is never swapped or rebuilt - a splice references it
 * directly as the first operand of a double-width sum whose second operand reads a
 * {@code style$}-namespaced field. An undriven field answers zero, and {@code x + 0.0d} answers
 * {@code x}'s exact bits for every value but {@code -0.0}, so under every other style of the row
 * the woven graph evaluates to the shipped value and shipped renders stay bit-identical.
 *
 * <p>The raw hatch is the one splice that is not a sum, and it keeps the same promise by a gate
 * instead: the authored graph rides the true arm of a selection on the style's own
 * {@code style$<id>} field, whose false arm is the expression the channel already held. That
 * field holds at one under the style and is undriven, so zero, under every other - the same
 * reading a play site takes of a selection gate.
 *
 * <p>Every walk over a graph that can hold shipped instances carries an identity-keyed visited
 * or memo structure and short-circuits on an instance already seen, because a pose is a graph
 * whose nodes stand for enormously many paths and whose tree expansion does not terminate in
 * practice. That law binds the rest scans, the raw-hatch checks and the interning alike, and it
 * bans any recorded line from rendering an expression - diagnostics speak field, bone, channel
 * and count vocabulary only.
 *
 * <p>Two relationships are derived here that no author spells and no shipped row states
 * outright. An anatomical stance - every tier verb but the hat - lands on the articulation the
 * pose as it shipped turns for that part, read off the mesh's own parents; and a bone the
 * shipped state silhouettes show riding another bone's frame is seated on it, its pivot carried
 * by the leader's held stance as an ordinary additive displacement. A seat is a position and
 * never a rotation, and a pair merely adjacent at bind is a contact the audit measures and no
 * seat.
 *
 * <p>Units convert exactly once at this boundary: degrees to radians through
 * {@link Math#toRadians}, model pixels across the mesh's flattened factor, seconds passing
 * through untouched. Refusals are {@link IllegalArgumentException} - authoring errors, neither
 * load nor render failures - and each records its context as an {@code ERROR} entry immediately
 * before the throw.
 */
@Parity(subject = Subject.ENTITY)
public final class PoseCompiler {

    /**
     * Ticks one real second spans at the clock rate every clip second is defined against.
     */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * The catalog period a compile without a target catalog frames its strip window from.
     */
    private static final int DEFAULT_PERIOD_TICKS = 24;

    /**
     * The prefix every synthetic driver field carries, spelled by no vanilla render-state field.
     */
    private static final String FIELD_PREFIX = "style$";

    /**
     * The coined bone segment a container step's fields carry - no mesh names a bone this way.
     */
    private static final String CONTAINER_SEGMENT = "$container";

    /**
     * The channel-token segment a uniform scale's one shared field carries across its three axes.
     */
    private static final String SCALE_SEGMENT = "scale";

    /**
     * The suffix the hover bob's second summand carries - two drivers cannot share one field.
     */
    private static final String BOB_SUFFIX = "_bob";

    /**
     * The name vanilla reserves for the part every bone hangs from, which the mesh names nowhere.
     */
    private static final String ROOT_PART = "root";

    /**
     * The least a seat carries a follower by, in model units, for the carry to be written at all.
     */
    private static final float SEAT_EPSILON = 1e-4f;

    /**
     * The row ordinal standing for a bone the leg roster holds nowhere, which no row answers to.
     */
    private static final int NO_ROW = -1;

    /**
     * How many rows of legs a diagonal pairing is defined on - a front row and a hind one.
     */
    private static final int COUPLET_ROWS = 2;

    /**
     * The side index of the leg the frontmost diagonal pair is led by, counting the two sides in
     * the order {@link Side} declares them.
     */
    private static final int LEADING_SIDE = 1;

    private PoseCompiler() {}

    // ------------------------------------------------------------------------------------
    // public surface
    // ------------------------------------------------------------------------------------

    /**
     * One compiled style: the row's shipped pose with the splices woven in, the flat style row
     * that drives them, and what the lowering had to leave out.
     *
     * @param pose the row's shipped pose with splices woven in - shipped instances referenced,
     *     never rebuilt
     * @param style one flat row carrying sources, drivers, toggles and age
     * @param drops the addresses that reached nothing on the target mesh, in first-written
     *     order - what a strict install refuses over and a tolerant one proceeds past
     * @param diagnostics the scope this compile recorded into
     */
    /**
     * One address a compile resolved to nothing, and what kind of address it was.
     *
     * <p>Three different things reach this list and only one of them is a bone: a name the author
     * wrote that the mesh does not declare, a selector whose resolution came back empty, and a
     * gait number keyed on a rank the mesh carries no row for. Held as one string apiece, every
     * message about the list called all three of them bones - which told an author that a timing
     * number was a bone it had never written.
     */
    public sealed interface Unreached {

        /**
         * How a message names this address, in the author's own terms and with its kind said.
         *
         * @return the reading
         */
        @NotNull String describe();

        /**
         * A bone the author named that the target mesh does not declare.
         *
         * @param bone the bone name, as the author wrote it
         */
        record Named(@NotNull String bone) implements Unreached {

            /** {@inheritDoc} */
            @Override
            public @NotNull String describe() {
                return "bone '" + this.bone + "'";
            }

        }

        /**
         * A selector the target mesh answered with no bone at all.
         *
         * @param reading the address in the author's own terms
         */
        record Selection(@NotNull String reading) implements Unreached {

            /** {@inheritDoc} */
            @Override
            public @NotNull String describe() {
                return "selector " + this.reading;
            }

        }

        /**
         * A gait number keyed on a rank the target mesh carries no row for.
         *
         * @param verb the verb that states the number
         * @param rank the rank it is keyed on
         */
        record Keyed(@NotNull String verb, @NotNull Rank rank) implements Unreached {

            /** {@inheritDoc} */
            @Override
            public @NotNull String describe() {
                return this.verb + "(" + this.rank + ")";
            }

        }

        /**
         * Renders a list of unreached addresses as one comma-separated clause.
         *
         * @param unreached the addresses that reached nothing
         * @return the clause, empty where nothing went unreached
         */
        static @NotNull String describeAll(@NotNull Collection<? extends Unreached> unreached) {
            return unreached.stream().map(Unreached::describe).collect(Collectors.joining(", "));
        }

    }

    public record Compiled(
        @NotNull EntityPose pose,
        @NotNull PoseStyle style,
        @NotNull ConcurrentList<Unreached> drops,
        @NotNull StyleDiagnostics diagnostics
    ) {}

    /**
     * Compiles a built style against one target row, recording into a standalone quiet
     * diagnostics root.
     *
     * @param style the built style to lower
     * @param row the target row whose mesh and shipped pose the lowering runs against
     * @return the compiled style
     * @throws IllegalArgumentException if any lowering rule refuses the authored content
     */
    public static @NotNull Compiled compile(@NotNull BuiltStyle style, @NotNull Entity row) {
        StyleDiagnostics root = StyleDiagnostics.root("styles", StyleDiagnostics.Output.NONE, null);
        return compile(style, row, root.child(row.id().toString()).child(style.styleId()));
    }

    /**
     * Compiles a built style against one target row, recording into the given scope.
     *
     * @param style the built style to lower
     * @param row the target row whose mesh and shipped pose the lowering runs against
     * @param scope the diagnostics scope the compile records under
     * @return the compiled style
     * @throws IllegalArgumentException if any lowering rule refuses the authored content
     */
    public static @NotNull Compiled compile(@NotNull BuiltStyle style, @NotNull Entity row,
                                            @NotNull StyleDiagnostics scope) {
        return compile(style, row, row.pose(), scope, new GraphInterner());
    }

    /**
     * Compiles a built style against one target row over a caller-held interner pool - the arm
     * an installer reuses so splices stack and subtrees unify across every style added to one
     * row. The evidence pose is the row's pose as it shipped: an installer whose row has been
     * woven by earlier installs hands the pristine one here, so what the joint rule reads as
     * "the pose turns this bone" is vanilla's articulation and never an author's splice.
     *
     * @param style the built style to lower
     * @param row the target row whose mesh and shipped pose the lowering runs against
     * @param evidence the row's pose as it shipped, read for which bones vanilla articulates
     * @param scope the diagnostics scope the compile records under
     * @param pool the interner pool shared across the row's compiles
     * @return the compiled style
     * @throws IllegalArgumentException if any lowering rule refuses the authored content
     */
    public static @NotNull Compiled compile(@NotNull BuiltStyle style, @NotNull Entity row,
                                     @NotNull EntityPose evidence, @NotNull StyleDiagnostics scope,
                                     @NotNull GraphInterner pool) {
        double window = row.styles().periodTicks() / (double) TICKS_PER_SECOND;
        return new Lowering(style, row.pose(), evidence, row.model(), Optional.empty(), scope, pool,
            Optional.empty(), window).lower();
    }

    /**
     * Compiles a built style against one distinct layer row - the same lowering run against the
     * layer's pose and mesh, with rebased splices reading per-layer fields under the coined
     * layer coordinate. The returned style carries a driver for every field this compile's
     * splices read - per-layer spellings for the rebased ones, the shared spelling for the
     * rest - so the result stands alone, and an installer merging rows first-wins keeps the
     * body compile's copy of a shared field. With no catalog in reach the strip window frames
     * at the default catalog period.
     *
     * @param style the built style to lower
     * @param pose the layer's shipped pose
     * @param mesh the layer's mesh
     * @param layer the coined layer coordinate the rebased fields are spelled under
     * @param scope the diagnostics scope the compile records under
     * @return the compiled layer arm
     * @throws IllegalArgumentException if any lowering rule refuses the authored content
     */
    public static @NotNull Compiled compileLayer(@NotNull BuiltStyle style, @NotNull EntityPose pose,
                                                 @NotNull EntityModelData mesh, @NotNull String layer,
                                                 @NotNull StyleDiagnostics scope) {
        return compileLayer(style, pose, pose, mesh, layer, scope, new GraphInterner(), Optional.empty(),
            DEFAULT_PERIOD_TICKS);
    }

    /**
     * Compiles a built style against one distinct layer row over a caller-held interner pool
     * and, when given, the body compile's play site - so the layer's woven row carries the very
     * clip and site instances the body's does. The target catalog's period frames the default
     * strip window exactly as the body compile's does, and the evidence pose is the layer's as
     * it shipped, for the reason the body compile takes one.
     *
     * @param style the built style to lower
     * @param pose the layer's shipped pose
     * @param evidence the layer's pose as it shipped, read for which bones vanilla articulates
     * @param mesh the layer's mesh
     * @param layer the coined layer coordinate the rebased fields are spelled under
     * @param scope the diagnostics scope the compile records under
     * @param pool the interner pool shared across the entity's compiles
     * @param playSite the body compile's play site to carry by instance; empty builds an
     *     identical site of this compile's own
     * @param periodTicks the target catalog's period in ticks - the strip window where the
     *     script declares none
     * @return the compiled layer arm
     * @throws IllegalArgumentException if any lowering rule refuses the authored content
     */
    public static @NotNull Compiled compileLayer(@NotNull BuiltStyle style, @NotNull EntityPose pose,
                                          @NotNull EntityPose evidence, @NotNull EntityModelData mesh,
                                          @NotNull String layer, @NotNull StyleDiagnostics scope,
                                          @NotNull GraphInterner pool, @NotNull Optional<EntityPose.Clip> playSite,
                                          int periodTicks) {
        double window = periodTicks / (double) TICKS_PER_SECOND;
        return new Lowering(style, pose, evidence, mesh, Optional.of(layer), scope, pool, playSite, window).lower();
    }

    // ------------------------------------------------------------------------------------
    // the lowering worker
    // ------------------------------------------------------------------------------------

    /**
     * One compile run - the script folded into per-channel plans, then lowered rule by rule.
     */
    private static final class Lowering {

        private final @NotNull BuiltStyle style;
        private final @NotNull PoseScript script;
        private final @NotNull EntityPose shipped;

        /**
         * The row's pose as it shipped - what the joint rule reads for which bones vanilla
         * articulates. On a row earlier installs have woven, {@link #shipped} carries their
         * splices, and a splice is the author's evidence, never vanilla's.
         */
        private final @NotNull EntityPose evidence;
        private final @NotNull EntityModelData mesh;
        private final @NotNull LimbRoster roster;
        private final @NotNull Optional<String> layer;
        private final @NotNull StyleDiagnostics scope;
        private final @NotNull StyleDiagnostics events;
        private final @NotNull GraphInterner pool;
        private final @NotNull Optional<EntityPose.Clip> sharedSite;
        private final double windowSeconds;
        private final float flattened;
        private final @NotNull Set<String> drivenFields;

        /**
         * Every driver this compile emits onto its returned row, in emission order.
         */
        private final @NotNull LinkedHashMap<String, StyleDriver> drivers = new LinkedHashMap<>();

        /**
         * The distinct splice fields lowered, per-layer and shared spellings alike.
         */
        private final @NotNull Set<String> fields = new LinkedHashSet<>();

        /**
         * The addresses that reached nothing, in first-written order and each recorded once -
         * the set is what holds both, so a recording site adds without asking.
         */
        private final @NotNull Set<Unreached> dropped = new LinkedHashSet<>();

        /**
         * The per-bone channel plans, in first-touch order.
         */
        private final @NotNull LinkedHashMap<String, LinkedHashMap<PoseChannel, ChannelPlan>> bonePlans = new LinkedHashMap<>();

        /**
         * The per-bone uniform scale factors, the last authored factor winning.
         */
        private final @NotNull LinkedHashMap<String, Double> scalePlans = new LinkedHashMap<>();

        /**
         * The authored container steps, one channel-plan map per step in author order.
         */
        private final @NotNull List<LinkedHashMap<PoseChannel, ChannelPlan>> stepPlans = new ArrayList<>();

        /**
         * The container channels already claimed, one field holding one driver.
         */
        private final @NotNull Set<PoseChannel> containerClaimed = EnumSet.noneOf(PoseChannel.class);

        /**
         * The keyframed timelines, keyed by stanced bone in first-touch order.
         */
        private final @NotNull List<TrackPlan> trackPlans = new ArrayList<>();

        /**
         * Whether the head's implicit hat mirror is in play - the hat rides the head's instances.
         */
        private boolean hatMirror;

        private int clipChannelCount;
        private int containerStepCount;

        private Lowering(@NotNull BuiltStyle style, @NotNull EntityPose shipped, @NotNull EntityPose evidence,
                         @NotNull EntityModelData mesh, @NotNull Optional<String> layer,
                         @NotNull StyleDiagnostics scope, @NotNull GraphInterner pool,
                         @NotNull Optional<EntityPose.Clip> sharedSite, double defaultWindow) {
            this.style = style;
            this.script = style.script();
            this.shipped = shipped;
            this.evidence = evidence;
            this.mesh = mesh;
            this.roster = LimbRoster.of(mesh);
            this.layer = layer;
            this.scope = scope;
            this.events = scope.child("compile");
            this.pool = pool;
            this.sharedSite = sharedSite;
            this.windowSeconds = this.script.periodSeconds().orElse(defaultWindow);
            this.flattened = mesh.getFlattenedScale();
            this.drivenFields = this.script.keepStride()
                ? Set.of("ageInTicks", "walkAnimationSpeed", "walkAnimationPos")
                : Set.of();
        }

        /**
         * Runs the whole lowering and assembles the compiled result.
         */
        private @NotNull Compiled lower() {
            if (!this.shipped.isReadable())
                throw this.refuse("Style '%s' cannot compile against a pose that could not be read: %s",
                    this.style.styleId(), this.shipped.refusal().orElse(""));

            Rules.period(this.style, this.events);
            Rules.ranks(this.style, this.roster, this.events);
            Rules.cycleOffsets(this.style, this.events);
            Rules.axes(this.style, this.roster, this.events);
            Rules.absentRanks(this.style, this.roster, this.dropped, this.events);
            Rules.crossedSides(this.style, this.roster, this.events);
            this.pool.adopt(this.shipped);
            this.foldStances();
            this.seatFollowers();

            if (this.script.keepStride())
                this.strideDrivers();

            LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones = this.lowerBones();
            List<Map<PoseChannel, PoseExpr>> container = this.lowerContainer();
            Optional<EntityPose.Clip> site = this.lowerClip();
            this.lowerRaws(bones);

            if (!this.dropped.isEmpty())
                this.events.warn("unreached: %d address(es) [%s] reach nothing on a mesh declaring [%s]",
                    this.dropped.size(), Unreached.describeAll(this.dropped),
                    String.join(", ", this.mesh.getBones().keySet()));
            this.events.info("lowering inventory: %d driver(s), %d splice field(s), %d clip channel(s), %d container step(s)",
                this.drivers.size(), this.fields.size(), this.clipChannelCount, this.containerStepCount);

            ConcurrentList<EntityPose.Clip> clips = this.shipped.clips();
            if (site.isPresent()) {
                List<EntityPose.Clip> played = new ArrayList<>(clips);
                played.add(site.get());
                clips = Concurrent.newUnmodifiableList(played);
            }
            EntityPose woven = new EntityPose(
                Concurrent.newUnmodifiableList(container),
                Concurrent.newUnmodifiableMap(bones),
                clips,
                this.shipped.refusal(),
                this.shipped.states());
            PoseStyle row = new PoseStyle(this.style.styleId(), this.style.sources(),
                Concurrent.newUnmodifiableMap(this.drivers), this.style.toggles(), this.style.age(),
                this.declaredPeriodTicks());
            return new Compiled(woven, row, Concurrent.newUnmodifiableList(this.dropped), this.scope);
        }


        /**
         * The declared period lowered to whole ticks at the clock rate, empty where the script
         * rides the catalog period.
         */
        private @NotNull Optional<Integer> declaredPeriodTicks() {
            return this.script.periodSeconds().isPresent()
                ? Optional.of((int) Math.round(this.script.periodSeconds().getAsDouble() * TICKS_PER_SECOND))
                : Optional.empty();
        }

        /**
         * Copies the universal stride trio onto the built row, so the shipped walk math runs live.
         */
        private void strideDrivers() {
            if (this.layer.isPresent()) return;
            this.drivers.put("ageInTicks",
                new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
            this.drivers.put("walkAnimationSpeed",
                new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
            this.drivers.put("walkAnimationPos",
                new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        }

        /**
         * Folds the captured stances into per-channel plans, filtering written bones against the
         * mesh roster. Clip tracks collect whether or not the mesh declares their bone - a clip
         * channel of an absent bone filters at render, and keeping it makes the clip identical
         * across every row one style weaves into.
         */
        private void foldStances() {
            for (PoseScript.Stance stance : this.script.stances()) {
                if (stance.limb().isEmpty()) {
                    this.foldStep(stance);
                    continue;
                }
                switch (stance.limb().get()) {
                    case PoseScript.Limb.Named named -> this.foldNamed(named, stance);
                    case PoseScript.Limb.Selected selected -> this.foldSelected(selected, stance);
                }
            }
        }

        /**
         * Folds one stance addressed at a bone the author named.
         *
         * <p>A name the mesh does not declare drops, and its clip tracks are collected anyway - a
         * clip channel of an absent bone filters at render, and keeping it makes the clip identical
         * across every row one style weaves into.
         */
        private void foldNamed(PoseScript.Limb.@NotNull Named limb,
                               @NotNull PoseScript.Stance stance) {
            if (this.implicitHatMirror(stance)) {
                this.hatMirror = true;
                return;
            }
            if (!this.mesh.getBones().containsKey(limb.bone())) {
                this.dropped.add(new Unreached.Named(limb.bone()));
                for (PoseScript.Track track : stance.of(PoseScript.Track.class))
                    this.trackPlans.add(new TrackPlan(Optional.of(limb.bone()), track));
                return;
            }
            PoseScript.Limb.Named landed = this.articulated(limb);
            for (PoseScript.Track track : stance.of(PoseScript.Track.class))
                this.trackPlans.add(new TrackPlan(Optional.of(landed.bone()), track));
            this.foldLimb(landed, stance);
        }

        /**
         * Folds one selected stance onto every leg the target mesh answers with.
         *
         * <p>The roster resolves the selector against this row's own bones, so one authored stance
         * lands on two legs of a walker and eight of a crawler without the author counting either.
         * A member's side is the side of the leg it hangs off and never the side its own name
         * claims - two boots in the corpus are cross-parented by vanilla, so a segment's name is
         * the one thing about it that cannot be trusted.
         *
         * <p>A selector no bone answers still contributes its clip tracks, carrying no bone. What
         * a track states about the clip - its length, and whether the clip loops or holds - is a
         * property of the style rather than of the mesh, so a subject answering fewer legs must
         * not answer with a shorter clip, a differently gated one, or none.
         */
        private void foldSelected(@NotNull PoseScript.Limb.Selected selected,
                                  @NotNull PoseScript.Stance stance) {
            List<String> members = LimbRoster.members(selected.selector(), this.mesh, () -> this.roster);
            // The chain note stays on this side of the seam: the resolver answers bones and records
            // nothing, so an install resolving the same address adds no entry it does not add today.
            if (selected.selector() instanceof LimbSelector.Family
                && LimbFamily.chained(this.mesh, members))
                this.events.info("family: %s hangs each member off the one before it, so one stance compounds down the chain",
                    selected.reading());
            if (members.isEmpty()) {
                boolean derived = selected.selector() instanceof LimbSelector.Legs legs
                    && legs.stamp() == LimbSelector.Stamp.FAR;
                this.events.info("selector: %s reaches no bone this mesh declares",
                    selected.reading());
                if (!derived) this.dropped.add(new Unreached.Selection(selected.reading()));
                for (PoseScript.Track track : stance.of(PoseScript.Track.class))
                    this.trackPlans.add(new TrackPlan(Optional.empty(), track));
                return;
            }
            this.events.info("selector: %s reaches %d bone(s) %s",
                selected.reading(), members.size(), members);
            for (String member : members) {
                double shift = this.shiftOf(selected.selector(), member);
                PoseScript.Stance travelled =
                    scaled(stance, this.gainOf(selected.selector(), member));
                PoseScript.Limb.Named named =
                    new PoseScript.Limb.Named(member, selected.axis(), selected.anatomical());
                PoseScript.Limb.Named landed = this.articulated(named);
                for (PoseScript.Track track : travelled.of(PoseScript.Track.class))
                    this.trackPlans.add(new TrackPlan(Optional.of(landed.bone()), track, shift));
                this.foldLimb(landed, travelled);
            }
        }

        /**
         * How far into the cycle one leg's copy of a gait's shape starts, as a share of it.
         *
         * <p>Two terms sum here and both are read off the LEG rather than off the address that
         * reached it. Its row's own offset: an address naming a rank takes that rank's, and one
         * naming none reaches every row, so the offset is the leg's own row's - or a shape stated
         * once over the whole roster would take one row's offset or none at all, and a gait written
         * the way the verb set is meant to be written would walk in lockstep. And its side's: the
         * far side of every pair starts behind the near one, on the side the mesh hangs the leg off
         * rather than the side the bone's own name claims.
         *
         * <p>A row one bone paints whole carries no side, so the side term is zero there and the
         * row takes one copy of the shape rather than two a share of a cycle apart.
         *
         * @param selector the address the stance was written with
         * @param bone the leg the roster answered, as the mesh names it
         * @return the share of a cycle this leg starts into
         */
        private double shiftOf(@NotNull LimbSelector selector, @NotNull String bone) {
            if (this.script.cycle().isEmpty()) return 0d;
            if (!(selector instanceof LimbSelector.Legs legs)) return 0d;
            PoseScript.Cycle cycle = this.script.cycle().get();
            Optional<LimbRoster.Member> placed = this.roster.placementOf(bone);
            return this.rankShift(cycle, legs, placed)
                + sideShift(cycle, placed)
                + coupletShift(cycle, placed)
                + depthShift(cycle, placed);
        }

        /**
         * The offset one bone takes from how far below its leg's root it sits - none at the root,
         * and one more share of the cycle for every bone between it and there.
         */
        private static double depthShift(@NotNull PoseScript.Cycle cycle,
                                         @NotNull Optional<LimbRoster.Member> placed) {
            if (cycle.trail().isEmpty() || placed.isEmpty()) return 0d;
            return placed.get().depth() * cycle.trail().get().cycles();
        }

        /**
         * What one bone multiplies its leg's travel by for sitting below the root - the whole of it
         * at the root, and one more multiple for every bone between it and there.
         *
         * <p>Multiplied down the chain rather than raised to the depth, because that is the
         * relationship the verb states and the number the author worked out: a fade of two thirds
         * two bones down is two thirds of two thirds, which is what an author writes and not
         * necessarily what a general power answers to the last bit.
         */
        private static double depthGain(@NotNull PoseScript.Cycle cycle,
                                        @NotNull Optional<LimbRoster.Member> placed) {
            if (cycle.trail().isEmpty() || placed.isEmpty()) return 1d;
            double faded = 1d;
            for (int below = 0; below < placed.get().depth(); below++)
                faded *= cycle.trail().get().fade();
            return faded;
        }

        /**
         * The offset one leg takes from the row it sits in.
         */
        private double rankShift(@NotNull PoseScript.Cycle cycle, LimbSelector.@NotNull Legs legs,
                                 @NotNull Optional<LimbRoster.Member> placed) {
            return this.rowValue(cycle.phases(), legs, placed, 0d);
        }

        /**
         * What one leg multiplies the shape's travel by - the multiple of the row it sits in, and
         * the whole of the travel where no gait named that row.
         *
         * @param selector the address the stance was written with
         * @param bone the leg the roster answered, as the mesh names it
         * @return the multiple this leg's copy of the shape travels
         */
        private double gainOf(@NotNull LimbSelector selector, @NotNull String bone) {
            if (this.script.cycle().isEmpty()) return 1d;
            if (!(selector instanceof LimbSelector.Legs legs)) return 1d;
            PoseScript.Cycle cycle = this.script.cycle().get();
            Optional<LimbRoster.Member> placed = this.roster.placementOf(bone);
            return this.rowValue(cycle.gains(), legs, placed, 1d) * depthGain(cycle, placed);
        }

        /**
         * The number one leg takes from a per-row table.
         *
         * <p>An address naming a rank takes that rank's entry. One naming none reaches every row,
         * so the entry is the LEG's own row's - read per leg rather than per address, or a shape
         * stated once over the whole roster would take one row's number or none at all.
         *
         * @param byRank the table a gait filled, in rank order
         * @param legs the address the stance was written with
         * @param placed where the roster holds this leg
         * @param none what a leg no entry reaches takes
         * @return the number this leg takes
         */
        private double rowValue(@NotNull Map<Rank, Double> byRank, LimbSelector.@NotNull Legs legs,
                                @NotNull Optional<LimbRoster.Member> placed, double none) {
            if (byRank.isEmpty()) return none;
            if (legs.rank().isPresent()) return byRank.getOrDefault(legs.rank().get(), none);

            int row = placed.map(LimbRoster.Member::row).orElse(NO_ROW);
            double held = none;
            for (Map.Entry<Rank, Double> entry : byRank.entrySet())
                if (this.roster.row(entry.getKey()).filter(at -> at.ordinal() == row).isPresent())
                    held = entry.getValue();
            return held;
        }

        /**
         * The offset one leg takes from the side it sits on - the far side's, and nothing on the
         * near side or on a row carrying no side at all.
         */
        private static double sideShift(@NotNull PoseScript.Cycle cycle,
                                        @NotNull Optional<LimbRoster.Member> placed) {
            if (cycle.opposed().isEmpty()) return 0d;
            return placed.flatMap(LimbRoster.Member::side).filter(Side.LEFT::equals).isPresent()
                ? cycle.opposed().getAsDouble()
                : 0d;
        }

        /**
         * The offset one leg takes from the diagonal pair it belongs to - none on the leading
         * pair, and the whole of what the trot states on the other.
         *
         * <p>Which pair a leg is in is its row and its side read together, counting the row from
         * the front and the two sides in the order a body meets them. The frontmost right leg is
         * always in the leading pair, so which pair leads travels in the shape's own bound order
         * rather than in an argument nothing about a mesh predicts.
         *
         * <p>A leg carrying no side is in neither pair, which is a mesh {@link #validateAxes} has
         * already refused a trot on - the term reads its answer rather than guessing one.
         */
        private static double coupletShift(@NotNull PoseScript.Cycle cycle,
                                           @NotNull Optional<LimbRoster.Member> placed) {
            if (cycle.coupled().isEmpty() || placed.isEmpty()) return 0d;
            Optional<Side> side = placed.get().side();
            if (side.isEmpty()) return 0d;
            int pair = (placed.get().row() + LEADING_SIDE - side.get().ordinal()) % COUPLET_ROWS;
            return pair == 0 ? 0d : cycle.coupled().getAsDouble();
        }

        /**
         * The limb a stance lands on - an anatomical name resolves to the articulation the
         * shipped pose turns for that part: the named bone itself where the pose writes a
         * rotation channel of it, else up the mesh's own parents to the nearest ancestor whose
         * rotation the pose writes, and the named bone again where the climb stops short. A
         * literal name is itself.
         *
         * <p>The climb passes only through a part seated exactly at its parent's pivot: such a
         * part turns about the very point the parent does, so stancing it apart from the
         * parent could only leave its siblings behind, which vanilla - never writing it -
         * never does. A bone with a pivot of its own is a joint whatever the pose does with it,
         * and stops the climb on itself.
         *
         * <p>Read off the pose as it shipped and the mesh's own parents, never off a list of
         * names: an equine {@code head} lands on {@code head_parts} because the pose turns the
         * neck assembly and never the head cube, while a wolf's {@code head} lands on itself
         * because the pose turns that shell.
         */
        private PoseScript.Limb.@NotNull Named articulated(PoseScript.Limb.@NotNull Named limb) {
            if (!limb.anatomical() || this.writesRotation(limb.bone())) return limb;
            String joint = limb.bone();
            while (!this.writesRotation(joint)) {
                EntityModelData.Bone part = this.mesh.getBones().get(joint);
                String parent = part.getParent();
                if (parent == null || parent.equals(joint) || !this.mesh.getBones().containsKey(parent)
                    || !part.getPivot().equals(Vector3f.ZERO))
                    return limb;
                joint = parent;
            }
            this.events.info("joint: '%s' lands on '%s' - the articulation the shipped pose turns for it",
                limb.bone(), joint);
            return new PoseScript.Limb.Named(joint, limb.axis(), true);
        }

        /**
         * Whether the pose as it shipped writes any rotation channel of a bone.
         */
        private boolean writesRotation(@NotNull String bone) {
            Map<PoseChannel, PoseExpr> channels = this.evidence.bones().get(bone);
            if (channels == null) return false;
            for (PoseChannel channel : channels.keySet())
                if (channel.kind() == PoseChannel.Kind.ROTATION) return true;
            return false;
        }

        /**
         * Folds one container step's verbs, refusing what a seat cannot carry.
         */
        private void foldStep(@NotNull PoseScript.Stance stance) {
            if (!stance.of(PoseScript.Aim.class).isEmpty())
                throw this.refuse("Style '%s' aims a container step - a step has no pivot to aim from",
                    this.style.styleId());
            if (!stance.of(PoseScript.Track.class).isEmpty())
                throw this.refuse("Style '%s' keys a timeline on a container step - a clip channel names a bone",
                    this.style.styleId());
            if (!stance.of(PoseScript.Scale.class).isEmpty())
                throw this.refuse("Style '%s' scales a container step, which reaches no bone below it",
                    this.style.styleId());
            LinkedHashMap<PoseChannel, ChannelPlan> plan = new LinkedHashMap<>();
            this.foldVerbs(stance, plan);
            this.stepPlans.add(plan);
        }

        /**
         * Folds one limb stance's verbs into the bone's accumulated plan.
         */
        private void foldLimb(PoseScript.Limb.@NotNull Named limb, @NotNull PoseScript.Stance stance) {
            LinkedHashMap<PoseChannel, ChannelPlan> plan =
                this.bonePlans.computeIfAbsent(limb.bone(), bone -> new LinkedHashMap<>());
            this.foldVerbs(stance, plan);
            for (PoseScript.Scale scale : stance.of(PoseScript.Scale.class))
                this.scalePlans.put(limb.bone(), scale.factor());
            for (PoseScript.Aim aim : stance.of(PoseScript.Aim.class))
                this.foldAim(limb, aim, plan);
        }

        /**
         * Folds one stance's writes and waves - the last absolute write states where a channel
         * lands, every additive write shifts it, and one wave rides the folded stance.
         */
        private void foldVerbs(@NotNull PoseScript.Stance stance,
                               @NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan) {
            for (PoseScript.Write write : stance.of(PoseScript.Write.class)) {
                ChannelPlan channel = plan.computeIfAbsent(write.channel(), key -> new ChannelPlan());
                if (write.absolute()) channel.absoluteDegrees = write.value();
                else channel.additive += write.value();
            }
            for (PoseScript.Sway sway : stance.of(PoseScript.Sway.class))
                this.foldWave(plan, sway.axis().channel(), sway, null);
            for (PoseScript.Spin spin : stance.of(PoseScript.Spin.class))
                this.foldWave(plan, spin.axis().channel(), null, spin);
        }

        /**
         * Claims one channel's wave slot - one field holds one driver, so a second wave refuses.
         */
        private void foldWave(@NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan,
                              @NotNull PoseChannel channel,
                              @Nullable PoseScript.Sway sway, @Nullable PoseScript.Spin spin) {
            ChannelPlan folded = plan.computeIfAbsent(channel, key -> new ChannelPlan());
            if (folded.sway != null || folded.spin != null)
                throw this.refuse("Style '%s' waves channel '%s' twice on one target - one field holds one driver",
                    this.style.styleId(), channel.token());
            folded.sway = sway;
            folded.spin = spin;
        }

        /**
         * Whether a stance is the head's implicit hat mirror - a hat stance whose fragment list
         * is the very instance a head stance captured, which the automatic build-time copy
         * produces. An implicit mirror rides the head's lowered instances and drops silently where
         * a mesh lacks the shell, because the author never spelled it.
         *
         * <p>Reference identity is the whole test, and it is the test because a hat spelled by
         * hand to the same values captures into its own list. Sharing a list by reference is not
         * unique to the hat copy - a selector pair asked to read one side's stance as written
         * shares one too - so the head-named sibling is what narrows it to this one idiom.
         */
        private boolean implicitHatMirror(@NotNull PoseScript.Stance stance) {
            if (stance.limb().flatMap(PoseScript.Limb::named)
                .filter("hat"::equals).isEmpty()) return false;
            if (stance.fragments().isEmpty()) return false;
            for (PoseScript.Stance other : this.script.stances()) {
                if (other == stance) continue;
                if (other.limb().flatMap(PoseScript.Limb::named).filter("head"::equals).isPresent()
                    && other.fragments() == stance.fragments())
                    return true;
            }
            return false;
        }

        /**
         * Re-seats every bone whose seat's leader the style stances - the follower's pivot is
         * carried to where the leader's held stance puts the frame the follower rides, and the
         * carry lands on the follower's plan in the evaluator's own units beside the author's
         * pixel shifts, so it lowers through the same field, driver and splice a spelled
         * {@code offset} would and crosses the flattened factor exactly once, where they do. Position
         * only: the follower keeps whatever rotation its own channels hold. A seat is read off
         * the shipped silhouettes beside the mesh, never spelled by the author, and a follower
         * seated on a leader that stands where it rests is left exactly where it is.
         *
         * <p>The carry follows the leader's HELD stance - its absolute and additive writes - and
         * not a wave or a timeline on it, which is recorded where it would matter. A chain of
         * seats carries in order, each follower's displacement computed before any plan is
         * written so a re-seated leader is read once. A carry is solved against this row's own
         * pivots, so on a woven layer it reads a per-row field, as a rebased absolute does.
         */
        private void seatFollowers() {
            Seats.Derived derived = Seats.derive(this.shipped, this.mesh);
            if (derived.seats().isEmpty()) return;

            Map<String, Vector3f> carried = new LinkedHashMap<>();
            for (String follower : derived.seats().keySet())
                this.carry(follower, derived, carried, new LinkedHashSet<>());

            carried.forEach((follower, delta) -> {
                if (delta.length() < SEAT_EPSILON) return;
                String leader = derived.seats().get(follower).leader();
                if (this.waved(leader))
                    this.events.warn("seat: '%s' rides '%s' through its held stance alone - the wave or timeline on '%s' is not followed",
                        follower, leader, leader);
                LinkedHashMap<PoseChannel, ChannelPlan> plan =
                    this.bonePlans.computeIfAbsent(follower, bone -> new LinkedHashMap<>());
                carrySeat(plan, PoseChannel.X, delta.x());
                carrySeat(plan, PoseChannel.Y, delta.y());
                carrySeat(plan, PoseChannel.Z, delta.z());
                this.events.info("seat: '%s' rides '%s' - re-seated by (%.3f, %.3f, %.3f) where the held stance carries it",
                    follower, leader, delta.x(), delta.y(), delta.z());
            });
        }

        /**
         * Lands one axis of a carry on the follower's plan - a shift in the evaluator's own units,
         * marked as this row's own; an axis the seat does not move along is left unspelled.
         */
        private static void carrySeat(@NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan,
                                      @NotNull PoseChannel channel, float shift) {
            if (shift == 0f) return;
            ChannelPlan folded = plan.computeIfAbsent(channel, key -> new ChannelPlan());
            folded.carried += shift;
            folded.perRow = true;
        }

        /**
         * How far a seated bone's pivot is carried from rest under this style - zero for a bone
         * seated on nothing, or on a leader standing where it rests; memoized per follower,
         * and a cycle of seats carries nothing rather than recursing.
         */
        private @NotNull Vector3f carry(@NotNull String follower, @NotNull Seats.Derived derived,
                                        @NotNull Map<String, Vector3f> carried, @NotNull Set<String> visiting) {
            Vector3f known = carried.get(follower);
            if (known != null) return known;
            Seats.Seat seat = derived.seats().get(follower);
            if (seat == null) return Vector3f.ZERO;
            if (!visiting.add(follower)) {
                this.events.warn("seat: '%s' rides a chain of seats that returns to it - carried nowhere", follower);
                return Vector3f.ZERO;
            }
            Seats.Placement leaderRest = derived.rest().get(seat.leader());
            Seats.Placement leaderHeld = this.held(seat.leader(), derived, carried, visiting);
            Vector3f delta = leaderHeld.equals(leaderRest)
                ? Vector3f.ZERO
                : snapped(leaderHeld.carry(seat.offset()).subtract(derived.rest().get(follower).pivot()));
            carried.put(follower, delta);
            return delta;
        }

        /**
         * A carry with the rounding dust of a rotate and its inverse taken off each component,
         * so an axis the seat does not move along emits no field.
         */
        private static @NotNull Vector3f snapped(@NotNull Vector3f carry) {
            return new Vector3f(
                Math.abs(carry.x()) < SEAT_EPSILON ? 0f : carry.x(),
                Math.abs(carry.y()) < SEAT_EPSILON ? 0f : carry.y(),
                Math.abs(carry.z()) < SEAT_EPSILON ? 0f : carry.z());
        }

        /**
         * Where one top-level bone stands under this style's held stance - its rest with the
         * folded absolute and additive writes over it, its pivot carried by its own seat.
         */
        private @NotNull Seats.Placement held(@NotNull String bone, @NotNull Seats.Derived derived,
                                              @NotNull Map<String, Vector3f> carried, @NotNull Set<String> visiting) {
            Seats.Placement rest = derived.rest().get(bone);
            Map<PoseChannel, Float> written = new EnumMap<>(PoseChannel.class);
            LinkedHashMap<PoseChannel, ChannelPlan> plan = this.bonePlans.get(bone);
            if (plan != null)
                plan.forEach((channel, folded) -> {
                    boolean rotation = channel.kind() == PoseChannel.Kind.ROTATION;
                    if (channel.kind() == PoseChannel.Kind.SCALE) return;
                    double atRest = restChannel(rest, channel);
                    double value = rotation
                        ? (folded.absoluteDegrees != null ? Math.toRadians(folded.absoluteDegrees) : atRest)
                            + Math.toRadians(folded.additive)
                        : atRest + folded.additive;
                    written.put(channel, (float) value);
                });
            Vector3f delta = this.carry(bone, derived, carried, visiting);
            Seats.Placement stanced = rest.with(written);
            return delta.equals(Vector3f.ZERO)
                ? stanced
                : new Seats.Placement(stanced.pivot().add(delta), stanced.pitch(), stanced.yaw(), stanced.roll());
        }

        /**
         * Whether a bone's stance carries a wave or a timeline, which a seat does not follow.
         */
        private boolean waved(@NotNull String bone) {
            LinkedHashMap<PoseChannel, ChannelPlan> plan = this.bonePlans.get(bone);
            if (plan != null)
                for (ChannelPlan folded : plan.values())
                    if (folded.sway != null || folded.spin != null) return true;
            for (TrackPlan track : this.trackPlans)
                if (track.bone().filter(bone::equals).isPresent()) return true;
            return false;
        }

        /**
         * One channel of a resting placement, positions in model units and rotations in radians.
         */
        private static double restChannel(@NotNull Seats.Placement rest, @NotNull PoseChannel channel) {
            return switch (channel) {
                case X -> rest.pivot().x();
                case Y -> rest.pivot().y();
                case Z -> rest.pivot().z();
                case X_ROT -> rest.pitch();
                case Y_ROT -> rest.yaw();
                case Z_ROT -> rest.roll();
                case X_SCALE, Y_SCALE, Z_SCALE -> 1d;
            };
        }

        /**
         * Lowers every bone plan into splices woven over the shipped bone map - shipped
         * expression instances kept where untouched, spliced channels replacing their entries,
         * and freshly written bones appended after the shipped roster.
         */
        private @NotNull LinkedHashMap<String, Map<PoseChannel, PoseExpr>> lowerBones() {
            LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones = new LinkedHashMap<>();
            this.shipped.bones().forEach(bones::put);
            Map<PoseChannel, PoseExpr> headSpliced = null;
            for (Map.Entry<String, LinkedHashMap<PoseChannel, ChannelPlan>> entry : this.bonePlans.entrySet()) {
                String bone = entry.getKey();
                EnumMap<PoseChannel, PoseExpr> spliced = new EnumMap<>(PoseChannel.class);
                entry.getValue().forEach((channel, plan) ->
                    this.lowerBoneChannel(bone, channel, plan, spliced));
                Double factor = this.scalePlans.get(bone);
                if (factor != null)
                    this.lowerScale(bone, factor, spliced);
                if (spliced.isEmpty()) continue;
                this.weave(bones, bone, spliced);
                if ("head".equals(bone)) headSpliced = spliced;
            }
            if (this.hatMirror && headSpliced != null && this.mesh.getBones().containsKey("hat"))
                this.weave(bones, "hat", headSpliced);
            return bones;
        }

        /**
         * Replaces one bone's woven channels over whatever the shipped map already carried.
         */
        private void weave(@NotNull LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones,
                           @NotNull String bone, @NotNull Map<PoseChannel, PoseExpr> spliced) {
            EnumMap<PoseChannel, PoseExpr> merged = new EnumMap<>(PoseChannel.class);
            Map<PoseChannel, PoseExpr> existing = bones.get(bone);
            if (existing != null) merged.putAll(existing);
            merged.putAll(spliced);
            bones.put(bone, Collections.unmodifiableMap(merged));
        }

        /**
         * Lowers one bone channel's folded plan - the absolute arm rebases against the
         * evaluated rest, the additive arm converts and splices as-is, and a wave carries the
         * folded stance delta in its own bounds so one field still holds one driver.
         */
        private void lowerBoneChannel(@NotNull String bone, @NotNull PoseChannel channel,
                                      @NotNull ChannelPlan plan,
                                      @NotNull EnumMap<PoseChannel, PoseExpr> out) {
            boolean rotation = channel.kind() == PoseChannel.Kind.ROTATION;
            PoseExpr base = this.baseOf(bone, channel);
            boolean rebased = rotation && plan.absoluteDegrees != null;
            double delta;
            if (rebased) {
                this.refuseDrivenBase(base, bone, channel);
                double rest = this.restOf(base);
                delta = Math.toRadians(plan.absoluteDegrees) - rest + Math.toRadians(plan.additive);
            } else
                delta = rotation ? Math.toRadians(plan.additive) : plan.additive / this.flattened + plan.carried;
            boolean waved = plan.sway != null || plan.spin != null;
            if (!waved && delta == 0d) {
                if (rebased)
                    this.events.info("elided write: bone '%s' channel '%s' rebases to a zero delta - no field, no driver, no splice",
                        bone, channel.token());
                return;
            }
            String field = this.boneField(bone, channel.token(), rebased || plan.perRow);
            this.emitDriver(field, plan, delta);
            out.put(channel, this.splice(base, field));
        }

        /**
         * Lowers one uniform scale - all three scale channels spliced over one shared field,
         * the delta rebased against the evaluated rest the three axes must agree on.
         */
        private void lowerScale(@NotNull String bone, double factor,
                                @NotNull EnumMap<PoseChannel, PoseExpr> out) {
            PoseExpr baseX = this.baseOf(bone, PoseChannel.X_SCALE);
            PoseExpr baseY = this.baseOf(bone, PoseChannel.Y_SCALE);
            PoseExpr baseZ = this.baseOf(bone, PoseChannel.Z_SCALE);
            this.refuseDrivenBase(baseX, bone, PoseChannel.X_SCALE);
            this.refuseDrivenBase(baseY, bone, PoseChannel.Y_SCALE);
            this.refuseDrivenBase(baseZ, bone, PoseChannel.Z_SCALE);
            ConcurrentList<Float> rests = PoseEvaluator.values(
                Concurrent.newUnmodifiableList(baseX, baseY, baseZ), this.mesh, PoseEvaluator.AT_REST);
            float rest = rests.getFirst();
            if (rest != rests.get(1) || rest != rests.get(2))
                throw this.refuse("Style '%s' scales bone '%s' whose axes rest at ('%s', '%s', '%s') - one uniform delta cannot rebase divergent rests",
                    this.style.styleId(), bone, rests.getFirst(), rests.get(1), rests.get(2));
            double delta = factor - rest;
            if (delta == 0d) return;
            String field = this.boneField(bone, SCALE_SEGMENT, true);
            this.driver(field, new StyleDriver(field, StyleDriver.Wave.HOLD, 0f, (float) delta, Optional.empty()));
            PoseExpr input = this.pool.intern(new PoseExpr.Input(field));
            out.put(PoseChannel.X_SCALE, this.pool.intern(dadd(baseX, input)));
            out.put(PoseChannel.Y_SCALE, this.pool.intern(dadd(baseY, input)));
            out.put(PoseChannel.Z_SCALE, this.pool.intern(dadd(baseZ, input)));
        }

        /**
         * Lowers the authored container steps and the hover idiom. On a row whose shipped clips
         * displace the container the custom channels fold into the shipped innermost step -
         * splicing a channel it writes, joining one it does not - so the fold seat never
         * re-seats; everywhere else the custom steps append below the shipped steps, innermost.
         */
        private @NotNull List<Map<PoseChannel, PoseExpr>> lowerContainer() {
            List<Map<PoseChannel, PoseExpr>> custom = new ArrayList<>();
            for (LinkedHashMap<PoseChannel, ChannelPlan> plan : this.stepPlans) {
                Map<PoseChannel, PoseExpr> step = this.lowerStep(plan);
                if (!step.isEmpty()) custom.add(step);
            }
            this.script.hover().ifPresent(hover -> {
                Map<PoseChannel, PoseExpr> step = this.lowerHover(hover);
                if (!step.isEmpty()) custom.add(step);
            });
            this.containerStepCount = custom.size();
            if (custom.isEmpty()) return this.shipped.container();
            List<Map<PoseChannel, PoseExpr>> steps = new ArrayList<>(this.shipped.container());
            if (this.foldSeat() && !steps.isEmpty()) {
                EnumMap<PoseChannel, PoseExpr> innermost = new EnumMap<>(PoseChannel.class);
                innermost.putAll(steps.getLast());
                for (Map<PoseChannel, PoseExpr> step : custom)
                    step.forEach((channel, expr) -> {
                        PoseExpr seated = innermost.get(channel);
                        innermost.put(channel, seated == null ? expr : this.pool.intern(dadd(seated, expr)));
                    });
                steps.set(steps.size() - 1, Collections.unmodifiableMap(innermost));
            } else
                steps.addAll(custom);
            return steps;
        }

        /**
         * Lowers one container step - bare field reads, because a container channel rests at
         * no-transform and additive-from-zero needs no base.
         */
        private @NotNull Map<PoseChannel, PoseExpr> lowerStep(
            @NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan) {

            EnumMap<PoseChannel, PoseExpr> step = new EnumMap<>(PoseChannel.class);
            plan.forEach((channel, folded) -> {
                boolean rotation = channel.kind() == PoseChannel.Kind.ROTATION;
                double stated = (folded.absoluteDegrees != null ? folded.absoluteDegrees : 0d) + folded.additive;
                double delta = rotation ? Math.toRadians(stated) : stated / this.flattened;
                boolean waved = folded.sway != null || folded.spin != null;
                if (!waved && delta == 0d) return;
                if (!rotation && this.flattened != 1f)
                    throw this.refuse("Style '%s' displaces the container of a mesh flattened at '%s' - the step seats parentless, which that factor alone does not answer",
                        this.style.styleId(), this.flattened);
                this.claimContainer(channel);
                String field = this.containerField(channel.token());
                this.emitDriver(field, folded, delta);
                step.put(channel, this.pool.intern(new PoseExpr.Input(field)));
            });
            return Collections.unmodifiableMap(step);
        }

        /**
         * Lowers the hover idiom - one step whose vertical channel sums a held lift and a swept
         * bob, each on its own field because two drivers cannot share one. The surface hides
         * the sign: lift and bob are positive at the surface and negative on the y-down axis.
         */
        private @NotNull Map<PoseChannel, PoseExpr> lowerHover(@NotNull PoseScript.Hover hover) {
            if (hover.liftPixels() == 0d && hover.bobPixels() == 0d) return Map.of();
            if (this.flattened != 1f)
                throw this.refuse("Style '%s' hovers a mesh flattened at '%s' - the step seats parentless, which that factor alone does not answer",
                    this.style.styleId(), this.flattened);
            this.claimContainer(PoseChannel.Y);
            PoseExpr lift = null;
            PoseExpr bob = null;
            if (hover.liftPixels() != 0d) {
                String field = this.containerField(PoseChannel.Y.token());
                this.driver(field, new StyleDriver(field, StyleDriver.Wave.HOLD,
                    0f, (float) -hover.liftPixels(), Optional.empty()));
                lift = this.pool.intern(new PoseExpr.Input(field));
            }
            if (hover.bobPixels() != 0d) {
                String field = this.containerField(PoseChannel.Y.token()) + BOB_SUFFIX;
                this.driver(field, new StyleDriver(field, StyleDriver.Wave.SWEEP,
                    0f, (float) -hover.bobPixels(), Optional.empty()));
                bob = this.pool.intern(new PoseExpr.Input(field));
            }
            PoseExpr vertical = lift == null ? bob
                : bob == null ? lift
                : this.pool.intern(dadd(lift, bob));
            EnumMap<PoseChannel, PoseExpr> step = new EnumMap<>(PoseChannel.class);
            step.put(PoseChannel.Y, vertical);
            return Collections.unmodifiableMap(step);
        }

        /**
         * Claims one container channel - one field holds one driver, so a second writer refuses.
         */
        private void claimContainer(@NotNull PoseChannel channel) {
            if (!this.containerClaimed.add(channel))
                throw this.refuse("Style '%s' writes container channel '%s' twice - one field holds one driver",
                    this.style.styleId(), channel.token());
        }

        /**
         * Whether a shipped clip displaces the container - a clip channel naming the part every
         * bone hangs from, or a dangling parent the mesh's flattening left spelled nowhere a
         * bone lookup reaches.
         */
        private boolean foldSeat() {
            for (EntityPose.Clip site : this.shipped.clips())
                for (PoseClip.Channel channel : site.clip().channels())
                    if (!this.mesh.getBones().containsKey(channel.bone()) && this.isContainer(channel.bone()))
                        return true;
            return false;
        }

        /**
         * Whether a name no bone answers to is the container - the root part, or a dangling parent.
         */
        private boolean isContainer(@NotNull String named) {
            if (ROOT_PART.equals(named)) return true;
            for (EntityModelData.Bone bone : this.mesh.getBones().values())
                if (named.equals(bone.getParent())) return true;
            return false;
        }

        /**
         * Lowers every captured timeline into one clip behind one selection-gated play site -
         * the gate held at one and a fresh clock ramping at one tick per tick, so keyframe
         * seconds are real twenty-per-second seconds and no shipped idle term wakes.
         */
        private @NotNull Optional<EntityPose.Clip> lowerClip() {
            if (this.trackPlans.isEmpty()) return Optional.empty();
            boolean looping = this.trackPlans.getFirst().track().looping();
            for (TrackPlan plan : this.trackPlans)
                if (plan.track().looping() != looping)
                    throw this.refuse("Style '%s' mixes loop() and once() across its timelines - a clip loops or holds as one",
                        this.style.styleId());
            double length = 0d;
            for (TrackPlan plan : this.trackPlans)
                length = Math.max(length, plan.track().overSeconds().orElse(this.windowSeconds));
            LinkedHashMap<ChannelKey, List<PoseClip.Keyframe>> accumulated = new LinkedHashMap<>();
            boolean unplaced = false;
            for (TrackPlan plan : this.trackPlans) {
                if (plan.bone().isEmpty()) {
                    unplaced |= !plan.track().motions().isEmpty();
                    continue;
                }
                this.emitTrack(plan.bone().get(), plan, accumulated);
            }
            if (accumulated.isEmpty() && !unplaced) return Optional.empty();

            List<PoseClip.Channel> channels = new ArrayList<>(accumulated.size());
            accumulated.forEach((key, keyframes) -> {
                keyframes.sort(Comparator.comparingDouble(PoseClip.Keyframe::timeSeconds));
                for (int at = 1; at < keyframes.size(); at++)
                    if (keyframes.get(at).timeSeconds() == keyframes.get(at - 1).timeSeconds())
                        throw this.refuse("Style '%s' keys bone '%s' %s twice at '%s' seconds - keyframe times ascend strictly per channel",
                            this.style.styleId(), key.bone(), key.target().token(),
                            keyframes.get(at).timeSeconds());
                channels.add(new PoseClip.Channel(key.bone(), key.target(),
                    Concurrent.newUnmodifiableList(keyframes)));
            });
            if (this.hatMirror)
                for (PoseClip.Channel channel : List.copyOf(channels))
                    if ("head".equals(channel.bone()))
                        channels.add(new PoseClip.Channel("hat", channel.target(), channel.keyframes()));
            this.clipChannelCount = channels.size();

            if (length > this.windowSeconds)
                this.events.warn("strip truncation: the clip runs '%s' seconds against a '%s' second strip window - frames beyond the window render truncated",
                    length, this.windowSeconds);

            String gate = FIELD_PREFIX + this.style.styleId();
            String clock = gate + "$clock";
            this.driver(gate, new StyleDriver(gate, StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
            this.driver(clock, new StyleDriver(clock, StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
            PoseClip clip = new PoseClip((float) length, looping, Concurrent.newUnmodifiableList(channels));
            return Optional.of(this.sharedSite.orElseGet(() -> new EntityPose.Clip(
                "style:" + this.style.styleId(), MotionSource.SELECT, Optional.of(gate),
                Concurrent.newUnmodifiableList(this.pool.intern(new PoseExpr.Input(clock))), clip)));
        }

        /**
         * Emits one track's motion fragments as keyframes onto the accumulated channels, offset
         * into the cycle by however far the plan says this copy of the shape starts.
         *
         * <p>Times and values are carried in author precision and narrowed once, at the end. An
         * offset applied after the narrowing lands a frame a few nanoseconds off the boundary it
         * was meant to hit, which no equality catches and which ships a duplicate frame.
         *
         * @param bone the bone the track keys
         * @param plan the captured timeline and how far into the cycle it starts
         * @param accumulated the per-channel keyframe lists the clip is assembled from
         */
        private void emitTrack(@NotNull String bone, @NotNull TrackPlan plan,
                               @NotNull LinkedHashMap<ChannelKey, List<PoseClip.Keyframe>> accumulated) {
            PoseScript.Track track = plan.track();
            double length = track.overSeconds().orElse(this.windowSeconds);
            PoseClip.Interpolation curve = track.ease().interpolation();
            LinkedHashMap<PoseClip.Target, List<Frame>> emitted =
                framesOf(track, length, this.flattened, planted(this.script));
            for (Map.Entry<PoseClip.Target, List<Frame>> channel : emitted.entrySet()) {
                List<Frame> frames = plan.shiftCycles() % 1d == 0d
                    ? channel.getValue()
                    : this.offset(bone, channel.getValue(),
                        plan.shiftCycles() * length, length);
                List<PoseClip.Keyframe> out = accumulated.computeIfAbsent(
                    new ChannelKey(bone, channel.getKey()), key -> new ArrayList<>());
                for (Frame frame : frames)
                    out.add(new PoseClip.Keyframe((float) frame.atSeconds(), (float) frame.x(),
                        (float) frame.y(), (float) frame.z(), curve));
            }
        }

        /**
         * One channel's frames re-timed to start a share of the cycle in.
         *
         * <p>Every frame inside the cycle moves by the offset and wraps, and the pair at the
         * cycle's own ends is read back off the unshifted shape at the time the wrap brings there.
         * Where that time is a frame of its own the two land together, and the later is dropped -
         * the wrap reads the shape at exactly that frame's own instant, so what it carries is that
         * frame's own value and the drop loses nothing. Two frames written close together both
         * survive, because they narrow to two instants the clip can tell apart.
         *
         * <p>Everything this could refuse is refused ahead of it, against the script alone, so one
         * chain reaches the same verdict on every subject rather than on the ones whose mesh
         * happened to answer.
         *
         * <p>A share already inside the cycle is left exactly as it was rather than taken through
         * a round trip that would return it a whole ulp away - which lands the frame that should
         * have hit the cycle's start a hair past it, where nothing carries it back and the clip
         * ships two frames a fraction of a nanosecond apart.
         */
        private @NotNull List<Frame> offset(@NotNull String bone, @NotNull List<Frame> base,
                                            double shift, double length) {
            List<Frame> sorted = new ArrayList<>(base);
            sorted.sort(Comparator.comparingDouble(Frame::atSeconds));
            double wrapped = shift % length;
            if (wrapped < 0d) wrapped += length;
            if (wrapped == 0d) return sorted;

            List<Frame> moved = new ArrayList<>(sorted.size() + 1);
            for (Frame frame : sorted)
                if (frame.atSeconds() < length)
                    moved.add(frame.at((frame.atSeconds() + wrapped) % length));
            Frame boundary = sample(sorted, (length - wrapped) % length);
            moved.add(boundary.at(0d));
            moved.add(boundary.at(length));
            moved.sort(Comparator.comparingDouble(Frame::atSeconds));

            List<Frame> kept = new ArrayList<>(moved.size());
            for (Frame frame : moved) {
                if (kept.isEmpty()
                    || (float) frame.atSeconds() != (float) kept.getLast().atSeconds()) {
                    kept.add(frame);
                    continue;
                }
                if (!frame.narrows(kept.getLast()))
                    throw this.refuse("Style '%s' keys bone '%s' twice at '%s' seconds - keyframe times ascend strictly per channel",
                        this.style.styleId(), bone, (float) frame.atSeconds());
            }
            return kept;
        }

        /**
         * One track's motion fragments as frames, per target, in the precision they were written.
         *
         * <p>A plant reshapes the two fragments that are triangles into trapezoids, holding the
         * resting bound until the plateau ends and reaching the peak midway through what is left.
         * A share of none of the cycle is the triangle itself, to the bit: the peak of an unplanted
         * shape solves to half the length exactly, which is what it was written as.
         *
         * @param track the captured timeline
         * @param length the seconds one run of the track spans
         * @param flattened the whole-mesh factor model units cross
         * @param planted the share of the cycle a triangle stays at its resting bound
         * @return the frames each target takes, in the order the motions named them
         */
        private static @NotNull LinkedHashMap<PoseClip.Target, List<Frame>> framesOf(
            @NotNull PoseScript.Track track, double length, float flattened, double planted) {

            double peak = (planted + 1d) / 2d;
            LinkedHashMap<PoseClip.Target, List<Frame>> emitted = new LinkedHashMap<>();
            for (PoseScript.Motion motion : track.motions()) {
                switch (motion) {
                    case PoseScript.Swing swing -> {
                        List<Frame> frames = framesOf(emitted, PoseClip.Target.ROTATION);
                        frames.add(rotationFrame(0d, swing.axis(), swing.fromDegrees()));
                        if (planted > 0d)
                            frames.add(rotationFrame(planted * length, swing.axis(),
                                swing.fromDegrees()));
                        frames.add(rotationFrame(peak * length, swing.axis(), swing.toDegrees()));
                        frames.add(rotationFrame(length, swing.axis(), swing.fromDegrees()));
                    }
                    case PoseScript.Bob bob -> {
                        List<Frame> frames = framesOf(emitted, PoseClip.Target.POSITION);
                        double lifted = -bob.pixels() / flattened;
                        frames.add(new Frame(0d, 0d, 0d, 0d));
                        if (planted > 0d) frames.add(new Frame(planted * length, 0d, 0d, 0d));
                        frames.add(new Frame(peak * length, 0d, lifted, 0d));
                        frames.add(new Frame(length, 0d, 0d, 0d));
                    }
                    case PoseScript.Keyframe frame ->
                        framesOf(emitted, PoseClip.Target.ROTATION)
                            .add(new Frame(frame.atSeconds(),
                                Math.toRadians(frame.pitchDegrees()),
                                Math.toRadians(frame.yawDegrees()),
                                Math.toRadians(frame.rollDegrees())));
                    case PoseScript.Shift shift ->
                        framesOf(emitted, PoseClip.Target.POSITION)
                            .add(new Frame(shift.atSeconds(),
                                shift.xPixels() / flattened,
                                shift.yPixels() / flattened,
                                shift.zPixels() / flattened));
                }
            }
            return emitted;
        }

        /**
         * The unshifted shape read at one instant, straight between the frames bracketing it.
         */
        private static @NotNull Frame sample(@NotNull List<Frame> frames, double atSeconds) {
            Frame before = frames.getFirst();
            for (Frame frame : frames) {
                if (frame.atSeconds() > atSeconds) {
                    double span = frame.atSeconds() - before.atSeconds();
                    double progress = span == 0d ? 0d : (atSeconds - before.atSeconds()) / span;
                    return new Frame(atSeconds,
                        before.x() + (frame.x() - before.x()) * progress,
                        before.y() + (frame.y() - before.y()) * progress,
                        before.z() + (frame.z() - before.z()) * progress);
                }
                before = frame;
            }
            return before.at(atSeconds);
        }

        /**
         * The accumulating frame list of one target.
         */
        private static @NotNull List<Frame> framesOf(
            @NotNull LinkedHashMap<PoseClip.Target, List<Frame>> emitted,
            @NotNull PoseClip.Target target) {

            return emitted.computeIfAbsent(target, key -> new ArrayList<>());
        }

        /**
         * One rotation frame - the swung axis carries the delta in radians, the others rest.
         */
        private static @NotNull Frame rotationFrame(double atSeconds, @NotNull Turn axis,
                                                    double degrees) {
            double radians = Math.toRadians(degrees);
            return new Frame(atSeconds,
                axis == Turn.PITCH ? radians : 0d,
                axis == Turn.YAW ? radians : 0d,
                axis == Turn.ROLL ? radians : 0d);
        }

        /**
         * Splices the raw expression captures - each interned, checked once over every node, and
         * replacing its channel whole under this style's gate, because the author owns the graph
         * there and every other style of the row owns what the channel already held.
         *
         * <p>What the author wrote is checked for every raw, and what the subject answers only for
         * one whose bone this mesh declares. A width, a namespace and an arity are facts about the
         * text, so a chain carrying one of them refuses wherever it installs; a read of a missing
         * bone matters only where the written bone lands, because a write the mesh cannot place
         * filters out before the graph is ever evaluated.
         */
        private void lowerRaws(@NotNull LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones) {
            for (PoseScript.Raw raw : this.script.raws()) {
                PoseExpr interned = this.pool.intern(raw.expr());
                this.checkWritten(interned);
                if (!this.mesh.getBones().containsKey(raw.bone())) {
                    this.dropped.add(new Unreached.Named(raw.bone()));
                    continue;
                }
                this.checkReads(interned);
                EnumMap<PoseChannel, PoseExpr> replaced = new EnumMap<>(PoseChannel.class);
                replaced.put(raw.channel(), this.gated(bones, raw.bone(), raw.channel(), interned));
                this.weave(bones, raw.bone(), replaced);
            }
        }

        /**
         * One raw graph behind this style's own gate - the true arm of a selection on the
         * {@code style$<id>} field, whose false arm is whatever the channel held before the
         * splice: what this compile has already woven there, else the shipped instance, else the
         * bone's own read. The gate holds at one under this style and is undriven, so zero, under
         * every other, which is how a raw carries the promise a summed splice carries by
         * answering its base's bits.
         *
         * <p>A live render-state gate the author wants is spelled inside the graph and composes
         * with this one: this decides which style is being drawn, and theirs what that style does.
         */
        private @NotNull PoseExpr gated(@NotNull LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones,
                                        @NotNull String bone, @NotNull PoseChannel channel,
                                        @NotNull PoseExpr raw) {
            String gate = FIELD_PREFIX + this.style.styleId();
            this.driver(gate, new StyleDriver(gate, StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
            Map<PoseChannel, PoseExpr> woven = bones.get(bone);
            PoseExpr held = woven == null ? null : woven.get(channel);
            PosePredicate selected = this.pool.intern(new PosePredicate(PosePredicate.Comparison.NE,
                this.pool.intern(new PoseExpr.Input(gate)),
                this.pool.intern(new PoseExpr.Const(0d, PoseOperator.Width.DOUBLE))));
            return this.pool.intern(new PoseExpr.Select(selected, raw,
                held != null ? held : this.baseOf(bone, channel)));
        }

        /**
         * Runs the authored-text walk over a raw graph, memoized per node instance.
         */
        private void checkWritten(@NotNull PoseExpr root) {
            this.checkWritten(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        /**
         * Checks one raw node's text - width exactness, field namespace, arity.
         *
         * <p>Nothing here reads the subject, which is why it runs for every raw the style writes
         * rather than for the ones this mesh happens to place.
         */
        private void checkWritten(@NotNull PoseExpr node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            switch (node) {
                case PoseExpr.Const constant -> {
                    if (constant.width() == PoseOperator.Width.FLOAT
                        && (double) (float) constant.value() != constant.value())
                        throw this.refuse("Style '%s' splices float literal '%s', which no float holds exactly",
                            this.style.styleId(), constant.value());
                }
                case PoseExpr.Input input -> {
                    String gate = FIELD_PREFIX + this.style.styleId();
                    if (input.field().startsWith(FIELD_PREFIX)
                        && !input.field().equals(gate) && !input.field().startsWith(gate + "$"))
                        throw this.refuse("Style '%s' reads field '%s', which another style's namespace drives",
                            this.style.styleId(), input.field());
                }
                case PoseExpr.BoneRead ignored -> { }
                case PoseExpr.Op op -> {
                    if (op.operands().size() != op.operator().arity())
                        throw this.refuse("Style '%s' applies '%s' to %d operand(s), which takes %d",
                            this.style.styleId(), op.operator().token(),
                            op.operands().size(), op.operator().arity());
                    for (PoseExpr operand : op.operands())
                        this.checkWritten(operand, visited);
                }
                case PoseExpr.Select select -> {
                    this.checkWritten(select.condition(), visited);
                    this.checkWritten(select.whenTrue(), visited);
                    this.checkWritten(select.whenFalse(), visited);
                }
            }
        }

        /**
         * Checks one raw predicate's operands under the authored-text walk.
         */
        private void checkWritten(@NotNull PosePredicate node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            this.checkWritten(node.left(), visited);
            this.checkWritten(node.right(), visited);
        }

        /**
         * Runs the bone-read walk over a raw graph, memoized per node instance.
         */
        private void checkReads(@NotNull PoseExpr root) {
            this.checkReads(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        /**
         * Checks one raw node's reads against the bones this mesh declares.
         *
         * <p>This is the half that reads the subject, and it runs behind the drop: a write the
         * mesh cannot place filters out whole, so what its graph reads is a question about a
         * channel nothing evaluates.
         */
        private void checkReads(@NotNull PoseExpr node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            switch (node) {
                case PoseExpr.Const ignored -> { }
                case PoseExpr.Input ignored -> { }
                case PoseExpr.BoneRead read -> {
                    if (!this.mesh.getBones().containsKey(read.bone()))
                        throw this.refuse("Style '%s' reads bone '%s', which this mesh does not declare - a read of a missing bone throws at render",
                            this.style.styleId(), read.bone());
                }
                case PoseExpr.Op op -> {
                    for (PoseExpr operand : op.operands())
                        this.checkReads(operand, visited);
                }
                case PoseExpr.Select select -> {
                    this.checkReads(select.condition(), visited);
                    this.checkReads(select.whenTrue(), visited);
                    this.checkReads(select.whenFalse(), visited);
                }
            }
        }

        /**
         * Checks one raw predicate's operands under the bone-read walk.
         */
        private void checkReads(@NotNull PosePredicate node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            this.checkReads(node.left(), visited);
            this.checkReads(node.right(), visited);
        }

        /**
         * Solves one aim - pitch and yaw from the direction between the stanced bone's pivot
         * and the target, both in the mesh's own pixels so the angles are unchanged by any
         * flattening factor. A hanging limb rests pointing down and takes the quarter-turn
         * pitch offset; a facing bone takes the solve as-is; roll stays authored. The solved
         * pair lands as ordinary absolute writes, so rebase, elision and the driven-base
         * refusal all apply unchanged.
         */
        private void foldAim(PoseScript.Limb.@NotNull Named limb, @NotNull PoseScript.Aim aim,
                             @NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan) {
            Vector3f pivot = this.mesh.getBones().get(limb.bone()).getPivot();
            double dx = aim.xPixels() - pivot.x();
            double dy = aim.yPixels() - pivot.y();
            double dz = aim.zPixels() - pivot.z();
            if (dx == 0d && dy == 0d && dz == 0d)
                throw this.refuse("Style '%s' aims bone '%s' at its own pivot - no direction to aim",
                    this.style.styleId(), limb.bone());
            double pitch = Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            if (limb.axis() == PoseScript.AimAxis.DOWN) pitch -= 90d;
            double yaw = Math.toDegrees(Math.atan2(-dx, -dz));
            plan.computeIfAbsent(PoseChannel.X_ROT, key -> new ChannelPlan()).absoluteDegrees = normalized(pitch);
            plan.computeIfAbsent(PoseChannel.Y_ROT, key -> new ChannelPlan()).absoluteDegrees = normalized(yaw);
        }

        /**
         * The splice base - the shipped instance where the pose writes the channel, else a bone read.
         */
        private @NotNull PoseExpr baseOf(@NotNull String bone, @NotNull PoseChannel channel) {
            Map<PoseChannel, PoseExpr> channels = this.shipped.bones().get(bone);
            PoseExpr written = channels == null ? null : channels.get(channel);
            return written != null ? written : this.pool.intern(new PoseExpr.BoneRead(bone, channel));
        }

        /**
         * A base's value with every field resting - what an absolute write rebases against.
         */
        private double restOf(@NotNull PoseExpr base) {
            return PoseEvaluator.values(Concurrent.newUnmodifiableList(base), this.mesh,
                PoseEvaluator.AT_REST).getFirst();
        }

        /**
         * Refuses a rebase over a base reading a field the built row drives - the rest snapshot
         * would part company with the live value. The remedy is the one the channel actually
         * has, which on a scale is none. The scan visits each node once by instance.
         */
        private void refuseDrivenBase(@NotNull PoseExpr base, @NotNull String bone,
                                      @NotNull PoseChannel channel) {
            if (this.drivenFields.isEmpty()) return;
            String driven = drivenFieldIn(base, this.drivenFields,
                Collections.newSetFromMap(new IdentityHashMap<>()));
            if (driven != null)
                throw this.refuse("Style '%s' writes bone '%s' channel '%s' absolutely over a base reading driven field '%s' - %s",
                    this.style.styleId(), bone, channel.token(), driven, remedyFor(channel));
        }

        /**
         * The spelling that composes with a live base on the given channel, where one exists.
         */
        private static @NotNull String remedyFor(@NotNull PoseChannel channel) {
            return switch (channel.kind()) {
                case ROTATION -> "pitchBy, yawBy and rollBy compose with a live base, and an aim has no additive spelling of its own";
                case SCALE -> "no additive scale spelling exists, so a driven scale channel cannot be stated";
                case POSITION -> "offset composes with a live base";
            };
        }

        /**
         * The first driven field a base reads, or {@code null} where it reads none.
         */
        private static @Nullable String drivenFieldIn(@NotNull PoseExpr node,
                                                      @NotNull Set<String> driven,
                                                      @NotNull Set<Object> visited) {
            if (!visited.add(node)) return null;
            return switch (node) {
                case PoseExpr.Input input -> driven.contains(input.field()) ? input.field() : null;
                case PoseExpr.Op op -> {
                    for (PoseExpr operand : op.operands()) {
                        String found = drivenFieldIn(operand, driven, visited);
                        if (found != null) yield found;
                    }
                    yield null;
                }
                case PoseExpr.Select select -> {
                    String found = visited.add(select.condition())
                        ? firstNonNull(
                            drivenFieldIn(select.condition().left(), driven, visited),
                            drivenFieldIn(select.condition().right(), driven, visited))
                        : null;
                    if (found == null) found = drivenFieldIn(select.whenTrue(), driven, visited);
                    if (found == null) found = drivenFieldIn(select.whenFalse(), driven, visited);
                    yield found;
                }
                default -> null;
            };
        }

        /**
         * The first non-null of two scan answers.
         */
        private static @Nullable String firstNonNull(@Nullable String first, @Nullable String second) {
            return first != null ? first : second;
        }

        /**
         * Emits one channel's driver - a swept or cycling wave carries the folded stance delta
         * in its own bounds, and everything else holds the delta.
         */
        private void emitDriver(@NotNull String field, @NotNull ChannelPlan plan, double delta) {
            StyleDriver driver;
            if (plan.sway != null)
                driver = new StyleDriver(field, StyleDriver.Wave.SWEEP,
                    (float) (Math.toRadians(plan.sway.fromDegrees()) + delta),
                    (float) (Math.toRadians(plan.sway.toDegrees()) + delta),
                    Optional.empty());
            else if (plan.spin != null)
                driver = new StyleDriver(field, StyleDriver.Wave.CYCLE,
                    (float) delta,
                    (float) (delta + Math.toRadians(plan.spin.perPeriodDegrees())),
                    Optional.empty());
            else
                driver = new StyleDriver(field, StyleDriver.Wave.HOLD, 0f, (float) delta, Optional.empty());
            this.driver(field, driver);
        }

        /**
         * Registers one field and its driver. Every compile emits a driver for each field its
         * own splices read - a woven layer can land motion on a bone the body's mesh dropped,
         * so no compile assumes another already drove a shared spelling - and an installer
         * merging rows first-wins holds one copy per field, the body compile's where both
         * compiles drive it.
         */
        private void driver(@NotNull String field, @NotNull StyleDriver driver) {
            this.fields.add(field);
            this.drivers.put(field, driver);
        }

        /**
         * One splice - the double-width sum of the base and a fresh field read, interned whole.
         */
        private @NotNull PoseExpr splice(@NotNull PoseExpr base, @NotNull String field) {
            return this.pool.intern(dadd(base, this.pool.intern(new PoseExpr.Input(field))));
        }

        /**
         * One bone channel's field - a splice solved against this row's own rests or pivots
         * reads a per-layer field on a woven layer, the shared spelling everywhere else.
         */
        private @NotNull String boneField(@NotNull String bone, @NotNull String token, boolean perRow) {
            String gate = FIELD_PREFIX + this.style.styleId();
            return perRow && this.layer.isPresent()
                ? gate + "$" + this.layer.get() + "$" + bone + "$" + token
                : gate + "$" + bone + "$" + token;
        }

        /**
         * One container channel's field - the coined seat segment, shared across woven rows.
         */
        private @NotNull String containerField(@NotNull String token) {
            return FIELD_PREFIX + this.style.styleId() + "$" + CONTAINER_SEGMENT + "$" + token;
        }

        /**
         * Records the refusal context and builds it - the entry is the post-mortem, and the
         * {@code throw} at the call site is the gate.
         *
         * <p>Returned rather than thrown so that not returning is visible to the compiler and to a
         * reader: a refusal spelled {@code throw this.refuse(...)} ends its branch in the branch,
         * where one that threw from in here ended it somewhere a reader had to already know about.
         *
         * @param message the refusal, as a format string
         * @param args the format arguments
         * @return the refusal to throw
         */
        private @NotNull IllegalArgumentException refuse(@NotNull @PrintFormat String message,
                                                         @Nullable Object... args) {
            return PoseCompiler.refuse(this.events, message, args);
        }

    }

    /**
     * The rules a compile runs ONCE, before any member is stamped.
     *
     * <p>Each rule states its read set in its parameter list, and the convention is the point of
     * the class: <b>a rule whose parameters name no {@link LimbRoster} reads the author's text
     * alone</b>, so it reaches the same verdict on every subject the chain installs on. A rule
     * that takes one reads the mesh, and reads it here rather than downstream - once, against the
     * roster, where its message is a function of the chain and the mesh instead of whatever the
     * fold happened to reach first.
     *
     * <p>What is NOT here is the other half of the split, and it is left visible rather than
     * gathered: the per-bone refusals run inside the fold, where some read the author's text and
     * some read a resolved bone, and moving them in would state a schedule they do not keep. The
     * convention constrains what a rule READS and says nothing about when it runs, so it cannot
     * carry them.
     */
    private static final class Rules {

        private Rules() {}

        /**
         * Refuses a declared period the strip cannot frame or a still style cannot read.
         */
        static void period(@NotNull BuiltStyle style, @NotNull StyleDiagnostics events) {
            if (style.script().periodSeconds().isEmpty()) return;
            double seconds = style.script().periodSeconds().getAsDouble();
            if (seconds <= 0d)
                throw refuse(events, "Style '%s' declares a period of '%s' seconds, which is not positive",
                    style.styleId(), seconds);
            long ticks = Math.round(seconds * TICKS_PER_SECOND);
            if (ticks <= 0 || ticks % StyleCatalog.STRIP_FRAMES != 0)
                throw refuse(events, "Style '%s' declares a period of '%s' seconds (%d ticks), which the %d-frame strip does not tile",
                    style.styleId(), seconds, ticks, StyleCatalog.STRIP_FRAMES);
            if (style.sources().isEmpty())
                throw refuse(events, "Style '%s' declares a period but holds still - only a moving style reads one",
                    style.styleId());
        }

        /**
         * Refuses two ranks this mesh answers with one row.
         *
         * <p>Ranks are ordinals into however many rows a mesh carries, so more than one of them
         * reaches the same row on a mesh shorter than the ladder - {@code FRONT} and {@code HIND}
         * are one row on every mesh carrying a single row of legs, which is what lets one chain
         * run over two legs and eight. Stancing both names that row twice, and the second stamp
         * lands on the first with nothing in either name saying so.
         *
         * <p>A rank the mesh has no row for is passed over here: it addresses nothing rather than
         * something already held, and the roster filter answers it.
         */
        static void ranks(@NotNull BuiltStyle style, @NotNull LimbRoster roster,
                          @NotNull StyleDiagnostics events) {
            LinkedHashMap<Integer, Rank> claimed = new LinkedHashMap<>();
            Set<Rank> named = EnumSet.noneOf(Rank.class);
            for (PoseScript.Stance stance : style.script().stances()) {
                Optional<Rank> addressed = legsOf(stance).flatMap(LimbSelector.Legs::rank);
                if (addressed.isEmpty() || !named.add(addressed.get())) continue;
                Rank rank = addressed.get();
                Optional<LimbRoster.Row> row = roster.row(rank);
                if (row.isEmpty()) continue;
                Rank held = claimed.putIfAbsent(row.get().ordinal(), rank);
                if (held != null)
                    throw refuse(events, "Style '%s' stances rank '%s' and rank '%s', which a mesh carrying '%d' leg row(s) answers with one row - the second stamp lands on the row the first already holds",
                        style.styleId(), held, rank, roster.rows().size());
            }
        }

        /**
         * Refuses a cycle offset the shape it was written over cannot carry.
         *
         * <p>Two verbs state an offset and both answer here, because what makes one unstateable is
         * the shape rather than the verb. The offsets are read in rank order and the side's after
         * them, so which of several unstateable offsets is named is the chain's own reading and
         * never the run's.
         *
         * <p>Every reason an offset is unstateable is a fact about what the author wrote, so every
         * refusal here reads the script and no mesh. A rank the target carries no row for is NOT
         * one of them: it addresses nothing, which is the answer rather than an error, and a
         * refusal keyed on it would let the same chain install on one subject and refuse on the
         * next. A mesh whose rows carry no side to offset is the same answer for the same reason.
         *
         * <p>What is unstateable: a shape written as a wave, which has no offset to start late by,
         * because a wave lowers to a driver and a driver derives its whole phase from the tick -
         * moving it onto the clip clock to make room would drop the field it was emitting and
         * change every table the style writes, so the author is asked for a timeline instead; a
         * clip that holds rather than loops, which has no wrap for a share of a cycle to mean
         * anything in; a smoothed track, whose frames read their neighbours from the clip's ends
         * rather than across them, so re-timing one states a different curve; a track whose two
         * ends disagree, where moving the wrap moves a jump; and a track keying one instant twice,
         * which the clip refuses however it was written.
         *
         * <p>A whole number of cycles is passed over, because it is no offset at all - the wrap
         * takes it to zero, and the same chain written as a zero would be lowered rather than
         * read.
         */
        static void cycleOffsets(@NotNull BuiltStyle style, @NotNull StyleDiagnostics events) {
            if (style.script().cycle().isEmpty()) return;
            PoseScript.Cycle cycle = style.script().cycle().get();
            cycle.phases().forEach((rank, cycles) ->
                refuseUnreal(style, events, "a phase at rank '" + rank + "'", cycles));
            cycle.gains().forEach((rank, factor) ->
                refuseUnreal(style, events, "a gain at rank '" + rank + "'", factor));
            cycle.opposed().ifPresent(cycles -> refuseUnreal(style, events, "an opposed far side", cycles));
            cycle.coupled().ifPresent(cycles -> refuseUnreal(style, events, "a trot", cycles));
            cycle.plantShare().ifPresent(share -> refuseUnreal(style, events, "a plant", share));
            cycle.trail().ifPresent(trail -> {
                refuseUnreal(style, events, "a trailing chain's lag", trail.cycles());
                refuseUnreal(style, events, "a trailing chain's fade", trail.fade());
            });
            if (cycle.coupled().isPresent() && cycle.opposed().isPresent())
                throw refuse(events, "Style '%s' gaits both a trot and an opposed side - a trot already states what the two sides of a row do, so the two together state a cycle that is neither a diagonal nor a pace",
                    style.styleId());
            if (cycle.plantShare().isPresent()) {
                double share = cycle.plantShare().getAsDouble();
                if (!(share >= 0d && share < 1d))
                    throw refuse(events, "Style '%s' plants for '%s' of a cycle - a plant holds a shape at rest for a share of the cycle it then travels in, which is at least none of it and less than all of it",
                        style.styleId(), share);
            }
            cycle.phases().forEach((rank, cycles) -> {
                if (whole(cycles)) return;
                for (PoseScript.Stance stance : style.script().stances())
                    if (reachesRank(stance, rank))
                        checkOffset(style, events, "a phase at rank '" + rank + "'", stance);
            });
            if (cycle.opposed().isPresent() && !whole(cycle.opposed().getAsDouble()))
                for (PoseScript.Stance stance : style.script().stances())
                    if (reachesFarSide(stance)) checkOffset(style, events, "an opposed far side", stance);
            if (cycle.coupled().isPresent() && !whole(cycle.coupled().getAsDouble()))
                for (PoseScript.Stance stance : style.script().stances())
                    if (legsOf(stance).isPresent()) checkOffset(style, events, "a trot", stance);
            if (cycle.trail().isPresent() && !whole(cycle.trail().get().cycles()))
                for (PoseScript.Stance stance : style.script().stances())
                    if (legsOf(stance).isPresent()) checkOffset(style, events, "a trailing chain", stance);
        }

        /**
         * Refuses a gait verb whose axis this mesh's legs cannot answer.
         *
         * <p>This is the one gait rule that reads the mesh, and it reads it once - before a member
         * is stamped, so the message is a function of the roster and the chain rather than of what
         * the fold happened to reach first. A verb keyed on an axis the legs do not carry states a
         * relationship that lands on nothing, which renders as some other animal's cycle with
         * nothing red, so it refuses however the install was asked for.
         *
         * <p>A mesh naming no leg at all is passed over. That subject has no legs rather than the
         * wrong ones, which is the drop a tolerant install exists for, and the selector's own empty
         * resolution already reports it.
         */
        static void axes(@NotNull BuiltStyle style, @NotNull LimbRoster roster,
                         @NotNull StyleDiagnostics events) {
            if (style.script().cycle().isEmpty() || roster.rows().isEmpty()) return;
            PoseScript.Cycle cycle = style.script().cycle().get();

            if (cycle.coupled().isPresent()) {
                if (roster.rows().size() != COUPLET_ROWS)
                    throw refuse(events, "Style '%s' gaits a trot on a mesh carrying '%d' leg row(s) - a diagonal pairs a front leg with the opposite hind one, which '%d' row(s) have no unique reading of",
                        style.styleId(), roster.rows().size(), roster.rows().size());
                refuseUnsided(style, roster, events, "a trot", "pairs each leg with the one across the body from it");
            }
            if (cycle.opposed().isPresent())
                refuseUnsided(style, roster, events, "an opposed far side",
                    "starts the far side of every pair behind the near one");
            if (cycle.shared())
                refuseUnsided(style, roster, events, "a shared far side",
                    "reads the far side of every pair with every sign as written");
            if (cycle.trail().isPresent() && roster.members().stream()
                .noneMatch(member -> member.depth() > 0))
                throw refuse(events, "Style '%s' gaits a trailing chain on a mesh whose legs declare no bone below the root - a lag and a fade per bone below the root is the stance itself where there is none",
                    style.styleId());
        }

        /**
         * Records a rank a gait's numbers name and this mesh answers with no row.
         *
         * <p>A number keyed on an absent row lands on nothing, which is exactly what an ADDRESS
         * keyed on one already does - and an address says so, because its own empty resolution
         * joins the written bones the mesh does not declare. A number resolves nothing, so it has
         * no empty resolution to report, and a mistyped rank on a gait's timing was the one thing
         * in the vocabulary that could go wrong in silence on every install path at once.
         *
         * <p>It joins the same list rather than refusing, so the fork stays where it is: a strict
         * install refuses and names the rank, and a tolerant one proceeds - which is what keeps a
         * chain deliberately written to run over two, four and eight legs installable while still
         * catching the slip on the path that asks to be told.
         *
         * <p>A mesh naming no leg at all is passed over. Every rank is absent there, so recording
         * them would report the mesh rather than the chain, and the selector's own empty resolution
         * already reports that.
         */
        static void absentRanks(@NotNull BuiltStyle style, @NotNull LimbRoster roster,
                                @NotNull Set<Unreached> dropped, @NotNull StyleDiagnostics events) {
            if (style.script().cycle().isEmpty() || roster.rows().isEmpty()) return;
            PoseScript.Cycle cycle = style.script().cycle().get();
            cycle.phases().keySet().forEach(rank -> absentRank(roster, dropped, events, "phase", rank));
            cycle.gains().keySet().forEach(rank -> absentRank(roster, dropped, events, "gain", rank));
        }

        /**
         * Records one gait number's rank where the mesh carries no such row.
         */
        private static void absentRank(@NotNull LimbRoster roster, @NotNull Set<Unreached> dropped,
                                       @NotNull StyleDiagnostics events, @NotNull String verb,
                                       @NotNull Rank rank) {
            if (roster.row(rank).isPresent()) return;
            Unreached.Keyed keyed = new Unreached.Keyed(verb, rank);
            dropped.add(keyed);
            events.info("gait: %s names a row this mesh does not carry, so it lands on nothing",
                keyed.describe());
        }

        /**
         * Records the legs this mesh names against the side they sit on, where the style says
         * which side a leg is on rather than stamping both alike.
         *
         * <p>The side a member carries is its NAME's, which is the right reading and stays one:
         * the meshes a geometric reader gets wrong are silently wrong - a fox pivots both hind legs
         * left of centre and a copper golem puts both on the midline - where a named side that
         * disagrees with its own pixels is a fact the mesh states out loud and the roster can hand
         * over. So it is handed over here rather than overriding anything.
         *
         * <p>It is recorded only where the style has something to lose by it. A stamp that reaches
         * both sides alike loses nothing, because which of the two took the authored copy is not a
         * question it asked. What loses is a chain that says which side a leg is ON - an offset
         * between the two sides, a shared reading of the far one, a pairing across the body, or one
         * leg addressed by side and rank. On a mesh whose names cross in ONE row of two, a pairing
         * across the body reads as one along it, which is the other animal's cycle rendered
         * cleanly.
         */
        static void crossedSides(@NotNull BuiltStyle style, @NotNull LimbRoster roster,
                                 @NotNull StyleDiagnostics events) {
            if (roster.crossed().isEmpty() || !sideKeyed(style.script())) return;
            events.warn("crossed sides: %d leg(s) [%s] are named against the side they sit on, and this style states which side a leg is on - a pairing across the body reads as one along it, and a leg addressed by side is the one opposite",
                roster.crossed().size(), String.join(", ", roster.crossed()));
        }

        /**
         * Whether this style says which side a leg is on, as against stamping both alike.
         */
        /**
         * The window a track with no authored length closes over.
         *
         * <p>The declared period where the author stated one, and the default period otherwise -
         * never the TARGET ROW's catalog period. A closure verdict is a fact about what the author
         * wrote, so reading the row here would let one chain close on one subject and fail to close
         * on the next, which is the thing every other rule in this class is arranged to prevent.
         *
         * @param script the captured script to read
         * @return the window in seconds
         */
        private static double closureWindow(@NotNull PoseScript script) {
            return script.periodSeconds().orElse((double) DEFAULT_PERIOD_TICKS / TICKS_PER_SECOND);
        }

        private static boolean sideKeyed(@NotNull PoseScript script) {
            if (script.cycle().filter(cycle -> cycle.opposed().isPresent()
                || cycle.coupled().isPresent() || cycle.shared()).isPresent()) return true;
            for (PoseScript.Stance stance : script.stances())
                if (legsOf(stance).filter(legs -> legs.side().isPresent()
                    && legs.stamp() == LimbSelector.Stamp.LONE).isPresent()) return true;
            return false;
        }

        /**
         * Refuses a side-keyed verb on a mesh carrying a row one bone paints whole.
         *
         * <p>Stated over every row rather than over none, because that is the verb's own sentence:
         * it speaks for each row the mesh carries, and a mesh mixing a fused row with a sided one
         * would otherwise take the alternation on half its legs and nothing on the other half.
         *
         * @param reading how the refusal names the verb, in the author's own terms
         * @param does what the verb states about the two sides, as a third-person clause
         */
        private static void refuseUnsided(@NotNull BuiltStyle style, @NotNull LimbRoster roster,
                                          @NotNull StyleDiagnostics events, @NotNull String reading,
                                          @NotNull String does) {
            List<String> fused = new ArrayList<>();
            for (LimbRoster.Row row : roster.rows())
                if (row.members().stream().noneMatch(member ->
                    member.depth() == 0 && member.side().isPresent()))
                    row.members().stream()
                        .filter(member -> member.depth() == 0)
                        .forEach(member -> fused.add(member.bone()));
            if (fused.isEmpty()) return;
            throw refuse(events, "Style '%s' gaits %s, which %s, on a mesh whose leg row(s) [%s] carry no side - one bone paints both legs of the row, so there is no second leg for the term to land on",
                style.styleId(), reading, does, String.join(", ", fused));
        }

        /**
         * Refuses a gait number that is not a number.
         *
         * <p>Every other rule about these values asks what they MEAN, and a value outside the reals
         * has no meaning to ask about: it passes the whole-cycle test, because it is not equal to
         * zero; it passes the wrap, because it is neither below zero nor equal to it; and it passes
         * the clip's own duplicate-frame test, because it is not equal to itself either. What it
         * reaches is a keyframe at no time carrying no value, which nothing downstream can see.
         *
         * @param reading how the refusal names the number, in the author's own terms
         * @param value the number as the author wrote it
         */
        private static void refuseUnreal(@NotNull BuiltStyle style, @NotNull StyleDiagnostics events,
                                         @NotNull String reading, double value) {
            if (Double.isFinite(value)) return;
            throw refuse(events, "Style '%s' states %s as '%s' - every number a cycle carries is a real one",
                style.styleId(), reading, value);
        }

        /**
         * Whether a share of a cycle is no offset at all - the wrap takes a whole number of cycles
         * to zero, so it states what writing zero states.
         */
        private static boolean whole(double cycles) {
            return cycles % 1d == 0d;
        }

        /**
         * Refuses one stance an offset cannot start late.
         *
         * @param reading how the refusal names the offset, in the author's own terms
         * @param stance the captured stance the offset was written over
         */
        private static void checkOffset(@NotNull BuiltStyle style, @NotNull StyleDiagnostics events,
                                        @NotNull String reading,
                                        @NotNull PoseScript.Stance stance) {
            if (!stance.of(PoseScript.Sway.class).isEmpty() || !stance.of(PoseScript.Spin.class).isEmpty())
                throw refuse(events, "Style '%s' gaits %s over a swayed shape - a wave carries no offset of its own, so a shape starting late in the cycle states itself as a timeline",
                    style.styleId(), reading);
            for (PoseScript.Track track : stance.of(PoseScript.Track.class)) {
                if (!track.looping())
                    throw refuse(events, "Style '%s' gaits %s over a clip that holds rather than loops - a share of a cycle needs a cycle to wrap in",
                        style.styleId(), reading);
                if (track.ease() == Ease.SMOOTH)
                    throw refuse(events, "Style '%s' gaits %s over a smoothed track - a smoothed frame reads its neighbours from the clip's ends rather than across them, so re-timing one states a different curve",
                        style.styleId(), reading);
                double length = track.overSeconds().orElse(closureWindow(style.script()));
                Lowering.framesOf(track, length, 1f, planted(style.script())).values().forEach(frames -> {
                    List<Frame> sorted = new ArrayList<>(frames);
                    sorted.sort(Comparator.comparingDouble(Frame::atSeconds));
                    for (int at = 1; at < sorted.size(); at++)
                        if ((float) sorted.get(at).atSeconds()
                            == (float) sorted.get(at - 1).atSeconds())
                            throw refuse(events, "Style '%s' gaits %s over a track keying '%s' seconds twice - keyframe times ascend strictly per channel, an offset included",
                                style.styleId(), reading, sorted.get(at).atSeconds());
                    if (sorted.getFirst().atSeconds() != 0d
                        || sorted.getLast().atSeconds() != length
                        || !sorted.getFirst().rests(sorted.getLast()))
                        throw refuse(events, "Style '%s' gaits %s over a track that does not close - an offset moves where the cycle wraps, and a wrap the two ends disagree across is a jump",
                            style.styleId(), reading);
                });
            }
        }

        /**
         * Whether one stance's address reaches the row a rank names.
         *
         * <p>An address naming no rank reaches every row the mesh answers, so it reaches that one
         * too - which is what makes a phase written beside the whole-roster step verb bind.
         */
        private static boolean reachesRank(@NotNull PoseScript.Stance stance, @NotNull Rank rank) {
            return legsOf(stance)
                .filter(legs -> legs.rank().isEmpty() || legs.rank().get() == rank)
                .isPresent();
        }

        /**
         * Whether one stance's address reaches the far side of a pair.
         *
         * <p>An address naming no side reaches both sides, so it reaches that one too. Read off
         * what the author wrote and never off the mesh: a chain reaching no far leg on one subject
         * has to reach the same verdict as one reaching four on the next.
         */
        private static boolean reachesFarSide(@NotNull PoseScript.Stance stance) {
            return legsOf(stance)
                .filter(legs -> legs.side().isEmpty() || legs.side().get() == Side.LEFT)
                .isPresent();
        }

        /**
         * The leg address one stance was written with, empty where it names a bone or a family.
         *
         * @param stance the captured stance to read
         * @return the address, or empty where the stance addresses something other than legs
         */
        private static @NotNull Optional<LimbSelector.Legs> legsOf(@NotNull PoseScript.Stance stance) {
            if (stance.limb().isEmpty()) return Optional.empty();
            if (!(stance.limb().get() instanceof PoseScript.Limb.Selected selected))
                return Optional.empty();
            if (!(selected.selector() instanceof LimbSelector.Legs legs)) return Optional.empty();
            return Optional.of(legs);
        }

    }


    // ------------------------------------------------------------------------------------
    // plan model
    // ------------------------------------------------------------------------------------

    /**
     * One channel's folded verbs - the last absolute write, the summed additive shifts, and at
     * most one wave.
     */
    private static final class ChannelPlan {

        /**
         * Where the channel lands, in author units; {@code null} where nothing states it.
         */
        private @Nullable Double absoluteDegrees;

        /**
         * The summed additive shifts, in author units.
         */
        private double additive;

        /**
         * The seat carry, in the evaluator's own units - solved beside the mesh at the pivots the
         * graph reads, so it has crossed the flattened factor already and adds after the pixel
         * shifts cross it.
         */
        private double carried;

        /**
         * The swept wave riding the folded stance, or {@code null}.
         */
        private @Nullable PoseScript.Sway sway;

        /**
         * The cycling wave riding the folded stance, or {@code null}.
         */
        private @Nullable PoseScript.Spin spin;

        /**
         * Whether the additive shift was solved against this row's own pivots - a carried
         * seat - and so reads a per-row field on a woven layer, the way a rebased absolute does.
         */
        private boolean perRow;

    }

    /**
     * One captured timeline, the bone it keys, and how far into the cycle it starts.
     *
     * @param bone the stanced bone the track keys, empty where the address the author wrote
     *     answered no bone at all on this mesh
     * @param track the captured timeline
     * @param shiftCycles the share of one cycle this copy of the shape starts into
     */
    private record TrackPlan(@NotNull Optional<String> bone, @NotNull PoseScript.Track track,
                             double shiftCycles) {

        /**
         * Constructs a plan playing the track from the cycle's own start.
         *
         * @param bone the stanced bone the track keys
         * @param track the captured timeline
         */
        private TrackPlan(@NotNull Optional<String> bone, @NotNull PoseScript.Track track) {
            this(bone, track, 0d);
        }

    }

    /**
     * One clip frame in the precision the author wrote it, before the clip narrows it.
     *
     * @param atSeconds when in the cycle the frame lands
     * @param x the first member the frame displaces - pitch in radians, or a model-unit offset
     * @param y the second
     * @param z the third
     */
    /**
     * Records the refusal context and builds it - the entry is the post-mortem, and the
     * {@code throw} at the call site is the gate.
     *
     * @param events the scope the refusal records into
     * @param message the refusal, as a format string
     * @param args the format arguments
     * @return the refusal to throw
     */
    private static @NotNull IllegalArgumentException refuse(@NotNull StyleDiagnostics events,
                                                            @NotNull @PrintFormat String message,
                                                            @Nullable Object... args) {
        String formatted = String.format(message, args);
        events.error("%s", formatted);
        return new IllegalArgumentException(formatted);
    }

    /**
     * The share of a cycle every triangle a style emits stays at its resting bound, which is none
     * of it where no gait planted one.
     *
     * @param script the captured script to read
     * @return the plateau share, between none of the cycle and all of it
     */
    private static double planted(@NotNull PoseScript script) {
        if (script.cycle().isEmpty()) return 0d;
        return script.cycle().get().plantShare().orElse(0d);
    }

    private record Frame(double atSeconds, double x, double y, double z) {

        /**
         * This frame's values read at another instant.
         *
         * @param seconds when the copy lands
         * @return the copy
         */
        private @NotNull Frame at(double seconds) {
            return new Frame(seconds, this.x, this.y, this.z);
        }

        /**
         * Whether another frame displaces exactly what this one does.
         *
         * @param other the frame to compare against
         * @return {@code true} where all three members agree
         */
        private boolean rests(@NotNull Frame other) {
            return this.x == other.x && this.y == other.y && this.z == other.z;
        }

        /**
         * Whether another frame displaces what this one does once the clip has narrowed both.
         *
         * <p>The clip stores floats, so two frames a clip cannot tell apart are the same frame
         * whatever their authored precision said, and comparing at the wider width would refuse a
         * wrap that landed a hair off the instant it was read at.
         *
         * @param other the frame to compare against
         * @return {@code true} where all three members agree as the clip holds them
         */
        private boolean narrows(@NotNull Frame other) {
            return (float) this.x == (float) other.x
                && (float) this.y == (float) other.y
                && (float) this.z == (float) other.z;
        }

    }

    /**
     * One clip channel coordinate.
     *
     * @param bone the bone the channel displaces
     * @param target which of the bone's members it displaces
     */
    private record ChannelKey(@NotNull String bone, @NotNull PoseClip.Target target) {}

    /**
     * One stance with every fragment of its TRAVEL multiplied, and everything else untouched.
     *
     * <p>What travels is a wave's bounds, the angle a turn covers, and the reach of each fragment
     * of a timeline. What is left alone is everything that states where a limb LANDS - the writes,
     * the uniform scales and the aim targets - because a multiple of a destination is a different
     * destination rather than a shorter excursion toward one. A scale in particular rests at one
     * rather than at zero, so multiplying it would resize the bone instead of moving it less far.
     *
     * <p>A multiple of the whole travel returns the stance itself rather than a copy of it, which
     * is what keeps a gait stating no multiple lowering exactly as it lowered before there was one
     * to state - the fragment list is the very instance the capture built, identity included.
     *
     * @param stance the captured stance to scale
     * @param factor what the travel is multiplied by
     * @return the scaled stance, or the stance itself at a factor of one
     */
    private static @NotNull PoseScript.Stance scaled(@NotNull PoseScript.Stance stance, double factor) {
        if (factor == 1d) return stance;
        return new PoseScript.Stance(stance.limb(), Concurrent.newUnmodifiableList(
            stance.fragments().stream().<PoseScript.Fragment>map(fragment -> switch (fragment) {
                case PoseScript.Sway sway -> new PoseScript.Sway(sway.axis(),
                    sway.fromDegrees() * factor, sway.toDegrees() * factor);
                case PoseScript.Spin spin -> new PoseScript.Spin(spin.axis(),
                    spin.perPeriodDegrees() * factor);
                case PoseScript.Track track -> scaled(track, factor);
                case PoseScript.Write write -> write;
                case PoseScript.Scale scale -> scale;
                case PoseScript.Aim aim -> aim;
            }).toList()));
    }

    /**
     * One timeline with every motion fragment's reach multiplied, its times untouched - a gain
     * states how far a copy of the shape travels and never when it travels there.
     */
    private static @NotNull PoseScript.Track scaled(@NotNull PoseScript.Track track, double factor) {
        return new PoseScript.Track(
            Concurrent.newUnmodifiableList(track.motions().stream()
                .map(motion -> scaled(motion, factor)).toList()),
            track.overSeconds(), track.ease(), track.looping());
    }

    /**
     * One motion fragment with its reach multiplied.
     */
    private static @NotNull PoseScript.Motion scaled(@NotNull PoseScript.Motion motion, double factor) {
        return switch (motion) {
            case PoseScript.Swing swing -> new PoseScript.Swing(swing.axis(),
                swing.fromDegrees() * factor, swing.toDegrees() * factor);
            case PoseScript.Bob bob -> new PoseScript.Bob(bob.pixels() * factor);
            case PoseScript.Keyframe frame -> new PoseScript.Keyframe(frame.atSeconds(),
                frame.pitchDegrees() * factor, frame.yawDegrees() * factor,
                frame.rollDegrees() * factor);
            case PoseScript.Shift shift -> new PoseScript.Shift(shift.atSeconds(),
                shift.xPixels() * factor, shift.yPixels() * factor, shift.zPixels() * factor);
        };
    }

    /**
     * The double-width sum of two expressions - the splice shape every lowering rule emits.
     */
    private static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

    /**
     * An angle wrapped into the half-open turn around zero, in degrees.
     */
    private static double normalized(double degrees) {
        double wrapped = degrees % 360d;
        if (wrapped > 180d) wrapped -= 360d;
        if (wrapped < -180d) wrapped += 360d;
        return wrapped;
    }

}
