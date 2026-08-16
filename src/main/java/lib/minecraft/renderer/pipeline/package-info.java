/**
 * Asset acquisition and parsing pipeline - turns a Minecraft version plus a stack of resource
 * pack directories into the populated DTOs the renderer reads through a
 * {@link lib.minecraft.renderer.engine.RendererContext RendererContext}.
 *
 * <p><b>Orchestrator.</b> {@link lib.minecraft.renderer.client.ClientAcquisition ClientAcquisition} is the lone
 * entry point: one {@link lib.minecraft.renderer.client.ClientOptions ClientOptions} call
 * downloads the version's client jar (via the {@code MojangContract} Feign proxy with shared
 * domain-aware rate limiting), extracts the {@code assets/} + {@code data/} subtrees, walks the
 * active pack stack with each domain loader (models, blockstates, tags, textures, colormaps,
 * banner patterns, potion colours), reads the OptiFine rules ({@code cit} / {@code ctm} /
 * {@code colormap} / {@code color.properties}), and returns a {@code ClientAcquisition.Result} of populated
 * maps plus pack-root paths.
 *
 * <p><b>Production context.</b>
 * {@link lib.minecraft.renderer.pipeline.PipelineRendererContext PipelineRendererContext} wraps a
 * {@code ClientAcquisition.Result} into the production {@code RendererContext}, backing every {@code findX}
 * / {@code resolveX} with an eager index plus on-demand CIT / CTM matchers. Test and tooling stubs
 * implement {@code RendererContext} directly.
 *
 * <p><b>Sub-packages.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.pipeline.pack pack} - the resource-pack model: the
 *       {@code PackStack} / {@code ResourcePack} / {@code PackContainer} types, the {@code MCMeta} /
 *       {@code FormatRange} parsers, the {@code PackId} heuristic, the {@code PackAcquisition}
 *       materialization + {@code TextureIndexer} resolution seam, and {@code ResolvedModels}, which
 *       folds a model's {@code parent} chain across the stack.</li>
 *   <li>{@link lib.minecraft.renderer.pipeline.loader loader} - one loader per asset family
 *       (JSON / NBT / PNG), each with its own pack-stack merge precedence.</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.rule rule} - immutable parsed-rule records and
 *       matchers for OptiFine features (CIT / CTM / colormap) and the value types they consume.</li>
 * </ul>
 *
 * <p><b>Gson.</b> {@link lib.minecraft.renderer.pipeline.util.PipelineGsonContributor
 * PipelineGsonContributor} registers the tensor {@code Vector2f/3f/4f} adapters through the
 * {@code GsonContributor} {@link java.util.ServiceLoader ServiceLoader} SPI, so any downstream
 * {@code GsonSettings.defaults()} build deserializes asset JSON automatically.
 *
 * <p><b>Parity.</b> The dump is a serialisation of the loaded pipeline state, so a read layer that
 * resolves a different value moves a dumped byte. This is the wide claim beside the narrower ones
 * the packages below carry, and what it answers for alone are the utilities and the context class.
 * It reaches no render at all: nothing here draws, so no sweep and no render pin is on its list.
 *
 * @see lib.minecraft.renderer.client.ClientAcquisition
 * @see lib.minecraft.renderer.pipeline.PipelineRendererContext
 * @see lib.minecraft.renderer.engine.RendererContext
 */
@Parity(claim = "pipeline-reads")
package lib.minecraft.renderer.pipeline;

import lib.minecraft.renderer.parity.Parity;

