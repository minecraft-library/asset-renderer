package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.TextureSize;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Mesh-surgery contract tests for {@link EntityIndexBuilder}, exercised on a hand-built fixture rather
 * than a shipped geometry so the subtree walk is pinned independently of any entity's bone layout.
 */
@DisplayName("EntityIndexBuilder mesh surgery")
class EntityIndexBuilderMeshTest {

    private static final String ENTITY = "minecraft:test_villager";

    private static Diagnostics diagnostics() {
        return Diagnostics.root("test", Diagnostics.Output.NONE, null);
    }

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
        return new EntityModelData.Bone(pivot, EulerRotation.NONE, EulerRotation.NONE, 1f,
            Concurrent.newList(new EntityModelData.Cube()), parent);
    }

    @Test
    @DisplayName("clearSubtreeCubes empties the root bone and every descendant, sparing the rest")
    void clearSubtreeCubesEmptiesTheSubtree() {
        EntityModelData source = fixture();
        EntityModelData cleared = EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY, diagnostics()).orElseThrow();

        for (String name : new String[]{"head", "hat", "hat_rim", "nose"})
            assertThat("the " + name + " subtree bone is emptied", cleared.getBones().get(name).getCubes().isEmpty(), is(true));
        for (String name : new String[]{"body", "right_leg"})
            assertThat("the " + name + " bone outside the subtree keeps its cubes", cleared.getBones().get(name).getCubes().isEmpty(), is(false));
    }

    @Test
    @DisplayName("clearSubtreeCubes keeps every bone, its pose and its parent link")
    void clearSubtreeCubesKeepsTheHierarchy() {
        EntityModelData source = fixture();
        EntityModelData cleared = EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY, diagnostics()).orElseThrow();

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
        EntityIndexBuilder.clearSubtreeCubes(source, "head", ENTITY, diagnostics()).orElseThrow();

        assertThat("the primary mesh still draws its head", source.getBones().get("head").getCubes().isEmpty(), is(false));
        assertThat("the primary mesh still draws its hat", source.getBones().get("hat").getCubes().isEmpty(), is(false));
    }

    @Test
    @DisplayName("a root bone absent from the mesh yields empty rather than a partial mesh")
    void clearSubtreeCubesRejectsAnUnknownRoot() {
        assertThat(EntityIndexBuilder.clearSubtreeCubes(fixture(), "snout", ENTITY, diagnostics()), is(Optional.empty()));
    }

}
