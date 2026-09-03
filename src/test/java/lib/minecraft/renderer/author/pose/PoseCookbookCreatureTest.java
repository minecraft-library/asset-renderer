package lib.minecraft.renderer.author.pose;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.asset.pose.PoseOperator;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worked chains beyond the biped - a quadruped stance reused across two rosters, a static
 * container tilt on a flattened mesh, and a partial-roster flier on the raw-bone tier - each
 * installed or compiled against the shipped rows it was written for.
 */
@DisplayName("the quadruped and custom cookbook chains land the poses they spell")
class PoseCookbookCreatureTest {

    /**
     * The catalog period every chain frames its excursions against.
     */
    private static final int PERIOD = 24;

    /**
     * The eight strip ticks an animated schedule samples.
     */
    private static final int[] STRIP_TICKS = {0, 3, 6, 9, 12, 15, 18, 21};

    /**
     * The one-entry inventory every moving style infers.
     */
    private static final @NotNull List<PoseStyle.StyleSource> TICK_ONLY =
        List.of(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty()));

    // ------------------------------------------------------------------------------------
    // beg
    // ------------------------------------------------------------------------------------

    /**
     * Haunches folded and paws raised, head swaying, tail wagging - wolf first, cat reused.
     */
    @Nested
    @DisplayName("beg")
    class Beg {

        private final @NotNull BuiltStyle beg = Poses.quadruped("beg")
            .body(body -> body.pitch(-40))
            .hindLegs(leg -> leg.pitch(-70))
            .frontLegs(leg -> leg.pitch(-35))
            .head(head -> head.pitch(-15)
                .timeline(track -> track.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
            .tail(tail -> tail.sway(Turn.YAW, -25, 25))
            .build();

        @Test
        @DisplayName("the wolf install lowers to six held splices, one swept tail and one smooth clip")
        void wolfLoweringShape() {
            StyleRegistrar registrar = StyleRegistrar.ofShipped();
            EntityPose shipped = registrar.definitions().get("minecraft:wolf").pose();
            registrar.add("minecraft:wolf", this.beg);
            Entity woven = registrar.definitions().get("minecraft:wolf");
            PoseStyle installed = woven.styles().byId("beg").orElseThrow();

            for (String field : List.of("style$beg$body$x_rot", "style$beg$head$x_rot",
                "style$beg$left_hind_leg$x_rot", "style$beg$right_hind_leg$x_rot",
                "style$beg$left_front_leg$x_rot", "style$beg$right_front_leg$x_rot"))
                assertEquals(StyleDriver.Wave.HOLD, installed.drivers().get(field).wave(),
                    "'" + field + "' holds its stance");
            StyleDriver wag = installed.drivers().get("style$beg$tail$y_rot");
            assertEquals(StyleDriver.Wave.SWEEP, wag.wave());
            assertEquals(rad(-25), wag.rest(), "the wag starts at its near bound");
            assertEquals(rad(25), wag.extent(), "and peaks at the far one mid-period");

            assertEquals(1, woven.pose().clips().size(), "one clip joins the shipped none");
            PoseClip clip = woven.pose().clips().getFirst().clip();
            assertEquals(1.2f, clip.lengthSeconds());
            assertEquals(1, clip.channels().size());
            assertEquals("head", clip.channels().getFirst().bone());
            assertEquals(PoseClip.Interpolation.CATMULLROM,
                clip.channels().getFirst().keyframes().getFirst().interpolation(),
                "the smooth ease bakes the curved interpolation");

            PoseExpr.Op headSplice = assertInstanceOf(PoseExpr.Op.class,
                woven.pose().bones().get("head").get(PoseChannel.X_ROT));
            assertSame(shipped.bones().get("head").get(PoseChannel.X_ROT),
                headSplice.operands().getFirst(),
                "the stance lands on the head shell over the shipped instance");
            assertSame(shipped.bones().get("real_head").get(PoseChannel.Z_ROT),
                woven.pose().bones().get("real_head").get(PoseChannel.Z_ROT),
                "the inner head's shipped writes ride inside the shell untouched");
            assertEquals(TICK_ONLY, List.copyOf(installed.sources()));
        }

        @Test
        @DisplayName("the wolf lands the begging silhouette, wag and sway at their named ticks")
        void wolfSilhouetteAtNamedTicks() {
            StyleRegistrar registrar = StyleRegistrar.ofShipped();
            registrar.add("minecraft:wolf", this.beg);
            Entity woven = registrar.definitions().get("minecraft:wolf");
            PoseStyle installed = woven.styles().byId("beg").orElseThrow();

            EntityModelData start = posed(woven, installed, 0);
            assertEquals(-40, start.getBones().get("body").getRotation().pitch(), 1e-3);
            assertEquals(-15, start.getBones().get("head").getRotation().pitch(), 1e-3);
            assertEquals(-70, start.getBones().get("right_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-70, start.getBones().get("left_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-35, start.getBones().get("right_front_leg").getRotation().pitch(), 1e-3);
            assertEquals(-35, start.getBones().get("left_front_leg").getRotation().pitch(), 1e-3);
            assertEquals(-25, start.getBones().get("tail").getRotation().yaw(), 1e-3,
                "the wag rests at its near bound - the shipped stride term rests at zero");
            assertEquals(-8, start.getBones().get("head").getRotation().roll(), 1e-3,
                "the sway opens at its first keyframe");

            EntityModelData middle = posed(woven, installed, 12);
            assertEquals(25, middle.getBones().get("tail").getRotation().yaw(), 1e-3,
                "mid-period the wag peaks");
            assertEquals(8, middle.getBones().get("head").getRotation().roll(), 1e-3,
                "and the sway holds its far keyframe");
        }

        @Test
        @DisplayName("one built value serves the wolf strictly and the cat tolerantly")
        void oneValueServesTwoRosters() {
            StyleRegistrar registrar = StyleRegistrar.ofShipped();
            registrar.add("minecraft:wolf", this.beg)
                .addTolerant("minecraft:cat", this.beg);

            Entity cat = registrar.definitions().get("minecraft:cat");
            PoseStyle installed = cat.styles().byId("beg").orElseThrow();
            EntityModelData start = posed(cat, installed, 0);
            assertEquals(-15, start.getBones().get("head").getRotation().pitch(), 1e-3,
                "each roster rebases its own rests onto the same absolute targets");
            assertEquals(-40, start.getBones().get("body").getRotation().pitch(), 1e-3);
            assertEquals(-70, start.getBones().get("right_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-35, start.getBones().get("left_front_leg").getRotation().pitch(), 1e-3);
            assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                    entry.severity() == StyleDiagnostics.Severity.WARN
                        && entry.path().contains("minecraft:cat/beg")
                        && entry.message().contains("tail")),
                "the feline spells its tail otherwise, and the drop says so");
        }

        @Test
        @DisplayName("a strict cat install refuses naming the tail and the roster that lacks it")
        void strictCatInstallRefuses() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StyleRegistrar.ofShipped().add("minecraft:cat", this.beg));
            assertTrue(refused.getMessage().contains("tail"), refused.getMessage());
            assertTrue(refused.getMessage().contains("tail1"),
                "the declared roster rides along: " + refused.getMessage());
        }

        @Test
        @DisplayName("a body settle refuses on the feline - its parentless body rides a flattened mesh")
        void bodySettleRefusesOnTheFlattenedFeline() {
            BuiltStyle settle = Poses.quadruped("settle")
                .body(body -> body.pitch(-40).offset(0, 2, 0))
                .build();
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PoseCompiler.compile(settle, EntityModelLoader.load().get("minecraft:cat")));
            assertTrue(refused.getMessage().contains("'body'"), refused.getMessage());
            assertTrue(refused.getMessage().contains("0.8"),
                "the factor that cannot answer the displacement is named: " + refused.getMessage());
        }

    }

    // ------------------------------------------------------------------------------------
    // rear
    // ------------------------------------------------------------------------------------

    /**
     * The whole horse tipped up about its seat - rotation only, because the mesh is flattened.
     */
    @Nested
    @DisplayName("rear")
    class Rear {

        private final @NotNull Entity horse = EntityModelLoader.load().get("minecraft:horse");

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(
            Poses.quadruped("rear")
                .container(step -> step.pitch(-30))
                .frontLegs(leg -> leg.pitch(-65))
                .head(head -> head.pitch(25))
                .tail(tail -> tail.pitch(-30))
                .build(),
            this.horse);

        @Test
        @DisplayName("the container step is one bare rotation read - no base, no second channel")
        void containerStepIsOneBareRotationRead() {
            assertEquals(1, this.compiled.pose().container().size());
            Map<PoseChannel, PoseExpr> step = this.compiled.pose().container().getFirst();
            assertEquals(1, step.size(), "the tilt is the step's whole content");
            assertEquals("style$rear$$container$x_rot",
                assertInstanceOf(PoseExpr.Input.class, step.get(PoseChannel.X_ROT)).field(),
                "a container rests at no transform, so absolute equals additive there");
        }

        @Test
        @DisplayName("the tilt, tuck, curl and tail land as authored at tick zero")
        void landsTheRearingSilhouette() {
            EntityModelData posed = PoseKit.posed(
                this.compiled.pose(), this.horse.model(), this.compiled.style(), PERIOD, 0);
            assertEquals(-30, posed.getBones().get("$container").getRotation().pitch(), 1e-3,
                "the whole body tips about the seat");
            assertEquals(25, posed.getBones().get("head").getRotation().pitch(), 1e-3,
                "the neck curls against the tilt");
            assertEquals(-30, posed.getBones().get("tail").getRotation().pitch(), 1e-3);
            assertEquals(-65, posed.getBones().get("right_front_leg").getRotation().pitch(), 1e-3);
            assertEquals(-65, posed.getBones().get("left_front_leg").getRotation().pitch(), 1e-3,
                "forelegs tucked - a static style rebases the walk-borne bases at their rest");
            assertTrue(this.compiled.style().sources().isEmpty(), "a statue");
        }

        @Test
        @DisplayName("the haunch shift this pose wants refuses - the seat rides a flattened mesh")
        void haunchShiftRefusesOnTheFlattenedMesh() {
            BuiltStyle shifted = Poses.quadruped("rear_shift")
                .container(step -> step.pitch(-30).offset(0, 3, -5))
                .build();
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PoseCompiler.compile(shifted, this.horse));
            assertTrue(refused.getMessage().contains("1.1"),
                "the factor that cannot answer the displacement is named: " + refused.getMessage());
        }

    }

    // ------------------------------------------------------------------------------------
    // flutter
    // ------------------------------------------------------------------------------------

    /**
     * A partial humanoid on the raw-bone tier - antiphase wingbeats over a gentle hover.
     */
    @Nested
    @DisplayName("flutter")
    class Flutter {

        private final @NotNull Entity allay = StyleRegistrar.ofShipped()
            .add("minecraft:allay", Poses.custom("flutter")
                .bone("left_wing", wing -> wing.timeline(track -> track.swing(Turn.YAW, -50, 10).over(0.3)))
                .bone("right_wing", wing -> wing.timeline(track -> track.swing(Turn.YAW, 50, -10).over(0.3)))
                .bone("head", head -> head.pitchBy(-8))
                .hover(4, 1)
                .build())
            .definitions().get("minecraft:allay");

        private final @NotNull PoseStyle installed = this.allay.styles().byId("flutter").orElseThrow();

        @Test
        @DisplayName("one short clip carries both wings as two authored antiphase channels")
        void clipCarriesTheAntiphaseWings() {
            assertEquals(1, this.allay.pose().clips().size());
            PoseClip clip = this.allay.pose().clips().getFirst().clip();
            assertEquals(0.3f, clip.lengthSeconds(), "four beats tile the strip period");
            assertEquals(2, clip.channels().size());
            PoseClip.Channel left = clip.channels().getFirst();
            PoseClip.Channel right = clip.channels().getLast();
            assertEquals("left_wing", left.bone());
            assertEquals("right_wing", right.bone());
            assertEquals(rad(10), left.keyframes().get(1).y(), "the left beats toward its far bound");
            assertEquals(rad(-10), right.keyframes().get(1).y(), "the right beats against it");
        }

        @Test
        @DisplayName("at every strip tick the wings hold exact antiphase")
        void wingsMoveInAntiphase() {
            for (int tick : STRIP_TICKS) {
                EntityModelData posed = posed(this.allay, this.installed, tick);
                assertEquals(-posed.getBones().get("right_wing").getRotation().yaw(),
                    posed.getBones().get("left_wing").getRotation().yaw(), 0f,
                    "tick " + tick + ": the shipped rests mirror and the beats negate");
            }
        }

        @Test
        @DisplayName("the beat tiles at the clip length - six ticks apart, the wings repeat")
        void wingBeatTilesAtTheClipLength() {
            for (int tick : new int[] {0, 3, 9, 12}) {
                assertEquals(posed(this.allay, this.installed, tick).getBones().get("left_wing").getRotation().yaw(),
                    posed(this.allay, this.installed, tick + 6).getBones().get("left_wing").getRotation().yaw(),
                    1e-3f, "tick " + tick + " repeats a beat later");
            }
        }

        @Test
        @DisplayName("the hover appends the ordinary innermost seat and the head splice holds")
        void hoverAppendsTheOrdinarySeat() {
            assertEquals(1, this.allay.pose().container().size(),
                "no shipped clip displaces this row's seat, so the step simply appends");
            PoseExpr.Op vertical = assertInstanceOf(PoseExpr.Op.class,
                this.allay.pose().container().getFirst().get(PoseChannel.Y));
            assertEquals(PoseOperator.DADD, vertical.operator(), "the lift and the bob sum on one channel");

            assertEquals(-4f, posed(this.allay, this.installed, 0).getBones().get("$container").getPivot().y(),
                "the bob rests, leaving the lift alone");
            assertEquals(-5f, posed(this.allay, this.installed, 12).getBones().get("$container").getPivot().y(),
                "and peaks mid-period");
            StyleDriver nod = this.installed.drivers().get("style$flutter$head$x_rot");
            assertNotNull(nod, "the head carriage is one held additive splice");
            assertEquals(rad(-8), nod.extent());
            assertEquals(TICK_ONLY, List.copyOf(this.installed.sources()));
        }

    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    /**
     * One woven subject posed under its installed row at one tick.
     */
    private static @NotNull EntityModelData posed(
        @NotNull Entity woven, @NotNull PoseStyle installed, int tick) {

        return PoseKit.posed(woven.pose(), woven.model(), installed, PERIOD, tick);
    }

    /**
     * The authored degrees as the radians a driver extent carries.
     */
    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }

}
