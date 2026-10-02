package lib.minecraft.renderer.bake.pose;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseEvaluator;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link EntityMesh#getBones()} insertion order is the tied-depth priority the depth contract
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
 * poses it with its own model class, so {@link #posed(Entity, PoseStyle, int, int)} poses the body
 * and every pass together - posing the body alone leaves a sheep's wool where the sheep no longer
 * is. The passes that redraw the body's own mesh, the collar and the horse marking, are handed the
 * posed body directly and move with it for free. Each equipment mesh - a saddle, a body armour, the
 * happy ghast's harness - is posed the same way, by the model class its layer is handed.
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
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public final class PosePlayer {

    /**
     * The name the container enters the bone map under, when a pose writes one at all - chosen to
     * collide with nothing a vanilla model class declares a field for.
     */
    private static final @NotNull String CONTAINER_BONE = "$container";

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
     * overlay pass's mesh posed by the model class that pass belongs to, a suppressed pass's
     * no-hat alternate posed with the pass it stands in for, and every equipment mesh posed by the
     * model class its layer is handed, under the wearer's style and tick. The worn humanoid armour
     * shell is passed through as it stands.
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
        EntityMesh model = posed(subject.pose(), subject.model(), style, periodTicks, tick);
        ConcurrentList<Entity.OverlayLayer> overlays = posedOverlays(subject, style, periodTicks, tick);
        ConcurrentList<Entity.EquipmentOverlay> equipment = posedEquipment(subject, style, periodTicks, tick);
        if (model == subject.model() && overlays == subject.overlays()
            && equipment == subject.layers().equipment())
            return subject;

        Entity.Builder builder = subject.mutate().model(model).overlays(overlays);
        if (equipment != subject.layers().equipment())
            builder.layers(new Entity.Layers(equipment, subject.layers().humanoidArmor(), subject.layers().wings()));
        return builder.build();
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
    public static @NotNull EntityMesh posed(
        @NotNull EntityPose pose, @NotNull EntityMesh mesh,
        @NotNull PoseStyle style, int periodTicks, int tick) {

        if (PoseStyle.BIND.equals(style.id())) return mesh;
        if (!pose.isReadable()) return mesh;
        ToDoubleFunction<String> frame = style.frameAt(tick, periodTicks);
        PoseEvaluator.ChannelWrites writes = evaluate(pose, mesh, frame);
        // The clips a model plays are applied ON TOP of what its body assigned, because vanilla's
        // three offset members all add to the value already there.
        ClipPlayer.Displacement displaced = ClipPlayer.deltas(pose, mesh, frame);
        if (writes.isEmpty() && displaced.isEmpty()) return mesh;
        return rebuild(mesh, writes, displaced);
    }

    /**
     * Names the innermost step a posed mesh is seated under - the container step every top-level bone
     * of the mesh hangs from, and the one no other step hangs from.
     *
     * <p>It is where the steps above the model end: what the subject's renderer composes and what the
     * pose writes on the container stand at it and above it, and every bone the mesh declares stands
     * below it. The steps are named outermost first, each the next name free of the mesh's own, so
     * the innermost is the last of them.
     *
     * @param posed the mesh as {@link #posed(EntityPose, EntityMesh, PoseStyle, int, int)} answers it
     * @return the innermost step's bone name, or empty where nothing seated the mesh - the {@code bind}
     *     row, an unreadable pose, and a pose writing no container that no clip displaces
     */
    public static @NotNull Optional<String> seat(@NotNull EntityMesh posed) {
        String innermost = null;
        for (String name = CONTAINER_BONE; posed.getBones().containsKey(name); name += '_')
            innermost = name;
        return Optional.ofNullable(innermost);
    }

    /**
     * Evaluates every channel a pose writes to a bone this mesh has.
     *
     * <p>A pose that could not be read writes nothing, the same as one that poses nothing - the two
     * are told apart on {@link EntityPose#isReadable()}, before this, by whatever cares.
     *
     * <p><b>A bone the mesh does not declare is not evaluated</b>, because it does not draw and no
     * caller has anywhere to put the answer. A pose belongs to a model class where a mesh belongs to
     * a subject, so the two disagree by construction wherever a bone rests undrawn and its subtree
     * came out with it - an illager's crossed arms leave the pose still naming the pair it hangs
     * instead, and vanilla's own {@code setupAnim} writes those same fields on parts nothing renders.
     * Reading the mesh for a channel of a bone that is gone is what would fail, and it fails loudly
     * where it is genuinely wrong: a bone the mesh <em>does</em> have, reading one it does not.
     *
     * @param pose the model's pose
     * @param model the mesh being posed, which is what an unwritten channel is read from
     * @param frame what each render-state figure reads as, {@link PoseEvaluator#AT_REST} where none
     *     is driven
     * @return the value each written channel evaluates to
     */
    public static PoseEvaluator.@NotNull ChannelWrites evaluate(
        @NotNull EntityPose pose, @NotNull EntityMesh model,
        @NotNull ToDoubleFunction<String> frame) {

        if (!pose.isReadable()) return PoseEvaluator.ChannelWrites.NONE;

        // Collected in insertion order rather than through Map.copyOf: what comes out is read in
        // order downstream, and copyOf salts its iteration per JVM launch.
        Map<String, Map<PoseChannel, PoseExpr>> declared = pose.bones()
            .entrySet()
            .stream()
            .filter(written -> model.getBones().containsKey(written.getKey()))
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, Map.Entry::getValue,
                (first, second) -> first));
        return PoseEvaluator.evaluate(pose.container(), declared, channels(model), frame);
    }

    /**
     * A list of expressions evaluated against this mesh, each narrowed to the width a channel is
     * finally stored at.
     *
     * <p>What a clip's play site carries: how far through the clip the model is and how hard it is
     * playing it are the model's own arithmetic over the same figures a bone channel reads, so they
     * go through the same evaluation rather than a second one.
     *
     * @param expressions the expressions to evaluate, in order
     * @param model the mesh being posed, which is what an unwritten channel is read from
     * @param frame what each render-state figure reads as, {@link PoseEvaluator#AT_REST} where none
     *     is driven
     * @return each expression's value, in the order given
     */
    public static @NotNull ConcurrentList<Float> values(
        @NotNull List<PoseExpr> expressions, @NotNull EntityMesh model,
        @NotNull ToDoubleFunction<String> frame) {

        return PoseEvaluator.values(expressions, channels(model), frame);
    }

    /**
     * What each of this mesh's bones holds before a pose writes it, in the units the table speaks.
     *
     * <p>A rotation is answered in RADIANS because that is the unit the table's arithmetic is in,
     * where the mesh stores degrees.
     *
     * <p>A position is answered in the MODEL's own units, which a mesh flattened at
     * {@link EntityMesh#getFlattenedScale() one factor} does not store it in: every pivot below
     * the dissolved root arrived multiplied by that factor, and a top-level pivot carries the
     * feet-anchor translate beside it. {@link #authored} undoes both, so the number vanilla's own
     * field holds is what is answered, and what a pose does with it crosses forward again where the
     * kit writes it.
     *
     * <p>A scale is answered as vanilla's field too - the bone's rest over
     * {@link EntityMesh#scaleAbove the scale above its part}, which is exactly one on every part of
     * a mesh flattened at one factor - and a bone's scale is uniform, so all three axes read it.
     *
     * @throws RendererException if an expression reads a bone this mesh does not declare
     */
    private static PoseEvaluator.@NotNull BoneChannels channels(@NotNull EntityMesh model) {
        return (name, channel) -> {
            if (!model.getBones().containsKey(name))
                throw new RendererException("entity pose: reads '%s' of bone '%s', which this mesh does not declare",
                    channel.token(), name);

            return authored(model, name, channel);
        };
    }

    /** Each overlay pass where a style's drivers leave it, or the list itself when none of them moved. */
    private static @NotNull ConcurrentList<Entity.OverlayLayer> posedOverlays(
        @NotNull Entity subject, @NotNull PoseStyle style, int periodTicks, int tick) {

        ConcurrentList<Entity.OverlayLayer> overlays = subject.overlays();
        List<Entity.OverlayLayer> out = new ArrayList<>(overlays.size());
        boolean moved = false;
        for (Entity.OverlayLayer overlay : overlays) {
            EntityMesh mesh = posed(overlay.pose(), overlay.model(), style, periodTicks, tick);
            // The suppressed-pass alternate is the same mesh with a subtree emptied, so it takes the
            // same pose - a villager under a full-hat profession still moves the head it draws none of.
            Optional<EntityMesh> noHat = overlay.noHatModel()
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
     * Each equipment mesh where its layer's own pose leaves it under the wearer's style, or the list
     * itself when none of them moved. Vanilla runs every equipment model's own animation with its
     * wearer's render state, so a mesh plays the row of the class its layer is handed rather than
     * borrowing the wearer's.
     */
    private static @NotNull ConcurrentList<Entity.EquipmentOverlay> posedEquipment(
        @NotNull Entity subject, @NotNull PoseStyle style, int periodTicks, int tick) {

        ConcurrentList<Entity.EquipmentOverlay> equipment = subject.layers().equipment();
        List<Entity.EquipmentOverlay> out = new ArrayList<>(equipment.size());
        boolean moved = false;
        for (Entity.EquipmentOverlay overlay : equipment) {
            EntityMesh mesh = posed(overlay.pose(), overlay.model(), style, periodTicks, tick);
            moved |= mesh != overlay.model();
            out.add(mesh == overlay.model() ? overlay : overlay.withModel(mesh));
        }
        return moved ? Concurrent.newUnmodifiableList(out) : equipment;
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

    /** The mesh with every written channel applied, or the mesh itself when none of them moved it. */
    private static @NotNull EntityMesh rebuild(
        @NotNull EntityMesh model, @NotNull PoseEvaluator.ChannelWrites writes,
        @NotNull ClipPlayer.Displacement displaced) {

        if (writes.container().isEmpty() && displaced.isEmpty()
            && writes.bones().values().stream().allMatch(Map::isEmpty)) return model;

        // Read from the mesh being posed rather than from the one being built: what a pose and a clip
        // assign is in the model's own units, and this is the factor that puts one of those in this
        // mesh - a fact about the mesh as the tooling flattened it.
        float flattened = model.getFlattenedScale();
        // Collected into a LinkedHashMap rather than through Map.copyOf: the mesh's own bone order is
        // the tied-depth priority, and copyOf salts its iteration per JVM launch.
        LinkedHashMap<String, EntityMesh.Bone> bones = model.getBones().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                bone -> displacedBone(model, bone.getValue(), bone.getKey(),
                    writes.bones().getOrDefault(bone.getKey(), Map.of()),
                    displaced.of(bone.getKey()), flattened),
                (first, second) -> first, LinkedHashMap::new));
        List<Map<PoseChannel, Float>> container =
            displacedContainer(writes.container(), displaced.container());
        if (!container.isEmpty()) seatUnderContainer(bones, container, displaced.container(), flattened);
        return new EntityMesh(model.getTextureSize(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * The container's steps carrying what its clips displace it by, folded onto the innermost.
     *
     * <p><b>It folds onto a step rather than becoming one</b>, for the reason
     * {@link #displacedBone} sums onto a bone: vanilla holds one part pose for the root and
     * {@code offsetPos} and {@code offsetRotation} add into the very fields a body assigned, so the
     * two are one step and not two. A container the pose leaves unwritten starts at rest, which is
     * what makes the sum on an untouched channel the displacement itself. With no step written, the
     * displacement is seated as the one step, even where the clips key nothing but its scale.
     *
     * <p>The innermost is the right seat whichever step it turns out to be. Where the pose writes
     * the root the step IS the root and the fold is vanilla's own addition; where the innermost is
     * instead the frame a renderer's sequence closes with, that step turns nothing, and a translate
     * composes by addition either way - so folding into it and hanging a further step below it are
     * the same transform.
     *
     * <p>The scale axes are passed over, as {@link #displacedBone} passes them over: a clip's scale
     * is a factor on the chain rather than a place to sum onto, so {@link #seatUnderContainer}
     * carries a clip's container scale on the innermost step's own pose scale instead.
     *
     * @param written the steps the pose writes, outermost first
     * @param displaced what the clips displace the container by
     * @return the steps to seat, which is {@code written} itself where no clip reaches the container
     */
    private static @NotNull List<Map<PoseChannel, Float>> displacedContainer(
        @NotNull List<Map<PoseChannel, Float>> written, @NotNull Map<PoseChannel, Float> displaced) {

        if (displaced.isEmpty()) return written;
        List<Map<PoseChannel, Float>> steps = new ArrayList<>(written);
        Map<PoseChannel, Float> innermost = new EnumMap<>(PoseChannel.class);
        if (!steps.isEmpty()) innermost.putAll(steps.getLast());
        for (Map.Entry<PoseChannel, Float> delta : displaced.entrySet()) {
            if (delta.getKey().kind() == PoseChannel.Kind.SCALE) continue;
            innermost.merge(delta.getKey(), delta.getValue(), Float::sum);
        }
        if (steps.isEmpty()) steps.add(innermost);
        else steps.set(steps.size() - 1, innermost);
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
     * <p>The scale axes are the exception and are summed nowhere. Both sides land on the bone's own
     * pose scale, which the chain composes after the bone's rotation so it reaches every descendant
     * - a clip's displacement as the field's rest plus the delta over that rest, through
     * {@link #posedScale}, and a written scale as its ratio to the field's rest, through
     * {@link #posedBone} - and one bone reached by both refuses rather than composing them. The
     * field's rest is read here once, through the function the evaluator's read of the channel
     * takes, and only for a bone something writes or displaces: one reached by neither is handed
     * back unread, as {@link #posedBone} hands back a bone written nothing.
     *
     * <p>Both sides are read and summed in the MODEL's own units - a clip displaces the same field a
     * body assigns - and the crossing into a flattened mesh's units happens once, where the value is
     * finally placed.
     */
    private static @NotNull EntityMesh.Bone displacedBone(
        @NotNull EntityMesh model, @NotNull EntityMesh.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, @NotNull Map<PoseChannel, Float> displaced,
        float flattened) {

        if (written.isEmpty() && displaced.isEmpty()) return bone;
        float field = fieldRest(model, name, bone);
        if (displaced.isEmpty()) return posedBone(bone, name, written, flattened, field);

        Map<PoseChannel, Float> moved = new EnumMap<>(PoseChannel.class);
        moved.putAll(written);
        for (Map.Entry<PoseChannel, Float> delta : displaced.entrySet()) {
            PoseChannel channel = delta.getKey();
            if (channel.kind() == PoseChannel.Kind.SCALE) continue;
            Float assigned = written.get(channel);
            float base = assigned == null ? authored(model, name, channel) : assigned;
            moved.put(channel, base + delta.getValue());
        }
        return posedBone(posedScale(bone, name, written, displaced, field), name, moved, flattened, field);
    }

    /**
     * The bone carrying what its clips scale it by, or the bone itself where none of them do.
     *
     * <p>A clip's scale is {@code offsetScale}'s {@code +=} on vanilla's field, so the bone's own
     * field draws at its rest {@code f} plus the displacement {@code d}, and the pose scale is that
     * over the rest, {@code (f + d) / f} - which is exactly one plus the displacement wherever the
     * field rests at one.
     *
     * <p>Refuses rather than guesses where a pose and a clip both reach one bone's scale: vanilla
     * adds the clip onto the field the body assigned, so the bone draws at {@code (s + d) / f} over
     * its field's rest {@code f}, where a written ratio and a displacement multiply to
     * {@code s / f * (f + d) / f}, and the two answers part company wherever {@code s} is not the
     * rest. No shipped model does both - the fifteen classes playing a scaling clip write no scale
     * channel of their own - so this is a shape the corpus does not have rather than one being
     * handled. A field resting at zero refuses as well, having no ratio to carry the displacement.
     *
     * @param bone the bone the clips scale
     * @param name the bone's name, which a refusal reports
     * @param written what the pose writes the bone
     * @param displaced what the clips displace the bone by
     * @param fieldRest the value the bone's own scale field rests at
     * @return the bone carrying the clips' scale, or the bone itself where they scale it by nothing
     * @throws RendererException if a pose and a clip both scale one bone, or a clip scales a field
     *     resting at zero
     */
    private static @NotNull EntityMesh.Bone posedScale(
        @NotNull EntityMesh.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, @NotNull Map<PoseChannel, Float> displaced,
        float fieldRest) {

        float x = displaced.getOrDefault(PoseChannel.X_SCALE, 0f);
        float y = displaced.getOrDefault(PoseChannel.Y_SCALE, 0f);
        float z = displaced.getOrDefault(PoseChannel.Z_SCALE, 0f);
        if (x == 0f && y == 0f && z == 0f) return bone;

        if (written.containsKey(PoseChannel.X_SCALE) || written.containsKey(PoseChannel.Y_SCALE)
            || written.containsKey(PoseChannel.Z_SCALE))
            throw new RendererException(
                "entity pose: bone '%s' is scaled by its model and by a clip, which one factor cannot hold",
                name);
        if (fieldRest == 0f)
            throw new RendererException(
                "entity pose: bone '%s' is scaled by a clip from a rest of zero, which no ratio can carry",
                name);

        return bone.withPoseScale(new Vector3f(
            (fieldRest + x) / fieldRest, (fieldRest + y) / fieldRest, (fieldRest + z) / fieldRest));
    }

    /**
     * What a channel holds before anything is written to it, in the units a pose and a clip both
     * speak - the mesh's own value, with a flattened mesh's factor taken back off a position, and
     * the feet-anchor translate taken off a top-level pivot's y before it, and a scale read as the
     * part's own field.
     *
     * <p>A whole-mesh scale is taken about {@link EntityMesh#FEET_ANCHOR}, so the tooling pushed
     * {@link EntityMesh#flattenedShift the anchor's translate} onto every top-level pivot beside
     * the factor, and the number vanilla's own field holds is what is left once both are undone. A
     * bone below the top level absorbed no translate, its pivot being parent-relative, so its read
     * divides alone.
     *
     * <p>A bone's rest scale is every scale field from the root to the part multiplied together, so
     * the part's own field is that rest over {@link EntityMesh#scaleAbove the scale above the part}
     * - one on every part of a mesh flattened at one factor, and a top part's own factor on a mesh
     * whose factors sit in its top parts. A read of the channel answers that field, and a literal a
     * pose writes is a value of that field, as in vanilla's own arithmetic.
     *
     * <p>This is the one place the crossing is spelled: the evaluator's bone read, a clip's base on
     * a channel the pose leaves unwritten and the seat derivation's resting placement take it here.
     *
     * <p>The mesh is read as loaded, or posed with no container step seated. Both crossings lean on
     * {@link EntityMesh#getFlattenedScale() the mesh's shared factor}, which a seated step - a
     * cubeless bone resting at one beside the mesh's own - takes away from a flattened mesh.
     *
     * @param model the mesh read, as loaded or posed with no container step seated
     * @param name the bone read, which the mesh declares
     * @param channel the channel read
     * @return the channel's value before any write, in the units the pose speaks
     * @throws RendererException if a scale channel is read on a bone resting at a scale other than
     *     zero under a scale of zero
     */
    public static float authored(
        @NotNull EntityMesh model, @NotNull String name, @NotNull PoseChannel channel) {

        EntityMesh.Bone bone = model.getBones().get(name);
        float flattened = model.getFlattenedScale();
        return switch (channel) {
            case X -> bone.getPivot().x() / flattened;
            case Y -> (bone.getPivot().y() - anchorShift(bone, flattened)) / flattened;
            case Z -> bone.getPivot().z() / flattened;
            case X_ROT -> bone.getRotation().pitchRadians();
            case Y_ROT -> bone.getRotation().yawRadians();
            case Z_ROT -> bone.getRotation().rollRadians();
            case X_SCALE, Y_SCALE, Z_SCALE -> fieldRest(model, name, bone);
        };
    }

    /**
     * The value a bone's own scale field rests at - its rest over
     * {@link EntityMesh#scaleAbove the scale above its part}, exactly one wherever the two are
     * equal, IEEE division being correctly rounded.
     *
     * <p>A bone resting at zero reads a field of zero rather than a division, so a pose that turns
     * it and scales nothing still hands it back: zero over zero is no number, and no number equals
     * itself, so the uniform fold would refuse the bone's own rest.
     *
     * @param model the mesh the bone belongs to
     * @param name the bone's name
     * @param bone the bone
     * @return the value the bone's own scale field rests at
     * @throws RendererException if the bone rests at a scale other than zero under a scale of zero
     */
    private static float fieldRest(
        @NotNull EntityMesh model, @NotNull String name, @NotNull EntityMesh.Bone bone) {

        float rest = bone.getScale();
        if (rest == 0f) return 0f;
        float above = model.scaleAbove(name);
        if (above == 0f)
            throw new RendererException(
                "entity pose: bone '%s' rests at '%s' under a scale of zero above it, over which no field exists",
                name, rest);
        return rest / above;
    }

    /**
     * The feet-anchor translate a bone's y pivot carries - the flattened mesh's shift on a
     * top-level bone, and zero on every bone below one or on a mesh flattened at nothing.
     */
    private static float anchorShift(@NotNull EntityMesh.Bone bone, float flattened) {
        return bone.getParent() == null ? EntityMesh.flattenedShift(flattened) : 0f;
    }

    /**
     * One bone where the pose leaves it, or the bone itself when the pose writes it no geometry.
     *
     * <p>The rotation is assembled as the Euler triplet the bone already carries, one channel at a
     * time, because that triplet is what a chain composition finally reads - a rotation pre-composed
     * as a matrix and multiplied in reaches {@code rotationZYX} through different arithmetic and
     * parts from the authored pose at a delta of zero.
     *
     * <p><b>A top-level pivot of a flattened mesh is two numbers, and a write there crosses both.</b>
     * The tooling pushes a whole-mesh scale onto the top-level bones as the feet-anchor translate as
     * well as the factor, so a position written on one lands at the factor times the value plus that
     * translate on y - the expansion the generator applied to the pivot - and a value written back to
     * what the bone reads keeps the mesh's own number. A container step stands above the root both
     * crossings ride, so the seat hands its steps here at a factor of one and each lands at the
     * number written.
     *
     * <p>A written scale never touches the bone's rest factor: it is vanilla's field, and it rides
     * the bone's pose scale as its ratio to the value that field rests at, through
     * {@link #posedRatio}, so it reaches the bone's descendants as vanilla's stack carries a part's
     * scale field. The seat hands a container step here at a field of one, which is where the root
     * rests wherever a step can carry a scale: the seat refuses a scale the pose writes on a step,
     * and a clip's on a mesh flattened at any factor but one.
     */
    private static @NotNull EntityMesh.Bone posedBone(
        @NotNull EntityMesh.Bone bone, @NotNull String name,
        @NotNull Map<PoseChannel, Float> written, float flattened, float fieldRest) {

        if (written.isEmpty()) return bone;

        Vector3f pivot = bone.getPivot();
        EulerRotation rotation = bone.getRotation();
        float shift = anchorShift(bone, flattened);
        Vector3f placed = new Vector3f(
            placed(written, PoseChannel.X, pivot.x(), flattened, 0f),
            placed(written, PoseChannel.Y, pivot.y(), flattened, shift),
            placed(written, PoseChannel.Z, pivot.z(), flattened, 0f));
        // Placed and turned through one copy that carries the pose scale over: what the pose and a
        // clip scale the bone by, and which selection draws it, were all settled before this copy,
        // and a positional rebuild is what would put any of them back at its default.
        return posedRatio(bone, name, written, fieldRest).withPose(
            placed,
            new EulerRotation(
                degrees(written, PoseChannel.X_ROT, rotation.pitch(), rotation.pitchRadians()),
                degrees(written, PoseChannel.Y_ROT, rotation.yaw(), rotation.yawRadians()),
                degrees(written, PoseChannel.Z_ROT, rotation.roll(), rotation.rollRadians())));
    }

    /**
     * The bone carrying the scale the pose writes as its ratio to the value the bone's own field
     * rests at, or the bone itself where the pose writes it no scale or exactly that rest.
     *
     * <p>A written scale is vanilla's field, the number {@code setupAnim} assigns the part, and the
     * field's rest is the bone's rest over the scale above the part, so their ratio is the field
     * vanilla's pose assigns over the one the part rests at. On the chain it goes on after the
     * bone's rotation, which is where {@code translateAndRotate} puts the field, so it reaches the
     * bone's own cubes on top of the rest factor they already carry and every descendant's cubes
     * and pivot through the stack - the part drawing at the written field times every scale above
     * it, as vanilla draws it.
     *
     * <p>The ratio is ASSIGNED rather than multiplied onto a pose scale the bone already holds, and
     * that is exact: {@link #posedScale} refuses a clip's scale beside any written one, and
     * {@link #seatUnderContainer} refuses a written step scale before it seats a step, so a bone
     * written a scale arrives here carrying no pose scale of its own.
     *
     * <p>A bone written no scale, or one equal to the field's rest, is handed back rather than
     * assigned a ratio of one, and that is load-bearing twice over. It keeps whatever pose scale the
     * bone arrived with - a clip's on a bone the pose also turns or places, and a clip's container
     * scale on the step the seat folds it onto - which a unit ratio would wipe. And the matrix the
     * chain composes is the one an unwritten bone composes, which is what keeps the happy ghast's
     * body, whose pose reads its own scale back, bit-identical to one no pose touched. That read and
     * this rest are one number because {@link #authored} answers the read through the same function
     * the rest here comes from.
     *
     * <p>The identity test runs before the zero test, so a bone resting at zero that the pose turns
     * or places and scales nothing is handed back like any other; only a scale written away from a
     * rest of zero refuses.
     *
     * @param bone the bone the pose writes
     * @param name the bone's name, which a refusal reports
     * @param written what the pose writes the bone
     * @param fieldRest the value the bone's own scale field rests at
     * @return the bone carrying the written ratio, or the bone itself where the pose writes it no
     *     scale or exactly the field's rest
     * @throws RendererException if the three axes do not agree, or the pose scales a bone resting
     *     at zero, over which no ratio exists
     */
    private static @NotNull EntityMesh.Bone posedRatio(
        @NotNull EntityMesh.Bone bone, @NotNull String name, @NotNull Map<PoseChannel, Float> written,
        float fieldRest) {

        float scale = scale(written, name, fieldRest);
        if (scale == fieldRest) return bone;
        if (fieldRest == 0f)
            throw new RendererException(
                "entity pose: bone '%s' is scaled to '%s' from a rest of zero, which no ratio can carry",
                name, scale);

        float ratio = scale / fieldRest;
        return bone.withPoseScale(new Vector3f(ratio, ratio, ratio));
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
     * mesh flattened at {@link EntityMesh#getFlattenedScale() one factor} does not store a pivot
     * in those, every one below the dissolved root having arrived multiplied by it, so the written
     * value crosses the same way - which is what places an elder guardian's spikes where a subject
     * 2.35 times the size wears them rather than at a plain guardian's reach.
     *
     * <p>A top-level pivot's y carries the feet-anchor translate beside the factor, so the crossing
     * takes it back off before dividing and puts it back after multiplying; every other component
     * crosses the factor alone, and a zero shift is never added, so a signed zero keeps its sign.
     *
     * <p>A value written back to what the mesh already held keeps the mesh's own number rather than
     * the one a divide and a multiply land on, for the reason {@link #degrees} keeps the authored
     * degrees.
     */
    private static float placed(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel,
        float authored, float flattened, float shift) {

        Float value = written.get(channel);
        if (value == null) return authored;
        if (flattened == 1f) return value;
        if (value == (authored - shift) / flattened) return authored;
        return shift == 0f ? value * flattened : value * flattened + shift;
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
     * The one uniform scale the three axes the table writes fold onto, as vanilla's field - an axis
     * the pose leaves unwritten holding the value the field rests at.
     *
     * <p>Per-axis written scale is not a shape the corpus needs: the single model that scales
     * writes one expression to all three axes, so folding them is exact. A divergence refuses
     * rather than folding, because no one value is all three axes and any one picked would draw
     * the bone at a size the pose never wrote. That is worth failing over rather than picking an
     * axis to believe.
     *
     * <p>No custom style reaches the refusal: an install refuses a raw scale written on fewer than
     * three axes or with a graph per axis, where the script is read. It guards the shipped table,
     * which a hand-built or regenerated one can still reach.
     *
     * @param written what the pose writes the bone
     * @param bone the bone's name, which a refusal reports
     * @param fieldRest the value the bone's own scale field rests at
     * @return the one scale the three axes agree on
     * @throws RendererException if the three axes do not agree
     */
    private static float scale(
        @NotNull Map<PoseChannel, Float> written, @NotNull String bone, float fieldRest) {

        float x = held(written, PoseChannel.X_SCALE, fieldRest);
        float y = held(written, PoseChannel.Y_SCALE, fieldRest);
        float z = held(written, PoseChannel.Z_SCALE, fieldRest);
        if (x != y || y != z)
            throw new RendererException(
                "entity pose: bone '%s' scales to (%s, %s, %s), which one uniform bone scale cannot hold",
                bone, x, y, z);
        return x;
    }

    /**
     * Seats every top-level bone under the container the pose writes and its clips displace.
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
     * <p><b>A step is placed at the number the pose wrote, whatever the mesh is flattened at.</b> A
     * whole-mesh scale rides vanilla's root, below the renderer's steps and the ground frame, and the
     * tooling dissolved it into the top-level bones - the factor onto each of them and the feet anchor
     * onto their pivots - so the seat stands where vanilla's pose stack stands above that root and
     * neither crossing reaches it. A model writing its own root's position on a scaled mesh would
     * replace the anchor that root carries, which a raw step does not take off; no shipped model
     * writes its root on a mesh flattened at any factor but one.
     *
     * <p><b>What a clip scales the container by rides the innermost step's own pose scale</b>, as
     * one plus the displacement on each axis - what {@code offsetScale}'s {@code +=} leaves on a
     * root reset to one - through {@link #posedScale} at a field of one, the method a bone's clip
     * scale takes. The chain puts a pose scale on after the step's rotation and skips it where every
     * axis stands at one, which is {@code translateAndRotate}'s order and its skip, so it reaches
     * every bone below the step, and a zero displacement hands the step back untouched.
     *
     * <p>Two shapes refuse, and no shipped subject reaches either. A scale the POSE writes on a step
     * assigns the root's own field, which on a mesh flattened at a factor other than one holds that
     * factor inside the feet-anchor translate the seat stands above, so a ratio on the step would
     * multiply the factor rather than replace it and scale the anchor with it; no shipped model
     * writes a container scale, so the step refuses one on every mesh rather than drawing it on
     * the meshes flattened at nothing alone. A clip's scale on a mesh flattened at a factor other
     * than one is vanilla's root scaling by that factor plus the displacement, inside the same
     * anchor, so a scale here would multiply the factor rather than add to it and scale the anchor
     * with it. That one refuses by value rather than by channel, a zero being exact at any factor.
     * A clip's container rotation on such a mesh is the same shape and is drawn rather than
     * refused, turning about the step's pivot above the anchor; no flattened mesh plays one.
     *
     * @param bones the posed bones, in the mesh's own order, which the steps are appended to
     * @param steps the steps to seat, outermost first
     * @param displaced what the clips displace the container by, read here for its scale alone
     * @param flattened the mesh's whole-mesh factor, {@code 1f} where it has none
     * @throws RendererException if the pose writes the container a scale, or a clip scales the
     *     container on a mesh flattened at a factor other than one
     */
    private static void seatUnderContainer(
        @NotNull LinkedHashMap<String, EntityMesh.Bone> bones,
        @NotNull List<Map<PoseChannel, Float>> steps,
        @NotNull Map<PoseChannel, Float> displaced, float flattened) {

        for (Map<PoseChannel, Float> written : steps)
            for (PoseChannel channel : written.keySet())
                if (channel.kind() == PoseChannel.Kind.SCALE)
                    throw new RendererException(
                        "entity pose: the container writes '%s', a root scale a flattened mesh holds inside the feet anchor the seat stands above and no shipped model writes",
                        channel.token());

        float x = displaced.getOrDefault(PoseChannel.X_SCALE, 0f);
        float y = displaced.getOrDefault(PoseChannel.Y_SCALE, 0f);
        float z = displaced.getOrDefault(PoseChannel.Z_SCALE, 0f);
        if (flattened != 1f && (x != 0f || y != 0f || z != 0f))
            throw new RendererException(
                "entity pose: a clip scales the container by (%s, %s, %s) on a mesh flattened at '%s', whose root scales inside the feet anchor",
                x, y, z, flattened);

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
        // draws so no drawing order changes. A step stands above the root the flattening dissolved,
        // where neither the factor nor the feet anchor reaches, so it is placed at the number the pose
        // wrote. The innermost alone carries a clip's scale, being the step the clips fold onto.
        for (int step = 0; step < steps.size(); step++) {
            EntityMesh.Bone rest = step == steps.size() - 1
                ? posedScale(new EntityMesh.Bone(), names.get(step), steps.get(step), displaced, 1f)
                : new EntityMesh.Bone();
            EntityMesh.Bone seated = posedBone(rest, names.get(step), steps.get(step), 1f, 1f);
            bones.put(names.get(step),
                step == 0 ? seated : reparented(seated, names.get(step - 1)));
        }
    }

    /** One bone hung off a different parent, everything else about it untouched. */
    private static @NotNull EntityMesh.Bone reparented(
        @NotNull EntityMesh.Bone bone, @NotNull String parent) {

        return bone.withParent(parent);
    }

    /** Whether a bone hangs from the root, by the same three tests the chain composition applies. */
    private static boolean isTopLevel(
        @NotNull Map<String, EntityMesh.Bone> bones, @NotNull String name,
        @NotNull EntityMesh.Bone bone) {

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
