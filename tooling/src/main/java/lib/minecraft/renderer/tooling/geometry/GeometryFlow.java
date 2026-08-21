package lib.minecraft.renderer.tooling.geometry;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared geometry pipeline: consumes the manifest a models walk populated in the same
 * session and emits the paired geometry file - own JsonTree tree, own file, recycled
 * discovery, so a manifest key and its geometry entry can never desync across tasks.
 *
 * <p>Per deduped request: parse, stamp the {@code source} twin + {@code texture_size} +
 * {@code y_axis} + {@code cull}, append under the minted factory-coordinate key. Emits an
 * empty-but-valid envelope while the manifest is empty (the flow shell precedes the first
 * registering resolver). A failed parse is a Diagnostics ERROR and a skipped entry - never
 * a fallback literal; {@code GeometryRefClosureTest} then fails on the dangling reference,
 * which is the loud path working.
 */
@UtilityClass
public final class GeometryFlow {

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
     * @param session the live session
     * @throws ToolingException if either package lists no class
     */
    public static void requireModelPackage(@NotNull ToolingSession session) {
        List<String> roots = List.of(
            VanillaSourceClasses.Types.CLIENT_MODEL_ROOT,
            VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT);
        for (String packageRoot : roots) {
            if (session.cache().list(packageRoot, ".class").isEmpty())
                throw new ToolingException(
                    "Client jar lists no class under '%s' - the invokestatic-follow package gate has nothing to match",
                    packageRoot);
        }
    }

    /**
     * Parses every manifest entry and writes the geometry file.
     *
     * @param session the live session
     * @param manifest the registry the models walk populated
     * @param out the output path ({@code entity_geometry.json} / {@code block_geometry.json})
     * @return the bones each factory class's mesh names at top level, by class
     */
    public static @NotNull Map<String, Set<String>> emit(
        @NotNull ToolingSession session, @NotNull GeometryManifest manifest, @NotNull Path out) {

        Diagnostics diagnostics = session.diagnostics().child("geometry");
        Map<String, Set<String>> rootBones = new LinkedHashMap<>();
        Set<String> disagreed = new LinkedHashSet<>();
        JsonTree root = session.envelope(
            "GeometryManifest registration order (walk order; append-last as a data-structure property)");
        JsonTree geometries = root.child("geometries");
        for (Map.Entry<String, GeometryRequest> entry : manifest.entries().entrySet()) {
            String key = entry.getKey();
            GeometryRequest request = entry.getValue();
            Diagnostics scope = diagnostics.child(key);
            JsonTree parsed = GeometryParser.parse(session.cache(), request, scope);
            if (parsed == null) continue;                       // ERROR already recorded by the parser

            JsonTree node = JsonTree.object();
            node.put("source", sourceTwin(request));
            int texWidth = request.texWidthOverride() != null
                ? request.texWidthOverride() : parsed.getInt("textureWidth", 64);
            int texHeight = request.texHeightOverride() != null
                ? request.texHeightOverride() : parsed.getInt("textureHeight", 64);
            node.putInts("texture_size", texWidth, texHeight);
            node.put("y_axis", request.yAxis().name());
            if (GeometryCullResolver.usesCullRenderType(session.cache(), request.factoryClass()))
                node.put("cull", true);
            node.putIf("bones", parsed.find("bones"));
            geometries.put(key, node);
            recordRootBones(rootBones, disagreed, request.factoryClass(), parsed);
        }
        root.write(out);
        diagnostics.info("wrote %s", out.toAbsolutePath());

        disagreed.forEach(rootBones::remove);
        return Collections.unmodifiableMap(rootBones);
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

        Set<String> named = new LinkedHashSet<>();
        bones.members().forEach((name, bone) -> {
            if (bone.findString("parent").isEmpty()) named.add(name);
        });

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
