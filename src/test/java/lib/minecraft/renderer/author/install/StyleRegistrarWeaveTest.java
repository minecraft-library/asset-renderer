package lib.minecraft.renderer.author.install;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.PoseScript;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.author.compile.PoseCompiler;
import lib.minecraft.renderer.author.mesh.LimbRoster;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.pose.ClipDrive;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.exception.StyleException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.fixture.CompilerFixtures.bone;
import static lib.minecraft.renderer.fixture.CompilerFixtures.hatless;
import static lib.minecraft.renderer.fixture.CompilerFixtures.hipped;
import static lib.minecraft.renderer.fixture.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.CompilerFixtures.ridingHat;
import static lib.minecraft.renderer.fixture.CompilerFixtures.stomp;
import static lib.minecraft.renderer.fixture.CompilerFixtures.swelling;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.overlay;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay weave - same-instance passes following the body for free, distinct rows taking
 * per-layer rebased splices under coined coordinates, the strict-or-tolerant fork per row, the
 * skip for a layer nothing lands on, and the recorded weave events at their scope paths.
 */
@DisplayName("an install weaves every pose row the runtime evaluates")
class StyleRegistrarWeaveTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    @Test
    @DisplayName("a same-instance pass re-points at the spliced pose and a distinct row rebases per layer")
    void sameInstanceRepointsAndDistinctRowRebases() {
        EntityMesh body = humanoid();
        EntityMesh wool = humanoid();
        wool.getBones().put("right_arm", bone(-5f, 2f, 0f, -15f, 0f, 25f, 1f, null));
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        EntityPose woolPose = pose(List.of(), Map.of(), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", body, bodyPose, StyleCatalog.BIND_ONLY,
                overlay(humanoid(), bodyPose), overlay(wool, woolPose))));
        registrar.add("minecraft:test",
            Poses.humanoid("raise").arm(Side.RIGHT, arm -> arm.roll(90)).build());

        Entity woven = registrar.definitions().get("minecraft:test");
        assertSame(woven.pose(), woven.overlays().getFirst().pose(),
            "a pass sharing the body's pose instance carries the spliced instance");
        EntityPose layerPose = woven.overlays().getLast().pose();
        assertNotSame(woolPose, layerPose, "a distinct row is rebuilt, not re-pointed");

        PoseStyle installed = woven.styles().byId("raise").orElseThrow();
        assertTrue(installed.drivers().containsKey("style$raise$$layer1$right_arm$z_rot"),
            "a rebased extent is per-row data on a per-layer field");
        assertEquals(90f, PosePlayer.posed(woven.pose(), body, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f);
        assertEquals(90f, PosePlayer.posed(layerPose, wool, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f,
            "two rows' rests differ, and each lands its own delta on the one absolute target");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/raise/weave/$layer1")
                    && entry.message().contains("weave-full")),
            "the whole-row weave records under the coined coordinate's scope");
    }

    @Test
    @DisplayName("a strict half-match weave refuses naming the coined coordinate and each missing bone")
    void strictHalfMatchRefusesNamingTheLayer() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(halfMatchRow()));

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add("minecraft:test", raiseAndLean()));
        assertTrue(refused.getMessage().contains("$layer0"), refused.getMessage());
        assertTrue(refused.getMessage().contains("'body'")
                || refused.getMessage().contains("[body]")
                || refused.getMessage().contains("body"),
            "the missing bone is named: " + refused.getMessage());
    }

    @Test
    @DisplayName("a tolerant half-match weaves the present subset and records the drop")
    void tolerantHalfMatchWeavesTheSubset() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(halfMatchRow()));
        registrar.addTolerant("minecraft:test", raiseAndLean());

        Entity woven = registrar.definitions().get("minecraft:test");
        EntityPose layerPose = woven.overlays().getFirst().pose();
        PoseStyle installed = woven.styles().byId("stretch").orElseThrow();
        assertEquals(90f, PosePlayer.posed(layerPose, woven.overlays().getFirst().model(), installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f,
            "the present bone still weaves");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.path().equals("styles/minecraft:test/stretch/weave/$layer0")
                    && entry.message().contains("weave-subset")
                    && entry.message().contains("body")),
            "the subset weave records each dropped bone");
    }

    @Test
    @DisplayName("a layer sharing no written bone skips whole, untouched by instance")
    void disjointLayerSkipsWhole() {
        EntityMesh wings = new EntityMesh();
        wings.getBones().put("left_wing", bone(2f, 4f, 0f, 0f, 0f, 0f, 1f, null));
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        Entity.OverlayLayer layer = overlay(wings, pose(List.of(), Map.of(), List.of()));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), bodyPose, StyleCatalog.BIND_ONLY, layer)));
        registrar.add("minecraft:test",
            Poses.humanoid("raise").arm(Side.RIGHT, arm -> arm.roll(90)).build());

        assertSame(layer, registrar.definitions().get("minecraft:test").overlays().getFirst(),
            "nothing lands, so the pass is left as it was");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.WARN
                    && entry.path().equals("styles/minecraft:test/raise/weave/$layer0")
                    && entry.message().contains("weave-skip")),
            "and the skip records under the coined coordinate's scope");
    }

    @Test
    @DisplayName("a woven layer shares the body's turn-delta reads and its play-site instance")
    void wovenLayerSharesFieldReadsAndPlaySite() {
        EntityMesh wool = humanoid();
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), bodyPose, StyleCatalog.BIND_ONLY,
                overlay(wool, pose(List.of(), Map.of(), List.of())))));
        registrar.add("minecraft:test", Poses.humanoid("bounce")
            .arm(Side.RIGHT, arm -> arm.pitchBy(-10)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build());

        Entity woven = registrar.definitions().get("minecraft:test");
        EntityPose layerPose = woven.overlays().getFirst().pose();
        PoseExpr.Op onBody = assertInstanceOf(PoseExpr.Op.class,
            woven.pose().bones().get("right_arm").get(PoseChannel.X_ROT));
        PoseExpr.Op onLayer = assertInstanceOf(PoseExpr.Op.class,
            layerPose.bones().get("right_arm").get(PoseChannel.X_ROT));
        assertSame(onBody.operands().getLast(), onLayer.operands().getLast(),
            "a turn's delta splice is row-independent, so both rows read one interned field node");

        EntityPose.Clip bodySite = woven.pose().clips().getLast();
        EntityPose.Clip layerSite = layerPose.clips().getLast();
        assertSame(bodySite, layerSite, "one play site serves every woven row");
        assertSame(bodySite.clip(), layerSite.clip(), "and one clip table rides it");
        assertEquals(ClipDrive.SELECT, bodySite.drive());
    }

    @Test
    @DisplayName("a pass counts the head's hat copy as written only where its hat sits outside the head's chain")
    void aPassCountsTheHatCopyOnlyOffTheHeadsChain() {
        EntityMesh shell = humanoid();
        shell.getBones().put("hat", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "head"));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
                overlay(shell, pose(List.of(), Map.of(), List.of())),
                overlay(humanoid(), pose(List.of(), Map.of(), List.of())))));
        registrar.add("minecraft:test", Poses.humanoid("nod").head(head -> head.yaw(35)).build());

        Entity woven = registrar.definitions().get("minecraft:test");
        assertTrue(woven.pose().bones().containsKey("hat"), "the body's top-level hat takes the head's mirror");
        assertFalse(woven.overlays().getFirst().pose().bones().containsKey("hat"),
            "the first pass's hat hangs from its head and takes nothing");
        assertTrue(woven.overlays().getLast().pose().bones().containsKey("hat"),
            "the second pass's top-level hat takes the mirror");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/nod/weave/$layer0")
                    && entry.message().contains("woven whole - 1 written bone(s)")),
            "so the first pass's weave counts the head alone");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == Diagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/nod/weave/$layer1")
                    && entry.message().contains("woven whole - 2 written bone(s)")),
            "and the second's counts the head and the copy its hat takes");
    }

    @Test
    @DisplayName("a scale collision on a woven layer row's own clips refuses at install")
    void layerClipScaleCollisionRefuses() {
        EntityMesh wool = humanoid();
        PoseClip puff = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("right_arm", PoseChannel.Kind.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0.1f, 0.1f, 0.1f, PoseClip.Interpolation.LINEAR)))));
        EntityPose woolPose = pose(List.of(), Map.of(), List.of(
            new EntityPose.Clip("LayerAnimation#PUFF", ClipDrive.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), puff)));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, overlay(wool, woolPose))));

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add("minecraft:test",
                Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build()));
        assertTrue(refused.getMessage().contains("LayerAnimation#PUFF"),
            "the layer's own clip is the collision named: " + refused.getMessage());
    }

    @Test
    @DisplayName("a selected scale on a distinct pass is scanned on the pass's own mesh, whose roster answers the leg its clip scales")
    void aSelectedScaleOnADistinctPassIsScannedOnThePassesMesh() {
        // The pass's mesh swaps the hips front to back, so its roster answers the right hind leg
        // for the front right address the body answers with its front right leg.
        EntityMesh swapped = hipped(7f, -5f);
        assertRosterAnswers(hipped(), "right_front_leg");
        assertRosterAnswers(swapped, "right_hind_leg");
        EntityPose passPose = swelling("body", "right_hind_leg");
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", hipped(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
                overlay(swapped, passPose))));

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add("minecraft:test", stomp()));
        assertTrue(refused.getMessage().contains("'right_hind_leg'")
                && refused.getMessage().contains("FixtureAnimation#SWELL"),
            "the scan names the leg the pass's compile scales and the pass's clip scaling it: "
                + refused.getMessage());

        // Compiled past the scan, the pass's compile scales the leg its own roster answers, so the
        // refusal front-runs a render failure.
        PoseCompiler.Compiled compiled = PoseCompiler.compileLayer(stomp(), passPose, swapped, "$layer0",
            Diagnostics.root("styles", Diagnostics.Output.NONE, null));
        RendererException thrown = assertThrows(RendererException.class,
            () -> PosePlayer.posed(compiled.pose(), swapped, compiled.style(), PERIOD, 0));
        assertTrue(thrown.getMessage().contains("bone 'right_hind_leg' is scaled by its model and by a clip"),
            thrown.getMessage());
    }

    @Test
    @DisplayName("a leg the body's roster answers does not refuse a distinct pass whose own roster answers another")
    void aLegTheBodyAnswersDoesNotRefuseADistinctPass() {
        EntityMesh swapped = hipped(7f, -5f);
        assertRosterAnswers(swapped, "right_hind_leg");
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", hipped(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
                overlay(swapped, swelling("body", "right_front_leg")))));
        assertDoesNotThrow(() -> registrar.add("minecraft:test", stomp()),
            "the pass's compile scales its right hind leg, which no clip of the pass scales");

        Entity woven = registrar.definitions().get("minecraft:test");
        PoseStyle installed = woven.styles().byId("stomp").orElseThrow();
        EntityMesh posed = assertDoesNotThrow(() ->
            PosePlayer.posed(woven.overlays().getFirst().pose(), swapped, installed, PERIOD, 0));
        assertEquals(new Vector3f(1.5f, 1.5f, 1.5f), posed.getBones().get("right_hind_leg").getPoseScale(),
            "the leg the pass's roster answers takes the authored scale");
        assertEquals(new Vector3f(1.25f, 1.25f, 1.25f), posed.getBones().get("right_front_leg").getPoseScale(),
            "and the leg the clip scales carries the clip's scale alone");
    }

    @Test
    @DisplayName("a head scale refuses where a distinct pass's top-level hat takes the head's copy and its own clip scales that hat, though the body's hat rides its head")
    void aPassesTopLevelHatIsScannedOnThePassesMesh() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", ridingHat(), pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
                overlay(humanoid(), swelling("body", "hat")))));

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add("minecraft:test", Poses.humanoid("bulk").head(head -> head.scale(1.5)).build()));
        assertTrue(refused.getMessage().contains("'hat'") && refused.getMessage().contains("FixtureAnimation#SWELL"),
            "the pass's own clip is the collision named: " + refused.getMessage());
    }

    @Test
    @DisplayName("a pass sharing its body's pose refuses where its hat rides the head and the body's top-level hat takes the head's copy")
    void aSharedPassWhoseHatRelationDiffersRefuses() {
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), bodyPose, StyleCatalog.BIND_ONLY, overlay(ridingHat(), bodyPose))));

        StyleException refused = assertThrows(StyleException.class,
            () -> registrar.add("minecraft:test", nod()));
        assertTrue(refused.getMessage().contains("for a mesh whose hat hangs apart from the head")
                && refused.getMessage().contains("layer '$layer0' draws a hat that rides the head")
                && refused.getMessage().contains("move twice with the head"),
            "the refusal names the body's hat, the pass and its hat: " + refused.getMessage());

        EntityPose hatlessPose = pose(List.of(), Map.of(), List.of());
        assertDoesNotThrow(() -> StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(), hatlessPose,
                StyleCatalog.BIND_ONLY, overlay(hatless(), hatlessPose)))).add("minecraft:test", nod()),
            "a hatless pass has no hat to place, so it installs");
    }

    @Test
    @DisplayName("a pass sharing a hatless body's pose plays a head timeline its riding hat follows once, the clip copying nothing onto a hat")
    void aSharedRidingHatUnderAHatlessBodyTakesNoClipCopy() {
        EntityPose bodyPose = pose(List.of(), Map.of(), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", hatless(), bodyPose, StyleCatalog.BIND_ONLY, overlay(ridingHat(), bodyPose))));
        assertDoesNotThrow(() -> registrar.add("minecraft:test", nodding()));

        Entity woven = registrar.definitions().get("minecraft:test");
        assertEquals(List.of("head"), woven.pose().clips().getLast().clip().channels().stream()
                .map(PoseClip.Channel::bone).distinct().toList(),
            "the head's chain carries the pass's riding hat, so no channel is copied to a hat");
    }

    @Test
    @DisplayName("a distinct pass whose hat relation differs from its body's refuses a head timeline the body's clip decides")
    void aDistinctPassRefusesAHeadTimelineDecidedOnAnotherHat() {
        StyleException leftBehind = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions(entity("minecraft:test", hatless(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, overlay(humanoid(), pose(List.of(), Map.of(), List.of())))))
                .add("minecraft:test", nodding()));
        assertTrue(leftBehind.getMessage().contains("layer '$layer0'") && leftBehind.getMessage().contains("stay behind"),
            "a top-level hat the body's clip never copies to is left behind: " + leftBehind.getMessage());

        StyleException twice = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, overlay(ridingHat(), pose(List.of(), Map.of(), List.of())))))
                .add("minecraft:test", nodding()));
        assertTrue(twice.getMessage().contains("layer '$layer0'") && twice.getMessage().contains("twice"),
            "a riding hat the body's clip copies to would move twice: " + twice.getMessage());
    }

    @Test
    @DisplayName("a distinct pass's no-hat alternate refuses where its hat relation differs from the pass's own")
    void aNoHatAlternateWhoseHatRelationDiffersRefuses() {
        StyleException refused = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, withNoHat(overlay(humanoid(), pose(List.of(), Map.of(), List.of())), ridingHat()))))
                .add("minecraft:test", nod()));
        assertTrue(refused.getMessage().contains("no-hat alternate of layer '$layer0'"),
            "the refusal names the alternate: " + refused.getMessage());

        assertDoesNotThrow(() -> StyleRegistrar.of(definitions(entity("minecraft:test", humanoid(),
                pose(List.of(), Map.of(), List.of()), StyleCatalog.BIND_ONLY,
                withNoHat(overlay(humanoid(), pose(List.of(), Map.of(), List.of())), hatless()))))
            .add("minecraft:test", nod()), "a hatless alternate installs");
    }

    @Test
    @DisplayName("a pass reusing another form's weave of one pose row refuses where its hat relation differs from the mesh that weave ran on")
    void aPassReusingAnotherFormsWeaveRefusesOnADifferentHat() {
        StyleException refused = assertThrows(StyleException.class,
            () -> StyleRegistrar.of(definitions(coated(ridingHat(), humanoid()))).add("minecraft:test", nod()));
        assertTrue(refused.getMessage().contains("layer '$variant:red$layer0'"),
            "the refusal names the coat's pass: " + refused.getMessage());

        assertDoesNotThrow(() -> StyleRegistrar.of(definitions(coated(ridingHat(), ridingHat())))
            .add("minecraft:test", nod()), "two passes hanging their hats alike share the weave");
    }

    @Test
    @DisplayName("the sheep's wool pass follows an absolute write through its own rebased row")
    void sheepWoolFollowsTheBody() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:sheep", Poses.custom("tip")
            .bone("head", head -> head.pitch(-30))
            .build());

        Entity sheep = registrar.definitions().get("minecraft:sheep");
        PoseStyle installed = sheep.styles().byId("tip").orElseThrow();
        Entity.OverlayLayer wool = sheep.overlays().stream()
            .filter(layer -> layer.pose() != sheep.pose())
            .findFirst()
            .orElseThrow();
        assertEquals(-30f, PosePlayer.posed(sheep.pose(), sheep.model(), installed, PERIOD, 0)
            .getBones().get("head").getRotation().pitch(), 1e-3f);
        assertEquals(-30f, PosePlayer.posed(wool.pose(), wool.model(), installed, PERIOD, 0)
            .getBones().get("head").getRotation().pitch(), 1e-3f,
            "the wool row rebases against its own rest and lands the same absolute target");
    }

    @Test
    @DisplayName("the stray's clothing pass poses identically to the body on every shared bone")
    void strayClothingFollowsTheBody() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:stray", Poses.humanoid("stretch")
            .arms(arm -> arm.pitch(-170))
            .build());

        Entity stray = registrar.definitions().get("minecraft:stray");
        PoseStyle installed = stray.styles().byId("stretch").orElseThrow();
        Entity.OverlayLayer clothing = stray.overlays().stream()
            .filter(layer -> layer.pose() != stray.pose())
            .findFirst()
            .orElseThrow();
        EntityMesh body = PosePlayer.posed(stray.pose(), stray.model(), installed, PERIOD, 0);
        EntityMesh clothed = PosePlayer.posed(clothing.pose(), clothing.model(), installed, PERIOD, 0);
        for (String arm : List.of("right_arm", "left_arm")) {
            assertEquals(-170f, body.getBones().get(arm).getRotation().pitch(), 1e-3f);
            assertEquals(body.getBones().get(arm).getRotation().pitch(),
                clothed.getBones().get(arm).getRotation().pitch(), 1e-4f,
                "'" + arm + "' lands where the body's does");
        }
    }

    @Test
    @DisplayName("the breeze's disjoint wind pass follows by instance and filters cleanly")
    void breezeWindFollowsByInstanceAndFilters() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:breeze", Poses.custom("peer")
            .bone("head", head -> head.pitchBy(6))
            .build());

        Entity breeze = registrar.definitions().get("minecraft:breeze");
        PoseStyle installed = breeze.styles().byId("peer").orElseThrow();
        for (Entity.OverlayLayer layer : breeze.overlays())
            assertSame(breeze.pose(), layer.pose(),
                "every breeze pass shares the body's pose instance and follows the weave");
        Entity.OverlayLayer wind = breeze.overlays().stream()
            .filter(layer -> layer.model().getBones().containsKey("wind_body"))
            .findFirst()
            .orElseThrow();
        assertDoesNotThrow(() -> PosePlayer.posed(breeze.pose(), wind.model(), installed, PERIOD, 0),
            "the disjoint roster takes what it declares and drops the rest");
    }

    @Test
    @DisplayName("a tolerant weave drives motion on a bone only the layer's mesh declares")
    void tolerantWeaveDrivesALayerOnlyBone() {
        EntityMesh plumed = humanoid();
        plumed.getBones().put("plume", bone(0f, 2f, 0f, 0f, 0f, 0f, 1f, null));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, overlay(plumed, pose(List.of(), Map.of(), List.of())))));
        registrar.addTolerant("minecraft:test", Poses.custom("nod")
            .bone("plume", plume -> plume.sway(Turn.PITCH, -10, 10))
            .build());

        Entity woven = registrar.definitions().get("minecraft:test");
        PoseStyle installed = woven.styles().byId("nod").orElseThrow();
        StyleDriver sway = installed.drivers().get("style$nod$plume$x_rot");
        assertNotNull(sway, "the body dropped the bone, so the layer compile lands the shared driver");
        assertEquals(StyleDriver.Wave.SWEEP, sway.wave());
        EntityPose layerPose = woven.overlays().getFirst().pose();
        assertEquals(-10f, PosePlayer.posed(layerPose, plumed, installed, PERIOD, 0)
            .getBones().get("plume").getRotation().pitch(), 1e-3f,
            "the sway rests at its near bound at tick zero");
        assertEquals(10f, PosePlayer.posed(layerPose, plumed, installed, PERIOD, PERIOD / 2)
            .getBones().get("plume").getRotation().pitch(), 1e-3f,
            "and peaks mid-window on the layer rather than resting at zero");
    }

    // ------------------------------------------------------------------------------------

    /**
     * A row whose one distinct-row pass declares the arm but not the torso.
     */
    private static @NotNull Entity halfMatchRow() {
        EntityMesh partial = humanoid();
        partial.getBones().remove("body");
        return entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
            StyleCatalog.BIND_ONLY, overlay(partial, pose(List.of(), Map.of(), List.of())));
    }

    /**
     * A row whose body and one variant coat draw one mesh, each carrying a distinct-row pass on one
     * shared pose instance - the coat's pass over a mesh of its own.
     *
     * @param pass the body's pass mesh
     * @param coatPass the coat's pass mesh
     * @return the row
     */
    private static @NotNull Entity coated(@NotNull EntityMesh pass, @NotNull EntityMesh coatPass) {
        EntityPose layerPose = pose(List.of(), Map.of(), List.of());
        Entity bare = entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
            StyleCatalog.BIND_ONLY, overlay(pass, layerPose));
        Entity coat = bare.mutate().overlays(Concurrent.newUnmodifiableList(overlay(coatPass, layerPose))).build();
        LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
        coats.put("red", coat);
        Entity.Axes axes = bare.axes();
        return bare.mutate()
            .axes(new Entity.Axes(axes.baby(), axes.shape(), axes.state(), axes.size(),
                new Entity.Variation<>(Concurrent.newUnmodifiableLinkedMap(coats), Optional.empty())))
            .build();
    }

    /**
     * The same pass with a no-hat alternate.
     */
    private static @NotNull Entity.OverlayLayer withNoHat(@NotNull Entity.OverlayLayer layer,
                                                          @NotNull EntityMesh alternate) {
        return new Entity.OverlayLayer(layer.model(), layer.textureRef(), layer.pass(), layer.tintArgb(),
            layer.skipBounds(), layer.tintBy(), layer.textureBy(), layer.gate(), Optional.of(alternate),
            layer.pose(), layer.textureScroll());
    }

    /**
     * Holds a precondition of the selector cases: the front right address answers the given leg
     * on the mesh.
     */
    private static void assertRosterAnswers(@NotNull EntityMesh mesh, @NotNull String leg) {
        PoseScript.Limb.Selected selected = assertInstanceOf(PoseScript.Limb.Selected.class,
            stomp().script().stances().getFirst().limb().orElseThrow());
        assertEquals(List.of(leg), List.copyOf(LimbRoster.members(selected.selector(), mesh, () -> LimbRoster.of(mesh))),
            "the front right address answers '" + leg + "' on this mesh");
    }

    /**
     * A humanoid head turned, which the head's automatic copy carries to the hat.
     */
    private static @NotNull BuiltStyle nod() {
        return Poses.humanoid("nod").head(head -> head.yaw(35)).build();
    }

    /**
     * A humanoid head swung on a timeline, which the head's automatic copy carries to the hat.
     */
    private static @NotNull BuiltStyle nodding() {
        return Poses.humanoid("nod")
            .head(head -> head.timeline(track -> track.swing(Turn.PITCH, -10, 10).over(0.6)))
            .build();
    }

    /**
     * A style writing the right arm and the torso - a half match for {@link #halfMatchRow()}.
     */
    private static @NotNull BuiltStyle raiseAndLean() {
        return Poses.humanoid("stretch")
            .arm(Side.RIGHT, arm -> arm.roll(90))
            .torso(torso -> torso.pitch(12))
            .build();
    }

}
