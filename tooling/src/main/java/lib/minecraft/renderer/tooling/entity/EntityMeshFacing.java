package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the constant turn a renderer folds into its delegated body rotation into the mesh it turns,
 * so no member states it and nothing at render adds it back.
 *
 * <p>Vanilla's {@code setupRotations} may fold a literal into the body rotation it hands the base -
 * {@code ShulkerRenderer} passes {@code bodyRot + 180f}, and every other renderer passes it through.
 * The base applies that rotation as the subject's FACING, so it reaches every render rather than only
 * the ones that pose, which is why it cannot travel as a container step.
 *
 * <p><b>It goes in the CUBE's rotation slot, never the bone's.</b> A bone's rotation is what a pose
 * writes, and a pose REPLACES the channel it writes rather than composing onto it - so a turn left
 * there survives on the bones the pose does not name and is discarded on the ones it does, which
 * tears the subject apart rather than shifting it. Nothing writes a cube's slot at render, so a turn
 * put there is still there when the mesh draws.
 *
 * <p>The two land in the same place because of what the chain is. A root bone draws at
 * {@code T(pivot) . R_bone . [cube] . box} and the render applies the facing outside all of it, at
 * {@code R_y(turn) . T(pivot) . R_bone . box}. A pivot standing on the axis the turn is about lets
 * the turn pass the translate, and rotations about one axis commute with each other, so it passes the
 * bone's rotation too and arrives exactly where the cube slot puts it. Those are the two conditions
 * {@link #apply} refuses without, and they are the whole of why this is exact rather than close.
 *
 * <p>The cube's own box and texture map are built before its rotation applies, so the turn moves the
 * finished cube and its texture together. Nothing here reaches a UV.
 */
@UtilityClass
public final class EntityMeshFacing {

    /** The mesh sources a subject can name that the age walk below does not reach. */
    private static final @NotNull List<String> UNREACHED =
        List.of("overlays", "block_overlays", "equipment", "armor");

    /**
     * Turns every mesh whose renderer folds a facing, and refuses where the turn would not survive.
     *
     * @param diagnostics the flow's sink
     * @param models the model table's {@code models} node
     * @param geometries the parsed entries, keyed by minted coordinate, modified in place
     * @param facings the turn each renderer folds, in degrees, by renderer simple name
     * @param rigid the models whose pose a turn about y would NOT survive
     * @param composing the simple names of renderers that compose steps above their meshes
     * @throws ToolingException if a subject's mesh, pose or renderer would not carry the turn intact
     */
    public static void apply(
        @NotNull Diagnostics diagnostics, @NotNull JsonTree models,
        @NotNull Map<String, JsonTree> geometries, @NotNull Map<String, Float> facings,
        @NotNull Set<String> rigid, @NotNull Set<String> composing) {

        if (facings.isEmpty()) return;
        Map<String, Float> byCoordinate = new LinkedHashMap<>();
        models.members().forEach((id, subject) -> {
            String renderer = subject.findString("renderer").map(ClassKit::simpleName).orElse(null);
            Float degrees = renderer == null ? null : facings.get(renderer);
            if (degrees == null) return;

            // A step composes above the mesh where the facing composes outside the step, so a
            // renderer doing both would need the turn to pass the step as well, which nothing here
            // has checked. No renderer in the corpus does both.
            if (composing.contains(renderer))
                throw new ToolingException(
                    "entity '%s' folds a facing of '%s' AND composes steps above its meshes, "
                        + "and the turn has only been proven to pass a pose",
                    id, degrees);
            for (String member : UNREACHED)
                if (subject.find(member).isPresent())
                    throw new ToolingException(
                        "entity '%s' folds a facing of '%s' and also declares '%s', whose meshes this "
                            + "does not reach - every mesh a turned subject draws has to be turned",
                        id, degrees, member);

            for (JsonTree option : ageOptions(subject)) {
                String coordinate = option.findString("geometry").orElse(null);
                if (coordinate == null) continue;
                Float held = byCoordinate.putIfAbsent(coordinate, degrees);
                if (held != null && !held.equals(degrees))
                    throw new ToolingException(
                        "geometry '%s' is turned by both '%s' and '%s', and one mesh faces one way",
                        coordinate, held, degrees);
                requireTurnable(id, coordinate, rigid);
            }
        });

        byCoordinate.forEach((coordinate, degrees) -> {
            JsonTree entry = geometries.get(coordinate);
            if (entry == null) return;                          // a dangling ref the closure test owns
            turn(coordinate, entry, degrees);
            diagnostics.info("turned '%s' by %s degree(s) about y", coordinate, degrees);
        });
    }

    /**
     * Refuses a coordinate whose model poses in a way a turn about y would not survive.
     *
     * <p>The model is the coordinate's own head, split at the first {@code #} the way every reader of
     * this keyspace splits it. A model nobody posed is absent from the refusals and is safe by that
     * absence, which is why the pose flow states its FAILURES rather than its passes.
     */
    private static void requireTurnable(
        @NotNull String id, @NotNull String coordinate, @NotNull Set<String> rigid) {

        int split = coordinate.indexOf('#');
        String model = split < 0 ? coordinate : coordinate.substring(0, split);
        if (rigid.contains(model))
            throw new ToolingException(
                "entity '%s' folds a facing onto '%s', whose pose turns it off the y axis - the facing "
                    + "would not compose with what the pose writes",
                id, coordinate);
    }

    /**
     * Puts the turn in every cube of every root bone, refusing a mesh that cannot carry it.
     *
     * <p>The cube's anchor is set to the bone's own origin rather than to the cube's, because the turn
     * is about the subject's axis and a bone-local origin is where that axis passes once the bone
     * chain has translated to it.
     */
    private static void turn(@NotNull String coordinate, @NotNull JsonTree entry, float degrees) {
        JsonTree bones = entry.find("bones").orElse(null);
        if (bones == null) return;
        bones.members().forEach((name, bone) -> {
            if (bone.findString("parent").isPresent()) return;
            requireOnAxis(coordinate, name, bone);
            bone.findArray("cubes").ifPresent(cubes -> cubes.elements().forEach(cube -> {
                if (cube.find("rotation").isPresent() || cube.find("pivot").isPresent())
                    throw new ToolingException(
                        "geometry '%s' bone '%s' has a cube already turning about its own anchor, and "
                            + "one slot cannot hold that turn and the subject's facing both",
                        coordinate, name);
                cube.putFloats("pivot", 0f, 0f, 0f);
                cube.putFloats("rotation", 0f, degrees, 0f);
            }));
        });
    }

    /** Refuses a root bone the turn would move, which is one standing off the axis or already turning. */
    private static void requireOnAxis(@NotNull String coordinate, @NotNull String name, @NotNull JsonTree bone) {
        if (bone.find("rotation").isPresent() || bone.find("bind_pose_rotation").isPresent())
            throw new ToolingException(
                "geometry '%s' bone '%s' already turns, and the facing would have to compose with it "
                    + "into one slot the pose then overwrites",
                coordinate, name);
        JsonTree pivot = bone.findArray("pivot").orElse(null);
        if (pivot == null) return;
        if (pivot.getFloat(0, 0f) != 0f || pivot.getFloat(2, 0f) != 0f)
            throw new ToolingException(
                "geometry '%s' bone '%s' stands at x=%s z=%s, off the axis the facing turns about, so "
                    + "the turn would move it as well as face it",
                coordinate, name, pivot.getFloat(0, 0f), pivot.getFloat(2, 0f));
    }

    /** The age axis' options, empty where the subject declares no age axis. */
    private static @NotNull List<JsonTree> ageOptions(@NotNull JsonTree subject) {
        return subject.find("axes")
            .flatMap(axes -> axes.find("age"))
            .flatMap(age -> age.find("options"))
            .map(declared -> declared.members().values().collect(Collectors.toList()))
            .orElse(List.of());
    }

}
