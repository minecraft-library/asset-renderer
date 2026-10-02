package lib.minecraft.renderer.fixture;

import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.author.BuiltStyle;
import lib.minecraft.renderer.author.Poses;
import lib.minecraft.renderer.author.Side;
import lib.minecraft.renderer.author.Turn;
import lib.minecraft.renderer.author.install.StyleRegistrar;
import lib.minecraft.renderer.bake.pose.PosePlayer;
import lib.minecraft.renderer.engine.pose.PoseChannel;
import lib.minecraft.renderer.engine.pose.PoseExpr;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

import static lib.minecraft.renderer.fixture.CompilerFixtures.constant;
import static lib.minecraft.renderer.fixture.CompilerFixtures.dadd;
import static lib.minecraft.renderer.fixture.CompilerFixtures.input;
import static lib.minecraft.renderer.fixture.CompilerFixtures.pose;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.definitions;
import static lib.minecraft.renderer.fixture.RegistrarFixtures.entity;

/**
 * Hand-authored styles woven onto a row by the registrar, for tests that exercise what a woven row
 * becomes rather than how it is authored.
 */
public final class WovenRows {

    private WovenRows() {}

    /**
     * Weaves the animated wave onto a row over the given mesh - two stance writes and one keyframed
     * swing, so a play site is woven.
     *
     * @param mesh the mesh the row is built over
     * @return the woven pose
     */
    public static @NotNull EntityPose wave(@NotNull EntityMesh mesh) {
        return woven(mesh, waveStyle());
    }

    /**
     * Weaves the static sit onto a row over the given mesh - the wave's stance writes with no
     * timeline, so no site and no table.
     *
     * @param mesh the mesh the row is built over
     * @return the woven pose
     */
    public static @NotNull EntityPose sit(@NotNull EntityMesh mesh) {
        return woven(mesh, sitStyle());
    }

    /**
     * Weaves the hatch onto a row over the given mesh - a raw splice over one channel beside two
     * ordinary stances, gated on the style's own selection.
     *
     * @param mesh the mesh the row is built over
     * @return the woven pose
     */
    public static @NotNull EntityPose hatch(@NotNull EntityMesh mesh) {
        return woven(mesh, rawStyle());
    }

    /**
     * Poses one mesh with the pose belonging to it under a resolved style at one tick.
     *
     * @param pose the pose belonging to the mesh
     * @param mesh the mesh to pose
     * @param style the resolved catalog row to pose with
     * @param periodTicks the ticks one whole excursion spans
     * @param tick the frame's sample tick
     * @return the posed mesh
     */
    public static @NotNull EntityMesh posed(@NotNull EntityPose pose, @NotNull EntityMesh mesh,
                                            @NotNull PoseStyle style, int periodTicks, int tick) {
        return PosePlayer.posed(pose, mesh, style, periodTicks, tick);
    }

    /**
     * The given style installed over the shipped shapes on the given mesh, woven by the registrar.
     */
    private static @NotNull EntityPose woven(@NotNull EntityMesh mesh, @NotNull BuiltStyle style) {
        // The shipped table writes head and hat with one shared instance, the way a shell that
        // copies its head is baked.
        PoseExpr shippedHead = dadd(constant(0.25d), input("ageInTicks"));
        EntityPose shipped = pose(List.of(), Map.of(
            "head", Map.of(PoseChannel.Y_ROT, shippedHead),
            "hat", Map.of(PoseChannel.Y_ROT, shippedHead)), List.of());
        StyleRegistrar registrar = StyleRegistrar.of(definitions(
            entity("minecraft:test", mesh, shipped, StyleCatalog.BIND_ONLY)));
        registrar.add("minecraft:test", style);
        return registrar.definitions().get("minecraft:test").pose();
    }

    /**
     * The animated wave - two stance writes and one keyframed swing, so a play site is woven.
     */
    private static @NotNull BuiltStyle waveStyle() {
        return Poses.humanoid("wave")
            .head(head -> head.yaw(15))
            .arm(Side.RIGHT, arm -> arm.pitch(-40)
                .timeline(track -> track.swing(Turn.ROLL, -20, 20).over(0.6)))
            .build();
    }

    /**
     * The static sit - the same stance writes with no timeline, so no site and no table.
     */
    private static @NotNull BuiltStyle sitStyle() {
        return Poses.humanoid("sit")
            .head(head -> head.yaw(15))
            .arm(Side.RIGHT, arm -> arm.pitch(-40))
            .build();
    }

    /**
     * A raw splice over one channel beside two ordinary stances - the gated arm the hatch weaves,
     * whose graph reads a live field of its own so neither arm is a constant.
     */
    private static @NotNull BuiltStyle rawStyle() {
        return Poses.custom("hatch")
            .bone("head", head -> head.yaw(15))
            .bone("right_arm", arm -> arm.pitch(-40))
            .expr("body", PoseChannel.X_ROT, dadd(constant(0.5d), input("ageInTicks")))
            .build();
    }

}
