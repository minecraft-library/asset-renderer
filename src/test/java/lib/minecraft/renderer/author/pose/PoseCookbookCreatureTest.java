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
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worked chains beyond the biped - a quadruped stance reused across two rosters, a static
 * body tilt on a flattened mesh, and a partial-roster flier on the raw-bone tier - each
 * installed or compiled against the shipped rows it was written for, and the two creature
 * chains laid bone for bone against vanilla's own silhouette of the stance they spell.
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
     * Vanilla's own sitting branch through the quadruped verbs and one bone name - the body
     * settled and pitched to forty-five, the mane settled two down and pitched to seventy-two,
     * the hips folded flat, the paws pitched back a hair and one down - with the head swaying
     * and the tail wagging over it; wolf first, cat reused.
     */
    @Nested
    @DisplayName("beg")
    class Beg {

        private final @NotNull BuiltStyle beg = Poses.quadruped("beg")
            .body(body -> body.pitch(45).offset(0, 4, -2))
            .bone("upper_body", mane -> mane.pitch(72).offset(0, 2, 0))
            .hindLegs(leg -> leg.pitch(-90))
            .frontLegs(leg -> leg.pitch(-27).offset(0, 1, 0))
            .head(head -> head.pitch(-15)
                .timeline(track -> track.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
            .tail(tail -> tail.sway(Turn.YAW, -25, 25))
            .build();

        @Test
        @DisplayName("the wolf install lowers to held splices, one swept tail, one smooth clip and three seated rides")
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
            for (String field : List.of("style$beg$body$y", "style$beg$body$z",
                "style$beg$left_front_leg$y", "style$beg$right_front_leg$y"))
                assertEquals(StyleDriver.Wave.HOLD, installed.drivers().get(field).wave(),
                    "'" + field + "' holds its settle");
            StyleDriver wag = installed.drivers().get("style$beg$tail$y_rot");
            assertEquals(StyleDriver.Wave.SWEEP, wag.wave());
            assertEquals(rad(-25), wag.rest(), "the wag starts at its near bound");
            assertEquals(rad(25), wag.extent(), "and peaks at the far one mid-period");

            // The tail and the hind legs ride the body's frame - seats the sitting silhouette
            // derives - so each takes a held displacement on the two axes the pitch carries it
            // along, spliced over the shipped read of its authored pivot, and nothing sideways.
            for (String seated : List.of("tail", "right_hind_leg", "left_hind_leg")) {
                for (String token : List.of("y", "z")) {
                    String field = "style$beg$" + seated + "$" + token;
                    assertEquals(StyleDriver.Wave.HOLD, installed.drivers().get(field).wave(),
                        "'" + field + "' carries the seat");
                }
                assertNull(installed.drivers().get("style$beg$" + seated + "$x"),
                    "a pitch carries nothing sideways, so no field is spelled for it");
                PoseExpr.Op ride = assertInstanceOf(PoseExpr.Op.class,
                    woven.pose().bones().get(seated).get(PoseChannel.Y));
                assertSame(shipped.bones().get(seated).get(PoseChannel.Y), ride.operands().getFirst(),
                    "the seat splices over the shipped instance, never a rebuilt one");
            }
            // The mane is placed by hand in every state vanilla poses and rides nothing, so its
            // two channels are the chain's own held writes rather than a seat's carry.
            for (String token : List.of("x_rot", "y"))
                assertEquals(StyleDriver.Wave.HOLD, installed.drivers().get("style$beg$upper_body$" + token).wave(),
                    "'style$beg$upper_body$" + token + "' holds the mane where the chain names it");
            assertNull(installed.drivers().get("style$beg$upper_body$z"),
                "a settle straight down spells no field along the axis it does not move");

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
            assertEquals(45, start.getBones().get("body").getRotation().pitch(), 1e-3);
            assertPivot(start.getBones().get("body").getPivot(), 0f, 18f, 0f, "the body settles four down and two forward");
            assertEquals(-15, start.getBones().get("head").getRotation().pitch(), 1e-3);
            assertEquals(-90, start.getBones().get("right_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-90, start.getBones().get("left_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-27, start.getBones().get("right_front_leg").getRotation().pitch(), 1e-3);
            assertEquals(-27, start.getBones().get("left_front_leg").getRotation().pitch(), 1e-3);
            assertPivot(start.getBones().get("right_front_leg").getPivot(), -2.5f, 17f, -4f, "the paw settles one down");
            assertEquals(-25, start.getBones().get("tail").getRotation().yaw(), 1e-3,
                "the wag rests at its near bound - the shipped stride term rests at zero");
            assertEquals(-8, start.getBones().get("head").getRotation().roll(), 1e-3,
                "the sway opens at its first keyframe");

            // The tail's seat sits six pixels down the body's own axis and two above it; pitched
            // to 45 and settled, that frame carries the seat to within four tenths of a pixel of
            // where vanilla's sitting branch places the tail by hand, (-1, 21, 6), its own pitch
            // kept - and the hip's seat, five down the axis and two above, to within three tenths
            // of vanilla's (-2.5, 22.7, 2).
            assertPivot(start.getBones().get("tail").getPivot(), -1f, 20.828f, 5.657f, "the tail rides the seated body");
            assertEquals(36, start.getBones().get("tail").getRotation().pitch(), 1e-3,
                "a seat carries position only - the tail keeps its own pitch");
            assertPivot(start.getBones().get("right_hind_leg").getPivot(), -2.5f, 22.950f, 2.121f,
                "the hind leg folds flat under the seated haunch");
            assertPivot(start.getBones().get("upper_body").getPivot(), -1f, 16f, -3f,
                "the mane is no follower and settles where the chain names it");
            assertEquals(72, start.getBones().get("upper_body").getRotation().pitch(), 1e-3);

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
            assertEquals(45, start.getBones().get("body").getRotation().pitch(), 1e-3);
            assertEquals(-90, start.getBones().get("right_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(-27, start.getBones().get("left_front_leg").getRotation().pitch(), 1e-3);
            assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                    entry.severity() == StyleDiagnostics.Severity.WARN
                        && entry.path().contains("minecraft:cat/beg")
                        && entry.message().contains("tail")
                        && entry.message().contains("upper_body")),
                "the feline spells its tail otherwise and carries no mane at all, and the drop says so");
        }

        @Test
        @DisplayName("the beg lands vanilla's own sitting branch bone for bone - the seats within four tenths of a pixel of the hand placements, the head and the wag its own")
        void begLandsVanillasSittingBranch() {
            Entity wolf = EntityModelLoader.load().get("minecraft:wolf");
            EntityModelData beg = compiled(this.beg, wolf);
            EntityModelData sitting = compiled(RegistrarFixtures.silhouette(wolf, "isSitting=true", "sitting"), wolf);

            for (String bone : List.of("body", "upper_body", "right_front_leg", "left_front_leg", "real_head", "real_tail")) {
                assertPivotWithin(beg, sitting, bone, 0.011f,
                    "spelled at vanilla's own numbers, save the hundredth of a pixel the paws splay");
                assertTurnAlike(beg, sitting, bone, "spelled at vanilla's own angles");
            }
            // The seats carry the body's exact frame; vanilla places the same parts by hand in
            // whole pixels, and the difference is the seat's residual, not a pose of the author's.
            for (String bone : List.of("right_hind_leg", "left_hind_leg", "tail"))
                assertPivotWithin(beg, sitting, bone, 0.4f, "the seat lands within the hand placement's rounding");
            for (String bone : List.of("right_hind_leg", "left_hind_leg"))
                assertTurnAlike(beg, sitting, bone, "the hips fold at vanilla's own angle");
            assertEquals(sitting.getBones().get("tail").getRotation().pitch(),
                beg.getBones().get("tail").getRotation().pitch(), 1e-3,
                "the wag is the tail's one difference - its pitch is vanilla's");
            assertEquals(-25, beg.getBones().get("tail").getRotation().yaw(), 1e-3);
            assertEquals(0, sitting.getBones().get("tail").getRotation().yaw(), 1e-3);
            // The mane sits outside the quadruped roster and is named as the mesh names it, which
            // is what carries the branch's own settle and pitch onto the chain.
            assertPivot(beg.getBones().get("upper_body").getPivot(), -1f, 16f, -3f, "the mane settles two down");
            assertEquals(72, beg.getBones().get("upper_body").getRotation().pitch(), 1e-3);
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
        @DisplayName("a body settle lands on the feline - its parentless body crosses the flattened factor and the feet anchor once")
        void bodySettleLandsOnTheFlattenedFeline() {
            Entity cat = EntityModelLoader.load().get("minecraft:cat");
            float factor = cat.model().getFlattenedScale();
            float authored = cat.model().getBones().get("body").getPivot().y();
            BuiltStyle settle = Poses.quadruped("settle")
                .body(body -> body.pitch(-40).offset(0, 2, 0))
                .build();

            PoseCompiler.Compiled compiled = PoseCompiler.compile(settle, cat);

            assertEquals(2f / factor, compiled.style().drivers().get("style$settle$body$y").extent(), 1e-6f,
                "the surface pixels divide the factor once");
            EntityModelData posed = PoseKit.posed(compiled.pose(), cat.model(), compiled.style(), 24, 0);
            assertEquals(authored + 2f, posed.getBones().get("body").getPivot().y(), 1e-3f,
                "and the write-back puts the factor and the anchor back, landing the two pixels");
        }

    }

    // ------------------------------------------------------------------------------------
    // rear
    // ------------------------------------------------------------------------------------

    /**
     * Vanilla's own standing branch through the quadruped verbs - the body pitched up forty-five
     * about its own pivot, the neck lifted and drawn back, the forelegs lifted and pawing, the
     * hind legs braced - on a mesh flattened at 1.1, where the surface spells vanilla's field
     * pixels scaled by the factor.
     */
    @Nested
    @DisplayName("rear")
    class Rear {

        private final @NotNull Entity horse = EntityModelLoader.load().get("minecraft:horse");

        private final @NotNull BuiltStyle rear = Poses.quadruped("rear")
            .body(body -> body.pitch(-45))
            .head(head -> head.pitch(15).offset(0, -8.8, 8.8))
            .leg(Corner.FRONT_LEFT, leg -> leg.pitch(-117.3).offset(0, -13.2, 4.4))
            .leg(Corner.FRONT_RIGHT, leg -> leg.pitch(-2.7).offset(0, -13.2, 4.4))
            .hindLegs(leg -> leg.pitch(15))
            .build();

        private final PoseCompiler.@NotNull Compiled compiled = PoseCompiler.compile(this.rear, this.horse);

        @Test
        @DisplayName("a container tilt is one bare rotation read - no base, no second channel")
        void containerTiltIsOneBareRotationRead() {
            PoseCompiler.Compiled tilt = PoseCompiler.compile(
                Poses.quadruped("tilt").container(step -> step.pitch(-30)).build(), this.horse);
            assertEquals(1, tilt.pose().container().size());
            Map<PoseChannel, PoseExpr> step = tilt.pose().container().getFirst();
            assertEquals(1, step.size(), "the tilt is the step's whole content");
            assertEquals("style$tilt$$container$x_rot",
                assertInstanceOf(PoseExpr.Input.class, step.get(PoseChannel.X_ROT)).field(),
                "a container rests at no transform, so absolute equals additive there");
        }

        @Test
        @DisplayName("the tilt, lift, curl and paws land as authored at tick zero - the curl on the neck assembly, the tail written nothing")
        void landsTheRearingSilhouette() {
            float factor = this.horse.model().getFlattenedScale();
            EntityModelData posed = PoseKit.posed(
                this.compiled.pose(), this.horse.model(), this.compiled.style(), PERIOD, 0);
            assertTrue(this.compiled.pose().container().isEmpty(),
                "the body tips and the container holds still, so the hind legs stay planted");
            assertEquals(-45, posed.getBones().get("body").getRotation().pitch(), 1e-3,
                "the body tips up about its own pivot");
            assertEquals(15, posed.getBones().get("head_parts").getRotation().pitch(), 1e-3,
                "the neck curls against the tilt - the head verb lands on the articulation the pose turns");
            assertEquals(0, posed.getBones().get("head").getRotation().pitch(), 1e-3,
                "the head cube is untouched and rides the assembly with the snout, mane and ears");
            assertAuthored(posed.getBones().get("head_parts"), factor, 0f, -4f, -4f,
                "the neck lifts eight and draws back eight in vanilla's field pixels - the surface spelled them scaled");
            assertNull(this.compiled.style().drivers().get("style$rear$tail$x_rot"),
                "the tail is written nothing - a real child of the body, it rises with the tilt");
            assertEquals(30, posed.getBones().get("tail").getRotation().pitch(), 1e-3,
                "and keeps its shipped pitch");
            assertEquals(-117.3, posed.getBones().get("left_front_leg").getRotation().pitch(), 1e-3);
            assertEquals(-2.7, posed.getBones().get("right_front_leg").getRotation().pitch(), 1e-3,
                "the forelegs paw a radian either side of sixty - vanilla alternates them with age, and tick zero holds this frame");
            for (String leg : List.of("left_front_leg", "right_front_leg"))
                assertAuthored(posed.getBones().get(leg), factor, leg.startsWith("left") ? 4f : -4f, 2f, -6f,
                    "the foreleg lifts twelve and draws back four in vanilla's field pixels");
            assertEquals(15, posed.getBones().get("left_hind_leg").getRotation().pitch(), 1e-3);
            assertEquals(15, posed.getBones().get("right_hind_leg").getRotation().pitch(), 1e-3,
                "the hind legs brace");
            assertTrue(this.compiled.style().sources().isEmpty(), "a statue");
        }

        @Test
        @DisplayName("the rear lands vanilla's own standing branch bone for bone - no seat derives on the horse, so every placement is the surface's own crossing of the factor and the anchor")
        void rearLandsVanillasStandingBranch() {
            EntityModelData rear = compiled(this.rear, this.horse);
            EntityModelData standing = compiled(
                RegistrarFixtures.silhouette(this.horse, "standAnimation=1", "standing"), this.horse);

            for (String bone : this.horse.model().getBones().keySet()) {
                assertPivotWithin(rear, standing, bone, 2e-3f, "spelled at vanilla's own numbers");
                assertTurnAlike(rear, standing, bone, "spelled at vanilla's own angles");
            }
        }

        @Test
        @DisplayName("a haunch shift on the container refuses - the seat rides a flattened mesh")
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
     * One style compiled against a shipped row and posed at tick zero - the lowering by itself,
     * without the catalog row and overlay weave an install adds around it.
     */
    private static @NotNull EntityModelData compiled(@NotNull BuiltStyle style, @NotNull Entity row) {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(style, row);
        return PoseKit.posed(compiled.pose(), row.model(), compiled.style(), PERIOD, 0);
    }

    /**
     * The authored degrees as the radians a driver extent carries.
     */
    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    /**
     * One pivot against its expected components, within the rounding of a rotate and its inverse.
     */
    private static void assertPivot(@NotNull Vector3f pivot, float x, float y, float z, @NotNull String message) {
        assertEquals(x, pivot.x(), 2e-3, message + " (x)");
        assertEquals(y, pivot.y(), 2e-3, message + " (y)");
        assertEquals(z, pivot.z(), 2e-3, message + " (z)");
    }

    /**
     * One bone's pivot in the units vanilla's own fields speak - the factor and the feet anchor
     * taken back off - against its expected components.
     */
    private static void assertAuthored(
        @NotNull EntityModelData.Bone bone, float factor, float x, float y, float z, @NotNull String message) {

        assertEquals(x, PoseKit.authored(bone, PoseChannel.X, factor), 2e-3, message + " (x)");
        assertEquals(y, PoseKit.authored(bone, PoseChannel.Y, factor), 2e-3, message + " (y)");
        assertEquals(z, PoseKit.authored(bone, PoseChannel.Z, factor), 2e-3, message + " (z)");
    }

    /**
     * One bone's pivot against the same bone's under another posing, within a distance.
     */
    private static void assertPivotWithin(
        @NotNull EntityModelData posed, @NotNull EntityModelData against, @NotNull String bone,
        float pixels, @NotNull String message) {

        Vector3f a = posed.getBones().get(bone).getPivot();
        Vector3f b = against.getBones().get(bone).getPivot();
        assertTrue(a.subtract(b).length() <= pixels,
            message + ": '" + bone + "' at " + a + " against " + b);
    }

    /**
     * One bone's rotation against the same bone's under another posing - each channel equal
     * modulo a full turn, within a hundredth of a degree.
     */
    private static void assertTurnAlike(
        @NotNull EntityModelData posed, @NotNull EntityModelData against, @NotNull String bone,
        @NotNull String message) {

        EulerRotation a = posed.getBones().get(bone).getRotation();
        EulerRotation b = against.getBones().get(bone).getRotation();
        assertEquals(0, turn(a.pitch() - b.pitch()), 1e-2, message + ": '" + bone + "' pitch " + a.pitch() + " against " + b.pitch());
        assertEquals(0, turn(a.yaw() - b.yaw()), 1e-2, message + ": '" + bone + "' yaw " + a.yaw() + " against " + b.yaw());
        assertEquals(0, turn(a.roll() - b.roll()), 1e-2, message + ": '" + bone + "' roll " + a.roll() + " against " + b.roll());
    }

    /**
     * A signed angle difference folded into a half turn either way.
     */
    private static float turn(float degrees) {
        return ((degrees % 360f) + 540f) % 360f - 180f;
    }

}
