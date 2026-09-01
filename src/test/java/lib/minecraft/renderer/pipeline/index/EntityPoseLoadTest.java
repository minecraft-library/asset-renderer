package lib.minecraft.renderer.pipeline.index;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.IdleFigure;
import lib.minecraft.renderer.asset.pose.IdleState;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table read back through the loader.
 *
 * <p>Five things are worth pinning and nothing else here is. The expression grammar has to survive
 * the round trip operand for operand, because a table that decodes into a DIFFERENT tree is a table
 * that reads perfectly and animates wrongly. A container has to arrive as a container rather than as
 * a bone, because it is the parent transform every bone the mesh names at top level hangs off and a
 * bone of that name is one nothing draws. A refusal has to arrive as a refusal, because a model whose
 * pose could not be read renders exactly like one that poses nothing - pinned from a written table
 * rather than from a shipped model, every model in the corpus being readable. The pose has to follow
 * the mesh across the age fork, because a baby is its own model class - a turtle poses a bone only
 * its adult mesh has and a donkey poses its head off a pitch only its foal assigns, so one pose per
 * entity would be wrong for one of them whichever way it was chosen. And a sub-expression the table
 * names once has to arrive once, because a reader that rebuilt one per reference would turn a
 * humanoid's nine hundred nodes back into the twenty-two million the table exists not to write.
 */
@DisplayName("the shipped pose table")
class EntityPoseLoadTest {

    /** Plain, because the reader is declared on the type it reads rather than configured onto one. */
    private static final @NotNull Gson GSON = new Gson();

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("a pufferfish fin decodes to the arithmetic the table spells")
    void anExpressionSurvivesTheRoundTrip() {
        // Written out whole rather than sampled, because every near miss is well formed: operands in
        // the other order, the widening dropped, the sampled sine read as the libm one. Each decodes
        // without complaint and each moves the fin.
        PoseExpr wave = op(PoseOperator.MTH_SIN,
            op(PoseOperator.F2D, op(PoseOperator.MUL, new PoseExpr.Input("ageInTicks"), constant(0.2f))));

        assertEquals(
            op(PoseOperator.ADD, constant(-0.2f), op(PoseOperator.MUL, constant(0.4f), wave)),
            channel("minecraft:pufferfish", "right_blue_fin", PoseChannel.Z_ROT),
            "the right fin leans out of a sampled sine of the age");
    }

    @Test
    @DisplayName("a model whose pose could not be read says so rather than posing nothing")
    void aRefusalArrivesAsARefusal() {
        // Written here rather than taken off a shipped model, because every model in the corpus is
        // readable. The arm stays live all the same: a version bump is exactly the event that puts a
        // body in front of the walk it cannot phrase, and the table has to be able to say so.
        EntityPose refused = GSON.fromJson("""
            {"poses": {"SomeModel": {"refused": "poses off something this walk cannot phrase"}}}""",
            RawEntityPosesFile.class).poses().get("SomeModel");

        assertFalse(refused.isReadable(), "a pose carrying a reason is not a readable one");
        assertEquals("poses off something this walk cannot phrase", refused.refusal().orElseThrow(),
            "and it carries what stopped the walk verbatim");
        assertEquals(List.of(), List.copyOf(refused.bones().keySet()),
            "a refusal poses nothing, which is how it renders and why it has to be distinguishable");
        assertEquals(List.of(), refused.container(), "and it poses no container either");
    }

    @Test
    @DisplayName("a container arrives above the bones rather than as one of them")
    void aContainerArrivesAsAContainer() {
        // Two models pose the container their mesh was built around, and neither mesh names a bone
        // for it - so a reader that filed it under `bones` would be posing a bone nothing draws and
        // leaving the thing that carries the model where it is drawn unposed.
        //
        // The dragon is the one that could not be answered any other way. Its container turns as
        // well as moving, and a rotation reaches each child's POSITION about the container's pivot
        // as well as composing with the child's own rotation, so there is no per-bone term for it.
        EntityPose dragon = pose("minecraft:ender_dragon");
        assertTrue(dragon.isReadable(), "the ender dragon has a readable pose");
        assertEquals(1, dragon.container().size(),
            "a body assigns the root's fields once, so the sequence it composes is one step long");
        assertEquals(Set.of(PoseChannel.Y, PoseChannel.Z, PoseChannel.X_ROT),
            dragon.container().getFirst().keySet(), "its container is placed twice and turned once");
        assertFalse(dragon.bones().containsKey("<mesh root>"),
            "and the container is nowhere among the bones, under that spelling or any other");

        // A turtle moves the container it hangs off, and what the channel holds is a number rather
        // than a read of a bone: a flattened container has no authored pose left to read, the mesh
        // having put whatever it held into the bones below it.
        //
        // The number is where the turtle rests. It drops by one while carrying an egg, and an
        // offline turtle carries none - so the branch that would have said so is resolved before the
        // row ships and what arrives is the arm a turtle standing there takes.
        EntityPose turtle = pose("minecraft:turtle");
        assertEquals(1, turtle.container().size(), "and the turtle's is one step too");
        assertEquals(Set.of(PoseChannel.Y), turtle.container().getFirst().keySet(),
            "the turtle moves its container once");
        assertEquals(constant(0f), turtle.container().getFirst().get(PoseChannel.Y),
            "and it rests at zero, there being no egg to carry");
    }

    @Test
    @DisplayName("a shulker's 180 arrives in the mesh, and nothing at load or render adds it")
    void aFacingYawArrivesInTheMesh() {
        // The shulker's setupRotations folds a 180 into the body rotation it delegates, and the base
        // applies that rotation as the subject's FACING - which reaches every render mode, BIND
        // included, where a container step never could. So it is baked into the mesh at generation
        // and nothing here recovers it: no member states it, the renderers row states nothing, and
        // the pose container is empty.
        //
        // It sits in the CUBE's slot rather than the bone's because a pose REPLACES a bone channel
        // it writes, and this pose writes y_rot on two of these three bones. A turn in the bone slot
        // would survive on 'base' and be discarded on 'head' and 'lid'.
        Entity shulker = entities.get("minecraft:shulker");
        assertNotNull(shulker, "the corpus ships a shulker");
        assertEquals(List.of(), shulker.pose().container(),
            "the facing is in the mesh, so nothing reaches the container");

        shulker.model().getBones().forEach((name, bone) -> {
            assertEquals(EulerRotation.NONE, bone.getRotation(),
                name + " keeps its bone slot free for the pose to write");
            bone.getCubes().forEach(cube -> {
                assertEquals(180f, cube.getRotation().yaw(), name + " carries the facing in its cube");
                assertEquals(0f, cube.getRotation().pitch(), name + " turns about y alone");
                assertEquals(0f, cube.getRotation().roll(), name + " turns about y alone");
                assertEquals(Vector3f.ZERO, cube.getPivot(),
                    name + " turns about the bone's own origin, which is where the subject's axis passes");
            });
        });
    }

    @Test
    @DisplayName("the pose follows the mesh across the age fork, in both directions")
    void thePoseSwapsWithTheBabyMesh() {
        // The two directions are the whole point. Both ages of both animals pose, and each poses
        // something the other does not, so a single pose per entity is wrong for one of them
        // whichever way it is chosen - and wrong silently, because the bone names an adult and a
        // baby share are the ones a wrong pose would animate.
        //
        // A turtle carries its egg on a belly bone only the adult mesh has, and only the adult's
        // pose touches it. A baby handed the adult's pose would be posing a bone its own mesh does
        // not declare.
        assertTrue(pose("minecraft:turtle").isReadable(), "the adult turtle has a readable pose");
        assertTrue(babyPose("minecraft:turtle").isReadable(), "and so does the baby");
        assertTrue(pose("minecraft:turtle").bones().containsKey("egg_belly"),
            "the adult turtle poses the belly it carries an egg on");
        assertFalse(babyPose("minecraft:turtle").bones().containsKey("egg_belly"),
            "the baby has no such bone and poses none");

        // Both donkeys pose, so their direction is read off what they pose WITH. A foal assigns its
        // own head pitch and reads that back, where the adult takes the pitch the render state
        // carries - so a foal handed the adult's pose would look wherever the animal is looking.
        //
        // Asserted as the two heads holding different expressions rather than by naming the figure
        // the adult reads. An offline subject looks straight ahead, so the pitch it is handed is
        // resolved into the row before it ships and the adult's head arrives as the angle that pitch
        // works out to; the foal's arrives as the read of its own bone, which nothing resolves. What
        // survives either spelling is that the two are not the same pose, which is the whole claim.
        EntityPose donkey = pose("minecraft:donkey");
        EntityPose foal = babyPose("minecraft:donkey");
        assertTrue(donkey.isReadable(), "the adult donkey has a readable pose");
        assertTrue(foal.isReadable(), "and so does the foal");
        assertNotEquals(donkey.bones().get("head_parts"), foal.bones().get("head_parts"),
            "and the two heads are posed differently, each by its own mesh's model");
    }

    @Test
    @DisplayName("a shared sub-expression arrives as one object, not one copy per place naming it")
    void sharingSurvivesTheRoundTrip() {
        // A humanoid's arms are a graph rather than a tree: nine hundred sub-expressions standing for
        // twenty-two million, because a walk that follows both arms of everything it cannot decide
        // reaches the same arithmetic down enormously many paths. The table says so once, and a
        // reader that answered each reference with a fresh record would put the tree back - which
        // does not merely cost memory, it is the size that could not be written down in the first
        // place. So this counts the edges: a tree of n nodes has n-1 of them and no more.
        EntityPose zombie = pose("minecraft:zombie");
        assertTrue(zombie.isReadable(), "a zombie's pose is readable");

        Map<Object, Integer> reached = new IdentityHashMap<>();
        zombie.bones().values().forEach(channels -> channels.values().forEach(expr -> edges(expr, reached)));

        assertFalse(reached.isEmpty(), "a zombie poses something");
        assertTrue(reached.values().stream().anyMatch(count -> count > 1),
            "at least one sub-expression is reached from more than one place, which a tree cannot be");
        assertTrue(reached.size() < 4000,
            "and the whole pose stays the size the table spells it at, not the size it stands for");
    }

    /** Every node an expression reaches, counted once per place that names it. */
    private static void edges(@NotNull PoseExpr expr, @NotNull Map<Object, Integer> reached) {
        if (reached.merge(expr, 1, Integer::sum) > 1) return;
        switch (expr) {
            case PoseExpr.Op op -> op.operands().forEach(operand -> edges(operand, reached));
            case PoseExpr.Select select -> {
                edges(select.condition(), reached);
                edges(select.whenTrue(), reached);
                edges(select.whenFalse(), reached);
            }
            default -> { /* a leaf reaches nothing */ }
        }
    }

    private static void edges(@NotNull PosePredicate predicate, @NotNull Map<Object, Integer> reached) {
        if (reached.merge(predicate, 1, Integer::sum) > 1) return;
        edges(predicate.left(), reached);
        edges(predicate.right(), reached);
    }

    @Test
    @DisplayName("a model that genuinely poses nothing is not a refusal")
    void anEmptyPoseIsNotARefusal() {
        // A slime declares no typed setupAnim anywhere in its chain, so what it inherits is the reset
        // and it really does hold still. Reading that as a failure would put it in the same bucket as
        // the nine the walk could not finish.
        EntityPose slime = pose("minecraft:slime");
        assertTrue(slime.isReadable(), "a slime's pose is readable");
        assertEquals(List.of(), List.copyOf(slime.bones().keySet()), "and it is empty");
    }

    @Test
    @DisplayName("a clip play site carries the rate and amplitude the model plays it at")
    void aClipSiteKeepsItsConstants() {
        // The clip table already says which clip and under what drive. What only the play site knows
        // is how fast and how far, which is why it is recorded beside the pose rather than left to
        // the clip: two models playing one clip at two rates are otherwise indistinguishable.
        List<EntityPose.Clip> walked = pose("minecraft:frog").clips().stream()
            .filter(clip -> clip.drive() == MotionSource.STRIDE).toList();
        assertFalse(walked.isEmpty(), "a frog drives clips off its walk");
        walked.forEach(clip -> assertEquals(4, clip.arguments().size(),
            clip.coordinate() + " is driven by four arguments"));
    }

    @Test
    @DisplayName("every state-driven play site names the state its gate reads")
    void aStateSiteNamesItsGate() {
        // Which state a clip sits behind is the only thing saying WHICH of a model's several is
        // running, and a site that named none would answer zero at render and never play - which
        // reads exactly like the subject nothing has ticked, so nothing downstream would notice.
        // The loader refuses one; this is that refusal exercised over the whole shipped corpus
        // rather than over a hand-built row.
        //
        // A named state OUTSIDE the runtime's own roster is not a defect and is deliberately not
        // asserted against: it answers zero, which is the state nobody selected, and that is what a
        // subject standing still holds every state it does not play at.
        Map<String, List<String>> unnamed = new TreeMap<>();
        for (Map.Entry<String, Entity> subject : entities.entrySet()) {
            EntityPose pose = subject.getValue().pose();
            if (!pose.isReadable()) continue;
            List<String> bare = pose.clips().stream()
                .filter(clip -> clip.drive() == MotionSource.SELECT)
                .filter(clip -> clip.field().isEmpty())
                .map(EntityPose.Clip::coordinate)
                .toList();
            if (!bare.isEmpty()) unnamed.put(subject.getKey(), bare);
        }
        assertEquals(Map.of(), unnamed,
            "a state-driven site naming no state is one nothing can ever start, and it is silent");
    }

    @Test
    @DisplayName("a shipped pose names no figure the runtime cannot answer")
    void nothingButTheTickIsLeftToAnswer() {
        // What the reader rests on. Everything a subject standing still answers about itself - which
        // constant an enum member holds, what a question of a reference the state holds rests at,
        // what a figure its own render state builds it at - is resolved where the table is written,
        // so a channel is either a number or a function of the figures a caller drives. A row that
        // named anything else would be a question nothing offline can answer, and it would answer
        // zero in silence: the arm a switch ends at is not the arm a subject stands in, and it cost
        // the skeleton family a forty-four degree forward swing at rest before it was resolved.
        //
        // Taken from the roster the RUNTIME answers rather than typed out, so a table that ships a
        // figure PoseKit cannot answer fails here rather than rendering it as a silent zero.
        Set<String> driven = Stream.of(
                Stream.of("ageInTicks", "walkAnimationPos", "walkAnimationSpeed"),
                Stream.of(IdleFigure.values()).map(IdleFigure::field),
                Stream.of(IdleState.values()).map(IdleState::field))
            .flatMap(figures -> figures)
            .collect(Collectors.toSet());
        Map<String, Set<String>> named = new TreeMap<>();
        for (Map.Entry<String, Entity> subject : entities.entrySet()) {
            EntityPose pose = subject.getValue().pose();
            if (!pose.isReadable()) continue;
            Set<String> reads = new TreeSet<>();
            Map<Object, Boolean> walked = new IdentityHashMap<>();
            pose.bones().values().forEach(channels ->
                channels.values().forEach(expr -> figures(expr, reads, walked)));
            pose.container().forEach(step -> step.values().forEach(expr -> figures(expr, reads, walked)));
            pose.clips().forEach(clip -> clip.arguments().forEach(expr -> figures(expr, reads, walked)));
            reads.removeAll(driven);
            if (!reads.isEmpty()) named.put(subject.getKey(), reads);
        }
        assertEquals(Map.of(), named,
            "a figure no caller drives is one nothing offline answers, and it answers zero silently");
    }

    /** Every render-state figure one expression reads, walked once per node rather than once per path. */
    private static void figures(
        @NotNull PoseExpr expr, @NotNull Set<String> reads, @NotNull Map<Object, Boolean> walked) {

        if (walked.put(expr, Boolean.TRUE) != null) return;
        switch (expr) {
            case PoseExpr.Input input -> reads.add(input.field());
            case PoseExpr.Op op -> op.operands().forEach(operand -> figures(operand, reads, walked));
            case PoseExpr.Select select -> {
                figures(select.whenTrue(), reads, walked);
                figures(select.whenFalse(), reads, walked);
                figures(select.condition().left(), reads, walked);
                figures(select.condition().right(), reads, walked);
            }
            default -> { /* a literal and a bone read name no figure */ }
        }
    }

    @Test
    @DisplayName("a body naming a pose key of its own resolves it, and two frames are two subjects")
    void aBodyNamingItsOwnPoseKeyResolvesIt() {
        // A key a row declares and the table does not carry answers the empty pose in SILENCE: its
        // refusal is empty, so it reads as readable, and the mesh draws unposed and unstripped with
        // nothing said. So the one class posing two ways is pinned on both halves - each key
        // resolves a pose, and the frames are told apart where the split's whole difference now
        // lives, on the undrawn lists the fold resolved per frame: an evoker rests with its arms
        // crossed where a pillager's hang, which is the crossed-arms bone drawing for one and the
        // hanging pair for the other.
        assertTrue(pose("minecraft:evoker").isReadable(), "an evoker takes a pose");
        assertTrue(pose("minecraft:pillager").isReadable(), "and a pillager takes one too");
        Entity evoker = entities.get("minecraft:evoker");
        Entity pillager = entities.get("minecraft:pillager");
        assertTrue(evoker.model().getBones().containsKey("arms"), "an evoker draws its crossed arms");
        assertFalse(evoker.model().getBones().containsKey("left_arm"), "and not the hanging pair");
        assertTrue(pillager.model().getBones().containsKey("left_arm"), "a pillager hangs its arms");
        assertFalse(pillager.model().getBones().containsKey("arms"), "and draws no crossed pair");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull EntityPose pose(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.pose();
    }

    private static @NotNull EntityPose babyPose(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity.resolve(AppearanceOptions.builder().age(Age.BABY).build()).pose();
    }

    private static @NotNull PoseExpr channel(
        @NotNull String id, @NotNull String bone, @NotNull PoseChannel channel) {

        EntityPose pose = pose(id);
        assertTrue(pose.bones().containsKey(bone), id + " is expected to pose '" + bone + "'");
        PoseExpr written = pose.bones().get(bone).get(channel);
        assertNotNull(written, id + " is expected to write " + bone + "." + channel.token());
        return written;
    }

    private static @NotNull PoseExpr op(@NotNull PoseOperator operator, @NotNull PoseExpr... operands) {
        return new PoseExpr.Op(operator, Concurrent.newUnmodifiableList(operands));
    }

    private static @NotNull PoseExpr constant(float value) {
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

}
