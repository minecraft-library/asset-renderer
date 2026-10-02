package lib.minecraft.renderer.engine.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import lib.minecraft.renderer.bake.mesh.BlockGeometryKit;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The angles the client's block and item models author in degrees, held to converting to one radian
 * by {@link EulerRotation}'s route and by vanilla's.
 *
 * <p>The renderer narrows {@code Math.toRadians} to {@code float}, in {@link EulerRotation}'s accessors
 * for a {@code display} rotation and in the same expression in {@link BlockGeometryKit} for an
 * element's. Vanilla multiplies in float by {@code 0.017453292f}, in {@code ItemTransform.apply} and
 * both {@code CuboidRotation} forms. The two routes land one ULP apart on some degrees, and the renderer
 * keeps its own on the strength of no authored angle being one of them. That is a fact about the
 * client's models rather than about either route, so a version bump can falsify it with no renderer
 * edit, and this reads every model under {@code models/block} and {@code models/item} to ask again.
 *
 * <p>An element angle is read in both spellings vanilla accepts - {@code axis} with {@code angle}, and
 * {@code x}, {@code y} and {@code z} - so an angle authored in the second still counts.
 */
@DisplayName("EulerRotation's radian is vanilla's for every display and element angle the client authors")
@ExtendWith(ClientAssetsExtension.class)
class EulerRotationVanillaAnglesTest {

    /** The factor vanilla multiplies a degree by, in float, to reach its radian. */
    private static final float VANILLA_DEGREES_TO_RADIANS = 0.017453292f;

    /** The extracted client's model directory, relative to the vanilla pack root. */
    private static final @NotNull String MODELS = "assets/minecraft/models";

    /** The subtrees of {@link #MODELS} that author a display rotation or an element angle. */
    private static final @NotNull List<String> SUBTREES = List.of("block", "item");

    /** The members of an element's {@code rotation} that hold an angle, across both of vanilla's spellings. */
    private static final @NotNull List<String> ELEMENT_ANGLES = List.of("angle", "x", "y", "z");

    /** The rule this measurement backs, so a failure names the decision to re-measure. */
    private static final @NotNull String RULE = "RENDERER-RULES.md, \"Do not switch EulerRotation's "
        + "degrees-to-radians conversion to vanilla's float multiply\"";

    @Test
    @DisplayName("no display or element angle is one of the degrees the two routes part on")
    void everyAuthoredAngleConvertsAsVanillaDoes() {
        Map<Float, String> display = new TreeMap<>();
        Map<Float, String> element = new TreeMap<>();
        for (Path model : models()) {
            String name = modelsRoot().relativize(model).toString().replace('\\', '/');
            JsonObject json = read(model);
            readDisplay(json, name, display);
            readElements(json, name, element);
        }

        assertThat("distinct display rotation values read under " + SUBTREES, display, is(not(anEmptyMap())));
        assertThat("distinct element angles read under " + SUBTREES, element, is(not(anEmptyMap())));

        List<String> parted = new ArrayList<>(parted("display", display));
        parted.addAll(parted("element", element));
        assertThat("authored angles EulerRotation converts to a different radian than vanilla's float multiply, of "
                + display.size() + " distinct display values and " + element.size() + " distinct element angles. "
                + RULE + " rests on there being none - re-measure that decision at this client version rather"
                + " than reading this as a renderer defect",
            parted, is(empty()));
    }

    @Test
    @DisplayName("the two routes part on 66 of the 721 integer degrees in [-360, 360]")
    void theRoutesPartOnSixtySixIntegerDegrees() {
        long parted = IntStream.rangeClosed(-360, 360).filter(EulerRotationVanillaAnglesTest::parts).count();

        assertThat("integer degrees in [-360, 360] where EulerRotation's radian is not vanilla's - the figure "
            + RULE + " states, and what lets the authored-angle check fail at all", parted, is(66L));
    }

    /**
     * Adds every value of every {@code display} transform's {@code rotation} to the values seen so far.
     *
     * @param model the parsed model
     * @param name the model's path under the model directory, kept for the first value it supplies
     * @param into each distinct value to the first model authoring it
     */
    private static void readDisplay(@NotNull JsonObject model, @NotNull String name, @NotNull Map<Float, String> into) {
        if (!(model.get("display") instanceof JsonObject transforms)) return;

        for (Map.Entry<String, JsonElement> transform : transforms.entrySet()) {
            if (transform.getValue().getAsJsonObject().get("rotation") instanceof JsonArray rotation)
                rotation.forEach(degrees -> into.putIfAbsent(degrees.getAsFloat(), name));
        }
    }

    /**
     * Adds every element's rotation angle to the angles seen so far, in either of vanilla's spellings.
     *
     * @param model the parsed model
     * @param name the model's path under the model directory, kept for the first angle it supplies
     * @param into each distinct angle to the first model authoring it
     */
    private static void readElements(@NotNull JsonObject model, @NotNull String name, @NotNull Map<Float, String> into) {
        if (!(model.get("elements") instanceof JsonArray elements)) return;

        for (JsonElement element : elements) {
            if (!(element.getAsJsonObject().get("rotation") instanceof JsonObject rotation)) continue;
            for (String member : ELEMENT_ANGLES) {
                if (rotation.get(member) instanceof JsonPrimitive angle && angle.isNumber())
                    into.putIfAbsent(angle.getAsFloat(), name);
            }
        }
    }

    /**
     * Lists the angles of one kind the two routes convert to different radians, each with the model
     * that authors it and both radians.
     *
     * @param kind what the angles are, for the failure message
     * @param angles each distinct angle to the first model authoring it
     * @return one line per parting angle
     */
    private static @NotNull List<String> parted(@NotNull String kind, @NotNull Map<Float, String> angles) {
        return angles.entrySet().stream()
            .filter(angle -> parts(angle.getKey()))
            .map(angle -> kind + " " + angle.getKey() + " in " + angle.getValue() + ": "
                + rendererRadians(angle.getKey()) + " where vanilla has " + vanillaRadians(angle.getKey()))
            .toList();
    }

    /**
     * Answers whether the two routes convert an angle to different floats.
     *
     * @param degrees the angle in degrees
     * @return {@code true} when the renderer's radian is not vanilla's
     */
    private static boolean parts(float degrees) {
        return Float.compare(rendererRadians(degrees), vanillaRadians(degrees)) != 0;
    }

    /**
     * Converts an angle the way the renderer does, through {@link EulerRotation}'s own accessor.
     *
     * @param degrees the angle in degrees
     * @return the renderer's radian
     */
    private static float rendererRadians(float degrees) {
        return new EulerRotation(degrees, 0f, 0f).pitchRadians();
    }

    /**
     * Converts an angle the way vanilla does, by a float multiply.
     *
     * @param degrees the angle in degrees
     * @return vanilla's radian
     */
    private static float vanillaRadians(float degrees) {
        return degrees * VANILLA_DEGREES_TO_RADIANS;
    }

    /**
     * Lists every model file under the authoring subtrees, sorted so the first model named for a value
     * is the same on every run.
     *
     * @return the model files
     */
    private static @NotNull List<Path> models() {
        List<Path> models = new ArrayList<>();
        for (String subtree : SUBTREES) {
            try (Stream<Path> files = Files.walk(modelsRoot().resolve(subtree))) {
                files.filter(file -> file.toString().endsWith(".json")).sorted().forEach(models::add);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return models;
    }

    /**
     * Parses one model file.
     *
     * @param model the model file
     * @return the parsed model
     */
    private static @NotNull JsonObject read(@NotNull Path model) {
        try {
            return JsonParser.parseString(Files.readString(model)).getAsJsonObject();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Resolves the extracted client's model directory.
     *
     * @return the directory
     */
    private static @NotNull Path modelsRoot() {
        return ClientAssetsExtension.vanillaRoot().resolve(MODELS);
    }

}
