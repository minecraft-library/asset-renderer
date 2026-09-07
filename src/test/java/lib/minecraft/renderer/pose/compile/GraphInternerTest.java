package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.pose.PosePredicate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The pool's contract - adoption seeds every reachable shipped instance untouched, interning
 * resolves a structure to one canonical instance, and every walk completes on graphs whose tree
 * expansion does not.
 */
@DisplayName("the interner unifies structures without ever swapping a shipped instance")
class GraphInternerTest {

    @Test
    @DisplayName("a builder duplicate of a shipped subtree resolves to the shipped instance")
    void builderDuplicateResolvesToShippedInstance() {
        PoseExpr shipped = dadd(new PoseExpr.BoneRead("head", PoseChannel.X_ROT), constant(0.5d));
        GraphInterner interner = new GraphInterner();
        interner.adopt(boneWrite("head", shipped));

        PoseExpr duplicate = dadd(new PoseExpr.BoneRead("head", PoseChannel.X_ROT), constant(0.5d));

        assertSame(shipped, interner.intern(duplicate), "the duplicate names the shipped structure");
        assertSame(shipped, interner.intern(shipped), "the shipped instance answers as itself");
    }

    @Test
    @DisplayName("value-equal distinct shipped leaves both survive adoption unrewritten")
    void valueEqualShippedLeavesBothSurvive() {
        PoseExpr first = constant(1.5d);
        PoseExpr second = constant(1.5d);
        GraphInterner interner = new GraphInterner();
        interner.adopt(pose(
            List.of(Map.of(PoseChannel.Y, first)),
            Map.of("head", Map.of(PoseChannel.Y, second)),
            List.of()));

        assertSame(first, interner.intern(first), "an adopted leaf passes through as itself");
        assertSame(second, interner.intern(second), "a value-equal duplicate is never swapped for the first");
        assertSame(first, interner.intern(constant(1.5d)), "a new duplicate answers the first registration");
    }

    @Test
    @DisplayName("adoption visits each instance once - a forty-rung diamond ladder seeds in linear time")
    void adoptionWalksEachInstanceOnce() {
        // Each rung's two operands are the SAME previous-rung instance, so forty-one nodes stand
        // for 2^40 paths; a walk without the identity short-circuit does not finish, and one with
        // it registers each node exactly once.
        GraphInterner interner = new GraphInterner();
        interner.adopt(boneWrite("body", ladder(40)));

        assertEquals(41, interner.size(), "each rung and the base leaf register exactly once");
    }

    @Test
    @DisplayName("adoption reaches container steps, bone channels and clip site arguments")
    void adoptionReachesEveryRegion() {
        PoseExpr step = dadd(new PoseExpr.Input("limbSwing"), constant(2d));
        PoseExpr channel = dadd(new PoseExpr.BoneRead("head", PoseChannel.Y_ROT), constant(3d));
        PoseExpr argument = dadd(new PoseExpr.Input("walkAnimationPos"), constant(4d));
        GraphInterner interner = new GraphInterner();
        interner.adopt(pose(
            List.of(Map.of(PoseChannel.Y, step)),
            Map.of("head", Map.of(PoseChannel.Y_ROT, channel)),
            List.of(new EntityPose.Clip("gait", MotionSource.STRIDE, Optional.empty(),
                Concurrent.newUnmodifiableList(argument, constant(1d), constant(20d), constant(1d)),
                new PoseClip(1f, false, Concurrent.newUnmodifiableList())))));

        assertSame(step, interner.intern(dadd(new PoseExpr.Input("limbSwing"), constant(2d))));
        assertSame(channel, interner.intern(dadd(new PoseExpr.BoneRead("head", PoseChannel.Y_ROT), constant(3d))));
        assertSame(argument, interner.intern(dadd(new PoseExpr.Input("walkAnimationPos"), constant(4d))));
    }

    @Test
    @DisplayName("a select-gated clip argument adopts, predicate included")
    void clipArgumentsAdoptPredicates() {
        PoseExpr zero = constant(0d);
        PosePredicate gate = new PosePredicate(PosePredicate.Comparison.GT, new PoseExpr.Input("style$sit"), zero);
        PoseExpr argument = new PoseExpr.Select(gate, new PoseExpr.Input("style$sit$clock"), zero);
        GraphInterner interner = new GraphInterner();
        interner.adopt(pose(List.of(), Map.of(), List.of(new EntityPose.Clip(
            "sit", MotionSource.SELECT, Optional.of("style$sit"),
            Concurrent.newUnmodifiableList(argument),
            new PoseClip(1f, true, Concurrent.newUnmodifiableList())))));

        PosePredicate duplicateGate = new PosePredicate(PosePredicate.Comparison.GT,
            new PoseExpr.Input("style$sit"), constant(0d));

        assertSame(gate, interner.intern(duplicateGate), "the predicate adopted through the clip site");
        assertSame(argument, interner.intern(new PoseExpr.Select(duplicateGate,
            new PoseExpr.Input("style$sit$clock"), constant(0d))), "the gated site answers the shipped instance");
    }

    @Test
    @DisplayName("two intern calls with structurally equal new nodes answer one instance")
    void structurallyEqualNewNodesUnify() {
        GraphInterner interner = new GraphInterner();
        PoseExpr first = interner.intern(dadd(new PoseExpr.Input("style$sit$body$x_rot"), constant(0.25d)));
        PoseExpr second = interner.intern(dadd(new PoseExpr.Input("style$sit$body$x_rot"), constant(0.25d)));

        assertSame(first, second, "one structure is one pooled instance");
        assertEquals(3, interner.size(), "the input, the constant and the sum pool once each");
    }

    @Test
    @DisplayName("structurally equal new predicates pool once")
    void newPredicatesUnify() {
        GraphInterner interner = new GraphInterner();
        PosePredicate first = interner.intern(new PosePredicate(PosePredicate.Comparison.GT,
            new PoseExpr.Input("style$sit"), constant(0d)));
        PosePredicate second = interner.intern(new PosePredicate(PosePredicate.Comparison.GT,
            new PoseExpr.Input("style$sit"), constant(0d)));

        assertSame(first, second, "one comparison over one operand pair is one pooled predicate");
    }

    @Test
    @DisplayName("a structural ladder duplicate interns to the shipped rungs without growing the pool")
    void ladderDuplicateInternsToShipped() {
        PoseExpr shipped = ladder(40);
        GraphInterner interner = new GraphInterner();
        interner.adopt(boneWrite("body", shipped));

        assertSame(shipped, interner.intern(ladder(40)), "every level resolves to the shipped instance");
        assertEquals(41, interner.size(), "canonicalizing registers nothing new");
    }

    @Test
    @DisplayName("a new parent over a duplicate child is rebuilt over the shipped child")
    void newParentRebuildsOverCanonicalChild() {
        PoseExpr leaf = constant(0.75d);
        GraphInterner interner = new GraphInterner();
        interner.adopt(boneWrite("tail", leaf));

        PoseExpr splice = interner.intern(dadd(constant(0.75d), new PoseExpr.Input("style$wag$tail$x_rot")));

        assertSame(leaf, ((PoseExpr.Op) splice).operands().getFirst(),
            "the pooled sum reads the shipped leaf, not the duplicate it was built over");
    }

    @Test
    @DisplayName("one pool spans adoption of the body, a layer and the interns between them")
    void poolSpansAdoptsAndInterns() {
        PoseExpr bodyShared = dadd(new PoseExpr.Input("ageInTicks"), constant(0.5d));
        GraphInterner interner = new GraphInterner();
        interner.adopt(boneWrite("body", bodyShared));

        assertSame(bodyShared, interner.intern(dadd(new PoseExpr.Input("ageInTicks"), constant(0.5d))),
            "a splice base built between adopts unifies with the body");

        PoseExpr layerTwin = dadd(new PoseExpr.Input("ageInTicks"), constant(0.5d));
        interner.adopt(boneWrite("hat", layerTwin));

        assertSame(layerTwin, interner.intern(layerTwin), "the layer's own twin passes through as itself");
        assertSame(bodyShared, interner.intern(dadd(new PoseExpr.Input("ageInTicks"), constant(0.5d))),
            "the body's first registration stays canonical across adopts");
    }

    @Test
    @DisplayName("a literal's width and the sign of its zero key apart")
    void constWidthAndZeroSignStayDistinct() {
        GraphInterner interner = new GraphInterner();
        PoseExpr floatZero = interner.intern(new PoseExpr.Const(0d, PoseOperator.Width.FLOAT));
        PoseExpr doubleZero = interner.intern(new PoseExpr.Const(0d, PoseOperator.Width.DOUBLE));
        PoseExpr negativeZero = interner.intern(new PoseExpr.Const(-0d, PoseOperator.Width.DOUBLE));

        assertNotSame(floatZero, doubleZero, "width is local data");
        assertNotSame(doubleZero, negativeZero, "the two zeros hold different bits");
        assertEquals(3, interner.size(), "three literals, three pool entries");
    }

    // ------------------------------------------------------------------------------------

    /**
     * A readable pose over the given regions.
     */
    private static @NotNull EntityPose pose(
        @NotNull List<Map<PoseChannel, PoseExpr>> container,
        @NotNull Map<String, Map<PoseChannel, PoseExpr>> bones,
        @NotNull List<EntityPose.Clip> clips) {

        return new EntityPose(
            Concurrent.newUnmodifiableList(container),
            Concurrent.newUnmodifiableMap(bones),
            Concurrent.newUnmodifiableList(clips),
            Optional.empty());
    }

    /**
     * A pose writing one bone's pitch with the given expression.
     */
    private static @NotNull EntityPose boneWrite(@NotNull String bone, @NotNull PoseExpr expression) {
        return pose(List.of(), Map.of(bone, Map.of(PoseChannel.X_ROT, expression)), List.of());
    }

    private static @NotNull PoseExpr constant(double value) {
        return new PoseExpr.Const(value, PoseOperator.Width.FLOAT);
    }

    private static @NotNull PoseExpr dadd(@NotNull PoseExpr left, @NotNull PoseExpr right) {
        return new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(left, right));
    }

    /**
     * A ladder of the given height - each rung an operation over the SAME previous-rung instance twice.
     */
    private static @NotNull PoseExpr ladder(int rungs) {
        PoseExpr node = constant(0.25d);
        for (int rung = 0; rung < rungs; rung++)
            node = dadd(node, node);
        return node;
    }

}
