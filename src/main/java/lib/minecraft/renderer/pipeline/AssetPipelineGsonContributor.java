package lib.minecraft.renderer.pipeline;

import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tensor.Vector4f;
import org.jetbrains.annotations.NotNull;

/**
 * Registers asset-renderer type adapters with {@link GsonSettings#defaults()} via the
 * {@link GsonContributor} {@link java.util.ServiceLoader} SPI.
 * <p>
 * Discovered through {@code META-INF/services/dev.simplified.gson.GsonContributor}; downstream
 * callers that build a {@link com.google.gson.Gson} via {@code GsonSettings.defaults().create()}
 * pick up these adapters automatically.
 */
public class AssetPipelineGsonContributor implements GsonContributor {

    @Override
    public void contribute(GsonSettings.@NotNull Builder builder) {
        builder.withTypeAdapter(Vector4f.class, new Vector4f.Adapter());
    }

}
