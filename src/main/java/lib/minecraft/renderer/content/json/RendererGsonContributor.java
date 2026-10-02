package lib.minecraft.renderer.content.json;

import com.google.gson.Gson;
import dev.simplified.gson.GsonContributor;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.mesh.TextureSize;
import lib.minecraft.renderer.asset.model.ModelTexture;
import lib.minecraft.renderer.engine.geometry.EulerRotation;
import lib.minecraft.renderer.engine.math.Vector2f;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.engine.math.Vector4f;
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
     * Registers the renderer's type adapters and its one factory on the given builder, so asset JSON
     * deserialises into the renderer's value types wherever each appears:
     * <ul>
     *     <li><b>vectors</b> - {@link Vector2f}, {@link Vector3f} and {@link Vector4f} as their arrays</li>
     *     <li><b>rotations</b> - {@link EulerRotation} as its {@code [pitch, yaw, roll]} array</li>
     *     <li><b>mesh and model leaves</b> - {@link TextureSize} as its {@code [w, h]} array, and
     *     {@link ModelTexture} as its string or {@code sprite} / {@code force_translucent} object form</li>
     *     <li><b>ids</b> - {@link ResourceId} as a scalar id field's {@code namespace:name} string</li>
     *     <li><b>unions</b> - the multipart {@link Block.Multipart.When} condition, the item dispatch
     *     tree's {@link ItemModelNode} and its per-layer {@link LayerTint}, each nested term resolving
     *     through the same registration</li>
     *     <li><b>cube grow</b> - {@link CubeGrowFactory}, which binds an {@link EntityMesh.Cube}'s
     *     {@code grow} member through {@link CubeGrowAdapter}</li>
     * </ul>
     *
     * @param builder the Gson settings builder to contribute to
     */
    @Override
    public void contribute(@NotNull GsonSettings.Builder builder) {
        builder
            .withTypeAdapter(Vector2f.class, new Vector2fAdapter())
            .withTypeAdapter(Vector3f.class, new Vector3fAdapter())
            .withTypeAdapter(Vector4f.class, new Vector4fAdapter())
            .withTypeAdapter(EulerRotation.class, new EulerRotationAdapter())
            .withTypeAdapter(TextureSize.class, new TextureSizeAdapter())
            .withTypeAdapter(ModelTexture.class, new ModelTextureAdapter())
            .withTypeAdapter(ResourceId.class, new ResourceIdAdapter())
            .withTypeAdapter(Block.Multipart.When.class, new MultipartWhenDeserializer())
            .withTypeAdapter(ItemModelNode.class, new ItemModelNodeDeserializer())
            .withTypeAdapter(LayerTint.class, new LayerTintDeserializer());
        builder.withFactories(new CubeGrowFactory());
    }

}
