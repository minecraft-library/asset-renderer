package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portable value's contract - inferred sources, the age default, toggle capture and the
 * reserved-id refusal.
 */
@DisplayName("a built style infers its sources and defends the reserved ids")
class BuiltStyleTest {

    @Test
    @DisplayName("each reserved id refuses at build, naming the id")
    void reservedIdsRefuse() {
        for (String id : List.of("bind", "idle", "stride", "animated")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Poses.humanoid(id).build());
            assertTrue(refused.getMessage().contains("'" + id + "'"),
                "the refusal names what was asked: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("every tier refuses a reserved id the same way")
    void everyTierRefuses() {
        assertThrows(IllegalArgumentException.class, () -> Poses.quadruped("stride").build());
        assertThrows(IllegalArgumentException.class, () -> Poses.custom("animated").build());
    }

    @Test
    @DisplayName("absolute writes, aims and a container offset alone infer a still style")
    void stillStyleInfersNoSources() {
        BuiltStyle sit = Poses.humanoid("sit")
            .preset(Preset.SITTING)
            .container(c -> c.offset(0, 7, 0))
            .build();

        assertTrue(sit.sources().isEmpty(), "a statue holds still - one frame at tick 0");
    }

    @Test
    @DisplayName("a timeline infers the clock source")
    void timelineInfersTick() {
        BuiltStyle wave = Poses.humanoid("wave")
            .arm(Side.RIGHT, a -> a.rotate(-160, 0, 10)
                .timeline(t -> t.swing(Turn.ROLL, -20, 20).over(0.6).ease(Ease.SMOOTH)))
            .build();

        assertEquals(tickOnly(), List.copyOf(wave.sources()));
    }

    @Test
    @DisplayName("a sway infers the clock source")
    void swayInfersTick() {
        BuiltStyle beg = Poses.quadruped("beg")
            .tail(t -> t.sway(Turn.YAW, -25, 25))
            .build();

        assertEquals(tickOnly(), List.copyOf(beg.sources()));
    }

    @Test
    @DisplayName("a spin infers the clock source")
    void spinInfersTick() {
        BuiltStyle top = Poses.custom("top")
            .bone("head", h -> h.spin(Turn.YAW, 360))
            .build();

        assertEquals(tickOnly(), List.copyOf(top.sources()));
    }

    @Test
    @DisplayName("riding the stride infers the clock source")
    void keepStrideInfersTick() {
        BuiltStyle jog = Poses.humanoid("jog")
            .keepStride()
            .arms(a -> a.pitchBy(-20))
            .build();

        assertEquals(tickOnly(), List.copyOf(jog.sources()));
    }

    @Test
    @DisplayName("a nonzero hover bob infers the clock source, a zero bob stays still")
    void hoverBobDecidesMotion() {
        BuiltStyle levitate = Poses.humanoid("levitate").hover(8, 2).build();
        BuiltStyle perch = Poses.humanoid("perch").hover(4, 0).build();

        assertEquals(tickOnly(), List.copyOf(levitate.sources()));
        assertTrue(perch.sources().isEmpty(), "a still lift renders one frame");
    }

    @Test
    @DisplayName("the age defaults to adult, age() restricts and allAges() opts out")
    void ageDefaultsToAdult() {
        assertEquals(Optional.of(Age.ADULT), Poses.humanoid("sit").build().age());
        assertEquals(Optional.of(Age.BABY), Poses.humanoid("nap").age(Age.BABY).build().age());
        assertEquals(Optional.empty(), Poses.humanoid("t_pose").allAges().build().age());
    }

    @Test
    @DisplayName("toggles append in call order")
    void togglesAppendInOrder() {
        BuiltStyle croak = Poses.custom("puff")
            .toggles("sac", "brow")
            .toggles("crest")
            .build();

        assertEquals(List.of("sac", "brow", "crest"), List.copyOf(croak.toggles()));
    }

    @Test
    @DisplayName("the built value carries its id and the captured script")
    void carriesIdAndScript() {
        BuiltStyle sit = Poses.humanoid("sit")
            .preset(Preset.SITTING)
            .container(c -> c.offset(0, 7, 0))
            .build();

        assertEquals("sit", sit.styleId());
        assertTrue(sit.script().stances().stream().anyMatch(stance -> stance.limb().isEmpty()),
            "the container step rides the script");
        assertTrue(sit.toggles().isEmpty());
    }

    /**
     * The one-entry inventory every moving style infers.
     */
    private static List<PoseStyle.StyleSource> tickOnly() {
        return List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()));
    }

}
