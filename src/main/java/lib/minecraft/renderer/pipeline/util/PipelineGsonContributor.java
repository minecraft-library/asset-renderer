package lib.minecraft.renderer.pipeline.util;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ArgbColor;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

import java.util.ServiceLoader;

/**
 * Registers asset-renderer type adapters with {@link GsonSettings#defaults()} via the
 * {@link GsonContributor} {@link ServiceLoader} SPI.
 * <p>
 * Discovered through {@code META-INF/services/dev.simplified.gson.GsonContributor}; downstream
 * callers that build a {@link Gson} via {@code GsonSettings.defaults().create()} pick up these
 * adapters automatically.
 */
public class PipelineGsonContributor implements GsonContributor {

    /**
     * Registers the shared renderer type adapters on the given builder so asset JSON deserialises
     * into the renderer's value types: the tensor {@link Vector2f} / {@link Vector3f} / {@link Vector4f}
     * vectors, the {@link ArgbColor} hex-colour, and the {@link ResourceId} {@code namespace:name} id
     * for scalar id fields (the model-id-dialect {@link ResourceId.ModelIdAdapter} is applied per field
     * with {@code @JsonAdapter}, not globally).
     *
     * @param builder the Gson settings builder to contribute to
     */
    @Override
    public void contribute(@NotNull GsonSettings.Builder builder) {
        builder
            .withTypeAdapter(Vector2f.class, new Vector2f.Adapter())
            .withTypeAdapter(Vector3f.class, new Vector3f.Adapter())
            .withTypeAdapter(Vector4f.class, new Vector4f.Adapter())
            .withTypeAdapter(ArgbColor.class, new ArgbColor.Adapter())
            .withTypeAdapter(ResourceId.class, new ResourceId.Adapter());
    }

}
