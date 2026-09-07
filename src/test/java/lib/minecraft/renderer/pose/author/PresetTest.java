package lib.minecraft.renderer.pose.author;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins for the preset silhouettes - the shipped whole-degree triples, spot-checked row by row.
 */
@DisplayName("preset silhouettes carry their whole-degree triples")
class PresetTest {

    @Test
    @DisplayName("the roster is twenty named silhouettes plus T-pose and at-ease")
    void rosterSize() {
        assertEquals(22, Preset.values().length);
    }

    @Test
    @DisplayName("attention is all zeros - stiffer than the stand's own rest")
    void attentionIsAllZeros() {
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.head());
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.body());
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.rightArm());
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.leftArm());
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.rightLeg());
        assertEquals(Preset.Triple.ZERO, Preset.ATTENTION.leftLeg());
    }

    @Test
    @DisplayName("sitting folds both thighs forward and rests the arms ahead")
    void sittingRow() {
        assertEquals(new Preset.Triple(-80, 20, 0), Preset.SITTING.rightArm());
        assertEquals(new Preset.Triple(-80, -20, 0), Preset.SITTING.leftArm());
        assertEquals(new Preset.Triple(-90, 10, 0), Preset.SITTING.rightLeg());
        assertEquals(new Preset.Triple(-90, -10, 0), Preset.SITTING.leftLeg());
        assertEquals(Preset.Triple.ZERO, Preset.SITTING.head());
        assertEquals(Preset.Triple.ZERO, Preset.SITTING.body());
    }

    @Test
    @DisplayName("walking swings arms and legs in opposition")
    void walkingRow() {
        assertEquals(new Preset.Triple(20, 0, 10), Preset.WALKING.rightArm());
        assertEquals(new Preset.Triple(-20, 0, -10), Preset.WALKING.leftArm());
        assertEquals(new Preset.Triple(-20, 0, 0), Preset.WALKING.rightLeg());
        assertEquals(new Preset.Triple(20, 0, 0), Preset.WALKING.leftLeg());
    }

    @Test
    @DisplayName("pointing levels the right arm and turns the head along it")
    void pointingRow() {
        assertEquals(new Preset.Triple(0, 20, 0), Preset.POINTING.head());
        assertEquals(new Preset.Triple(-90, 18, 0), Preset.POINTING.rightArm());
        assertEquals(new Preset.Triple(0, 0, -10), Preset.POINTING.leftArm());
        assertEquals(Preset.Triple.ZERO, Preset.POINTING.rightLeg());
    }

    @Test
    @DisplayName("salute folds the right forearm across the brow")
    void saluteRow() {
        assertEquals(new Preset.Triple(-124, -51, -35), Preset.SALUTE.rightArm());
        assertEquals(new Preset.Triple(29, 0, 25), Preset.SALUTE.leftArm());
        assertEquals(new Preset.Triple(5, 0, 0), Preset.SALUTE.body());
        assertEquals(new Preset.Triple(0, -4, -2), Preset.SALUTE.rightLeg());
        assertEquals(new Preset.Triple(0, 4, 2), Preset.SALUTE.leftLeg());
    }

    @Test
    @DisplayName("stargazing reaches the right arm nearly vertical")
    void stargazingRow() {
        assertEquals(new Preset.Triple(-22, 25, 0), Preset.STARGAZING.head());
        assertEquals(new Preset.Triple(-4, 10, 0), Preset.STARGAZING.body());
        assertEquals(new Preset.Triple(-153, 34, -3), Preset.STARGAZING.rightArm());
        assertEquals(new Preset.Triple(4, 18, 0), Preset.STARGAZING.leftArm());
        assertEquals(new Preset.Triple(-4, 17, 2), Preset.STARGAZING.rightLeg());
        assertEquals(new Preset.Triple(6, 24, 0), Preset.STARGAZING.leftLeg());
    }

    @Test
    @DisplayName("the T-pose is arm roll alone, mirrored")
    void tPoseRow() {
        assertEquals(new Preset.Triple(0, 0, 90), Preset.T_POSE.rightArm());
        assertEquals(new Preset.Triple(0, 0, -90), Preset.T_POSE.leftArm());
        assertEquals(Preset.Triple.ZERO, Preset.T_POSE.head());
        assertEquals(Preset.Triple.ZERO, Preset.T_POSE.body());
        assertEquals(Preset.Triple.ZERO, Preset.T_POSE.rightLeg());
        assertEquals(Preset.Triple.ZERO, Preset.T_POSE.leftLeg());
    }

    @Test
    @DisplayName("at-ease is the armor stand's rest - arms hung, legs splayed a degree")
    void atEaseRow() {
        assertEquals(new Preset.Triple(-15, 0, 10), Preset.AT_EASE.rightArm());
        assertEquals(new Preset.Triple(-10, 0, -10), Preset.AT_EASE.leftArm());
        assertEquals(new Preset.Triple(1, 0, 1), Preset.AT_EASE.rightLeg());
        assertEquals(new Preset.Triple(-1, 0, -1), Preset.AT_EASE.leftLeg());
        assertEquals(Preset.Triple.ZERO, Preset.AT_EASE.head());
        assertEquals(Preset.Triple.ZERO, Preset.AT_EASE.body());
    }

    @Test
    @DisplayName("every triple in the roster is whole degrees")
    void everyValueIsWholeDegrees() {
        for (Preset preset : Preset.values()) {
            for (Preset.Triple triple : new Preset.Triple[] {
                preset.head(), preset.body(), preset.rightArm(),
                preset.leftArm(), preset.rightLeg(), preset.leftLeg()
            }) {
                assertEquals(Math.rint(triple.pitchDegrees()), triple.pitchDegrees(), preset + " pitch");
                assertEquals(Math.rint(triple.yawDegrees()), triple.yawDegrees(), preset + " yaw");
                assertEquals(Math.rint(triple.rollDegrees()), triple.rollDegrees(), preset + " roll");
            }
        }
    }

}
