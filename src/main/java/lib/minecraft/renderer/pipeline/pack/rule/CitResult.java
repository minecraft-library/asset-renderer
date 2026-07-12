package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The effect of the highest-precedence matching CIT rule on one item render (03-rules §2) - the
 * widened return of the {@code resolveItemTextureOverride} seam (from HEAD's {@code Optional<String>}).
 *
 * <p>A match swaps the {@code layer0} {@link #texture} before decode, swaps named {@link #subTextures}
 * per {@code texture.<name>}, and re-enters model resolution with {@link #model} (doc 05 owns the join).
 * The {@link #glint} decision rides through to the compose terminal (03-rules §7). {@link #NONE} is the
 * no-match result: every override empty and glint left at {@link GlintPolicy#DEFAULT}.
 *
 * @param texture the {@code layer0} replacement, absent when no rule matched or the match overrode only a model
 * @param subTextures the {@code texture.<name>} replacements keyed by sub-texture name
 * @param model the model override, absent when no rule matched or the match overrode only textures
 * @param glint the glint decision (a {@link GlintPolicy#DEFAULT} placeholder until sub-commit 3c)
 */
public record CitResult(
    @NotNull Optional<ResourceId> texture,
    @NotNull ConcurrentMap<String, ResourceId> subTextures,
    @NotNull Optional<ResourceId> model,
    @NotNull GlintPolicy glint
) {

    /** The no-match result - no override, vanilla glint. */
    public static final @NotNull CitResult NONE = new CitResult(
        Optional.empty(), Concurrent.newMap(), Optional.empty(), GlintPolicy.DEFAULT);

    /**
     * Builds a result from a matched rule's declared output with the given glint decision - the
     * {@link GlintEvaluator} decides the policy once per render and the item walk grafts it onto the
     * winning output (03-rules §7).
     *
     * @param output the matched rule's output
     * @param glint the render's glint decision
     * @return the render-time result
     */
    public static @NotNull CitResult of(@NotNull CitOutput output, @NotNull GlintPolicy glint) {
        return new CitResult(output.texture(), output.subTextures(), output.model(), glint);
    }

    /**
     * Returns a copy carrying the given glint decision - used when the render matched no item rule but
     * a {@code type=enchantment} rule or {@code useGlint=false} still changed the glint (03-rules §7).
     *
     * @param glint the glint decision to carry
     * @return the same overrides with the glint decision replaced
     */
    public @NotNull CitResult withGlint(@NotNull GlintPolicy glint) {
        return new CitResult(this.texture, this.subTextures, this.model, glint);
    }

    /**
     * The override texture for one item layer, if this result carries one - {@code layer0} maps to the
     * {@link #texture} replacement, any other {@code layerN} to its {@code texture.<name>} entry in
     * {@link #subTextures}. A pure lookup, so a caller resolves the whole layer stack against one
     * already-computed result rather than re-walking the rule list per layer. {@link #NONE} returns
     * empty for every layer, leaving the model's own bindings in force.
     *
     * @param layerKey the layer key, e.g. {@code layer0} / {@code layer1}
     * @return the override texture id, or empty when this result leaves the layer to the model
     */
    public @NotNull Optional<ResourceId> textureFor(@NotNull String layerKey) {
        return layerKey.equals("layer0") ? this.texture : this.subTextures.getOptional(layerKey);
    }

}
