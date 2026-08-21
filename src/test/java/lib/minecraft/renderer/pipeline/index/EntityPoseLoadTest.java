package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PosePredicate;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped pose table read back through the loader.
 *
 * <p>Four things are worth pinning and nothing else here is. The expression grammar has to survive
 * the round trip operand for operand, because a table that decodes into a DIFFERENT tree is a table
 * that reads perfectly and animates wrongly. A refusal has to arrive as a refusal, because a model
 * whose pose could not be read renders exactly like one that poses nothing. The pose has to follow
 * the mesh across the age fork, because a baby is its own model class - a turtle poses a bone only
 * its adult mesh has and a donkey poses its head off a pitch only its foal assigns, so one pose per
 * entity would be wrong for one of them whichever way it was chosen. And a sub-expression the
 * table names once has
 * to arrive once, because a reader that rebuilt one per reference would turn a humanoid's nine
 * hundred nodes back into the twenty-two million the table exists not to write.
 */
@DisplayName("the shipped pose table")
class EntityPoseLoadTest {

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
        EntityPose dragon = pose("minecraft:ender_dragon");
        assertFalse(dragon.isReadable(), "the ender dragon's pose is not readable");
        assertTrue(dragon.refusal().orElseThrow().contains("getHistoricalPos"),
            "and it says what stopped it: the model poses off a track of where it has lately been");
        assertEquals(List.of(), List.copyOf(dragon.bones().keySet()),
            "a refusal poses nothing, which is how it renders and why it has to be distinguishable");
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
        // own head pitch and reads that back, where the adult reads the pitch the render state
        // carries - so a foal handed the adult's pose would look wherever the animal is looking.
        assertTrue(pose("minecraft:donkey").isReadable(), "the adult donkey has a readable pose");
        assertTrue(babyPose("minecraft:donkey").isReadable(), "and so does the foal");
        assertTrue(reads(pose("minecraft:donkey"), "xRot"),
            "the adult donkey's head follows the pitch it is handed");
        assertFalse(reads(babyPose("minecraft:donkey"), "xRot"),
            "the foal's does not - it holds the angle it assigned itself");
    }

    /**
     * Whether any channel of a pose reads a named render-state field.
     *
     * @param pose the pose to search
     * @param field the vanilla render-state field name
     * @return whether the field is read anywhere inside it
     */
    private static boolean reads(@NotNull EntityPose pose, @NotNull String field) {
        Map<Object, Integer> reached = new IdentityHashMap<>();
        pose.bones().values().forEach(channels -> channels.values().forEach(expr -> edges(expr, reached)));
        return reached.keySet().stream()
            .anyMatch(node -> node instanceof PoseExpr.Input input && input.field().equals(field));
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
        switch (predicate) {
            case PosePredicate.Not not -> edges(not.operand(), reached);
            case PosePredicate.Compare compare -> {
                edges(compare.left(), reached);
                edges(compare.right(), reached);
            }
            default -> { /* a leaf reaches nothing */ }
        }
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
            .filter(clip -> clip.gate() == EntityPose.Gate.WALK).toList();
        assertFalse(walked.isEmpty(), "a frog drives clips off its walk");
        walked.forEach(clip -> assertEquals(4, clip.arguments().size(),
            clip.coordinate() + " is driven by four arguments"));
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
        return new PoseExpr.Op(operator, List.of(operands));
    }

    private static @NotNull PoseExpr constant(float value) {
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

}
