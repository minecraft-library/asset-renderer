package lib.minecraft.renderer.diagnostic;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The report of every stand-in a render draws - the missing-model cube for a subject id nothing
 * resolved for, and the checkerboard for a texture id no pack supplied.
 * <p>
 * Each kind keeps its own set of the ids already reported, held for the life of the process, so an
 * unresolved id logs once rather than once per render or once per face that names it, and an id missed
 * both as a subject and as a texture is reported under each. Logs to {@code System.err}, as
 * {@link RuleDiagnostics} does.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
public class Substitutions {

    /** The subject ids already reported, so one unresolved subject logs once rather than once per render. */
    private static final @NotNull ConcurrentSet<String> MODELS = Concurrent.newSet();

    /** The texture ids already reported, so the ninetieth face naming one stays quiet. */
    private static final @NotNull ConcurrentSet<String> TEXTURES = Concurrent.newSet();

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
     * Reports a texture id no pack supplied, the first time it is seen.
     *
     * @param textureId the texture id no pack supplied
     */
    public static void texture(@NotNull String textureId) {
        if (TEXTURES.add(textureId))
            System.err.printf("Missing texture '%s' - drawing the checkerboard%n", textureId);
    }

}
