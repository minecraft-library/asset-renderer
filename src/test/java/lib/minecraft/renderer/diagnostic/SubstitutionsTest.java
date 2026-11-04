package lib.minecraft.renderer.diagnostic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link Substitutions}: a leaf model id is reported once, in its own wording, however
 * many items name it - its set is keyed by the model, so a second item naming it stays quiet - and the
 * subject, texture and leaf kinds keep separate sets. The subject and texture wordings, and their
 * once-only reports, are held where a render reaches them, by {@code MissingSubjectTest} and
 * {@code RendererContextWithMissingTextureTest}.
 * <p>
 * The reporting sets are static and live as long as the process, so every id below is unique to the
 * test that names it.
 */
@DisplayName("Substitutions reports each stand-in once")
class SubstitutionsTest {

    @Test
    @DisplayName("a subject report does not silence a texture report of the same id")
    void keepsTheTwoKindsApart() {
        String id = "minecraft:substitutions_test_both_kinds";

        String model = errDuring(() -> Substitutions.model(id));
        String texture = errDuring(() -> Substitutions.texture(id));

        assertThat(model, containsString("No block or item '" + id + "'"));
        assertThat(texture, containsString("Missing texture '" + id + "'"));
    }

    @Test
    @DisplayName("a leaf model is reported once per model id, however many items name it")
    void reportsALeafModelOnce() {
        String model = "minecraft:item/substitutions_test_leaf_reported_once";

        String first = errDuring(() -> Substitutions.leafModel(model, "minecraft:diamond_sword"));
        String second = errDuring(() -> Substitutions.leafModel(model, "minecraft:diamond_sword"));
        String other = errDuring(() -> Substitutions.leafModel(model, "minecraft:iron_sword"));

        assertThat(first, containsString("Missing model '" + model + "' named by item 'minecraft:diamond_sword' - drawing the missing model"));
        assertThat(second, is(emptyString()));
        assertThat("the set is keyed by the model, not the item naming it", other, is(emptyString()));
    }

    @Test
    @DisplayName("a leaf report keeps its own set, apart from the subject's")
    void keepsTheLeafKindApart() {
        String id = "minecraft:substitutions_test_leaf_and_subject";

        String subject = errDuring(() -> Substitutions.model(id));
        String leaf = errDuring(() -> Substitutions.leafModel(id, "minecraft:stick"));

        assertThat(subject, containsString("No block or item '" + id + "'"));
        assertThat(leaf, containsString("Missing model '" + id + "'"));
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static String errDuring(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
