package lib.minecraft.renderer.tooling.animation;

import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PosePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins on the two bone flags a pose walk writes beside the nine channels - that the flags and the
 * channels partition the members a body writes, and which literal an untouched flag rests at when two
 * fork arms merge.
 */
@DisplayName("the bone flags a pose walk writes")
class BoneFlagTest {

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

}
