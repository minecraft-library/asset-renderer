package lib.minecraft.renderer.pipeline.util;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.item.ItemModelNode;
import lib.minecraft.renderer.pipeline.pack.MultipartWhenDeserializer;
import lib.minecraft.renderer.pipeline.pack.item.ItemModelNodeDeserializer;
import lib.minecraft.renderer.pipeline.pack.item.LayerTintDeserializer;
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
     * vectors, the {@link ResourceId} {@code namespace:name} id
     * for scalar id fields (the model-id-dialect {@link ResourceId.ModelIdAdapter} is applied per field
     * with {@code @JsonAdapter}, not globally), the multipart {@link Block.Multipart.When} condition
     * union (its {@code AND} / {@code OR} recursion resolves through the same registration), and the item
     * dispatch tree - the {@link ItemModelNode} type-discriminated tree (recursing through the context)
     * and its per-layer {@link LayerTint}.
     *
     * @param builder the Gson settings builder to contribute to
     */
    @Override
    public void contribute(@NotNull GsonSettings.Builder builder) {
        builder
            .withTypeAdapter(Vector2f.class, new Vector2f.Adapter())
            .withTypeAdapter(Vector3f.class, new Vector3f.Adapter())
            .withTypeAdapter(Vector4f.class, new Vector4f.Adapter())
            .withTypeAdapter(ResourceId.class, new ResourceId.Adapter())
            .withTypeAdapter(Block.Multipart.When.class, new MultipartWhenDeserializer())
            .withTypeAdapter(ItemModelNode.class, new ItemModelNodeDeserializer())
            .withTypeAdapter(LayerTint.class, new LayerTintDeserializer());
    }

}
