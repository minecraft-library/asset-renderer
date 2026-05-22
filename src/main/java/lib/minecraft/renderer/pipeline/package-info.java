/**
 * Asset acquisition and parsing pipeline. Turns a Minecraft version number and a stack of
 * resource pack directories into the populated DTOs the renderer layer reads via a
 * {@link RendererContext RendererContext}.
 *
 * <p><b>Orchestrator.</b> {@link Pipeline Pipeline} is the
 * single entry point. One {@link PipelineOptions
 * PipelineOptions} -&gt; {@code Pipeline.Result} call:
 * <ol>
 *   <li>Downloads the targeted version's client jar via the
 *       {@code api.simplified.mojang.MojangContract} Feign proxy ({@link
 *       lib.minecraft.renderer.pipeline.Pipeline#mojang() Pipeline.mojang()}), with
 *       domain-aware rate limiting shared across every concurrent caller in the JVM.</li>
 *   <li>Extracts the {@code minecraft/} subtrees ({@link
 *       lib.minecraft.renderer.pipeline.VanillaPaths#VANILLA_ASSET_ROOT assets} and
 *       {@link VanillaPaths#VANILLA_DATA_ROOT data}).</li>
 *   <li>Walks the active pack stack with each domain-specific loader (block models, item
 *       models, blockstates, block tags, textures, color maps, banner patterns, potion
 *       colors).</li>
 *   <li>Reads OptiFine-flavoured pack rules ({@code optifine/cit}, {@code optifine/ctm},
 *       {@code optifine/colormap}, {@code optifine/color.properties}) and produces the
 *       descending-priority resolver list every renderer queries through
 *       {@link PipelineRendererContext PipelineRendererContext}.</li>
 *   <li>Returns a {@code Pipeline.Result} containing the populated maps plus the pack-root
 *       paths the texture loader uses for on-demand sampling.</li>
 * </ol>
 *
 * <p><b>Production context.</b> {@link
 * lib.minecraft.renderer.pipeline.PipelineRendererContext PipelineRendererContext} is the
 * production {@code RendererContext} - it wires every {@code findX} / {@code resolveX} method
 * onto the {@code Pipeline.Result} maps plus on-demand
 * {@link CitMatcher CIT} /
 * {@link CtmMatcher CTM} matchers. Test contexts and
 * tooling stubs implement {@code RendererContext} directly without going through this class.
 *
 * <p><b>Sub-packages.</b>
 * <ul>
 *   <li>{@link lib.minecraft.renderer.pipeline.loader loader} - one loader per JSON / NBT /
 *       PNG asset family. Each one walks an ascending or descending pack stack with explicit
 *       merge semantics; see each loader's javadoc for the precedence rules.</li>
 *   <li>{@link lib.minecraft.renderer.pipeline.pack pack} - immutable parsed-rule records and
 *       matchers for OptiFine-flavoured pack features: {@code CitRule}, {@code CitMatcher},
 *       {@code CtmRule}, {@code CtmMatcher}, {@code ColorProperties}, {@code IntRange},
 *       {@code NbtCondition}, {@code NeighborPattern}, {@code PackMeta}, plus the
 *       {@code FormatSpec} and {@code ItemContext} value types they consume.</li>
 *   <li>{@link lib.minecraft.renderer.pipeline.resolver resolver} - resolver utilities that
 *       walk multiple loaders' output to produce a single answer: model parent inheritance
 *       ({@code ModelResolver}), block-entity overlay materialization
 *       ({@code OverlayResolver}), and the pack-stack precedence walker
 *       ({@code PackResolver}).</li>
 *   <li>{@link lib.minecraft.renderer.pipeline.util util} - SPI implementations and shared
 *       cross-cutting utilities ({@link PackAcquirer
 *       PackAcquirer}, {@link PackDownloader
 *       PackDownloader}, {@link RendererDebug
 *       RendererDebug} per-pixel diagnostic dump).</li>
 * </ul>
 *
 * <p><b>Gson integration.</b> {@link
 * lib.minecraft.renderer.pipeline.PipelineGsonContributor PipelineGsonContributor} registers
 * the {@link Vector2f Vector2f} /
 * {@link Vector3f Vector3f} /
 * {@link Vector4f Vector4f} type adapters with
 * {@code GsonSettings.defaults()} via the {@code GsonContributor}
 * {@link ServiceLoader ServiceLoader} SPI, so any downstream module that builds a
 * {@code Gson} through {@code GsonSettings.defaults().create()} can deserialize asset JSON
 * automatically.
 *
 * <p><b>Path constants.</b> Every loader pulls its vanilla-jar prefixes from {@link
 * lib.minecraft.renderer.pipeline.VanillaPaths VanillaPaths} so a future Mojang rename can
 * be made in one file.
 *
 * @see lib.minecraft.renderer.pipeline.Pipeline
 * @see lib.minecraft.renderer.pipeline.PipelineRendererContext
 * @see lib.minecraft.renderer.engine.RendererContext
 */
package lib.minecraft.renderer.pipeline;

import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.VanillaPaths;
import lib.minecraft.renderer.pipeline.pack.CitMatcher;
import lib.minecraft.renderer.pipeline.pack.CtmMatcher;
import lib.minecraft.renderer.pipeline.util.PackAcquirer;
import lib.minecraft.renderer.pipeline.util.PackDownloader;
import lib.minecraft.renderer.pipeline.util.RendererDebug;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tensor.Vector4f;

import java.util.ServiceLoader;
