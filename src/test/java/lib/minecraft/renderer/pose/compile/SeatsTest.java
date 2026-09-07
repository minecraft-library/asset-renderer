package lib.minecraft.renderer.pose.compile;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.kit.BoneKit;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.pose.PoseChannel;
import lib.minecraft.renderer.pose.PoseExpr;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.bone;
import static lib.minecraft.renderer.pose.compile.CompilerFixtures.constant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The seats a pose's state silhouettes reveal over a mesh - which top-level bone rides which
 * other's frame - read from the placements vanilla makes by hand, and only from those.
 */
@DisplayName("seats are derived from the placements a state silhouette makes")
class SeatsTest {

    /** A quarter turn in radians, the body's pitch in every sitting fixture here. */
    private static final float QUARTER = (float) Math.toRadians(45);

    /**
     * A five-unit reach along the body's axis turned a quarter turn - it rises by this much and
     * comes forward by this much, the pitch carrying local z onto -y and +z.
     */
    private static final float REACH = (float) (5d * Math.sin(Math.toRadians(45)));

    // ------------------------------------------------------------------------------------
    // the shipped roster
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the wolf's tail and hind legs ride its body; the mane, forelegs and head ride nothing")
    void wolfTailAndHindLegsRideTheBody() {
        Seats.Derived derived = derived("minecraft:wolf");

        assertEquals(Set.of("tail", "right_hind_leg", "left_hind_leg"), derived.seats().keySet());
        for (Seats.Seat seat : derived.seats().values())
            assertEquals("body", seat.leader());
        assertEquals(Set.of("isSitting=true"), derived.witnesses(), "the sitting branch alone witnesses the seats");
        Seats.Seat tail = derived.seats().get("tail");
        assertEquals(-1f, tail.offset().x(), 1e-3);
        assertEquals(6f, tail.offset().y(), 1e-3, "six pixels down the body's own axis");
        assertEquals(2f, tail.offset().z(), 1e-3, "and two above it");
    }

    @Test
    @DisplayName("the feline tail is a chain - the first segment rides the body, the second the first")
    void felineTailIsAChain() {
        for (String feline : List.of("minecraft:cat", "minecraft:ocelot")) {
            Seats.Derived derived = derived(feline);
            assertEquals(Set.of("tail1", "tail2"), derived.seats().keySet(), feline);
            assertEquals("body", derived.seats().get("tail1").leader(), feline);
            assertEquals("tail1", derived.seats().get("tail2").leader(), feline);
        }
    }

    @Test
    @DisplayName("the dragon's neck is a chain of ten-unit segments, each seated on the one below it")
    void dragonNeckIsAChain() {
        Seats.Derived derived = derived("minecraft:ender_dragon");

        assertEquals("neck1", derived.seats().get("neck2").leader());
        assertEquals("neck2", derived.seats().get("neck3").leader());
        assertEquals("neck3", derived.seats().get("neck4").leader());
        assertEquals("neck4", derived.seats().get("head").leader());
        assertEquals(-10f, derived.seats().get("head").offset().z(), 1e-2, "ten units along the segment");
    }

    @Test
    @DisplayName("a humanoid's arms, legs and head ride nothing - the crouch moves them by their own amounts")
    void humanoidLimbsRideNothing() {
        Seats.Derived derived = derived("minecraft:zombie");

        assertTrue(derived.seats().isEmpty(), "adjacent at bind is not a seat");
        assertTrue(derived.witnesses().isEmpty(), "and no state of a biped witnesses one");
    }

    @Test
    @DisplayName("a parrot sat lower as a whole is a shift, not a seat - nothing turned")
    void wholeFigureShiftIsNoSeat() {
        assertTrue(derived("minecraft:parrot").seats().isEmpty());
    }

    // ------------------------------------------------------------------------------------
    // the rule on hand-built shapes
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a bone the lowered body's frame carries rides the body, with its resting offset")
    void carriedBoneRidesTheTurnedLeader() {
        EntityModelData mesh = bodyAndTail();
        EntityPose pose = withStates(Map.of("isSitting=true", Map.of(
            "body", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
            "tail", Map.of(PoseChannel.Y, constant(10f - REACH), PoseChannel.Z, constant(REACH)))));

        Seats.Derived derived = Seats.derive(pose, mesh);

        assertEquals(Set.of("tail"), derived.seats().keySet());
        assertEquals("body", derived.seats().get("tail").leader());
        assertEquals(5f, derived.seats().get("tail").offset().z(), 1e-3, "five units down the body's axis");
    }

    @Test
    @DisplayName("a bone the state moves elsewhere than the frame carries it rides nothing")
    void boneMovedElsewhereRidesNothing() {
        EntityModelData mesh = bodyAndTail();
        EntityPose pose = withStates(Map.of("isSitting=true", Map.of(
            "body", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
            "tail", Map.of(PoseChannel.Y, constant(14f), PoseChannel.Z, constant(7f)))));

        assertTrue(Seats.derive(pose, mesh).seats().isEmpty(),
            "the tail travelled, but not to where the body's frame carries its resting offset");
    }

    @Test
    @DisplayName("two bones that carry each other are one rigid piece and seat neither")
    void rigidPairSeatsNeither() {
        // A pair of legs folded under together, each moved and turned exactly as the other -
        // each one's frame carries the other, and with no third bone to lead them nothing does.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("right_leg", bone(-2.5f, 16f, 7f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("left_leg", bone(0.5f, 16f, 7f, 0f, 0f, 0f, 1f, null));
        Map<PoseChannel, PoseExpr> folded = Map.of(PoseChannel.X_ROT, constant(Math.toRadians(270)),
            PoseChannel.Y, constant(22.7f), PoseChannel.Z, constant(2f));
        EntityPose pose = withStates(Map.of("isSitting=true", Map.of("right_leg", folded, "left_leg", folded)));

        assertTrue(Seats.derive(pose, mesh).seats().isEmpty(), "neither leads the other");
    }

    @Test
    @DisplayName("a bone turning in place leads the bone its frame carries, however that one turns")
    void turningLeaderLeadsWhateverItCarries() {
        EntityModelData mesh = bodyAndTail();
        EntityPose pose = withStates(Map.of("isSitting=true", Map.of(
            "body", Map.of(PoseChannel.X_ROT, constant(QUARTER)),
            "tail", Map.of(PoseChannel.X_ROT, constant(QUARTER),
                PoseChannel.Y, constant(10f - REACH), PoseChannel.Z, constant(REACH)))));

        Seats.Derived derived = Seats.derive(pose, mesh);

        assertEquals(Set.of("tail"), derived.seats().keySet(),
            "the body never travelled, so nothing witnesses it riding the tail");
        assertEquals("body", derived.seats().get("tail").leader());
    }

    @Test
    @DisplayName("a shift with no turn witnesses nothing")
    void shiftWithoutTurnWitnessesNothing() {
        EntityModelData mesh = bodyAndTail();
        EntityPose pose = withStates(Map.of("isSitting=true", Map.of(
            "body", Map.of(PoseChannel.Y, constant(14f)),
            "tail", Map.of(PoseChannel.Y, constant(14f)))));

        assertTrue(Seats.derive(pose, mesh).seats().isEmpty());
    }

    @Test
    @DisplayName("a pose carrying no silhouette derives nothing, and the rest is still answered")
    void noSilhouetteNoSeats() {
        Seats.Derived derived = Seats.derive(EntityPose.NONE, bodyAndTail());

        assertTrue(derived.seats().isEmpty());
        assertEquals(Set.of("body", "tail"), derived.rest().keySet());
        assertEquals(5f, derived.rest().get("tail").pivot().z(), 0f);
    }

    // ------------------------------------------------------------------------------------
    // the convention
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a carry lands exactly where the engine's chain composition places a real child")
    void carryMatchesTheChainComposition() {
        // A parent turned on all three axes and a child hung under it at an offset on all three:
        // the child's pivot in the working frame is what the chain composes for a real parent,
        // and the seat's carry of that same offset through the parent's placement must land on
        // it - the seat borrows the engine's frame arithmetic rather than re-deriving it.
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("parent", bone(1f, 10f, -2f, 40f, 20f, 10f, 1f, null));
        mesh.getBones().put("child", bone(-1f, 6f, 2f, 0f, 0f, 0f, 1f, "parent"));
        Vector3f offset = new Vector3f(-1f, 6f, 2f);

        Vector3f composed = Vector3f.ZERO.transform(BoneKit.buildChainTransforms(mesh.getBones()).get("child"));
        Seats.Placement parent = Seats.restOf(EntityPose.NONE, mesh).get("parent");
        Vector3f carried = parent.carry(offset);

        assertEquals(composed.x(), carried.x(), 1e-4, "x");
        assertEquals(composed.y(), carried.y(), 1e-4, "y");
        assertEquals(composed.z(), carried.z(), 1e-4, "z");
        Vector3f back = parent.localOf(carried);
        assertEquals(offset.x(), back.x(), 1e-4, "the inverse reads the offset back (x)");
        assertEquals(offset.y(), back.y(), 1e-4, "the inverse reads the offset back (y)");
        assertEquals(offset.z(), back.z(), 1e-4, "the inverse reads the offset back (z)");
    }

    // ------------------------------------------------------------------------------------

    private static @NotNull Seats.Derived derived(@NotNull String entityId) {
        Entity row = EntityModelLoader.load().get(entityId);
        assumeTrue(row != null, "bundled entity tables answer " + entityId);
        return Seats.derive(row.pose(), row.model());
    }

    /**
     * Two top-level bones - a body, and a tail five units behind it along the body's own axis.
     */
    private static @NotNull EntityModelData bodyAndTail() {
        EntityModelData mesh = new EntityModelData();
        mesh.getBones().put("body", bone(0f, 10f, 0f, 0f, 0f, 0f, 1f, null));
        mesh.getBones().put("tail", bone(0f, 10f, 5f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    /**
     * A pose writing nothing, carrying the given silhouettes.
     */
    private static @NotNull EntityPose withStates(@NotNull Map<String, Map<String, Map<PoseChannel, PoseExpr>>> states) {
        Map<String, EntityPose.Silhouette> silhouettes = new LinkedHashMap<>();
        states.forEach((key, bones) -> silhouettes.put(key,
            new EntityPose.Silhouette(Concurrent.newUnmodifiableLinkedMap(bones))));
        return new EntityPose(Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableMap(Map.of()),
            Concurrent.newUnmodifiableList(), Optional.empty(), Concurrent.newUnmodifiableLinkedMap(silhouettes));
    }

}
