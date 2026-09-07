package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A seat lowers as a held displacement on the follower's own field, which every style but the
 * one that stanced the leader leaves at zero - so an install that weaves seats leaves every
 * shipped style of the row, bind, idle and stride, at the bits it posed before the install,
 * across the whole strip. The render path is blind to the silhouettes the seats derive from;
 * this asks the same question of an install.
 */
@DisplayName("an install that weaves seats leaves the shipped styles at their bits")
class SeatInstallParityTest {

    @Test
    @DisplayName("the beg and the rear leave every shipped style of the wolf and the horse at its bits")
    void cookbookInstallsLeaveTheShippedStylesAtTheirBits() {
        ConcurrentMap<String, Entity> pristine = EntityModelLoader.load();
        assumeTrue(pristine.containsKey("minecraft:wolf") && pristine.containsKey("minecraft:horse"),
            "bundled entity tables answer");
        StyleRegistrar registrar = StyleRegistrar.ofShipped()
            .add("minecraft:wolf", Poses.quadruped("beg")
                .body(body -> body.pitch(-40))
                .hindLegs(leg -> leg.pitch(-70))
                .frontLegs(leg -> leg.pitch(-35))
                .head(head -> head.pitch(-15)
                    .timeline(timeline -> timeline.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
                .tail(tail -> tail.sway(Turn.YAW, -25, 25))
                .build())
            .add("minecraft:horse", Poses.quadruped("rear")
                .container(seat -> seat.pitch(-30))
                .frontLegs(leg -> leg.pitch(-65))
                .head(head -> head.pitch(25))
                .tail(tail -> tail.pitch(-30))
                .build());

        for (String id : List.of("minecraft:wolf", "minecraft:horse"))
            assertShippedStylesHold(pristine.get(id), registrar.definitions().get(id));
    }

    @Test
    @DisplayName("every shipped row deriving a seat rests at its bits under a probe turning and shifting each leader")
    void everyCarryingRowRestsAtItsBitsUnderAProbe() {
        ConcurrentMap<String, Entity> pristine = EntityModelLoader.load();
        assumeTrue(!pristine.isEmpty(), "bundled entity tables answer");
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        List<String> probed = new ArrayList<>();
        pristine.forEach((id, row) -> {
            if (!row.pose().isReadable()) return;
            Seats.Derived derived = Seats.derive(row.pose(), row.model());
            if (derived.seats().isEmpty()) return;
            Set<String> leaders = new LinkedHashSet<>();
            for (Seats.Seat seat : derived.seats().values())
                leaders.add(seat.leader());
            // A parentless bone of a flattened mesh cannot be displaced, so such a row is probed
            // by a turn alone.
            boolean placeable = row.model().getFlattenedScale() == 1f;
            CustomPose.Builder probe = Poses.custom("probe");
            for (String leader : leaders)
                probe.bone(leader, stance -> placeable ? stance.pitchBy(30).offset(1, 2, 3) : stance.pitchBy(30));
            registrar.addTolerant(id, probe.build());
            probed.add(id);
        });

        assertTrue(probed.contains("minecraft:wolf"), "the wolf's seats are among the probed rows");
        for (String id : probed)
            assertShippedStylesHold(pristine.get(id), registrar.definitions().get(id));
    }

    /**
     * Holds every shipped style the pristine row's catalog carries - bind, idle and stride - to
     * bone-for-bone identical bits across the strip's ticks on the woven row.
     */
    private static void assertShippedStylesHold(@NotNull Entity pristine, @NotNull Entity woven) {
        int period = pristine.styles().periodTicks();
        List<PoseStyle> shipped = new ArrayList<>();
        shipped.add(pristine.styles().bind());
        pristine.styles().byId(PoseStyle.IDLE).ifPresent(shipped::add);
        pristine.styles().byId(PoseStyle.STRIDE).ifPresent(shipped::add);
        for (PoseStyle style : shipped) {
            PoseStyle after = woven.styles().byId(style.id()).orElseGet(() -> woven.styles().bind());
            assertParity(pristine.pose(), woven.pose(), pristine.model(), style, after, period);
        }
    }

    /**
     * Poses both rows under one shipped style at every strip tick and holds every bone the
     * pristine row poses to the same pivot, rotation and scale bits, the two zero signs
     * identified. A woven row that appended a container step to a pose carrying none poses one
     * seat bone more, which every former root hangs from and which rests as identity under a
     * shipped style; a container the pose shipped with is held to its bits like any other bone.
     */
    private static void assertParity(@NotNull EntityPose before, @NotNull EntityPose after,
                                     @NotNull EntityModelData mesh, @NotNull PoseStyle style,
                                     @NotNull PoseStyle wovenStyle, int period) {
        for (int frame = 0; frame < StyleCatalog.STRIP_FRAMES; frame++) {
            int tick = frame * period / StyleCatalog.STRIP_FRAMES;
            String context = style.id() + " at tick " + tick + ": ";
            Map<String, EntityModelData.Bone> pristine = PoseKit.posed(before, mesh, style, period, tick).getBones();
            Map<String, EntityModelData.Bone> woven = PoseKit.posed(after, mesh, wovenStyle, period, tick).getBones();

            Set<String> expected = new LinkedHashSet<>(pristine.keySet());
            if (woven.containsKey("$container") && !pristine.containsKey("$container")) {
                expected.add("$container");
                EntityModelData.Bone seat = woven.get("$container");
                assertTrue(seat.getPivot().equals(Vector3f.ZERO) && seat.getRotation().pitch() == 0f
                        && seat.getRotation().yaw() == 0f && seat.getRotation().roll() == 0f && seat.getScale() == 1f,
                    context + "the seat rests as identity");
            }
            assertEquals(expected, woven.keySet(), context + "the roster grows by at most the seat");

            pristine.forEach((name, bone) -> {
                EntityModelData.Bone posed = woven.get(name);
                assertEquals(bits(bone.getPivot().x()), bits(posed.getPivot().x()), context + name + " pivot x");
                assertEquals(bits(bone.getPivot().y()), bits(posed.getPivot().y()), context + name + " pivot y");
                assertEquals(bits(bone.getPivot().z()), bits(posed.getPivot().z()), context + name + " pivot z");
                assertEquals(bits(bone.getRotation().pitch()), bits(posed.getRotation().pitch()), context + name + " pitch");
                assertEquals(bits(bone.getRotation().yaw()), bits(posed.getRotation().yaw()), context + name + " yaw");
                assertEquals(bits(bone.getRotation().roll()), bits(posed.getRotation().roll()), context + name + " roll");
                assertEquals(bits(bone.getScale()), bits(posed.getScale()), context + name + " scale");
            });
        }
    }

    /**
     * The float's bits with the two zeros identified - the one sign a resting splice may flip.
     */
    private static int bits(float value) {
        return Float.floatToIntBits(value == 0f ? 0f : value);
    }

}
