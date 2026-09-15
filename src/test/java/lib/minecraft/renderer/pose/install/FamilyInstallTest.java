package lib.minecraft.renderer.pose.install;

import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.pose.author.BuiltStyle;
import lib.minecraft.renderer.pose.author.Poses;
import lib.minecraft.renderer.pose.author.Turn;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One stem over however many bones a subject numbers with it.
 *
 * <p>A family buys exactly one thing over naming each bone: the count is the mesh's answer. These
 * install one chain on subjects that spell the same stem a different number of times, and on one
 * that spells it not at all.
 */
@DisplayName("a family stance lands on every bone the subject numbers with the stem")
class FamilyInstallTest {

    /**
     * One stance over every member of one stem.
     */
    private static @NotNull BuiltStyle wave(@NotNull String styleId, @NotNull String stem) {
        return Poses.custom(styleId)
            .family(stem, member -> member.sway(Turn.YAW, -12, 12))
            .build();
    }

    /**
     * The driver fields one style tolerantly installed on one subject spells, sorted.
     */
    private static @NotNull List<String> fieldsOf(@NotNull BuiltStyle style,
                                                  @NotNull String entityId) {
        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        registrar.addTolerant(entityId, style);
        Entity woven = registrar.definitions().get(entityId);
        PoseStyle installed = woven.styles().byId(style.styleId()).orElseThrow();
        return installed.drivers().keySet().stream().sorted().toList();
    }

    @Test
    @DisplayName("the cat's split tail answers a stem where it answers no tail bone")
    void aSplitTailAnswersTheStem() {
        List<String> cat = fieldsOf(wave("swish", "tail"), "minecraft:cat");

        assertEquals(2, cat.size(), () -> "the cat numbers its tail twice: " + cat);
        assertTrue(cat.stream().anyMatch(field -> field.contains("tail1")), () -> cat.toString());
        assertTrue(cat.stream().anyMatch(field -> field.contains("tail2")), () -> cat.toString());
    }

    @Test
    @DisplayName("the same stem answers one bone on a subject that spells it once")
    void oneStemReachesWhateverTheMeshNumbers() {
        List<String> wolf = fieldsOf(wave("swish", "tail"), "minecraft:wolf");
        List<String> dragon = fieldsOf(wave("swish", "tail"), "minecraft:ender_dragon");

        assertEquals(1, wolf.size(), () -> "a wolf spells one bare tail: " + wolf);
        assertEquals(12, dragon.size(), () -> "a dragon numbers twelve: " + dragon);
    }

    @Test
    @DisplayName("a stem the mesh numbers no bone for drops on a tolerant install and refuses a strict one")
    void anUnansweredStemDropsAndRefuses() {
        assertEquals(List.of(), fieldsOf(wave("swish", "tail"), "minecraft:creeper"),
            "a creeper numbers no tail, so the stance lands nowhere");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> StyleRegistrar.ofShipped().add("minecraft:creeper", wave("swish", "tail")));
        assertTrue(refusal.getMessage().contains("tail family"),
            () -> "the refusal names the stem in the author's own terms: " + refusal.getMessage());
    }

    @Test
    @DisplayName("a stem outside the leg vocabulary reaches a ring of arms the same way")
    void aStemReachesWhatIsNotALegAtAll() {
        List<String> arms = fieldsOf(wave("drift", "tentacle"), "minecraft:squid");

        assertEquals(8, arms.size(), () -> "a squid numbers eight arms: " + arms);
        assertTrue(arms.stream().allMatch(field -> field.contains("tentacle")), arms::toString);
    }

}
