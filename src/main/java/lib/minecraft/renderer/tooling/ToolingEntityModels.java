package lib.minecraft.renderer.tooling;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tooling.blockentity.Source;
import lib.minecraft.renderer.tooling.blockentity.SourceDiscovery;
import lib.minecraft.renderer.tooling.blockentity.YAxis;
import lib.minecraft.renderer.tooling.entity.EntityBlockOverlayResolver;
import lib.minecraft.renderer.tooling.entity.EntityLayerDefinitionResolver;
import lib.minecraft.renderer.tooling.entity.EntityLayerScanner;
import lib.minecraft.renderer.tooling.entity.EntityOverlayResolver;
import lib.minecraft.renderer.tooling.entity.EntityProceduralLoops;
import lib.minecraft.renderer.tooling.entity.EntityRendererDiscovery;
import lib.minecraft.renderer.tooling.entity.EntityRendererScaleResolver;
import lib.minecraft.renderer.tooling.entity.EntitySetupRotationsResolver;
import lib.minecraft.renderer.tooling.entity.EntityVariantDefaultResolver;
import lib.minecraft.renderer.tooling.entity.EntityTextureResolver;
import lib.minecraft.renderer.tooling.entity.EntityVariantResolver;
import lib.minecraft.renderer.tooling.entity.MobRegistryDiscovery;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Entry point invoked by the {@code entityModels} Gradle task. Produces the entity-side
 * counterpart of {@link ToolingBlockEntities} - a single ASM walk over the deobfuscated
 * client jar that emits two paired JSON resources the runtime pipeline reads:
 *
 * <ul>
 *   <li>{@code src/main/resources/lib/minecraft/renderer/entity_models.json} - per-entity
 *       metadata: geometry reference, texture reference, optional {@code variant_of}
 *       back-link, overlays list, hidden bones, force-opaque flags.</li>
 *   <li>{@code src/main/resources/lib/minecraft/renderer/entity_geometry.json} - deduplicated
 *       bone / cube trees keyed by the unique {@code createBodyLayer}-equivalent factory
 *       method. Entities that share a factory point at the same geometry entry.</li>
 * </ul>
 *
 * <p><b>Pipeline phases.</b> Each phase narrows to one resolver in
 * {@link lib.minecraft.renderer.tooling.entity}:
 * <ol>
 *   <li><b>Phase A - mob discovery</b>
 *       ({@link MobRegistryDiscovery}, {@link EntityRendererDiscovery}). Map every living mob
 *       to its registered renderer class.</li>
 *   <li><b>Phase B - per-renderer binding</b>
 *       ({@link EntityTextureResolver}, {@link EntityVariantResolver},
 *       {@link EntityLayerScanner}). Texture references (hardcoded / conditional /
 *       variant-driven), data-driven variant tables from {@code data/minecraft/X_variant/},
 *       and the overlay enumeration from {@code addLayer(...)} call sites.</li>
 *   <li><b>Phase C - geometry parse</b>
 *       ({@link EntityLayerDefinitionResolver}, {@link EntityProceduralLoops}). Walks the
 *       {@code LayerDefinition}-returning factory and the procedural-loop supplemental bone
 *       tables for squid / blaze / ghast / silverfish / endermite / slime families.</li>
 *   <li><b>Phase D - overlay resolution</b>
 *       ({@link EntityOverlayResolver}, {@link EntityBlockOverlayResolver},
 *       {@link EntitySetupRotationsResolver}). Emits the overlay rows that
 *       {@link EntityRenderer EntityRenderer} consumes at runtime.</li>
 * </ol>
 *
 * <p><b>Diagnostics.</b> Two dev-only JSON dumps land under
 * {@code cache/asset-renderer/diagnostics/} (gitignored) alongside the production output;
 * they capture intermediate state the production output collapses away. Mannequin is the
 * one mob that falls outside the standard EntityRenderer flow (rendered through the avatar
 * pipeline) and shows up only in the diagnostics.
 *
 * @see ToolingBlockEntities
 * @see lib.minecraft.renderer.EntityRenderer
 * @see lib.minecraft.renderer.kit.EntityGeometryKit
 */
@UtilityClass
public final class ToolingEntityModels {

    /**
     * Phase-A/B diagnostic output path. Lives under {@code cache/} (gitignored).
     */
    private static final @NotNull Path DIAGNOSTICS_OUTPUT =
        Path.of("cache/asset-renderer/diagnostics/java_entity_renderers.json");

    /**
     * Phase-C geometry diagnostic output path. Lives under {@code cache/} (gitignored).
     */
    private static final @NotNull Path GEOMETRY_DIAGNOSTICS_OUTPUT =
        Path.of("cache/asset-renderer/diagnostics/java_entity_geometry.json");

    /**
     * Phase-D runtime-consumable per-entity metadata path. Same shape as the legacy
     * {@code entity_models.json} but populated by the Java pipeline; loaded by
     * {@code EntityModelLoader} when {@code PipelineOptions.entityModelSource = JAVA}.
     */
    private static final @NotNull Path MODELS_JAVA_OUTPUT =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /**
     * Phase-D runtime-consumable per-geometry bone/cube data path. Same shape as the
     * legacy {@code entity_geometry.json}; one geometry entry per unique factory
     * method, deduplicated when multiple entities share the same {@code createBodyLayer}.
     */
    private static final @NotNull Path GEOMETRY_JAVA_OUTPUT =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /**
     * Namespace prefix applied to every emitted entity id.
     */
    private static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    /**
     * Variant id treated as the base entity (no separate {@code variant_of} row emitted) when
     * walking data-driven variant tables. Vanilla 1.21+ uses {@code "temperate"} for cow / pig /
     * chicken / frog as the climate-default; the base entity ({@code minecraft:cow}) takes that
     * variant's texture when no spawn-condition match overrides it.
     */
    private static final @NotNull String DEFAULT_VARIANT_ID = "temperate";

    /**
     * Per-entity default texture stems for renderers whose binding is genuinely unresolvable from
     * the renderer class alone AND whose variant detection doesn't fit the
     * {@link EntityVariantDefaultResolver}'s enum-default shape:
     * <ul>
     * <li><b>shulker</b> - {@code ShulkerRenderer} reads its texture array from
     *     {@code Sheets.SHULKER_TEXTURE_LOCATION}, a sibling-class List that maps
     *     {@code DyeColor} -&gt; Identifier through atlas sprite ids. Default to the
     *     un-coloured base shulker texture.</li>
     * <li><b>copper_golem</b> - {@code CopperGolemRenderer} dispatches on weathering state via
     *     a chained {@code INVOKESTATIC + INVOKEVIRTUAL} pattern the static walker doesn't
     *     unfold. Default to the unweathered base.</li>
     * <li><b>ender_dragon</b> - {@code EnderDragonRenderer.<clinit>} binds 4 textures
     *     (dragon.png, dragon_eyes.png, dragon_exploding.png, end_crystal_beam.png) but
     *     doesn't override getTextureLocation - the resolver's hierarchy walk finds nothing
     *     because EnderDragonRenderer extends EntityRenderer&lt;EnderDragon&gt; and
     *     EntityRenderer's getTextureLocation is abstract. Java's render path picks
     *     DRAGON_TEXTURE_LOCATION via direct getstatic in the submit path, which the static
     *     walker can't follow. Hardcode the static rest-pose texture.</li>
     * </ul>
     *
     * <p>Axolotl + rabbit moved out: their {@code state.variant} field is a public enum with
     * a {@code DEFAULT} static field that {@link EntityVariantDefaultResolver} walks via
     * bytecode. Adding new entries here should be a last resort - prefer extending the
     * variant-default walker or the renderer-specific binding logic.
     */
    private static final @NotNull java.util.Map<String, String> ENTITY_TEXTURE_HARD_DEFAULTS = java.util.Map.of(
        "minecraft:shulker", "shulker/shulker",
        "minecraft:copper_golem", "copper_golem/copper_golem",
        "minecraft:ender_dragon", "enderdragon/dragon"
    );

    /**
     * Runs the discovery pipeline and writes the diagnostic JSON.
     *
     * @param args ignored - all paths are fixed
     * @throws IOException if the client jar cannot be read or the diagnostic file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        PipelineOptions options = PipelineOptions.defaults();

        System.out.println("Downloading Minecraft client jar...");
        Path clientJar = Pipeline.downloadJarToCache(options);

        Diagnostics diagnostics = new Diagnostics();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            ConcurrentList<MobRegistryDiscovery.MobEntry> mobs = MobRegistryDiscovery.discover(zip, diagnostics);
            System.out.println("Discovered " + mobs.size() + " living-mob entity types");

            ConcurrentList<EntityRendererDiscovery.Registration> registrations =
                EntityRendererDiscovery.discover(zip, diagnostics);
            System.out.println("Discovered " + registrations.size() + " entity-renderer registrations");

            Map<String, String> rendererByField = new LinkedHashMap<>();
            // Per-entity-field maps - donkey vs mule both bind DonkeyRenderer but each lambda
            // pushes a distinct ModelLayers / Type-enum constant onto its own constructor call;
            // keying by renderer class alone would lose the per-mob distinction.
            Map<String, ConcurrentList<String>> lambdaLayerFieldsByEntityField = new LinkedHashMap<>();
            Map<String, ConcurrentList<EntityRendererDiscovery.TypeFieldRef>> lambdaTypeArgsByEntityField = new LinkedHashMap<>();
            for (EntityRendererDiscovery.Registration reg : registrations) {
                rendererByField.put(reg.entityFieldName(), reg.rendererInternalName());
                lambdaLayerFieldsByEntityField.put(reg.entityFieldName(), reg.lambdaLayerFields());
                lambdaTypeArgsByEntityField.put(reg.entityFieldName(), reg.lambdaTypeArgs());
            }

            ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Variant>> variants =
                EntityVariantResolver.loadAll(zip, diagnostics);
            System.out.println("Loaded variant tables for " + variants.size() + " entity types ("
                + variants.keySet() + ")");

            ConcurrentMap<String, String> dataVariantDefaults =
                EntityVariantResolver.loadDataDrivenDefaults(zip, diagnostics);
            System.out.println("Canonical data-variant defaults: " + dataVariantDefaults);

            // Per-mob: gather (renderer, texture binding, layer list) and roll up coverage stats.
            Map<String, EntityRecord> records = new LinkedHashMap<>();
            Set<String> mobsWithoutRenderer = new LinkedHashSet<>();
            int withPrimaryTexture = 0;
            int variantDriven = 0;
            int unresolvedTexture = 0;
            for (MobRegistryDiscovery.MobEntry mob : mobs) {
                String entityId = MINECRAFT_NAMESPACE + mob.entityId();
                String renderer = rendererByField.get(mob.fieldName());
                if (renderer == null) {
                    mobsWithoutRenderer.add(entityId);
                    continue;
                }

                ConcurrentList<EntityRendererDiscovery.TypeFieldRef> typeArgs =
                    lambdaTypeArgsByEntityField.getOrDefault(mob.fieldName(), Concurrent.newList());
                EntityTextureResolver.Binding binding =
                    EntityTextureResolver.resolve(zip, renderer, mob.entityId(), typeArgs, diagnostics);
                // Enum-default variant detection: AxolotlRenderer / RabbitRenderer read their
                // texture via state.variant lookup into a Map<XVariant, Identifier>. The
                // resolver walks the variant enum class for its DEFAULT static field and
                // computes the canonical texture stem (axolotl/axolotl_lucy, rabbit/rabbit_brown)
                // without a hardcoded entry. Falls back to ENTITY_TEXTURE_HARD_DEFAULTS for
                // patterns the walker can't recover (shulker DyeColor sheet, copper_golem
                // weathering dispatch, ender_dragon submit-path getstatic, cat data registry).
                EntityVariantDefaultResolver.DefaultVariant variantDefault =
                    EntityVariantDefaultResolver.resolve(zip, renderer, diagnostics);
                if (variantDefault != null) {
                    String stem = entityId.startsWith(MINECRAFT_NAMESPACE) ? entityId.substring(MINECRAFT_NAMESPACE.length()) : entityId;
                    String candidate = "textures/entity/" + stem + "/" + stem + "_" + variantDefault.defaultName() + ".png";
                    // Verify the path-by-convention exists in the jar. Vanilla doesn't always
                    // follow <stem>/<stem>_<variant>.png:
                    // - mooshroom's textures live under cow/ (e.g. cow/mooshroom_red.png)
                    // - fox.RED has no separate texture (just fox.png; variant doesn't suffix)
                    // Existence-gating keeps the override narrow to entities whose convention
                    // matches (axolotl, llama, parrot, rabbit); the others fall through to
                    // the binding-extracted path or hardcoded fallback.
                    String assetPath = "assets/minecraft/" + candidate;
                    if (zip.getEntry(assetPath) != null) {
                        binding = new EntityTextureResolver.Binding(
                            candidate,
                            null,
                            binding.variantSourceClass(),
                            "(enum-default '" + variantDefault.defaultName() + "')"
                        );
                    }
                }
                if (binding.primaryTexturePath() == null) {
                    String fallback = ENTITY_TEXTURE_HARD_DEFAULTS.get(entityId);
                    if (fallback != null)
                        binding = new EntityTextureResolver.Binding(
                            "textures/entity/" + fallback + ".png",
                            null,
                            binding.variantSourceClass(),
                            "(hard-default)"
                        );
                }
                ConcurrentList<String> layers = EntityLayerScanner.scan(zip, renderer, diagnostics);

                String variantStem;
                if (binding.variantSourceClass() != null) {
                    variantStem = EntityVariantResolver.directoryFor(binding.variantSourceClass());
                } else if (binding.isUnresolved() && variants.containsKey(mob.entityId())) {
                    // Fallback for state-field-read patterns (WolfRenderer, CatRenderer, etc.):
                    // getTextureLocation returns state.texture, which is populated upstream from
                    // a variant lookup we can't trace from getTextureLocation alone. The variant
                    // directory's existence is a reliable signal the entity is variant-driven.
                    variantStem = mob.entityId();
                    binding = new EntityTextureResolver.Binding(null, null, "(state-field-driven)", binding.hierarchySource());
                } else {
                    variantStem = null;
                }

                // Phase E.2 added a fallback that surfaces a primary texture even when the
                // binding is variant-flagged - count those as "with primary" so the coverage
                // numbers reflect the runtime-consumable artifact (entity_models.json
                // gets a texture_ref either way). Fully unresolved variant bindings (no
                // texture path at all) still count as variantDriven.
                if (binding.primaryTexturePath() != null) withPrimaryTexture++;
                else if (binding.isVariantDriven()) variantDriven++;
                else unresolvedTexture++;

                float setupYawAddend = EntitySetupRotationsResolver.resolve(zip, renderer);
                Float rendererScale = EntityRendererScaleResolver.resolve(zip, renderer, diagnostics);
                records.put(entityId, new EntityRecord(renderer, binding, layers, variantStem, mob.fieldName(), setupYawAddend, rendererScale));
            }

            JsonObject root = buildDiagnosticJson(
                options, mobs, registrations, records, mobsWithoutRenderer, variants,
                withPrimaryTexture, variantDriven, unresolvedTexture, diagnostics
            );

            Files.createDirectories(DIAGNOSTICS_OUTPUT.getParent());
            Files.writeString(
                DIAGNOSTICS_OUTPUT,
                new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator()
            );
            System.out.println("Wrote " + DIAGNOSTICS_OUTPUT.toAbsolutePath());

            // Phase C: per-mob geometry extraction. Resolve each renderer's primary
            // ModelLayers.X via LayerDefinitions.createRoots, build synthetic Sources, and
            // delegate to the shared block-entity Parser - the bytecode patterns
            // (LayerDefinition.create / CubeListBuilder / PartPose / addOrReplaceChild) are
            // identical between block and mob models. Frame conversion (Java Y-DOWN to
            // legacy Y-UP) is deferred to Phase E.5 - this phase only verifies bone/cube
            // count parity vs the legacy baseline.
            ConcurrentMap<String, EntityLayerDefinitionResolver.Resolution> layerDefs =
                EntityLayerDefinitionResolver.loadLayerDefinitions(zip, diagnostics);
            System.out.println("Loaded " + layerDefs.size() + " ModelLayers entries from LayerDefinitions.createRoots");

            ConcurrentMap<String, EntityLayerDefinitionResolver.Resolution> entityToResolution = Concurrent.newMap();
            List<Source> sources = new ArrayList<>();
            for (Map.Entry<String, EntityRecord> entry : records.entrySet()) {
                ConcurrentList<String> lambdaFields = lambdaLayerFieldsByEntityField.getOrDefault(
                    entry.getValue().entityFieldName(), Concurrent.newList()
                );
                // Augment with model-layer fields walked from the renderer's Type enum
                // (donkey / horse family): the lambda exposes only the saddle / equipment
                // layer, but the body layer lives behind Type.X.model. Adding the per-
                // constant ModelLayers reference lets the picker's entity-id-match heuristic
                // promote ModelLayers.DONKEY over the lambda's DONKEY_SADDLE for
                // minecraft:donkey.
                ConcurrentList<EntityRendererDiscovery.TypeFieldRef> typeArgsForLayer =
                    lambdaTypeArgsByEntityField.getOrDefault(entry.getValue().entityFieldName(), Concurrent.newList());
                if (!typeArgsForLayer.isEmpty()) {
                    String typeOwner = typeArgsForLayer.get(0).owner();
                    Map<String, String> typeToModelLayer = EntityTextureResolver.typeConstantModelLayerMap(zip, typeOwner);
                    for (EntityRendererDiscovery.TypeFieldRef ref : typeArgsForLayer) {
                        String layer = typeToModelLayer.get(ref.name());
                        if (layer != null && !lambdaFields.contains(layer)) lambdaFields.add(layer);
                    }
                }
                EntityLayerDefinitionResolver.Resolution resolution =
                    EntityLayerDefinitionResolver.resolvePrimary(
                        zip, entry.getValue().rendererInternalName(), entry.getKey(),
                        lambdaFields, layerDefs, diagnostics
                    );
                if (resolution == null) continue;
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
                    resolution.appliedMeshTransformerScale()
                ));
            }
            System.out.println("Resolved " + sources.size() + " primary LayerDefinition factories for geometry parsing");

            // Phase E.4 second pass: walk every renderer's RenderLayer chain for composite-
            // model overlays (slime outer shell, sheep wool, sheep wool undercoat). Each
            // composite overlay carries its own ModelLayers field, which we add as an extra
            // parser source so the resulting bone tree gets a deduped geometry id alongside
            // the primary entity geometries. Eye overlays still resolve via the same call;
            // their {@code modelLayerField == null} short-circuits the extra-parse step.
            Map<String, ConcurrentList<EntityOverlayResolver.OverlayDescriptor>> overlaysByEntity =
                new LinkedHashMap<>();
            Map<String, ConcurrentList<EntityBlockOverlayResolver.BlockOverlayDescriptor>> blockOverlaysByEntity =
                new LinkedHashMap<>();
            Set<String> compositeOverlayFields = new LinkedHashSet<>();
            for (Map.Entry<String, EntityRecord> entry : records.entrySet()) {
                String entityId = entry.getKey();
                EntityRecord rec = entry.getValue();
                ConcurrentList<EntityOverlayResolver.OverlayDescriptor> overlays =
                    EntityOverlayResolver.resolve(zip, rec.rendererInternalName(), rec.layers(), entityId, diagnostics);
                overlaysByEntity.put(entityId, overlays);
                for (EntityOverlayResolver.OverlayDescriptor desc : overlays)
                    if (desc.modelLayerField() != null) compositeOverlayFields.add(desc.modelLayerField());

                // Block-decoration layer overlays (mooshroom mushrooms, iron golem flower, etc.)
                // Walked by EntityBlockOverlayResolver from the renderer's addLayer calls and
                // each matched layer's submit-method pose-stack ops. Generalises the prior
                // hardcoded {@link #buildMooshroomBlockOverlays} table - kept as a fallback for
                // entities the resolver doesn't yet recognise.
                ConcurrentList<EntityBlockOverlayResolver.BlockOverlayDescriptor> blockOverlays =
                    EntityBlockOverlayResolver.resolve(zip, entityId, rec.rendererInternalName(), diagnostics);
                blockOverlaysByEntity.put(entityId, blockOverlays);
            }

            // Build extra Sources for each unique composite overlay layer and run them through the
            // same Parser. The synthetic entityId {@code __overlay_<FIELD>} keeps overlay parses
            // distinguishable from entity primaries while still flowing through the same dedupe
            // machinery downstream (writeRuntimeJson dedupes by factory class+method, which
            // collapses overlay <-> primary collisions naturally).
            Map<String, EntityLayerDefinitionResolver.Resolution> overlayFieldToResolution = new LinkedHashMap<>();
            for (String field : compositeOverlayFields) {
                EntityLayerDefinitionResolver.Resolution res = layerDefs.get(field);
                if (res == null) {
                    diagnostics.info("composite overlay ModelLayers.%s missing from LayerDefinitions.createRoots - skipped", field);
                    continue;
                }
                overlayFieldToResolution.put(field, res);
                sources.add(new Source(
                    res.targetClass() + ".class",
                    res.targetMethod(),
                    overlayEntityKey(field),
                    YAxis.DOWN,
                    0f,
                    res.texWidthOverride(),
                    res.texHeightOverride(),
                    null,
                    new float[8],
                    res.defaultInflate()
                ));
                entityToResolution.put(overlayEntityKey(field), res);
            }
            System.out.println("Composite overlay layers: " + overlayFieldToResolution.size()
                + " (" + overlayFieldToResolution.keySet() + ")");

            ConcurrentMap<String, JsonObject> geometries = ToolingBlockEntities.Parser.parse(clientJar, sources, diagnostics);
            System.out.println("Parsed geometry for " + geometries.size() + " entities + overlays");

            // Stub-injection for procedural-loop entities the Parser can't extract bones for
            // (silverfish + endermite both build bone names via {@code makeConcatWithConstants}
            // and pull cube dimensions from static {@code int[][] BODY_SIZES} arrays the parser
            // can't decode - the parser returns no geometry, so without this stub the entity
            // drops out of {@code entity_models.json}). The stub is just an empty bones
            // container with a sensible 64x32 default texture size; the procedural-loop template
            // populates the bones in the next pass.
            for (Map.Entry<String, EntityLayerDefinitionResolver.Resolution> entry : entityToResolution.entrySet()) {
                if (geometries.containsKey(entry.getKey())) continue;
                if (!EntityProceduralLoops.hasTemplate(entry.getValue())) continue;
                JsonObject stub = new JsonObject();
                stub.addProperty("textureWidth", entry.getValue().texWidthOverride() != null
                    ? entry.getValue().texWidthOverride() : 64);
                stub.addProperty("textureHeight", entry.getValue().texHeightOverride() != null
                    ? entry.getValue().texHeightOverride() : 32);
                stub.add("bones", new JsonObject());
                geometries.put(entry.getKey(), stub);
            }

            // Phase E.3: augment procedural-loop geometries (squid tentacles etc.) that the
            // shared linear-walking Parser collapses into a single iteration. See
            // {@link EntityProceduralLoops} for the per-class template registry.
            for (Map.Entry<String, JsonObject> entry : geometries.entrySet()) {
                EntityLayerDefinitionResolver.Resolution res = entityToResolution.get(entry.getKey());
                if (res != null) EntityProceduralLoops.augment(entry.getValue(), res);
            }

            JsonObject geometryRoot = buildGeometryDiagnosticJson(
                options, mobs.size(), records.size(), entityToResolution, geometries, diagnostics
            );
            Files.writeString(
                GEOMETRY_DIAGNOSTICS_OUTPUT,
                new GsonBuilder().setPrettyPrinting().create().toJson(geometryRoot) + System.lineSeparator()
            );
            System.out.println("Wrote " + GEOMETRY_DIAGNOSTICS_OUTPUT.toAbsolutePath());

            // Phase D: emit runtime-consumable JSONs in the EntityModelLoader-expected shape.
            // entity_geometry.json carries one entry per unique factory (deduplicated when
            // multiple entities share a createBodyLayer); entity_models.json carries
            // per-entity rows pointing into the geometry table plus optional variant rows
            // emitted from the data-driven variant tables loaded in Phase B.
            int variantRowsEmitted = writeRuntimeJson(
                records, entityToResolution, geometries, variants, diagnostics,
                overlaysByEntity, overlayFieldToResolution, dataVariantDefaults,
                blockOverlaysByEntity
            );
            System.out.println("Wrote " + MODELS_JAVA_OUTPUT.toAbsolutePath() + " (+ " + variantRowsEmitted + " variant rows)");
            System.out.println("Wrote " + GEOMETRY_JAVA_OUTPUT.toAbsolutePath());

            System.out.printf(
                "Coverage: %d / %d mapped; texture %d hard / %d variant / %d unresolved; geometry %d%n",
                records.size(), mobs.size(), withPrimaryTexture, variantDriven, unresolvedTexture, geometries.size()
            );
            if (!mobsWithoutRenderer.isEmpty()) {
                System.out.println("Mobs without renderer:");
                mobsWithoutRenderer.forEach(id -> System.out.println("  " + id));
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

    /**
     * Builds the Phase-C geometry diagnostic JSON: per-entity bone/cube counts plus the resolved
     * factory class+method, suitable for diff-comparison against the legacy
     * {@code entity_geometry.json}.
     */
    /**
     * Converts a list of {@link EntityBlockOverlayResolver.BlockOverlayDescriptor} into the
     * JSON wire format consumed by
     * {@link EntityModelLoader#loadBlockOverlays}.
     * Each descriptor becomes one {@code block_overlays[]} row; descriptors are emitted in the
     * order the resolver returned them (which mirrors the bytecode pushPose/popPose order).
     */
    private static @NotNull JsonArray buildBlockOverlaysJson(
        @NotNull ConcurrentList<EntityBlockOverlayResolver.BlockOverlayDescriptor> descriptors
    ) {
        JsonArray rows = new JsonArray();
        for (EntityBlockOverlayResolver.BlockOverlayDescriptor desc : descriptors) {
            JsonObject row = new JsonObject();
            row.addProperty("block_id", desc.blockId());
            if (desc.attachedBone() != null) row.addProperty("attached_bone", desc.attachedBone());
            JsonArray opsJson = new JsonArray();
            for (var op : desc.ops()) {
                JsonObject opJson = switch (op.kind()) {
                    case TRANSLATE -> translate(op.a(), op.b(), op.c());
                    case ROTATE_Y -> rotateY(op.a());
                    case SCALE -> scale(op.a(), op.b(), op.c());
                };
                opsJson.add(opJson);
            }
            row.add("transforms", opsJson);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Builds a {@code translate} transform op JSON.
     */
    private static @NotNull JsonObject translate(float x, float y, float z) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "translate");
        op.addProperty("x", x);
        op.addProperty("y", y);
        op.addProperty("z", z);
        return op;
    }

    /**
     * Builds a {@code rotate_y} transform op JSON.
     */
    private static @NotNull JsonObject rotateY(float degrees) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "rotate_y");
        op.addProperty("degrees", degrees);
        return op;
    }

    /**
     * Builds a {@code scale} transform op JSON.
     */
    private static @NotNull JsonObject scale(float x, float y, float z) {
        JsonObject op = new JsonObject();
        op.addProperty("op", "scale");
        op.addProperty("x", x);
        op.addProperty("y", y);
        op.addProperty("z", z);
        return op;
    }

    private static @NotNull JsonObject buildGeometryDiagnosticJson(
        @NotNull PipelineOptions options,
        int mobsTotal,
        int mobsWithRenderer,
        @NotNull ConcurrentMap<String, EntityLayerDefinitionResolver.Resolution> entityToResolution,
        @NotNull ConcurrentMap<String, JsonObject> geometries,
        @NotNull Diagnostics diagnostics
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Per-entity geometry from Java client jar bytecode (LayerDefinition.create / CubeListBuilder / PartPose / addOrReplaceChild walked via the shared block-entity Parser). Frame is vanilla Java's natural Y-DOWN.");
        root.addProperty("client_version", options.getVersion());
        root.addProperty("mobs_total", mobsTotal);
        root.addProperty("mobs_with_renderer", mobsWithRenderer);
        root.addProperty("mobs_with_primary_layer", entityToResolution.size());
        root.addProperty("mobs_with_geometry", geometries.size());

        int totalBones = 0;
        int totalCubes = 0;
        JsonObject entities = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : geometries.entrySet()) {
            JsonObject geometry = entry.getValue();
            JsonObject row = new JsonObject();
            EntityLayerDefinitionResolver.Resolution res = entityToResolution.get(entry.getKey());
            if (res != null) {
                row.addProperty("layer_field", res.sourceLayerField());
                row.addProperty("factory_class", res.targetClass());
                row.addProperty("factory_method", res.targetMethod());
            }
            int textureWidth = geometry.has("textureWidth") ? geometry.get("textureWidth").getAsInt() : 0;
            int textureHeight = geometry.has("textureHeight") ? geometry.get("textureHeight").getAsInt() : 0;
            row.addProperty("texture_width", textureWidth);
            row.addProperty("texture_height", textureHeight);

            int boneCount = 0;
            int cubeCount = 0;
            JsonArray boneNames = new JsonArray();
            if (geometry.has("bones") && geometry.get("bones").isJsonObject()) {
                JsonObject bones = geometry.getAsJsonObject("bones");
                boneCount = bones.size();
                for (Map.Entry<String, com.google.gson.JsonElement> b : bones.entrySet()) {
                    boneNames.add(b.getKey());
                    if (b.getValue().isJsonObject()
                        && b.getValue().getAsJsonObject().has("cubes")
                        && b.getValue().getAsJsonObject().get("cubes").isJsonArray())
                        cubeCount += b.getValue().getAsJsonObject().getAsJsonArray("cubes").size();
                }
            }
            row.addProperty("bone_count", boneCount);
            row.addProperty("cube_count", cubeCount);
            row.add("bone_names", boneNames);
            entities.add(entry.getKey(), row);
            totalBones += boneCount;
            totalCubes += cubeCount;
        }
        root.addProperty("total_bones", totalBones);
        root.addProperty("total_cubes", totalCubes);
        root.add("entities", entities);

        JsonArray diagnosticsArr = new JsonArray();
        for (String line : diagnostics.entries()) diagnosticsArr.add(line);
        root.add("diagnostics", diagnosticsArr);
        return root;
    }

    /**
     * Synthetic per-overlay entityId used as a parser source key. Keeps composite-overlay
     * geometries distinguishable from real entity primaries while still flowing through the
     * shared dedupe machinery in {@link #writeRuntimeJson}.
     */
    private static @NotNull String overlayEntityKey(@NotNull String modelLayerField) {
        return "__overlay_" + modelLayerField;
    }

    /**
     * Emits the runtime-consumable {@code entity_models.json} and
     * {@code entity_geometry.json}. The geometry file deduplicates by factory class+method
     * so multiple entities sharing one {@code createBodyLayer} (e.g. zombie / husk / drowned all
     * point at the same {@code AbstractZombieRenderer} chain) share one geometry entry. The
     * models file emits one row per Phase-A entity plus additional rows per non-default
     * data-driven variant (cow_cold / cow_warm / pig_cold / etc.), each carrying
     * {@code variant_of} pointing back at its base entity.
     *
     * @param overlaysByEntity per-entity pre-resolved overlay descriptors from
     *     {@link EntityOverlayResolver#resolve}; eye overlays carry a {@code null}
     *     {@code modelLayerField} (reuse base geometry), composite overlays carry the
     *     {@code ModelLayers.X} field name
     * @param overlayFieldToResolution map from composite-overlay {@code ModelLayers.X} field
     *     name to the {@code Resolution} for its layer-definition factory; lets the writer
     *     dedupe overlay geometries through the same {@code factoryKey -> geometryId} table
     *     used for primary entities
     * @return the number of variant rows written (in addition to base-entity rows)
     */
    private static int writeRuntimeJson(
        @NotNull Map<String, EntityRecord> records,
        @NotNull ConcurrentMap<String, EntityLayerDefinitionResolver.Resolution> entityToResolution,
        @NotNull ConcurrentMap<String, JsonObject> geometries,
        @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Variant>> variants,
        @NotNull Diagnostics diagnostics,
        @NotNull Map<String, ConcurrentList<EntityOverlayResolver.OverlayDescriptor>> overlaysByEntity,
        @NotNull Map<String, EntityLayerDefinitionResolver.Resolution> overlayFieldToResolution,
        @NotNull Map<String, String> dataVariantDefaults,
        @NotNull Map<String, ConcurrentList<EntityBlockOverlayResolver.BlockOverlayDescriptor>> blockOverlaysByEntity
    ) throws IOException {
        // Build (factoryKey -> geometry id) so multiple entities sharing one createBodyLayer
        // map to one geometry entry. Geometry id derived from the factory class name's lowercased
        // simple name plus the method's suffix - matches the convention {@code geometry.X}.
        Map<String, String> factoryKeyToGeometryId = new LinkedHashMap<>();
        JsonObject geometriesOut = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : geometries.entrySet()) {
            EntityLayerDefinitionResolver.Resolution res = entityToResolution.get(entry.getKey());
            if (res == null) continue;
            // Include defaultInflate in the dedupe key so the same factory called with different
            // CubeDeformation args (e.g. {@code DrownedModel.createBodyLayer(NONE)} for the body
            // vs {@code .createBodyLayer(0.25)} for the outer-layer overlay) gets distinct
            // geometry entries instead of collapsing onto a single inflate=0 row.
            String factoryKey = res.targetClass() + "#" + res.targetMethod()
                + (res.defaultInflate() != 0f ? "#inflate=" + res.defaultInflate() : "")
                + (res.defaultFloatParam() != null ? "#fparam=" + res.defaultFloatParam() : "")
                + (res.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + res.appliedMeshTransformerScale() : "");
            String geometryId = factoryKeyToGeometryId.computeIfAbsent(factoryKey, k -> {
                String simple = res.targetClass().substring(res.targetClass().lastIndexOf('/') + 1);
                String entityName = stripModelSuffix(simple).toLowerCase(java.util.Locale.ROOT);
                String candidate = "geometry." + entityName;
                int collision = 0;
                while (geometriesOut.has(candidate)) {
                    collision++;
                    candidate = "geometry." + entityName + "_" + collision;
                }
                return candidate;
            });
            if (!geometriesOut.has(geometryId)) geometriesOut.add(geometryId, entry.getValue());
        }

        JsonObject geometryRoot = new JsonObject();
        geometryRoot.addProperty("//", "Generated by ToolingEntityModels. Per-geometry bone/cube tree from Java client jar bytecode, deduplicated by factory class+method. Frame is vanilla Java's natural Y-DOWN.");
        geometryRoot.add("geometries", geometriesOut);
        Files.createDirectories(GEOMETRY_JAVA_OUTPUT.getParent());
        Files.writeString(
            GEOMETRY_JAVA_OUTPUT,
            new GsonBuilder().setPrettyPrinting().create().toJson(geometryRoot) + System.lineSeparator()
        );

        JsonObject entitiesOut = new JsonObject();
        int variantRows = 0;
        for (Map.Entry<String, EntityRecord> entry : records.entrySet()) {
            String entityId = entry.getKey();
            EntityRecord rec = entry.getValue();
            EntityLayerDefinitionResolver.Resolution res = entityToResolution.get(entityId);
            String geometryId = res == null ? null : factoryKeyToGeometryId.get(
                res.targetClass() + "#" + res.targetMethod()
                + (res.defaultInflate() != 0f ? "#inflate=" + res.defaultInflate() : "")
                + (res.defaultFloatParam() != null ? "#fparam=" + res.defaultFloatParam() : "")
                + (res.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + res.appliedMeshTransformerScale() : ""));
            if (geometryId == null) continue;

            JsonObject row = new JsonObject();
            row.addProperty("geometry_ref", geometryId);
            String texture = rec.binding().primaryTexturePath();
            // Variant-driven base entities (cow / pig / chicken / frog / cat / wolf) have no
            // hardcoded primary texture - their renderer reads it from the variant's data-driven
            // asset_id at runtime. Default to the temperate / first variant's texture so the
            // base-entity row still has a sensible texture_ref the renderer can fall back on.
            if (texture == null && rec.variantStem() != null) {
                ConcurrentList<EntityVariantResolver.Variant> vlist = variants.get(rec.variantStem());
                if (vlist != null && !vlist.isEmpty()) {
                    EntityVariantResolver.Variant defaultVariant =
                        pickDefaultVariant(vlist, dataVariantDefaults.get(rec.variantStem()));
                    String def = defaultVariant.primaryTexturePath();
                    if (def != null) texture = def;
                }
            }
            if (texture != null) row.addProperty("texture_ref", stripTexturesPrefix(texture));
            row.addProperty("armor_type", inferArmorType(rec.layers()));
            // Renderer.scale residue extracted by EntityRendererScaleResolver. Non-null only
            // when the renderer's scale override contains at least one literal poseStack.scale
            // call AND the product differs from 1.0 - currently wither (2.0) and slime (0.999).
            if (rec.rendererScale() != null) row.addProperty("renderer_scale", rec.rendererScale());

            // Phase E.4: emit overlays (eye layers + composite-model layers like slime outer
            // shell, sheep wool, sheep wool undercoat). Eye overlays carry {@code modelLayerField
            // == null} and reuse the base entity's geometry. Composite overlays carry their own
            // {@code ModelLayers.X} field; resolving it through the same {@code factoryKey ->
            // geometryId} table that primaries use gives the overlay a stable deduped geometry
            // entry. A composite overlay whose factory wasn't found in {@code layerDefs} silently
            // drops (the parser had no source to extract it from).
            ConcurrentList<EntityOverlayResolver.OverlayDescriptor> overlays =
                overlaysByEntity.getOrDefault(entityId, Concurrent.newList());
            if (!overlays.isEmpty()) {
                JsonArray overlaysJson = new JsonArray();
                for (EntityOverlayResolver.OverlayDescriptor desc : overlays) {
                    String overlayGeometryId = geometryId;
                    if (desc.modelLayerField() != null) {
                        EntityLayerDefinitionResolver.Resolution overlayRes =
                            overlayFieldToResolution.get(desc.modelLayerField());
                        if (overlayRes == null) continue;
                        String overlayFactoryKey = overlayRes.targetClass() + "#" + overlayRes.targetMethod()
                            + (overlayRes.defaultInflate() != 0f ? "#inflate=" + overlayRes.defaultInflate() : "")
                            + (overlayRes.defaultFloatParam() != null ? "#fparam=" + overlayRes.defaultFloatParam() : "")
                            + (overlayRes.appliedMeshTransformerScale() != 1f ? "#appliedMT=" + overlayRes.appliedMeshTransformerScale() : "");
                        overlayGeometryId = factoryKeyToGeometryId.get(overlayFactoryKey);
                        if (overlayGeometryId == null) continue;
                    }
                    JsonObject overlay = new JsonObject();
                    overlay.addProperty("geometry_ref", overlayGeometryId);
                    overlay.addProperty("texture_ref", stripTexturesPrefix(desc.texturePath()));
                    if (desc.emissive()) overlay.addProperty("emissive", true);
                    if (desc.tintArgb() != 0xFFFFFFFF)
                        overlay.addProperty("tint_color", String.format("0x%08X", desc.tintArgb()));
                    // Overlays sharing the base geometry need a microscopic outward inflate to
                    // clear ModelEngine's equal-Z depth-fail (depthVal <= existingDepth REJECTS
                    // at equal Z). Without it, the overlay lands on the same depth as the lit
                    // skin texel and never wins - enderman renders pink instead of pure purple,
                    // breeze / cave_spider / phantom likewise. Spider accidentally works only
                    // because its rotated leg bones introduce FP noise that breaks the equal-Z
                    // tie. Applies to ALL same-geometry overlays (which today means eye layers):
                    // both emissive variants (RenderTypes.eyes -> EMISSIVE + NO_CARDINAL_LIGHTING)
                    // AND shaded translucent variants (RenderTypes.breezeEyes ->
                    // ENTITY_TRANSLUCENT_EMISSIVE with PER_FACE_LIGHTING). Non-eye overlays use
                    // composite geometry (modelLayerField != null) and are excluded - their
                    // visibility is vanilla-gated by runtime state ({@code SheepWoolUndercoatLayer}
                    // on {@code woolColor != WHITE}, {@code DrownedOuterLayer} unconditionally),
                    // and depth-fail rejection is what hides them at zero state.
                    boolean sharesBaseGeometry = desc.modelLayerField() == null;
                    if (sharesBaseGeometry)
                        overlay.addProperty("inflate", 0.001f);
                    overlaysJson.add(overlay);
                }
                if (!overlaysJson.isEmpty()) row.add("overlays", overlaysJson);
            }

            // Block-model overlays driven by {@link EntityBlockOverlayResolver}: walks the
            // renderer's addLayer calls to find recognised block-decoration layers (mooshroom's
            // {@code MushroomCowMushroomLayer}, iron-golem's flower layer, enderman's carried
            // block, etc), then walks each layer's submit method between pushPose / popPose
            // pairs to extract the literal pose-stack ops. Produces one row per pair.
            ConcurrentList<EntityBlockOverlayResolver.BlockOverlayDescriptor> blockOverlayDescs =
                blockOverlaysByEntity.getOrDefault(entityId, Concurrent.newList());
            if (!blockOverlayDescs.isEmpty()) row.add("block_overlays", buildBlockOverlaysJson(blockOverlayDescs));

            // setup_yaw_addend: vanilla {@code <X>Renderer.setupRotations} override's literal
            // float constant added to {@code bodyRot} before {@code super.setupRotations}. Only
            // {@code ShulkerRenderer} surfaces a non-zero value ({@code +180F}); every other
            // override leaves {@code bodyRot} unmodified and the resolver returns 0 (which we
            // omit from JSON to keep noise-free rows).
            if (rec.setupYawAddend() != 0f) row.addProperty("setup_yaw_addend", rec.setupYawAddend());

            entitiesOut.add(entityId, row);

            // Variant rows for data-driven variants only (cow_cold, pig_warm, chicken_cold, ...).
            // Skip the default (temperate) since it IS the base entity. Skip overlay-state
            // variants (creeper_charged, sheep_sheared) - those need RenderLayer extraction
            // which is Phase E.5 work.
            if (rec.variantStem() == null) continue;
            ConcurrentList<EntityVariantResolver.Variant> variantList = variants.get(rec.variantStem());
            if (variantList == null) continue;
            for (EntityVariantResolver.Variant variant : variantList) {
                if (DEFAULT_VARIANT_ID.equals(variant.variantId())) continue;
                String variantPrimary = variant.primaryTexturePath();
                if (variantPrimary == null) continue;
                String variantEntityId = entityId + "_" + variant.variantId();
                JsonObject variantRow = new JsonObject();
                variantRow.addProperty("geometry_ref", geometryId);
                variantRow.addProperty("texture_ref", stripTexturesPrefix(variantPrimary));
                variantRow.addProperty("armor_type", row.get("armor_type").getAsString());
                variantRow.addProperty("variant_of", entityId);
                entitiesOut.add(variantEntityId, variantRow);
                variantRows++;
            }
        }
        diagnostics.info("entity_models.json: %d base entities + %d variant rows", entitiesOut.size() - variantRows, variantRows);

        JsonObject modelsRoot = new JsonObject();
        modelsRoot.addProperty("//", "Generated by ToolingEntityModels. Per-entity metadata pointing at entity_geometry.json. Variant rows (cow_cold, pig_warm, ...) emitted from data/minecraft/X_variant/ tables.");
        modelsRoot.add("entities", entitiesOut);
        // Cross-entity families derived from shared geometry_ref. variant_of on each entity row
        // covers variant-of-same-entity groupings (cow_cold -> cow). The families table here
        // handles non-variant entities that share a primary createBodyLayer factory (mooshroom
        // and cow both bake CowModel.createBodyLayer -> both end up at geometry.cow). See
        // deriveCrossEntityFamilies for the detection rule.
        JsonObject familiesOut = deriveCrossEntityFamilies(entitiesOut, diagnostics);
        if (familiesOut.size() > 0) modelsRoot.add("families", familiesOut);
        Files.createDirectories(MODELS_JAVA_OUTPUT.getParent());
        Files.writeString(
            MODELS_JAVA_OUTPUT,
            new GsonBuilder().setPrettyPrinting().create().toJson(modelsRoot) + System.lineSeparator()
        );
        return variantRows;
    }

    /**
     * Common geometry-name prefixes that don't appear in the entity id. {@code geometry.adultcamel}
     * pairs with {@code minecraft:camel}; the resolver strips these prefixes before matching the
     * geometry stem against entity ids.
     */
    private static final @NotNull java.util.List<String> GEOMETRY_NAME_PREFIXES = java.util.List.of("adult", "baby");

    /**
     * Derives the cross-entity family table by clustering non-variant entities that share a
     * {@code geometry_ref}. The detection rule:
     * <ol>
     *   <li>Index every emitted entity row by its {@code geometry_ref}.</li>
     *   <li>Drop variant rows ({@code variant_of} present) - they're already grouped under
     *       their declared root via the per-entity {@code variant_of} field.</li>
     *   <li>For each geometry shared by 2+ non-variant entities, identify the family root as
     *       the unique member whose id (after the {@code minecraft:} namespace strip) equals
     *       the geometry stem (after the {@code geometry.} prefix strip and known
     *       {@link #GEOMETRY_NAME_PREFIXES} stripping). Examples:
     *       <ul>
     *         <li>{@code geometry.cow} + (cow, mooshroom) -> root is cow (matches geometry stem)</li>
     *         <li>{@code geometry.adultcamel} + (camel, camel_husk) -> root is camel (matches after
     *             stripping "adult")</li>
     *         <li>{@code geometry.illager} + (evoker, illusioner, pillager, vindicator) -> no
     *             member's id matches "illager", so no family is emitted (the existing
     *             {@code variant_of} flow handles intra-illager grouping if any)</li>
     *       </ul>
     *   </li>
     *   <li>Emit family mappings: every non-root sibling -> root.</li>
     * </ol>
     *
     * <p>The "id matches geometry stem" rule cleanly separates the wanted families (where the
     * geometry was authored for one canonical entity and re-used by a derivative) from the
     * coincidence families (where multiple sibling entities share a generic-named model).
     */
    private static @NotNull JsonObject deriveCrossEntityFamilies(
        @NotNull JsonObject entitiesOut,
        @NotNull Diagnostics diagnostics
    ) {
        Map<String, java.util.List<String>> geometryToBaseEntities = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : entitiesOut.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject row = entry.getValue().getAsJsonObject();
            if (row.has("variant_of")) continue;
            if (!row.has("geometry_ref")) continue;
            String geomRef = row.get("geometry_ref").getAsString();
            geometryToBaseEntities.computeIfAbsent(geomRef, k -> new java.util.ArrayList<>()).add(entry.getKey());
        }

        JsonObject families = new JsonObject();
        for (Map.Entry<String, java.util.List<String>> e : geometryToBaseEntities.entrySet()) {
            java.util.List<String> members = e.getValue();
            if (members.size() < 2) continue;
            String root = pickCanonicalFamilyRoot(e.getKey(), members);
            if (root == null) {
                diagnostics.info("cross-entity family skipped: '%s' shared by %s - no member id matches the geometry stem",
                    e.getKey(), members);
                continue;
            }
            for (String member : members) {
                if (member.equals(root)) continue;
                families.addProperty(member, root);
                diagnostics.info("cross-entity family: %s -> %s (shared %s)", member, root, e.getKey());
            }
        }
        return families;
    }

    /**
     * Returns the family root by matching the geometry stem (after stripping the
     * {@code geometry.} prefix and any {@link #GEOMETRY_NAME_PREFIXES} prefix) against each
     * candidate entity id (after the {@code minecraft:} namespace strip). Returns {@code null}
     * when no member matches the stem (e.g. {@code geometry.illager} + evoker/illusioner/
     * pillager/vindicator - no member is named "illager").
     */
    private static @Nullable String pickCanonicalFamilyRoot(@NotNull String geometryRef, @NotNull java.util.List<String> members) {
        String stem = geometryRef.startsWith("geometry.") ? geometryRef.substring("geometry.".length()) : geometryRef;
        for (String prefix : GEOMETRY_NAME_PREFIXES) {
            if (stem.startsWith(prefix)) stem = stem.substring(prefix.length());
        }
        String targetId = MINECRAFT_NAMESPACE + stem;
        for (String member : members)
            if (member.equals(targetId)) return member;
        return null;
    }

    /**
     * Picks the default variant from a variant list, preferring the canonical default detected
     * from the entity's {@code <X>Variants.DEFAULT} static field
     * ({@code WolfVariants.DEFAULT = PALE} resolves to {@code "pale"}; {@code CatVariants}
     * has no DEFAULT field so {@code canonicalDefaultId} is null and the fallback chain runs).
     * The fallback chain prefers {@code "temperate"} (cow / pig / chicken / frog
     * climate-default), then the first entry.
     */
    private static @NotNull EntityVariantResolver.Variant pickDefaultVariant(
        @NotNull ConcurrentList<EntityVariantResolver.Variant> variantList,
        @Nullable String canonicalDefaultId
    ) {
        if (canonicalDefaultId != null) {
            for (EntityVariantResolver.Variant v : variantList)
                if (canonicalDefaultId.equals(v.variantId())) return v;
        }
        for (EntityVariantResolver.Variant v : variantList)
            if (DEFAULT_VARIANT_ID.equals(v.variantId())) return v;
        return variantList.get(0);
    }

    /**
     * Strips trailing {@code "Model"} from a class simple name; falls back to the input on no match.
     */
    private static @NotNull String stripModelSuffix(@NotNull String simpleName) {
        return simpleName.endsWith("Model") ? simpleName.substring(0, simpleName.length() - "Model".length()) : simpleName;
    }

    /**
     * Strips the leading {@code "textures/"} segment so the texture_ref stored in the
     * runtime JSON matches the convention ({@code "cow/cow"} not
     * {@code "textures/entity/cow/cow.png"}). Idempotent on already-stripped inputs.
     */
    private static @NotNull String stripTexturesPrefix(@NotNull String path) {
        String stripped = path;
        if (stripped.startsWith("textures/")) stripped = stripped.substring("textures/".length());
        if (stripped.startsWith("entity/")) stripped = stripped.substring("entity/".length());
        if (stripped.endsWith(".png")) stripped = stripped.substring(0, stripped.length() - ".png".length());
        return stripped;
    }

    /**
     * Heuristic armor-type classification - the runtime renderer uses this to pick which armor
     * mesh to layer over the entity. Currently emits {@code "humanoid"} when the renderer's
     * overlay layer list contains {@code HumanoidArmorLayer}; otherwise {@code "none"}.
     * Phase E.5 may extend this to detect other equipment slots (saddle, leash, banner).
     */
    private static @NotNull String inferArmorType(@NotNull ConcurrentList<String> layers) {
        for (String layer : layers)
            if (layer.endsWith("HumanoidArmorLayer")) return "humanoid";
        return "none";
    }

    /**
     * Per-entity record collected from Phase A + B walks.
     */
    private record EntityRecord(
        @NotNull String rendererInternalName,
        @NotNull EntityTextureResolver.Binding binding,
        @NotNull ConcurrentList<String> layers,
        String variantStem,
        @NotNull String entityFieldName,
        float setupYawAddend,
        @Nullable Float rendererScale
    ) {}

    /**
     * Builds the diagnostic JSON document covering Phase A + B output.
     */
    private static @NotNull JsonObject buildDiagnosticJson(
        @NotNull PipelineOptions options,
        @NotNull ConcurrentList<MobRegistryDiscovery.MobEntry> mobs,
        @NotNull ConcurrentList<EntityRendererDiscovery.Registration> registrations,
        @NotNull Map<String, EntityRecord> records,
        @NotNull Set<String> mobsWithoutRenderer,
        @NotNull ConcurrentMap<String, ConcurrentList<EntityVariantResolver.Variant>> variants,
        int withPrimaryTexture,
        int variantDriven,
        int unresolvedTexture,
        @NotNull Diagnostics diagnostics
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Phase A+B diagnostic - lists living mobs from MobRegistryDiscovery and their detected renderer + model classes. Run ./gradlew :asset-renderer:entityModels to refresh.");
        root.addProperty("client_version", options.getVersion());
        root.addProperty("mobs_discovered", mobs.size());
        root.addProperty("mobs_with_renderer", records.size());
        root.addProperty("mobs_without_renderer", mobsWithoutRenderer.size());
        root.addProperty("renderer_registrations_total", registrations.size());
        root.addProperty("texture_primary_count", withPrimaryTexture);
        root.addProperty("texture_variant_driven_count", variantDriven);
        root.addProperty("texture_unresolved_count", unresolvedTexture);

        JsonObject entities = new JsonObject();
        for (Map.Entry<String, EntityRecord> entry : records.entrySet()) {
            JsonObject row = new JsonObject();
            EntityRecord rec = entry.getValue();
            row.addProperty("renderer", rec.rendererInternalName());
            EntityTextureResolver.Binding binding = rec.binding();
            if (binding.primaryTexturePath() != null) row.addProperty("texture", binding.primaryTexturePath());
            if (binding.babyTexturePath() != null) row.addProperty("baby_texture", binding.babyTexturePath());
            if (binding.isVariantDriven()) {
                row.addProperty("variant_source_class", binding.variantSourceClass());
                if (rec.variantStem() != null) row.addProperty("variant_directory", rec.variantStem());
            }
            if (binding.hierarchySource() != null) row.addProperty("binding_inherited_from", binding.hierarchySource());
            if (!rec.layers().isEmpty()) {
                JsonArray layers = new JsonArray();
                for (String layer : rec.layers()) layers.add(layer);
                row.add("overlay_layers", layers);
            }
            entities.add(entry.getKey(), row);
        }
        root.add("entities", entities);

        JsonArray unmappedList = new JsonArray();
        mobsWithoutRenderer.forEach(unmappedList::add);
        root.add("mobs_without_renderer_list", unmappedList);

        JsonObject variantTables = new JsonObject();
        for (Map.Entry<String, ConcurrentList<EntityVariantResolver.Variant>> e : variants.entrySet()) {
            JsonArray arr = new JsonArray();
            for (EntityVariantResolver.Variant v : e.getValue()) {
                JsonObject vo = new JsonObject();
                vo.addProperty("variant_id", v.variantId());
                String primary = v.primaryTexturePath();
                if (primary != null) vo.addProperty("texture", primary);
                String babyPrimary = v.primaryBabyTexturePath();
                if (babyPrimary != null) vo.addProperty("baby_texture", babyPrimary);
                if (v.textures().size() > 1) {
                    JsonObject sub = new JsonObject();
                    v.textures().forEach(sub::addProperty);
                    vo.add("textures", sub);
                }
                if (!v.babyTextures().isEmpty() && v.babyTextures().size() > 1) {
                    JsonObject sub = new JsonObject();
                    v.babyTextures().forEach(sub::addProperty);
                    vo.add("baby_textures", sub);
                }
                if (v.model() != null) vo.addProperty("model", v.model());
                arr.add(vo);
            }
            variantTables.add(e.getKey(), arr);
        }
        root.add("variant_tables", variantTables);

        JsonArray diagnosticsArr = new JsonArray();
        for (String line : diagnostics.entries()) diagnosticsArr.add(line);
        root.add("diagnostics", diagnosticsArr);

        return root;
    }

}
