package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Mesh-surgery contract tests for {@link EntityIndexBuilder}, exercised on a hand-built fixture rather
 * than a shipped geometry so the subtree walk is pinned independently of any entity's bone layout.
 */
@DisplayName("EntityIndexBuilder mesh surgery")
class EntityIndexBuilderMeshTest {

    private static final String ENTITY = "minecraft:test_villager";

    /** Model units per block, the factor a {@code y_shift} crosses on its way onto the mesh. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    /**
     * The villager bone shape: {@code head} carries {@code hat} (which carries {@code hat_rim}) and
     * {@code nose}, alongside an untouched {@code body} / {@code right_leg} pair. Every bone owns one
     * cube and a distinct pivot so the pass-through of pose state is observable.
     */
    private static EntityModelData fixture() {
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
        bones.put("body", bone(new Vector3f(0f, 1f, 0f), null));
        bones.put("head", bone(new Vector3f(0f, 2f, 0f), null));
        bones.put("hat", bone(new Vector3f(0f, 3f, 0f), "head"));
        bones.put("hat_rim", bone(new Vector3f(0f, 4f, 0f), "hat"));
        bones.put("nose", bone(new Vector3f(0f, 5f, 0f), "head"));
        bones.put("right_leg", bone(new Vector3f(0f, 6f, 0f), "body"));
        return new EntityModelData(TextureSize.DEFAULT, 0f, Concurrent.adoptLinkedMap(bones), false);
    }

    private static EntityModelData.Bone bone(Vector3f pivot, String parent) {
        return bone(pivot, parent, 0.25f);
    }

    /** One bone owning a single fully-populated cube, so a surgery's pass-through is observable. */
    private static EntityModelData.Bone bone(Vector3f pivot, String parent, float grow) {
        EntityModelData.Cube cube = new EntityModelData.Cube(
            new Vector3f(1f, 2f, 3f), new Vector3f(4f, 5f, 6f), new Vector2f(7f, 8f),
            new Vector3f(grow, grow, grow), true, new Vector3f(9f, 10f, 11f),
            EulerRotation.NONE, Concurrent.newMap());
        return new EntityModelData.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(cube), parent);
    }

    // ------------------------------------------------------------------------------------
    // retainExactParts
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a bone keeps its cubes only when it is named and no ancestor of it is")
    void retainExactPartsKeepsCubesOnlyOnANamedBoneWithNoNamedAncestor() {
        EntityModelData retained = EntityIndexBuilder.retainExactParts(fixture(), List.of("head", "hat"));

        assertThat("head is named and has no named ancestor, so it draws",
            retained.getBones().get("head").getCubes().isEmpty(), is(false));
        assertThat("hat is named but hangs off head, which is also named, so it is emptied",
            retained.getBones().get("hat").getCubes().isEmpty(), is(true));
        for (String name : new String[]{"hat_rim", "nose", "body", "right_leg"})
            assertThat("the unnamed " + name + " is emptied", retained.getBones().get(name).getCubes().isEmpty(), is(true));
    }

    @Test
    @DisplayName("retainExactParts empties cubes and never drops a bone, so the chain survives")
    void retainExactPartsKeepsEveryBoneAsAPoseOnlyNode() {
        EntityModelData source = fixture();
        EntityModelData retained = EntityIndexBuilder.retainExactParts(source, List.of("head"));

        assertThat(Set.copyOf(retained.getBones().keySet()), equalTo(Set.copyOf(source.getBones().keySet())));
        for (String name : source.getBones().keySet()) {
            EntityModelData.Bone before = source.getBones().get(name);
            EntityModelData.Bone after = retained.getBones().get(name);
            assertThat(name + " keeps its pivot", after.getPivot(), equalTo(before.getPivot()));
            assertThat(name + " keeps its parent", after.getParent(), equalTo(before.getParent()));
            assertThat(name + " keeps its scale", after.getScale(), is(before.getScale()));
        }
    }

    // ------------------------------------------------------------------------------------
    // shiftModel
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a shift moves root pivots by the blocks crossed into model units, sign flipped")
    void shiftModelMovesRootPivotsAndLeavesChildrenAlone() {
        EntityModelData source = fixture();
        EntityModelData shifted = EntityIndexBuilder.shiftModel(source, 0.5f);
        float delta = -0.5f * MODEL_UNITS_PER_BLOCK;

        for (String name : new String[]{"body", "head"})
            assertThat("the root bone " + name + " moves", shifted.getBones().get(name).getPivot().y(),
                is(source.getBones().get(name).getPivot().y() + delta));
        for (String name : new String[]{"hat", "hat_rim", "nose", "right_leg"})
            assertThat("the child bone " + name + " holds, its pivot being relative to its parent",
                shifted.getBones().get(name).getPivot(), equalTo(source.getBones().get(name).getPivot()));
    }

    @Test
    @DisplayName("a shift of nothing hands the mesh straight back")
    void shiftModelReturnsTheSourceAtZero() {
        EntityModelData source = fixture();

        assertThat(EntityIndexBuilder.shiftModel(source, 0f), is(sameInstance(source)));
    }

    // ------------------------------------------------------------------------------------
    // inflateModel
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an inflate ADDS to the grow a cube already carries, on every axis")
    void inflateModelAddsTheDeltaToEveryCubeGrow() {
        EntityModelData inflated = EntityIndexBuilder.inflateModel(fixture(), 0.5f);

        for (String name : inflated.getBones().keySet()) {
            Vector3f grow = inflated.getBones().get(name).getCubes().getFirst().getGrow();
            assertThat(name + " grows on x", grow.x(), is(0.75f));
            assertThat(name + " grows on y", grow.y(), is(0.75f));
            assertThat(name + " grows on z", grow.z(), is(0.75f));
        }
    }

    @Test
    @DisplayName("an inflate touches the grow and nothing else the cube carries")
    void inflateModelLeavesEveryOtherCubeMemberAlone() {
        EntityModelData source = fixture();
        EntityModelData inflated = EntityIndexBuilder.inflateModel(source, 0.5f);

        EntityModelData.Cube before = source.getBones().get("head").getCubes().getFirst();
        EntityModelData.Cube after = inflated.getBones().get("head").getCubes().getFirst();
        assertThat("the origin holds", after.getOrigin(), equalTo(before.getOrigin()));
        assertThat("the size holds", after.getSize(), equalTo(before.getSize()));
        assertThat("the uv holds", after.getUv(), equalTo(before.getUv()));
        assertThat("the mirror holds", after.isMirror(), is(before.isMirror()));
        assertThat("the pivot holds", after.getPivot(), equalTo(before.getPivot()));
        assertThat("the rotation holds", after.getRotation(), equalTo(before.getRotation()));
        assertThat("the source is untouched", before.getGrow().x(), is(0.25f));
    }

    // ------------------------------------------------------------------------------------
    // clearSubtreeCubes
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("clearSubtreeCubes empties the root bone and every descendant, sparing the rest")
    void clearSubtreeCubesEmptiesTheSubtree() {
        EntityModelData source = fixture();
        EntityModelData cleared = EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY).orElseThrow();

        for (String name : new String[]{"head", "hat", "hat_rim", "nose"})
            assertThat("the " + name + " subtree bone is emptied", cleared.getBones().get(name).getCubes().isEmpty(), is(true));
        for (String name : new String[]{"body", "right_leg"})
            assertThat("the " + name + " bone outside the subtree keeps its cubes", cleared.getBones().get(name).getCubes().isEmpty(), is(false));
    }

    @Test
    @DisplayName("clearSubtreeCubes keeps every bone, its pose and its parent link")
    void clearSubtreeCubesKeepsTheHierarchy() {
        EntityModelData source = fixture();
        EntityModelData cleared = EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY).orElseThrow();

        assertThat("the clear empties cubes, it never drops a bone",
            Set.copyOf(cleared.getBones().keySet()), equalTo(Set.copyOf(source.getBones().keySet())));
        for (String name : source.getBones().keySet()) {
            EntityModelData.Bone before = source.getBones().get(name);
            EntityModelData.Bone after = cleared.getBones().get(name);
            assertThat(name + " keeps its pivot", after.getPivot(), equalTo(before.getPivot()));
            assertThat(name + " keeps its rotation", after.getRotation(), equalTo(before.getRotation()));
            assertThat(name + " keeps its bind-pose rotation", after.getBindPoseRotation(), equalTo(before.getBindPoseRotation()));
            assertThat(name + " keeps its scale", after.getScale(), is(before.getScale()));
            assertThat(name + " keeps its parent", after.getParent(), equalTo(before.getParent()));
        }
    }

    @Test
    @DisplayName("clearSubtreeCubes copies rather than mutates - the source keeps its head cubes")
    void clearSubtreeCubesLeavesTheSourceIntact() {
        EntityModelData source = fixture();
        EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY).orElseThrow();

        assertThat("the primary mesh still draws its head", source.getBones().get("head").getCubes().isEmpty(), is(false));
        assertThat("the primary mesh still draws its hat", source.getBones().get("hat").getCubes().isEmpty(), is(false));
    }

    @Test
    @DisplayName("a root bone absent from the mesh yields empty rather than a partial mesh")
    void clearSubtreeCubesRejectsAnUnknownRoot() {
        assertThat(EntityIndexBuilder.clearSubtreeCubes(fixture(), "snout", ENTITY), is(Optional.empty()));
    }

}
