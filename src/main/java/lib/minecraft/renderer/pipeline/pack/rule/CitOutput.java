package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The output side of a {@link CitRule} - the {@code texture} / {@code texture.<name>} /
 * {@code model} / {@code model.<name>} replacements a match applies (03-rules §3.1). All four are
 * resolved to pack-relative {@link ResourceId}s at parse time so the matcher never re-derives paths
 * (07-optifine defect #7). The sub-model map is parse-and-hold - model overrides join model
 * resolution (doc 05) later.
 *
 * @param texture the {@code layer0} replacement, absent when the rule overrides only a model
 * @param subTextures the {@code texture.<name>} replacements keyed by sub-texture name
 * @param model the whole-model override, absent when the rule overrides only textures
 * @param subModels the {@code model.<name>} overrides keyed by sub-model name, held for doc 05
 */
public record CitOutput(
    @NotNull Optional<ResourceId> texture,
    @NotNull ConcurrentMap<String, ResourceId> subTextures,
    @NotNull Optional<ResourceId> model,
    @NotNull ConcurrentMap<String, ResourceId> subModels
) {

    /** An output carrying no replacements. */
    public static final @NotNull CitOutput EMPTY = new CitOutput(
        Optional.empty(), Concurrent.newMap(), Optional.empty(), Concurrent.newMap());

    /**
     * Whether this output carries any texture or model replacement - a rule with none is inert and
     * the parser rejects it.
     *
     * @return {@code true} when at least one replacement is present
     */
    public boolean isEmpty() {
        return this.texture.isEmpty() && this.subTextures.isEmpty() && this.model.isEmpty() && this.subModels.isEmpty();
    }

}
