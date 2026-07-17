package lib.minecraft.renderer.asset.model;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The fully-resolved block or item model parsed from a vanilla JSON file under
 * {@code assets/minecraft/models/block/*.json} or {@code .../item/*.json}.
 * <p>
 * By the time an instance lives inside a {@code Block} or {@code Item}, every reference to a parent
 * has been walked and deep-merged so the textures map and elements list already contain everything
 * needed to render. No lazy resolution happens at render time.
 * <p>
 * Block and item models share the same shape ({@code textures}, {@code elements},
 * {@code display}); the two domain-specific flags ({@link #ambientocclusion} for blocks,
 * {@link #guiLight3D} for items) default such that each is a no-op for the other domain's models.
 */
@Getter
@NoArgsConstructor
public class ModelData {

    /**
     * Whether the model should receive ambient occlusion during rendering. Defaults to
     * {@code true}, which matches vanilla for most solid blocks. Block-domain field.
     */
    private boolean ambientocclusion = true;

    /**
     * Texture variable bindings: {@code "#top" -> "minecraft:block/grass_block_top"} for blocks,
     * {@code "layer0" -> "minecraft:item/diamond_sword"} for items. Object-form 26.1 entries
     * ({@code {sprite, force_translucent}}) are flattened to their sprite string here; the flag is
     * retained separately on {@link #textureObjects}.
     */
    private @NotNull ConcurrentMap<String, String> textures = Concurrent.newMap();

    /**
     * The object-form texture entries that carried 26.1 sampler flags, keyed by the same variable
     * name as {@link #textures} ({@code "all" -> ModelTexture("minecraft:block/glass", true)}).
     * Present only for the {@code {sprite, force_translucent}} object form - string-form entries are
     * absent. Retained-not-consumed: it is deliberately excluded from {@link #equals}/{@link #hashCode}
     * so it never perturbs model dedup, and no renderer reads it yet (a later trigger folds
     * {@link ModelTexture#forceTranslucent} into the translucent sort).
     */
    @Setter
    private @NotNull ConcurrentMap<String, ModelTexture> textureObjects = Concurrent.newMap();

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
            && Objects.equals(textures, that.textures)
            && Objects.equals(elements, that.elements)
            && Objects.equals(display, that.display);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ambientocclusion, textures, elements, display, guiLight3D);
    }

}
