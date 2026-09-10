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
import lib.minecraft.renderer.pose.author.PoseScript;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.tensor.Vector3f;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
     * @param droppedBones the written bones the target mesh does not declare, in first-written
     *     order - what a strict install refuses over and a tolerant one proceeds past
     * @param diagnostics the scope this compile recorded into
     */
    public record Compiled(
        @NotNull EntityPose pose,
        @NotNull PoseStyle style,
        @NotNull ConcurrentList<String> droppedBones,
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
         * The written bones the mesh does not declare, in first-written order.
         */
        private final @NotNull List<String> dropped = new ArrayList<>();

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
                this.refuse("Style '%s' cannot compile against a pose that could not be read: %s",
                    this.style.styleId(), this.shipped.refusal().orElse(""));

            this.validatePeriod();
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
                this.events.warn("dropped bones: %d written bone(s) [%s] missing from a mesh declaring [%s]",
                    this.dropped.size(), String.join(", ", this.dropped),
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
         * Refuses a declared period the strip cannot frame or a still style cannot read.
         */
        private void validatePeriod() {
            if (this.script.periodSeconds().isEmpty()) return;
            double seconds = this.script.periodSeconds().getAsDouble();
            if (seconds <= 0d)
                this.refuse("Style '%s' declares a period of '%s' seconds, which is not positive",
                    this.style.styleId(), seconds);
            long ticks = Math.round(seconds * TICKS_PER_SECOND);
            if (ticks <= 0 || ticks % StyleCatalog.STRIP_FRAMES != 0)
                this.refuse("Style '%s' declares a period of '%s' seconds (%d ticks), which the %d-frame strip does not tile",
                    this.style.styleId(), seconds, ticks, StyleCatalog.STRIP_FRAMES);
            if (this.style.sources().isEmpty())
                this.refuse("Style '%s' declares a period but holds still - only a moving style reads one",
                    this.style.styleId());
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
                PoseScript.Limb limb = stance.limb().get();
                boolean implicit = this.implicitHatMirror(stance);
                if (implicit) {
                    this.hatMirror = true;
                    continue;
                }
                if (!this.mesh.getBones().containsKey(limb.bone())) {
                    if (!this.dropped.contains(limb.bone()))
                        this.dropped.add(limb.bone());
                    for (PoseScript.Track track : stance.tracks())
                        this.trackPlans.add(new TrackPlan(limb.bone(), track));
                    continue;
                }
                PoseScript.Limb landed = this.articulated(limb);
                for (PoseScript.Track track : stance.tracks())
                    this.trackPlans.add(new TrackPlan(landed.bone(), track));
                this.foldLimb(landed, stance);
            }
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
        private @NotNull PoseScript.Limb articulated(@NotNull PoseScript.Limb limb) {
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
            if (!stance.aims().isEmpty())
                this.refuse("Style '%s' aims a container step - a step has no pivot to aim from",
                    this.style.styleId());
            if (!stance.tracks().isEmpty())
                this.refuse("Style '%s' keys a timeline on a container step - a clip channel names a bone",
                    this.style.styleId());
            if (!stance.scales().isEmpty())
                this.refuse("Style '%s' scales a container step, which reaches no bone below it",
                    this.style.styleId());
            LinkedHashMap<PoseChannel, ChannelPlan> plan = new LinkedHashMap<>();
            this.foldVerbs(stance, plan);
            this.stepPlans.add(plan);
        }

        /**
         * Folds one limb stance's verbs into the bone's accumulated plan.
         */
        private void foldLimb(@NotNull PoseScript.Limb limb, @NotNull PoseScript.Stance stance) {
            LinkedHashMap<PoseChannel, ChannelPlan> plan =
                this.bonePlans.computeIfAbsent(limb.bone(), bone -> new LinkedHashMap<>());
            this.foldVerbs(stance, plan);
            for (PoseScript.Scale scale : stance.scales())
                this.scalePlans.put(limb.bone(), scale.factor());
            for (PoseScript.Aim aim : stance.aims())
                this.foldAim(limb, aim, plan);
        }

        /**
         * Folds one stance's writes and waves - the last absolute write states where a channel
         * lands, every additive write shifts it, and one wave rides the folded stance.
         */
        private void foldVerbs(@NotNull PoseScript.Stance stance,
                               @NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan) {
            for (PoseScript.Write write : stance.writes()) {
                ChannelPlan channel = plan.computeIfAbsent(write.channel(), key -> new ChannelPlan());
                if (write.absolute()) channel.absoluteDegrees = write.value();
                else channel.additive += write.value();
            }
            for (PoseScript.Sway sway : stance.sways())
                this.foldWave(plan, channelOf(sway.axis()), sway, null);
            for (PoseScript.Spin spin : stance.spins())
                this.foldWave(plan, channelOf(spin.axis()), null, spin);
        }

        /**
         * Claims one channel's wave slot - one field holds one driver, so a second wave refuses.
         */
        private void foldWave(@NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan,
                              @NotNull PoseChannel channel,
                              @Nullable PoseScript.Sway sway, @Nullable PoseScript.Spin spin) {
            ChannelPlan folded = plan.computeIfAbsent(channel, key -> new ChannelPlan());
            if (folded.sway != null || folded.spin != null)
                this.refuse("Style '%s' waves channel '%s' twice on one target - one field holds one driver",
                    this.style.styleId(), channel.token());
            folded.sway = sway;
            folded.spin = spin;
        }

        /**
         * Whether a stance is the head's implicit hat mirror - a hat stance whose fragment
         * lists are the very instances a head stance captured, which only the automatic
         * build-time copy produces. An implicit mirror rides the head's lowered instances and
         * drops silently where a mesh lacks the shell, because the author never spelled it.
         */
        private boolean implicitHatMirror(@NotNull PoseScript.Stance stance) {
            if (stance.limb().map(limb -> !"hat".equals(limb.bone())).orElse(true)) return false;
            boolean carries = !stance.writes().isEmpty() || !stance.scales().isEmpty()
                || !stance.aims().isEmpty() || !stance.sways().isEmpty()
                || !stance.spins().isEmpty() || !stance.tracks().isEmpty();
            if (!carries) return false;
            for (PoseScript.Stance other : this.script.stances()) {
                if (other == stance) continue;
                if (other.limb().map(limb -> "head".equals(limb.bone())).orElse(false)
                    && other.writes() == stance.writes() && other.scales() == stance.scales()
                    && other.aims() == stance.aims() && other.sways() == stance.sways()
                    && other.spins() == stance.spins() && other.tracks() == stance.tracks())
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
                if (track.bone().equals(bone)) return true;
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
                this.refuse("Style '%s' scales bone '%s' whose axes rest at ('%s', '%s', '%s') - one uniform delta cannot rebase divergent rests",
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
                    this.refuse("Style '%s' displaces the container of a mesh flattened at '%s' - the step seats parentless, which that factor alone does not answer",
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
                this.refuse("Style '%s' hovers a mesh flattened at '%s' - the step seats parentless, which that factor alone does not answer",
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
                this.refuse("Style '%s' writes container channel '%s' twice - one field holds one driver",
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
                    this.refuse("Style '%s' mixes loop() and once() across its timelines - a clip loops or holds as one",
                        this.style.styleId());
            double length = 0d;
            for (TrackPlan plan : this.trackPlans)
                length = Math.max(length, plan.track().overSeconds().orElse(this.windowSeconds));
            LinkedHashMap<ChannelKey, List<PoseClip.Keyframe>> accumulated = new LinkedHashMap<>();
            for (TrackPlan plan : this.trackPlans)
                this.emitTrack(plan, accumulated);
            if (accumulated.isEmpty()) return Optional.empty();

            List<PoseClip.Channel> channels = new ArrayList<>(accumulated.size());
            accumulated.forEach((key, keyframes) -> {
                keyframes.sort(Comparator.comparingDouble(PoseClip.Keyframe::timeSeconds));
                for (int at = 1; at < keyframes.size(); at++)
                    if (keyframes.get(at).timeSeconds() == keyframes.get(at - 1).timeSeconds())
                        this.refuse("Style '%s' keys bone '%s' %s twice at '%s' seconds - keyframe times ascend strictly per channel",
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
         * Emits one track's motion fragments as keyframes onto the accumulated channels.
         */
        private void emitTrack(@NotNull TrackPlan plan,
                               @NotNull LinkedHashMap<ChannelKey, List<PoseClip.Keyframe>> accumulated) {
            PoseScript.Track track = plan.track();
            double length = track.overSeconds().orElse(this.windowSeconds);
            PoseClip.Interpolation curve = track.ease() == Ease.SMOOTH
                ? PoseClip.Interpolation.CATMULLROM
                : PoseClip.Interpolation.LINEAR;
            for (PoseScript.Motion motion : track.motions()) {
                switch (motion) {
                    case PoseScript.Swing swing -> {
                        List<PoseClip.Keyframe> frames = this.framesOf(accumulated, plan.bone(), PoseClip.Target.ROTATION);
                        frames.add(rotationFrame(0d, swing.axis(), swing.fromDegrees(), curve));
                        frames.add(rotationFrame(length / 2d, swing.axis(), swing.toDegrees(), curve));
                        frames.add(rotationFrame(length, swing.axis(), swing.fromDegrees(), curve));
                    }
                    case PoseScript.Bob bob -> {
                        List<PoseClip.Keyframe> frames = this.framesOf(accumulated, plan.bone(), PoseClip.Target.POSITION);
                        float lifted = (float) (-bob.pixels() / this.flattened);
                        frames.add(new PoseClip.Keyframe(0f, 0f, 0f, 0f, curve));
                        frames.add(new PoseClip.Keyframe((float) (length / 2d), 0f, lifted, 0f, curve));
                        frames.add(new PoseClip.Keyframe((float) length, 0f, 0f, 0f, curve));
                    }
                    case PoseScript.Keyframe frame ->
                        this.framesOf(accumulated, plan.bone(), PoseClip.Target.ROTATION)
                            .add(new PoseClip.Keyframe((float) frame.atSeconds(),
                                (float) Math.toRadians(frame.pitchDegrees()),
                                (float) Math.toRadians(frame.yawDegrees()),
                                (float) Math.toRadians(frame.rollDegrees()), curve));
                    case PoseScript.Shift shift ->
                        this.framesOf(accumulated, plan.bone(), PoseClip.Target.POSITION)
                            .add(new PoseClip.Keyframe((float) shift.atSeconds(),
                                (float) (shift.xPixels() / this.flattened),
                                (float) (shift.yPixels() / this.flattened),
                                (float) (shift.zPixels() / this.flattened), curve));
                }
            }
        }

        /**
         * The accumulating keyframe list of one bone and target.
         */
        private @NotNull List<PoseClip.Keyframe> framesOf(
            @NotNull LinkedHashMap<ChannelKey, List<PoseClip.Keyframe>> accumulated,
            @NotNull String bone, @NotNull PoseClip.Target target) {

            return accumulated.computeIfAbsent(new ChannelKey(bone, target), key -> new ArrayList<>());
        }

        /**
         * One rotation keyframe - the swung axis carries the delta in radians, the others rest.
         */
        private static @NotNull PoseClip.Keyframe rotationFrame(
            double atSeconds, @NotNull Turn axis, double degrees, @NotNull PoseClip.Interpolation curve) {

            float radians = (float) Math.toRadians(degrees);
            return new PoseClip.Keyframe((float) atSeconds,
                axis == Turn.PITCH ? radians : 0f,
                axis == Turn.YAW ? radians : 0f,
                axis == Turn.ROLL ? radians : 0f,
                curve);
        }

        /**
         * Splices the raw expression captures - each interned, checked once over every node, and
         * replacing its channel whole under this style's gate, because the author owns the graph
         * there and every other style of the row owns what the channel already held.
         */
        private void lowerRaws(@NotNull LinkedHashMap<String, Map<PoseChannel, PoseExpr>> bones) {
            for (PoseScript.Raw raw : this.script.raws()) {
                if (!this.mesh.getBones().containsKey(raw.bone())) {
                    if (!this.dropped.contains(raw.bone()))
                        this.dropped.add(raw.bone());
                    continue;
                }
                PoseExpr interned = this.pool.intern(raw.expr());
                this.checkRaw(interned);
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
         * Runs the one per-node check walk over a raw graph, memoized per node instance.
         */
        private void checkRaw(@NotNull PoseExpr root) {
            this.checkRaw(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        /**
         * Checks one raw node - width exactness, field namespace, roster presence, arity.
         */
        private void checkRaw(@NotNull PoseExpr node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            switch (node) {
                case PoseExpr.Const constant -> {
                    if (constant.width() == PoseOperator.Width.FLOAT
                        && (double) (float) constant.value() != constant.value())
                        this.refuse("Style '%s' splices float literal '%s', which no float holds exactly",
                            this.style.styleId(), constant.value());
                }
                case PoseExpr.Input input -> {
                    String gate = FIELD_PREFIX + this.style.styleId();
                    if (input.field().startsWith(FIELD_PREFIX)
                        && !input.field().equals(gate) && !input.field().startsWith(gate + "$"))
                        this.refuse("Style '%s' reads field '%s', which another style's namespace drives",
                            this.style.styleId(), input.field());
                }
                case PoseExpr.BoneRead read -> {
                    if (!this.mesh.getBones().containsKey(read.bone()))
                        this.refuse("Style '%s' reads bone '%s', which this mesh does not declare - a read of a missing bone throws at render",
                            this.style.styleId(), read.bone());
                }
                case PoseExpr.Op op -> {
                    if (op.operands().size() != op.operator().arity())
                        this.refuse("Style '%s' applies '%s' to %d operand(s), which takes %d",
                            this.style.styleId(), op.operator().token(),
                            op.operands().size(), op.operator().arity());
                    for (PoseExpr operand : op.operands())
                        this.checkRaw(operand, visited);
                }
                case PoseExpr.Select select -> {
                    this.checkRaw(select.condition(), visited);
                    this.checkRaw(select.whenTrue(), visited);
                    this.checkRaw(select.whenFalse(), visited);
                }
            }
        }

        /**
         * Checks one raw predicate's operands under the same walk.
         */
        private void checkRaw(@NotNull PosePredicate node, @NotNull Set<Object> visited) {
            if (!visited.add(node)) return;
            this.checkRaw(node.left(), visited);
            this.checkRaw(node.right(), visited);
        }

        /**
         * Solves one aim - pitch and yaw from the direction between the stanced bone's pivot
         * and the target, both in the mesh's own pixels so the angles are unchanged by any
         * flattening factor. A hanging limb rests pointing down and takes the quarter-turn
         * pitch offset; a facing bone takes the solve as-is; roll stays authored. The solved
         * pair lands as ordinary absolute writes, so rebase, elision and the driven-base
         * refusal all apply unchanged.
         */
        private void foldAim(@NotNull PoseScript.Limb limb, @NotNull PoseScript.Aim aim,
                             @NotNull LinkedHashMap<PoseChannel, ChannelPlan> plan) {
            Vector3f pivot = this.mesh.getBones().get(limb.bone()).getPivot();
            double dx = aim.xPixels() - pivot.x();
            double dy = aim.yPixels() - pivot.y();
            double dz = aim.zPixels() - pivot.z();
            if (dx == 0d && dy == 0d && dz == 0d)
                this.refuse("Style '%s' aims bone '%s' at its own pivot - no direction to aim",
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
                this.refuse("Style '%s' writes bone '%s' channel '%s' absolutely over a base reading driven field '%s' - %s",
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
         * Records the refusal context and throws it - the entry is the post-mortem, the throw the gate.
         */
        private void refuse(@NotNull @PrintFormat String message, @Nullable Object... args) {
            String formatted = String.format(message, args);
            this.events.error("%s", formatted);
            throw new IllegalArgumentException(formatted);
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
     * One captured timeline addressed at one bone.
     *
     * @param bone the stanced bone the track keys
     * @param track the captured timeline
     */
    private record TrackPlan(@NotNull String bone, @NotNull PoseScript.Track track) {}

    /**
     * One clip channel coordinate.
     *
     * @param bone the bone the channel displaces
     * @param target which of the bone's members it displaces
     */
    private record ChannelKey(@NotNull String bone, @NotNull PoseClip.Target target) {}

    /**
     * The double-width sum of two expressions - the splice shape every lowering rule emits.
     */
    private static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

    /**
     * The rotation channel one turn axis lands on.
     */
    private static @NotNull PoseChannel channelOf(@NotNull Turn axis) {
        return switch (axis) {
            case PITCH -> PoseChannel.X_ROT;
            case YAW -> PoseChannel.Y_ROT;
            case ROLL -> PoseChannel.Z_ROT;
        };
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
