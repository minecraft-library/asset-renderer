package lib.minecraft.renderer.asset.model;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.tensor.EulerRotation;
import lib.minecraft.renderer.tensor.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Copy contract for {@link EntityModelData.Bone}: every derived copy carries every member it did not
 * set.
 *
 * <p>The failure this pins is silent - a copy that drops a member renders a bone at rest, or draws
 * one the subject rests without, with nothing to say so. A positional rebuild had already lost
 * {@code poseScale} once, which is why the copies are named rather than spelled out at each site.
 */
@DisplayName("EntityModelData.Bone copies")
class BoneCopyTest {

    private static final Vector3f PIVOT = new Vector3f(1f, 2f, 3f);
    private static final Vector3f POSE_SCALE = new Vector3f(1.5f, 1.5f, 1.5f);
    private static final EulerRotation ROTATION = new EulerRotation(10f, 20f, 30f);
    private static final EulerRotation BIND = new EulerRotation(90f, 0f, 0f);

    /** A bone standing away from the default on every member, so a dropped one is visible. */
    private static EntityModelData.Bone loaded() {
        return new EntityModelData.Bone(PIVOT, ROTATION, BIND, 0.75f,
            Concurrent.newList(new EntityModelData.Cube()), "body", POSE_SCALE, false, "chest");
    }

    private static void assertCarriesEverythingBut(
        String setMember, EntityModelData.Bone copy, EntityModelData.Bone source) {

        if (!"pivot".equals(setMember) && !"pose".equals(setMember))
            assertThat(setMember + " copy keeps the pivot", copy.getPivot(), equalTo(source.getPivot()));
        if (!"cubes".equals(setMember))
            assertThat(setMember + " copy keeps the cubes", copy.getCubes(), equalTo(source.getCubes()));
        if (!"parent".equals(setMember))
            assertThat(setMember + " copy keeps the parent", copy.getParent(), equalTo(source.getParent()));
        if (!"poseScale".equals(setMember))
            assertThat(setMember + " copy keeps the clip displacement", copy.getPoseScale(), equalTo(source.getPoseScale()));
        if (!"visible".equals(setMember))
            assertThat(setMember + " copy keeps the rest visibility", copy.isVisible(), is(source.isVisible()));
        if (!"pose".equals(setMember)) {
            assertThat(setMember + " copy keeps the rotation", copy.getRotation(), equalTo(source.getRotation()));
            assertThat(setMember + " copy keeps the scale", copy.getScale(), is(source.getScale()));
        }
        assertThat(setMember + " copy keeps the bind-pose rotation", copy.getBindPoseRotation(), equalTo(source.getBindPoseRotation()));
        assertThat(setMember + " copy keeps the toggle", copy.getToggle(), equalTo(source.getToggle()));
    }

    @Test
    @DisplayName("every named copy carries every member it did not set")
    void everyCopyCarriesTheRest() {
        EntityModelData.Bone source = loaded();

        assertCarriesEverythingBut("pivot", source.withPivot(new Vector3f(9f, 9f, 9f)), source);
        assertCarriesEverythingBut("cubes", source.withCubes(Concurrent.newList()), source);
        assertCarriesEverythingBut("parent", source.withParent("head"), source);
        assertCarriesEverythingBut("poseScale", source.withPoseScale(new Vector3f(2f, 2f, 2f)), source);
        assertCarriesEverythingBut("visible", source.withVisible(true), source);
        assertCarriesEverythingBut("pose",
            source.withPose(new Vector3f(4f, 5f, 6f), EulerRotation.NONE, 2f), source);
    }

    @Test
    @DisplayName("a copy sets the member it names")
    void everyCopySetsWhatItNames() {
        EntityModelData.Bone source = loaded();

        assertThat(source.withPivot(new Vector3f(9f, 9f, 9f)).getPivot(), equalTo(new Vector3f(9f, 9f, 9f)));
        assertThat(source.withCubes(Concurrent.newList()).getCubes().isEmpty(), is(true));
        assertThat(source.withParent("head").getParent(), equalTo("head"));
        assertThat(source.withParent(null).getParent(), is(nullValue()));
        assertThat(source.withPoseScale(new Vector3f(2f, 2f, 2f)).getPoseScale(), equalTo(new Vector3f(2f, 2f, 2f)));
        assertThat(source.withVisible(true).isVisible(), is(true));

        EntityModelData.Bone posed = source.withPose(new Vector3f(4f, 5f, 6f), EulerRotation.NONE, 2f);
        assertThat(posed.getPivot(), equalTo(new Vector3f(4f, 5f, 6f)));
        assertThat(posed.getRotation(), equalTo(EulerRotation.NONE));
        assertThat(posed.getScale(), is(2f));
    }

    @Test
    @DisplayName("a bone already drawing that way is handed back rather than copied")
    void withVisibleIsIdentityWhenNothingMoves() {
        EntityModelData.Bone hidden = loaded();

        assertThat(hidden.withVisible(false), is(sameInstance(hidden)));
        assertThat(hidden.withVisible(true).withVisible(true).isVisible(), is(true));
    }

    @Test
    @DisplayName("a bone loaded without either member draws, and nothing flips it")
    void theDefaultsAreDrawnAndUntoggled() {
        EntityModelData.Bone plain = new EntityModelData.Bone(PIVOT, ROTATION, BIND, 1f,
            Concurrent.newList(), null);

        assertThat("a bone a mesh is loaded with draws", plain.isVisible(), is(true));
        assertThat("and names no selection that flips it", plain.getToggle(), is(nullValue()));
        assertThat("and stands at no clip displacement", plain.isPoseScaled(), is(false));
    }

}
