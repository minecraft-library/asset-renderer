package lib.minecraft.renderer.pipeline;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
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

    @Override
    public void contribute(@NotNull GsonSettings.Builder builder) {
        builder
            .withTypeAdapter(Vector2f.class, new Vector2f.Adapter())
            .withTypeAdapter(Vector3f.class, new Vector3f.Adapter())
            .withTypeAdapter(Vector4f.class, new Vector4f.Adapter());
    }

}
