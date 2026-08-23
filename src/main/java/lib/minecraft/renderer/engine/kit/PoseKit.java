package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mesh a subject's own model leaves it holding at one instant - {@link PoseEvaluator}'s channel
 * values written back onto the bones they name.
 *
 * <p><b>Under {@link EntityOptions.PoseMode#BIND} this hands back the very instance it was given</b>,
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
 * <p><b>The two flag channels are applied per frame, by the same rule the resting strip applies at
 * load</b> - a bone the pose hides takes its whole subtree with it, and one it skips loses its own
 * cubes while its descendants keep drawing. What the strip does at load this does at each instant,
 * so a model that hides a bone on a frame rather than at rest is drawn hidden on that frame.
 *
 * <p>No flag in the shipped corpus reads elapsed time, so today every frame agrees with the strip
 * and this changes no subject. That is measured rather than assumed, and it is what says the two
 * halves cannot disagree while it holds - a model that starts reading a clock for a flag is what
 * would part them.
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
     * The mesh this subject's model leaves it holding at one tick.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param subject the resolved subject, supplying the mesh, the pose and what it rests at
     * @param tick the frame's sample tick
     * @return the posed mesh, or the subject's own mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick) {
        return posed(mode, subject.pose(), subject.restingState(), subject.model(), tick);
    }

    /**
     * One mesh where the pose that belongs to it leaves it at one tick.
     *
     * <p>Held apart from the subject because a subject is more than one posed mesh: each overlay pass
     * poses its own with its own model class, and what they share is the wearer's resting state
     * rather than a pose.
     *
     * @param mode the authored pose, or the one its model evaluates at the tick under a gait
     * @param pose the pose belonging to this mesh
     * @param restingState which constant each enum render-state member rests at, by member name
     * @param model the mesh to pose
     * @param tick the frame's sample tick
     * @return the posed mesh, or the given mesh itself where nothing poses it
     */
    public static @NotNull EntityModelData posed(
        @NotNull EntityOptions.PoseMode mode, @NotNull EntityPose pose,
        @NotNull Map<String, String> restingState, @NotNull EntityModelData model, int tick) {

        if (mode == EntityOptions.PoseMode.BIND) return model;
        if (!pose.isReadable()) return model;

        PoseEvaluator.Frame frame = frameAt(mode, pose, restingState, tick);
        PoseEvaluator.ChannelWrites writes = PoseEvaluator.evaluate(pose, model, frame);
        // The clips a model plays are applied ON TOP of what its body assigned, because vanilla's
        // three offset members all add to the value already there. So the two are resolved apart and
        // composed here rather than merged into one write set, which is also what keeps the replace
        // rule and the add rule from having to be told apart per channel further down.
        Map<String, Map<PoseChannel, Float>> displaced = ClipKit.deltas(pose, model, frame);
        if (writes.isEmpty() && displaced.isEmpty()) return model;
        return rebuild(model, writes, displaced);
    }

    /**
     * The whole subject as it stands at one tick - its own mesh posed, and every overlay pass's mesh
     * posed by the model class that pass belongs to.
     *
     * <p>An overlay carries geometry of its own and a pose of its own, so posing the body alone
     * leaves a sheep's wool where the sheep no longer is. What they share is the resting state, the
     * subject being one animal however many passes draw it.
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

        EntityModelData model = posed(mode, subject, tick);
        List<Entity.OverlayLayer> overlays = posedOverlays(mode, subject, tick);
        if (model == subject.model() && overlays == subject.overlays()) return subject;
        return subject.mutate().model(model).overlays(overlays).build();
    }

    /** Each overlay pass where its own model leaves it, or the list itself when none of them moved. */
    private static @NotNull List<Entity.OverlayLayer> posedOverlays(
        @NotNull EntityOptions.PoseMode mode, @NotNull Entity subject, int tick) {

        List<Entity.OverlayLayer> overlays = subject.overlays();
        List<Entity.OverlayLayer> out = new ArrayList<>(overlays.size());
        boolean moved = false;
        for (Entity.OverlayLayer overlay : overlays) {
            EntityModelData mesh =
                posed(mode, overlay.pose(), subject.restingState(), overlay.model(), tick);
            // The suppressed-pass alternate is the same mesh with a subtree emptied, so it takes the
            // same pose - a villager under a full-hat profession still moves the head it draws none of.
            Optional<EntityModelData> noHat = overlay.noHatModel()
                .map(alternate -> posed(mode, overlay.pose(), subject.restingState(), alternate, tick));
            moved |= mesh != overlay.model()
                || !noHat.equals(overlay.noHatModel());
            out.add(new Entity.OverlayLayer(mesh, overlay.textureRef(), overlay.pass(),
                overlay.tintArgb(), overlay.skipBounds(), overlay.tintBy(), overlay.textureBy(),
                overlay.gate(), noHat, overlay.pose()));
        }
        return moved ? List.copyOf(out) : overlays;
    }

    // ------------------------------------------------------------------------------------

    /**
     * What the subject answers about itself at one tick - what it rests at, with the figures this
     * preset drives run forward.
     *
     * <p>A subject an offline render poses is standing where it is, so every figure but elapsed age
     * is the figure it rests at: it is walking at no speed, swinging at nothing and holding nothing.
     * Elapsed age is the exception and the reason a frame differs from its neighbour at all.
     *
     * <p>A gait names the further figures that stop resting, and nothing else about it differs.
     * {@link EntityOptions.PoseMode#WALK} answers the two a stride is carried on: vanilla steps the
     * phase by the amplitude once a tick rather than deriving it from the clock, so the phase is the
     * tick times the amplitude and the two are one schedule.
     *
     * <p>Delegating the rest to {@link PoseEvaluator#restingIn} rather than answering it here is
     * what keeps a humanoid's arms off NaN - {@code speedValue} is divided by and is built at one.
     */
    private static @NotNull PoseEvaluator.Frame frameAt(
        @NotNull EntityOptions.PoseMode mode, @NotNull EntityPose pose,
        @NotNull Map<String, String> restingState, int tick) {

        PoseEvaluator.Frame rest = PoseEvaluator.restingIn(pose, restingState);
        boolean walking = mode == EntityOptions.PoseMode.WALK;
        return new PoseEvaluator.Frame() {
            @Override
            public float input(@NotNull String field) {
                if (AGE_IN_TICKS.equals(field)) return tick;
                if (!walking) return rest.input(field);
                if (WALK_SPEED.equals(field)) return WALK_AMPLITUDE;
                if (WALK_POSITION.equals(field)) return tick * WALK_AMPLITUDE;
                return rest.input(field);
            }

            @Override
            public float carried(@NotNull String field) {
                return rest.carried(field);
            }

            @Override
            public float question(@NotNull String receiver, @NotNull String question) {
                return rest.question(receiver, question);
            }

            @Override
            public float element(@NotNull String receiver, int index) {
                return rest.element(receiver, index);
            }

            @Override
            public boolean is(@NotNull String member, @NotNull String constant) {
                return rest.is(member, constant);
            }

            @Override
            public boolean has(@NotNull String member) {
                return rest.has(member);
            }
        };
    }

    /** The mesh with every written channel applied, or the mesh itself when none of them moved it. */
    private static @NotNull EntityModelData rebuild(
        @NotNull EntityModelData model, @NotNull PoseEvaluator.ChannelWrites writes,
        @NotNull Map<String, Map<PoseChannel, Float>> displaced) {

        Set<String> undrawn = hidden(model, writes.bones());
        if (writes.container().isEmpty() && undrawn.isEmpty() && displaced.isEmpty()
            && writes.bones().values().stream().noneMatch(PoseKit::changesBone)) return model;

        // Read from the mesh being posed rather than from the one being built: what a pose and a clip
        // assign is in the model's own units, and this is the factor that puts one of those in this
        // mesh - a fact about the mesh as the tooling flattened it.
        float flattened = model.getFlattenedScale();
        // Collected into a LinkedHashMap rather than through Map.copyOf: the mesh's own bone order is
        // the tied-depth priority, and copyOf salts its iteration per JVM launch.
        LinkedHashMap<String, EntityModelData.Bone> bones = model.getBones().entrySet().stream()
            .filter(bone -> !undrawn.contains(bone.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey,
                bone -> displacedBone(bone.getValue(), bone.getKey(),
                    writes.bones().getOrDefault(bone.getKey(), Map.of()),
                    displaced.getOrDefault(bone.getKey(), Map.of()), flattened),
                (first, second) -> first, LinkedHashMap::new));
        if (!writes.container().isEmpty()) seatUnderContainer(bones, writes.container(), flattened);
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * The bones this frame draws none of - each one the pose hides, and everything hanging below it.
     *
     * <p>Vanilla's {@code visible = false} skips the part and its descendants in one act, so removing
     * the name alone re-parents each orphan onto the root and lands geometry that should have
     * vanished somewhere the subject is not.
     *
     * <p>The channel carries a boolean in a float, so it is read against zero as a NUMBER rather than
     * compared as a value - a negative zero is a bone that does not draw, and equality would call it
     * a different number and draw it.
     *
     * @param model the mesh being posed
     * @param written what each bone's channels evaluated to
     * @return every bone name this frame draws nothing of
     */
    private static @NotNull Set<String> hidden(
        @NotNull EntityModelData model, @NotNull Map<String, Map<PoseChannel, Float>> written) {

        Set<String> undrawn = model.getBones().keySet().stream()
            .filter(bone -> flagClear(written.getOrDefault(bone, Map.of()), PoseChannel.VISIBLE))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return undrawn.isEmpty() ? undrawn : withDescendants(model.getBones(), undrawn);
    }

    /** Whether a flag channel was written, and written to something other than zero. */
    private static boolean flagSet(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel) {

        Float value = written.get(channel);
        return value != null && value != 0f;
    }

    /** Whether a flag channel was written, and written to zero. */
    private static boolean flagClear(
        @NotNull Map<PoseChannel, Float> written, @NotNull PoseChannel channel) {

        Float value = written.get(channel);
        return value != null && value == 0f;
    }

    /**
     * Closes a set of bones downwards over the bone forest, so what it holds is whole subtrees.
     *
     * <p>A fixpoint rather than one pass, because a bone map is in the tooling's own
     * {@code addOrReplaceChild} order and a child can precede its parent in it.
     *
     * @param bones the mesh's bones keyed by name
     * @param seed the bones to close over, which is left untouched
     * @return the seed and every bone reached from it through a parent chain
     */
    public static @NotNull Set<String> withDescendants(
        @NotNull Map<String, EntityModelData.Bone> bones, @NotNull Set<String> seed) {

        Set<String> closed = new LinkedHashSet<>(seed);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, EntityModelData.Bone> bone : bones.entrySet()) {
                if (closed.contains(bone.getKey())) continue;
                String parent = bone.getValue().getParent();
                if (parent != null && closed.contains(parent)) {
                    closed.add(bone.getKey());
                    grew = true;
                }
            }
        }
        return closed;
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

        return new EntityModelData.Bone(bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
            bone.getScale(), bone.getCubes(), bone.getParent(), new Vector3f(1f + x, 1f + y, 1f + z));
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
            case VISIBLE -> 1f;
            case SKIP_DRAW -> 0f;
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

        if (!changesBone(written)) return bone;

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
        return new EntityModelData.Bone(
            placed,
            new EulerRotation(
                degrees(written, PoseChannel.X_ROT, rotation.pitch(), rotation.pitchRadians()),
                degrees(written, PoseChannel.Y_ROT, rotation.yaw(), rotation.yawRadians()),
                degrees(written, PoseChannel.Z_ROT, rotation.roll(), rotation.rollRadians())),
            bone.getBindPoseRotation(),
            scale(written, name, bone.getScale()),
            skipsDraw(written) ? Concurrent.newList() : bone.getCubes(),
            bone.getParent(),
            // Carried rather than defaulted: what a clip scales the bone by was settled before this
            // ran, and the six-argument form would put it back at rest.
            bone.getPoseScale());
    }

    /**
     * Whether anything here reaches the bone itself, so a bone the pose leaves alone - and one it
     * only hides, which is answered by dropping it rather than by rewriting it - stays the instance
     * it already was.
     */
    private static boolean changesBone(@NotNull Map<PoseChannel, Float> written) {
        return movesGeometry(written) || skipsDraw(written);
    }

    /** Whether any channel here is one a bone's pivot, rotation or scale carries. */
    private static boolean movesGeometry(@NotNull Map<PoseChannel, Float> written) {
        return written.keySet().stream().anyMatch(channel -> !channel.isFlag());
    }

    /**
     * Whether this frame draws the bone's own cubes.
     *
     * <p>The narrower of the two flags: where a hidden bone takes its subtree, a skipped one keeps
     * every descendant drawing and loses only what it owns.
     */
    private static boolean skipsDraw(@NotNull Map<PoseChannel, Float> written) {
        return flagSet(written, PoseChannel.SKIP_DRAW);
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
                if (channel.isFlag() || channel.kind() == PoseChannel.Kind.SCALE)
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

        return new EntityModelData.Bone(bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
            bone.getScale(), bone.getCubes(), parent);
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
