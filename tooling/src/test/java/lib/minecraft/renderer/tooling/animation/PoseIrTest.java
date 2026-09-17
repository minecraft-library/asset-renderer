package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.PosePredicate;

import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseOperator;
import lib.minecraft.renderer.tensor.VanillaMth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins on the pose expression vocabulary - the fold-versus-evaluate identity, the width discipline
 * that identity rests on, and the two token tables a shipped byte is spelled from.
 */
@DisplayName("the pose expression vocabulary")
class PoseIrTest {

    @Test
    @DisplayName("an operation over literals folds to the value the operator itself computes")
    void foldingAgreesWithApplying() {
        // The whole reason folding at extraction is safe: the fold calls the same method the
        // renderer will. If these ever disagree, a folded expression and an evaluated one are two
        // different poses.
        for (PoseOperator operator : PoseOperator.values()) {
            List<PoseExpr> operands = new ArrayList<>();
            double[] values = new double[operator.arity()];
            for (int index = 0; index < operator.arity(); index++) {
                // Every operand truncates to a non-zero int, because vanilla's integer divide is a
                // real divide: a zero divisor throws here exactly as it would in the client, and
                // that is a failed walk for the caller to report rather than a value to invent.
                double value = 2.0 + index;
                values[index] = value;
                operands.add(new PoseExpr.Constant(value, operator.width()));
            }
            PoseExpr folded = PoseExpr.operation(operator, operands);
            PoseExpr.Constant literal = assertInstanceOf(PoseExpr.Constant.class, folded, operator.token());
            assertEquals(Double.doubleToLongBits(operator.apply(values)), Double.doubleToLongBits(literal.value()),
                operator.token() + " folded to something its own apply does not answer");
            assertSame(operator.width(), literal.width(), operator.token() + " folded at the wrong width");
        }
    }

    @Test
    @DisplayName("an operation with a non-literal operand stays unfolded")
    void anythingSymbolicStaysSymbolic() {
        PoseExpr open = PoseExpr.operation(PoseOperator.MUL, new PoseExpr.Constant(2f), new PoseExpr.Input("ageInTicks"));
        assertInstanceOf(PoseExpr.Op.class, open, "an input operand must not fold");
    }

    @Test
    @DisplayName("the ravager's neck offset folds at double width, which a float fold does not reproduce")
    void widthIsNotDecoration() {
        // RavagerModel.setupAnim: f2d; 40.0d ddiv; dstore; dload; 10.0d dmul; Math.sin(D)D; d2f;
        // 3.0f fmul. The divide and the multiply are DOUBLE and the value narrows once, after the
        // sine. Folding that shape at float width is a different number, which is the entire reason
        // PoseOperator carries a width.
        for (int step = 1; step <= 64; step++) {
            float tick = step * 0.5f;

            PoseExpr wide = PoseExpr.operation(PoseOperator.MUL,
                PoseExpr.operation(PoseOperator.D2F,
                    PoseExpr.operation(PoseOperator.LIBM_SIN,
                        PoseExpr.operation(PoseOperator.DMUL,
                            PoseExpr.operation(PoseOperator.DDIV,
                                PoseExpr.operation(PoseOperator.F2D, new PoseExpr.Constant(tick)),
                                new PoseExpr.Constant(40.0)),
                            new PoseExpr.Constant(10.0)))),
                new PoseExpr.Constant(3f));

            float vanilla = (float) Math.sin((double) tick / 40.0 * 10.0) * 3f;
            assertEquals(Float.floatToIntBits(vanilla),
                Float.floatToIntBits((float) ((PoseExpr.Constant) wide).value()),
                "the wide fold must reproduce vanilla's own arithmetic at tick " + tick);
        }

        // And the width is load-bearing rather than incidental. Taken over the arithmetic itself
        // rather than through the sine, because a narrowing after the sine can round two different
        // arguments onto one float and hide the divergence. At 0.03f it does not: dividing and
        // multiplying at float width rounds twice where doing it wide rounds once.
        float diverging = 0.03f;
        PoseExpr wideCore = PoseExpr.operation(PoseOperator.D2F,
            PoseExpr.operation(PoseOperator.DMUL,
                PoseExpr.operation(PoseOperator.DDIV,
                    PoseExpr.operation(PoseOperator.F2D, new PoseExpr.Constant(diverging)),
                    new PoseExpr.Constant(40.0)),
                new PoseExpr.Constant(10.0)));
        PoseExpr narrowCore = PoseExpr.operation(PoseOperator.MUL,
            PoseExpr.operation(PoseOperator.DIV, new PoseExpr.Constant(diverging), new PoseExpr.Constant(40f)),
            new PoseExpr.Constant(10f));

        assertEquals(Float.floatToIntBits((float) ((double) diverging / 40.0 * 10.0)),
            Float.floatToIntBits((float) ((PoseExpr.Constant) wideCore).value()),
            "the wide core must be vanilla's double arithmetic");
        assertNotEquals(Float.floatToIntBits((float) ((PoseExpr.Constant) wideCore).value()),
            Float.floatToIntBits((float) ((PoseExpr.Constant) narrowCore).value()),
            "the same shape folded at float width must be a different number - if it is not, this "
                + "pin has stopped demonstrating why PoseOperator carries a width");
    }

    @Test
    @DisplayName("widening is exact and narrowing rounds once")
    void conversionsRoundWhereVanillaRounds() {
        double wide = 0.1;
        PoseExpr narrowed = PoseExpr.operation(PoseOperator.D2F, new PoseExpr.Constant(wide));
        assertEquals(0.1f, (float) ((PoseExpr.Constant) narrowed).value(), "d2f rounds to the float neighbour");

        PoseExpr widened = PoseExpr.operation(PoseOperator.F2D, new PoseExpr.Constant(0.1f));
        assertEquals(Double.doubleToLongBits(0.1f), Double.doubleToLongBits(((PoseExpr.Constant) widened).value()),
            "f2d must be exact, never a second rounding");
    }

    @Test
    @DisplayName("the two trigonometry contracts stay two operators")
    void sampledAndLibmAreNotInterchangeable() {
        int agreements = 0;
        for (int step = 0; step < 64; step++) {
            double angle = step * 0.1;
            double sampled = PoseOperator.MTH_SIN.apply(angle);
            double libm = PoseOperator.LIBM_SIN.apply(angle);
            if (Double.doubleToLongBits(sampled) == Double.doubleToLongBits(libm)) agreements++;
        }
        assertTrue(agreements < 32, "the sampled table and libm must not be the same function");
        assertEquals(Float.floatToIntBits(VanillaMth.mthSin(1.0)), Float.floatToIntBits((float) PoseOperator.MTH_SIN.apply(1.0)),
            "mth_sin must be the table the renderer samples");
    }

    @Test
    @DisplayName("an operation refuses an operand count that is not its arity")
    void arityIsEnforced() {
        assertThrows(IllegalArgumentException.class,
            () -> PoseExpr.operation(PoseOperator.CLAMP, new PoseExpr.Constant(1f)),
            "a ternary built with one operand must not be representable");
        assertThrows(IllegalArgumentException.class, () -> PoseOperator.NEG.apply(1.0, 2.0),
            "applying a unary to two operands must not be representable");
    }

    @Test
    @DisplayName("every operator and every channel is spelled once and resolves back")
    void tokensAreUniqueAndReversible() {
        Set<String> operatorTokens = new HashSet<>();
        for (PoseOperator operator : PoseOperator.values()) {
            assertTrue(operatorTokens.add(operator.token()), "duplicate operator token " + operator.token());
            assertSame(operator, PoseOperator.ofToken(operator.token()), operator.token());
        }
        Set<String> channelTokens = new HashSet<>();
        for (PoseChannel channel : PoseChannel.values()) {
            assertTrue(channelTokens.add(channel.token()), "duplicate channel token " + channel.token());
            assertSame(channel, PoseChannel.ofToken(channel.token()), channel.token());
        }
        assertEquals(9, channelTokens.size(), "the channel vocabulary is the nine the renderer ships");
    }

    @Test
    @DisplayName("every ModelPart member a body writes is a channel or a flag, and none is both")
    void everyWrittenMemberIsAChannelOrAFlag() {
        // The eleven measured members are the nine channels plus the two flags, and the walk reaches
        // each by converting the camel-case field a putfield spells into the snake-case token the
        // table is written in. So this is what says the two rosters still partition those eleven: a
        // member answering both, or neither, is a write the walk would refuse or file twice.
        Set<String> fields = new HashSet<>();
        for (PoseChannel channel : PoseChannel.values()) {
            String field = StringUtil.toCamelCase(channel.token());
            assertTrue(fields.add(field), "duplicate ModelPart field " + field);
            assertSame(channel, PoseChannel.ofToken(StringUtil.toSnakeCase(field)),
                field + " must round-trip to its own channel");
            assertNull(BoneFlag.ofField(field), field + " is a channel and must not also be a flag");
        }
        for (BoneFlag flag : BoneFlag.values()) {
            assertTrue(fields.add(flag.field()), "duplicate ModelPart field " + flag.field());
            assertSame(flag, BoneFlag.ofField(flag.field()), flag.field());
            assertNull(PoseChannel.ofToken(flag.token()),
                flag.field() + " is a flag and must not also ship as a channel");
            assertEquals(flag.field(), StringUtil.toCamelCase(flag.token()),
                "a flag's field and token are one word in two cases");
        }
        assertEquals(11, fields.size(), "the measured ModelPart write surface is eleven members");
    }

    @Test
    @DisplayName("the four ModelPart members vanilla mutates by method are not channels")
    void onlyFieldWritesAreChannels() {
        // setRotation, offsetPos, offsetRotation and translateAndRotate are called from nowhere a
        // pose walk reaches, so none of them names a channel. If one ever does, the walk should
        // fail on the call rather than find a channel waiting for it.
        for (String absent : List.of("setRotation", "offsetPos", "offsetRotation", "translateAndRotate")) {
            assertNull(PoseChannel.ofToken(StringUtil.toSnakeCase(absent)),
                absent + " must not resolve to a channel");
            assertNull(BoneFlag.ofField(absent), absent + " must not resolve to a flag");
        }
    }

    @Test
    @DisplayName("an arm that leaves a flag alone leaves the literal it rested at, not a read of itself")
    void aForkDefaultsAFlagToItsRest() {
        // What merging two fork arms does for a flag, and the one shape the corpus never produces:
        // an arm writing a flag on a bone the other arm does not touch. Forcing both defaults to the
        // wrong literal moves no emitted byte and trips nothing, so this is the only thing that says
        // which literal an untouched flag stands at - and the two differ, a part drawing until
        // something hides it and skipping none of its own cubes until something says otherwise.
        //
        // It has to be a literal rather than a read of the flag: a bone read is the one node the fold
        // refuses to settle, and a flag that reaches a resting map unsettled stops the generation.
        PosePredicate condition = new PosePredicate(PosePredicate.Comparison.GT,
            new PoseExpr.Input("swimAmount"), new PoseExpr.Constant(0f));
        PoseExpr hidden = new PoseExpr.Constant(0);
        PoseExpr skipping = new PoseExpr.Constant(1);

        Map<BoneFlag, Map<String, PoseExpr>> merged = PoseWalk.mergeFlags(condition,
            Map.of(BoneFlag.VISIBLE, Map.of("hat", hidden)),
            Map.of(BoneFlag.SKIP_DRAW, Map.of("body", skipping)));

        assertEquals(new PoseExpr.Select(condition, hidden, new PoseExpr.Constant(1)),
            merged.get(BoneFlag.VISIBLE).get("hat"),
            "the arm that hid the hat against the arm that left it drawing, which is where visible rests");
        assertEquals(new PoseExpr.Select(condition, new PoseExpr.Constant(0), skipping),
            merged.get(BoneFlag.SKIP_DRAW).get("body"),
            "and skip_draw rests at zero, so the arm that did not write it is the one that skips nothing");

        assertEquals(new PoseExpr.Constant(1), BoneFlag.VISIBLE.resting(),
            "a part draws until something hides it");
        assertEquals(new PoseExpr.Constant(0), BoneFlag.SKIP_DRAW.resting(),
            "and skips none of its own cubes until something says otherwise");

        // An arm writing what the flag already rested at is not a disagreement, so the merge keeps
        // the literal rather than guarding it. That is what makes the rest value load-bearing in
        // both directions: read it wrongly and this collapse either happens where it should not or
        // fails to happen where it should.
        assertEquals(hidden, PoseWalk.mergeFlags(condition,
                Map.of(BoneFlag.SKIP_DRAW, Map.of("body", hidden)), Map.of())
            .get(BoneFlag.SKIP_DRAW).get("body"),
            "an arm writing the resting literal agrees with the arm that wrote nothing");
    }

    @Test
    @DisplayName("a comparison over literals decides, and negation collapses rather than wrapping")
    void predicatesFoldWhereTheyCan() {
        PosePredicate decided = PosePredicate.comparing(
            PosePredicate.Comparison.LT, new PoseExpr.Constant(1f), new PoseExpr.Constant(2f));
        assertEquals(Optional.of(true), decided.answered(), "a literal comparison must decide");
        assertEquals(PosePredicate.settled(true), decided, "and it is spelled as the decision it is");

        PosePredicate open = PosePredicate.comparing(
            PosePredicate.Comparison.GT, new PoseExpr.Input("swimAmount"), new PoseExpr.Constant(0f));
        assertEquals(Optional.empty(), open.answered(),
            "a comparison against an input must not decide");

        assertEquals(PosePredicate.settled(false), decided.negate(),
            "negating a decided predicate decides the other way");
        assertEquals(open, open.negate().negate(),
            "a double negation is the comparison it started as");
        assertNotEquals(open, open.negate(), "a single negation is not");
    }

}
