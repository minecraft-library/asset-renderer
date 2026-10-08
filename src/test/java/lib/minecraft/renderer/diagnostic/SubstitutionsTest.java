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
 * Coverage of {@link Substitutions}: a subject id, a leaf model id and a texture id are each reported
 * once, in its own wording, however often it is drawn, and the kinds keep separate sets. A leaf
 * model's set is keyed by the model, so a second item naming it stays quiet.
 * <p>
 * The reporting sets are static and live as long as the process, so every id below is unique to the
 * test that names it.
 */
@DisplayName("Substitutions reports each stand-in once")
class SubstitutionsTest {

    @Test
    @DisplayName("a subject is reported once however often it is drawn")
    void reportsASubjectOnce() {
        String id = "minecraft:missing_model_kit_test_reported_once";

        String first = errDuring(() -> Substitutions.model(id));
        String second = errDuring(() -> Substitutions.model(id));

        assertThat(first, containsString("Missing model for '" + id + "' - drawing the missing-model cube"));
        assertThat(second, is(emptyString()));
    }

    @Test
    @DisplayName("a texture is reported once however many faces name it")
    void reportsATextureOnce() {
        String id = "minecraft:block/substitutions_test_texture_reported_once";

        String first = errDuring(() -> Substitutions.texture(id));
        String second = errDuring(() -> Substitutions.texture(id));

        assertThat(first, containsString("Missing texture '" + id + "' - drawing the checkerboard"));
        assertThat(second, is(emptyString()));
    }

    @Test
    @DisplayName("a subject report does not silence a texture report of the same id")
    void keepsTheTwoKindsApart() {
        String id = "minecraft:substitutions_test_both_kinds";

        String model = errDuring(() -> Substitutions.model(id));
        String texture = errDuring(() -> Substitutions.texture(id));

        assertThat(model, containsString("Missing model for '" + id + "'"));
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

        assertThat(subject, containsString("Missing model for '" + id + "'"));
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
