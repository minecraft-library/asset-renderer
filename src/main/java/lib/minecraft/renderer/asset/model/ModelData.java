package lib.minecraft.renderer.asset.model;

import com.google.gson.annotations.SerializedName;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * The fully-resolved block or item model parsed from a vanilla JSON file under
 * {@code assets/minecraft/models/block/*.json} or {@code .../item/*.json}.
 * <p>
 * By the time an instance lives inside a {@code Block} or {@code Item}, every reference to a parent
 * has been walked and deep-merged so the textures map and elements list already contain everything
 * needed to render. No lazy resolution happens at render time.
 * <p>
 * Block and item models share the same shape ({@code parent}, {@code textures}, {@code elements},
 * {@code display}); the two domain-specific flags ({@link #ambientocclusion} for blocks,
 * {@link #guiLight3D} for items) default such that each is a no-op for the other domain's models.
 */
@Getter
@NoArgsConstructor
public class ModelData {

    /**
     * The optional parent model identifier. Populated only for introspection / debugging after
     * the resolver has already merged its contents into this instance.
     */
    private @NotNull Optional<String> parent = Optional.empty();

    /**
     * Whether the model should receive ambient occlusion during rendering. Defaults to
     * {@code true}, which matches vanilla for most solid blocks. Block-domain field.
     */
    @SerializedName("ambientocclusion")
    private boolean ambientocclusion = true;

    /**
     * Texture variable bindings: {@code "#top" -> "minecraft:block/grass_block_top"} for blocks,
     * {@code "layer0" -> "minecraft:item/diamond_sword"} for items.
     */
    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    /**
     * The list of element boxes that make up the model (empty for layered flat items).
     */
    private @NotNull ConcurrentList<ModelElement> elements = Concurrent.newList();

    /**
     * Display transforms keyed by display slot: {@code gui}, {@code head}, {@code thirdperson_righthand}, etc.
     */
    private @NotNull ConcurrentMap<String, ModelTransform> display = Concurrent.newMap();

    /**
     * Whether this item should render its GUI icon using the 3D {@code elements} pipeline.
     * Item-domain field.
     */
    private boolean guiLight3D = false;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModelData that = (ModelData) o;
        return ambientocclusion == that.ambientocclusion
            && guiLight3D == that.guiLight3D
            && Objects.equals(parent, that.parent)
            && Objects.equals(textures, that.textures)
            && Objects.equals(elements, that.elements)
            && Objects.equals(display, that.display);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, ambientocclusion, textures, elements, display, guiLight3D);
    }

}
