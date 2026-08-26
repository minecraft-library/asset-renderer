package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the {@code setupRotations} translate into the mesh it moves, and takes the member naming it
 * off the model table.
 *
 * <p>It lands on the mesh because the renderer and the canvas-sizing bounds walk both read the mesh:
 * moving it is what keeps a subject and the frame measured around it from disagreeing. Vanilla
 * translates in world space where {@code +Y} is up and the mesh is authored Y-down, so the sign flips
 * on the way in; only root bones move, a child's pivot being relative to its parent.
 *
 * <p><b>A transform and a shift are two spellings of one {@code setupRotations} and only one may
 * answer.</b> The reader used to refuse a subject carrying both, off the member this removes, so the
 * refusal moves here - where the shift is still known and the renderer's steps are already walked.
 * Both together would move the subject twice.
 */
@UtilityClass
public final class EntityMeshShift {

    /** Model units per block - the scale a vanilla {@code PoseStack} translate is expressed against. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    /**
     * Translates every shifted mesh and strips the member that asked for it.
     *
     * @param diagnostics the flow's sink
     * @param models the model table's {@code models} node
     * @param geometries the parsed entries, keyed by minted coordinate, modified in place
     * @param composingRenderers the simple names of renderers that compose steps above their meshes
     * @throws ToolingException if a subject carries both a render transform and a shift
     */
    public static void apply(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, JsonTree> geometries, @NotNull Set<String> composingRenderers) {

        Map<String, Float> byCoordinate = new LinkedHashMap<>();
        models.members().forEach((id, subject) -> {
            for (JsonTree option : ageOptions(subject)) {
                float blocks = option.getFloat("y_shift", 0f);
                if (blocks == 0f) continue;
                if (composes(subject, composingRenderers))
                    throw new ToolingException(
                        "entity '%s' carries both a render transform and a setupRotations y shift, "
                            + "which would move it twice",
                        id);
                String coordinate = option.findString("geometry").orElse(null);
                option.remove("y_shift");
                if (coordinate == null) continue;
                Float held = byCoordinate.putIfAbsent(coordinate, blocks);
                if (held != null && held != blocks)
                    throw new ToolingException(
                        "geometry '%s' is shifted by both '%s' and '%s', and one mesh stands in one place",
                        coordinate, held, blocks);
            }
        });

        byCoordinate.forEach((coordinate, blocks) -> {
            JsonTree entry = geometries.get(coordinate);
            if (entry == null) return;                          // a dangling ref the closure test owns
            shift(entry, blocks);
            diagnostics.info("shifted '%s' by %s block(s)", coordinate, blocks);
        });
    }

    /** Moves every root bone of one mesh along Y by a vanilla translation in blocks. */
    private static void shift(@NotNull JsonTree entry, float blocks) {
        JsonTree bones = entry.find("bones").orElse(null);
        if (bones == null) return;
        float delta = -blocks * MODEL_UNITS_PER_BLOCK;
        bones.members().forEach((name, bone) -> {
            if (bone.findString("parent").isPresent()) return;
            JsonTree pivot = bone.findArray("pivot").orElse(null);
            if (pivot == null) return;
            bone.putFloats("pivot",
                pivot.getFloat(0, 0f), pivot.getFloat(1, 0f) + delta, pivot.getFloat(2, 0f));
        });
    }

    /** Whether the subject's renderer composes steps above every mesh it submits. */
    private static boolean composes(@NotNull JsonTree subject, @NotNull Set<String> composingRenderers) {
        String renderer = subject.findString("renderer").orElse(null);
        return renderer != null && composingRenderers.contains(ClassKit.simpleName(renderer));
    }

    /** The age axis' options, empty where the subject declares no age axis. */
    private static @NotNull List<JsonTree> ageOptions(@NotNull JsonTree subject) {
        List<JsonTree> options = new ArrayList<>();
        subject.find("axes")
            .flatMap(axes -> axes.find("age"))
            .flatMap(age -> age.find("options"))
            .ifPresent(declared -> declared.members().forEach((name, option) -> options.add(option)));
        return options;
    }

}
