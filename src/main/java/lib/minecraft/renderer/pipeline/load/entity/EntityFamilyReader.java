package lib.minecraft.renderer.pipeline.load.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.pipeline.load.ArgbHex;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Geometry;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.Entity.BlockOverlayLayer;
import lib.minecraft.renderer.asset.Entity.BoneToggle;
import lib.minecraft.renderer.asset.Entity.EquipmentOverlay;
import lib.minecraft.renderer.asset.Entity.LargeShape;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity.RotateX;
import lib.minecraft.renderer.asset.Entity.RotateY;
import lib.minecraft.renderer.asset.Entity.RotateZ;
import lib.minecraft.renderer.asset.Entity.Scale;
import lib.minecraft.renderer.asset.Entity.TransformOp;
import lib.minecraft.renderer.asset.Entity.Translate;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
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
 * The native v2 reader for entity model definitions: the flattener inverse. Reads the family form of
 * {@code v2/entity_models.json} (90 base-entity families) joined against {@code v2/entity_geometry.json}
 * (the deduplicated bone trees) DIRECTLY into the {@link Entity} map the renderer consumes -
 * with no legacy flat-row intermediate, no eight parallel side-channel maps, and no
 * {@code CARRIED_FIELDS} lock-step.
 *
 * <p>The v2 geometry file is keyed by the same manifest factory coordinate the family baseline names
 * under {@code axes.age.options.adult.geometry} (e.g. {@code AdultWolfModel#createBodyLayer},
 * {@code PigModel#createBodyLayer@grow=0.5}), so a coordinate resolves DIRECTLY - the bridge's
 * {@code geometry.<stem>} legacy-id replay is a bridge fiction the native path never mints. A dangling
 * coordinate fails LOUD ({@link PipelineException}), matching the historic {@code EntityModelLoader}
 * contract.
 *
 * <p>The one surviving concept from the flattener is <b>id-encoded variant expansion</b>: a
 * {@code variant} axis flattens to {@code minecraft:<id>_<opt>} render pseudo-ids, the default option
 * carrying the family baseline and every other option pointing back at it via {@code variant_of} for the
 * family canvas-union ({@link #loadFamilies}). The family's render fields (overlays, block overlays,
 * scale, tint, bones) apply to EVERY variant row - the renderer resolves each variant row directly and
 * does not inherit through {@code variant_of}, so the baseline is copied onto each row here just as the
 * flattener copied it.
 *
 * <p>Texture paths are reduced to the runtime {@code textures/entity/}-relative sub-path the texture
 * resolver indexes on (drop the {@code minecraft:textures/entity/} prefix + {@code .png}), matching
 * {@code EntityModelsBridge.strip} so the native render is byte-identical.
 */
public final class EntityFamilyReader {

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
     * tint gate excludes it - matching the legacy {@code EntityRuntimeJsonWriter} stamp the native
     * read reproduces. Mirrors {@code EntityModelLoader.DEPTH_CLEARANCE_INFLATE}.
     */
    private static final float DEPTH_CLEARANCE_INFLATE = 0.001f;

    private static final int WHITE = 0xFFFFFFFF;

    private EntityFamilyReader() {}

    /**
     * Reads the entity model catalog natively from the v2 resources.
     *
     * @param diagnostics the scope envelope and read warnings are recorded to
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException if a resource is malformed, or an entity references a geometry
     *     coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> load(@NotNull Diagnostics diagnostics) {
        Optional<V2Document> geometryDoc = V2Resources.read(GEOMETRY_RESOURCE, V2Resources.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        Optional<V2Document> modelsDoc = V2Resources.read(MODELS_RESOURCE, V2Resources.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        if (geometryDoc.isEmpty() || modelsDoc.isEmpty()) return Concurrent.newMap();

        Map<String, EntityModelData> geometries = parseGeometries(geometryDoc.get());
        if (geometries.isEmpty()) return Concurrent.newMap();
        JsonObject families = familiesOf(modelsDoc.get());
        if (families == null) return Concurrent.newMap();

        LinkedHashMap<String, Entity> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            readFamily(entry.getKey(), entry.getValue().getAsJsonObject(), geometries, definitions, diagnostics);
        }
        return Concurrent.adoptMap(definitions);
    }

    /**
     * Returns {@code entityId -> familyMembers} keyed by every native entity id, derived from
     * {@code variant_of} (variant siblings roll up to their base row) plus the cross-entity
     * {@code family_of} groupings (mooshroom -&gt; cow). Singletons return a single-element list of
     * themselves so callers iterate uniformly. Reproduces {@code EntityModelLoader.loadFamilies}'s
     * two-pass fold on the natively-expanded rows.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return family membership keyed by entity id (empty when the models resource is absent)
     */
    public static @NotNull Map<String, List<String>> loadFamilies(@NotNull Diagnostics diagnostics) {
        Optional<V2Document> modelsDoc = V2Resources.read(MODELS_RESOURCE, V2Resources.MissingPolicy.GRACEFUL_EMPTY, diagnostics);
        if (modelsDoc.isEmpty()) return Map.of();
        JsonObject families = familiesOf(modelsDoc.get());
        if (families == null) return Map.of();

        // Row id -> its variant_of base (null for a base / plain row) in expansion order, plus the
        // cross-entity family_of table (keyed by the FAMILY id, as the flattener emitted it).
        Map<String, String> variantOf = new LinkedHashMap<>();
        Map<String, String> crossFamilies = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            String familyId = entry.getKey();
            JsonObject family = entry.getValue().getAsJsonObject();
            JsonObject variant = variantAxis(family);
            boolean idEncoded = variant != null && variant.has("id_encoded") && variant.get("id_encoded").getAsBoolean();
            if (variant != null && idEncoded) {
                String defaultOption = variant.get("default").getAsString();
                String baseId = familyId + "_" + defaultOption;
                for (String option : variant.getAsJsonObject("options").keySet()) {
                    String rowId = familyId + "_" + option;
                    variantOf.put(rowId, option.equals(defaultOption) ? null : baseId);
                }
            } else {
                // Non-variant OR option-encoded variant family: one base row. Option-encoded coats live on
                // the base definition's axes.variants and are measured by the family canvas union, not as
                // separate member rows.
                variantOf.put(familyId, null);
            }
            // family_of groups a non-variant sub-species under its base (camel_husk -> camel). A variant
            // family's family_of (mooshroom -> cow, trader_llama -> llama) is INERT at runtime in both
            // id-encoding states - id-encoded, its rows are pseudo-ids the family-id-keyed crossFamilies
            // never matches; guarding it to non-variant families keeps that inertness once option-encoding
            // makes the base row a plain id, so the coat's family stays itself in both states.
            if (variant == null && family.has("family_of")) crossFamilies.put(familyId, family.get("family_of").getAsString());
        }

        Map<String, String> entityToFamily = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : variantOf.entrySet()) {
            String family = crossFamilies.get(row.getKey());
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
    // family read
    // ------------------------------------------------------------------------------------

    /**
     * Reads one v2 family into one (plain) or many (id-encoded variant) {@link Entity} rows,
     * adding each to {@code definitions}.
     */
    private static void readFamily(
        @NotNull String familyId,
        @NotNull JsonObject family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, Entity> definitions,
        @NotNull Diagnostics diagnostics
    ) {
        // Axis unification #1: the family baseline (primary geometry + adult texture) lives under the
        // mandatory age axis' options.adult, not at top level.
        JsonObject adult = adultOption(family);
        String baseCoord = adult.get("geometry").getAsString();

        JsonObject render = family.has("render") ? family.getAsJsonObject("render") : null;
        float rendererScale = render != null && render.has("scale") ? render.get("scale").getAsFloat() : 1f;
        float setupYawAddend = render != null && render.has("yaw_addend") ? render.get("yaw_addend").getAsFloat() : 0f;
        int baseTint = render != null && render.has("tint") ? ArgbHex.parse(render.get("tint").getAsString(), diagnostics) : WHITE;

        JsonObject bones = family.has("bones") ? family.getAsJsonObject("bones") : null;
        JsonArray hiddenBones = bones != null && bones.has("hidden") && bones.get("hidden").isJsonArray() ? bones.getAsJsonArray("hidden") : null;
        JsonObject boneToggleSpecs = bones != null && bones.has("toggles") && bones.get("toggles").isJsonObject() ? bones.getAsJsonObject("toggles") : null;

        JsonArray familyOverlays = family.has("overlays") && family.get("overlays").isJsonArray() ? family.getAsJsonArray("overlays") : new JsonArray();
        List<BlockOverlayLayer> blockOverlays = family.has("block_overlays") && family.get("block_overlays").isJsonArray()
            ? loadBlockOverlays(family.getAsJsonArray("block_overlays")) : List.of();

        Optional<String> collarTexture = collarTextureOf(family);
        List<EquipmentOverlay> equipment = loadEquipment(family, geometries, familyId, diagnostics);
        boolean markings = markingsOf(family);
        boolean humanoidArmor = humanoidArmorOf(family);
        String babyCoord = babyGeometryOf(family);
        Optional<EntityModelData> babyModel = babyCoord == null ? Optional.empty() : Optional.ofNullable(geometries.get(babyCoord));

        JsonObject variant = variantAxis(family);
        if (variant != null) {
            boolean idEncoded = variant.has("id_encoded") && variant.get("id_encoded").getAsBoolean();
            String defaultOption = variant.get("default").getAsString();
            JsonObject options = variant.getAsJsonObject("options");
            VariantContext ctx = new VariantContext(baseCoord, geometries, hiddenBones, boneToggleSpecs, familyOverlays,
                blockOverlays, baseTint, setupYawAddend, rendererScale, babyModel, collarTexture, equipment, markings, humanoidArmor);
            if (idEncoded) {
                // id-encoded: each coat is a first-class render pseudo-id minecraft:<id>_<opt>.
                for (Map.Entry<String, JsonElement> option : options.entrySet()) {
                    String rowId = familyId + "_" + option.getKey();
                    definitions.put(rowId, buildVariantRow(rowId, option.getValue().getAsJsonObject(), ctx, diagnostics));
                }
                return;
            }
            // option-encoded [axis-unification #3]: one base row minecraft:<id>, the coat resolved at render
            // from EntityAppearance.variant. Every option is built into a sub-definition (byte-identical to
            // the pseudo-id it replaced); the base row IS the default coat carrying the full option map so
            // the resolver fold + family canvas union reach every coat.
            LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> option : options.entrySet())
                coats.put(option.getKey(), buildVariantRow(familyId, option.getValue().getAsJsonObject(), ctx, diagnostics));
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
        Optional<String> textureRef = adult.has("texture") ? Optional.of(stripEntity(adult.get("texture").getAsString())) : Optional.empty();

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
        @Nullable JsonArray hiddenBones,
        @Nullable JsonObject boneToggleSpecs,
        @NotNull JsonArray familyOverlays,
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
        @NotNull JsonObject optionObj,
        @NotNull VariantContext ctx,
        @NotNull Diagnostics diagnostics
    ) {
        String rowCoord = optionObj.has("geometry") ? optionObj.get("geometry").getAsString() : ctx.baseCoord();
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
     * Resolves a v2 {@code overlays} array into {@link OverlayLayer}s. An overlay without a
     * {@code geometry} member (or one naming the base coordinate) inherits the post-hidden-strip base
     * mesh so its cubes co-register with the base; a distinct coordinate resolves fresh from the
     * geometry table (a missing coordinate warns and drops). {@code retain_bones} restricts the mesh
     * to a vanilla {@code retainExactParts} subset before inflate; {@code grow} inflates every cube.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull JsonArray overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String baseCoord,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        List<OverlayLayer> out = new ArrayList<>();
        for (JsonElement el : overlays) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            String coord = entry.has("geometry") ? entry.get("geometry").getAsString() : baseCoord;
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
            Optional<String> overlayTexture = entry.has("texture") ? Optional.of(stripEntity(entry.get("texture").getAsString())) : Optional.empty();
            // retain_bones (warden pulsating spots) restricts the overlay to a vanilla retainExactParts
            // subset of the shared mesh, so the glow texture draws only where vanilla's subset
            // LayerDefinition does. Applied before inflate so surviving cubes inflate together.
            EntityModelData retained = entry.has("retain_bones") && entry.get("retain_bones").isJsonArray()
                ? retainExactParts(overlayModel, entry.getAsJsonArray("retain_bones"))
                : overlayModel;
            boolean hasTint = entry.has("tint");
            boolean hasTintBy = entry.has("tint_by");
            // inflate: an explicit grow (real vanilla CubeDeformation - tropical_fish 0.008, llama carpet
            // 0.5) wins; else a same-mesh grow-less overlay that RE-SUBMITS the base geometry with no
            // colour tint gets the depth-clearance inflate (emissive eyes, texture_by profession /
            // crackiness); every other overlay carries none.
            float inflate = entry.has("grow") ? entry.get("grow").getAsFloat()
                : sameGeometry && !hasTint && !hasTintBy ? DEPTH_CLEARANCE_INFLATE : 0f;
            EntityModelData materialised = inflate != 0f ? inflateModel(retained, inflate) : retained;
            JsonObject pipeline = entry.has("pipeline") ? entry.getAsJsonObject("pipeline") : null;
            boolean emissive = pipeline != null && pipeline.has("emissive") && pipeline.get("emissive").getAsBoolean();
            int overlayTint = hasTint ? ArgbHex.parse(entry.get("tint").getAsString(), diagnostics) : WHITE;
            // Same-geometry overlays carrying ONLY the auto-emitted depth-clearance inflate are excluded
            // from the canvas-sizing bounds: they render the IDENTICAL cube tree as the base (vanilla
            // submits the same ModelPart through a second render type with NO inflate), so the base
            // already contributes their full silhouette extent. A LARGER inflate is a real vanilla
            // CubeDeformation vanilla's own bounds walk includes, so it keeps contributing. An explicit
            // skip_bounds (llama carpet, NO_RENDER_LAYER_SUFFIXES) always wins.
            boolean depthClearanceOnly = sameGeometry && inflate <= DEPTH_CLEARANCE_INFLATE;
            boolean skipBounds = entry.has("skip_bounds") && entry.get("skip_bounds").getAsBoolean() || depthClearanceOnly;
            Optional<String> tintBy = entry.has("tint_by") ? Optional.of(entry.get("tint_by").getAsString()) : Optional.empty();
            Optional<String> textureBy = entry.has("texture_by") ? Optional.of(entry.get("texture_by").getAsString()) : Optional.empty();
            // The overlay's render condition, parsed straight from its v2 `when` object into the typed
            // AppearanceGate (flag/charged/tinted). Absent -> unconditional.
            Optional<AppearanceGate> gate = parseOverlayGate(entry.has("when") ? entry.getAsJsonObject("when") : null, tintBy);
            // blend / alpha (default NORMAL / 1.0). `additive` -> the energy-swirl glow; `translucent` /
            // `normal` -> source-over (the slime shell's translucency lives in its texture alpha, not a
            // blend-function difference). An un-annotated overlay renders byte-identical.
            BlendMode blend = parseBlend(pipeline != null && pipeline.has("blend") ? pipeline.get("blend").getAsString() : null, diagnostics);
            float alpha = pipeline != null && pipeline.has("alpha") ? pipeline.get("alpha").getAsFloat() : 1f;
            out.add(new OverlayLayer(materialised, overlayTexture, emissive, overlayTint, skipBounds, tintBy, textureBy, blend, alpha, gate));
        }
        return out;
    }

    /**
     * Parses an overlay's v2 {@code when} object into a typed {@link AppearanceGate}: {@code flag} maps
     * to {@link AppearanceGate.FlagGate}, {@code charged} to {@link AppearanceGate.ChargedGate}, and
     * {@code tinted} to {@link AppearanceGate.TintedGate} (carrying the overlay's tint axis token so the
     * gate is self-contained). Absent or unrecognised yields empty (unconditional).
     *
     * @param when the overlay's {@code when} object, or {@code null} when absent
     * @param tintBy the overlay's tint axis token, used to seed a {@link AppearanceGate.TintedGate}
     * @return the parsed gate, or empty when unconditional
     */
    private static @NotNull Optional<AppearanceGate> parseOverlayGate(@Nullable JsonObject when, @NotNull Optional<String> tintBy) {
        if (when == null) return Optional.empty();
        if (when.has("flag"))
            return Optional.of(new AppearanceGate.FlagGate(when.get("flag").getAsString(), when.has("value") && when.get("value").getAsBoolean()));
        if (when.has("charged") && when.get("charged").getAsBoolean())
            return Optional.of(new AppearanceGate.ChargedGate());
        if (when.has("tinted") && when.get("tinted").getAsBoolean())
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
     * Resolves a v2 {@code block_overlays} array into {@link BlockOverlayLayer} rows. A fixed row names
     * its {@code block}; a {@code selectable} row's block is supplied at render from the carried
     * selection, so its {@code block} may be omitted entirely (the enderman carried block). The
     * {@code transforms} entries are the tagged op objects the renderer pattern-matches.
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull JsonArray array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            boolean selectable = row.has("selectable") && row.get("selectable").getAsBoolean();
            if (!row.has("block") && !selectable) continue;
            String blockId = row.has("block") ? row.get("block").getAsString() : "";
            String attachedBone = row.has("attached_bone") && !row.get("attached_bone").isJsonNull()
                ? row.get("attached_bone").getAsString()
                : null;
            List<TransformOp> ops = new ArrayList<>();
            if (row.has("transforms") && row.get("transforms").isJsonArray()) {
                for (JsonElement opElement : row.getAsJsonArray("transforms")) {
                    if (!opElement.isJsonObject()) continue;
                    JsonObject opObj = opElement.getAsJsonObject();
                    switch (opObj.has("op") ? opObj.get("op").getAsString() : "") {
                        case "translate" -> ops.add(new Translate(opObj.get("x").getAsFloat(), opObj.get("y").getAsFloat(), opObj.get("z").getAsFloat()));
                        case "rotate_y" -> ops.add(new RotateY(opObj.get("degrees").getAsFloat()));
                        case "rotate_x" -> ops.add(new RotateX(opObj.get("degrees").getAsFloat()));
                        case "rotate_z" -> ops.add(new RotateZ(opObj.get("degrees").getAsFloat()));
                        case "scale" -> ops.add(new Scale(opObj.get("x").getAsFloat(), opObj.get("y").getAsFloat(), opObj.get("z").getAsFloat()));
                        default -> { }
                    }
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
    private static @NotNull Map<String, String> variantStateTextures(@NotNull JsonObject optionObj) {
        Map<String, String> states = new LinkedHashMap<>();
        if (optionObj.has("textures"))
            for (Map.Entry<String, JsonElement> texture : optionObj.getAsJsonObject("textures").entrySet())
                states.put(texture.getKey(), stripEntity(texture.getValue().getAsString()));
        if (optionObj.has("baby_texture")) states.put("baby", stripEntity(optionObj.get("baby_texture").getAsString()));
        return states.size() > 1 ? states : Map.of();
    }

    /**
     * Returns a variant option's {@code textures.wild} as the base texture ref, or empty when absent.
     */
    private static @NotNull Optional<String> variantWildTexture(@NotNull JsonObject optionObj) {
        if (!optionObj.has("textures")) return Optional.empty();
        JsonObject textures = optionObj.getAsJsonObject("textures");
        return textures.has("wild") ? Optional.of(stripEntity(textures.get("wild").getAsString())) : Optional.empty();
    }

    /**
     * Returns the mandatory age axis' {@code options.adult} body - the family baseline (primary
     * {@code geometry}, and for non-variant families the adult {@code texture}) that axis unification #1
     * relocated from the former top-level {@code geometry}/{@code texture} members.
     */
    private static @NotNull JsonObject adultOption(@NotNull JsonObject family) {
        return family.getAsJsonObject("axes").getAsJsonObject("age").getAsJsonObject("options").getAsJsonObject("adult");
    }

    /** Returns the {@code axes.variant} object when the family carries an id-encoded variant axis. */
    private static @Nullable JsonObject variantAxis(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        return axes.has("variant") ? axes.getAsJsonObject("variant") : null;
    }

    /** Returns the {@code age.baby} option object, or {@code null} when the family has no age axis. */
    private static @Nullable JsonObject ageBaby(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("age")) return null;
        JsonObject options = axes.getAsJsonObject("age").getAsJsonObject("options");
        return options.has("baby") ? options.getAsJsonObject("baby") : null;
    }

    /** Returns the family's baby geometry coordinate from its {@code age} axis, or {@code null}. */
    private static @Nullable String babyGeometryOf(@NotNull JsonObject family) {
        JsonObject baby = ageBaby(family);
        return baby != null && baby.has("geometry") ? baby.get("geometry").getAsString() : null;
    }

    /** Returns the family's single stripped baby texture from {@code age.baby.texture}, or {@code null}. */
    private static @Nullable String babyTextureOf(@NotNull JsonObject family) {
        JsonObject baby = ageBaby(family);
        return baby != null && baby.has("texture") ? stripEntity(baby.get("texture").getAsString()) : null;
    }

    /** Returns the dyed-collar layer's stripped texture, or empty when the family has no collar layer. */
    private static @NotNull Optional<String> collarTextureOf(@NotNull JsonObject family) {
        if (!family.has("layers")) return Optional.empty();
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (layer.has("id") && "collar".equals(layer.get("id").getAsString()) && layer.has("overlay")) {
                JsonObject overlay = layer.getAsJsonObject("overlay");
                if (overlay.has("texture")) return Optional.of(stripEntity(overlay.get("texture").getAsString()));
            }
        }
        return Optional.empty();
    }

    /** Returns whether the family carries a {@code markings} layer (the horse marking overlay). */
    private static boolean markingsOf(@NotNull JsonObject family) {
        if (!family.has("layers")) return false;
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (layer.has("id") && "markings".equals(layer.get("id").getAsString())) return true;
        }
        return false;
    }

    /**
     * Returns whether the family carries a {@code humanoid} armor classification row [LOCKED 3] - the
     * v2 {@code layers} armor row EntityLayersResolver emits off a {@code HumanoidArmorLayer} site.
     * Absence IS {@code none} (the classification is derived off the roster, not a required member).
     * The native reader's consumption of the relocated {@code armor_type}, replacing the former
     * flattener hard-require of a top-level member the render path dropped (debt row 7).
     */
    private static boolean humanoidArmorOf(@NotNull JsonObject family) {
        if (!family.has("layers")) return false;
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (layer.has("armor_type") && "humanoid".equals(layer.get("armor_type").getAsString())) return true;
        }
        return false;
    }

    /**
     * Resolves the family's {@code when.equipment}-gated layers into {@link EquipmentOverlay}s, binding
     * each overlay's {@code geometry} coordinate to its baked mesh. The {@code texture_template} and
     * {@code default_material} are the already-relative equipment sub-path forms the renderer
     * substitutes {@code <material>} into; a layer naming an unknown geometry warns and drops.
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @NotNull JsonObject family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (!family.has("layers")) return List.of();
        List<EquipmentOverlay> out = new ArrayList<>();
        for (JsonElement element : family.getAsJsonArray("layers")) {
            JsonObject layer = element.getAsJsonObject();
            if (!layer.has("when") || !layer.has("overlay")) continue;
            JsonObject when = layer.getAsJsonObject("when");
            if (!when.has("equipment")) continue;
            JsonObject overlay = layer.getAsJsonObject("overlay");
            if (!overlay.has("geometry") || !overlay.has("texture_template") || !overlay.has("default_material")) continue;
            String coord = overlay.get("geometry").getAsString();
            EntityModelData model = geometries.get(coord);
            if (model == null) {
                diagnostics.warn("entity '%s' equipment layer references geometry '%s' absent from entity_geometry", entityId, coord);
                continue;
            }
            out.add(new EquipmentOverlay(when.get("equipment").getAsString(), model,
                overlay.get("texture_template").getAsString(), overlay.get("default_material").getAsString()));
        }
        return List.copyOf(out);
    }

    /**
     * Resolves the family's {@code shape.large} option (tropical fish) into a {@link LargeShape}: the
     * large body mesh, its stripped base texture, and the pattern overlays materialised on the large
     * geometry. Empty when the family has no shape axis or its large geometry is missing.
     */
    private static @NotNull Optional<LargeShape> buildLargeShape(
        @NotNull JsonObject family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (!family.has("axes")) return Optional.empty();
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("shape")) return Optional.empty();
        JsonObject options = axes.getAsJsonObject("shape").getAsJsonObject("options");
        if (!options.has("large")) return Optional.empty();
        JsonObject large = options.getAsJsonObject("large");
        if (!large.has("geometry")) return Optional.empty();
        String coord = large.get("geometry").getAsString();
        EntityModelData model = geometries.get(coord);
        if (model == null) return Optional.empty();
        JsonArray largeOverlays = large.has("overlays") && large.get("overlays").isJsonArray() ? large.getAsJsonArray("overlays") : new JsonArray();
        List<OverlayLayer> overlays = loadOverlays(largeOverlays, geometries, coord, model, entityId, diagnostics);
        Optional<String> textureRef = large.has("texture") ? Optional.of(stripEntity(large.get("texture").getAsString())) : Optional.of("");
        return Optional.of(new LargeShape(model, textureRef, overlays));
    }

    /**
     * Resolves the family's {@code size} axis geometry alternatives (pufferfish small / medium) into
     * {@code Size -> mesh}. Options carrying a {@code scale} (not a {@code geometry}) are skipped; the
     * default size is the base mesh and never appears here.
     */
    private static @NotNull Map<Size, EntityModelData> buildSizeModels(@NotNull JsonObject family, @NotNull Map<String, EntityModelData> geometries) {
        JsonObject options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> option : options.entrySet()) {
            JsonObject body = option.getValue().getAsJsonObject();
            if (!body.has("geometry")) continue;
            EntityModelData mesh = geometries.get(body.get("geometry").getAsString());
            if (mesh != null) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), mesh);
        }
        return out;
    }

    /**
     * Resolves the family's {@code size} axis scale alternatives (salmon / slime / magma_cube) into
     * {@code Size -> factor}. The default size is scale {@code 1.0} and never appears here.
     */
    private static @NotNull Map<Size, Float> buildSizeScales(@NotNull JsonObject family) {
        JsonObject options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> option : options.entrySet()) {
            JsonObject body = option.getValue().getAsJsonObject();
            if (body.has("scale")) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), body.get("scale").getAsFloat());
        }
        return out;
    }

    /** Returns the family's {@code axes.size.options} object, or {@code null} when it has no size axis. */
    private static @Nullable JsonObject sizeOptions(@NotNull JsonObject family) {
        if (!family.has("axes")) return null;
        JsonObject axes = family.getAsJsonObject("axes");
        if (!axes.has("size")) return null;
        return axes.getAsJsonObject("size").getAsJsonObject("options");
    }

    // ------------------------------------------------------------------------------------
    // bones + geometry surgery
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a family / variant geometry coordinate against the parsed geometry table, failing LOUD
     * on a dangling coordinate (matching the historic {@code EntityModelLoader} contract).
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
        @Nullable JsonObject toggles,
        @NotNull EntityModelData fullModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (toggles == null) return Map.of();
        Map<String, BoneToggle> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : toggles.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject spec = entry.getValue().getAsJsonObject();
            if (!spec.has("bones") || !spec.get("bones").isJsonArray()) continue;
            boolean defaultVisible = spec.has("default") && spec.get("default").getAsBoolean();
            LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
            for (JsonElement element : spec.getAsJsonArray("bones")) {
                if (!element.isJsonPrimitive()) continue;
                String boneName = element.getAsString();
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
        @Nullable JsonArray hiddenBones,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (hiddenBones == null) return model;
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(model.getBones());
        for (JsonElement el : hiddenBones) {
            if (!el.isJsonPrimitive()) continue;
            String name = el.getAsString();
            if (bones.remove(name) == null)
                diagnostics.warn("entity '%s' hidden_bones names bone '%s' which is not on the geometry", entityId, name);
        }
        return new EntityModelData(model.getTextureWidth(), model.getTextureHeight(), model.getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * Restricts an overlay model to the vanilla {@code retainExactParts} subset named by
     * {@code retainBones}: a bone keeps its cubes iff it is named AND no ancestor is (vanilla's
     * {@code clearRecursively} empties a retained part's descendant subtree). Every other bone is kept
     * as a pose-only node so the transform hierarchy stays intact.
     */
    private static @NotNull EntityModelData retainExactParts(@NotNull EntityModelData source, @NotNull JsonArray retainBones) {
        Set<String> retain = new LinkedHashSet<>();
        for (JsonElement el : retainBones)
            if (el.isJsonPrimitive()) retain.add(el.getAsString());
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
        return new EntityModelData(source.getTextureWidth(), source.getTextureHeight(), source.getInventoryYRotation(), Concurrent.adoptLinkedMap(out), source.isCull());
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
     * Returns a deep-cloned copy of {@code model} with every cube's inflate bumped by {@code delta} -
     * surrounding the base mesh with the inflated overlay instead of z-fighting it. Bones, pivots,
     * rotations, UVs, and parent links are preserved verbatim.
     */
    private static @NotNull EntityModelData inflateModel(@NotNull EntityModelData source, float delta) {
        LinkedHashMap<String, EntityModelData.Bone> inflated = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : source.getBones().entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            ArrayList<EntityModelData.Cube> cubes = new ArrayList<>(bone.getCubes().size());
            for (EntityModelData.Cube cube : bone.getCubes())
                cubes.add(new EntityModelData.Cube(
                    cube.getOrigin(), cube.getSize(), cube.getUv(),
                    cube.getInflate() + delta, cube.isMirror(),
                    cube.getPivot(), cube.getRotation(), cube.getFaceUv(),
                    cube.getGrowAxis()));       // depth-clearance is a scalar bump; per-axis grow (none in 26.1) rides through
            inflated.put(e.getKey(), new EntityModelData.Bone(
                bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                bone.getScale(), Concurrent.adoptList(cubes), bone.getParent()));
        }
        return new EntityModelData(source.getTextureWidth(), source.getTextureHeight(), source.getInventoryYRotation(), Concurrent.adoptLinkedMap(inflated), source.isCull());
    }

    // ------------------------------------------------------------------------------------
    // resource + text helpers
    // ------------------------------------------------------------------------------------

    /** Parses every {@code geometries} entry (skipping {@code //} comment keys) into a coordinate map. */
    private static @NotNull Map<String, EntityModelData> parseGeometries(@NotNull V2Document geometryDoc) {
        JsonObject root = geometryDoc.payload().toGson().getAsJsonObject();
        if (!root.has("geometries")) return Map.of();
        JsonObject geometriesJson = root.getAsJsonObject("geometries");
        Map<String, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : geometriesJson.entrySet()) {
            if (entry.getKey().startsWith("//")) continue;
            out.put(entry.getKey(), V2Geometry.parse(entry.getValue().getAsJsonObject()));
        }
        return out;
    }

    /** Returns the {@code families} object of a parsed models document, or {@code null} when absent. */
    private static @Nullable JsonObject familiesOf(@NotNull V2Document modelsDoc) {
        JsonObject root = modelsDoc.payload().toGson().getAsJsonObject();
        return root.has("families") ? root.getAsJsonObject("families") : null;
    }

    /**
     * Reduces a full v2 entity texture path to the {@code textures/entity/}-relative sub-path the
     * texture resolver re-qualifies as {@code minecraft:entity/<ref>} - dropping the
     * {@code minecraft:textures/entity/} prefix and the {@code .png} suffix. Mirrors
     * {@code EntityModelsBridge.strip} so the resolved id is byte-identical.
     */
    private static @NotNull String stripEntity(@NotNull String path) {
        if (!path.startsWith(TEXTURE_PREFIX) || !path.endsWith(TEXTURE_SUFFIX))
            throw new PipelineException("Unexpected entity texture path '%s' (expected '%s<sub>%s')", path, TEXTURE_PREFIX, TEXTURE_SUFFIX);
        return path.substring(TEXTURE_PREFIX.length(), path.length() - TEXTURE_SUFFIX.length());
    }
}
