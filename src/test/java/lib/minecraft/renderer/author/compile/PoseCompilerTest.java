package lib.minecraft.renderer.author.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Gait;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Rank;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.bake.mesh.EntityGeometryKit;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.PoseOperator;
import lib.minecraft.renderer.engine.pose.PoseWidth;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.fixture.CompilerFixtures;
import lib.minecraft.renderer.math.Matrix4f;
import lib.minecraft.renderer.math.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static lib.minecraft.renderer.fixture.CompilerFixtures.bone;
import static lib.minecraft.renderer.fixture.CompilerFixtures.boneWrite;
import static lib.minecraft.renderer.fixture.CompilerFixtures.chainAt;
import static lib.minecraft.renderer.fixture.CompilerFixtures.constant;
import static lib.minecraft.renderer.fixture.CompilerFixtures.crossedSides;
import static lib.minecraft.renderer.fixture.CompilerFixtures.dadd;
import static lib.minecraft.renderer.fixture.CompilerFixtures.drawnScale;
import static lib.minecraft.renderer.fixture.CompilerFixtures.flattened;
import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.input;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.CompilerFixtures.row;
import static lib.minecraft.renderer.fixture.CompilerFixtures.walker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lowering rules measured through the posing seam - evaluated channel values at given ticks
 * against hand-replicated expected radians, plus the field grammar, width and unit pins the
 * compiled records must hold.
 */
@DisplayName("the compiler lowers a built style into splices the runtime evaluates as authored")
class PoseCompilerTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    /** How near a drawn scale or a chain's translation comes to count as the derived value. */
    private static final float DRAWN = 1e-5f;

    @Test
    @DisplayName("an absolute write rebases against the evaluated rest and lands the stated angle")
    void absoluteWriteRebasesAgainstTheRest() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("t_pose").arm(Side.RIGHT, arm -> arm.roll(90)).build(),
            row(mesh, EntityPose.NONE));

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        StyleDriver driver = compiled.style().drivers().get("style$t_pose$right_arm$z_rot");
        assertNotNull(driver, "one channel splice takes one style-namespaced field");
        assertEquals(StyleDriver.Wave.HOLD, driver.wave(), "a held stance is a held extent");
        assertEquals(extentOf(90, rest), driver.extent(), "the extent is the rebased delta, narrowed once");

        float landed = posed(compiled, mesh, 0).getBones().get("right_arm").getRotation().roll();
        assertEquals(degreesOf(rest, driver.extent()), landed, "the sum of rest and delta is the write-back");
        assertEquals(90f, landed, 1e-4f, "and the sum lands the absolute target, not the extent");
    }

    @Test
    @DisplayName("a channel the shipped pose writes is spliced over the shipped instance itself")
    void shippedBaseIsReferencedByInstance() {
        PoseExpr shippedExpr = dadd(constant(0.3d), constant(0d));
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, shippedExpr);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean").arm(Side.RIGHT, arm -> arm.pitch(-40)).build(),
            row(humanoid(), shipped));

        PoseExpr woven = compiled.pose().bones().get("right_arm").get(PoseChannel.X_ROT);
        PoseExpr.Op splice = assertInstanceOf(PoseExpr.Op.class, woven);
        assertEquals(PoseOperator.DADD, splice.operator(), "the splice sums at double width");
        assertSame(shippedExpr, splice.operands().getFirst(),
            "the base is the shipped instance, never a rebuilt duplicate");
        PoseExpr.Input field = assertInstanceOf(PoseExpr.Input.class, splice.operands().getLast());
        assertEquals("style$lean$right_arm$x_rot", field.field());
        assertEquals(extentOf(-40, 0.3f),
            compiled.style().drivers().get("style$lean$right_arm$x_rot").extent(),
            "the rebase reads the shipped expression's evaluated rest");
    }

    @Test
    @DisplayName("an absolute write that rebases to a zero delta elides whole")
    void zeroDeltaElidesTheWrite() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("still").head(head -> head.pitch(0)).build(),
            row(mesh, EntityPose.NONE));

        assertTrue(compiled.style().drivers().isEmpty(), "no field and no driver");
        assertFalse(compiled.pose().bones().containsKey("head"), "and no splice");
        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.message().contains("head") && entry.message().contains("x_rot")),
            "the elision records its bone and channel");
    }

    @Test
    @DisplayName("a ladder-shaped shipped base compiles - every scan visits each node once")
    void ladderBaseCompilesByVisitingEachNodeOnce() {
        // Each rung's two operands are the SAME previous-rung instance, so forty-one nodes
        // stand for two-to-the-fortieth paths - the humanoid-arm shape in miniature. The rest
        // evaluation, the driven-base scan, the raw-hatch check and the interning of the gate
        // the hatch rides all walk it, and each completes only by memoizing per node instance.
        PoseExpr ladder = constant(0.25d);
        for (int rung = 0; rung < 40; rung++)
            ladder = dadd(ladder, ladder);
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, ladder);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean")
                .keepStride()
                .arm(Side.RIGHT, arm -> arm.pitch(-40))
                .build(),
            row(humanoid(), shipped));

        PoseExpr.Op splice = assertInstanceOf(PoseExpr.Op.class,
            compiled.pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        assertSame(ladder, splice.operands().getFirst(),
            "the shipped ladder rides the splice by instance");

        PoseExpr duplicate = constant(0.25d);
        for (int rung = 0; rung < 40; rung++)
            duplicate = dadd(duplicate, duplicate);
        PoseCompiler.Compiled hatched = PoseCompiler.compile(
            Poses.custom("twin").expr("right_arm", PoseChannel.Y_ROT, duplicate).build(),
            row(humanoid(), shipped));
        PoseExpr.Select gated = assertInstanceOf(PoseExpr.Select.class,
            hatched.pose().bones().get("right_arm").get(PoseChannel.Y_ROT));
        assertSame(ladder, gated.whenTrue(),
            "a structural duplicate interns to the shipped instances before its checks walk it");
    }

    @Test
    @DisplayName("under a row that drives none of its fields the woven graph answers the shipped values")
    void restingSplicesAnswerShippedValues() {
        EntityMesh mesh = humanoid();
        PoseExpr shippedExpr = constant(0.25d);
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked.
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedExpr),
            "hat", Map.of(PoseChannel.Y_ROT, shippedExpr)), List.of());
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("nod")
                .head(head -> head.yaw(35).pitch(-20))
                .arm(Side.LEFT, arm -> arm.rollBy(15))
                .build(),
            row(mesh, shipped));

        EntityMesh underShipped = PosePlayer.posed(shipped, mesh, undriven("other"), PERIOD, 7);
        EntityMesh underWoven = PosePlayer.posed(compiled.pose(), mesh, undriven("other"), PERIOD, 7);
        assertEquals(underShipped.getBones(), underWoven.getBones(),
            "every added node evaluates to the shipped value when its field rests");
    }

    @Test
    @DisplayName("an authored hat is reported on a hatless mesh where the head's implicit mirror is not")
    void anAuthoredHatDoesNotRideTheHeadsInstances() {
        EntityMesh hatless = humanoid();
        hatless.getBones().remove("hat");

        // The head's automatic copy hands the hat the head's own fragment list INSTANCES, and the
        // mirror is recognised by that identity and by nothing else - so it drops silently, because
        // the author never spelled it.
        PoseCompiler.Compiled implicit = PoseCompiler.compile(
            Poses.humanoid("nod").head(head -> head.yaw(35)).build(),
            row(hatless, EntityPose.NONE));
        assertTrue(implicit.drops().isEmpty(),
            "the head's automatic copy rides the head's instances and drops silently: "
                + implicit.drops());

        // Spelled out, the same values are captured into fresh lists, so the identity test fails and
        // the address is the author's own - which a hatless mesh is entitled to report.
        PoseCompiler.Compiled authored = PoseCompiler.compile(
            Poses.humanoid("nod").head(head -> head.yaw(35)).hat(hat -> hat.yaw(35)).build(),
            row(hatless, EntityPose.NONE));
        assertEquals(List.of("bone 'hat'"), described(authored.drops()),
            "an authored hat is reported where the mesh lacks the shell");
    }

    @Test
    @DisplayName("a hat the head carries takes none of the head's write - the head's chain already turns it")
    void aHatTheHeadCarriesTakesNoMirror() {
        // The zombie draws HumanoidModel#createMesh, which hangs its hat from its head at a zero pose
        // as vanilla's does, and no shipped humanoid row writes the hat.
        Entity zombie = EntityModelLoader.load().get("minecraft:zombie");
        EntityMesh mesh = zombie.model();
        assertEquals("head", mesh.getBones().get("hat").getParent(), "the shipped hat hangs from the head");
        assertFalse(zombie.pose().bones().containsKey("hat"), "and the shipped row writes none of its channels");

        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("dab").head(head -> head.rotate(30, -35, 0)).build(), zombie);
        assertFalse(compiled.pose().bones().containsKey("hat"),
            "no splice of the head's is woven onto a hat the head carries");

        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(30f, posed.getBones().get("head").getRotation().pitch(), 1e-4f, "the head takes the turn");
        assertEquals(-35f, posed.getBones().get("head").getRotation().yaw(), 1e-4f);
        EntityMesh.Bone hat = posed.getBones().get("hat");
        assertEquals(new EulerRotation(0f, 0f, 0f), hat.getRotation(),
            "and the hat rests unturned inside the head's chain, which turns it once");
        assertEquals(mesh.getBones().get("hat").getPivot(), hat.getPivot(), "at its rest pivot");
    }

    @Test
    @DisplayName("a scaled head draws a hat it carries at the head's scale once, through the head's chain")
    void aHatTheHeadCarriesIsScaledOnce() {
        Entity zombie = EntityModelLoader.load().get("minecraft:zombie");
        EntityMesh mesh = zombie.model();
        assertEquals("head", mesh.getBones().get("hat").getParent(), "the shipped hat hangs from the head");

        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("bulk").head(head -> head.scale(1.5)).build(), zombie);
        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), posed.getBones().get("head").getPoseScale(),
            "the head rides the written scale");
        assertFalse(posed.getBones().get("hat").isPoseScaled(), "and the hat it carries takes none of its own");
        assertEquals(1.5f, drawnScale(posed, "hat"), DRAWN,
            "so the head's chain draws the hat at the head's scale, once");
    }

    @Test
    @DisplayName("the block-overlay anchor on a scaled bone carries the bone's own written scale, as vanilla's layer applies the part's own step")
    void theBlockOverlayAnchorCarriesThePartsScale() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("bulk").head(head -> head.scale(1.5)).build(), row(mesh, EntityPose.NONE));

        Matrix4f anchor = EntityGeometryKit.resolveBoneAnchorMatrix(posed(compiled, mesh, 0), "head");
        assertEquals(1.5f, new Vector3f(1f, 0f, 0f).transformNormal(anchor).length(), DRAWN,
            "a block drawn on the head scales with it, as translateAndRotate's scale reaches what the layer draws");
    }

    @Test
    @DisplayName("a hat the head carries keeps the row's own hat write under a head that turns and moves")
    void aHatTheHeadCarriesKeepsTheRowsOwnWrite() {
        // The enderman's is the one shipped row writing its hat - the hat's own height read back - and
        // its hat hangs from its head as vanilla's does.
        Entity enderman = EntityModelLoader.load().get("minecraft:enderman");
        assertEquals("head", enderman.model().getBones().get("hat").getParent(), "the shipped hat hangs from the head");
        Map<PoseChannel, PoseExpr> shippedHat = enderman.pose().bones().get("hat");
        assertNotNull(shippedHat, "and the shipped row writes it");

        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean").head(head -> head.rotate(10, 20, 0).offset(0, -2, 0)).build(), enderman);
        assertTrue(compiled.pose().bones().get("head").containsKey(PoseChannel.Y),
            "the head takes the offset");
        assertSame(shippedHat, compiled.pose().bones().get("hat"),
            "and the row's own hat write rides untouched, none of the head's turn or offset woven over it");
    }

    @Test
    @DisplayName("a head timeline copies onto a hat off the head's chain and adds no channel for one the head carries")
    void aHeadClipCopiesOntoAHatOffTheHeadsChainAlone() {
        BuiltStyle nod = Poses.humanoid("nod")
            .head(head -> head.timeline(track -> track.swing(Turn.PITCH, -10, 10).over(0.6)))
            .build();

        assertEquals(List.of("head"),
            clipBones(PoseCompiler.compile(nod, EntityModelLoader.load().get("minecraft:zombie"))),
            "the head's chain already plays the head's clip on a hat hanging from it");
        assertEquals(List.of("head", "hat"), clipBones(PoseCompiler.compile(nod, row(humanoid(), EntityPose.NONE))),
            "a top-level hat takes a copy of every head channel");

        EntityMesh headless = humanoid();
        headless.getBones().remove("head");
        headless.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "head"));
        assertEquals(List.of("head", "hat"), clipBones(PoseCompiler.compile(nod, row(headless, EntityPose.NONE))),
            "and so does a hat naming a head the mesh does not declare, which hangs from the root");
    }

    @Test
    @DisplayName("a hat whose parents end at the root or close a cycle short of the head takes the head's mirror")
    void aHatWhoseParentsNeverMeetTheHeadTakesTheMirror() {
        BuiltStyle nod = Poses.humanoid("nod").head(head -> head.yaw(35)).build();
        EntityMesh selfParented = humanoid();
        selfParented.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "hat"));
        EntityMesh dangling = humanoid();
        dangling.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "crown"));
        EntityMesh looped = humanoid();
        looped.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "brim"));
        looped.getBones().put("brim", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "hat"));

        for (EntityMesh mesh : List.of(selfParented, dangling, looped)) {
            String parent = mesh.getBones().get("hat").getParent();
            PoseCompiler.Compiled compiled = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> PoseCompiler.compile(nod, row(mesh, EntityPose.NONE)),
                "the walk up the hat's parents ends on a hat hanging from '" + parent + "'");
            assertSame(compiled.pose().bones().get("head").get(PoseChannel.Y_ROT),
                compiled.pose().bones().get("hat").get(PoseChannel.Y_ROT),
                "a hat hanging from '" + parent + "' rides the head's instance");
        }
    }

    @Test
    @DisplayName("a hat hanging below the head through another bone takes no mirror - the walk climbs to the head")
    void aHatBelowTheHeadThroughAnotherBoneTakesNoMirror() {
        EntityMesh banded = humanoid();
        banded.getBones().put("band", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "head"));
        banded.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "band"));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("nod").head(head -> head.yaw(35)).build(), row(banded, EntityPose.NONE));

        assertTrue(compiled.pose().bones().containsKey("head"), "the head takes the write");
        assertFalse(compiled.pose().bones().containsKey("hat"),
            "and a hat the head carries two bones down takes nothing of it");
    }

    @Test
    @DisplayName("an additive write rides a live driven base with no rebase")
    void additiveWriteRidesTheLiveBase() {
        EntityMesh mesh = humanoid();
        PoseExpr stride = input("walkAnimationPos");
        EntityPose shipped = boneWrite("right_arm", PoseChannel.X_ROT, stride);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("jog").keepStride().arm(Side.RIGHT, arm -> arm.pitchBy(-20)).build(),
            row(mesh, shipped));

        assertEquals(StyleDriver.Wave.RAMP, compiled.style().drivers().get("walkAnimationPos").wave(),
            "the stride trio copies onto the row");
        assertEquals((float) Math.toRadians(-20), compiled.style().drivers().get("style$jog$right_arm$x_rot").extent(),
            "an additive delta converts once and never rebases");

        // At tick six the ramped stride phase answers six, and the splice adds its delta onto
        // that live value rather than onto a rest snapshot.
        float landed = posed(compiled, mesh, 6).getBones().get("right_arm").getRotation().pitch();
        float written = (float) (6d + (double) (float) Math.toRadians(-20));
        assertEquals((float) Math.toDegrees(written), landed);
    }

    @Test
    @DisplayName("a sway lowers to one swept driver whose bounds are the authored deltas")
    void swayLowersToOneSweptDriver() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wag").arm(Side.RIGHT, arm -> arm.sway(Turn.ROLL, -8, 8)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$wag$right_arm$z_rot");
        assertNotNull(driver);
        assertEquals(StyleDriver.Wave.SWEEP, driver.wave());
        assertEquals((float) Math.toRadians(-8), driver.rest(), "the sweep rests at the first bound");
        assertEquals((float) Math.toRadians(8), driver.extent(), "and peaks at the second");

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        assertEquals(degreesOf(rest, driver.at(0, PERIOD)),
            posed(compiled, mesh, 0).getBones().get("right_arm").getRotation().roll(),
            "tick zero holds the resting bound");
        assertEquals(degreesOf(rest, driver.at(12, PERIOD)),
            posed(compiled, mesh, 12).getBones().get("right_arm").getRotation().roll(),
            "mid-period holds the peak");
        assertEquals(degreesOf(rest, (float) Math.toRadians(8)),
            posed(compiled, mesh, 12).getBones().get("right_arm").getRotation().roll(),
            "which is the far bound exactly");
    }

    @Test
    @DisplayName("a spin lowers to one cycling driver wrapping a full turn per period")
    void spinLowersToOneCyclingDriver() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("twirl").head(head -> head.spin(Turn.YAW, 360)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$twirl$head$y_rot");
        assertNotNull(driver);
        assertEquals(StyleDriver.Wave.CYCLE, driver.wave());
        assertEquals(0f, driver.rest());
        assertEquals((float) Math.toRadians(360), driver.extent());
        assertEquals((float) Math.toRadians(360) * 0.25f, driver.at(6, PERIOD),
            "a quarter period travels a quarter turn");
    }

    @Test
    @DisplayName("a stance write and a sway on one channel fold into one driver's shifted bounds")
    void writeAndSwayFoldIntoOneDriver() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("tilt").head(head -> head.yawBy(30).sway(Turn.YAW, -5, 5)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$tilt$head$y_rot");
        assertNotNull(driver, "one field holds one driver");
        assertEquals(StyleDriver.Wave.SWEEP, driver.wave());
        double delta = Math.toRadians(30);
        assertEquals((float) (Math.toRadians(-5) + delta), driver.rest(),
            "the stance delta shifts both bounds");
        assertEquals((float) (Math.toRadians(5) + delta), driver.extent());
    }

    @Test
    @DisplayName("timelines lower to one clip behind a selection-gated play site")
    void timelinesLowerToOneGatedClip() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.roll(30)
                    .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(1, compiled.pose().clips().size(), "one clip per built style");
        EntityPose.Clip site = compiled.pose().clips().getFirst();
        assertEquals(ClipDrive.SELECT, site.drive());
        assertEquals(Optional.of("style$wave"), site.field(), "the gate field is present by construction");
        assertEquals(1, site.arguments().size(), "the selection arity is satisfied by construction");
        PoseExpr.Input clock = assertInstanceOf(PoseExpr.Input.class, site.arguments().getFirst());
        assertEquals("style$wave$clock", clock.field(), "a fresh clock, never a shipped idle term");
        assertEquals(1f, compiled.style().drivers().get("style$wave").extent(), "the gate holds at one");
        assertEquals(StyleDriver.Wave.RAMP, compiled.style().drivers().get("style$wave$clock").wave());

        PoseClip clip = site.clip();
        assertEquals(0.6f, clip.lengthSeconds());
        assertTrue(clip.looping());
        assertEquals(1, clip.channels().size());
        PoseClip.Channel channel = clip.channels().getFirst();
        assertEquals("right_arm", channel.bone());
        assertEquals(PoseChannel.Kind.ROTATION, channel.target());
        assertEquals(List.of(0f, 0.3f, 0.6f),
            channel.keyframes().stream().map(PoseClip.Keyframe::timeSeconds).toList(),
            "the swing triangle keys its ends and middle");
        assertEquals((float) Math.toRadians(-20), channel.keyframes().getFirst().z(),
            "keyframe components are radians");
        assertEquals(PoseClip.Interpolation.LINEAR, channel.keyframes().getFirst().interpolation());
    }

    @Test
    @DisplayName("the clip's deltas add onto the stance at real twenty-per-second seconds")
    void clipDeltasRideTheStance() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.roll(30)
                    .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
                .build(),
            row(mesh, EntityPose.NONE));

        float rest = mesh.getBones().get("right_arm").getRotation().rollRadians();
        float extent = compiled.style().drivers().get("style$wave$right_arm$z_rot").extent();
        float stance = (float) ((double) rest + (double) extent);
        // Ticks zero, three and six are clock seconds 0, 0.15 and 0.3 - the swing's start, its
        // midpoint climb and its peak.
        float[] deltas = {(float) Math.toRadians(-20), 0f, (float) Math.toRadians(20)};
        int[] ticks = {0, 3, 6};
        for (int at = 0; at < ticks.length; at++) {
            float landed = posed(compiled, mesh, ticks[at]).getBones().get("right_arm").getRotation().roll();
            float expected = (float) Math.toDegrees(stance + deltas[at]);
            assertEquals(expected, landed, 1e-4f, "tick " + ticks[at]);
        }
    }

    @Test
    @DisplayName("a paired stamp mirrors the timeline, so the pair moves as mirror images")
    void pairedTimelineMirrors() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("clap")
                .arms(arm -> arm.timeline(track -> track.swing(Turn.YAW, 0, -25).over(0.4)))
                .build(),
            row(mesh, EntityPose.NONE));

        PoseClip clip = compiled.pose().clips().getFirst().clip();
        assertEquals(2, clip.channels().size(), "one rotation channel per arm");
        PoseClip.Keyframe right = clip.channels().getFirst().keyframes().get(1);
        PoseClip.Keyframe left = clip.channels().getLast().keyframes().get(1);
        assertEquals((float) Math.toRadians(-25), right.y(), "the right swings as authored");
        assertEquals((float) Math.toRadians(25), left.y(), "the left swings negated");
    }

    @Test
    @DisplayName("a container step appends innermost with bare field reads")
    void containerStepAppendsBareFieldReads() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit")
                .container(step -> step.offset(0, 7, 0))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(1, compiled.pose().container().size());
        PoseExpr vertical = compiled.pose().container().getFirst().get(PoseChannel.Y);
        assertEquals("style$sit$$container$y", assertInstanceOf(PoseExpr.Input.class, vertical).field(),
            "a container channel rests at no transform, so the read needs no base");
        assertEquals(7f, compiled.style().drivers().get("style$sit$$container$y").extent());

        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(7f, posed.getBones().get("$container").getPivot().y(),
            "the seat drops the whole figure");
        assertTrue(compiled.style().sources().isEmpty(), "a held seat is a statue, not an animation");
    }

    @Test
    @DisplayName("hover lowers to a lift held and a bob swept on two container fields")
    void hoverLowersToLiftAndBob() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("levitate").hover(8, 2).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver lift = compiled.style().drivers().get("style$levitate$$container$y");
        StyleDriver bob = compiled.style().drivers().get("style$levitate$$container$y_bob");
        assertEquals(StyleDriver.Wave.HOLD, lift.wave());
        assertEquals(-8f, lift.extent(), "y-down, so lift is negative and the surface hides the sign");
        assertEquals(StyleDriver.Wave.SWEEP, bob.wave());
        assertEquals(-2f, bob.extent());

        PoseExpr vertical = compiled.pose().container().getFirst().get(PoseChannel.Y);
        assertEquals(PoseOperator.DADD, assertInstanceOf(PoseExpr.Op.class, vertical).operator(),
            "two drivers cannot share one field, so the channel sums two reads");

        assertEquals(-8f, posed(compiled, mesh, 0).getBones().get("$container").getPivot().y(),
            "the sweep rests at zero, leaving the lift alone");
        assertEquals(-10f, posed(compiled, mesh, 12).getBones().get("$container").getPivot().y(),
            "and peaks mid-period, dipping the figure");
        assertEquals(List.of(new PoseStyle.StyleSource(StyleClock.TICK, Optional.empty())),
            List.copyOf(compiled.style().sources()), "a nonzero bob moves the style on the clock");
    }

    @Test
    @DisplayName("on a row whose clips displace the container the custom step is the fold seat")
    void foldSeatTakesTheCustomStep() {
        EntityMesh mesh = humanoid();
        // A shipped clip reaching the part every bone hangs from marks the fold-seat row; its
        // first instant displaces nothing, so evaluation stays neutral.
        EntityPose shipped = pose(List.of(), Map.of(), List.of(displacingSite()));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("perch").container(step -> step.offset(0, 5, 0)).build(),
            row(mesh, shipped));

        assertEquals(1, compiled.pose().container().size(),
            "the custom channels form the row's sole written step, which the displacement folds onto");
    }

    @Test
    @DisplayName("a shipped container step a clip displaces is spliced, never re-seated")
    void foldSeatSplicesTheShippedStep() {
        EntityMesh mesh = humanoid();
        PoseExpr shippedStep = constant(0.1d);
        EntityPose shipped = pose(
            List.of(Map.of(PoseChannel.X_ROT, shippedStep)),
            Map.of(), List.of(displacingSite()));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("perch")
                .container(step -> step.pitch(-30).yawBy(10))
                .build(),
            row(mesh, shipped));

        assertEquals(1, compiled.pose().container().size(), "no step appends below a shipped seat");
        Map<PoseChannel, PoseExpr> innermost = compiled.pose().container().getFirst();
        PoseExpr.Op folded = assertInstanceOf(PoseExpr.Op.class, innermost.get(PoseChannel.X_ROT));
        assertSame(shippedStep, folded.operands().getFirst(),
            "a channel the shipped step writes is spliced on the shipped instance");
        assertInstanceOf(PoseExpr.Input.class, innermost.get(PoseChannel.Y_ROT),
            "a channel it does not write joins the step as a bare read");
    }

    /**
     * A shipped play site whose clip reaches the container and rests displacement-free.
     */
    private static @NotNull EntityPose.Clip displacingSite() {
        PoseClip clip = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("root", PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0f, 0f, 0.05f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR)))));
        return new EntityPose.Clip("sway", ClipDrive.NONE, Optional.empty(),
            Concurrent.newUnmodifiableList(), clip);
    }

    @Test
    @DisplayName("a uniform scale splices three channels reading one shared field")
    void uniformScaleSplicesThreeChannelsOverOneField() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build(),
            row(mesh, EntityPose.NONE));

        StyleDriver driver = compiled.style().drivers().get("style$bulk$right_arm$scale");
        assertNotNull(driver, "one shared field, spelled outside the channel-token set");
        assertEquals(0.5f, driver.extent(), "the delta rebases against the rest the axes agree on");

        Map<PoseChannel, PoseExpr> arm = compiled.pose().bones().get("right_arm");
        PoseExpr.Op x = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.X_SCALE));
        PoseExpr.Op y = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.Y_SCALE));
        PoseExpr.Op z = assertInstanceOf(PoseExpr.Op.class, arm.get(PoseChannel.Z_SCALE));
        assertSame(x.operands().getLast(), y.operands().getLast(), "one interned read per shared field");
        assertSame(y.operands().getLast(), z.operands().getLast());

        EntityMesh posed = posed(compiled, mesh, 0);
        EntityMesh.Bone scaled = posed.getBones().get("right_arm");
        assertEquals(1f, scaled.getScale(), "the arm keeps the factor it rests at");
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), scaled.getPoseScale(),
            "equal axis values satisfy the uniform fold and ride the chain as the ratio to that rest");
        assertEquals(1.5f, drawnScale(posed, "right_arm"), DRAWN, "so the arm draws at the authored factor");
    }

    @Test
    @DisplayName("a raw scale of one graph on all three axes draws one uniform factor, one instance or three built apart")
    void aRawScaleOfOneGraphDrawsOneUniformFactor() {
        // The arm rests at one on a mesh flattened at nothing, where the mesh's units and vanilla's
        // field read alike, so what this holds is the shape the install takes rather than a unit.
        EntityMesh mesh = humanoid();
        PoseExpr grown = new PoseExpr.Constant(1.5d, PoseWidth.FLOAT);
        BuiltStyle shared = Poses.custom("swell")
            .expr("right_arm", PoseChannel.X_SCALE, grown)
            .expr("right_arm", PoseChannel.Y_SCALE, grown)
            .expr("right_arm", PoseChannel.Z_SCALE, grown)
            .build();
        BuiltStyle apart = Poses.custom("swell")
            .expr("right_arm", PoseChannel.X_SCALE, new PoseExpr.Constant(1.5d, PoseWidth.FLOAT))
            .expr("right_arm", PoseChannel.Y_SCALE, new PoseExpr.Constant(1.5d, PoseWidth.FLOAT))
            .expr("right_arm", PoseChannel.Z_SCALE, new PoseExpr.Constant(1.5d, PoseWidth.FLOAT))
            .build();

        for (BuiltStyle style : List.of(shared, apart)) {
            String shape = style == shared ? "one instance" : "three built apart";
            EntityMesh posed = posed(PoseCompiler.compile(style, row(mesh, EntityPose.NONE)), mesh, 0);
            EntityMesh.Bone scaled = posed.getBones().get("right_arm");
            assertEquals(1f, scaled.getScale(), shape + ": the arm keeps the factor it rests at");
            assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), scaled.getPoseScale(),
                shape + ": the three axes evaluate to one value, which the uniform fold takes");
            assertEquals(1.5f, drawnScale(posed, "right_arm"), DRAWN,
                shape + ": so the arm draws at the written factor");
        }
    }

    @Test
    @DisplayName("a scale on a flattened mesh multiplies the factor the bone carries, as a part written under vanilla's scaled root draws")
    void scaleOnAFlattenedMeshMultipliesTheFactor() {
        EntityMesh mesh = flattened(2f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("bulk").bone("tail", tail -> tail.scale(1.5)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(1f, compiled.style().drivers().get("style$bulk$tail$scale").extent(),
            "the field holds the factor times the authored scale, less the rest the factor sets");
        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(2f, posed.getBones().get("tail").getScale(), "the bone keeps the factor it rests at");
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), posed.getBones().get("tail").getPoseScale(),
            "and rides the authored scale as its ratio to that factor");
        assertEquals(3f, drawnScale(posed, "tail"), DRAWN,
            "so the bone draws at the factor times the authored scale");
    }

    @Test
    @DisplayName("a unit scale on a flattened mesh drives no field and leaves the bone at the factor it carries")
    void unitScaleOnAFlattenedMeshLeavesTheFactor() {
        EntityMesh mesh = flattened(2f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("bulk").bone("tail", tail -> tail.scale(1)).build(),
            row(mesh, EntityPose.NONE));

        assertTrue(compiled.style().drivers().isEmpty(), "the factor times one is the rest, a zero delta");
        EntityMesh posed = posed(compiled, mesh, 0);
        assertFalse(posed.getBones().get("tail").isPoseScaled(), "so the bone takes no pose scale");
        assertEquals(2f, drawnScale(posed, "tail"), DRAWN, "and draws at the factor it is flattened at");
    }

    @Test
    @DisplayName("a scale on an aged-down mesh replaces a top part's own factor, carries the part below it, and multiplies the one a part below draws under")
    void scaleOnAnAgedDownMeshReadsTheScaleAboveThePart() {
        // Vanilla's baby transform scales each top-level part's own pose, so the subtree factor
        // sits in that part's field and reaches a part below it through the stack.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("head", CompilerFixtures.bone(0f, 8f, -6f, 0f, 0f, 0f, 0.75f, null));
        mesh.getBones().put("body", CompilerFixtures.bone(0f, 12f, 0f, 0f, 0f, 0f, 0.5f, null));
        mesh.getBones().put("tail", CompilerFixtures.bone(0f, 10f, 6f, 30f, 0f, 0f, 0.5f, "body"));
        assertEquals(1f, mesh.getFlattenedScale(), "a factor per subtree is no whole-mesh factor");
        // Each part scales in a style of its own, so the part below draws under its parent's
        // factor at rest - the one vanilla draws it under while nothing writes the parent.
        PoseCompiler.Compiled top = PoseCompiler.compile(
            Poses.custom("bulk").bone("body", body -> body.scale(1.5)).build(),
            row(mesh, EntityPose.NONE));
        PoseCompiler.Compiled below = PoseCompiler.compile(
            Poses.custom("bulk").bone("tail", tail -> tail.scale(1.5)).build(),
            row(mesh, EntityPose.NONE));

        EntityMesh grown = posed(top, mesh, 0);
        assertEquals(0.5f, grown.getBones().get("body").getScale(), "a top part keeps the factor it rests at");
        assertEquals(new Vector3f(3f, 3f, 3f), grown.getBones().get("body").getPoseScale(),
            "and rides the write as its ratio to that factor");
        assertEquals(1.5f, drawnScale(grown, "body"), DRAWN,
            "a top part's own field holds its subtree's factor, and the write replaces it");
        // Vanilla's stack carries the body's field to the tail: its field of one under 1.5, and its
        // pivot swung out by the same ratio about the body's.
        assertEquals(0.5f, grown.getBones().get("tail").getScale(), "the part below keeps its rest");
        assertFalse(grown.getBones().get("tail").isPoseScaled(), "and takes no pose scale of its own");
        assertEquals(1.5f, drawnScale(grown, "tail"), DRAWN, "yet draws under the body's written scale");
        assertAt(new Vector3f(0f, 42f, 18f), chainAt(grown, "tail"), "with its pivot scaled by the body's ratio");

        EntityMesh lower = posed(below, mesh, 0);
        assertEquals(0.5f, lower.getBones().get("tail").getScale(), "a part below keeps the factor it rests at");
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), lower.getBones().get("tail").getPoseScale(),
            "and rides the write as its ratio to that factor");
        assertEquals(0.75f, drawnScale(lower, "tail"), DRAWN,
            "a part below it draws its field under that factor");
    }

    @Test
    @DisplayName("nested scale writes draw a part below at its own written field times every written field above it")
    void nestedScalesDrawAtTheProduct() {
        // Vanilla's baby transform leaves the body's field at 0.5 and the tail's own field at 0.75, and
        // the tooling flattens the tail's pivot and scale through the body's, so the tail rests at 0.375.
        EntityMesh mesh = new EntityMesh();
        mesh.getBones().put("body", CompilerFixtures.bone(0f, 12f, 0f, 0f, 0f, 0f, 0.5f, null));
        mesh.getBones().put("tail", CompilerFixtures.bone(0f, 10f, 6f, 30f, 0f, 0f, 0.375f, "body"));
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("bulk").bone("body", body -> body.scale(2)).bone("tail", tail -> tail.scale(3)).build(),
            row(mesh, EntityPose.NONE));

        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(new Vector3f(4f, 4f, 4f), posed.getBones().get("body").getPoseScale(),
            "the body rides 2 over its rest of 0.5");
        assertEquals(new Vector3f(4f, 4f, 4f), posed.getBones().get("tail").getPoseScale(),
            "and the tail 3 over its own field of 0.75, the scale above it read at the body's rest");
        assertEquals(6f, drawnScale(posed, "tail"), DRAWN,
            "so the tail draws at 2 times 3, vanilla's stack of the two written fields");
        assertAt(new Vector3f(0f, 52f, 24f), chainAt(posed, "tail"),
            "with its pivot scaled by the body's written field");
    }

    @Test
    @DisplayName("an aim solve lands pitch and yaw from the row's own pivot, roll untouched")
    void aimSolvesPitchAndYawFromTheRowsPivot() {
        EntityMesh mesh = humanoid();
        double targetX = 18, targetY = -30, targetZ = -14;
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("hail")
                .arm(Side.RIGHT, arm -> arm.aimAt(targetX, targetY, targetZ))
                .head(head -> head.aimAt(targetX, targetY, targetZ))
                .build(),
            row(mesh, EntityPose.NONE));

        // Recompute the two solves from the row's own pivots - the built value states the
        // TARGET, and each row solves its own angles.
        double[] armSolve = solved(mesh, "right_arm", targetX, targetY, targetZ);
        double[] headSolve = solved(mesh, "head", targetX, targetY, targetZ);

        EntityMesh posed = posed(compiled, mesh, 0);
        assertEquals(armSolve[0] - 90d, posed.getBones().get("right_arm").getRotation().pitch(), 1e-3,
            "a hanging limb rests pointing down and takes the quarter-turn offset");
        assertEquals(armSolve[1], posed.getBones().get("right_arm").getRotation().yaw(), 1e-3);
        assertEquals(headSolve[0], posed.getBones().get("head").getRotation().pitch(), 1e-3,
            "a facing bone takes the solve as-is");
        assertEquals(headSolve[1], posed.getBones().get("head").getRotation().yaw(), 1e-3);
        assertEquals(10f, posed.getBones().get("right_arm").getRotation().roll(),
            "roll stays authored - the solve writes two channels");
    }

    /**
     * The facing solve for one bone - pitch and yaw degrees toward a model-space target.
     */
    private static double @NotNull [] solved(
        @NotNull EntityMesh mesh, @NotNull String bone,
        double targetX, double targetY, double targetZ) {

        double dx = targetX - mesh.getBones().get(bone).getPivot().x();
        double dy = targetY - mesh.getBones().get(bone).getPivot().y();
        double dz = targetZ - mesh.getBones().get(bone).getPivot().z();
        return new double[] {
            Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))),
            Math.toDegrees(Math.atan2(-dx, -dz))
        };
    }

    @Test
    @DisplayName("a pixel offset crosses the flattened factor once and lands whole pixels")
    void pixelOffsetCrossesTheFlattenedFactorOnce() {
        EntityMesh mesh = flattened(2f);
        float authored = mesh.getBones().get("tail").getPivot().y();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("settle").bone("tail", tail -> tail.offset(0, 4, 0)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(2f, compiled.style().drivers().get("style$settle$tail$y").extent(),
            "the graph speaks authored-over-factor space, so the surface pixels divide once");
        assertEquals(authored + 4f, posed(compiled, mesh, 0).getBones().get("tail").getPivot().y(),
            "and the write-back multiplies the factor once, landing the authored pixels");
    }

    @Test
    @DisplayName("a pixel offset on a parentless bone of a flattened mesh crosses the factor and its anchor once")
    void parentlessOffsetOnAFlattenedMeshLandsWholePixels() {
        EntityMesh mesh = flattened(2f);
        float authored = mesh.getBones().get("body").getPivot().y();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("settle").bone("body", body -> body.offset(0, 4, 0)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(2f, compiled.style().drivers().get("style$settle$body$y").extent(),
            "the surface pixels divide once, a top-level bone no differently from a child");
        assertEquals(authored + 4f, posed(compiled, mesh, 0).getBones().get("body").getPivot().y(), 1e-4f,
            "and the write-back multiplies the factor once and puts the feet anchor back, landing the authored pixels");
    }

    @Test
    @DisplayName("a container step on a flattened mesh crosses no factor and seats at the pixels written")
    void containerStepOnAFlattenedMeshSeatsAtThePixelsWritten() {
        EntityMesh mesh = flattened(2f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("scoot").container(step -> step.offset(0, 3, -5).pitch(-30)).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(3f, compiled.style().drivers().get("style$scoot$$container$y").extent(),
            "the step stands above the root the factor rides, so the field holds the pixels written");
        assertEquals(-5f, compiled.style().drivers().get("style$scoot$$container$z").extent());
        assertEquals((float) Math.toRadians(-30), compiled.style().drivers().get("style$scoot$$container$x_rot").extent(),
            "a turn crosses nothing, as it does at a factor of one");

        EntityMesh.Bone seat = posed(compiled, mesh, 0).getBones().get("$container");
        assertEquals(new Vector3f(0f, 3f, -5f), seat.getPivot(),
            "the seat places the step at the number written, whatever the mesh is flattened at");
        assertEquals(-30f, seat.getRotation().pitch(), 1e-4f, "and turns it as authored");
    }

    @Test
    @DisplayName("a hover on a flattened mesh crosses no factor and lifts and bobs by the pixels written")
    void hoverOnAFlattenedMeshSeatsAtThePixelsWritten() {
        EntityMesh mesh = flattened(2f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.custom("float").hover(8, 2).build(),
            row(mesh, EntityPose.NONE));

        assertEquals(-8f, compiled.style().drivers().get("style$float$$container$y").extent(),
            "the lift is the pixels written, as at a factor of one");
        assertEquals(-2f, compiled.style().drivers().get("style$float$$container$y_bob").extent(),
            "and so is the bob");
        assertEquals(-8f, posed(compiled, mesh, 0).getBones().get("$container").getPivot().y(),
            "the sweep rests at zero, leaving the lift alone");
        assertEquals(-10f, posed(compiled, mesh, 12).getBones().get("$container").getPivot().y(),
            "and peaks mid-period, dipping the figure by the bob written");
    }

    @Test
    @DisplayName("driver extents narrow to float exactly once at the record boundary")
    void extentsNarrowOnceAtTheDriverBoundary() {
        EntityMesh mesh = humanoid();
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("lean").torso(torso -> torso.pitch(12.34)).build(),
            row(mesh, EntityPose.NONE));

        double exact = Math.toRadians(12.34) - (double) mesh.getBones().get("body").getRotation().pitchRadians();
        assertEquals(Float.floatToIntBits((float) exact),
            Float.floatToIntBits(compiled.style().drivers().get("style$lean$body$x_rot").extent()),
            "the double delta rounds exactly once");
    }

    @Test
    @DisplayName("a woven layer rebases on per-layer fields and spells shared fields the body's way")
    void layerCompileRebasesOnPerLayerFields() {
        EntityMesh body = humanoid();
        EntityMesh wool = humanoid();
        // The layer's arm rests elsewhere, so one absolute target needs a per-row delta.
        wool.getBones().put("right_arm", CompilerFixtures.bone(-5f, 2f, 0f, -15f, 0f, 25f, 1f, null));
        BuiltStyle style = Poses.humanoid("raise")
            .arm(Side.RIGHT, arm -> arm.roll(90).pitchBy(-10))
            .build();

        Diagnostics root = Diagnostics.root("styles", Diagnostics.Output.NONE, null);
        PoseCompiler.Compiled bodyArm = PoseCompiler.compile(style, row(body, EntityPose.NONE),
            root.child("minecraft:test").child("raise"));
        PoseCompiler.Compiled layerArm = PoseCompiler.compileLayer(style, EntityPose.NONE, wool,
            "$layer1", root.child("minecraft:test").child("raise"));

        assertEquals(List.of("style$raise$$layer1$right_arm$z_rot", "style$raise$right_arm$x_rot"),
            List.copyOf(layerArm.style().drivers().keySet()),
            "a rebased extent is per-row data; an additive turn drives the body's shared spelling");
        PoseExpr.Op additive = assertInstanceOf(PoseExpr.Op.class,
            layerArm.pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        assertEquals("style$raise$right_arm$x_rot",
            assertInstanceOf(PoseExpr.Input.class, additive.operands().getLast()).field());

        LinkedHashMap<String, StyleDriver> merged = new LinkedHashMap<>(bodyArm.style().drivers());
        layerArm.style().drivers().forEach(merged::putIfAbsent);
        PoseStyle installed = new PoseStyle("raise", bodyArm.style().sources(),
            Concurrent.newUnmodifiableMap(merged), bodyArm.style().toggles(), bodyArm.style().age(),
            bodyArm.style().periodTicks());

        float onBody = PosePlayer.posed(bodyArm.pose(), body, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll();
        float onLayer = PosePlayer.posed(layerArm.pose(), wool, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll();
        assertEquals(90f, onBody, 1e-4f);
        assertEquals(90f, onLayer, 1e-4f,
            "two rows' rests need not agree - each lands its own delta on the same absolute target");
    }

    @Test
    @DisplayName("a written bone the mesh lacks is dropped, recorded, and mirrored by one warning")
    void droppedBonesAreRecordedAndWarned() {
        EntityMesh mesh = flattened(1f);
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.legged("beg")
                .head(head -> head.pitch(-15))
                .bone("left_hind_leg", leg -> leg.pitch(-70))
                .build(),
            row(mesh, EntityPose.NONE));

        assertEquals(List.of("bone 'left_hind_leg'"), described(compiled.drops()));
        List<Diagnostics.Entry> warned = compiled.diagnostics().entries().stream()
            .filter(entry -> entry.severity() == Diagnostics.Severity.WARN)
            .toList();
        assertEquals(1, warned.size(), "one line carries the whole drop");
        assertTrue(warned.getFirst().message().contains("left_hind_leg"), "naming each missing bone");
        assertTrue(warned.getFirst().message().contains("tail"), "and the roster the mesh declares");
        assertNotNull(compiled.pose().bones().get("head"), "everything else still lowers");
    }

    @Test
    @DisplayName("a clip outrunning the strip window records the truncation warning")
    void longClipRecordsTheTruncationWarning() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("stretch")
                .arm(Side.RIGHT, arm -> arm.timeline(track -> track.swing(Turn.PITCH, -10, 10).over(2.4)))
                .build(),
            row(humanoid(), EntityPose.NONE));

        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.message().contains("truncat")),
            "frames beyond the window render truncated, and the compile says so");
    }

    @Test
    @DisplayName("every compile records its lowering inventory")
    void compileRecordsItsInventory() {
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build(),
            row(humanoid(), EntityPose.NONE));

        assertTrue(compiled.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.message().contains("inventory")
                    && entry.path().endsWith("/compile")),
            "driver, field, clip-channel and container-step counts the compiler already holds");
    }

    @Test
    @DisplayName("recording is observation only - a quiet and a console compile emit the same output")
    void diagnosticsAreNeverLoadBearing() {
        EntityMesh mesh = humanoid();
        BuiltStyle style = Poses.humanoid("wave")
            .arm(Side.RIGHT, arm -> arm.roll(30)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build();

        PoseCompiler.Compiled quiet = PoseCompiler.compile(style, row(mesh, EntityPose.NONE),
            Diagnostics.root("styles", Diagnostics.Output.NONE, null).child("quiet"));
        PoseCompiler.Compiled loud = PoseCompiler.compile(style, row(mesh, EntityPose.NONE),
            Diagnostics.root("styles", Diagnostics.Output.CONSOLE, null).child("loud"));

        assertEquals(quiet.style().drivers(), loud.style().drivers());
        assertEquals(quiet.drops(), loud.drops());
        assertEquals(
            posed(quiet, mesh, 3).getBones(),
            PosePlayer.posed(loud.pose(), mesh, loud.style(), PERIOD, 3).getBones(),
            "bit-identical compiled output under either mode");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The compiled style posed onto its target mesh at one tick.
     */
    private static @NotNull EntityMesh posed(
        @NotNull PoseCompiler.Compiled compiled, @NotNull EntityMesh mesh, int tick) {

        return PosePlayer.posed(compiled.pose(), mesh, compiled.style(), PERIOD, tick);
    }

    /**
     * A resolved row nothing drives - what any other selection answers about the woven graph.
     */
    private static @NotNull PoseStyle undriven(@NotNull String id) {
        return new PoseStyle(id, Concurrent.newUnmodifiableList(),
            Concurrent.newUnmodifiableMap(), Concurrent.newUnmodifiableList(), Optional.empty(),
            Optional.empty());
    }

    /**
     * The crossed-side warnings one style records on the mesh whose front pair is named backwards.
     */
    private static @NotNull List<String> crossingsOf(@NotNull BuiltStyle style,
                                                     @NotNull EntityMesh mesh) {
        Diagnostics scope = Diagnostics.root("styles", Diagnostics.Output.NONE, null)
            .child("minecraft:test").child(style.styleId());
        PoseCompiler.compile(style, row(mesh, EntityPose.NONE), scope);
        return scope.entries().stream()
            .filter(entry -> entry.severity() == Diagnostics.Severity.WARN)
            .map(Diagnostics.Entry::message)
            .filter(message -> message.startsWith("crossed sides:"))
            .toList();
    }

    @Test
    @DisplayName("a style keyed on which side a leg is on is told when the mesh names them crossed")
    void aCrossedMeshIsReportedToASideKeyedStyle() {
        List<UnaryOperator<Gait>> keyed = List.of(
            gait -> gait.trot(0.5),
            gait -> gait.oppose(0.5),
            gait -> gait.share());

        for (UnaryOperator<Gait> written : keyed) {
            BuiltStyle style = Poses.legged("canter")
                .gait(gait -> written.apply(gait.step(leg -> leg.timeline(track -> track
                    .swing(Turn.PITCH, -20, 20).over(0.4)))))
                .build();
            List<String> crossings = crossingsOf(style, crossedSides());

            assertEquals(1, crossings.size(), () -> "one line, once per compile: " + crossings);
            assertTrue(crossings.getFirst().contains("right_front_leg"), crossings::toString);
            assertTrue(crossings.getFirst().contains("left_front_leg"), crossings::toString);
            assertFalse(crossings.getFirst().contains("hind"),
                () -> "and it names the legs that cross rather than the row that does not: "
                    + crossings);
        }
    }

    @Test
    @DisplayName("one leg addressed by rank and side is told too - it is the leg opposite")
    void anAddressedLegIsReportedToo() {
        BuiltStyle lift = Poses.legged("lift")
            .leg(Rank.FRONT, Side.RIGHT, leg -> leg.pitchBy(-20))
            .build();

        assertEquals(1, crossingsOf(lift, crossedSides()).size(),
            "the author named a side and the mesh answers with the leg across from it");
    }

    @Test
    @DisplayName("a stamp reaching both sides alike is told nothing, having asked nothing")
    void asymmetricStampIsNotReported() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .over(0.4)
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .plant(0.25)
                .phase(Rank.HIND, 0.5)
                .gain(Rank.HIND, 0.5))
            .build();

        assertEquals(List.of(), crossingsOf(amble, crossedSides()),
            "which of a row's two legs took the authored copy is not a question this chain "
                + "asked, so a crossed name costs it nothing and saying so would be noise");
        assertEquals(List.of(), crossingsOf(Poses.legged("canter")
                .gait(gait -> gait
                    .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                    .trot(0.5))
                .build(), walker()),
            "and a mesh naming its legs for the sides they sit on is told nothing either");
    }

    @Test
    @DisplayName("a gain keyed on a row the mesh carries that no shape reaches is told, and refuses nobody")
    void anInertGainOnACarriedRowIsReported() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(Rank.FRONT, leg -> leg.sway(Turn.PITCH, -20, 20))
                .gain(Rank.HIND, 0.5))
            .build();

        assertEquals(List.of("gait: gain(HIND) keys a row this mesh carries that no shape reaches, "
                + "so the number scales nothing"),
            gaitReadingsOf(amble, walker()));
        assertTrue(PoseCompiler.compile(amble, row(walker(), EntityPose.NONE)).drops().isEmpty(),
            "the number is correct about a row that exists, so nothing joins the drop list and a "
                + "strict install still takes the chain");
    }

    @Test
    @DisplayName("an unranked shape reaches every row, so a number keyed on one of them is not inert")
    void anUnrankedShapeReachesEveryRow() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.sway(Turn.PITCH, -20, 20))
                .gain(Rank.HIND, 0.5))
            .build();

        assertEquals(List.of(), gaitReadingsOf(amble, walker()),
            "the stamp names no rank, so it lands on every row the mesh answers - this one "
                + "included, which a check reading ranked stances alone would miss");
    }

    @Test
    @DisplayName("a plant with no triangle to hold is told - a keyframe is not a shape a plant reshapes")
    void aPlantWithNothingToHoldIsReported() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track
                    .keyframe(0, -20, 0, 0)
                    .keyframe(0.2, 20, 0, 0)
                    .keyframe(0.4, -20, 0, 0)
                    .over(0.4)))
                .plant(0.25))
            .build();

        assertEquals(List.of("gait: a plant of '0.25' of a cycle reshapes nothing - a plant holds a "
                + "triangle at its resting bound, and this style keys no swing and no bob for it to hold"),
            gaitReadingsOf(amble, walker()));
    }

    @Test
    @DisplayName("a plant over a swing holds a real triangle, so nothing is told")
    void aPlantOverASwingIsNotInert() {
        BuiltStyle amble = Poses.legged("amble")
            .gait(gait -> gait
                .step(leg -> leg.timeline(track -> track.swing(Turn.PITCH, -20, 20).over(0.4)))
                .plant(0.25))
            .build();

        assertEquals(List.of(), gaitReadingsOf(amble, walker()));
    }

    @Test
    @DisplayName("a compiled result hands back the scope it was given, which reaches the compile's own lines")
    void aCompiledResultHandsBackTheScopeItWasGiven() {
        Diagnostics handed = Diagnostics.root("styles", Diagnostics.Output.NONE, null)
            .child("minecraft:test").child("sit");
        PoseCompiler.Compiled compiled = PoseCompiler.compile(
            Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build(),
            row(humanoid(), EntityPose.NONE), handed);

        assertSame(handed, compiled.diagnostics(),
            "the scope handed in is the scope handed back, and not the compile child under it");
        assertTrue(compiled.diagnostics().entries().stream()
                .anyMatch(entry -> entry.path().equals("styles/minecraft:test/sit/compile")),
            "reading the parent reaches the child's lines, which is why the choice costs nothing "
                + "to a caller reading what one compile said");
    }

    /**
     * The gait readings one style records against the given mesh.
     */
    private static @NotNull List<String> gaitReadingsOf(@NotNull BuiltStyle style,
                                                        @NotNull EntityMesh mesh) {
        Diagnostics scope = Diagnostics.root("styles", Diagnostics.Output.NONE, null)
            .child("minecraft:test").child(style.styleId());
        PoseCompiler.compile(style, row(mesh, EntityPose.NONE), scope);
        return scope.entries().stream()
            .filter(entry -> entry.severity() == Diagnostics.Severity.WARN)
            .map(Diagnostics.Entry::message)
            .filter(message -> message.startsWith("gait: "))
            .toList();
    }

    /**
     * The bones a compile's own clip plays, in channel order.
     */
    private static @NotNull List<String> clipBones(@NotNull PoseCompiler.Compiled compiled) {
        return compiled.pose().clips().getLast().clip().channels().stream()
            .map(PoseClip.Channel::bone)
            .toList();
    }

    /**
     * Holds a drawn point to the derived one, axis by axis.
     */
    private static void assertAt(@NotNull Vector3f expected, @NotNull Vector3f actual, @NotNull String message) {
        assertEquals(expected.x(), actual.x(), DRAWN, message + " (x)");
        assertEquals(expected.y(), actual.y(), DRAWN, message + " (y)");
        assertEquals(expected.z(), actual.z(), DRAWN, message + " (z)");
    }

    /**
     * The compile-time rebase arithmetic, replicated bit for bit.
     */
    private static float extentOf(double absoluteDegrees, float restRadians) {
        return (float) (Math.toRadians(absoluteDegrees) - (double) restRadians);
    }

    /**
     * The evaluator's write-back arithmetic, replicated bit for bit.
     */
    private static float degreesOf(float restRadians, float extentRadians) {
        return (float) Math.toDegrees((float) ((double) restRadians + (double) extentRadians));
    }

    /**
     * The readings of a compile's unreached addresses, in order.
     *
     * @param drops what the compile reported as reaching nothing
     * @return each address as the message would name it
     */
    private static @NotNull List<String> described(
        @NotNull List<PoseCompiler.Unreached> drops) {

        return drops.stream().map(PoseCompiler.Unreached::describe).toList();
    }

}
