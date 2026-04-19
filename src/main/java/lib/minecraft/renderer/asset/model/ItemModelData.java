package lib.minecraft.renderer.asset.model;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * The fully-resolved item model parsed from a vanilla JSON file under
 * {@code assets/minecraft/models/item/*.json}.
 * <p>
 * Items are rendered one of two ways: as a flat layered sprite when their parent chain terminates
 * in {@code item/generated} (the {@code textures.layerN} keys are composited in order), or as a
 * three-dimensional model built from {@code elements} when the parent is {@code item/handheld} or
 * a custom model. The {@code display} map's {@code gui} transform is applied when rendering the
 * inventory icon; {@code thirdperson_righthand} when rendering the held-item view.
 */
@Getter
@NoArgsConstructor
public class ItemModelData {

    /** The fully-resolved parent model identifier, or empty for top-level models. */
    private @NotNull Optional<String> parent = Optional.empty();

    /** Texture variable bindings: {@code "layer0" -> "minecraft:item/diamond_sword"}. */
    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    /** The list of element boxes that make up the model (empty for layered flat items). */
    private @NotNull ConcurrentList<ModelElement> elements = Concurrent.newList();

    /** Display transforms keyed by display slot: {@code gui}, {@code head}, {@code thirdperson_righthand}, etc. */
    private @NotNull ConcurrentMap<String, ModelTransform> display = Concurrent.newMap();

    /** Whether this item should render its GUI icon using the 3D {@code elements} pipeline. */
    private boolean guiLight3D = false;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ItemModelData that = (ItemModelData) o;
        return guiLight3D == that.guiLight3D
            && Objects.equals(parent, that.parent)
            && Objects.equals(textures, that.textures)
            && Objects.equals(elements, that.elements)
            && Objects.equals(display, that.display);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent, textures, elements, display, guiLight3D);
    }

}
