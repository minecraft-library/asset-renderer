/**
 * ClientAcquisition-built Minecraft domain data - the classes and records the
 * {@link lib.minecraft.renderer.pipeline pipeline} decodes from resource packs and vanilla
 * registries into the {@link lib.minecraft.renderer.engine.RendererContext RendererContext}
 * ({@link lib.minecraft.renderer.asset.Block Block}, {@link lib.minecraft.renderer.asset.Item Item},
 * {@link lib.minecraft.renderer.asset.Entity Entity},
 * {@link lib.minecraft.renderer.asset.BannerPattern BannerPattern},
 * {@link lib.minecraft.renderer.asset.ColorMap ColorMap},
 * {@link lib.minecraft.renderer.asset.AnimationMetadata AnimationMetadata},
 * {@link lib.minecraft.renderer.asset.BlockTag BlockTag},
 * {@link lib.minecraft.renderer.asset.ResourceId ResourceId}), plus the pack-model spine the context
 * holds and resolves through ({@link lib.minecraft.renderer.asset.PackStack PackStack}) and the
 * fixed vanilla tables a render selection is drawn from
 * ({@link lib.minecraft.renderer.asset.DyeColor DyeColor},
 * {@link lib.minecraft.renderer.asset.BannerLayer BannerLayer}).
 *
 * <p><b>Charter.</b> Pipeline-built, render-consumed data lives here - both the decoded domain
 * definitions and the pack-model layer the renderer resolves through. The package splits:
 * <ul>
 *   <li><b>{@code asset}</b> (this level) - the top-level classes / records a renderer or the
 *       {@code RendererContext} holds directly ({@code Block}, {@code Item}, {@code Entity}, ...,
 *       {@code PackStack}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.appearance appearance} - the entity appearance axes an
 *       {@code AppearanceOptions} selection is drawn from and an {@code entity_models.json} row is
 *       gated on ({@code Age}, {@code Size}, {@code TintAxis}, {@code Villager}, {@code AppearanceGate},
 *       ...).</li>
 *   <li>{@link lib.minecraft.renderer.asset.equipment equipment} - the {@code equipment/*.json} model
 *       and the worn-armor vocabulary it composites ({@code ArmorSlot}, {@code ArmorMaterial},
 *       {@code ArmorTrim}, {@code ArmorPiece}, {@code Shell}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.model model} - the shapes shared by two or more
 *       {@code asset} classes (the model / element / face / transform types {@code Block} and
 *       {@code Item} both compose).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack pack} - the pack-model components {@code PackStack}
 *       caches ({@code ResourcePack}, {@code MCMeta}, {@code PackContainer}, {@code ResolvedTexture},
 *       {@code PackId}, {@code PackRoot}, {@code PackCapability}, {@code FormatRange}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.cats pack.cats} - the Catharsis {@code pack.cats}
 *       container decoder ({@code CatsIndex} / {@code CatsEntry}) behind {@code PackContainer.Cats}.</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.item pack.item} - the {@code items/*.json} dispatch
 *       trees ({@code ItemModelTree} / {@code ItemModelNode}).</li>
 *   <li>{@link lib.minecraft.renderer.asset.pack.rule pack.rule} - the OptiFine CIT / CTM rule family,
 *       NBT conditionals, and {@code color.properties} the renderer consults.</li>
 * </ul>
 *
 * <p>The OptiFine CIT / CTM rule types live in
 * {@link lib.minecraft.renderer.asset.pack.rule asset.pack.rule} - a sub-package of {@code asset},
 * because they are pack-parsed data the renderer consumes; the pipeline-side parsers that build them
 * stay in {@link lib.minecraft.renderer.pipeline.pack.rule pipeline.pack.rule}. The one piece of
 * that grammar quartered here is the {@code color.properties} key prefix on
 * {@link lib.minecraft.renderer.asset.Block.TintTarget Block.TintTarget}, which sits alongside the
 * colormap and fallback colour that target resolves through: splitting the four prefixes onto
 * {@code ColorProperties} would put one target's answers in two files, which is the arrangement the
 * table exists to end.
 *
 * <p><b>Restriction.</b> A caller's request is not asset data. The {@code *Options} bags a renderer
 * takes live in {@link lib.minecraft.renderer.option option}, and a pure math primitive lives in
 * {@link lib.minecraft.renderer.tensor tensor}. What a bag <i>names</i> is the other side of that
 * line: a dye palette, an armor slot, an appearance axis is vanilla vocabulary whichever side
 * supplies it, and the pipeline reads it too, so it is held here and the option layer points at it
 * rather than the reverse.
 *
 * <p><b>Parity.</b> These are the records the pipeline builds and the renderers consume, and the
 * dump's sections are a projection of exactly those records - so a change here is visible on both
 * sides at once. That makes this the one package family with no blindness to claim. The vocabulary
 * an options bag names declares the option surface's claim as well, because it reaches what that
 * bag reaches and the union of two select claims is what answers for it.
 */
@Parity(claim = "asset-layer")
package lib.minecraft.renderer.asset;

import lib.minecraft.renderer.parity.Parity;

