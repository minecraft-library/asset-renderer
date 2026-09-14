package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gait verb set - what one cycle captures, and which rows its shapes are addressed at.
 *
 * <p>A gait states no leg count, so what it captures is a set of rank-and-side addresses the
 * target mesh answers. These read the addresses back off the script rather than installing, which
 * is where the mesh gets its say.
 */
@DisplayName("a gait captures one shape per row and lets the mesh count the legs")
class GaitTest {

    @Test
    @DisplayName("the cycle's length is the style's period")
    void theCycleLengthIsThePeriod() {
        PoseScript script = Poses.legged("amble")
            .gait(gait -> gait.over(2.0).step(leg -> leg.sway(Turn.PITCH, -35, 35)))
            .build()
            .script();

        assertEquals(2.0, script.periodSeconds().orElseThrow(),
            "the one value a gait speaks in seconds");
    }

    @Test
    @DisplayName("an unkeyed step addresses every row, both sides, in one stamp")
    void anUnkeyedStepAddressesEveryRow() {
        PoseScript script = Poses.legged("glide")
            .gait(gait -> gait.step(leg -> leg.sway(Turn.ROLL, -8, 8)))
            .build()
            .script();

        assertEquals(List.of(
                new LimbSelector.Legs(Optional.empty(), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.empty(), Optional.of(Side.LEFT), Reach.ROOT, LimbSelector.Stamp.FAR)),
            selectorsOf(script),
            "no rank is named, so however many rows the mesh carries are the rows it walks");
    }

    @Test
    @DisplayName("a keyed step addresses one row per call, in author order")
    void keyedStepsAddressOneRowEach() {
        PoseScript script = Poses.legged("hover")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.pitchBy(22.5))
                .step(Rank.SECOND, leg -> leg.pitchBy(45))
                .step(Rank.HIND, leg -> leg.pitchBy(45)))
            .build()
            .script();

        assertEquals(List.of(
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.of(Rank.FRONT), Optional.of(Side.LEFT), Reach.ROOT, LimbSelector.Stamp.FAR),
                new LimbSelector.Legs(Optional.of(Rank.SECOND), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.of(Rank.SECOND), Optional.of(Side.LEFT), Reach.ROOT, LimbSelector.Stamp.FAR),
                new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.of(Side.RIGHT), Reach.ROOT,
                    LimbSelector.Stamp.NEAR),
                new LimbSelector.Legs(Optional.of(Rank.HIND), Optional.of(Side.LEFT), Reach.ROOT, LimbSelector.Stamp.FAR)),
            selectorsOf(script),
            "three rows, six addresses, and not one bone name among them");
        assertEquals(List.of(22.5, 22.5, 45d, 45d, 45d, 45d),
            script.stances().stream().map(stance -> stance.writes().getFirst().value()).toList(),
            "each row keeping the rest it was written with");
    }

    @Test
    @DisplayName("the far side derives under the sign rule, and share() reads it as written")
    void shareLeavesTheFarSideAsAuthored() {
        PoseScript signed = Poses.legged("bank")
            .gait(gait -> gait.step(leg -> leg.roll(8)))
            .build()
            .script();
        PoseScript shared = Poses.legged("glide")
            .gait(gait -> gait.share().step(leg -> leg.roll(8)))
            .build()
            .script();

        assertEquals(List.of(8d, -8d), rollsOf(signed),
            "roll negates on the far side, which is what two sides banking apart means");
        assertEquals(List.of(8d, 8d), rollsOf(shared),
            "and share() is the escape for the meshes whose sides turn the same way");
    }

    @Test
    @DisplayName("share() reaches a shape written before it")
    void shareReachesAShapeWrittenBeforeIt() {
        PoseScript script = Poses.legged("glide")
            .gait(gait -> gait.step(leg -> leg.roll(8)).share())
            .build()
            .script();

        assertEquals(List.of(8d, 8d), rollsOf(script),
            "a gait is written out whole once the lambda returns, so its verbs read in any order");
        assertTrue(script.stances().stream()
                .allMatch(stance -> stance.limb().orElseThrow() instanceof PoseScript.Limb.Selected),
            "every stance a gait captures asks the mesh rather than naming a bone");
    }

    @Test
    @DisplayName("a second gait keeps the first one's opposed side rather than unsaying it")
    void aSecondGaitKeepsTheOpposedSide() {
        PoseScript kept = Poses.legged("amble")
            .gait(gait -> gait.oppose(0.5).step(Rank.FRONT, leg -> leg.pitchBy(10)))
            .gait(gait -> gait.step(Rank.HIND, leg -> leg.pitchBy(20)))
            .build()
            .script();
        PoseScript replaced = Poses.legged("amble")
            .gait(gait -> gait.oppose(0.5).step(Rank.FRONT, leg -> leg.pitchBy(10)))
            .gait(gait -> gait.oppose(0.25).step(Rank.HIND, leg -> leg.pitchBy(20)))
            .build()
            .script();

        assertEquals(0.5, kept.cycle().orElseThrow().opposed().orElseThrow(),
            "the offset is the style's, so a later cycle naming none leaves it standing");
        assertEquals(0.25, replaced.cycle().orElseThrow().opposed().orElseThrow(),
            "and a later cycle naming one takes the later number, as a period does");
    }

    @Test
    @DisplayName("a plant alone is enough of a cycle to capture one")
    void aPlantAloneCapturesACycle() {
        PoseScript script = Poses.legged("amble")
            .gait(gait -> gait.plant(0.4).step(leg -> leg.pitchBy(10)))
            .build()
            .script();

        assertEquals(0.4, script.cycle().orElseThrow().plantShare().orElseThrow(),
            "how a cycle is spent is a fact about the cycle, whether or not any row starts late");
    }

    @Test
    @DisplayName("a gait that says nothing about time captures no cycle at all")
    void aTimelessGaitCapturesNoCycle() {
        PoseScript script = Poses.legged("hover")
            .gait(gait -> gait.step(Rank.FRONT, leg -> leg.pitchBy(22.5)))
            .build()
            .script();

        assertEquals(Optional.empty(), script.cycle(),
            "three rests and no relationship between them is a gait with no clock in it");
    }

    /**
     * The address each captured stance was written with, in author order.
     */
    private static @NotNull List<LimbSelector> selectorsOf(@NotNull PoseScript script) {
        return script.stances().stream()
            .map(stance -> stance.limb().orElseThrow())
            .map(limb -> ((PoseScript.Limb.Selected) limb).selector())
            .toList();
    }

    /**
     * The roll each captured stance writes, in author order.
     */
    private static @NotNull List<Double> rollsOf(@NotNull PoseScript script) {
        return script.stances().stream()
            .flatMap(stance -> stance.writes().stream())
            .filter(write -> write.channel() == PoseChannel.Z_ROT)
            .map(PoseScript.Write::value)
            .toList();
    }

}
