package lib.minecraft.renderer.tooling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.engine.kit.EntityGeometryKit;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lib.minecraft.renderer.tooling.entity.*;
import lib.minecraft.renderer.tooling.parser.GeometryParser;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point invoked by the {@code entityModels} Gradle task. Produces the entity-side
 * counterpart of {@link ToolingBlockModels} - a single ASM walk over the deobfuscated
 * client jar that emits two paired JSON resources the runtime pipeline reads:
 *
 * <ul>
 *   <li>{@code src/main/resources/lib/minecraft/renderer/entity_models.json} - per-entity
 *       metadata: geometry reference, texture reference, optional {@code variant_of}
 *       back-link, overlays list, hidden bones, renderer-scale and yaw-addend overrides,
 *       and per-entity tints.</li>
 *   <li>{@code src/main/resources/lib/minecraft/renderer/entity_geometry.json} - deduplicated
 *       bone / cube trees keyed by the unique {@code createBodyLayer}-equivalent factory
 *       method. Entities that share a factory point at the same geometry entry.</li>
 * </ul>
 *
 * <p><b>Pipeline stages.</b> Each stage narrows to one resolver in
 * {@link lib.minecraft.renderer.tooling.entity}:
 * <ol>
 *   <li><b>Mob discovery</b>
 *       ({@link EntityRegistryDiscovery}). Maps every living mob
 *       to its registered renderer class.</li>
 *   <li><b>Per-renderer binding</b>
 *       ({@link EntitySessionWalk} orchestrates per entity, fanning out to
 *       {@link EntityTextureResolver}, {@link EntityVariantResolver},
 *       {@link EntityBoneResolver}, {@link EntityRendererOverrides}). Texture references
 *       (hardcoded / conditional / variant-driven), data-driven variant tables from
 *       {@code data/minecraft/X_variant/}, the overlay enumeration from
 *       {@code addLayer(...)} call sites, and renderer-override extraction
 *       (setupRotations yaw addend + scale residue).</li>
 *   <li><b>Geometry parse</b>
 *       ({@link EntityLayerDefinitionResolver}). Walks the {@code LayerDefinition}-returning
 *       factory; procedural-loop entity bodies (squid / blaze / ghast / silverfish / endermite
 *       / slime families) are folded by the shared
 *       {@link GeometryParser} at parse time.</li>
 *   <li><b>Overlay resolution + emission</b>
 *       ({@link EntityOverlayResolver}, {@link EntityBlockOverlayResolver},
 *       {@link EntityRuntimeJsonWriter}). Resolves overlay rows and emits the runtime JSON
 *       {@link EntityRenderer} consumes (cross-entity family clustering folded
 *       into {@code EntityRuntimeJsonWriter}).</li>
 * </ol>
 *
 * <p><b>Diagnostics.</b> Two dev-only JSON dumps land under
 * {@code cache/asset-renderer/diagnostics/} (gitignored) alongside the production output;
 * {@link EntityDiagnosticsWriter} writes them and they capture intermediate state the
 * production output collapses away. Mannequin is the one mob that falls outside the
 * standard EntityRenderer flow (rendered through the avatar pipeline) and shows up only in
 * the diagnostics' {@code mobs_without_renderer_list}.
 *
 * @see ToolingBlockModels
 * @see EntityRenderer
 * @see EntityGeometryKit
 */
@UtilityClass
public final class ToolingEntityModels {

    /**
     * Runs the full entity-models tooling pipeline and writes the diagnostic + runtime JSON.
     *
     * @param args ignored - all paths are fixed
     * @throws IOException if the client jar cannot be read or any output file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        PipelineOptions options = PipelineOptions.defaults();

        System.out.println("Downloading Minecraft client jar...");
        Path clientJar = Pipeline.downloadJarToCache(options);

        try (EntityToolingContext context = EntityToolingContext.of(clientJar, options)) {
            Diagnostics diagnostics = context.diagnostics();
            EntityRegistryDiscovery.Result registry = EntityRegistryDiscovery.discover(context);
            System.out.println("Discovered " + registry.totalMobsDiscovered() + " living-mob entity types");
            System.out.println("Discovered " + registry.entries().size() + " entity-renderer registrations");
            // Cross-check the hardcoded NON_BASE_STEM_SUFFIXES set against a derivation from
            // the live entity-texture universe and log drift via diagnostics. Observation
            // only - the active filter remains the hardcoded set so a vanilla version bump
            // that adds a new state-overlay suffix surfaces here instead of as a silent
            // fallback-binding regression.
            EntityTextureResolver.auditNonBaseSuffixes(context);
            // Field-keyed view of the registry so the geometry-stage variant-enumeration loop can
            // pull each entity's lambdaLayerFields / lambdaTypeArgs from a single map lookup
            // rather than re-walking the registry list.
            Map<String, EntityRegistryDiscovery.Result.Entry> registryByField = new LinkedHashMap<>();
            for (EntityRegistryDiscovery.Result.Entry entry : registry.entries())
                registryByField.put(entry.entityFieldName(), entry);

            ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Result>> variants =
                EntityVariantResolver.loadAll(context, diagnostics);
            System.out.println("Loaded variant tables for " + variants.size() + " entity types ("
                + variants.keySet() + ")");

            ConcurrentMap<String, String> dataVariantDefaults =
                EntityVariantResolver.loadDataDrivenDefaults(context, diagnostics);
            System.out.println("Canonical data-variant defaults: " + dataVariantDefaults);

            // Per-mob binding fan-out: EntitySessionWalk runs the texture / variant /
            // bone-layer / renderer-overrides resolvers per entity and returns one Result;
            // this loop just records each Result and accumulates the texture-coverage
            // stats for the diagnostic line below.
            Map<String, EntitySessionWalk.Result> records = new LinkedHashMap<>();
            int withPrimaryTexture = 0;
            int variantDriven = 0;
            int unresolvedTexture = 0;
            for (EntityRegistryDiscovery.Result.Entry mob : registry.entries()) {
                EntitySessionWalk.Result walkResult = new EntitySessionWalk(context, mob, variants).walk();
                records.put(walkResult.entityId(), walkResult);

                EntityTextureResolver.Result binding = walkResult.binding();
                if (binding.primaryTexturePath() != null) withPrimaryTexture++;
                else if (binding.isVariantDriven()) variantDriven++;
                else unresolvedTexture++;
            }

            Path discoveryDiagOut = EntityDiagnosticsWriter.writeDiscoveryDiagnostic(
                options, registry, records, variants,
                withPrimaryTexture, variantDriven, unresolvedTexture, diagnostics
            );
            System.out.println("Wrote " + discoveryDiagOut);

            // Per-mob geometry extraction. Resolve each renderer's primary
            // ModelLayers.X via LayerDefinitions.createRoots, build synthetic Sources, and
            // delegate to the shared GeometryParser - the bytecode patterns
            // (LayerDefinition.create / CubeListBuilder / PartPose / addOrReplaceChild) are
            // identical between block-entity and entity models.
            ConcurrentMap<String, EntityLayerDefinitionResolver.Result> layerDefs =
                EntityLayerDefinitionResolver.loadLayerDefinitions(context.classNodes(), diagnostics);
            System.out.println("Loaded " + layerDefs.size() + " ModelLayers entries from LayerDefinitions.createRoots");

            ConcurrentMap<String, EntityLayerDefinitionResolver.Result> entityToResolution = Concurrent.newMap();
            List<Source> sources = new ArrayList<>();
            for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
                EntityRegistryDiscovery.Result.Entry registryEntry = registryByField.get(entry.getValue().entityFieldName());
                ConcurrentList<String> lambdaFields = registryEntry != null
                    ? registryEntry.lambdaLayerFields()
                    : Concurrent.newList();
                // Augment with model-layer fields walked from the renderer's Type enum
                // (donkey / horse family): the lambda exposes only the saddle / equipment
                // layer, but the body layer lives behind Type.X.model. Adding the per-
                // constant ModelLayers reference lets the picker's entity-id-match heuristic
                // promote ModelLayers.DONKEY over the lambda's DONKEY_SADDLE for
                // minecraft:donkey.
                ConcurrentList<EntityRegistryDiscovery.TypeFieldRef> typeArgsForLayer = registryEntry != null
                    ? registryEntry.lambdaTypeArgs()
                    : Concurrent.newList();
                if (!typeArgsForLayer.isEmpty()) {
                    String typeOwner = typeArgsForLayer.getFirst().owner();
                    Map<String, String> typeToModelLayer = EntityTextureResolver.typeConstantModelLayerMap(context.classNodes(), typeOwner);
                    for (EntityRegistryDiscovery.TypeFieldRef ref : typeArgsForLayer) {
                        String layer = typeToModelLayer.get(ref.name());
                        if (layer != null && !lambdaFields.contains(layer)) lambdaFields.add(layer);
                    }
                }
                EntityLayerDefinitionResolver.Result resolution =
                    EntityLayerDefinitionResolver.resolvePrimary(
                        context.classNodes(), entry.getValue().rendererInternalName(), entry.getKey(),
                        lambdaFields, layerDefs, diagnostics
                    );
                if (resolution == null) continue;
                // AdultZombifiedPiglinModel.createBodyLayer is a no-op delegate that returns
                // AdultPiglinModel.createBodyLayer(). Unaliasing here collapses the delegating
                // factory onto its base so the factoryKey-&gt;geometryId dedupe maps both piglin
                // AND zombified_piglin to a single shared geometry entry.
                resolution = EntityLayerDefinitionResolver.unaliasDelegate(context.classNodes(), resolution);
                entityToResolution.put(entry.getKey(), resolution);
                // paramFloatValues opts the parser into arithmetic evaluation (FADD / FMUL /
                // type conversions) and substitutes 0.0f for the first 8 FLOAD slots. Java's
                // shared factory methods (HumanoidModel.createMesh, AbstractEquineModel
                // .createBodyMesh, etc.) take a {@code float yOffset} parameter that the
                // LayerDefinitions.createRoots call site always passes as 0.0f for the primary
                // body layer; without substitution + arithmetic, pivots like
                // {@code 2 + yOffset} resolve to 0 and arms / legs land at the wrong position.
                // Legacy block-entity sources don't set this, preserving the legacy
                // literal-stack-only walk for them.
                // Slot 0 is overridden from the resolution's captured factory-arg literal when
                // present - DonkeyModel.createBodyLayer(F) reads its base body scale from
                // fload_0, and LayerDefinitions.createRoots passes the call-site constant
                // (DONKEY: 0.87f, MULE: 0.92f) which the resolver picks up.
                float[] paramFloats = new float[8];
                if (resolution.defaultFloatParam() != null)
                    paramFloats[0] = resolution.defaultFloatParam();
                sources.add(new Source(
                    resolution.targetClass() + ".class",
                    resolution.targetMethod(),
                    entry.getKey(),
                    YAxis.DOWN,
                    0f,
                    resolution.texWidthOverride(),
                    resolution.texHeightOverride(),
                    null,
                    paramFloats,
                    0f,
                    resolution.appliedMeshTransformerScale(),
                    null
                ));
            }
            System.out.println("Resolved " + sources.size() + " primary LayerDefinition factories for geometry parsing");

            // Variant LayerDefinition enumeration. Some data-driven variants (cow_cold,
            // cow_warm, chicken_cold, pig_cold) declare a `model` discriminator
            // ({@code "model": "cold"}) in their JSON; the matching {@code ColdCowModel} /
            // {@code WarmCowModel} / {@code ColdChickenModel} / {@code ColdPigModel} classes
            // register a separate {@code LayerDefinition} under {@code ModelLayers.<MODEL>_<STEM>}
            // (e.g., {@code COLD_COW}). The base-entity-only walk above only resolves the
            // primary {@code ModelLayers.X} for the renderer, missing these variant layers.
            // Add an extra Source per variant whose model layer is present in {@code layerDefs}
            // so the parser emits a distinct geometry that the variant row's geometry_ref can
            // point at (cow_cold -> ColdCowModel mesh, not the base CowModel).
            for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
                String baseEntityId = entry.getKey();
                String variantStem = entry.getValue().variantStem();
                if (variantStem == null) continue;
                ConcurrentList<EntityVariantResolver.Result> vlist = variants.get(variantStem);
                if (vlist == null) continue;
                // Prefer the bytecode-derived ModelType -> ModelLayers pairing from the renderer's
                // model-map construction over the {@code <MODEL>_<STEM>} naming convention. Cow's
                // WARM -> WARM_COW matches the convention, but zombie_nautilus's WARM ->
                // ZOMBIE_NAUTILUS_CORAL does not, so the convention alone drops the coral geometry.
                Map<String, String> modelTypeLayers = EntityVariantResolver.modelTypeToModelLayerField(
                    context.classNodes(), entry.getValue().rendererInternalName());
                for (EntityVariantResolver.Result variant : vlist) {
                    if (variant.model() == null) continue;
                    String modelLayerField = modelTypeLayers.get(variant.model().toUpperCase(java.util.Locale.ROOT));
                    if (modelLayerField == null)
                        modelLayerField = (variant.model() + "_" + variantStem).toUpperCase(java.util.Locale.ROOT);
                    EntityLayerDefinitionResolver.Result variantRes = layerDefs.get(modelLayerField);
                    if (variantRes == null) {
                        diagnostics.info("variant '%s_%s' references model '%s' but ModelLayers.%s not in LayerDefinitions",
                            baseEntityId, variant.variantId(), variant.model(), modelLayerField);
                        continue;
                    }
                    variantRes = EntityLayerDefinitionResolver.unaliasDelegate(context.classNodes(), variantRes);
                    String variantEntityId = baseEntityId + "_" + variant.variantId();
                    if (entityToResolution.containsKey(variantEntityId)) continue;
                    entityToResolution.put(variantEntityId, variantRes);
                    float[] variantParamFloats = new float[8];
                    if (variantRes.defaultFloatParam() != null)
                        variantParamFloats[0] = variantRes.defaultFloatParam();
                    sources.add(new Source(
                        variantRes.targetClass() + ".class",
                        variantRes.targetMethod(),
                        variantEntityId,
                        YAxis.DOWN,
                        0f,
                        variantRes.texWidthOverride(),
                        variantRes.texHeightOverride(),
                        null,
                        variantParamFloats,
                        0f,
                        variantRes.appliedMeshTransformerScale(),
                        null
                    ));
                }
            }
            System.out.println("Total sources after variant LayerDefinition enumeration: " + sources.size());

            // Second pass: walk every renderer's RenderLayer chain for composite-model
            // overlays (slime outer shell, sheep wool, sheep wool undercoat). Each composite
            // overlay carries its own ModelLayers field, which we add as an extra parser
            // source so the resulting bone tree gets a deduped geometry id alongside the
            // primary entity geometries. Eye overlays still resolve via the same call; their
            // {@code modelLayerField == null} short-circuits the extra-parse step.
            Map<String, ConcurrentList<EntityOverlayResolver.Result>> overlaysByEntity =
                new LinkedHashMap<>();
            Map<String, ConcurrentList<EntityBlockOverlayResolver.Result>> blockOverlaysByEntity =
                new LinkedHashMap<>();
            Set<String> compositeOverlayFields = new LinkedHashSet<>();
            for (Map.Entry<String, EntitySessionWalk.Result> entry : records.entrySet()) {
                String entityId = entry.getKey();
                EntitySessionWalk.Result rec = entry.getValue();
                ConcurrentList<EntityOverlayResolver.Result> overlays =
                    EntityOverlayResolver.resolve(context.classNodes(), rec.rendererInternalName(), rec.layers(), entityId, diagnostics);
                overlaysByEntity.put(entityId, overlays);
                for (EntityOverlayResolver.Result desc : overlays)
                    if (desc.modelLayerField() != null) compositeOverlayFields.add(desc.modelLayerField());

                // Block-decoration layer overlays (mooshroom mushrooms, iron golem flower, etc.)
                // Walked by EntityBlockOverlayResolver from the renderer's addLayer calls and
                // each matched layer's submit-method pose-stack ops.
                ConcurrentList<EntityBlockOverlayResolver.Result> blockOverlays =
                    EntityBlockOverlayResolver.resolve(context.classNodes(), entityId, rec.rendererInternalName(), diagnostics);
                blockOverlaysByEntity.put(entityId, blockOverlays);
            }

            // Build extra Sources for each unique composite overlay layer and run them through
            // the same GeometryParser. The synthetic entityId {@code __overlay_<FIELD>} keeps
            // overlay parses distinguishable from entity primaries while still flowing through
            // the same dedupe machinery downstream (EntityRuntimeJsonWriter dedupes by factory
            // class+method, which collapses overlay <-> primary collisions naturally).
            Map<String, EntityLayerDefinitionResolver.Result> overlayFieldToResolution = new LinkedHashMap<>();
            for (String field : compositeOverlayFields) {
                EntityLayerDefinitionResolver.Result res = layerDefs.get(field);
                if (res == null) {
                    diagnostics.info("composite overlay ModelLayers.%s missing from LayerDefinitions.createRoots - skipped", field);
                    continue;
                }
                overlayFieldToResolution.put(field, res);
                sources.add(new Source(
                    res.targetClass() + ".class",
                    res.targetMethod(),
                    EntityOverlayResolver.Result.entityKey(field),
                    YAxis.DOWN,
                    0f,
                    res.texWidthOverride(),
                    res.texHeightOverride(),
                    null,
                    new float[8],
                    res.defaultInflate(),
                    1f,
                    null
                ));
                entityToResolution.put(EntityOverlayResolver.Result.entityKey(field), res);
            }
            System.out.println("Composite overlay layers: " + overlayFieldToResolution.size()
                + " (" + overlayFieldToResolution.keySet() + ")");

            ConcurrentMap<String, JsonObject> geometries = GeometryParser.parse(clientJar, sources, diagnostics);
            System.out.println("Parsed geometry for " + geometries.size() + " entities + overlays");

            // Flag geometries whose model class requests vanilla's back-face-culling render type
            // (RenderTypes.entityCutoutCull) instead of the no-cull default (entityCutout). The
            // runtime kit reads this to cull zero-thickness plane cubes - a culled plane shows only
            // its camera-facing side (the bat ear's pink inner face) rather than drawing both
            // coincident sides and letting the LEQUAL depth tie-break pick the away (brown) side.
            int cullGeometries = 0;
            for (Source source : sources) {
                JsonObject geometry = geometries.get(source.entityId());
                if (geometry == null || geometry.has("cull")) continue;
                if (EntityRenderTypeResolver.usesCullRenderType(context.classNodes(), source.classEntry())) {
                    geometry.addProperty("cull", true);
                    cullGeometries++;
                }
            }
            System.out.println("Marked " + cullGeometries + " entityCutoutCull geometries (back-face culling)");

            Path geometryDiagOut = EntityDiagnosticsWriter.writeGeometryDiagnostic(
                options, registry.totalMobsDiscovered(), records.size(), entityToResolution, geometries, diagnostics
            );
            System.out.println("Wrote " + geometryDiagOut);

            // Emit the runtime-consumable JSONs in the EntityModelLoader-expected
            // shape. entity_geometry.json carries one entry per unique factory (deduplicated
            // when multiple entities share a createBodyLayer); entity_models.json carries
            // per-entity rows pointing into the geometry table plus optional variant rows
            // emitted from the data-driven variant tables loaded during binding.
            int variantRowsEmitted = EntityRuntimeJsonWriter.writeAll(
                context, records, entityToResolution, geometries, variants, diagnostics,
                overlaysByEntity, overlayFieldToResolution, dataVariantDefaults,
                blockOverlaysByEntity
            );
            System.out.println("Wrote " + EntityRuntimeJsonWriter.MODELS_OUTPUT.toAbsolutePath() + " (+ " + variantRowsEmitted + " variant rows)");
            System.out.println("Wrote " + EntityRuntimeJsonWriter.GEOMETRY_OUTPUT.toAbsolutePath());

            // Concurrent normalized family form (entity_models2.json), grouped from the flat file
            // just written. Built side-by-side with the reader-flattener so the round-trip can be
            // diffed against this known-good output while the new schema is iterated.
            EntityFamilyJsonWriter.writeAll(diagnostics);
            System.out.println("Wrote " + EntityFamilyJsonWriter.OUTPUT.toAbsolutePath());

            System.out.printf(
                "Coverage: %d / %d mapped; texture %d hard / %d variant / %d unresolved; geometry %d%n",
                records.size(), registry.totalMobsDiscovered(), withPrimaryTexture, variantDriven, unresolvedTexture, geometries.size()
            );
            if (!registry.mobsWithoutRenderer().isEmpty()) {
                System.out.println("Mobs without renderer:");
                registry.mobsWithoutRenderer().forEach(id -> System.out.println("  " + id));
            }
            if (unresolvedTexture > 0) {
                System.out.println("Mobs with unresolved texture binding:");
                records.entrySet().stream()
                    .filter(e -> e.getValue().binding().isUnresolved())
                    .forEach(e -> System.out.println("  " + e.getKey() + " (" + e.getValue().rendererInternalName() + ")"));
            }
            if (!diagnostics.isEmpty()) {
                System.out.println("Diagnostics:");
                diagnostics.entries().forEach(line -> System.out.println("  " + line));
            }
        }
    }

}
