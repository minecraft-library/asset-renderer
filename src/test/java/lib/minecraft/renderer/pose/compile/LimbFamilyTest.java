package lib.minecraft.renderer.pose.compile;

import lib.minecraft.renderer.asset.model.EntityModelData;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static lib.minecraft.renderer.pose.compile.CompilerFixtures.bone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bones one stem answers, and in what order.
 *
 * <p>A family is a spelling rule and nothing else, so what it owes is that the rule is the one
 * written down: the stem alone or the stem and a number, across an optional underscore, and
 * nothing a stem merely begins.
 */
@DisplayName("a stem answers the bones a mesh numbers with it, in numeric order")
class LimbFamilyTest {

    /**
     * A mesh spelling one stem every way the corpus does, beside three names that only begin with
     * it.
     */
    private static @NotNull EntityModelData spellings() {
        EntityModelData mesh = new EntityModelData();
        for (String named : List.of("tail", "tail0", "tail_1", "tail10", "tail2",
            "tail_base", "tail_fin", "tailbone", "real_tail", "body"))
            mesh.getBones().put(named, bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));
        return mesh;
    }

    @Test
    @DisplayName("the bare stem leads and the numbered members follow in numeric order")
    void membersAnswerInNumericOrder() {
        assertEquals(List.of("tail", "tail0", "tail_1", "tail2", "tail10"),
            List.copyOf(LimbFamily.members(spellings(), "tail")),
            "ten past two, which is what a name-ordered walk gets wrong");
    }

    @Test
    @DisplayName("a name the stem only begins is no member")
    void aStemDoesNotReachWhatItMerelyBegins() {
        List<String> members = List.copyOf(LimbFamily.members(spellings(), "tail"));

        assertFalse(members.contains("tail_base"), members::toString);
        assertFalse(members.contains("tail_fin"), members::toString);
        assertFalse(members.contains("tailbone"), members::toString);
        assertFalse(members.contains("real_tail"), members::toString);
    }

    @Test
    @DisplayName("a stem the mesh numbers no bone for answers nothing")
    void anUnspelledStemAnswersNothing() {
        assertEquals(List.of(), List.copyOf(LimbFamily.members(spellings(), "tentacle")));
    }

    @Test
    @DisplayName("a family reports whether its members hang off one another or stand as siblings")
    void chainingIsReportedRatherThanAssumed() {
        EntityModelData siblings = new EntityModelData();
        siblings.getBones().put("arm0", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "body"));
        siblings.getBones().put("arm1", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "body"));
        siblings.getBones().put("body", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));

        EntityModelData chain = new EntityModelData();
        chain.getBones().put("arm0", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "body"));
        chain.getBones().put("arm1", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, "arm0"));
        chain.getBones().put("body", bone(0f, 0f, 0f, 0f, 0f, 0f, 1f, null));

        assertFalse(LimbFamily.chained(siblings, LimbFamily.members(siblings, "arm")),
            "two arms hanging off the trunk each turn by what they were given");
        assertTrue(LimbFamily.chained(chain, LimbFamily.members(chain, "arm")),
            "one arm hanging off the other compounds what it was given");
    }

}
