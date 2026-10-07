package lib.minecraft.renderer.diagnostic;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The report of every stand-in a render draws, and of every picture it draws short of what it was
 * asked for:
 * <ul>
 * <li><b>{@link #model}</b> - the missing-model cube for a subject id nothing resolved for.</li>
 * <li><b>{@link #leafModel}</b> - the missing model for a model id an item definition's leaf names and
 * no pack ships.</li>
 * <li><b>{@link #texture}</b> - the checkerboard for a texture id no pack supplied.</li>
 * <li><b>{@link #flatIcon}</b> - a GUI icon asked to draw a model whose shape is its elements, which
 * a GUI icon does not draw.</li>
 * </ul>
 * <p>
 * Each kind keeps its own set of the ids already reported, held for the life of the process, so an
 * unresolved id logs once rather than once per render or once per face that names it, and an id missed
 * under two kinds is reported under each. Logs to {@code System.err}, as {@link RuleDiagnostics} does.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class Substitutions {

    /** The subject ids already reported, so one unresolved subject logs once rather than once per render. */
    private static final @NotNull ConcurrentSet<String> MODELS = Concurrent.newSet();

    /** The leaf model ids already reported, so one definition's typo logs once however many items name it. */
    private static final @NotNull ConcurrentSet<String> LEAF_MODELS = Concurrent.newSet();

    /** The texture ids already reported, so the ninetieth face naming one stays quiet. */
    private static final @NotNull ConcurrentSet<String> TEXTURES = Concurrent.newSet();

    /** The model ids already reported as drawn flat in a GUI slot. */
    private static final @NotNull ConcurrentSet<String> FLAT_ICONS = Concurrent.newSet();

    /**
     * Reports a subject id nothing resolved for, the first time it is seen.
     *
     * @param subjectId the block or item id neither index carries
     */
    public static void model(@NotNull String subjectId) {
        if (MODELS.add(subjectId))
            System.err.printf("Missing model for '%s' - drawing the missing-model cube%n", subjectId);
    }

    /**
     * Reports a model id an item definition's leaf names and no pack ships, the first time it is seen,
     * as vanilla warns of a missing model once per id.
     *
     * @param modelId the model id the leaf names
     * @param itemId the item whose definition named it, the first time it is seen
     */
    public static void leafModel(@NotNull String modelId, @NotNull String itemId) {
        if (LEAF_MODELS.add(modelId))
            System.err.printf("Missing model '%s' named by item '%s' - drawing the missing model%n", modelId, itemId);
    }

    /**
     * Reports a model an item definition selects for a GUI icon whose shape is its elements, the first
     * time it is seen. A GUI icon draws a model's {@code layerN} sprites alone, so the icon does not
     * show the model it was asked for.
     *
     * @param modelId the model id the item definition selected
     * @param itemId the item whose definition selected it, the first time it is seen
     */
    public static void flatIcon(@NotNull String modelId, @NotNull String itemId) {
        if (FLAT_ICONS.add(modelId))
            System.err.printf("Model '%s' named by item '%s' is drawn by its elements, which a GUI icon does not draw%n",
                modelId, itemId);
    }

    /**
     * Reports a texture id no pack supplied, the first time it is seen.
     *
     * @param textureId the texture id no pack supplied
     */
    public static void texture(@NotNull String textureId) {
        if (TEXTURES.add(textureId))
            System.err.printf("Missing texture '%s' - drawing the checkerboard%n", textureId);
    }

}
