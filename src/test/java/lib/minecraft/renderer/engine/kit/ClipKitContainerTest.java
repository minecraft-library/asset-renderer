package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A clip channel naming the part the geometry flow flattened away still reaches the container.
 *
 * <p>Vanilla builds its part lookup BEFORE any flattening this side does - {@code createPartLookup}
 * seeds {@code root -> this} and adds every named descendant - so a model whose root holds one named
 * part above the rest resolves a clip channel at that part's own name. The flow dissolves such a
 * part into the bones below it and leaves each of them naming it as a parent, so what a lookup by
 * bone name finds afterwards is nothing at all.
 *
 * <p>It was silent rather than loud, and expensive: the breeze's slide shoves its body six model
 * pixels and the reference had that shove where the render did not, which read as a canvas
 * disagreement rather than as a dropped channel.
 */
@DisplayName("a clip channel naming a flattened container")
class ClipKitContainerTest {

    /** The one model in the corpus whose mesh flattened a NAMED part, and the name it flattened. */
    private static final @NotNull String FLATTENED_MODEL = "minecraft:breeze";

    private static final @NotNull String FLATTENED_PART = "body";

    /**
     * The tick the slide's last keyframe sits on - its length is {@code 0.2s} and a tick is 50ms.
     *
     * <p>Held as the END rather than as a point along the ramp so the assertion below is the
     * authored number itself and not an interpolation of it.
     */
    private static final int SLIDE_END_TICK = 4;

    /** What that keyframe shoves the body by, in model pixels, straight off the clip table. */
    private static final float SLIDE_BODY_SHOVE = -6f;

    /** The bone a frog's croak both draws and moves, which no other clip in the corpus does. */
    private static final @NotNull String CROAK_BONE = "croaking_body";

    /** A tick inside the croak's three-second run, so the sac is somewhere off its resting size. */
    private static final int CROAK_MID_TICK = 20;

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("no mesh names more than one parent it does not declare")
    void oneDanglingNamePerMesh() {
        // What makes the container an ANSWER rather than a guess. A dangling parent is what a
        // flattened container leaves behind, and a second one on the same mesh would be a different
        // thing wearing that shape - a surgery that dropped an intermediate bone and left its
        // children pointing at it - which this rule would then read as the container.
        for (Entity subject : entities.values())
            for (EntityModelData mesh : meshes(subject)) {
                Set<String> dangling = danglingParents(mesh);
                assertTrue(dangling.size() <= 1,
                    subject.id() + " names " + dangling + " as parents and declares none of them, so "
                        + "which one the container is cannot be read off the mesh");
            }
    }

    @Test
    @DisplayName("the corpus still has the one mesh this rule exists for")
    void theFlattenedMeshIsStillThere() {
        // Pinned so a version bump that stops flattening it retires the rule loudly rather than
        // leaving a branch nothing reaches. It is a statement about the emitted mesh, so it fails
        // where the geometry flow changed and not where this kit did.
        EntityModelData body = flattenedMesh();
        assertFalse(body.getBones().containsKey(FLATTENED_PART),
            "the mesh is expected to declare no bone named '" + FLATTENED_PART + "'");
        assertEquals(Set.of(FLATTENED_PART), danglingParents(body),
            "and its top-level bones are expected to name that part as their parent");
    }

    @Test
    @DisplayName("a channel naming it displaces the container rather than nothing")
    void theFlattenedNameReachesTheContainer() {
        // At the tick the slide's own last keyframe sits on, so what comes back is the authored
        // value itself rather than a point on the ramp to it. The play site carries elapsed age as
        // its argument, so the frame has to answer that as well as the gate; answering only the gate
        // asks for the clip at its first instant, where every channel is still at rest.
        EntityModelData body = flattenedMesh();
        ClipKit.Displacement displaced = ClipKit.deltas(subject(FLATTENED_MODEL).pose(), body,
            field -> switch (field) {
                case "slide" -> 1d;
                case "ageInTicks" -> SLIDE_END_TICK;
                default -> 0d;
            });

        assertFalse(displaced.container().isEmpty(),
            "the slide writes '" + FLATTENED_PART + "', which is the part the flow flattened - so it "
                + "belongs to the container and not to nothing");
        assertEquals(SLIDE_BODY_SHOVE, displaced.container().get(PoseChannel.Z), 0f,
            "and it shoves the container the whole authored amount along z");
    }

    @Test
    @DisplayName("a clip whose own state also draws its bone reaches a bone the mesh kept")
    void aClipThatDrawsItsBoneReachesIt() {
        // The frog's croak is the corpus's one: FrogModel writes croakingBody.visible from the same
        // state the clip is gated on, and the clip writes that bone and nothing else. So the two
        // halves are inseparable - a mesh that dropped the sac would leave the clip displacing
        // nothing, which is what it did until the generator read `isStarted()` as a gate.
        //
        // The sac is kept and rests hidden, carrying the toggle a caller flips. It also rests INSIDE
        // the body at `grow: -0.1`, so what makes it visible is this displacement rather than the
        // toggle alone - which is the whole reason the clip has to reach it.
        Entity frog = subject("minecraft:frog");
        EntityModelData mesh = frog.model();
        EntityModelData.Bone sac = mesh.getBones().get(CROAK_BONE);
        assertNotNull(sac, "the mesh is expected to keep the sac the croak draws");
        assertFalse(sac.isVisible(), "and to rest it hidden, which is a frog no croak has started");

        assertTrue(ClipKit.deltas(frog.pose(), mesh, field -> 0d).of(CROAK_BONE).isEmpty(),
            "a frog nothing has croaked displaces the sac by nothing");

        ClipKit.Displacement croaking = ClipKit.deltas(frog.pose(), mesh,
            field -> switch (field) {
                case "croakAnimationState" -> 1d;
                case "ageInTicks" -> CROAK_MID_TICK;
                default -> 0d;
            });
        assertFalse(croaking.of(CROAK_BONE).isEmpty(),
            "and one mid-croak displaces it, which is the clip reaching the bone its own gate draws");
    }

    @Test
    @DisplayName("a name the mesh neither declares nor hangs a bone from still reaches nothing")
    void anUnknownNameIsStillPassedOver() {
        // The other half of the rule. A clip belongs to a model class where a mesh belongs to a
        // subject, so the two part company wherever a bone rests undrawn - an armadillo's shell body
        // and a frog's croaking sac are both dropped from the mesh they would have been drawn in -
        // and a channel naming one of those has to keep answering nothing.
        EntityModelData body = flattenedMesh();
        assertFalse(body.getBones().containsKey("no_such_bone"), "the fixture name is not a bone");
        assertTrue(danglingParents(body).stream().noneMatch("no_such_bone"::equals),
            "and no bone hangs from it either");
    }

    /** Every parent name a mesh's bones point at and the mesh itself declares no bone for. */
    private static @NotNull Set<String> danglingParents(@NotNull EntityModelData mesh) {
        Map<String, EntityModelData.Bone> bones = mesh.getBones();
        Set<String> dangling = new LinkedHashSet<>();
        for (EntityModelData.Bone bone : bones.values()) {
            String parent = bone.getParent();
            if (parent != null && !bones.containsKey(parent)) dangling.add(parent);
        }
        return dangling;
    }

    /** The body mesh of the one subject whose model flattened a named part. */
    private static @NotNull EntityModelData flattenedMesh() {
        return subject(FLATTENED_MODEL).model();
    }

    /** Every mesh a subject draws - its body, and each overlay pass's. */
    private static @NotNull Set<EntityModelData> meshes(@NotNull Entity subject) {
        Set<EntityModelData> drawn = new LinkedHashSet<>();
        drawn.add(subject.model());
        for (Entity.OverlayLayer overlay : subject.overlays()) drawn.add(overlay.model());
        return drawn;
    }

    private static @NotNull Entity subject(@NotNull String id) {
        Entity entity = entities.get(id);
        assertNotNull(entity, id + " is expected to load");
        return entity;
    }

}
