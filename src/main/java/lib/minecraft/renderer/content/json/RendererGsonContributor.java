package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.math.Vector2f;
import lib.minecraft.renderer.math.Vector3f;
import lib.minecraft.renderer.math.Vector4f;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.ServiceLoader;

/**
 * Registers asset-renderer type adapters with {@link GsonSettings#defaults()} via the
 * {@link GsonContributor} {@link ServiceLoader} SPI.
 * <p>
 * Discovered through {@code META-INF/services/dev.simplified.gson.GsonContributor}; downstream
 * callers that build a {@link Gson} via {@code GsonSettings.defaults().create()} pick up these
 * adapters automatically.
 *
 * <p><b>Parity.</b> Registered by a service file naming an interface another module declares, so
 * nothing in this tree references it and no constant pool can carry an edge to it. Its adapters
 * decide how every value the pipeline parses is read, which is under every render there is.
 */
@Parity(subject = Subject.ENGINE)
public class RendererGsonContributor implements GsonContributor {

    /**
     * Registers the shared renderer type adapters on the given builder so asset JSON deserialises
     * into the renderer's value types: the tensor {@link Vector2f} / {@link Vector3f} / {@link Vector4f}
     * vectors, the {@link ResourceId} {@code namespace:name} id
     * for scalar id fields (the model-id-dialect {@link ModelIdAdapter} is applied per field
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
            .withTypeAdapter(Vector2f.class, new Vector2fAdapter())
            .withTypeAdapter(Vector3f.class, new Vector3fAdapter())
            .withTypeAdapter(Vector4f.class, new Vector4fAdapter())
            .withTypeAdapter(ResourceId.class, new ResourceIdAdapter())
            .withTypeAdapter(Block.Multipart.When.class, new MultipartWhenDeserializer())
            .withTypeAdapter(ItemModelNode.class, new ItemModelNodeDeserializer())
            .withTypeAdapter(LayerTint.class, new LayerTintDeserializer());
    }

}
