package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseClip;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.install.StyleRegistrar;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.catalog;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.entity;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.styleRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container fold seat, measured as a sum: on a row whose shipped clips displace the
 * container, a custom container style's channels and the shipped displacement land on ONE seat,
 * and the folded values decompose exactly into the shipped-alone and custom-alone contributions.
 * The shipped displacement must PLAY for the measurement to mean anything - the camel arm rides
 * the copied stride trio and the breeze arm drives its one-hot gate by hand, a measurement rig
 * off the authoring surface.
 */
@DisplayName("the fold seat composes shipped displacement and custom container channels additively")
class FoldSeatAbTest {

    /**
     * The catalog period every excursion frames against.
     */
    private static final int PERIOD = 24;

    /**
     * The eight strip ticks an animated schedule samples.
     */
    private static final int[] STRIP_TICKS = {0, 3, 6, 9, 12, 15, 18, 21};

    @Test
    @DisplayName("on the camel the walk roll and the hover ride one seat, each contribution exact")
    void camelFoldComposesWalkAndHover() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:camel", Poses.custom("levitate")
            .hover(8, 2)
            .keepStride()
            .build());
        Entity woven = registrar.definitions().get("minecraft:camel");
        Entity shipped = EntityModelLoader.load().get("minecraft:camel");
        PoseStyle installed = woven.styles().byId("levitate").orElseThrow();
        PoseStyle strideOnly = styleRow("stride_rig", strideTrio());
        PoseStyle hoverOnly = styleRow("hover_rig", hoverFields(installed, "levitate"));

        boolean rolled = false;
        for (int tick : STRIP_TICKS) {
            EntityModelData.Bone folded = seat(PoseKit.posed(woven.pose(), woven.model(), installed, PERIOD, tick));
            EntityModelData.Bone walkAlone = seat(PoseKit.posed(shipped.pose(), shipped.model(), strideOnly, PERIOD, tick));
            EntityModelData.Bone hoverAlone = seat(PoseKit.posed(woven.pose(), woven.model(), hoverOnly, PERIOD, tick));

            assertEquals(hoverAlone.getPivot().y(), folded.getPivot().y(),
                "tick " + tick + ": the vertical is the hover's alone");
            assertEquals(walkAlone.getRotation().roll(), folded.getRotation().roll(),
                "tick " + tick + ": the roll is the shipped displacement's alone");
            assertEquals(0f, hoverAlone.getRotation().roll(),
                "tick " + tick + ": an undriven walk trio caps the shipped amplitude at zero");
            rolled |= walkAlone.getRotation().roll() != 0f;
        }
        assertTrue(rolled, "the copied stride trio makes the shipped displacement play");
    }

    @Test
    @DisplayName("without the stride trio the camel seat carries the hover alone - the vacuous arm")
    void camelWithoutStrideCarriesHoverAlone() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:camel", Poses.custom("levitate_rest")
            .hover(8, 2)
            .build());
        Entity woven = registrar.definitions().get("minecraft:camel");
        PoseStyle installed = woven.styles().byId("levitate_rest").orElseThrow();
        StyleDriver bob = installed.drivers().get("style$levitate_rest$$container$y_bob");
        assertNotNull(bob);

        for (int tick : STRIP_TICKS) {
            EntityModelData.Bone folded = seat(PoseKit.posed(woven.pose(), woven.model(), installed, PERIOD, tick));
            assertEquals(0f, folded.getRotation().roll(),
                "tick " + tick + ": nothing drives the walk, so nothing rolls");
            assertEquals((float) (-8d + (double) bob.at(tick, PERIOD)), folded.getPivot().y(),
                "tick " + tick + ": the seat is the lift plus the swept bob and nothing else");
        }
    }

    @Test
    @DisplayName("on the breeze the shove and the hover sum on one seat under the hand-driven gate")
    void breezeFoldComposesShoveAndHover() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:breeze", Poses.custom("hover_shove")
            .hover(8, 2)
            .build());
        Entity woven = registrar.definitions().get("minecraft:breeze");
        Entity shipped = EntityModelLoader.load().get("minecraft:breeze");
        PoseStyle installed = woven.styles().byId("hover_shove").orElseThrow();

        // The one-hot state fields have no surface verb, so the rig augments the compiled
        // drivers by hand: the gate held at one and elapsed age ramping, off the authoring
        // surface on purpose.
        Map<String, StyleDriver> shove = new LinkedHashMap<>();
        shove.put("shoot", new StyleDriver("shoot", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
        shove.put("ageInTicks", new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        Map<String, StyleDriver> augmented = new LinkedHashMap<>(hoverFields(installed, "hover_shove"));
        augmented.putAll(shove);
        PoseStyle foldedRig = styleRow("shove_rig", augmented);
        PoseStyle shoveRig = styleRow("shoot_rig", shove);
        PoseStyle hoverRig = styleRow("hover_rig", hoverFields(installed, "hover_shove"));

        // Half a second into the shove the body clip holds its pitch and shoves back and down.
        int tick = 10;
        EntityModelData.Bone folded = seat(PoseKit.posed(woven.pose(), woven.model(), foldedRig, PERIOD, tick));
        EntityModelData.Bone shoveAlone = seat(PoseKit.posed(shipped.pose(), shipped.model(), shoveRig, PERIOD, tick));
        EntityModelData.Bone hoverAlone = seat(PoseKit.posed(woven.pose(), woven.model(), hoverRig, PERIOD, tick));

        assertTrue(shoveAlone.getPivot().y() != 0f, "the shipped displacement plays under the rig");
        assertTrue(hoverAlone.getPivot().y() != 0f, "and the hover contributes its own vertical");
        assertEquals(hoverAlone.getPivot().y() + shoveAlone.getPivot().y(), folded.getPivot().y(),
            "the overlapping vertical is the exact float sum of the two contributions");
        assertEquals(shoveAlone.getPivot().z(), folded.getPivot().z(),
            "the depth shove is the shipped contribution alone");
        assertEquals(shoveAlone.getRotation().pitch(), folded.getRotation().pitch(),
            "and so is the pitch, on the one merged seat");
        assertEquals(0f, hoverAlone.getPivot().z());
    }

    @Test
    @DisplayName("a shipped step a clip displaces poses bit-identically before and after the fold-in")
    void spliceArmKeepsShippedStylesBitIdentical() {
        EntityModelData mesh = humanoid();
        PoseClip sway = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("root", PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(0.5f, 0f, 0f, 0.05f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR)))));
        EntityPose shipped = pose(
            List.of(Map.of(PoseChannel.X_ROT, constant(0.1d))),
            Map.of(),
            List.of(new EntityPose.Clip("FixtureAnimation#SWAY", MotionSource.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), sway)));
        PoseStyle wob = styleRow("wob", strideTrio());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, catalog(wob))));
        registrar.add("minecraft:test", Poses.humanoid("perch")
            .container(step -> step.yawBy(10))
            .build());
        Entity woven = registrar.definitions().get("minecraft:test");

        assertEquals(1, woven.pose().container().size(), "no step appends below a shipped seat");
        for (int tick : STRIP_TICKS)
            assertEquals(
                PoseKit.posed(shipped, mesh, wob, PERIOD, tick).getBones(),
                PoseKit.posed(woven.pose(), mesh, woven.styles().byId("wob").orElseThrow(), PERIOD, tick).getBones(),
                "tick " + tick + ": the folded channels rest at zero under a shipped style");
    }

    @Test
    @DisplayName("a fold-seat install records the folded channels and the displacing clips")
    void foldSeatInstallRecordsItsEvent() {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.add("minecraft:camel", Poses.custom("levitate").hover(8, 2).keepStride().build());
        registrar.add("minecraft:breeze", Poses.custom("hover_shove").hover(8, 2).build());

        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:camel/levitate/install")
                    && entry.message().contains("fold-seat")
                    && entry.message().contains("y")
                    && entry.message().contains("CamelAnimation#CAMEL_WALK")),
            "the camel event names the folded channel and the displacing clip");
        assertTrue(registrar.diagnostics().entries().stream().anyMatch(entry ->
                entry.severity() == StyleDiagnostics.Severity.INFO
                    && entry.path().equals("styles/minecraft:breeze/hover_shove/install")
                    && entry.message().contains("fold-seat")
                    && entry.message().contains("BreezeAnimation#SHOOT")),
            "the breeze event names its one-hot displacing clips");
    }

    // ------------------------------------------------------------------------------------

    /**
     * The one seat a posed mesh carries - the synthetic container bone, asserted singular.
     */
    private static EntityModelData.@NotNull Bone seat(@NotNull EntityModelData posed) {
        EntityModelData.Bone bone = posed.getBones().get("$container");
        assertNotNull(bone, "the seat bone exists");
        assertFalse(posed.getBones().containsKey("$container_"),
            "one seat carries everything - the fold never re-seats");
        return bone;
    }

    /**
     * The universal stride trio, spelled exactly as the ridden drivers are.
     */
    private static @NotNull Map<String, StyleDriver> strideTrio() {
        Map<String, StyleDriver> drivers = new LinkedHashMap<>();
        drivers.put("ageInTicks", new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        drivers.put("walkAnimationSpeed", new StyleDriver("walkAnimationSpeed", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
        drivers.put("walkAnimationPos", new StyleDriver("walkAnimationPos", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        return drivers;
    }

    /**
     * The installed row's two hover fields, copied so a rig can drive them alone.
     */
    private static @NotNull Map<String, StyleDriver> hoverFields(@NotNull PoseStyle installed, @NotNull String styleId) {
        Map<String, StyleDriver> drivers = new LinkedHashMap<>();
        for (String field : List.of(
            "style$" + styleId + "$$container$y",
            "style$" + styleId + "$$container$y_bob")) {
            StyleDriver driver = installed.drivers().get(field);
            assertNotNull(driver, "the hover driver '" + field + "' rides the installed row");
            drivers.put(field, driver);
        }
        return drivers;
    }

}
