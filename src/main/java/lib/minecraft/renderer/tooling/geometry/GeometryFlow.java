package lib.minecraft.renderer.tooling.geometry;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;

/**
 * The shared geometry pipeline: consumes the manifest a models walk populated in the same
 * session and emits the paired geometry file - own JsonNode tree, own file, recycled
 * discovery, so a manifest key and its geometry entry can never desync across tasks.
 *
 * <p>Per deduped request: parse, stamp the {@code source} twin + {@code texture_size} +
 * {@code y_axis} + {@code cull}, append under the minted factory-coordinate key. Emits an
 * empty-but-valid envelope while the manifest is empty (the flow shell precedes the first
 * registering resolver). A failed parse is a Diagnostics ERROR and a skipped entry - never
 * a fallback literal; {@code GeometryRefClosureTest} then fails on the dangling reference,
 * which is the loud path working.
 */
public final class GeometryFlow {

    private GeometryFlow() {
    }

    /**
     * Parses every manifest entry and writes the geometry file.
     *
     * @param session the live session
     * @param manifest the registry the models walk populated
     * @param out the output path ({@code entity_geometry.json} / {@code block_geometry.json})
     */
    public static void emit(@NotNull ToolingSession session, @NotNull GeometryManifest manifest, @NotNull Path out) {
        Diagnostics diagnostics = session.diagnostics().child("geometry");
        JsonNode root = JsonNode.envelope(session,
            "GeometryManifest registration order (walk order; append-last as a data-structure property)");
        JsonNode geometries = root.child("geometries");
        for (Map.Entry<String, GeometryRequest> entry : manifest.entries().entrySet()) {
            String key = entry.getKey();
            GeometryRequest request = entry.getValue();
            Diagnostics scope = diagnostics.child(key);
            JsonNode parsed = GeometryParser.parse(session.cache(), request, scope);
            if (parsed == null) continue;                       // ERROR already recorded by the parser

            JsonNode node = JsonNode.object();
            node.put("source", sourceTwin(request));
            int texWidth = request.texWidthOverride() != null
                ? request.texWidthOverride() : parsed.getInt("textureWidth", 64);
            int texHeight = request.texHeightOverride() != null
                ? request.texHeightOverride() : parsed.getInt("textureHeight", 64);
            node.putInts("texture_size", texWidth, texHeight);
            node.put("y_axis", request.yAxis().name());
            if (GeometryCullResolver.usesCullRenderType(session.cache(), request.factoryClass()))
                node.put("cull", true);
            node.putIf("bones", parsed.get("bones"));
            geometries.put(key, node);
        }
        root.writeResource(out, diagnostics);
    }

    /**
     * The structured {@code source} twin of the factory-coordinate key: the full class
     * coordinate plus the same discriminators the key encodes, machine-readable.
     */
    private static @NotNull JsonNode sourceTwin(@NotNull GeometryRequest request) {
        JsonNode source = JsonNode.object()
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
        int[] intParams = request.paramIntValues();
        if (intParams != null) {
            JsonNode bound = source.childArray("iparam");
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
