package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.json.JsonNode;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.pipeline.util.ArgbHex;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Entity.BlockOverlayLayer;
import lib.minecraft.renderer.asset.Entity.BoneToggle;
import lib.minecraft.renderer.asset.Entity.EquipmentOverlay;
import lib.minecraft.renderer.asset.Entity.LargeShape;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity.TransformOp;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateX;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateY;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateZ;
import lib.minecraft.renderer.asset.Entity.TransformOp.Scale;
import lib.minecraft.renderer.asset.Entity.TransformOp.Translate;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reader for entity model definitions. Reads the model form of
 * {@code entity_models.json} (90 base-entity models) joined against {@code entity_geometry.json}
 * (the deduplicated bone trees) DIRECTLY into the {@link Entity} map the renderer consumes.
 *
 * <p>The geometry file is keyed by the same manifest factory coordinate the model baseline names
 * under {@code axes.age.options.adult.geometry} (e.g. {@code AdultWolfModel#createBodyLayer},
 * {@code PigModel#createBodyLayer@grow=0.5}), so a coordinate resolves directly. A dangling
 * coordinate fails LOUD ({@link PipelineException}).
 *
 * <p>The reader's one non-trivial expansion is <b>id-encoded variant expansion</b>: a
 * {@code variant} axis flattens to {@code minecraft:<id>_<opt>} render pseudo-ids, the default option
 * carrying the model baseline and every other option pointing back at it via {@code variant_of} for the
 * group canvas-union (baked onto {@link Entity#members()}). The model's render fields (overlays, block overlays,
 * scale, tint, bones) apply to EVERY variant row - the renderer resolves each variant row directly and
 * does not inherit through {@code variant_of}, so the baseline is copied onto each row here.
 *
 * <p>Texture paths are reduced to the runtime {@code textures/entity/}-relative sub-path the texture
 * resolver indexes on (drop the {@code minecraft:textures/entity/} prefix + {@code .png}).
 */
public final class EntityModelLoader {

    private static final @NotNull String MODELS_RESOURCE = "entity_models.json";
    private static final @NotNull String GEOMETRY_RESOURCE = "entity_geometry.json";

    private static final @NotNull String TEXTURE_PREFIX = "minecraft:textures/entity/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    /**
     * The auto-emitted depth-clearance inflate applied to same-geometry grow-less overlays (emissive
     * eyes, {@code texture_by} profession / crackiness layers) so they win the coplanar depth tie
     * against the base mesh. This is OUR artifact - vanilla submits the identical {@code ModelPart}
     * with no deformation - so a same-geometry overlay carrying at most this much inflate is excluded
     * from canvas-sizing bounds. A tinted separate-{@code LayerDefinition} overlay that merely dedupes
     * into the base mesh (sheep wool undercoat: {@code tint_by wool_color}) is NOT stamped, so the
     * tint gate excludes it.
     */
    private static final float DEPTH_CLEARANCE_INFLATE = 0.001f;

    private static final int WHITE = 0xFFFFFFFF;

    private EntityModelLoader() {}

    /**
     * Loads the bundled entity definitions with a default console diagnostics scope.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an entity
     *     references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load() {
        return load(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the entity model catalog natively from the bundled resources.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load(@NotNull Diagnostics diagnostics) {
        Optional<ResourceDocument> geometryDoc = BundledResource.read(GEOMETRY_RESOURCE, BundledResource.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        Optional<ResourceDocument> modelsDoc = BundledResource.read(MODELS_RESOURCE, BundledResource.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        if (geometryDoc.isEmpty() || modelsDoc.isEmpty()) return Concurrent.newMap();

        Map<String, EntityModelData> geometries = parseGeometries(geometryDoc.get());
        if (geometries.isEmpty()) return Concurrent.newMap();
        JsonNode models = modelsOf(modelsDoc.get());
        if (models == null) return Concurrent.newMap();

        LinkedHashMap<String, Entity> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : models.members()) {
            if (!entry.getValue().isObject()) continue;
            readDefinition(entry.getKey(), entry.getValue(), geometries, definitions, diagnostics);
        }
        attachGroupMembers(models, definitions);
        return Concurrent.adoptMap(definitions);
    }

    /**
     * Stamps each grouped entity's canvas-group membership onto its {@link Entity#members()} - the
     * self-inclusive member list the group-union fit bound iterates. Groups of size one (singletons)
     * carry no members (the empty default); only genuine groups (size &gt; 1) are rewritten. Runs on the
     * already-parsed {@code models} object, so no second resource read is needed.
     *
     * @param models the parsed {@code models} object
     * @param definitions the built definitions, mutated in place for grouped entities
     */
    private static void attachGroupMembers(@NotNull JsonNode models, @NotNull Map<String, Entity> definitions) {
        for (Map.Entry<String, List<String>> group : groupMembership(models).entrySet()) {
            if (group.getValue().size() <= 1) continue;
            Entity entity = definitions.get(group.getKey());
            if (entity == null) continue;
            definitions.put(group.getKey(), entity.toBuilder().members(group.getValue()).build());
        }
    }

    /**
     * Returns {@code entityId -> groupMembers} keyed by every native entity id, derived from
     * {@code variant_of} (variant siblings roll up to their base row) plus the cross-entity
     * {@code group_of} groupings (mooshroom -&gt; cow). Singletons return a single-element list of
     * themselves so the fold is uniform; {@link #attachGroupMembers} keeps only the genuine groups.
     *
     * @param models the parsed {@code models} object
     * @return group membership keyed by entity id
     */
    private static @NotNull Map<String, List<String>> groupMembership(@NotNull JsonNode models) {
        // Row id -> its variant_of base (null for a base / plain row) in expansion order, plus the
        // cross-entity group_of table (keyed by the model id).
        Map<String, String> variantOf = new LinkedHashMap<>();
        Map<String, String> crossGroups = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : models.members()) {
            if (!entry.getValue().isObject()) continue;
            String familyId = entry.getKey();
            JsonNode family = entry.getValue();
            JsonNode variant = variantAxis(family);
            boolean idEncoded = variant != null && variant.getBool("id_encoded", false);
            if (variant != null && idEncoded) {
                String defaultOption = variant.getString("default");
                String baseId = familyId + "_" + defaultOption;
                for (String option : variant.get("options").keys().toList()) {
                    String rowId = familyId + "_" + option;
                    variantOf.put(rowId, option.equals(defaultOption) ? null : baseId);
                }
            } else {
                // Non-variant OR option-encoded variant model: one base row. Option-encoded coats live on
                // the base definition's axes.variants and are measured by the group canvas union, not as
                // separate member rows.
                variantOf.put(familyId, null);
            }
            // group_of groups a non-variant sub-species under its base (camel_husk -> camel). A variant
            // model's group_of (mooshroom -> cow, trader_llama -> llama) is INERT at runtime in both
            // id-encoding states - id-encoded, its rows are pseudo-ids the model-id-keyed crossGroups
            // never matches; guarding it to non-variant models keeps that inertness once option-encoding
            // makes the base row a plain id, so the coat's group stays itself in both states.
            if (variant == null && family.has("group_of")) crossGroups.put(familyId, family.getString("group_of"));
        }

        Map<String, String> entityToFamily = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : variantOf.entrySet()) {
            String family = crossGroups.get(row.getKey());
            if (family == null) family = row.getValue();
            if (family == null) family = row.getKey();
            entityToFamily.put(row.getKey(), family);
        }
        Map<String, List<String>> familyToMembers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entityToFamily.entrySet())
            familyToMembers.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entityToFamily.entrySet())
            result.put(e.getKey(), List.copyOf(familyToMembers.get(e.getValue())));
        return result;
    }

    // ------------------------------------------------------------------------------------
    // model read
    // ------------------------------------------------------------------------------------

    /**
     * Reads one model into one (plain) or many (id-encoded variant) {@link Entity} rows,
     * adding each to {@code definitions}.
     */
    private static void readDefinition(
        @NotNull String familyId,
        @NotNull JsonNode family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, Entity> definitions,
        @NotNull Diagnostics diagnostics
    ) {
        // The family baseline (primary geometry + adult texture) lives under the
        // mandatory age axis' options.adult, not at top level.
        JsonNode adult = adultOption(family);
        String baseCoord = adult.getString("geometry");

        JsonNode render = family.findObject("render").orElse(null);
        float rendererScale = render == null ? 1f : render.getFloat("scale", 1f);
        float setupYawAddend = render == null ? 0f : render.getFloat("yaw_addend", 0f);
        int baseTint = render == null ? WHITE : render.findString("tint").map(t -> ArgbHex.parse(t, diagnostics)).orElse(WHITE);

        JsonNode bones = family.findObject("bones").orElse(null);
        JsonNode hiddenBones = bones == null ? null : bones.findArray("hidden").orElse(null);
        JsonNode boneToggleSpecs = bones == null ? null : bones.findObject("toggles").orElse(null);

        JsonNode familyOverlays = family.findArray("overlays").orElse(JsonNode.array());
        List<BlockOverlayLayer> blockOverlays = family.findArray("block_overlays")
            .map(EntityModelLoader::loadBlockOverlays).orElse(List.of());

        Optional<String> collarTexture = collarTextureOf(family);
        List<EquipmentOverlay> equipment = loadEquipment(family, geometries, familyId, diagnostics);
        boolean markings = markingsOf(family);
        boolean humanoidArmor = humanoidArmorOf(family);
        String babyCoord = babyGeometryOf(family);
        Optional<EntityModelData> babyModel = babyCoord == null ? Optional.empty() : Optional.ofNullable(geometries.get(babyCoord));

        JsonNode variant = variantAxis(family);
        if (variant != null) {
            boolean idEncoded = variant.getBool("id_encoded", false);
            String defaultOption = variant.getString("default");
            JsonNode options = variant.get("options");
            VariantContext ctx = new VariantContext(baseCoord, geometries, hiddenBones, boneToggleSpecs, familyOverlays,
                blockOverlays, baseTint, setupYawAddend, rendererScale, babyModel, collarTexture, equipment, markings, humanoidArmor);
            if (idEncoded) {
                // id-encoded: each coat is a first-class render pseudo-id minecraft:<id>_<opt>.
                for (Map.Entry<String, JsonNode> option : options.members()) {
                    String rowId = familyId + "_" + option.getKey();
                    definitions.put(rowId, buildVariantRow(rowId, option.getValue(), ctx, diagnostics));
                }
                return;
            }
            // option-encoded variant: one base row minecraft:<id>, the coat resolved at render from
            // EntityAppearance.variant. Every option is built into a sub-definition; the base row IS the
            // default coat carrying the full option map so the resolver fold + family canvas union reach
            // every coat.
            LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> option : options.members())
                coats.put(option.getKey(), buildVariantRow(familyId, option.getValue(), ctx, diagnostics));
            Entity base = coats.getOrDefault(defaultOption, coats.values().iterator().next());
            Entity.Axes baseAxes = base.axes();
            definitions.put(familyId, base.toBuilder()
                .axes(new Entity.Axes(baseAxes.stateTextures(), baseAxes.babyModel(), baseAxes.largeShape(),
                    baseAxes.sizeModels(), baseAxes.sizeScales(), Map.copyOf(coats)))
                .build());
            return;
        }

        // Plain family: one row. The size / shape axes attach only to plain families (no family carries
        // both a variant axis and a size / shape axis), so they are resolved here.
        EntityModelData model = resolveModel(geometries, baseCoord, familyId);
        Map<String, BoneToggle> toggles = loadBoneToggles(boneToggleSpecs, model, familyId, diagnostics);
        model = applyHiddenBones(model, hiddenBones, familyId, diagnostics);
        List<OverlayLayer> overlays = loadOverlays(familyOverlays, geometries, baseCoord, model, familyId, diagnostics);
        Optional<String> textureRef = adult.findString("texture").map(EntityModelLoader::stripEntity);

        Map<String, String> stateTextures = new LinkedHashMap<>();
        // Plain families carry their single baby texture on age.baby.texture; expose it under the "baby"
        // state key so the renderer binds it the same way as variant families' per-option baby_texture.
        String babyTexture = babyTextureOf(family);
        if (babyTexture != null) stateTextures.put("baby", babyTexture);

        definitions.put(familyId, Entity.builder()
            .id(ResourceId.parse(familyId))
            .model(model).textureRef(textureRef).overlays(overlays).blockOverlays(blockOverlays)
            .baseTintArgb(baseTint).setupYawAddend(setupYawAddend).rendererScale(rendererScale)
            .boneToggles(toggles)
            .axes(new Entity.Axes(stateTextures, babyModel,
                buildLargeShape(family, geometries, familyId, diagnostics), buildSizeModels(family, geometries), buildSizeScales(family), Map.of()))
            .layers(new Entity.Layers(collarTexture, equipment, markings, humanoidArmor))
            .build());
    }

    /**
     * The family-level render context shared by every variant option's build, so one coat build serves
     * both the id-encoded pseudo-id expansion and the option-encoded sub-definition map.
     */
    private record VariantContext(
        @NotNull String baseCoord,
        @NotNull Map<String, EntityModelData> geometries,
        @Nullable JsonNode hiddenBones,
        @Nullable JsonNode boneToggleSpecs,
        @NotNull JsonNode familyOverlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        int baseTint,
        float setupYawAddend,
        float rendererScale,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull Optional<String> collarTexture,
        @NotNull List<EquipmentOverlay> equipment,
        boolean markings,
        boolean humanoidArmor
    ) {}

    /**
     * Builds one variant option's {@link Entity}: the option's geometry (its own coordinate when
     * it overrides the family mesh, else the base coordinate) with the family's bone toggles, hidden-bone
     * strip, and overlays materialised on it, plus the option's {@code wild} coat texture and per-state
     * textures. The built definition carries an empty {@code axes.variants} - it is a leaf coat, whether it
     * lands under an id-encoded pseudo-id or in an option-encoded family's coat map.
     */
    private static @NotNull Entity buildVariantRow(
        @NotNull String rowId,
        @NotNull JsonNode optionObj,
        @NotNull VariantContext ctx,
        @NotNull Diagnostics diagnostics
    ) {
        String rowCoord = optionObj.getString("geometry", ctx.baseCoord());
        EntityModelData model = resolveModel(ctx.geometries(), rowCoord, rowId);
        Map<String, BoneToggle> toggles = loadBoneToggles(ctx.boneToggleSpecs(), model, rowId, diagnostics);
        model = applyHiddenBones(model, ctx.hiddenBones(), rowId, diagnostics);
        List<OverlayLayer> overlays = loadOverlays(ctx.familyOverlays(), ctx.geometries(), rowCoord, model, rowId, diagnostics);
        Map<String, String> stateTextures = variantStateTextures(optionObj);
        Optional<String> textureRef = variantWildTexture(optionObj);
        return Entity.builder()
            .id(ResourceId.parse(rowId))
            .model(model).textureRef(textureRef).overlays(overlays).blockOverlays(ctx.blockOverlays())
            .baseTintArgb(ctx.baseTint()).setupYawAddend(ctx.setupYawAddend()).rendererScale(ctx.rendererScale())
            .boneToggles(toggles)
            .axes(new Entity.Axes(stateTextures, ctx.babyModel(), Optional.empty(), Map.of(), Map.of(), Map.of()))
            .layers(new Entity.Layers(ctx.collarTexture(), ctx.equipment(), ctx.markings(), ctx.humanoidArmor()))
            .build();
    }

    // ------------------------------------------------------------------------------------
    // overlays
    // ------------------------------------------------------------------------------------

    /**
     * Resolves an {@code overlays} array into {@link OverlayLayer}s. An overlay without a
     * {@code geometry} member (or one naming the base coordinate) inherits the post-hidden-strip base
     * mesh so its cubes co-register with the base; a distinct coordinate resolves fresh from the
     * geometry table (a missing coordinate warns and drops). {@code retain_bones} restricts the mesh
     * to a vanilla {@code retainExactParts} subset before inflate; {@code grow} inflates every cube.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull JsonNode overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String baseCoord,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        List<OverlayLayer> out = new ArrayList<>();
        for (JsonNode entry : overlays.elements()) {
            if (!entry.isObject()) continue;
            String coord = entry.getString("geometry", baseCoord);
            boolean sameGeometry = coord.equals(baseCoord);
            EntityModelData overlayModel;
            if (sameGeometry) {
                overlayModel = baseModel;
            } else {
                overlayModel = geometries.get(coord);
                if (overlayModel == null) {
                    diagnostics.warn("entity '%s' overlay references geometry '%s' absent from entity_geometry", entityId, coord);
                    continue;
                }
            }
            Optional<String> overlayTexture = entry.findString("texture").map(EntityModelLoader::stripEntity);
            // retain_bones (warden pulsating spots) restricts the overlay to a vanilla retainExactParts
            // subset of the shared mesh, so the glow texture draws only where vanilla's subset
            // LayerDefinition does. Applied before inflate so surviving cubes inflate together.
            EntityModelData retained = entry.findArray("retain_bones")
                .map(rb -> retainExactParts(overlayModel, rb))
                .orElse(overlayModel);
            boolean hasTint = entry.has("tint");
            boolean hasTintBy = entry.has("tint_by");
            // inflate: an explicit grow (real vanilla CubeDeformation - tropical_fish 0.008, llama carpet
            // 0.5) wins; else a same-mesh grow-less overlay that RE-SUBMITS the base geometry with no
            // colour tint gets the depth-clearance inflate (emissive eyes, texture_by profession /
            // crackiness); every other overlay carries none.
            float inflate = entry.has("grow") ? growScalar(entry.get("grow"))
                : sameGeometry && !hasTint && !hasTintBy ? DEPTH_CLEARANCE_INFLATE : 0f;
            EntityModelData materialised = inflate != 0f ? inflateModel(retained, inflate) : retained;
            JsonNode pipeline = entry.findObject("pipeline").orElse(null);
            boolean emissive = pipeline != null && pipeline.getBool("emissive", false);
            int overlayTint = hasTint ? ArgbHex.parse(entry.getString("tint"), diagnostics) : WHITE;
            // Same-geometry overlays carrying ONLY the auto-emitted depth-clearance inflate are excluded
            // from the canvas-sizing bounds: they render the IDENTICAL cube tree as the base (vanilla
            // submits the same ModelPart through a second render type with NO inflate), so the base
            // already contributes their full silhouette extent. A LARGER inflate is a real vanilla
            // CubeDeformation vanilla's own bounds walk includes, so it keeps contributing. An explicit
            // skip_bounds (llama carpet, NO_RENDER_LAYER_SUFFIXES) always wins.
            boolean depthClearanceOnly = sameGeometry && inflate <= DEPTH_CLEARANCE_INFLATE;
            boolean skipBounds = entry.getBool("skip_bounds", false) || depthClearanceOnly;
            Optional<String> tintBy = entry.findString("tint_by");
            Optional<String> textureBy = entry.findString("texture_by");
            // The overlay's render condition, parsed straight from its `when` object into the typed
            // AppearanceGate (flag/charged/tinted). Absent -> unconditional.
            Optional<AppearanceGate> gate = parseOverlayGate(entry.findObject("when").orElse(null), tintBy);
            // blend / alpha (default NORMAL / 1.0). `additive` -> the energy-swirl glow; `translucent` /
            // `normal` -> source-over (the slime shell's translucency lives in its texture alpha, not a
            // blend-function difference). An un-annotated overlay keeps the NORMAL / 1.0 default.
            BlendMode blend = parseBlend(pipeline == null ? null : pipeline.getString("blend"), diagnostics);
            float alpha = pipeline == null ? 1f : pipeline.getFloat("alpha", 1f);
            out.add(new OverlayLayer(materialised, overlayTexture, emissive, overlayTint, skipBounds, tintBy, textureBy, blend, alpha, gate));
        }
        return out;
    }

    /**
     * Parses an overlay's {@code when} object into a typed {@link AppearanceGate}: {@code flag} maps
     * to {@link AppearanceGate.FlagGate}, {@code charged} to {@link AppearanceGate.ChargedGate}, and
     * {@code tinted} to {@link AppearanceGate.TintedGate} (carrying the overlay's tint axis token so the
     * gate is self-contained). Absent or unrecognised yields empty (unconditional).
     *
     * @param when the overlay's {@code when} object, or {@code null} when absent
     * @param tintBy the overlay's tint axis token, used to seed a {@link AppearanceGate.TintedGate}
     * @return the parsed gate, or empty when unconditional
     */
    private static @NotNull Optional<AppearanceGate> parseOverlayGate(@Nullable JsonNode when, @NotNull Optional<String> tintBy) {
        if (when == null) return Optional.empty();
        if (when.has("flag"))
            return Optional.of(new AppearanceGate.FlagGate(when.getString("flag", ""), when.getBool("value", false)));
        if (when.getBool("charged", false))
            return Optional.of(new AppearanceGate.ChargedGate());
        if (when.getBool("tinted", false))
            return Optional.of(new AppearanceGate.TintedGate(tintBy.orElse("")));
        return Optional.empty();
    }

    /**
     * Parses an overlay's optional {@code blend} node into a {@link BlendMode}. {@code "additive"} maps
     * to {@link BlendMode#ADD}; {@code "translucent"}, {@code "normal"}, and an absent node all map to
     * {@link BlendMode#NORMAL} source-over. An unrecognised value warns and falls back to
     * {@link BlendMode#NORMAL}.
     */
    private static @NotNull BlendMode parseBlend(@Nullable String blend, @NotNull Diagnostics diagnostics) {
        if (blend == null) return BlendMode.NORMAL;
        return switch (blend.toLowerCase(Locale.ROOT)) {
            case "additive" -> BlendMode.ADD;
            case "translucent", "normal" -> BlendMode.NORMAL;
            default -> {
                diagnostics.warn("unknown overlay blend '%s' (expected normal/additive/translucent); using normal", blend);
                yield BlendMode.NORMAL;
            }
        };
    }

    // ------------------------------------------------------------------------------------
    // block overlays
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a {@code block_overlays} array into {@link BlockOverlayLayer} rows. A fixed row names
     * its {@code block}; a {@code selectable} row's block is supplied at render from the carried
     * selection, so its {@code block} may be omitted entirely (the enderman carried block). The
     * {@code transforms} entries are the tagged op objects the renderer pattern-matches.
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull JsonNode array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (JsonNode row : array.elements()) {
            if (!row.isObject()) continue;
            boolean selectable = row.getBool("selectable", false);
            if (!row.has("block") && !selectable) continue;
            String blockId = row.getString("block", "");
            String attachedBone = row.getString("attached_bone");
            List<TransformOp> ops = new ArrayList<>();
            for (JsonNode opObj : row.findArray("transforms").orElse(JsonNode.array()).elements()) {
                if (!opObj.isObject()) continue;
                switch (opObj.getString("op", "")) {
                    case "translate" -> ops.add(new Translate(opObj.getFloat("x", 0f), opObj.getFloat("y", 0f), opObj.getFloat("z", 0f)));
                    case "rotate_y" -> ops.add(new RotateY(opObj.getFloat("degrees", 0f)));
                    case "rotate_x" -> ops.add(new RotateX(opObj.getFloat("degrees", 0f)));
                    case "rotate_z" -> ops.add(new RotateZ(opObj.getFloat("degrees", 0f)));
                    case "scale" -> ops.add(new Scale(opObj.getFloat("x", 0f), opObj.getFloat("y", 0f), opObj.getFloat("z", 0f)));
                    default -> { }
                }
            }
            out.add(new BlockOverlayLayer(blockId, attachedBone, List.copyOf(ops), selectable));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------
    // axes + layers
    // ------------------------------------------------------------------------------------

    /**
     * Records a variant option's per-state textures ({@code wild} / {@code tame} / {@code angry} +
     * the per-option {@code baby_texture} under {@code "baby"}) when the option carries more than one -
     * a genuine multi-state family (wolf) or an ageable variant (cow). A single-texture option leaves
     * the map empty; the base {@code texture_ref} is the {@code wild} entry either way.
     */
    private static @NotNull Map<String, String> variantStateTextures(@NotNull JsonNode optionObj) {
        Map<String, String> states = new LinkedHashMap<>();
        optionObj.findObject("textures").ifPresent(textures -> {
            for (Map.Entry<String, JsonNode> texture : textures.members())
                states.put(texture.getKey(), stripEntity(textures.getString(texture.getKey())));
        });
        if (optionObj.has("baby_texture")) states.put("baby", stripEntity(optionObj.getString("baby_texture")));
        return states.size() > 1 ? states : Map.of();
    }

    /**
     * Returns a variant option's {@code textures.wild} as the base texture ref, or empty when absent.
     */
    private static @NotNull Optional<String> variantWildTexture(@NotNull JsonNode optionObj) {
        return optionObj.findObject("textures").flatMap(t -> t.findString("wild")).map(EntityModelLoader::stripEntity);
    }

    /**
     * Returns the mandatory age axis' {@code options.adult} body - the family baseline (primary
     * {@code geometry}, and for non-variant families the adult {@code texture}).
     */
    private static @NotNull JsonNode adultOption(@NotNull JsonNode family) {
        return family.get("axes").get("age").get("options").get("adult");
    }

    /** Returns the {@code axes.variant} object when the family carries an id-encoded variant axis. */
    private static @Nullable JsonNode variantAxis(@NotNull JsonNode family) {
        JsonNode axes = family.get("axes");
        return axes == null ? null : axes.findObject("variant").orElse(null);
    }

    /** Returns the {@code age.baby} option object, or {@code null} when the family has no age axis. */
    private static @Nullable JsonNode ageBaby(@NotNull JsonNode family) {
        JsonNode axes = family.get("axes");
        if (axes == null || !axes.has("age")) return null;
        return axes.get("age").get("options").findObject("baby").orElse(null);
    }

    /** Returns the family's baby geometry coordinate from its {@code age} axis, or {@code null}. */
    private static @Nullable String babyGeometryOf(@NotNull JsonNode family) {
        JsonNode baby = ageBaby(family);
        return baby == null ? null : baby.getString("geometry");
    }

    /** Returns the family's single stripped baby texture from {@code age.baby.texture}, or {@code null}. */
    private static @Nullable String babyTextureOf(@NotNull JsonNode family) {
        JsonNode baby = ageBaby(family);
        return baby == null ? null : baby.findString("texture").map(EntityModelLoader::stripEntity).orElse(null);
    }

    /** Returns the dyed-collar layer's stripped texture, or empty when the family has no collar layer. */
    private static @NotNull Optional<String> collarTextureOf(@NotNull JsonNode family) {
        for (JsonNode layer : family.findArray("layers").orElse(JsonNode.array()).elements()) {
            if (!"collar".equals(layer.getString("id"))) continue;
            Optional<String> texture = layer.findObject("overlay").flatMap(o -> o.findString("texture"));
            if (texture.isPresent()) return texture.map(EntityModelLoader::stripEntity);
        }
        return Optional.empty();
    }

    /** Returns whether the family carries a {@code markings} layer (the horse marking overlay). */
    private static boolean markingsOf(@NotNull JsonNode family) {
        for (JsonNode layer : family.findArray("layers").orElse(JsonNode.array()).elements())
            if ("markings".equals(layer.getString("id"))) return true;
        return false;
    }

    /**
     * Returns whether the family carries a {@code humanoid} armor classification row - the
     * {@code layers} armor row EntityLayersResolver emits off a {@code HumanoidArmorLayer} site.
     * Absence IS {@code none} (the classification is derived off the roster, not a required member).
     */
    private static boolean humanoidArmorOf(@NotNull JsonNode family) {
        for (JsonNode layer : family.findArray("layers").orElse(JsonNode.array()).elements())
            if ("humanoid".equals(layer.getString("armor_type"))) return true;
        return false;
    }

    /**
     * Resolves the family's {@code when.equipment}-gated layers into {@link EquipmentOverlay}s, binding
     * each overlay's {@code geometry} coordinate to its baked mesh. The {@code texture_template} and
     * {@code default_material} are the already-relative equipment sub-path forms the renderer
     * substitutes {@code <material>} into; a layer naming an unknown geometry warns and drops.
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @NotNull JsonNode family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        List<EquipmentOverlay> out = new ArrayList<>();
        for (JsonNode layer : family.findArray("layers").orElse(JsonNode.array()).elements()) {
            if (!layer.has("when") || !layer.has("overlay")) continue;
            JsonNode when = layer.get("when");
            if (!when.has("equipment")) continue;
            JsonNode overlay = layer.get("overlay");
            if (!overlay.has("geometry") || !overlay.has("texture_template") || !overlay.has("default_material")) continue;
            String coord = overlay.getString("geometry");
            EntityModelData model = geometries.get(coord);
            if (model == null) {
                diagnostics.warn("entity '%s' equipment layer references geometry '%s' absent from entity_geometry", entityId, coord);
                continue;
            }
            out.add(new EquipmentOverlay(when.getString("equipment"), model,
                overlay.getString("texture_template"), overlay.getString("default_material")));
        }
        return List.copyOf(out);
    }

    /**
     * Resolves the family's {@code shape.large} option (tropical fish) into a {@link LargeShape}: the
     * large body mesh, its stripped base texture, and the pattern overlays materialised on the large
     * geometry. Empty when the family has no shape axis or its large geometry is missing.
     */
    private static @NotNull Optional<LargeShape> buildLargeShape(
        @NotNull JsonNode family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        JsonNode axes = family.get("axes");
        if (axes == null || !axes.has("shape")) return Optional.empty();
        JsonNode options = axes.get("shape").get("options");
        JsonNode large = options == null ? null : options.findObject("large").orElse(null);
        if (large == null || !large.has("geometry")) return Optional.empty();
        String coord = large.getString("geometry");
        EntityModelData model = geometries.get(coord);
        if (model == null) return Optional.empty();
        JsonNode largeOverlays = large.findArray("overlays").orElse(JsonNode.array());
        List<OverlayLayer> overlays = loadOverlays(largeOverlays, geometries, coord, model, entityId, diagnostics);
        Optional<String> textureRef = large.findString("texture").map(EntityModelLoader::stripEntity).or(() -> Optional.of(""));
        return Optional.of(new LargeShape(model, textureRef, overlays));
    }

    /**
     * Resolves the family's {@code size} axis geometry alternatives (pufferfish small / medium) into
     * {@code Size -> mesh}. Options carrying a {@code scale} (not a {@code geometry}) are skipped; the
     * default size is the base mesh and never appears here.
     */
    private static @NotNull Map<Size, EntityModelData> buildSizeModels(@NotNull JsonNode family, @NotNull Map<String, EntityModelData> geometries) {
        JsonNode options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> option : options.members()) {
            JsonNode body = option.getValue();
            if (!body.has("geometry")) continue;
            EntityModelData mesh = geometries.get(body.getString("geometry"));
            if (mesh != null) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), mesh);
        }
        return out;
    }

    /**
     * Resolves the family's {@code size} axis scale alternatives (salmon / slime / magma_cube) into
     * {@code Size -> factor}. The default size is scale {@code 1.0} and never appears here.
     */
    private static @NotNull Map<Size, Float> buildSizeScales(@NotNull JsonNode family) {
        JsonNode options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> option : options.members()) {
            JsonNode body = option.getValue();
            if (body.has("scale")) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), body.getFloat("scale", 0f));
        }
        return out;
    }

    /** Returns the family's {@code axes.size.options} object, or {@code null} when it has no size axis. */
    private static @Nullable JsonNode sizeOptions(@NotNull JsonNode family) {
        JsonNode axes = family.get("axes");
        if (axes == null || !axes.has("size")) return null;
        return axes.get("size").get("options");
    }

    // ------------------------------------------------------------------------------------
    // bones + geometry surgery
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a family / variant geometry coordinate against the parsed geometry table, failing LOUD
     * on a dangling coordinate.
     */
    private static @NotNull EntityModelData resolveModel(@NotNull Map<String, EntityModelData> geometries, @NotNull String coord, @NotNull String entityId) {
        EntityModelData model = geometries.get(coord);
        if (model == null)
            throw new PipelineException("Entity '%s' references geometry '%s' which is absent from entity_geometry", entityId, coord);
        return model;
    }

    /**
     * Resolves a {@code bones.toggles} object into {@code toggle -> }{@link BoneToggle}, pulling each
     * named bone's {@link EntityModelData.Bone} from the FULL geometry BEFORE the {@code hidden}
     * strip - so a default-hidden toggle's bones are still present for the resolver to re-add
     * (donkey / mule / llama chest). A named bone absent from the geometry warns and drops; a toggle
     * left with no resolvable bones is omitted.
     */
    private static @NotNull Map<String, BoneToggle> loadBoneToggles(
        @Nullable JsonNode toggles,
        @NotNull EntityModelData fullModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (toggles == null) return Map.of();
        Map<String, BoneToggle> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : toggles.members()) {
            JsonNode spec = entry.getValue();
            if (!spec.isObject()) continue;
            Optional<JsonNode> boneArray = spec.findArray("bones");
            if (boneArray.isEmpty()) continue;
            boolean defaultVisible = spec.getBool("default", false);
            LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
            for (JsonNode element : boneArray.get().elements()) {
                Optional<String> name = element.stringValue();
                if (name.isEmpty()) continue;
                String boneName = name.get();
                EntityModelData.Bone bone = fullModel.getBones().get(boneName);
                if (bone == null) {
                    diagnostics.warn("entity '%s' bone_toggles '%s' names bone '%s' which is not on the geometry", entityId, entry.getKey(), boneName);
                    continue;
                }
                bones.put(boneName, bone);
            }
            if (!bones.isEmpty()) out.put(entry.getKey(), new BoneToggle(bones, defaultVisible));
        }
        return out;
    }

    /**
     * Returns a copy of {@code model} with the {@code hidden} bones stripped, or {@code model} verbatim
     * when there are none. The Java geometries pack every optional render target into one tree and gate
     * them by entity state at render; the static renderer hides the unwanted ones through this list.
     * A named bone absent from the geometry warns.
     */
    private static @NotNull EntityModelData applyHiddenBones(
        @NotNull EntityModelData model,
        @Nullable JsonNode hiddenBones,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (hiddenBones == null) return model;
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(model.getBones());
        for (JsonNode el : hiddenBones.elements()) {
            Optional<String> n = el.stringValue();
            if (n.isEmpty()) continue;
            String name = n.get();
            if (bones.remove(name) == null)
                diagnostics.warn("entity '%s' hidden_bones names bone '%s' which is not on the geometry", entityId, name);
        }
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * Restricts an overlay model to the vanilla {@code retainExactParts} subset named by
     * {@code retainBones}: a bone keeps its cubes iff it is named AND no ancestor is (vanilla's
     * {@code clearRecursively} empties a retained part's descendant subtree). Every other bone is kept
     * as a pose-only node so the transform hierarchy stays intact.
     */
    private static @NotNull EntityModelData retainExactParts(@NotNull EntityModelData source, @NotNull JsonNode retainBones) {
        Set<String> retain = new LinkedHashSet<>();
        for (JsonNode el : retainBones.elements())
            el.stringValue().ifPresent(retain::add);
        Map<String, EntityModelData.Bone> bones = source.getBones();
        LinkedHashMap<String, EntityModelData.Bone> out = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : bones.entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            boolean keepCubes = retain.contains(e.getKey()) && !hasAncestorInSet(bones, bone, retain);
            if (keepCubes || bone.getCubes().isEmpty()) {
                out.put(e.getKey(), bone);
            } else {
                out.put(e.getKey(), new EntityModelData.Bone(
                    bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                    bone.getScale(), Concurrent.adoptList(new ArrayList<>()), bone.getParent()));
            }
        }
        return new EntityModelData(source.getTextureSize(), source.getInventoryYRotation(), Concurrent.adoptLinkedMap(out), source.isCull());
    }

    /** Reports whether any proper ancestor of {@code bone} is named in {@code retain}. */
    private static boolean hasAncestorInSet(@NotNull Map<String, EntityModelData.Bone> bones, @NotNull EntityModelData.Bone bone, @NotNull Set<String> retain) {
        for (String parent = bone.getParent(); parent != null; ) {
            if (retain.contains(parent)) return true;
            EntityModelData.Bone p = bones.get(parent);
            if (p == null) return false;
            parent = p.getParent();
        }
        return false;
    }

    /**
     * Reads an overlay {@code grow} value defensively - a scalar returns as-is; an {@code [x, y, z]}
     * array (an asymmetric grow, absent from 26.1) returns its largest component so the depth-clearance
     * bump never decode-throws. The bump is applied per-axis by {@link #inflateModel}.
     */
    private static float growScalar(@NotNull JsonNode grow) {
        if (!grow.isArray()) return grow.floatValue(0f);
        float max = 0f;
        for (JsonNode axis : grow.elements()) max = Math.max(max, axis.floatValue(0f));
        return max;
    }

    /**
     * Returns a deep-cloned copy of {@code model} with every cube's grow bumped by {@code delta} on
     * every axis - surrounding the base mesh with the inflated overlay instead of z-fighting it. Bones,
     * pivots, rotations, UVs, and parent links are preserved verbatim.
     */
    private static @NotNull EntityModelData inflateModel(@NotNull EntityModelData source, float delta) {
        LinkedHashMap<String, EntityModelData.Bone> inflated = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : source.getBones().entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            ArrayList<EntityModelData.Cube> cubes = new ArrayList<>(bone.getCubes().size());
            for (EntityModelData.Cube cube : bone.getCubes()) {
                Vector3f grow = cube.getGrow();
                cubes.add(new EntityModelData.Cube(
                    cube.getOrigin(), cube.getSize(), cube.getUv(),
                    new Vector3f(grow.x() + delta, grow.y() + delta, grow.z() + delta), cube.isMirror(),
                    cube.getPivot(), cube.getRotation(), cube.getFaceUv()));
            }
            inflated.put(e.getKey(), new EntityModelData.Bone(
                bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                bone.getScale(), Concurrent.adoptList(cubes), bone.getParent()));
        }
        return new EntityModelData(source.getTextureSize(), source.getInventoryYRotation(), Concurrent.adoptLinkedMap(inflated), source.isCull());
    }

    // ------------------------------------------------------------------------------------
    // resource + text helpers
    // ------------------------------------------------------------------------------------

    /** Reads the {@code geometries} coordinate map straight into {@link EntityModelData} values. */
    private static @NotNull Map<String, EntityModelData> parseGeometries(@NotNull ResourceDocument geometryDoc) {
        Map<String, EntityModelData> geometries = geometryDoc.as(EntityGeometryFile.class).geometries();
        return geometries == null ? Map.of() : geometries;
    }

    /** The {@code entity_geometry.json} payload: geometry coordinate to its bone tree. */
    private record EntityGeometryFile(@NotNull Map<String, EntityModelData> geometries) {}

    /** Returns the {@code models} object of a parsed models document, or {@code null} when absent. */
    private static @Nullable JsonNode modelsOf(@NotNull ResourceDocument modelsDoc) {
        return modelsDoc.payload().findObject("models").orElse(null);
    }

    /**
     * Reduces a full entity texture path to the {@code textures/entity/}-relative sub-path the
     * texture resolver re-qualifies as {@code minecraft:entity/<ref>} - dropping the
     * {@code minecraft:textures/entity/} prefix and the {@code .png} suffix.
     */
    private static @NotNull String stripEntity(@NotNull String path) {
        if (!path.startsWith(TEXTURE_PREFIX) || !path.endsWith(TEXTURE_SUFFIX))
            throw new PipelineException("Unexpected entity texture path '%s' (expected '%s<sub>%s')", path, TEXTURE_PREFIX, TEXTURE_SUFFIX);
        return path.substring(TEXTURE_PREFIX.length(), path.length() - TEXTURE_SUFFIX.length());
    }
}
