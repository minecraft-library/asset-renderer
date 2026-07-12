/**
 * Pipeline-built Minecraft domain data - the classes and records the
 * {@link lib.minecraft.renderer.pipeline pipeline} decodes from resource packs and vanilla
 * registries into the {@link lib.minecraft.renderer.engine.RendererContext RendererContext}
 * ({@link lib.minecraft.renderer.asset.Block Block}, {@link lib.minecraft.renderer.asset.Item Item},
 * {@link lib.minecraft.renderer.asset.Entity Entity},
 * {@link lib.minecraft.renderer.asset.BannerPattern BannerPattern},
 * {@link lib.minecraft.renderer.asset.ColorMap ColorMap},
 * {@link lib.minecraft.renderer.asset.AnimationData AnimationData},
 * {@link lib.minecraft.renderer.asset.BlockTag BlockTag},
 * {@link lib.minecraft.renderer.asset.ResourceId ResourceId}).
 *
 * <p><b>Charter.</b> Only Pipeline-built data lives here. The package splits two ways:
 * <ul>
 *   <li><b>{@code asset}</b> (this level) - the top-level classes / records the pipeline builds.</li>
 *   <li>{@link lib.minecraft.renderer.asset.model model} - the shapes shared by two or more
 *       {@code asset} classes (the model / element / face / transform types {@code Block} and
 *       {@code Item} both compose).</li>
 * </ul>
 *
 * <p>The OptiFine CIT / CTM rule types moved to
 * {@link lib.minecraft.renderer.pipeline.pack.rule pipeline.pack.rule} in the resource-pack rebuild.
 *
 * <p><b>Restriction.</b> Caller-supplied render-input value types - a dye palette, a rotation, an
 * appearance selection - are NOT Pipeline-built and do not belong here even when they are
 * domain-shaped. Those live with the option layer
 * ({@link lib.minecraft.renderer.option option} / {@code option.spec}, e.g.
 * {@link lib.minecraft.renderer.option.spec.DyeColor DyeColor}) or, when a value type is a pure math
 * primitive, in {@link lib.minecraft.renderer.tensor tensor}.
 */
package lib.minecraft.renderer.asset;
