package lib.minecraft.renderer.pipeline.load;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The single parse home turning a {@code v2/*_geometry.json} bone-tree entry into an
 * {@link EntityModelData} - shared by the block and entity native readers (debt row 9: one bone-tree
 * schema, one parse).
 *
 * <p>The v2 geometry dialect pairs the atlas dimensions as a {@code texture_size:[w,h]} array; the
 * runtime record keeps them as the {@code textureWidth}/{@code textureHeight} scalars, so this helper
 * splits the array before deserialising. A scalar {@code grow} key is read straight into
 * {@link EntityModelData.Cube} via its {@code inflate}/{@code grow} alias; an {@code [x, y, z]} array
 * {@code grow} (an asymmetric {@code CubeDeformation}) is rewritten per cube to the {@code grow_axis}
 * key so it deserialises into {@link EntityModelData.Cube#getGrow() the per-axis grow} instead of
 * failing the scalar alias. Every 26.1 cube is scalar, so the rewrite never fires. {@code source} /
 * {@code y_axis} provenance on the entry is ignored (unknown Gson fields); {@code y_axis} is read at
 * the model level by the caller.
 */
public final class V2Geometry {

    /** Shared Gson configured with the project defaults, used to deserialise into the model record. */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private V2Geometry() {}

    /**
     * Parses a v2 geometry entry into the runtime model record, adapting the {@code texture_size}
     * array into the {@code textureWidth}/{@code textureHeight} scalars.
     *
     * @param geometry the v2 geometry entry (a {@code geometries[<coord>]} object)
     * @return the parsed model geometry
     */
    public static @NotNull EntityModelData parse(@NotNull JsonObject geometry) {
        JsonObject adapted = geometry.deepCopy();
        JsonElement textureSize = adapted.remove("texture_size");
        if (textureSize != null && textureSize.isJsonArray()) {
            JsonArray dimensions = textureSize.getAsJsonArray();
            adapted.addProperty("textureWidth", dimensions.get(0).getAsInt());
            adapted.addProperty("textureHeight", dimensions.get(1).getAsInt());
        }
        if (adapted.has("bones") && adapted.get("bones").isJsonObject())
            for (Map.Entry<String, JsonElement> bone : adapted.getAsJsonObject("bones").entrySet())
                adaptPerAxisGrow(bone.getValue());
        return GSON.fromJson(adapted, EntityModelData.class);
    }

    /**
     * Rewrites each of a bone's cubes' {@code [x, y, z]} array {@code grow} to the {@code grow_axis}
     * key (a scalar {@code grow} is left for the {@code inflate} alias). No 26.1 cube carries an array
     * grow, so this is a no-op there; it exists so a future asymmetric {@code CubeDeformation} loads as
     * a per-axis grow rather than throwing on the scalar alias.
     */
    private static void adaptPerAxisGrow(@NotNull JsonElement bone) {
        if (!bone.isJsonObject()) return;
        JsonObject boneObject = bone.getAsJsonObject();
        if (!boneObject.has("cubes") || !boneObject.get("cubes").isJsonArray()) return;
        for (JsonElement cube : boneObject.getAsJsonArray("cubes")) {
            if (!cube.isJsonObject()) continue;
            JsonObject cubeObject = cube.getAsJsonObject();
            JsonElement grow = cubeObject.get("grow");
            if (grow != null && grow.isJsonArray()) {
                cubeObject.remove("grow");
                cubeObject.add("grow_axis", grow);
            }
        }
    }
}
