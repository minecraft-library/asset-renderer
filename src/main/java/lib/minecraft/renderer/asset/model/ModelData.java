package lib.minecraft.renderer.asset.model;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.engine.texture.Textures;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The fully-resolved block or item model parsed from a vanilla JSON file under
 * {@code assets/minecraft/models/block/*.json} or {@code .../item/*.json}.
 * <p>
 * By the time an instance lives inside a {@code Block} or {@code Item}, every reference to a parent
 * has been walked and deep-merged so the textures map and elements list already contain everything
 * needed to render. No lazy resolution happens at render time.
 * <p>
 * Block and item models share the same shape ({@code textures}, {@code elements},
 * {@code display}), plus the block-domain {@link #ambientocclusion} flag, which defaults to a no-op
 * for item models.
 */
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class ModelData {

    /**
     * Whether the model should receive ambient occlusion during rendering. Defaults to
     * {@code true}, which matches vanilla for most solid blocks. Block-domain field.
     */
    private boolean ambientocclusion = true;

    /**
     * Texture variable bindings: {@code "#top" -> "minecraft:block/grass_block_top"} for blocks,
     * {@code "layer0" -> "minecraft:item/diamond_sword"} for items. Both authored shapes parse to a
     * {@link ModelTexture}: a bare string to {@code (sprite, false)}, the 26.1 object form
     * ({@code {sprite, force_translucent}}) to a value carrying the flag.
     */
    private @NotNull ConcurrentMap<String, ModelTexture> textures = Concurrent.newMap();

    /**
     * The list of element boxes that make up the model (empty for layered flat items).
     */
    private @NotNull ConcurrentList<ModelElement> elements = Concurrent.newList();

    /**
     * Display transforms keyed by display slot: {@code gui}, {@code head}, {@code thirdperson_righthand}, etc.
     */
    private @NotNull ConcurrentMap<String, ModelTransform> display = Concurrent.newMap();

    /**
     * Whether this model would render nothing: no element face resolves to a concrete texture, and
     * no renderable texture binding (a sprite {@code layerN} for items, or any non-{@code particle}
     * binding for blocks) is concrete either.
     * <p>
     * This is the derived replacement for the hand-curated {@code TEMPLATE_*_NAMES} lists: a model is
     * a template iff it would render blank. Concrete variant models ({@code block/acacia_slab_top},
     * {@code item/clock_00}, the armor-trim items, the multipart submodels) all carry a resolvable
     * texture and are kept; pure inheritance parents ({@code item/generated}, {@code block/cross},
     * {@code block/slab}, {@code item/air}) carry only unresolved {@code #variable} references and are
     * dropped.
     * <p>
     * Face references are dereferenced against {@link #textures} via
     * {@link Textures#resolveTextureReference}; a result still starting with {@code #} is an unresolved
     * {@code #variable} (a parent-template placeholder) and does not count as renderable. The
     * {@code particle} binding is ignored for blocks because it never draws on a face. The second
     * check keeps degenerate-but-textured models (a block whose geometry comes from a default cube
     * rather than an explicit {@code elements} array).
     *
     * @param item whether this is an item model ({@code layerN} sprites) or a block model
     * @return whether the model renders nothing
     */
    public boolean rendersNothing(boolean item) {
        for (ModelElement element : this.elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank()) continue;
                if (!Textures.resolveTextureReference(ref, this.textures).startsWith("#")) return false;
            }
        }

        for (Map.Entry<String, ModelTexture> binding : this.textures.entrySet()) {
            ModelTexture value = binding.getValue();
            if (value == null || value.sprite().startsWith("#")) continue;
            String key = binding.getKey();
            if (item ? key.startsWith("layer") : !key.equals("particle")) return false;
        }
        return true;
    }

}
