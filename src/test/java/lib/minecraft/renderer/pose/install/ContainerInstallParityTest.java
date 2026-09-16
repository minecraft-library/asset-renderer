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
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pose.MotionSource;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Turn;
import lib.minecraft.renderer.pose.compile.CompilerFixtures;
import lib.minecraft.renderer.pose.compile.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.dadd;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.humanoid;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.input;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.pose;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.catalog;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.pose.install.RegistrarFixtures.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bit parity across a container-carrying install - the added step changes the bone roster, so
 * the shipped-style assertion runs bone-matched by name: every pre-install bone keeps its exact
 * bits with only its parent allowed to move under the seat, and the seat itself evaluates as an
 * identity transform under every shipped style.
 */
@DisplayName("a container install leaves every shipped style's bits where they were")
class ContainerInstallParityTest {

    /**
     * The catalog period every fixture row frames its excursions against.
     */
    private static final int PERIOD = 24;

    /**
     * The eight strip ticks an animated schedule samples.
     */
    private static final int[] STRIP_TICKS = {0, 3, 6, 9, 12, 15, 18, 21};

    @Test
    @DisplayName("every pre-install bone matches by bits under each shipped style, parent aside")
    void shippedStylesMatchBoneForBoneAcrossTheRosterChange() {
        EntityModelData mesh = humanoid();
        // The shipped table writes head and hat with one shared moving instance, plus a bone
        // resting at a negative zero - the one sign a woven splice may flip.
        PoseExpr shippedHead = dadd(constant(0.02d), input("ageInTicks"));
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedHead),
            "hat", Map.of(PoseChannel.Y_ROT, shippedHead),
            "body", Map.of(PoseChannel.Y_ROT, constant(-0.0d))), List.of());
        PoseStyle wob = RegistrarFixtures.styleRow("wob", Map.of("ageInTicks",
            new StyleDriver("ageInTicks", StyleDriver.Wave.RAMP, 0f, 1f, Optional.empty())));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, catalog(wob))));
        registrar.add("minecraft:test",
            Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build());
        Entity woven = registrar.definitions().get("minecraft:test");

        EntityOptions options = EntityOptions.of("minecraft:test");
        for (String styleId : List.of("wob", PoseStyle.IDLE, PoseStyle.STRIDE)) {
            PoseStyle row = woven.styles().resolve(styleId, options);
            for (int tick : STRIP_TICKS) {
                EntityModelData before = PoseKit.posed(shipped, mesh, row, PERIOD, tick);
                EntityModelData after = PoseKit.posed(woven.pose(), mesh, row, PERIOD, tick);
                assertRosterGrewByTheSeat(before, after, styleId, tick);
                for (Map.Entry<String, EntityModelData.Bone> named : before.getBones().entrySet())
                    assertMatchedBits(named.getKey(), named.getValue(),
                        after.getBones().get(named.getKey()), styleId, tick);
                assertSeatIsIdentity(after.getBones().get("$container"), styleId, tick);
            }
        }
    }

    @Test
    @DisplayName("a former root re-parents under the seat and nothing else moves its parent")
    void onlyFormerRootsChangeParent() {
        EntityModelData mesh = humanoid();
        mesh.getBones().put("nose", CompilerFixtures.bone(0f, 1f, -2f, 0f, 0f, 0f, 1f, "head"));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, EntityPose.NONE, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test",
            Poses.humanoid("sit").container(step -> step.offset(0, 7, 0)).build());
        Entity woven = registrar.definitions().get("minecraft:test");

        PoseStyle idle = woven.styles().resolve(PoseStyle.IDLE, EntityOptions.of("minecraft:test"));
        EntityModelData before = PoseKit.posed(EntityPose.NONE, mesh, idle, PERIOD, 0);
        EntityModelData after = PoseKit.posed(woven.pose(), mesh, idle, PERIOD, 0);
        for (Map.Entry<String, EntityModelData.Bone> named : before.getBones().entrySet()) {
            String parentBefore = named.getValue().getParent();
            String parentAfter = after.getBones().get(named.getKey()).getParent();
            if (parentBefore == null)
                assertEquals("$container", parentAfter,
                    "'" + named.getKey() + "' was a root and now hangs from the seat");
            else
                assertEquals(parentBefore, parentAfter,
                    "'" + named.getKey() + "' keeps the parent it always had");
        }
    }

    @Test
    @DisplayName("a container sway folds under the channel token its turn axis names, each axis alone")
    void containerSwayFoldsUnderTheTokenItsAxisNames() {
        // One axis per install, because the fold records a SET - three swayed axes at once name all
        // three tokens whichever way the mapping is wired, and a swapped pair reads identical.
        assertFoldsUnder(Turn.PITCH, PoseChannel.X_ROT);
        assertFoldsUnder(Turn.YAW, PoseChannel.Y_ROT);
        assertFoldsUnder(Turn.ROLL, PoseChannel.Z_ROT);
    }

    // ------------------------------------------------------------------------------------

    /**
     * Installs one container sway about the given axis over a seat a shipped clip displaces, and
     * asserts the fold records that axis's channel token and neither of the other two rotations.
     *
     * @param axis the turn axis the container sways about
     * @param expected the channel whose token the fold is required to name
     */
    private static void assertFoldsUnder(@NotNull Turn axis, @NotNull PoseChannel expected) {
        EntityModelData mesh = humanoid();
        // A shipped clip on the root reaches the seat without being a bone the mesh declares, which
        // is what makes the fold displace and therefore what makes the token list get recorded.
        PoseClip rock = new PoseClip(1f, true, Concurrent.newUnmodifiableList(
            new PoseClip.Channel("root", PoseChannel.Kind.ROTATION, Concurrent.newUnmodifiableList(
                new PoseClip.Keyframe(0f, 0f, 0f, 0f, PoseClip.Interpolation.LINEAR),
                new PoseClip.Keyframe(1f, 0f, 0f, 0.05f, PoseClip.Interpolation.LINEAR)))));
        EntityPose shipped = pose(List.of(), Map.of(),
            List.of(new EntityPose.Clip("FixtureAnimation#ROCK", MotionSource.NONE, Optional.empty(),
                Concurrent.newUnmodifiableList(), rock)));
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", Poses.humanoid("rock")
            .container(step -> step.sway(axis, -5, 5))
            .build());

        String folded = registrar.diagnostics().entries().stream()
            .filter(entry -> entry.severity() == Diagnostics.Severity.INFO)
            .map(Diagnostics.Entry::message)
            .filter(message -> message.startsWith("fold-seat:"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(axis + ": a displaced container fold records its channel tokens"));
        assertTrue(folded.contains(expected.token()),
            axis + " folds under " + expected.token() + ": " + folded);
        for (PoseChannel other : List.of(PoseChannel.X_ROT, PoseChannel.Y_ROT, PoseChannel.Z_ROT))
            if (other != expected)
                assertFalse(folded.contains(other.token()),
                    axis + " folds under " + expected.token() + " alone, never " + other.token() + ": " + folded);
    }

    /**
     * The posed roster after the install is the roster before it plus the one seat bone.
     */
    private static void assertRosterGrewByTheSeat(
        @NotNull EntityModelData before, @NotNull EntityModelData after,
        @NotNull String styleId, int tick) {

        Set<String> expected = new LinkedHashSet<>(before.getBones().keySet());
        expected.add("$container");
        assertEquals(expected, after.getBones().keySet(),
            context(styleId, tick) + "the roster grows by exactly the seat");
    }

    /**
     * One matched bone's pivot, rotation and scale bits, the two zero signs identified.
     */
    private static void assertMatchedBits(
        @NotNull String name, @NotNull EntityModelData.Bone before,
        EntityModelData.Bone after, @NotNull String styleId, int tick) {

        String context = context(styleId, tick) + "bone '" + name + "' ";
        assertNotNull(after, context + "matches by name");
        assertEquals(bits(before.getPivot().x()), bits(after.getPivot().x()), context + "pivot x");
        assertEquals(bits(before.getPivot().y()), bits(after.getPivot().y()), context + "pivot y");
        assertEquals(bits(before.getPivot().z()), bits(after.getPivot().z()), context + "pivot z");
        assertEquals(bits(before.getRotation().pitch()), bits(after.getRotation().pitch()), context + "pitch");
        assertEquals(bits(before.getRotation().yaw()), bits(after.getRotation().yaw()), context + "yaw");
        assertEquals(bits(before.getRotation().roll()), bits(after.getRotation().roll()), context + "roll");
        assertEquals(bits(before.getScale()), bits(after.getScale()), context + "scale");
    }

    /**
     * The seat bone evaluates displacement-free - an identity transform above the roots.
     */
    private static void assertSeatIsIdentity(
        EntityModelData.Bone seat, @NotNull String styleId, int tick) {

        String context = context(styleId, tick) + "the seat ";
        assertNotNull(seat, context + "exists");
        assertTrue(seat.getPivot().x() == 0f && seat.getPivot().y() == 0f && seat.getPivot().z() == 0f,
            context + "rests unplaced, so it composes as identity");
        assertTrue(seat.getRotation().pitch() == 0f && seat.getRotation().yaw() == 0f
            && seat.getRotation().roll() == 0f, context + "rests unturned");
        assertEquals(1f, seat.getScale(), context + "rests unscaled");
    }

    /**
     * The float's bits with the two zeros identified - the one sign a woven splice may flip.
     */
    private static int bits(float value) {
        return Float.floatToIntBits(value == 0f ? 0f : value);
    }

    private static @NotNull String context(@NotNull String styleId, int tick) {
        return "style '" + styleId + "' tick " + tick + ": ";
    }

}
