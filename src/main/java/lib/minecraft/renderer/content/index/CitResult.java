package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.rule.CitOutput;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * The effect of the highest-precedence matching CIT rule on one item render - the return of the
 * {@code resolveItemTextureOverride} seam.
 *
 * <p>A match swaps the {@code layer0} {@link #texture} before decode, swaps named {@link #subTextures}
 * per {@code texture.<name>}, and re-enters model resolution with {@link #model}. The {@link #glint}
 * decision rides through to the compose terminal. {@link #NONE} is the no-match result: both overrides
 * absent and glint left at {@link GlintPolicy#DEFAULT}.
 *
 * <p>Whether a rule matched is read off the value rather than off which instance it is: a result no
 * rule produced - {@link #NONE}, or a copy of it carrying another glint decision - has both overrides
 * absent, and a matched rule's result has neither absent, since an override the rule does not declare
 * is empty.
 *
 * @param texture the {@code layer0} replacement; empty when the matched rule overrode only a model or
 *     sub-textures, and absent when no rule matched
 * @param subTextures the {@code texture.<name>} replacements keyed by sub-texture name
 * @param model the model override; empty when the matched rule overrode only textures, and absent when
 *     no rule matched
 * @param glint the glint decision
 */
@Parity(claim = "asset-layer")
@Parity(claim = "pack-rule-layer")
public record CitResult(
    @NotNull Possible<ResourceId> texture,
    @NotNull ConcurrentMap<String, ResourceId> subTextures,
    @NotNull Possible<ResourceId> model,
    @NotNull GlintPolicy glint
) {

    /**
     * The no-match result - no override, vanilla glint.
     */
    public static final @NotNull CitResult NONE = new CitResult(
        Possible.absent(), Concurrent.newMap(), Possible.absent(), GlintPolicy.DEFAULT);

    /**
     * Builds a result from a matched rule's declared output with the given glint decision - the context's
     * item-texture override ({@link RendererContext#resolveItemTextureOverride}) decides the policy once
     * per render and grafts it onto the winning output. An override the output does not declare is
     * empty, since a rule did match.
     *
     * @param output the matched rule's output
     * @param glint the render's glint decision
     * @return the render-time result
     */
    public static @NotNull CitResult of(@NotNull CitOutput output, @NotNull GlintPolicy glint) {
        return new CitResult(Possible.ofOptional(output.texture()), output.subTextures(), Possible.ofOptional(output.model()), glint);
    }

    /**
     * Returns a copy carrying the given glint decision - used when the render matched no item rule but
     * a {@code type=enchantment} rule or {@code useGlint=false} still changed the glint.
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
     * already-computed result rather than re-walking the rule list per layer. A result no rule produced
     * answers absent for every layer, leaving the model's own bindings in force.
     *
     * @param layerKey the layer key, e.g. {@code layer0} / {@code layer1}
     * @return the override texture id; empty when the matched rule leaves the layer to the model, and
     *     absent when no rule matched
     */
    public @NotNull Possible<ResourceId> textureFor(@NotNull String layerKey) {
        if (layerKey.equals("layer0")) return this.texture;
        if (this.texture.isAbsent()) return Possible.absent();
        return this.subTextures.containsKey(layerKey) ? Possible.of(this.subTextures.get(layerKey)) : Possible.empty();
    }

}
