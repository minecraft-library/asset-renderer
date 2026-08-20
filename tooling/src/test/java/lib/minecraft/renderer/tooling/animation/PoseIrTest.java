package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.tooling.kernel.VanillaMth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                operands.add(new PoseExpr.Const(value, operator.width()));
            }
            PoseExpr folded = PoseExpr.Op.of(operator, operands);
            PoseExpr.Const literal = assertInstanceOf(PoseExpr.Const.class, folded, operator.token());
            assertEquals(Double.doubleToLongBits(operator.apply(values)), Double.doubleToLongBits(literal.value()),
                operator.token() + " folded to something its own apply does not answer");
            assertSame(operator.width(), literal.width(), operator.token() + " folded at the wrong width");
        }
    }

    @Test
    @DisplayName("an operation with a non-literal operand stays unfolded")
    void anythingSymbolicStaysSymbolic() {
        PoseExpr open = PoseExpr.Op.of(PoseOperator.MUL, PoseExpr.Const.of(2f), new PoseExpr.Input("ageInTicks"));
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

            PoseExpr wide = PoseExpr.Op.of(PoseOperator.MUL,
                PoseExpr.Op.of(PoseOperator.D2F,
                    PoseExpr.Op.of(PoseOperator.LIBM_SIN,
                        PoseExpr.Op.of(PoseOperator.DMUL,
                            PoseExpr.Op.of(PoseOperator.DDIV,
                                PoseExpr.Op.of(PoseOperator.F2D, PoseExpr.Const.of(tick)),
                                PoseExpr.Const.of(40.0)),
                            PoseExpr.Const.of(10.0)))),
                PoseExpr.Const.of(3f));

            float vanilla = (float) Math.sin((double) tick / 40.0 * 10.0) * 3f;
            assertEquals(Float.floatToIntBits(vanilla),
                Float.floatToIntBits((float) ((PoseExpr.Const) wide).value()),
                "the wide fold must reproduce vanilla's own arithmetic at tick " + tick);
        }

        // And the width is load-bearing rather than incidental. Taken over the arithmetic itself
        // rather than through the sine, because a narrowing after the sine can round two different
        // arguments onto one float and hide the divergence. At 0.03f it does not: dividing and
        // multiplying at float width rounds twice where doing it wide rounds once.
        float diverging = 0.03f;
        PoseExpr wideCore = PoseExpr.Op.of(PoseOperator.D2F,
            PoseExpr.Op.of(PoseOperator.DMUL,
                PoseExpr.Op.of(PoseOperator.DDIV,
                    PoseExpr.Op.of(PoseOperator.F2D, PoseExpr.Const.of(diverging)),
                    PoseExpr.Const.of(40.0)),
                PoseExpr.Const.of(10.0)));
        PoseExpr narrowCore = PoseExpr.Op.of(PoseOperator.MUL,
            PoseExpr.Op.of(PoseOperator.DIV, PoseExpr.Const.of(diverging), PoseExpr.Const.of(40f)),
            PoseExpr.Const.of(10f));

        assertEquals(Float.floatToIntBits((float) ((double) diverging / 40.0 * 10.0)),
            Float.floatToIntBits((float) ((PoseExpr.Const) wideCore).value()),
            "the wide core must be vanilla's double arithmetic");
        assertNotEquals(Float.floatToIntBits((float) ((PoseExpr.Const) wideCore).value()),
            Float.floatToIntBits((float) ((PoseExpr.Const) narrowCore).value()),
            "the same shape folded at float width must be a different number - if it is not, this "
                + "pin has stopped demonstrating why PoseOperator carries a width");
    }

    @Test
    @DisplayName("widening is exact and narrowing rounds once")
    void conversionsRoundWhereVanillaRounds() {
        double wide = 0.1;
        PoseExpr narrowed = PoseExpr.Op.of(PoseOperator.D2F, PoseExpr.Const.of(wide));
        assertEquals(0.1f, (float) ((PoseExpr.Const) narrowed).value(), "d2f rounds to the float neighbour");

        PoseExpr widened = PoseExpr.Op.of(PoseOperator.F2D, PoseExpr.Const.of(0.1f));
        assertEquals(Double.doubleToLongBits(0.1f), Double.doubleToLongBits(((PoseExpr.Const) widened).value()),
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
            () -> PoseExpr.Op.of(PoseOperator.CLAMP, PoseExpr.Const.of(1f)),
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
        Set<String> channelFields = new HashSet<>();
        for (PoseChannel channel : PoseChannel.values()) {
            assertTrue(channelTokens.add(channel.token()), "duplicate channel token " + channel.token());
            assertTrue(channelFields.add(channel.field()), "duplicate channel field " + channel.field());
            assertSame(channel, PoseChannel.ofField(channel.field()), channel.field());
        }
        assertEquals(11, channelTokens.size(), "the sink vocabulary is the eleven measured ModelPart members");
    }

    @Test
    @DisplayName("the four ModelPart members vanilla mutates by method are not channels")
    void onlyFieldWritesAreChannels() {
        // setRotation, offsetPos, offsetRotation and translateAndRotate are called from nowhere a
        // pose walk reaches, so none of them names a channel. If one ever does, the walk should
        // fail on the call rather than find a channel waiting for it.
        for (String absent : List.of("setRotation", "offsetPos", "offsetRotation", "translateAndRotate"))
            assertEquals(null, PoseChannel.ofField(absent), absent + " must not resolve to a channel");
    }

    @Test
    @DisplayName("a comparison over literals decides, and negation collapses rather than wrapping")
    void predicatesFoldWhereTheyCan() {
        PosePredicate decided = PosePredicate.Compare.of(
            PosePredicate.Comparison.LT, PoseExpr.Const.of(1f), PoseExpr.Const.of(2f));
        assertEquals(new PosePredicate.Constant(true), decided, "a literal comparison must decide");

        PosePredicate open = PosePredicate.Compare.of(
            PosePredicate.Comparison.GT, new PoseExpr.Input("swimAmount"), PoseExpr.Const.of(0f));
        assertInstanceOf(PosePredicate.Compare.class, open, "a comparison against an input must not decide");

        assertEquals(new PosePredicate.Constant(false), decided.negate(), "negating a decided predicate decides");
        assertSame(open, open.negate().negate(), "a double negation collapses to the original instance");
        assertNotEquals(open, open.negate(), "a single negation does not");
    }

}
