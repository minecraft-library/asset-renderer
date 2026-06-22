package lib.minecraft.renderer.pipeline.util;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.TextureEngine;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Model-content helper shared by the pipeline index loaders: testing whether a parsed model would
 * render nothing. Namespaced model-id parsing now lives on
 * {@link lib.minecraft.renderer.asset.ResourceId}.
 */
@UtilityClass
public class Models {

    /**
     * Returns {@code true} when a model would render nothing: no element face resolves to a
     * concrete texture, and no renderable texture binding (a sprite {@code layerN} for items, or
     * any non-{@code particle} binding for blocks) is concrete either.
     * <p>
     * This is the derived replacement for the hand-curated {@code TEMPLATE_*_NAMES} lists: a model
     * is a template iff it would render blank. Concrete variant models ({@code block/acacia_slab_top},
     * {@code item/clock_00}, the armor-trim items, the multipart submodels) all carry a resolvable
     * texture and are kept; pure inheritance parents ({@code item/generated}, {@code block/cross},
     * {@code block/slab}, {@code item/air}) carry only unresolved {@code #variable} references and
     * are dropped.
     * <p>
     * Face references are dereferenced against {@code textures} via
     * {@link TextureEngine#resolveTextureReference}; a result still starting with {@code #} is an
     * unresolved {@code #variable} (a parent-template placeholder) and does not count as renderable.
     * The {@code particle} binding is ignored for blocks because it never draws on a face. The
     * second check keeps degenerate-but-textured models (a block whose geometry comes from a
     * default cube rather than an explicit {@code elements} array).
     *
     * @param elements the model's elements
     * @param textures the model's resolved texture variable map
     * @param isItem whether this is an item model ({@code layerN} sprites) or a block model
     * @return whether the model renders nothing
     */
    public static boolean rendersNothing(
        @NotNull ConcurrentList<ModelElement> elements,
        @NotNull ConcurrentMap<String, String> textures,
        boolean isItem
    ) {
        for (ModelElement element : elements) {
            for (ModelFace face : element.getFaces().values()) {
                String ref = face.getTexture();
                if (ref.isBlank()) continue;
                if (!TextureEngine.resolveTextureReference(ref, textures).startsWith("#")) return false;
            }
        }

        for (Map.Entry<String, String> binding : textures.entrySet()) {
            String value = binding.getValue();
            if (value == null || value.startsWith("#")) continue;
            String key = binding.getKey();
            if (isItem ? key.startsWith("layer") : !key.equals("particle")) return false;
        }
        return true;
    }

}
