package lib.minecraft.renderer.pipeline.load;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import org.jetbrains.annotations.NotNull;

/**
 * The single parse home turning a {@code v2/*_geometry.json} bone-tree entry into an
 * {@link EntityModelData} - shared by the block and entity native readers (debt row 9: one bone-tree
 * schema, one parse).
 *
 * <p>The v2 geometry dialect pairs the atlas dimensions as a {@code texture_size:[w,h]} array; the
 * runtime record keeps them as the {@code textureWidth}/{@code textureHeight} scalars, so this helper
 * splits the array before deserialising. The scalar {@code grow} key is read straight into
 * {@link EntityModelData.Cube} via its {@code inflate}/{@code grow} alias; {@code source}/{@code y_axis}
 * provenance on the entry is ignored (unknown Gson fields), and {@code y_axis} is read at the model
 * level by the caller.
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
        return GSON.fromJson(adapted, EntityModelData.class);
    }
}
