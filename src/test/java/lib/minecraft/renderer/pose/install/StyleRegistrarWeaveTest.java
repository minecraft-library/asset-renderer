package lib.minecraft.renderer.pose.install;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.pose.compile.StyleDiagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.bone;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.entity;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.overlay;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        EntityModelData body = humanoid();
        EntityModelData wool = humanoid();
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
        assertEquals(90f, PoseKit.posed(woven.pose(), body, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f);
        assertEquals(90f, PoseKit.posed(layerPose, wool, installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f,
            "two rows' rests differ, and each lands its own delta on the one absolute target");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/raise/weave/$layer1")
                    && entry.message().contains("weave-full")),
            "the whole-row weave records under the coined coordinate's scope");
    }

    @Test
    @DisplayName("a strict half-match weave refuses naming the coined coordinate and each missing bone")
    void strictHalfMatchRefusesNamingTheLayer() {
        StyleRegistrar registrar = StyleRegistrar.of(definitions(halfMatchRow()));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
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
        assertEquals(90f, PoseKit.posed(layerPose, woven.overlays().getFirst().model(), installed, PERIOD, 0)
            .getBones().get("right_arm").getRotation().roll(), 1e-4f,
            "the present bone still weaves");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.WARN
                    && entry.path().equals("styles/minecraft:test/stretch/weave/$layer0")
                    && entry.message().contains("weave-subset")
                    && entry.message().contains("body")),
            "the subset weave records each dropped bone");
    }

    @Test
    @DisplayName("a layer sharing no written bone skips whole, untouched by instance")
    void disjointLayerSkipsWhole() {
        EntityModelData wings = new EntityModelData();
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
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:test/raise/weave/$layer0")
                    && entry.message().contains("weave-skip")),
            "and the skip records under the coined coordinate's scope");
    }

    @Test
    @DisplayName("a woven layer shares the body's field reads and its play-site instance")
    void wovenLayerSharesFieldReadsAndPlaySite() {
        EntityModelData wool = humanoid();
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
            "a delta splice is row-independent, so both rows read one interned field node");

        EntityPose.Clip bodySite = woven.pose().clips().getLast();
        EntityPose.Clip layerSite = layerPose.clips().getLast();
        assertSame(bodySite, layerSite, "one play site serves every woven row");
        assertSame(bodySite.clip(), layerSite.clip(), "and one clip table rides it");
        assertEquals(MotionSource.SELECT, bodySite.drive());
    }

    @Test
    @DisplayName("a scale collision on a woven layer row's own clips refuses at install")
    void layerClipScaleCollisionRefuses() {
        EntityModelData wool = humanoid();
        PoseClip puff = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("right_arm", PoseClip.Target.SCALE, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0.1f, 0.1f, 0.1f, PoseClip.Interpolation.LINEAR)))));
        EntityPose woolPose = pose(List.of(), Map.of(), List.of(
            new EntityPose.Clip("LayerAnimation#PUFF", MotionSource.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), puff)));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
                StyleCatalog.BIND_ONLY, overlay(wool, woolPose))));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> registrar.add("minecraft:test",
                Poses.humanoid("bulk").arm(Side.RIGHT, arm -> arm.scale(1.5)).build()));
        assertTrue(refused.getMessage().contains("LayerAnimation#PUFF"),
            "the layer's own clip is the collision named: " + refused.getMessage());
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
        assertEquals(-30f, PoseKit.posed(sheep.pose(), sheep.model(), installed, PERIOD, 0)
            .getBones().get("head").getRotation().pitch(), 1e-3f);
        assertEquals(-30f, PoseKit.posed(wool.pose(), wool.model(), installed, PERIOD, 0)
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
        EntityModelData body = PoseKit.posed(stray.pose(), stray.model(), installed, PERIOD, 0);
        EntityModelData clothed = PoseKit.posed(clothing.pose(), clothing.model(), installed, PERIOD, 0);
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
        assertDoesNotThrow(() -> PoseKit.posed(breeze.pose(), wind.model(), installed, PERIOD, 0),
            "the disjoint roster takes what it declares and drops the rest");
    }

    @Test
    @DisplayName("a tolerant weave drives motion on a bone only the layer's mesh declares")
    void tolerantWeaveDrivesALayerOnlyBone() {
        EntityModelData plumed = humanoid();
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
        assertEquals(-10f, PoseKit.posed(layerPose, plumed, installed, PERIOD, 0)
            .getBones().get("plume").getRotation().pitch(), 1e-3f,
            "the sway rests at its near bound at tick zero");
        assertEquals(10f, PoseKit.posed(layerPose, plumed, installed, PERIOD, PERIOD / 2)
            .getBones().get("plume").getRotation().pitch(), 1e-3f,
            "and peaks mid-window on the layer rather than resting at zero");
    }

    // ------------------------------------------------------------------------------------

    /**
     * A row whose one distinct-row pass declares the arm but not the torso.
     */
    private static @NotNull Entity halfMatchRow() {
        EntityModelData partial = humanoid();
        partial.getBones().remove("body");
        return entity("minecraft:test", humanoid(), pose(List.of(), Map.of(), List.of()),
            StyleCatalog.BIND_ONLY, overlay(partial, pose(List.of(), Map.of(), List.of())));
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
