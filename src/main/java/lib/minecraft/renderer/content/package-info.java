/**
 * Turning bytes into the records behind the port - a Minecraft version plus a stack of resource pack
 * directories in, the populated records a renderer reads through a
 * {@link lib.minecraft.renderer.port.RendererContext RendererContext} out.
 *
 * <p><b>Entry points.</b> Two calls take a caller from nothing to a context.
 * {@link lib.minecraft.renderer.content.client.ClientAcquisition#acquire ClientAcquisition.acquire}
 * turns one {@link lib.minecraft.renderer.content.client.ClientOptions ClientOptions} into the
 * extracted {@link lib.minecraft.renderer.content.client.ClientAssets ClientAssets}: it downloads the
 * version's client jar through the {@code MojangContract} client with shared domain-aware rate
 * limiting and extracts the {@code assets/} + {@code data/} subtrees.
 * {@link lib.minecraft.renderer.content.index.AssetContent#load AssetContent.load} turns those into the
 * production context: it compiles the pack stack with its OptiFine rules ({@code cit} / {@code ctm} /
 * {@code color.properties}) merged in, runs each domain loader (models, blockstates, tags, colormaps,
 * banner patterns, item trees, equipment, paletted permutations) and each shipped-table reader, and
 * joins the results into the eager indexes
 * {@link lib.minecraft.renderer.content.index.IndexedRendererContext IndexedRendererContext} wraps -
 * every {@code findX} a map access, every rule-walking {@code resolveX} a walk of the stack's merged
 * CIT / CTM rules, and texture pixels left on disk until first asked for. Test and tooling stubs
 * implement {@code RendererContext} directly.
 *
 * <p><b>Sub-packages.</b> The package declares no type of its own; the work is cut by what each step
 * reads.
 * <ul>
 *   <li>{@link lib.minecraft.renderer.content.client client} - acquiring and extracting a vanilla
 *       client, and the one place that reaches the network.</li>
 *   <li>{@link lib.minecraft.renderer.content.read read} - reading a named path out of a byte source,
 *       a bundled table or a pack stack's files, without deciding what the bytes mean.</li>
 *   <li>{@link lib.minecraft.renderer.content.table table} - reading the tables the generator shipped
 *       into this JAR.</li>
 *   <li>{@link lib.minecraft.renderer.content.pack pack} - resolving the caller's pack stack into one
 *       readable, cached whole, and the per-domain loaders that read it;
 *       {@link lib.minecraft.renderer.content.pack.cats pack.cats} is the Catharsis pack format, its
 *       config grammar and its index.</li>
 *   <li>{@link lib.minecraft.renderer.content.rule rule} - parsing a pack's OptiFine / MCPatcher rule
 *       files into the {@link lib.minecraft.renderer.asset.rule asset.rule} grammar.</li>
 *   <li>{@link lib.minecraft.renderer.content.json json} - every type the module's Gson instance is
 *       built from, installed through the {@code GsonContributor}
 *       {@link java.util.ServiceLoader ServiceLoader} SPI so any downstream
 *       {@code GsonSettings.defaults()} build deserializes asset JSON automatically.</li>
 *   <li>{@link lib.minecraft.renderer.content.index index} - joining what was read into the runtime
 *       index behind the port.</li>
 * </ul>
 *
 * <p>A type that reads no bytes and joins no record does not belong under here.
 *
 * <p><b>Parity.</b> The dump is a serialisation of the loaded pipeline state, so a read layer that
 * resolves a different value moves a dumped byte. This is the wide claim beside the narrower ones the
 * packages and types below carry, and what it answers for alone is {@code content.read} and whatever
 * those leave of {@code content.json}, {@code content.rule} and {@code content.client}. It reaches no
 * render at all: nothing here draws, so no sweep and no render pin is on its list.
 *
 * @see lib.minecraft.renderer.content.client.ClientAcquisition
 * @see lib.minecraft.renderer.content.index.AssetContent
 * @see lib.minecraft.renderer.content.index.IndexedRendererContext
 * @see lib.minecraft.renderer.port.RendererContext
 */
@Parity(claim = "pipeline-reads")
package lib.minecraft.renderer.content;

import lib.minecraft.renderer.parity.Parity;

