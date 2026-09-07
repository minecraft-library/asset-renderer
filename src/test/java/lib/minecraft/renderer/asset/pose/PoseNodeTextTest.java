package lib.minecraft.renderer.asset.pose;

import dev.simplified.collection.Concurrent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pose node's text form names the node and refers to its children, so printing one costs its own
 * size rather than the size of the tree its graph stands for. The guarantee is load-bearing off the
 * render path: a failing assertion is formatted by calling {@code toString} on both sides, so a
 * recursive form would answer an assertion about a shared graph by exhausting the heap instead of
 * reporting the failure.
 */
@DisplayName("a pose node prints itself and refers to its children")
class PoseNodeTextTest {

    @Test
    @DisplayName("a forty-rung ladder prints in one node's worth of text, not the million million paths it stands for")
    void aLadderPrintsAsOneNode() {
        // Each rung's two operands are the SAME previous-rung instance, so forty-one nodes stand
        // for 2^40 paths; a recursive form does not finish, and this one is bounded.
        PoseExpr rung = new PoseExpr.Const(0.25d, PoseOperator.Width.DOUBLE);
        for (int height = 0; height < 40; height++)
            rung = new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(rung, rung));

        String printed = rung.toString();

        assertTrue(printed.length() < 128, "one rung's text, whatever hangs below it: " + printed);
        assertEquals("dadd" + ref(rung) + "(" + ref(operand(rung)) + ", " + ref(operand(rung)) + ")", printed,
            "the top rung names itself and refers to the one instance below it twice");
    }

    @Test
    @DisplayName("a pose holding a ladder prints bounded too - the arms bind everything that carries them")
    void aPoseHoldingALadderPrintsBounded() {
        PoseExpr rung = new PoseExpr.Const(0.25d, PoseOperator.Width.DOUBLE);
        for (int height = 0; height < 40; height++)
            rung = new PoseExpr.Op(PoseOperator.DADD, Concurrent.newUnmodifiableList(rung, rung));
        EntityPose pose = new EntityPose(
            Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(Map.of("body", Map.of(PoseChannel.X_ROT, rung))),
            Concurrent.newUnmodifiableList(),
            Optional.empty());

        assertTrue(pose.toString().length() < 4096,
            "the pose grows with the nodes it names, never with the paths they stand for");
    }

    @Test
    @DisplayName("a width spells its own literal, suffixed the way a Java literal of it is")
    void aWidthSpellsItsOwnLiteral() {
        assertEquals("0.5f", PoseOperator.Width.FLOAT.literal(0.5d));
        assertEquals("0.25d", PoseOperator.Width.DOUBLE.literal(0.25d));
        assertEquals("3.0i", PoseOperator.Width.INT.literal(3d),
            "the carrier is a double at every width, so the suffix is what says which was meant");
    }

    @Test
    @DisplayName("one instance reached twice carries one reference, and two equal instances carry two")
    void referencesAreIdentitiesRatherThanValues() {
        PoseExpr shared = new PoseExpr.Input("ageInTicks");
        PoseExpr duplicate = new PoseExpr.Input("ageInTicks");
        PoseExpr.Op reachedTwice = new PoseExpr.Op(PoseOperator.DADD,
            Concurrent.newUnmodifiableList(shared, shared));
        PoseExpr.Op reachedOnceEach = new PoseExpr.Op(PoseOperator.DADD,
            Concurrent.newUnmodifiableList(shared, duplicate));

        assertEquals("dadd" + ref(reachedTwice) + "(" + ref(shared) + ", " + ref(shared) + ")",
            reachedTwice.toString(), "sharing is what the repeated reference says");
        assertNotEquals(reachedOnceEach.toString().replace(ref(reachedOnceEach), ""),
            reachedTwice.toString().replace(ref(reachedTwice), ""),
            "and two structurally equal operands are told apart, so an identity assertion reads");
        assertEquals(shared, duplicate, "value equality is untouched - only the text form is identity-keyed");
        assertEquals(ref(shared), PoseExpr.ref(shared),
            "and the reference every arm spells is the one the type hands out");
    }

    @Test
    @DisplayName("each arm names its kind and its own local data")
    void eachArmNamesItsKindAndData() {
        PoseExpr.Const literal = new PoseExpr.Const(0.25d, PoseOperator.Width.DOUBLE);
        PoseExpr.Const single = new PoseExpr.Const(0.5d, PoseOperator.Width.FLOAT);
        PoseExpr.Const integral = new PoseExpr.Const(3d, PoseOperator.Width.INT);
        PoseExpr.Input field = new PoseExpr.Input("walkAnimationPos");
        PoseExpr.BoneRead read = new PoseExpr.BoneRead("body", PoseChannel.X_ROT);
        PosePredicate condition = new PosePredicate(PosePredicate.Comparison.NE, field, literal);
        PoseExpr.Select select = new PoseExpr.Select(condition, read, literal);

        assertEquals("const" + ref(literal) + "(0.25d)", literal.toString());
        assertEquals("const" + ref(single) + "(0.5f)", single.toString());
        assertEquals("const" + ref(integral) + "(3.0i)", integral.toString());
        assertEquals("input" + ref(field) + "(walkAnimationPos)", field.toString());
        assertEquals("read" + ref(read) + "(body.x_rot)", read.toString());
        assertEquals("ne" + ref(condition) + "(" + ref(field) + ", " + ref(literal) + ")",
            condition.toString());
        assertEquals("select" + ref(select) + "(" + ref(condition) + " ? " + ref(read) + " : " + ref(literal) + ")",
            select.toString());
    }

    /**
     * The reference one node is spelled with wherever it is reached from.
     */
    private static @NotNull String ref(@NotNull Object node) {
        return "@" + Integer.toHexString(System.identityHashCode(node));
    }

    /**
     * The first operand of an operation, which every rung of a ladder shares with its second.
     */
    private static @NotNull PoseExpr operand(@NotNull PoseExpr rung) {
        return ((PoseExpr.Op) rung).operands().getFirst();
    }

}
