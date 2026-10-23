package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part tree's replay of vanilla's map calls on the JDK's own {@code HashMap}, and the one
 * assumption it rests on: that the JDK running it sizes a copy as the JDK vanilla runs on does.
 */
@DisplayName("the part tree replays vanilla's map calls on the JDK's own HashMap")
class PartTreeTest {

    @Test
    @DisplayName("a JDK that sizes a copy unlike vanilla's runtime stops the flow")
    void anOlderJdkIsRefused() {
        ToolingException refused = assertThrows(ToolingException.class, () -> PartTree.requireVanillaCopySizing(18));
        assertTrue(refused.getMessage().contains("'18'"), refused.getMessage());
        assertDoesNotThrow(() -> PartTree.requireVanillaCopySizing(19));
        assertDoesNotThrow(() -> PartTree.requireVanillaCopySizing(25));
        assertDoesNotThrow(() -> PartTree.requireVanillaCopySizing(Runtime.version().feature()),
            "the JDK this suite runs on is one the flow runs on");
    }

    @Test
    @DisplayName("a replaced part's children iterate as a copy, and a put part's as a fresh map")
    void aReplacedPartIteratesAsACopy() {
        // The witch's hat: vanilla replaces it on the villager's mesh, which copies its one child into a
        // two-slot table, and then puts hat2, which grows the table to four.
        PartTree put = new PartTree();
        put.addOrReplace(null, "hat");
        put.addOrReplace("hat", "hat_rim");
        put.addOrReplace("hat", "hat2");
        assertEquals(List.of("hat", "hat2", "hat_rim"), put.order(), "put alone, the level iterates in sixteen slots");

        PartTree replaced = new PartTree();
        replaced.addOrReplace(null, "hat");
        replaced.addOrReplace("hat", "hat_rim");
        replaced.addOrReplace(null, "hat");
        replaced.addOrReplace("hat", "hat2");
        assertEquals(List.of("hat", "hat_rim", "hat2"), replaced.order(), "a copy of one name, then a put, in four slots");
    }

    @Test
    @DisplayName("the calls an entry carries replay to the tree that wrote them")
    void theCarriedCallsReplay() {
        PartTree tree = new PartTree();
        for (String part : List.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"))
            tree.addOrReplace(null, part);
        tree.addOrReplace("head", "hat");
        tree.transformRoot();
        tree.retainExactParts(Set.of("body", "left_arm"));

        JsonTree entry = JsonTree.object();
        tree.writeTo(entry);
        PartTree read = PartTree.of(entry);
        assertEquals(tree.order(), read.order(), "the replayed order");
        assertEquals(tree.toJson().toGson(), read.toJson().toGson(), "the replayed calls");
    }

}
