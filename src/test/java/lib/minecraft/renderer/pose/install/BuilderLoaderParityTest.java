package lib.minecraft.renderer.pose.install;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleDriver;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pipeline.index.RawEntityPosesFile;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Side;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.dadd;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Loader parity - a hand-authored table spelling what an install weaves, loaded through the real
 * reader, evaluates bit-for-bit against the registrar-built row under one hand-built style row.
 * The two graphs come from two pools, so identity is asserted only within each side and the
 * parity claimed across them is on the evaluated values alone.
 */
@DisplayName("a woven row and its hand-authored table read the same under one style row")
class BuilderLoaderParityTest {

    /**
     * The catalog period both sides frame their excursions against.
     */
    private static final int PERIOD = 24;

    /**
     * The authored table's resource path beside the test sources.
     */
    private static final @NotNull String FIXTURE = "/lib/minecraft/renderer/pose/install/woven_wave_parity.json";

    @Test
    @DisplayName("every bone and channel evaluates equal at ticks 0..23, clip deltas included")
    void loadedAndBuiltRowsEvaluateEqually() {
        EntityModelData mesh = humanoid();
        EntityPose loaded = loadedFixture();
        EntityPose woven = wovenEquivalent(mesh);
        PoseStyle handRow = handRow();

        assertEquals(loaded.bones().keySet(), woven.bones().keySet(),
            "the two rows write the same bones");
        for (int tick = 0; tick < PERIOD; tick++)
            assertEquals(
                PoseKit.posed(loaded, mesh, handRow, PERIOD, tick).getBones(),
                PoseKit.posed(woven, mesh, handRow, PERIOD, tick).getBones(),
                "tick " + tick + " evaluates bit-for-bit across the two graphs");
    }

    @Test
    @DisplayName("sharing holds within each side, and the two sides share no instance")
    void sharingIsScopedPerSide() {
        EntityPose loaded = loadedFixture();
        EntityPose woven = wovenEquivalent(humanoid());

        assertSame(loaded.bones().get("head").get(PoseChannel.Y_ROT),
            loaded.bones().get("hat").get(PoseChannel.Y_ROT),
            "the reader resolves both references to one instance");
        assertSame(woven.bones().get("head").get(PoseChannel.Y_ROT),
            woven.bones().get("hat").get(PoseChannel.Y_ROT),
            "the compiler weaves the hat through the head's instances");
        assertNotSame(loaded.bones().get("head").get(PoseChannel.Y_ROT),
            woven.bones().get("head").get(PoseChannel.Y_ROT),
            "two pools build two graphs - the parity across them is on values");
        assertEquals(MotionSource.SELECT, loaded.clips().getFirst().drive());
        assertEquals(Optional.of("style$wave"), loaded.clips().getFirst().field());
    }

    // ------------------------------------------------------------------------------------

    /**
     * The fixture's pose row, read through the real table reader.
     */
    private static @NotNull EntityPose loadedFixture() {
        try (InputStream held = BuilderLoaderParityTest.class.getResourceAsStream(FIXTURE)) {
            String text = new String(held.readAllBytes(), StandardCharsets.UTF_8);
            return new Gson().fromJson(text, RawEntityPosesFile.class).poses().get("WovenWave");
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read fixture '" + FIXTURE + "'", ex);
        }
    }

    /**
     * The registrar-built equivalent - the same style installed on the same shipped shapes.
     */
    private static @NotNull EntityPose wovenEquivalent(@NotNull EntityModelData mesh) {
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked.
        PoseExpr shippedHead = dadd(constant(0.25d), input("ageInTicks"));
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedHead),
            "hat", Map.of(PoseChannel.Y_ROT, shippedHead)), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", Poses.humanoid("wave")
            .head(head -> head.yaw(15))
            .arm(Side.RIGHT, arm -> arm.pitch(-40)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build());
        return registrar.definitions().get("minecraft:test").pose();
    }

    /**
     * One style row both sides evaluate under - drives the shipped subtree, both splices, the
     * clip gate and its clock, with round extents so the expectation is the graph shape alone.
     */
    private static @NotNull PoseStyle handRow() {
        Map<String, StyleDriver> drivers = new LinkedHashMap<>();
        drivers.put("ageInTicks", new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 0.01f, Optional.empty()));
        drivers.put("style$wave$head$y_rot", new StyleDriver("style$wave$head$y_rot", StyleDriver.Wave.HOLD, 0f, 0.2f, Optional.empty()));
        drivers.put("style$wave$right_arm$x_rot", new StyleDriver("style$wave$right_arm$x_rot", StyleDriver.Wave.HOLD, 0f, -0.5f, Optional.empty()));
        drivers.put("style$wave", new StyleDriver("style$wave", StyleDriver.Wave.HOLD, 0f, 1f, Optional.empty()));
        drivers.put("style$wave$clock", new StyleDriver("style$wave$clock", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty()));
        return new PoseStyle("wave",
            Concurrent.newUnmodifiableList(new PoseStyle.StyleSource(MotionSource.TICK, Optional.empty())),
            Concurrent.newUnmodifiableMap(drivers),
            Concurrent.newUnmodifiableList(), Optional.empty(), Optional.empty());
    }

}
