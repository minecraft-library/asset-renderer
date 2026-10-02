package lib.minecraft.renderer.tooling.geometry;

import com.google.gson.Gson;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.content.table.TableEnvelope;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shared geometry pipeline: consumes the manifest a models walk populated in the same
 * run and emits the paired geometry file - own JsonTree tree, own file, recycled
 * discovery, so a manifest key and its geometry entry can never desync across tasks.
 *
 * <p>Per deduped request: parse, stamp the {@code source} twin + {@code texture_size} +
 * {@code cull}, append under the minted factory-coordinate key. Emits an
 * empty-but-valid envelope while the manifest is empty (the flow shell precedes the first
 * registering resolver). A failed parse is a Diagnostics ERROR and a skipped entry - never
 * a fallback literal; {@code GeometryRefClosureTest} then fails on the dangling reference,
 * which is the loud path working.
 */
@UtilityClass
public final class GeometryFlow {

    /** The binding the renderer's loader reads a shipped geometry entry through. */
    private static final @NotNull Gson MESHES = GsonSettings.defaults().create();

    /**
     * Asserts the client jar carries both sides of the package gate the parser applies to an
     * {@code invokestatic} target - the model package it follows into, and the
     * geometry-primitive package it decodes in place. A listing answers empty rather than
     * failing, so this assertion is what turns a relocated package into a stopped run instead
     * of geometry that quietly loses every shared mesh helper.
     *
     * <p>Runs once, before the flow's first table is written: the strict gate reads its counters
     * after every write, so an entry recorded there and carried on would leave a table on disk
     * that no walk stands behind.
     *
     * @param run the live run
     * @throws ToolingException if either package lists no class
     */
    public static void requireModelPackage(@NotNull ToolingRun run) {
        List<String> roots = List.of(
            SourceClasses.Types.CLIENT_MODEL_ROOT,
            SourceClasses.Types.CLIENT_MODEL_GEOM_ROOT);
        for (String packageRoot : roots) {
            if (run.cache().list(packageRoot, ".class").isEmpty())
                throw new ToolingException(
                    "Client jar lists no class under '%s' - the invokestatic-follow package gate has nothing to match",
                    packageRoot);
        }
    }

    /**
     * Parses every manifest entry into the entry each minted key is written as, WITHOUT writing the
     * file.
     *
     * <p>Held apart from {@link #write} because a flow can have something to say about a mesh that
     * is not known until after the geometry has been parsed: which bones a subject rests without is
     * settled by the pose flow, and the pose flow reads the root bones this answers. So the entries
     * are parsed, taken through that, and written once - rather than written twice, which would
     * leave a table on disk that the second pass then contradicts. A flow with nothing to say
     * between the two hands one straight to the other.
     *
     * @param run the live run
     * @param manifest the registry the models walk populated
     * @return the entry per minted key, in registration order
     */
    public static @NotNull Map<String, JsonTree> parse(
        @NotNull ToolingRun run, @NotNull GeometryManifest manifest) {

        Diagnostics diagnostics = run.diagnostics().child("geometry");
        Map<String, JsonTree> entries = new LinkedHashMap<>();
        for (Map.Entry<String, GeometryRequest> entry : manifest.entries().entrySet()) {
            String key = entry.getKey();
            GeometryRequest request = entry.getValue();
            Diagnostics scope = diagnostics.child(key);
            JsonTree parsed = GeometryParser.parse(run.cache(), request, scope);
            if (parsed == null) continue;                       // ERROR already recorded by the parser

            JsonTree node = JsonTree.object();
            node.put("source", sourceTwin(request));
            int texWidth = request.texWidthOverride() != null
                ? request.texWidthOverride() : parsed.getInt("textureWidth", 64);
            int texHeight = request.texHeightOverride() != null
                ? request.texHeightOverride() : parsed.getInt("textureHeight", 64);
            node.putInts("texture_size", texWidth, texHeight);
            if (GeometryCullResolver.usesCullRenderType(run.cache(), request.factoryClass()))
                node.put("cull", true);
            node.putIf("bones", parsed.find("bones"));
            entries.put(key, node);
        }
        return entries;
    }

    /**
     * The bones each factory class's mesh names at top level, by class.
     *
     * @param manifest the registry the entries were parsed from
     * @param entries the parsed entries
     * @return the top-level bone set per factory class, omitting a class whose meshes disagree
     */
    public static @NotNull Map<String, Set<String>> rootBones(
        @NotNull GeometryManifest manifest, @NotNull Map<String, JsonTree> entries) {

        Map<String, Set<String>> rootBones = new LinkedHashMap<>();
        Set<String> disagreed = new LinkedHashSet<>();
        entries.forEach((key, node) ->
            recordRootBones(rootBones, disagreed, manifest.entries().get(key).factoryClass(), node));
        disagreed.forEach(rootBones::remove);
        return Collections.unmodifiableMap(rootBones);
    }

    /**
     * Where each part one mesh hangs from its root rests, as a pose's read of the part's own position
     * answers it, or nothing where that read is not the stored pivot.
     *
     * <p>The entry is read into the renderer's own {@link EntityMesh} through the binding the
     * renderer's loader reads the shipped table with, so the factor test is the mesh's own
     * {@link EntityMesh#getFlattenedScale()}. On a mesh flattened at a factor other than one every
     * top-level pivot carries that factor and the feet-anchor translate, and a read crosses both, so
     * such a mesh answers nothing; on one flattened at nothing a read of a part's position is its
     * pivot, and that is what is answered.
     *
     * <p>Only a part hanging from the root is answered. That is the part whose stored pivot is the
     * very field vanilla's model holds - an aged-down mesh writes its proportions into the top-level
     * parts' poses as vanilla's transform does - where a part below one carries the factor of the
     * part above it in its pivot and vanilla's own field does not.
     *
     * <p>The answer is the mesh as parsed, before any later pass moves it.
     *
     * @param entry the parsed entry
     * @return each top-level part's pivot by name, or empty where the mesh is flattened at a factor
     */
    public static @NotNull Optional<Map<String, Vector3f>> partRests(@NotNull JsonTree entry) {
        EntityMesh mesh = MESHES.fromJson(entry.toGson(), EntityMesh.class);
        if (mesh.getFlattenedScale() != 1f) return Optional.empty();
        Map<String, Vector3f> rests = new LinkedHashMap<>();
        mesh.getBones().forEach((name, bone) -> {
            if (bone.getParent() == null) rests.put(name, bone.getPivot());
        });
        return Optional.of(Collections.unmodifiableMap(rests));
    }

    /**
     * Writes the parsed entries as the geometry file.
     *
     * @param run the live run
     * @param entries the entry per minted key, in registration order
     * @param out the output path ({@code entity_geometry.json} / {@code block_geometry.json})
     */
    public static void write(
        @NotNull ToolingRun run, @NotNull Map<String, JsonTree> entries, @NotNull Path out) {

        JsonTree root = TableEnvelope.mint(run.diagnostics().path(),
            "GeometryManifest registration order (walk order; append-last as a data-structure property)",
            run.options().getVersion());
        JsonTree geometries = root.child("geometries");
        entries.forEach(geometries::put);
        root.write(out);
        run.diagnostics().child("geometry").info("wrote %s", out.toAbsolutePath());
    }

    /**
     * Notes the bones one mesh names at top level, against the class that built it.
     *
     * <p>A bone with no parent is one the mesh root holds directly, which is what a caller asking
     * this wants: the set a transform on that container reaches, the container itself being flattened
     * away by the time a mesh is written.
     *
     * <p>A class appears once per mesh derivation and its derivations have to AGREE, because what
     * this answers is a fact about the model rather than about one of its meshes. One whose baby and
     * adult meshes name different sets has no single answer, so it gets none rather than one of them.
     */
    private static void recordRootBones(
        @NotNull Map<String, Set<String>> rootBones, @NotNull Set<String> disagreed,
        @NotNull String factoryClass, @NotNull JsonTree parsed) {

        JsonTree bones = parsed.find("bones").orElse(null);
        if (bones == null) return;

        Set<String> named = bones.members()
            .filter((name, bone) -> bone.findString("parent").isEmpty())
            .keys()
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> held = rootBones.putIfAbsent(factoryClass, Collections.unmodifiableSet(named));
        if (held != null && !held.equals(named)) disagreed.add(factoryClass);
    }

    /**
     * Returns the machine-readable {@code source} twin of the factory-coordinate key: the full
     * class coordinate plus the same discriminators the key encodes, in the same canonical order.
     *
     * @param request the deduped request
     * @return the {@code source} object stamped onto the geometry entry
     */
    private static @NotNull JsonTree sourceTwin(@NotNull GeometryRequest request) {
        JsonTree source = JsonTree.object()
            .put("class", request.factoryClass())
            .put("method", request.factoryMethod());
        float[] grow = request.grow();
        if (grow[0] != 0f || grow[1] != 0f || grow[2] != 0f) {
            if (grow[0] == grow[1] && grow[1] == grow[2]) source.put("grow", grow[0]);
            else source.putFloats("grow", grow[0], grow[1], grow[2]);
        }
        float[] floatParams = request.paramFloatValues();
        if (floatParams != null && floatParams.length > 0 && floatParams[0] != 0f)
            source.put("fparam", floatParams[0]);
        if (request.appliedMeshTransformerScale() != 1f)
            source.put("scaled", request.appliedMeshTransformerScale());
        BabyMeshTransform baby = request.babyTransform();
        if (baby != null) source.put("baby", baby.discriminator());
        GeometryRequest.PoseParam pose = request.poseParam();
        if (pose != null && (pose.offset()[0] != 0f || pose.offset()[1] != 0f || pose.offset()[2] != 0f))
            source.putFloats("pose", pose.offset()[0], pose.offset()[1], pose.offset()[2]);
        int[] intParams = request.paramIntValues();
        if (intParams != null) {
            JsonTree bound = source.childArray("iparam");
            boolean any = false;
            for (int slot = 0; slot < intParams.length; slot++) {
                if (intParams[slot] == 0) continue;
                bound.add(slot + ":" + intParams[slot]);
                any = true;
            }
            if (!any) bound.add("0:0");
        }
        if (request.refParam() != null)
            source.put("ref", request.refParam().value());
        return source;
    }

}
