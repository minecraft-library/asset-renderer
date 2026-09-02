package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.IdleFigure;
import lib.minecraft.renderer.asset.pose.IdleState;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * The mesh a subject's own model leaves it holding at one instant - {@link PoseEvaluator}'s channel
 * values written back onto the bones they name.
 *
 * <p><b>Under the {@code bind} style row this hands back the very instance it was given</b>,
 * and that identity is the whole of what makes the authored pose cost nothing: no bone is copied, no
 * float is touched, and a render that asks for nothing draws exactly the bytes it drew before. The
 * same holds for a subject whose model poses nothing, one whose pose could not be read, and one that
 * writes only channels a mesh does not carry.
 *
 * <p>Otherwise the bone map is rebuilt <b>in the mesh's own order</b>, because
 * {@link EntityModelData#getBones()} insertion order is the tied-depth priority the depth contract
 * rests on - a coplanar pair is last-drawn-wins, so re-ordering the bones re-decides which face
 * survives.
 *
 * <p><b>A written channel replaces the value it names rather than displacing it.</b> The table's
 * expressions already read the authored value where they build on it, so what comes back is where
 * the bone stands and not how far it moved. A channel the pose leaves alone keeps the mesh's own
 * value untouched, and one written to exactly what the mesh already held keeps it too - which is
 * what makes a pose that resolves to the bind pose a bit-for-bit no-op rather than a round trip
 * through radians.
 *
 * <p><b>A subject is more than one posed mesh.</b> Each overlay pass carries geometry of its own and
 * poses it with its own model class, so {@link #posedSubject} poses the body and every pass together
 * - posing the body alone leaves a sheep's wool where the sheep no longer is. The passes that redraw
 * the body's own mesh, the collar and the horse marking, are handed the posed body directly and move
 * with it for free.
 *
 * <p><b>What the subject's RENDERER composes arrives already composed.</b> Vanilla applies
 * {@code setupRotations} to the pose stack before it submits the body or any layer, so the index
 * build seats those steps at the front of every pose the subject's meshes take - the body's, each
 * overlay pass's and the baby's - and what this reads is one container holding both.
 *
 * <p>Which bones a subject rests without is not decided here at all: the tables carry it resolved,
 * on the {@code undrawn} lists the load-time strip reads, and a flag channel the generator cannot
 * settle to a literal refuses the flow there rather than surfacing in a frame.
 */
@UtilityClass
public final class PoseKit {

    /** The render-state figure a frame's tick answers - vanilla's free-running age for the subject. */
    private static final @NotNull String AGE_IN_TICKS = "ageInTicks";

    /** How far through its stride a walking subject is, which vanilla steps rather than derives. */
    private static final @NotNull String WALK_POSITION = "walkAnimationPos";

    /** How hard a walking subject is walking, and the amount its stride advances by each tick. */
    private static final @NotNull String WALK_SPEED = "walkAnimationSpeed";

    /**
     * The stride amplitude {@link EntityOptions.PoseMode#WALK} walks at.
     *
     * <p>The full one: vanilla clamps what it accumulates into this figure to one, so a subject here
     * is walking as hard as anything ever does and every lesser gait is a fraction of the same
     * curve. It is also what the phase advances by per tick, the two being one schedule.
     */
    private static final float WALK_AMPLITUDE = 1f;

    /**
     * The name the container enters the bone map under, when a pose writes one at all - chosen to
     * collide with nothing a vanilla model class declares a field for.
     */
    private static final @NotNull String CONTAINER_BONE = "$container";

    /**
     * The excursions every caller gets who names none, which are the ones the harness drives.
     *
     * <p>Held rather than built per call so the default path allocates nothing, and so that a caller
     * who overrides an idle figure is the only one whose render is not comparable against the
     * reference set.
     */
    private static final @NotNull AnimationOptions DEFAULT_ANIMATION = AnimationOptions.defaults();

    /**
     * The mesh this subject's model leaves it holding at one tick.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param subject the resolved subject, supplying the mesh, the pose and what it rests at
     * @param tick the frame's sample tick
     * @return the posed mesh, or the subject's own mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick) {

        return posed(mode, subject, tick, DEFAULT_ANIMATION);
    }

    /**
     * The mesh this subject's model leaves it holding at one tick, at a caller's own excursions.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param subject the resolved subject, supplying the mesh, the pose and what it rests at
     * @param tick the frame's sample tick
     * @param animation what each idle figure rests at and reaches
     * @return the posed mesh, or the subject's own mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick,
        @NotNull AnimationOptions animation) {

        EntityOptions.PoseMode gait = gaitOf(mode, subject, animation);
        if (gait == EntityOptions.PoseMode.BIND) return subject.model();
        return posed(gait, subject.pose(), subject.model(), tick, animation);
    }

    /**
     * One mesh where the pose that belongs to it leaves it at one tick.
     *
     * <p>Held apart from the subject because a subject is more than one posed mesh: each overlay pass
     * poses its own with its own model class, and a pose belongs to a mesh rather than to a subject.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param pose the pose belonging to this mesh
     * @param model the mesh to pose
     * @param tick the frame's sample tick
     * @return the posed mesh, or the given mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull EntityPose pose,
        @NotNull EntityModelData model, int tick) {

        return posed(mode, pose, model, tick, DEFAULT_ANIMATION);
    }

    /**
     * One mesh where the pose that belongs to it leaves it at one tick, at a caller's own excursions.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param pose the pose belonging to this mesh
     * @param model the mesh to pose
     * @param tick the frame's sample tick
     * @param animation what each idle figure rests at and reaches
     * @return the posed mesh, or the given mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull EntityPose pose,
        @NotNull EntityModelData model, int tick, @NotNull AnimationOptions animation) {

        if (mode == EntityOptions.PoseMode.BIND) return model;
        if (!pose.isReadable()) return model;

        // A lone mesh answers for itself. The subject-level entries resolve before they reach here, so
        // this arm is only taken by a caller who has one mesh and no subject to ask about - and a mesh
        // on its own cannot scroll, there being no pass to carry the rate.
        EntityOptions.PoseMode gait = mode == EntityOptions.PoseMode.ANIMATED
            ? gaitOf(motionOf(List.of(new Drawn(pose, model)), false, animation))
            : mode;
        ToDoubleFunction<String> frame = frameAt(gait, tick, animation);
        PoseEvaluator.ChannelWrites writes = PoseEvaluator.evaluate(pose, model, frame);
        // The clips a model plays are applied ON TOP of what its body assigned, because vanilla's
        // three offset members all add to the value already there. So the two are resolved apart and
        // composed here rather than merged into one write set, which is also what keeps the replace
        // rule and the add rule from having to be told apart per channel further down.
        ClipKit.Displacement displaced = ClipKit.deltas(pose, model, frame);
        if (writes.isEmpty() && displaced.isEmpty()) return model;
        return rebuild(model, writes, displaced);
    }

    /**
     * The whole subject as it stands at one tick - its own mesh posed, and every overlay pass's mesh
     * posed by the model class that pass belongs to.
     *
     * <p>An overlay carries geometry of its own and a pose of its own, so posing the body alone
     * leaves a sheep's wool where the sheep no longer is. What they share is the sequence the
     * subject's renderer composes above them, seated at the front of each pose's container at index
     * build, the subject being one animal however many passes draw it.
     *
     * <p>Answers the very definition it was given when nothing moved, which is what keeps the
     * authored pose from rebuilding a definition per frame and per measured bound.
     *
     * @param mode the authored pose, or the one its models evaluate at the tick under a gait
     * @param subject the resolved subject
     * @param tick the frame's sample tick
     * @return the subject carrying the meshes it holds at that tick
     */
    public static @NotNull Entity posedSubject(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick) {

        return posedSubject(mode, subject, tick, DEFAULT_ANIMATION);
    }

    /**
     * The subject with every mesh it draws posed at one tick, at a caller's own excursions.
     *
     * @param mode the authored pose, or the one its models evaluate at the tick under a gait
     * @param subject the resolved subject
     * @param tick the frame's sample tick
     * @param animation what each idle figure rests at and reaches
     * @return the subject carrying the meshes it holds at that tick
     */
    public static @NotNull Entity posedSubject(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick,
        @NotNull AnimationOptions animation) {

        EntityOptions.PoseMode gait = gaitOf(mode, subject, animation);
        if (gait == EntityOptions.PoseMode.BIND) return subject;
        EntityModelData model = posed(gait, subject, tick, animation);
        ConcurrentList<Entity.OverlayLayer> overlays = posedOverlays(gait, subject, tick, animation);
        if (model == subject.model() && overlays == subject.overlays()) return subject;
        return subject.mutate().model(model).overlays(overlays).build();
    }

    /**
     * The preset a request resolves to for this subject - the request itself, unless it asked for
     * movement without naming a gait.
     *
     * <p>Worth calling once and carrying where a subject is posed at more than one tick: the answer
     * is a property of the subject rather than of the instant, and reaching it evaluates a whole
     * excursion.
     *
     * @param mode what the caller asked for
     * @param subject the resolved subject
     * @param animation what each idle figure rests at and reaches
     * @return the preset to pose with, which is never {@link EntityOptions.PoseMode#ANIMATED}
     */
    public static EntityOptions.@NotNull PoseMode gaitOf(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject,
        @NotNull AnimationOptions animation) {

        if (mode != EntityOptions.PoseMode.ANIMATED) return mode;
        return gaitOf(motionOf(subject, animation));
    }

    /** The preset that reaches a motion's movement - the walk for a stride, the resting one for the rest. */
    private static EntityOptions.@NotNull PoseMode gaitOf(@NotNull MotionSource motion) {
        return motion == MotionSource.STRIDE ? EntityOptions.PoseMode.WALK : EntityOptions.PoseMode.IDLE;
    }

    /**
     * What carries this subject's movement, read off the poses it draws through.
     *
     * <p>Every mesh the subject draws is evaluated across one excursion at both moving presets, and
     * what varied decides the answer. So this is a question about the shipped tables and the
     * excursions in force rather than about the entity, and a caller who flattens an excursion gets a
     * subject that genuinely no longer moves.
     *
     * <p><b>Answered for the subject as an appearance left it</b>, passes included, so a gate that
     * dropped a pass drops what that pass would have moved: an uncharged creeper does not scroll,
     * because the swirl that scrolls is not one of the overlays it draws.
     *
     * @param subject the resolved subject, supplying every mesh it draws and the pose belonging to each
     * @param animation what each idle figure rests at and reaches
     * @return what moves it, and so which gait reaches that movement
     */
    public static @NotNull MotionSource motionOf(
        @NotNull Entity subject, @NotNull AnimationOptions animation) {

        return motionOf(drawnBy(subject), scrolls(subject), animation);
    }

    /**
     * The per-render memo the bounds pass and the build pass share, posing this subject under the
     * resolved row - and any group member or variant coat measured beside it under its own
     * catalog's answer to the same style id - at one period.
     *
     * @param subject the resolved subject the render draws
     * @param style the resolved catalog row the render selects
     * @param periodTicks the ticks one whole excursion spans
     * @return the memo answering the posed subject per tick
     */
    public static @NotNull PosedFrames frames(
        @NotNull Entity subject, @NotNull PoseStyle style, int periodTicks) {

        return new PosedFrames(subject, style, periodTicks);
    }

    /**
     * The whole subject as its style's drivers leave it at one tick - its own mesh posed, every
     * overlay pass's mesh posed by the model class that pass belongs to, and a suppressed pass's
     * no-hat alternate posed with the pass it stands in for.
     *
     * <p>The {@code bind} row hands back the very instance it was given - identity, not a copy, so
     * the authored path allocates nothing - and so does any style that moves none of the subject's
     * meshes.
     *
     * @param subject the resolved subject
     * @param style the resolved catalog row to pose with
     * @param periodTicks the ticks one whole excursion spans
     * @param tick the frame's sample tick
     * @return the subject carrying the meshes it holds at that tick
     */
    public static @NotNull Entity posed(
        @NotNull Entity subject, @NotNull PoseStyle style, int periodTicks, int tick) {

        if (PoseStyle.BIND.equals(style.id())) return subject;
        EntityModelData model = posed(subject.pose(), subject.model(), style, periodTicks, tick);
        ConcurrentList<Entity.OverlayLayer> overlays = posedOverlays(subject, style, periodTicks, tick);
        if (model == subject.model() && overlays == subject.overlays()) return subject;
        return subject.mutate().model(model).overlays(overlays).build();
    }

    /**
     * One mesh where the pose that belongs to it leaves it at one tick under a resolved style.
     *
     * <p>Held apart from the subject because a subject is more than one posed mesh: each overlay
     * pass poses its own with its own model class, and a pose belongs to a mesh rather than to a
     * subject. The style arrives resolved, so nothing about the subject's motion is derived here;
     * the {@code bind} row and an unreadable pose both answer the given mesh itself.
     *
     * @param pose the pose belonging to this mesh
     * @param mesh the mesh to pose
     * @param style the resolved catalog row to pose with
     * @param periodTicks the ticks one whole excursion spans
     * @param tick the frame's sample tick
     * @return the posed mesh, or the given mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityPose pose, @NotNull EntityModelData mesh,
        @NotNull PoseStyle style, int periodTicks, int tick) {

        if (PoseStyle.BIND.equals(style.id())) return mesh;
        if (!pose.isReadable()) return mesh;
        ToDoubleFunction<String> frame = style.frameAt(tick, periodTicks);
        PoseEvaluator.ChannelWrites writes = PoseEvaluator.evaluate(pose, mesh, frame);
        // The clips a model plays are applied ON TOP of what its body assigned, because vanilla's
        // three offset members all add to the value already there.
        ClipKit.Displacement displaced = ClipKit.deltas(pose, mesh, frame);
        if (writes.isEmpty() && displaced.isEmpty()) return mesh;
        return rebuild(mesh, writes, displaced);
    }

    /** Each overlay pass where a style's drivers leave it, or the list itself when none of them moved. */
    private static @NotNull ConcurrentList<Entity.OverlayLayer> posedOverlays(
        @NotNull Entity subject, @NotNull PoseStyle style, int periodTicks, int tick) {

        ConcurrentList<Entity.OverlayLayer> overlays = subject.overlays();
        List<Entity.OverlayLayer> out = new ArrayList<>(overlays.size());
        boolean moved = false;
        for (Entity.OverlayLayer overlay : overlays) {
            EntityModelData mesh = posed(overlay.pose(), overlay.model(), style, periodTicks, tick);
            // The suppressed-pass alternate is the same mesh with a subtree emptied, so it takes the
            // same pose - a villager under a full-hat profession still moves the head it draws none of.
            Optional<EntityModelData> noHat = overlay.noHatModel()
                .map(alternate -> posed(overlay.pose(), alternate, style, periodTicks, tick));
            moved |= mesh != overlay.model()
                || !noHat.equals(overlay.noHatModel());
            out.add(new Entity.OverlayLayer(mesh, overlay.textureRef(), overlay.pass(),
                overlay.tintArgb(), overlay.skipBounds(), overlay.tintBy(), overlay.textureBy(),
                overlay.gate(), noHat, overlay.pose(), overlay.textureScroll()));
        }
        return moved ? Concurrent.newUnmodifiableList(out) : overlays;
    }

    /**
     * Per-render memo: bounds pass and build pass ask the same ticks; each subject is posed ONCE.
     *
     * <p>Thread-safe, because timeline baking builds frames in parallel. When the style is the
     * {@code bind} row both {@code at} forms answer the given instance without touching a map, so
     * the authored path allocates nothing.
     */
    public static final class PosedFrames {

        /** The resolved subject the per-tick memo poses. */
        private final @NotNull Entity subject;

        /** The resolved catalog row every subject here is posed with. */
        private final @NotNull PoseStyle style;

        /** The ticks one whole excursion spans. */
        private final int periodTicks;

        /** Whether the style is the {@code bind} row, whose answer is always the given instance. */
        private final boolean bind;

        /** The primary subject posed per tick. */
        private final @NotNull ConcurrentHashMap<Integer, Entity> frames = new ConcurrentHashMap<>();

        /** Each further subject posed per (instance, tick). */
        private final @NotNull ConcurrentHashMap<SubjectTick, Entity> memberFrames = new ConcurrentHashMap<>();

        private PosedFrames(@NotNull Entity subject, @NotNull PoseStyle style, int periodTicks) {
            this.subject = subject;
            this.style = style;
            this.periodTicks = periodTicks;
            this.bind = PoseStyle.BIND.equals(style.id());
        }

        /**
         * The primary subject as it stands at one tick, posed once per tick.
         *
         * @param tick the frame's sample tick
         * @return the posed subject
         */
        public @NotNull Entity at(int tick) {
            if (this.bind) return this.subject;
            return this.frames.computeIfAbsent(tick,
                sampled -> posed(this.subject, this.style, this.periodTicks, sampled));
        }

        /**
         * A group member or variant coat as it stands at one tick, posed under its own catalog's
         * {@link StyleCatalog#memberRow answer to the same style id} at the primary's period, once
         * per (member instance, tick).
         *
         * <p>Its own answer rather than the primary's resolved row, because a member is measured in
         * the stance it draws: a baby request resolves the universal rows where its family's rows
         * apply to the adult alone, and an adult coat measured under those would stand flat where
         * its own idle row holds it hovering - a canvas the family's reference does not share.
         *
         * <p>Keyed by REFERENCE identity of the member rather than by id, as a constraint: variant
         * coats share the family id, so an id-keyed memo would answer one coat's mesh for another -
         * the contract is one posed mesh per subject INSTANCE per tick.
         *
         * @param member the member or coat to pose
         * @param tick the frame's sample tick
         * @return the posed member
         */
        public @NotNull Entity at(@NotNull Entity member, int tick) {
            if (this.bind) return member;
            return this.memberFrames.computeIfAbsent(new SubjectTick(member, tick),
                key -> posed(key.subject(),
                    key.subject().styles().memberRow(this.style.id(), this.style),
                    this.periodTicks, key.tick()));
        }

        /**
         * One memo key: a subject instance at a tick, equal by the subject's reference identity -
         * two equal-but-distinct definitions are two subjects to pose.
         *
         * @param subject the subject instance being posed
         * @param tick the tick it is posed at
         */
        private record SubjectTick(@NotNull Entity subject, int tick) {

            @Override
            public boolean equals(Object other) {
                return other instanceof SubjectTick that
                    && this.subject == that.subject
                    && this.tick == that.tick;
            }

            @Override
            public int hashCode() {
                return 31 * System.identityHashCode(this.subject) + this.tick;
            }

        }

    }

    /** Each overlay pass where its own model leaves it, or the list itself when none of them moved. */
    private static @NotNull ConcurrentList<Entity.OverlayLayer> posedOverlays(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick,
        @NotNull AnimationOptions animation) {

        ConcurrentList<Entity.OverlayLayer> overlays = subject.overlays();
        List<Entity.OverlayLayer> out = new ArrayList<>(overlays.size());
        boolean moved = false;
        for (Entity.OverlayLayer overlay : overlays) {
            EntityModelData mesh = posed(mode, overlay.pose(), overlay.model(), tick, animation);
            // The suppressed-pass alternate is the same mesh with a subtree emptied, so it takes the
            // same pose - a villager under a full-hat profession still moves the head it draws none of.
            Optional<EntityModelData> noHat = overlay.noHatModel()
                .map(alternate -> posed(mode, overlay.pose(), alternate, tick, animation));
            moved |= mesh != overlay.model()
                || !noHat.equals(overlay.noHatModel());
            out.add(new Entity.OverlayLayer(mesh, overlay.textureRef(), overlay.pass(),
                overlay.tintArgb(), overlay.skipBounds(), overlay.tintBy(), overlay.textureBy(),
                overlay.gate(), noHat, overlay.pose(), overlay.textureScroll()));
        }
        return moved ? Concurrent.newUnmodifiableList(out) : overlays;
    }

    // ------------------------------------------------------------------------------------

    /**
     * What the subject answers about itself at one tick - nothing, with the figures this preset
     * drives run forward.
     *
     * <p>A subject an offline render poses is standing where it is, and a shipped pose names no
     * figure but the ones the tick drives - everything else about a subject standing still was
     * answered where the table was written. So elapsed age is the reason a frame differs from its
     * neighbour at all, and the rest rests.
     *
     * <p>A gait names the further figures that stop resting, and nothing else about it differs.
     * {@link EntityOptions.PoseMode#WALK} answers the two a stride is carried on: vanilla steps the
     * phase by the amplitude once a tick rather than deriving it from the clock, so the phase is the
     * tick times the amplitude and the two are one schedule.
     *
     * <p>Package-visible as the seam the oracle-equivalence test reads this classifier through.
     */
    static @NotNull ToDoubleFunction<String> frameAt(
        @NotNull EntityOptions.PoseMode mode, int tick, @NotNull AnimationOptions animation) {

        boolean walking = mode == EntityOptions.PoseMode.WALK;
        return field -> {
            if (AGE_IN_TICKS.equals(field)) return tick;
            // Answered before the stride pair, because these are what a subject standing still does:
            // a walking subject's tentacles do not stop waving, so a gait adds to this rather than
            // replacing it. Anything the roster does not name still rests, which is what keeps the
            // contract that a shipped pose names no figure but the ones the tick drives.
            //
            // A scalar figure is answered without the gait and a one-hot WITH it: a figure is a
            // function of the tick alone, where the member a group rests at is not the member it
            // moves at for a subject whose locomotion is a state-gated clip.
            IdleFigure figure = IdleFigure.ofField(field);
            if (figure != null) return animation.idleValue(figure, tick);
            IdleState factor = IdleState.ofField(field);
            if (factor != null) return animation.idleValue(factor, walking);
            if (!walking) return 0d;
            if (WALK_SPEED.equals(field)) return WALK_AMPLITUDE;
            if (WALK_POSITION.equals(field)) return tick * WALK_AMPLITUDE;
            return 0d;
        };
    }

    /** One mesh a subject draws, and the pose belonging to it. */
    private record Drawn(@NotNull EntityPose pose, @NotNull EntityModelData model) {}

    /** What every mesh a subject draws writes and displaces at one tick. */
    private record Instant(
        @NotNull List<PoseEvaluator.ChannelWrites> writes,
        @NotNull List<ClipKit.Displacement> clips
    ) {}

    /**
     * What moves a set of meshes, told apart by what varies across one excursion.
     *
     * <p>A swept figure and elapsed age both vary the written channels across the resting strip, and
     * this measurement cannot tell the two apart, so both answer {@link MotionSource#TICK}.
     */
    private static @NotNull MotionSource motionOf(
        @NotNull List<Drawn> drawn, boolean scrolls, @NotNull AnimationOptions animation) {

        List<Instant> resting = strip(drawn, EntityOptions.PoseMode.IDLE, animation);
        if (varies(resting, Instant::writes)) return MotionSource.TICK;
        if (varies(resting, Instant::clips)) return MotionSource.SELECT;
        // Asked after the geometry rather than before it: a pass that scrolls decides the answer only
        // where nothing about the mesh moved, this being a question about which gait to ask for.
        if (scrolls) return MotionSource.SCROLL;
        List<Instant> walking = strip(drawn, EntityOptions.PoseMode.WALK, animation);
        if (varies(walking, Instant::writes) || varies(walking, Instant::clips)) return MotionSource.STRIDE;
        return MotionSource.NONE;
    }

    /** Every mesh the subject draws, each carrying the pose its own model class wrote. */
    private static @NotNull List<Drawn> drawnBy(@NotNull Entity subject) {
        List<Drawn> out = new ArrayList<>();
        out.add(new Drawn(subject.pose(), subject.model()));
        for (Entity.OverlayLayer overlay : subject.overlays()) {
            out.add(new Drawn(overlay.pose(), overlay.model()));
            // The suppressed-pass alternate is the same mesh with a subtree emptied and takes the same
            // pose, so it moves wherever the pass it stands in for moves.
            overlay.noHatModel().ifPresent(alternate -> out.add(new Drawn(overlay.pose(), alternate)));
        }
        return out;
    }

    /** Whether any pass this subject draws translates its texture rather than its geometry. */
    private static boolean scrolls(@NotNull Entity subject) {
        return subject.overlays().stream().anyMatch(overlay -> overlay.textureScroll().isPresent());
    }

    /** What each tick of one excursion evaluates to, at one preset. */
    private static @NotNull List<Instant> strip(
        @NotNull List<Drawn> drawn, @NotNull EntityOptions.PoseMode mode,
        @NotNull AnimationOptions animation) {

        List<Instant> out = new ArrayList<>(IdleFigure.PERIOD_TICKS);
        for (int tick = 0; tick < IdleFigure.PERIOD_TICKS; tick++) {
            ToDoubleFunction<String> frame = frameAt(mode, tick, animation);
            List<PoseEvaluator.ChannelWrites> writes = new ArrayList<>(drawn.size());
            List<ClipKit.Displacement> clips = new ArrayList<>(drawn.size());
            for (Drawn one : drawn) {
                if (!one.pose().isReadable()) continue;
                writes.add(canonical(PoseEvaluator.evaluate(one.pose(), one.model(), frame)));
                clips.add(canonical(ClipKit.deltas(one.pose(), one.model(), frame)));
            }
            out.add(new Instant(writes, clips));
        }
        return out;
    }

    /**
     * The writes with the two float zeros held together - they compare equal as primitives and
     * unequal boxed, and a write flipping the sign of zero is not a change a render can show.
     */
    private static PoseEvaluator.@NotNull ChannelWrites canonical(PoseEvaluator.@NotNull ChannelWrites writes) {
        return new PoseEvaluator.ChannelWrites(
            writes.container().stream().map(PoseKit::canonical).toList(),
            canonicalBones(writes.bones()));
    }

    /** The displacements with the two float zeros held together, as {@link #canonical(PoseEvaluator.ChannelWrites)}. */
    private static ClipKit.@NotNull Displacement canonical(ClipKit.@NotNull Displacement clips) {
        return new ClipKit.Displacement(canonicalBones(clips.bones()), canonical(clips.container()));
    }

    private static @NotNull Map<String, Map<PoseChannel, Float>> canonicalBones(
        @NotNull Map<String, Map<PoseChannel, Float>> bones) {

        Map<String, Map<PoseChannel, Float>> out = new LinkedHashMap<>(bones.size());
        bones.forEach((bone, channels) -> out.put(bone, canonical(channels)));
        return out;
    }

    private static @NotNull Map<PoseChannel, Float> canonical(@NotNull Map<PoseChannel, Float> channels) {
        Map<PoseChannel, Float> out = new LinkedHashMap<>(channels.size());
        channels.forEach((channel, value) -> out.put(channel, value == 0f ? 0f : value));
        return out;
    }

    /** Whether one half of a strip answers differently at any two of its ticks. */
    private static boolean varies(
        @NotNull List<Instant> strip, @NotNull Function<Instant, Object> part) {

        Object first = part.apply(strip.getFirst());
        return strip.stream().anyMatch(instant -> !first.equals(part.apply(instant)));
    }

    /**
     * Whether the poses read a render-state figure nothing here answers, which is what separates a
     * subject whose animation has no driver from one carrying no animation at all.
     *
     * <p>Recorded off the evaluation rather than read off the table, so a figure named only down a
     * branch nothing takes is not counted - what decides this is what the subject actually asked for.
     */
    private static boolean readsUndriven(
        @NotNull List<Drawn> drawn, @NotNull AnimationOptions animation) {

        Set<String> read = new HashSet<>();
        for (int tick = 0; tick < IdleFigure.PERIOD_TICKS; tick++) {
            ToDoubleFunction<String> driven = frameAt(EntityOptions.PoseMode.WALK, tick, animation);
            ToDoubleFunction<String> recording = field -> {
                read.add(field);
                return driven.applyAsDouble(field);
            };
            for (Drawn one : drawn) {
                if (!one.pose().isReadable()) continue;
                PoseEvaluator.evaluate(one.pose(), one.model(), recording);
                ClipKit.deltas(one.pose(), one.model(), recording);
            }
        }
        return read.stream().anyMatch(field -> !answered(field));
    }

    /** Whether a render answers this render-state figure with anything but the resting zero. */
    private static boolean answered(@NotNull String field) {
        return AGE_IN_TICKS.equals(field)
            || WALK_POSITION.equals(field)
            || WALK_SPEED.equals(field)
            || IdleFigure.ofField(field) != null
            || IdleState.ofField(field) != null;
    }

    /** The mesh with every written channel applied, or the mesh itself when none of them moved it. */
    private static @NotNull EntityModelData rebuild(
        @NotNull EntityModelData model, @NotNull PoseEvaluator.ChannelWrites writes,
        @NotNull ClipKit.Displacement displaced) {

        if (writes.container().isEmpty() && displaced.isEmpty()
            && writes.bones().values().stream().allMatch(Map::isEmpty)) return model;

        // Read from the mesh being posed rather than from the one being built: what a pose and a clip
        // assign is in the model's own units, and this is the factor that puts one of those in this
        // mesh - a fact about the mesh as the tooling flattened it.
        float flattened = model.getFlattenedScale();
        // Collected into a LinkedHashMap rather than through Map.copyOf: the mesh's own bone order is
        // the tied-depth priority, and copyOf salts its iteration per JVM launch.
        LinkedHashMap<String, EntityModelData.Bone> bones = model.getBones().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                bone -> displacedBone(bone.getValue(), bone.getKey(),
                    writes.bones().getOrDefault(bone.getKey(), Map.of()),
                    displaced.of(bone.getKey()), flattened),
                (first, second) -> first, LinkedHashMap::new));
        List<Map<PoseChannel, Float>> container =
            displacedContainer(writes.container(), displaced.container());
        if (!container.isEmpty()) seatUnderContainer(bones, container, flattened);
        return new EntityModelData(model.getTextureSize(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * The container's steps carrying what its clips displace it by, folded onto the innermost.
     *
     * <p><b>It folds onto a step rather than becoming one</b>, for the reason
     * {@link #displacedBone} sums onto a bone: vanilla holds one part pose for the root and
     * {@code offsetPos} and {@code offsetRotation} add into the very fields a body assigned, so the
     * two are one step and not two. A container the pose leaves unwritten starts at rest, which is
     * what makes the sum on an untouched channel the displacement itself.
     *
     * <p>The innermost is the right seat whichever step it turns out to be. Where the pose writes
     * the root the step IS the root and the fold is vanilla's own addition; where the innermost is
     * instead the frame a renderer's sequence closes with, that step turns nothing, and a translate
     * composes by addition either way - so folding into it and hanging a further step below it are
     * the same transform.
     *
     * @param written the steps the pose writes, outermost first
     * @param displaced what the clips displace the container by
     * @return the steps to seat, which is {@code written} itself where no clip reaches the container
     */
    private static @NotNull List<Map<PoseChannel, Float>> displacedContainer(
        @NotNull List<Map<PoseChannel, Float>> written, @NotNull Map<PoseChannel, Float> displaced) {

        if (displaced.isEmpty()) return written;
        if (written.isEmpty()) return List.of(displaced);
        List<Map<PoseChannel, Float>> steps = new ArrayList<>(written);
        Map<PoseChannel, Float> innermost = new EnumMap<>(steps.getLast());
        displaced.forEach((channel, delta) -> innermost.merge(channel, delta, Float::sum));
        steps.set(steps.size() - 1, innermost);
        return steps;
    }

    /**
     * One bone where the pose leaves it and its clips displace it from there.
     *
     * <p>The two compose in one direction: what the pose wrote is a place and what a clip carries is
     * a displacement from it, so a channel both reach is the written value plus the delta, and a
     * channel only a clip reaches is the mesh's own value plus the delta. That is vanilla's own
     * order - a body assigns and then hands the part to {@code offsetPos} and its two siblings.
     *
     * <p>The scale axes are the exception and go somewhere else entirely. A pose's write folds onto
     * the one uniform factor a bone holds, which is the whole-mesh scale already flattened across
     * the mesh; a clip's displacement cannot, because it is per-axis and has to reach the bone's
     * descendants. So it lands on the bone's own pose scale instead, which the chain composes.
     *
     * <p>Both sides are read and summed in the MODEL's own units - a clip displaces the same field a
     * body assigns - and the crossing into a flattened mesh's units happens once, where the value is
     * finally placed.
     */
    private static @NotNull EntityModelData.Bone displacedBone(
        @NotNull EntityModelData.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, @NotNull Map<PoseChannel, Float> displaced,
        float flattened) {

        if (displaced.isEmpty()) return posedBone(bone, name, written, flattened);

        Map<PoseChannel, Float> moved = new EnumMap<>(PoseChannel.class);
        moved.putAll(written);
        for (Map.Entry<PoseChannel, Float> delta : displaced.entrySet()) {
            PoseChannel channel = delta.getKey();
            if (channel.kind() == PoseChannel.Kind.SCALE) continue;
            Float assigned = written.get(channel);
            float base = assigned == null ? authored(bone, channel, flattened) : assigned;
            moved.put(channel, base + delta.getValue());
        }
        return posedBone(posedScale(bone, name, written, displaced), name, moved, flattened);
    }

    /**
     * The bone carrying what its clips scale it by, or the bone itself where none of them do.
     *
     * <p>Refuses rather than guesses where a pose and a clip both reach one bone's scale: vanilla
     * holds one field there and adds the clip to what the body assigned, where these are two fields
     * that multiply, and the two answers part company. No shipped model does both - the fifteen
     * classes playing a scaling clip write no scale channel of their own - so this is a shape the
     * corpus does not have rather than one being handled.
     *
     * @throws RendererException if a pose and a clip both scale one bone
     */
    private static @NotNull EntityModelData.Bone posedScale(
        @NotNull EntityModelData.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, @NotNull Map<PoseChannel, Float> displaced) {

        float x = displaced.getOrDefault(PoseChannel.X_SCALE, 0f);
        float y = displaced.getOrDefault(PoseChannel.Y_SCALE, 0f);
        float z = displaced.getOrDefault(PoseChannel.Z_SCALE, 0f);
        if (x == 0f && y == 0f && z == 0f) return bone;

        if (written.containsKey(PoseChannel.X_SCALE) || written.containsKey(PoseChannel.Y_SCALE)
            || written.containsKey(PoseChannel.Z_SCALE))
            throw new RendererException(
                "entity pose: bone '%s' is scaled by its model and by a clip, which one factor cannot hold",
                name);

        return bone.withPoseScale(new Vector3f(1f + x, 1f + y, 1f + z));
    }

    /**
     * What a channel holds before anything is written to it, in the units a pose and a clip both
     * speak - the mesh's own value, with a flattened mesh's factor taken back off a position.
     */
    private static float authored(
        @NotNull EntityModelData.Bone bone, @NotNull PoseChannel channel, float flattened) {

        return switch (channel) {
            case X -> bone.getPivot().x() / flattened;
            case Y -> bone.getPivot().y() / flattened;
            case Z -> bone.getPivot().z() / flattened;
            case X_ROT -> bone.getRotation().pitchRadians();
            case Y_ROT -> bone.getRotation().yawRadians();
            case Z_ROT -> bone.getRotation().rollRadians();
            case X_SCALE, Y_SCALE, Z_SCALE -> bone.getScale();
        };
    }

    /**
     * One bone where the pose leaves it, or the bone itself when the pose writes it no geometry.
     *
     * <p>The rotation is assembled as the Euler triplet the bone already carries, one channel at a
     * time, because that triplet is what a chain composition finally reads - a rotation pre-composed
     * as a matrix and multiplied in reaches {@code rotationZYX} through different arithmetic and
     * parts from the authored pose at a delta of zero.
     *
     * <p><b>The mesh's root is where a flattened factor stops being one number.</b> The tooling
     * pushes a whole-mesh scale onto the top-level bones as a translate as well as a factor, and a
     * pose that places one of them would need both - so this refuses there rather than answering with
     * half of it. No shipped model does it: the corpus's one mesh that is both flattened and placed
     * by its pose is the elder guardian's, whose spikes and eye all hang off the head.
     *
     * @throws RendererException if a pose places the root of a flattened mesh
     */
    private static @NotNull EntityModelData.Bone posedBone(
        @NotNull EntityModelData.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, float flattened) {

        if (written.isEmpty()) return bone;

        Vector3f pivot = bone.getPivot();
        EulerRotation rotation = bone.getRotation();
        Vector3f placed = new Vector3f(
            placed(written, PoseChannel.X, pivot.x(), flattened),
            placed(written, PoseChannel.Y, pivot.y(), flattened),
            placed(written, PoseChannel.Z, pivot.z(), flattened));
        if (flattened != 1f && bone.getParent() == null && !placed.equals(pivot))
            throw new RendererException(
                "entity pose: bone '%s' is the root of a mesh flattened at '%s' and the pose places it, "
                    + "which that factor alone does not answer",
                name, flattened);
        // Placed, turned and scaled through one copy: what a clip scales the bone by, and which
        // selection draws it, were both settled before this ran, and a positional rebuild is what
        // would put either back at its default.
        return bone.withPose(
            placed,
            new EulerRotation(
                degrees(written, PoseChannel.X_ROT, rotation.pitch(), rotation.pitchRadians()),
                degrees(written, PoseChannel.Y_ROT, rotation.yaw(), rotation.yawRadians()),
                degrees(written, PoseChannel.Z_ROT, rotation.roll(), rotation.rollRadians())),
            scale(written, name, bone.getScale()));
    }

    /** A channel the pose wrote, or the mesh's own where it wrote none. */
    private static float held(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel, float authored) {

        Float value = written.get(channel);
        return value == null ? authored : value;
    }

    /**
     * A pivot component the pose wrote, in the units the mesh stores it in, or the mesh's own where
     * it wrote none.
     *
     * <p>A pose assigns the number vanilla's own part field holds, which is in the model's units. A
     * mesh flattened at {@link EntityModelData#getFlattenedScale() one factor} does not store a pivot
     * in those, every one below the dissolved root having arrived multiplied by it, so the written
     * value crosses the same way - which is what places an elder guardian's spikes where a subject
     * 2.35 times the size wears them rather than at a plain guardian's reach.
     *
     * <p>A value written back to what the mesh already held keeps the mesh's own number rather than
     * the one a divide and a multiply land on, for the reason {@link #degrees} keeps the authored
     * degrees.
     */
    private static float placed(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel,
        float authored, float flattened) {

        Float value = written.get(channel);
        if (value == null) return authored;
        if (flattened == 1f) return value;
        if (value == authored / flattened) return authored;
        return value * flattened;
    }

    /**
     * A rotation channel in the degrees a bone stores it in.
     *
     * <p>The table's arithmetic is in radians throughout, so a written channel converts back. One
     * written to exactly the radians the mesh already read keeps the authored degrees rather than
     * the value a round trip through two conversions lands on, which is what makes a bone the pose
     * puts back where it started bit-identical to one it never touched.
     */
    private static float degrees(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel,
        float authoredDegrees, float authoredRadians) {

        Float value = written.get(channel);
        if (value == null || value == authoredRadians) return authoredDegrees;
        return (float) Math.toDegrees(value);
    }

    /**
     * The one scale a bone holds, folded from the three axes the table writes.
     *
     * <p>Per-axis scale is a shape a bone cannot hold and not a shape the corpus needs: the single
     * model that scales writes one expression to all three axes, so folding them is exact. A
     * divergence is a mesh this cannot express, which is worth failing over rather than picking an
     * axis to believe.
     *
     * @throws RendererException if the three axes do not agree
     */
    private static float scale(
        @NotNull Map<PoseChannel, Float> written, @NotNull String bone, float authored) {

        float x = held(written, PoseChannel.X_SCALE, authored);
        float y = held(written, PoseChannel.Y_SCALE, authored);
        float z = held(written, PoseChannel.Z_SCALE, authored);
        if (x != y || y != z)
            throw new RendererException(
                "entity pose: bone '%s' scales to (%s, %s, %s), which one uniform bone scale cannot hold",
                bone, x, y, z);
        return x;
    }

    /**
     * Seats every top-level bone under the container the pose writes.
     *
     * <p>The container is a parent transform above them all and the mesh names it nowhere, so there
     * is no bone to write it onto - it enters as a cubeless bone every root is re-parented to, which
     * puts it through the same chain composition an authored parent goes through and draws nothing
     * of its own. It starts at rest, the flattening having already put whatever it held into the
     * bones below it, and it enters last so no bone that draws changes the order it is drawn in.
     *
     * <p>Top-level is read the way the chain composition reads it, and that is wider than a null
     * parent: a bone naming a parent this mesh does not declare hangs from the root, and the corpus
     * ships two of them. Reading only the null would seat the container above every bone but those,
     * which would draw them somewhere the rest of the subject is not.
     *
     * <p><b>A step per step, hung off each other in order, rather than one bone folding them.</b>
     * Each step is a part pose and the chain composition already applies one exactly as vanilla
     * applies a {@code ModelPart} - so a sequence needs no arithmetic of its own, only the parenting
     * that says which came first, and the bounds walk composes it the same way for free. Folding two
     * steps into one bone is what cannot be done: a translate between two rotations about different
     * axes is not a triple, and recovering one by pre-composing the product is the matrix arithmetic
     * that parts from an authored pose at a delta of zero.
     *
     * @throws RendererException if the container writes a channel a parent bone does not carry
     */
    private static void seatUnderContainer(
        @NotNull LinkedHashMap<String, EntityModelData.Bone> bones,
        @NotNull List<Map<PoseChannel, Float>> steps, float flattened) {

        for (Map<PoseChannel, Float> written : steps)
            for (PoseChannel channel : written.keySet())
                if (channel.kind() == PoseChannel.Kind.SCALE)
                    throw new RendererException(
                        "entity pose: the container writes '%s', which reaches no bone below it",
                        channel.token());

        // Named off the growing set, so the second step cannot take the first's name and the whole
        // chain stays clear of what the mesh already answers to.
        Set<String> taken = new LinkedHashSet<>(bones.keySet());
        List<String> names = new ArrayList<>(steps.size());
        for (int step = 0; step < steps.size(); step++) {
            String name = containerName(taken);
            taken.add(name);
            names.add(name);
        }

        // The mesh's own roots hang off the INNERMOST step, and they are re-parented before any step
        // enters the map so that what reads as top-level is what the mesh itself declares.
        String innermost = names.getLast();
        bones.replaceAll((bone, seated) -> !isTopLevel(bones, bone, seated)
            ? seated : reparented(seated, innermost));
        // Then the steps, each hung off the one before it, and all of them after every bone that
        // draws so no drawing order changes.
        for (int step = 0; step < steps.size(); step++) {
            EntityModelData.Bone seated =
                posedBone(new EntityModelData.Bone(), names.get(step), steps.get(step), flattened);
            bones.put(names.get(step),
                step == 0 ? seated : reparented(seated, names.get(step - 1)));
        }
    }

    /** One bone hung off a different parent, everything else about it untouched. */
    private static @NotNull EntityModelData.Bone reparented(
        @NotNull EntityModelData.Bone bone, @NotNull String parent) {

        return bone.withParent(parent);
    }

    /** Whether a bone hangs from the root, by the same three tests the chain composition applies. */
    private static boolean isTopLevel(
        @NotNull Map<String, EntityModelData.Bone> bones, @NotNull String name,
        @NotNull EntityModelData.Bone bone) {

        String parent = bone.getParent();
        return parent == null || parent.equals(name) || !bones.containsKey(parent);
    }

    /** A name for the container that no bone of this mesh already answers to. */
    private static @NotNull String containerName(@NotNull Set<String> taken) {
        StringBuilder name = new StringBuilder(CONTAINER_BONE);
        while (taken.contains(name.toString())) name.append('_');
        return name.toString();
    }

}
