/**
 * ClientAcquisition-built Minecraft domain data - the classes and records the
 * {@link lib.minecraft.renderer.pipeline pipeline} decodes from resource packs and vanilla
 * registries into the {@link lib.minecraft.renderer.engine.RendererContext RendererContext}
 * ({@link lib.minecraft.renderer.asset.Block Block}, {@link lib.minecraft.renderer.asset.Item Item},
 * {@link lib.minecraft.renderer.asset.Entity Entity},
 * {@link lib.minecraft.renderer.asset.BannerPattern BannerPattern},
 * {@link lib.minecraft.renderer.asset.ColorMap ColorMap},
 * {@link lib.minecraft.renderer.asset.AnimationData AnimationData},
 * {@link lib.minecraft.renderer.asset.BlockTag BlockTag},
 * {@link lib.minecraft.renderer.asset.ResourceId ResourceId}), plus the pack-model spine the context
 * holds and resolves against ({@link lib.minecraft.renderer.asset.PackStack PackStack},
 * {@link lib.minecraft.renderer.asset.MCMeta MCMeta}).
 *
 * <p><b>Charter.</b> Pipeline-built, render-consumed data lives here - both the decoded domain
 * definitions and the pack-model layer the renderer resolves through. The package splits:
 * <ul>
 *   <li><b>{@code asset}</b> (this level) - the top-level classes / records a renderer or the
 *       {@code RendererContext} holds directly ({@code Block}, {@code Item}, {@code Entity}, ...,
 *       {@code PackStack}, {@code MCMeta}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.model model} - the shapes shared by two or more
 *       {@code asset} classes (the model / element / face / transform types {@code Block} and
 *       {@code Item} both compose).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack pack} - the pack-model components {@code PackStack}
 *       caches ({@code ResourcePack}, {@code PackContainer}, {@code ResolvedTexture}, {@code PackId},
 *       {@code PackRoot}, {@code Capability}, {@code FormatRange}, {@code CatsIndex}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.item pack.item} - the {@code items/*.json} dispatch
 *       trees ({@code ItemModelTree} / {@code ItemModelNode}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.rule pack.rule} - the OptiFine CIT / CTM rule family,
 *       NBT conditionals, and {@code color.properties} the renderer consults.</li>
 * </ul>
 *
 * <p>The OptiFine CIT / CTM rule types live in
 * {@link lib.minecraft.renderer.asset.pack.rule asset.pack.rule} - a sub-package of {@code asset},
 * because they are pack-parsed data the renderer consumes; the pipeline-side parsers that build them
 * stay in {@link lib.minecraft.renderer.pipeline.pack.rule pipeline.pack.rule}.
 *
 * <p><b>Restriction.</b> Caller-supplied render-input value types - a dye palette, a rotation, an
 * appearance selection - are NOT pipeline-built and do not belong here even when they are
 * domain-shaped. Those live with the option layer
 * ({@link lib.minecraft.renderer.option option} / {@code option.spec}, e.g.
 * {@link lib.minecraft.renderer.option.spec.DyeColor DyeColor}) or, when a value type is a pure math
 * primitive, in {@link lib.minecraft.renderer.tensor tensor}.
 */
package lib.minecraft.renderer.asset;
