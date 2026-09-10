package lib.minecraft.renderer.pose.author;

import lib.minecraft.renderer.pose.PoseChannel;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The humanoid tier's verb table - bone mappings, hat auto-mirroring, the mirror sign rule on
 * stance and timeline values, and the preset-then-adjust funnel.
 */
@DisplayName("the humanoid builder maps, mirrors and stamps the canonical seven")
class HumanoidPoseTest {

    @Test
    @DisplayName("selectors land on the canonical bones with their aim-axis stamps")
    void selectorsMapTheCanonicalSeven() {
        PoseScript script = Poses.humanoid("map")
            .hat(h -> h.yaw(5))
            .head(h -> h.pitch(30))
            .torso(t -> t.pitchBy(12))
            .arm(Side.LEFT, a -> a.roll(-35))
            .leg(Side.RIGHT, l -> l.pitch(-90))
            .build()
            .script();

        assertEquals(PoseScript.AimAxis.FACING, stanceOf(script, "head").limb().orElseThrow().axis());
        assertEquals(PoseScript.AimAxis.FACING, stanceOf(script, "hat").limb().orElseThrow().axis());
        assertEquals(PoseScript.AimAxis.DOWN, stanceOf(script, "body").limb().orElseThrow().axis(),
            "torso addresses the bone the mesh names body");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Z_ROT, -35, true)),
            List.copyOf(stanceOf(script, "left_arm").writes()));
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, -90, true)),
            List.copyOf(stanceOf(script, "right_leg").writes()));
    }

    @Test
    @DisplayName("a head stance stamps the hat at build, sharing the captured fragments")
    void headAutoMirrorsOntoHat() {
        PoseScript script = Poses.humanoid("nod")
            .head(h -> h.pitch(30).yaw(15))
            .build()
            .script();

        PoseScript.Stance head = stanceOf(script, "head");
        PoseScript.Stance hat = stanceOf(script, "hat");
        assertSame(head.writes(), hat.writes(), "the hat copy shares the head's fragments, values untouched");
        assertEquals(PoseScript.AimAxis.FACING, hat.limb().orElseThrow().axis());
    }

    @Test
    @DisplayName("an authored hat claims the shell - no auto-copy joins it")
    void authoredHatClaimsTheShell() {
        PoseScript script = Poses.humanoid("tip")
            .hat(h -> h.yaw(5))
            .head(h -> h.pitch(30))
            .build()
            .script();

        List<PoseScript.Stance> hats = stancesOf(script, "hat");
        assertEquals(1, hats.size(), "the authored stance is the hat's whole story");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Y_ROT, 5, true)),
            List.copyOf(hats.getFirst().writes()));
    }

    @Test
    @DisplayName("no head stance means no hat stamp")
    void noHeadMeansNoHat() {
        PoseScript script = Poses.humanoid("lean")
            .torso(t -> t.pitchBy(12))
            .build()
            .script();

        assertTrue(stancesOf(script, "hat").isEmpty());
    }

    @Test
    @DisplayName("arms stamps the right as authored and the left under x, -y, -z")
    void armsStampTheMirrorPair() {
        PoseScript script = Poses.humanoid("clap")
            .arms(a -> a.pitch(-90).yaw(-10).roll(5).offset(1, 2, 3))
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -90, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -10, true),
                new PoseScript.Write(PoseChannel.Z_ROT, 5, true),
                new PoseScript.Write(PoseChannel.X, 1, false),
                new PoseScript.Write(PoseChannel.Y, 2, false),
                new PoseScript.Write(PoseChannel.Z, 3, false)),
            List.copyOf(stanceOf(script, "right_arm").writes()), "the authored side as given");
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -90, true),
                new PoseScript.Write(PoseChannel.Y_ROT, 10, true),
                new PoseScript.Write(PoseChannel.Z_ROT, -5, true),
                new PoseScript.Write(PoseChannel.X, -1, false),
                new PoseScript.Write(PoseChannel.Y, 2, false),
                new PoseScript.Write(PoseChannel.Z, 3, false)),
            List.copyOf(stanceOf(script, "left_arm").writes()),
            "pitch kept, yaw and roll negated, the sideways offset crossed");
    }

    @Test
    @DisplayName("the pair lambda runs once - the left stance is derived, never re-captured")
    void pairLambdaRunsOnce() {
        AtomicInteger runs = new AtomicInteger();
        PoseScript script = Poses.humanoid("count")
            .legs(l -> {
                runs.incrementAndGet();
                return l.pitch(-6);
            })
            .build()
            .script();

        assertEquals(1, runs.get());
        assertEquals(-6, folded(script, "right_leg", PoseChannel.X_ROT));
        assertEquals(-6, folded(script, "left_leg", PoseChannel.X_ROT), "pitch survives the mirror unsigned");
    }

    @Test
    @DisplayName("the mirror applies to timeline values - the left swing negates yaw and roll")
    void mirrorAppliesToTimelineValues() {
        PoseScript script = Poses.humanoid("clap")
            .arms(a -> a.timeline(t -> t
                .swing(Turn.YAW, 0, -25)
                .bob(2)
                .keyframe(0.3, 10, 5, -5)
                .shift(0.6, 1, -2, 3)
                .over(0.4)
                .ease(Ease.SMOOTH)
                .once()))
            .build()
            .script();

        PoseScript.Track right = stanceOf(script, "right_arm").tracks().getFirst();
        PoseScript.Track left = stanceOf(script, "left_arm").tracks().getFirst();
        assertEquals(List.of(
                new PoseScript.Swing(Turn.YAW, 0, -25),
                new PoseScript.Bob(2),
                new PoseScript.Keyframe(0.3, 10, 5, -5),
                new PoseScript.Shift(0.6, 1, -2, 3)),
            List.copyOf(right.motions()));
        assertEquals(List.of(
                new PoseScript.Swing(Turn.YAW, 0, 25),
                new PoseScript.Bob(2),
                new PoseScript.Keyframe(0.3, 10, -5, 5),
                new PoseScript.Shift(0.6, -1, -2, 3)),
            List.copyOf(left.motions()), "yaw and roll negate, the bob and the vertical shift hold");
        assertEquals(right.overSeconds(), left.overSeconds());
        assertEquals(right.ease(), left.ease());
        assertEquals(right.looping(), left.looping());
    }

    @Test
    @DisplayName("the mirror applies to sway, spin and aim - pitch holds, the rest negates")
    void mirrorAppliesToProceduralMotion() {
        PoseScript script = Poses.humanoid("drift")
            .arms(a -> a.sway(Turn.ROLL, -8, 8).sway(Turn.PITCH, -5, 5).spin(Turn.YAW, 360).aimAt(18, -30, -14))
            .build()
            .script();

        PoseScript.Stance left = stanceOf(script, "left_arm");
        assertEquals(List.of(
                new PoseScript.Sway(Turn.ROLL, 8, -8),
                new PoseScript.Sway(Turn.PITCH, -5, 5)),
            List.copyOf(left.sways()), "the roll bounds negate, the pitch sway rides unchanged");
        assertEquals(List.of(new PoseScript.Spin(Turn.YAW, -360)), List.copyOf(left.spins()));
        assertEquals(List.of(new PoseScript.Aim(-18, -30, -14)), List.copyOf(left.aims()),
            "the aim target crosses the centre plane");
    }

    @Test
    @DisplayName("mirrorArms copies the source's stances so far onto the other arm")
    void mirrorArmsCopies() {
        PoseScript script = Poses.humanoid("salute")
            .arm(Side.RIGHT, a -> a.rotate(-124, -51, -35))
            .mirrorArms(Side.RIGHT)
            .arm(Side.RIGHT, a -> a.yaw(10))
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -124, true),
                new PoseScript.Write(PoseChannel.Y_ROT, 51, true),
                new PoseScript.Write(PoseChannel.Z_ROT, 35, true)),
            List.copyOf(stanceOf(script, "left_arm").writes()));
        assertEquals(1, stancesOf(script, "left_arm").size(),
            "a source stance authored after the copy does not follow");
        assertEquals(-124, folded(script, "right_arm", PoseChannel.X_ROT), "the source is untouched");
    }

    @Test
    @DisplayName("mirrorLegs copies from either side")
    void mirrorLegsCopies() {
        PoseScript script = Poses.humanoid("cross")
            .leg(Side.LEFT, l -> l.rotate(1, 2, 3))
            .mirrorLegs(Side.LEFT)
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, 1, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -2, true),
                new PoseScript.Write(PoseChannel.Z_ROT, -3, true)),
            List.copyOf(stanceOf(script, "right_leg").writes()));
    }

    @Test
    @DisplayName("flip swaps the pairs under the sign rule and mirrors the centred stances in place")
    void flipMirrorsTheWholePose() {
        PoseScript script = Poses.humanoid("dab")
            .arm(Side.RIGHT, a -> a.rotate(-160, 35, 10))
            .leg(Side.LEFT, l -> l.pitch(-30).yawBy(4))
            .head(h -> h.yaw(15))
            .torso(t -> t.rollBy(4))
            .container(c -> c.yaw(10).offset(1, 0, 0))
            .flip()
            .arm(Side.RIGHT, a -> a.pitch(-10))
            .build()
            .script();

        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -160, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -35, true),
                new PoseScript.Write(PoseChannel.Z_ROT, -10, true)),
            List.copyOf(stanceOf(script, "left_arm").writes()), "the right arm crossed sides mirrored");
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.X_ROT, -30, true),
                new PoseScript.Write(PoseChannel.Y_ROT, -4, false)),
            List.copyOf(stanceOf(script, "right_leg").writes()), "the left leg crossed sides mirrored");
        assertEquals(-15, folded(script, "head", PoseChannel.Y_ROT), "the head mirrors in place");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.Z_ROT, -4, false)),
            List.copyOf(stanceOf(script, "body").writes()), "the torso mirrors in place");

        PoseScript.Stance step = script.stances().stream()
            .filter(stance -> stance.limb().isEmpty())
            .findFirst().orElseThrow();
        assertEquals(List.of(
                new PoseScript.Write(PoseChannel.Y_ROT, -10, true),
                new PoseScript.Write(PoseChannel.X, -1, false),
                new PoseScript.Write(PoseChannel.Y, 0, false),
                new PoseScript.Write(PoseChannel.Z, 0, false)),
            List.copyOf(step.writes()), "the container step mirrors in place");
        assertEquals(List.of(new PoseScript.Write(PoseChannel.X_ROT, -10, true)),
            List.copyOf(stanceOf(script, "right_arm").writes()), "a stance authored after the flip is unaffected");
    }

    @Test
    @DisplayName("a preset is a total stamp - all six limb triples land as absolute writes")
    void presetStampsAllSixTriples() {
        PoseScript script = Poses.humanoid("sit")
            .preset(Preset.SITTING)
            .build()
            .script();

        assertEquals(-80, folded(script, "right_arm", PoseChannel.X_ROT));
        assertEquals(20, folded(script, "right_arm", PoseChannel.Y_ROT));
        assertEquals(-80, folded(script, "left_arm", PoseChannel.X_ROT));
        assertEquals(-20, folded(script, "left_arm", PoseChannel.Y_ROT));
        assertEquals(-90, folded(script, "right_leg", PoseChannel.X_ROT));
        assertEquals(10, folded(script, "right_leg", PoseChannel.Y_ROT));
        assertEquals(-90, folded(script, "left_leg", PoseChannel.X_ROT));
        assertEquals(-10, folded(script, "left_leg", PoseChannel.Y_ROT));
        assertEquals(0, folded(script, "head", PoseChannel.X_ROT),
            "a zero triple is three absolute zero writes, not an omission");
        assertEquals(0, folded(script, "body", PoseChannel.Z_ROT));
        assertEquals(0, folded(script, "hat", PoseChannel.X_ROT), "the preset's head triple reaches the hat");
    }

    @Test
    @DisplayName("a later absolute write supersedes the preset's in the fold")
    void laterWriteSupersedesThePreset() {
        PoseScript script = Poses.humanoid("point")
            .preset(Preset.POINTING)
            .arm(Side.RIGHT, a -> a.yaw(25))
            .build()
            .script();

        assertEquals(25, folded(script, "right_arm", PoseChannel.Y_ROT),
            "the adjustment wins over the preset's 18");
        assertEquals(-90, folded(script, "right_arm", PoseChannel.X_ROT),
            "the untouched channels keep the preset's values");
        assertEquals(20, folded(script, "head", PoseChannel.Y_ROT));
        assertEquals(-10, folded(script, "left_arm", PoseChannel.Z_ROT));
    }

    /**
     * The stances addressing one bone, in capture order.
     */
    private static @NotNull List<PoseScript.Stance> stancesOf(@NotNull PoseScript script, @NotNull String bone) {
        return script.stances().stream()
            .filter(stance -> stance.limb().flatMap(PoseScript.Limb::named)
                .filter(bone::equals).isPresent())
            .toList();
    }

    /**
     * The last stance addressing one bone.
     */
    private static @NotNull PoseScript.Stance stanceOf(@NotNull PoseScript script, @NotNull String bone) {
        return stancesOf(script, bone).getLast();
    }

    /**
     * The value the last absolute write on one channel leaves - what a lowering fold lands on.
     */
    private static double folded(@NotNull PoseScript script, @NotNull String bone, @NotNull PoseChannel channel) {
        double value = Double.NaN;

        for (PoseScript.Stance stance : stancesOf(script, bone)) {
            for (PoseScript.Write write : stance.writes()) {
                if (write.channel() == channel && write.absolute())
                    value = write.value();
            }
        }

        return value;
    }

}
