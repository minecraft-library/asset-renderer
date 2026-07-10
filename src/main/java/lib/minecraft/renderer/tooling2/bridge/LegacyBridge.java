package lib.minecraft.renderer.tooling2.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The single entry to the temporary new-&gt;legacy bridge (10-bridge SS2.2): behind
 * {@code -Dasset.tooling2.bridge}, each pipeline loader receives a legacy-shaped tree
 * materialized FROM the v2 resources instead of its checked-in legacy classpath resource, and
 * {@code BridgeParityTest} asserts canonical-SHA equality of that reconstruction against the
 * checked-in legacy bytes (the information-completeness proof).
 *
 * <p>Pure JSON-&gt;JSON: this package imports no {@code ZipFile}, no ASM, and no old-tooling
 * classes; its inputs are the nine v2 classpath resources and its outputs the eight legacy-shaped
 * trees. It is the ONLY sanctioned place besides the kernel for raw Gson - the loaders consume
 * Gson types, so seams adapt through {@link JsonNode#toGson()}, and the converters build legacy
 * trees directly on Gson so value channels (int-vs-float, packed-hex strings) pass through
 * byte-for-byte from the v2 parse.
 *
 * <p>Temporary by charter: deleted at pipeline-rewrite completion together with the loader seams,
 * the {@code legacy_id} field, and the legacy resources + SHA fixtures (10-bridge SS9).
 */
public final class LegacyBridge {

    /** The fork-lifetime activation flag - rides the {@code asset.*} forwarder (10-bridge SS2.1). */
    private static final @NotNull String FLAG = "asset.tooling2.bridge";

    /** Classpath directory of the v2 resources the converters read. */
    private static final @NotNull String V2_DIR = "/lib/minecraft/renderer/v2/";

    /** The shared read Gson - same settings as every tooling2 parse, member order preserved. */
    static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private LegacyBridge() {
    }

    /**
     * Whether the bridge is active for this JVM.
     *
     * <p>A fork-lifetime switch - {@code EntityModelLoader.loadFamilies()} caches families for the
     * JVM lifetime, so the flag is read once per fork and never toggled mid-JVM (10-bridge SS2.1).
     * All gradle-forked consumers satisfy this by construction.
     *
     * @return {@code true} when {@code -Dasset.tooling2.bridge=true}
     */
    public static boolean active() {
        return Boolean.getBoolean(FLAG);
    }

    /**
     * Materializes the legacy-shaped tree for {@code legacyResourceName} from the v2 resources.
     *
     * @param legacyResourceName the legacy base name (e.g. {@code "block_models.json"})
     * @return the legacy-shaped tree, wrapped for the seam's {@link JsonNode#toGson()} adapt
     * @throws ToolingException if the name is unknown or its v2 inputs cannot be read
     */
    public static @NotNull JsonNode materialize(@NotNull String legacyResourceName) {
        JsonObject legacy = switch (legacyResourceName) {
            case "block_tints.json", "potion_colors.json", "glint_items.json", "color_maps.json" ->
                SnapshotBridge.convert(legacyResourceName, readV2(legacyResourceName));
            case "block_defaults.json" -> BlockDefaultsBridge.convert(readV2("block_defaults.json"));
            case "block_models.json" ->
                BlockModelsBridge.convert(readV2("block_models.json"), readV2("block_geometry.json"));
            case "entity_geometry.json" ->
                EntityGeometryBridge.convert(readV2("entity_models.json"), readV2("entity_geometry.json"));
            case "entity_models.json" ->
                EntityModelsBridge.convert(readV2("entity_models.json"), readV2("entity_geometry.json"));
            default -> throw new ToolingException("No tooling2 bridge converter for '%s'", legacyResourceName);
        };
        return JsonNode.wrap(legacy);
    }

    /**
     * Reads and parses a v2 classpath resource into a Gson object.
     *
     * @param name the v2 base name (e.g. {@code "block_geometry.json"})
     * @return the parsed object, member order preserved
     * @throws ToolingException if the resource is missing or unparseable
     */
    static @NotNull JsonObject readV2(@NotNull String name) {
        String path = V2_DIR + name;
        try (InputStream in = LegacyBridge.class.getResourceAsStream(path)) {
            if (in == null) throw new ToolingException("Missing v2 resource '%s'", path);
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class).getAsJsonObject();
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to read v2 resource '%s'", path);
        }
    }

    /**
     * Builds the shared legacy envelope: the declared {@code //} header plus {@code source_version}
     * copied from the v2 file. Legacy files carry NO {@code format} member (10-bridge SS5.5); the
     * caller adds its own payload member next.
     *
     * @param header the legacy generator-comment header (a converter-private constant)
     * @param v2 the v2 source object whose {@code source_version} stamp is copied
     * @return the two-member envelope root
     */
    static @NotNull JsonObject legacyEnvelope(@NotNull String header, @NotNull JsonObject v2) {
        JsonObject out = new JsonObject();
        out.addProperty("//", header);
        out.addProperty("source_version", v2.get("source_version").getAsString());
        return out;
    }

}
