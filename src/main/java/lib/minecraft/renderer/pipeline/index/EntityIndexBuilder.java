package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity.BlockOverlayLayer;
import lib.minecraft.renderer.asset.Entity.BoneToggle;
import lib.minecraft.renderer.asset.Entity.EquipmentOverlay;
import lib.minecraft.renderer.asset.Entity.LargeShape;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateX;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateY;
import lib.minecraft.renderer.asset.Entity.TransformOp.RotateZ;
import lib.minecraft.renderer.asset.Entity.TransformOp.Scale;
import lib.minecraft.renderer.asset.Entity.TransformOp.Translate;
import lib.minecraft.renderer.asset.Entity.TransformOp;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.raster.Composition;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.pipeline.util.ArgbHex;
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
 * Assembles the runtime entity index from the two pure reads: joins each {@link RawModel}'s geometry
 * coordinate against the geometry table, runs the mesh surgery (hidden-bone strip, {@code retainExactParts}
 * subset, {@code grow} inflate plus the auto-emitted depth-clearance bump), pivots the option axes into
 * {@link Entity.Axes}, folds each variant option into a sub-{@link Entity}, and stamps cross-entity
 * canvas-group membership. The leaf decodes (hex tint, {@code blend} token, texture strip, transform ops)
 * happen here too, where the {@link Diagnostics} handle is available - the raw records carry them verbatim.
 *
 * <p>Id-encoded variant expansion is retained faithfully but is a dead branch in 26.1 - the tooling hardcodes
 * {@code id_encoded: false}, so every family folds to one base row (option-encoded coats live on
 * {@code axes.variants}, measured by the group canvas union rather than as separate member rows).
 */
public final class EntityIndexBuilder {

    private static final @NotNull String TEXTURE_PREFIX = "minecraft:textures/entity/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    /** Model units per block - the scale a vanilla {@code PoseStack} translate is expressed against. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    private static final int WHITE = 0xFFFFFFFF;

    private EntityIndexBuilder() {}

    /**
     * Assembles the entity index from the raw model tree and the geometry table.
     *
     * @param geometries the geometry coordinate to bone tree table
     * @param rawFile the raw model catalog
     * @param diagnostics the scope read warnings are recorded to
     * @return definitions keyed by namespaced entity id, in file order
     * @throws PipelineException if an entity references a geometry coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> assemble(
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull RawEntityModelsFile rawFile,
        @NotNull Diagnostics diagnostics
    ) {
        Map<String, RawModel> models = rawFile.models();
        if (models == null) return Concurrent.newMap();
        LinkedHashMap<String, Entity> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, RawModel> entry : models.entrySet()) {
            if (entry.getValue() == null) continue;
            readDefinition(entry.getKey(), entry.getValue(), geometries, definitions, diagnostics);
        }
        attachGroupMembers(models, definitions);
        return Concurrent.adoptMap(definitions);
    }

    /**
     * Stamps each grouped entity's canvas-group membership onto its {@link Entity#members()} - the
     * self-inclusive member list the group-union fit bound iterates. Groups of size one (singletons)
     * carry no members (the empty default); only genuine groups (size &gt; 1) are rewritten.
     *
     * @param models the raw model catalog
     * @param definitions the built definitions, mutated in place for grouped entities
     */
    private static void attachGroupMembers(@NotNull Map<String, RawModel> models, @NotNull Map<String, Entity> definitions) {
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
     * @param models the raw model catalog
     * @return group membership keyed by entity id
     */
    private static @NotNull Map<String, List<String>> groupMembership(@NotNull Map<String, RawModel> models) {
        Map<String, String> variantOf = new LinkedHashMap<>();
        Map<String, String> crossGroups = new LinkedHashMap<>();
        for (Map.Entry<String, RawModel> entry : models.entrySet()) {
            if (entry.getValue() == null) continue;
            String familyId = entry.getKey();
            RawModel family = entry.getValue();
            RawVariantAxis variant = variantAxis(family);
            boolean idEncoded = variant != null && variant.idEncoded();
            if (variant != null && idEncoded) {
                String defaultOption = variant.defaultOption();
                String baseId = familyId + "_" + defaultOption;
                for (String option : variant.options().keySet()) {
                    String rowId = familyId + "_" + option;
                    variantOf.put(rowId, option.equals(defaultOption) ? null : baseId);
                }
            } else {
                // Non-variant OR option-encoded variant model: one base row. Option-encoded coats live on
                // the base definition's axes.variants and are measured by the group canvas union.
                variantOf.put(familyId, null);
            }
            // group_of groups a non-variant sub-species under its base (camel_husk -> camel). A variant
            // model's group_of (mooshroom -> cow) is inert at runtime, so it is guarded to non-variant
            // models to keep that inertness once option-encoding makes the base row a plain id.
            if (variant == null && family.groupOf() != null) crossGroups.put(familyId, family.groupOf());
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
     * Reads one model into one (plain) or many (id-encoded variant) {@link Entity} rows, adding each to
     * {@code definitions}.
     */
    private static void readDefinition(
        @NotNull String familyId,
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, Entity> definitions,
        @NotNull Diagnostics diagnostics
    ) {
        // The family baseline (primary geometry + adult texture) lives under the mandatory age axis'
        // options.adult, not at top level.
        RawAgeOption adult = adultOption(family);
        String baseCoord = adult.geometry();

        RawRender render = family.render();
        float rendererScale = render == null || render.scale() == null ? 1f : render.scale();
        float setupYawAddend = render == null ? 0f : render.yawAddend();
        // The setupRotations translate is per-age, so it rides the age options rather than `render`,
        // and it is applied to the mesh here with the rest of the surgery rather than carried to render
        // time: the renderer and the bounds walk both read the mesh, so moving it is what keeps the two
        // from disagreeing about where the subject is.
        RawAgeOption babyAge = ageBaby(family);
        float babyYShift = babyAge == null ? 0f : babyAge.yShift();
        int baseTint = render == null || render.tint() == null ? WHITE : ArgbHex.parse(render.tint(), diagnostics);

        RawBones bones = family.bones();
        List<String> hiddenBones = bones == null ? null : bones.hidden();
        Map<String, RawToggle> boneToggleSpecs = bones == null ? null : bones.toggles();

        List<RawOverlay> familyOverlays = nullToEmpty(family.overlays());
        List<BlockOverlayLayer> blockOverlays = family.blockOverlays() == null ? List.of() : loadBlockOverlays(family.blockOverlays());

        Optional<String> collarTexture = collarTextureOf(family);
        List<EquipmentOverlay> equipment = loadEquipment(family, geometries, familyId, diagnostics);
        boolean markings = markingsOf(family);
        Optional<Entity.HumanoidArmor> humanoidArmor = humanoidArmorOf(family, geometries, familyId, diagnostics);
        String babyCoord = babyGeometryOf(family);
        Optional<EntityModelData> babyModel = babyCoord == null ? Optional.empty()
            : Optional.ofNullable(geometries.get(babyCoord)).map(baby -> shiftModel(baby, babyYShift));
        List<OverlayLayer> babyOverlays = loadBabyOverlays(familyOverlays, geometries, babyCoord, babyModel, familyId, diagnostics);

        RawVariantAxis variant = variantAxis(family);
        if (variant != null) {
            boolean idEncoded = variant.idEncoded();
            String defaultOption = variant.defaultOption();
            Map<String, RawVariantOption> options = variant.options();
            VariantContext ctx = new VariantContext(baseCoord, geometries, hiddenBones, boneToggleSpecs, familyOverlays,
                blockOverlays, baseTint, setupYawAddend, rendererScale, babyModel, babyOverlays, collarTexture, equipment, markings, humanoidArmor,
                stateDefaultOf(family));
            if (idEncoded) {
                // id-encoded: each coat is a first-class render pseudo-id minecraft:<id>_<opt>.
                for (Map.Entry<String, RawVariantOption> option : options.entrySet()) {
                    String rowId = familyId + "_" + option.getKey();
                    definitions.put(rowId, buildVariantRow(rowId, option.getValue(), ctx, diagnostics));
                }
                return;
            }
            // option-encoded variant: one base row minecraft:<id>, the coat resolved at render. Every option
            // is built into a sub-definition; the base row IS the default coat carrying the full option map.
            LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
            for (Map.Entry<String, RawVariantOption> option : options.entrySet())
                coats.put(option.getKey(), buildVariantRow(familyId, option.getValue(), ctx, diagnostics));
            Entity base = coats.getOrDefault(defaultOption, coats.values().iterator().next());
            Entity.Axes baseAxes = base.axes();
            definitions.put(familyId, base.toBuilder()
                .axes(new Entity.Axes(baseAxes.stateTextures(), baseAxes.babyModel(), baseAxes.babyOverlays(),
                    baseAxes.largeShape(), baseAxes.sizeModels(), baseAxes.sizeScales(), Map.copyOf(coats),
                    Optional.ofNullable(defaultOption), stateDefaultOf(family), sizeDefaultOf(family)))
                .build());
            return;
        }

        // Plain family: one row. The size / shape axes attach only to plain families, so they resolve here.
        EntityModelData model = resolveModel(geometries, baseCoord, familyId);
        Map<String, BoneToggle> toggles = loadBoneToggles(boneToggleSpecs, model, familyId, diagnostics);
        model = applyHiddenBones(model, hiddenBones, familyId, diagnostics);
        // Ahead of the overlay load so a same-geometry pass is materialised on the shifted mesh and
        // travels with it; a pass on a mesh of its OWN would not, which the shift warns about.
        model = shiftModel(model, adult.yShift());
        List<OverlayLayer> overlays = loadOverlays(familyOverlays, geometries, baseCoord, model, familyId, diagnostics);
        Optional<String> textureRef = adult.texture() == null ? Optional.empty() : Optional.of(stripEntity(adult.texture()));

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
            .axes(new Entity.Axes(stateTextures, babyModel, babyOverlays,
                buildLargeShape(family, geometries, familyId, diagnostics), buildSizeModels(family, geometries),
                buildSizeScales(family), Map.of(), Optional.empty(), stateDefaultOf(family), sizeDefaultOf(family)))
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
        @Nullable List<String> hiddenBones,
        @Nullable Map<String, RawToggle> boneToggleSpecs,
        @NotNull List<RawOverlay> familyOverlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        int baseTint,
        float setupYawAddend,
        float rendererScale,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull List<OverlayLayer> babyOverlays,
        @NotNull Optional<String> collarTexture,
        @NotNull List<EquipmentOverlay> equipment,
        boolean markings,
        @NotNull Optional<Entity.HumanoidArmor> humanoidArmor,
        @NotNull Optional<String> stateDefault
    ) {}

    /**
     * Builds one variant option's {@link Entity}: the option's geometry (its own coordinate when it
     * overrides the family mesh, else the base coordinate) with the family's bone toggles, hidden-bone
     * strip, and overlays materialised on it, plus the option's {@code wild} coat texture and per-state
     * textures. The built definition carries an empty {@code axes.variants} - it is a leaf coat.
     *
     * <p>A coat naming its own {@code block} redraws the family's fixed block overlays as that block -
     * the mooshroom's brown mushrooms against the red ones its rows carry. Only the fixed rows move:
     * a selectable row's block is the caller's held one, supplied at render.
     */
    private static @NotNull Entity buildVariantRow(
        @NotNull String rowId,
        @NotNull RawVariantOption optionObj,
        @NotNull VariantContext ctx,
        @NotNull Diagnostics diagnostics
    ) {
        String rowCoord = optionObj.geometry() == null ? ctx.baseCoord() : optionObj.geometry();
        EntityModelData model = resolveModel(ctx.geometries(), rowCoord, rowId);
        Map<String, BoneToggle> toggles = loadBoneToggles(ctx.boneToggleSpecs(), model, rowId, diagnostics);
        model = applyHiddenBones(model, ctx.hiddenBones(), rowId, diagnostics);
        List<OverlayLayer> overlays = loadOverlays(ctx.familyOverlays(), ctx.geometries(), rowCoord, model, rowId, diagnostics);
        Map<String, String> stateTextures = variantStateTextures(optionObj);
        Optional<String> textureRef = variantWildTexture(optionObj);
        return Entity.builder()
            .id(ResourceId.parse(rowId))
            .model(model).textureRef(textureRef).overlays(overlays)
            .blockOverlays(coatBlockOverlays(ctx.blockOverlays(), optionObj.block()))
            .baseTintArgb(ctx.baseTint()).setupYawAddend(ctx.setupYawAddend()).rendererScale(ctx.rendererScale())
            .boneToggles(toggles)
            .axes(new Entity.Axes(stateTextures, ctx.babyModel(), ctx.babyOverlays(), Optional.empty(),
                Map.of(), Map.of(), Map.of(), Optional.empty(), ctx.stateDefault(), Optional.empty()))
            .layers(new Entity.Layers(ctx.collarTexture(), ctx.equipment(), ctx.markings(), ctx.humanoidArmor()))
            .build();
    }

    /**
     * The family's block overlays as one coat draws them: unchanged when the coat names no block of
     * its own, else every fixed row redrawn as that block. A selectable row is left alone - its
     * block id is a placeholder the caller's carried selection replaces at render, so rewriting it
     * here would be overwritten anyway and would read as though the coat had chosen it.
     */
    private static @NotNull List<BlockOverlayLayer> coatBlockOverlays(
        @NotNull List<BlockOverlayLayer> blockOverlays, @Nullable String coatBlock) {
        if (coatBlock == null || blockOverlays.isEmpty()) return blockOverlays;
        List<BlockOverlayLayer> out = new ArrayList<>(blockOverlays.size());
        for (BlockOverlayLayer overlay : blockOverlays)
            out.add(overlay.selectable() ? overlay : overlay.withBlockId(coatBlock));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------
    // overlays
    // ------------------------------------------------------------------------------------

    /**
     * Resolves an {@code overlays} list into {@link OverlayLayer}s. An overlay without a {@code geometry}
     * member (or one naming the base coordinate) inherits the post-hidden-strip base mesh so its cubes
     * co-register with the base; a distinct coordinate resolves fresh from the geometry table (a missing
     * coordinate warns and drops). {@code retain_bones} restricts the mesh to a vanilla
     * {@code retainExactParts} subset before inflate; {@code grow} inflates every cube.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull List<RawOverlay> overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String baseCoord,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        List<OverlayLayer> out = new ArrayList<>();
        for (RawOverlay entry : overlays) {
            String coord = entry.geometry() == null ? baseCoord : entry.geometry();
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
            Optional<String> overlayTexture = entry.texture() == null ? Optional.empty() : Optional.of(stripEntity(entry.texture()));
            // retain_bones (warden pulsating spots) restricts the overlay to a vanilla retainExactParts
            // subset of the shared mesh, so the glow texture draws only where vanilla's subset
            // LayerDefinition does. Applied before inflate so surviving cubes inflate together.
            EntityModelData retained = entry.retainBones() == null ? overlayModel : retainExactParts(overlayModel, entry.retainBones());
            boolean hasTint = entry.tint() != null;
            // inflate: an explicit grow is a real vanilla CubeDeformation (tropical_fish 0.008, llama
            // carpet 0.5); every other overlay carries none. A same-mesh grow-less overlay re-submits the
            // base geometry coincident with it, exactly as vanilla submits the identical ModelPart through
            // a second render type, and the depth test resolves the tie in the overlay's favour because it
            // draws second.
            float inflate = entry.grow() != null ? growScalar(entry.grow()) : 0f;
            EntityModelData materialised = inflate != 0f ? inflateModel(retained, inflate) : retained;
            RawPipeline pipeline = entry.pipeline();
            boolean emissive = pipeline != null && pipeline.emissive();
            int overlayTint = hasTint ? ArgbHex.parse(entry.tint(), diagnostics) : WHITE;
            // A same-geometry overlay with no deformation of its own is excluded from the canvas-sizing
            // bounds: it renders the IDENTICAL cube tree as the base, so the base already contributes its
            // full silhouette extent. An explicit grow is a real vanilla CubeDeformation that vanilla's own
            // bounds walk includes, so it keeps contributing. An explicit skip_bounds (llama carpet,
            // NO_RENDER_LAYER_SUFFIXES) always wins.
            boolean coincidentWithBase = sameGeometry && inflate == 0f;
            boolean skipBounds = entry.skipBounds() || coincidentWithBase;
            Optional<String> tintBy = Optional.ofNullable(entry.tintBy());
            Optional<String> textureBy = Optional.ofNullable(entry.textureBy());
            // The overlay's render condition, parsed straight from its `when` object into the typed
            // AppearanceGate (flag/charged/tinted). Absent -> unconditional.
            Optional<AppearanceGate> gate = parseOverlayGate(entry.when(), tintBy, overlayTint);
            // blend / alpha (default NORMAL / 1.0). `additive` -> the energy-swirl glow; `translucent` /
            // `normal` -> source-over. An un-annotated overlay keeps the NORMAL / 1.0 default.
            Composition blend = parseBlend(pipeline == null ? null : pipeline.blend(), diagnostics);
            float alpha = pipeline == null || pipeline.alpha() == null ? 1f : pipeline.alpha();
            // The suppressed-pass mesh: vanilla's clearChild(root).clearRecursively() over the SAME
            // materialised mesh, so the alternate differs from the primary in nothing but the emptied
            // subtree. Derived here rather than from a second geometry coordinate precisely so
            // sameGeometry, the depth-clearance inflate and the derived skipBounds above all stand.
            Optional<EntityModelData> noHatModel = entry.noHatRoot() == null ? Optional.empty()
                : clearSubtreeCubes(materialised, entry.noHatRoot(), entityId, diagnostics);
            out.add(new OverlayLayer(materialised, overlayTexture, emissive, overlayTint, skipBounds, tintBy, textureBy, blend, alpha, gate, noHatModel));
        }
        return out;
    }

    /**
     * Resolves the baby forms of an {@code overlays} list into the parallel {@link OverlayLayer} list a
     * baby render draws in place of the adult one. Each row carrying a {@code baby} delta is rewritten
     * into an overlay whose texture and cleared-bone root come from the delta and whose every other
     * member ({@code texture_by}, tint, {@code retain_bones}, {@code grow}, pipeline, bounds skip, gate)
     * is inherited from the row, then handed to {@link #loadOverlays} against the {@code age.baby} mesh.
     * A row with no delta is absent from the result, so a pass vanilla itself gates off a baby (the
     * villager profession and profession-level passes) drops out structurally.
     *
     * <p>The derived row's geometry is deliberately {@code null} rather than the row's own coordinate: the
     * row names the ADULT mesh, so carrying it would flip {@link #loadOverlays}'s {@code sameGeometry}
     * false, losing the derived bounds skip - the pass would re-enter the canvas union and move the baby
     * canvas. Left null it defaults to {@code babyCoord}, which is the same mesh instance
     * {@link Entity.Axes#babyModel()} holds.
     *
     * @param overlays the family's raw overlay rows
     * @param geometries the geometry coordinate to bone tree table
     * @param babyCoord the family's {@code age.baby} geometry coordinate, or {@code null} when it has none
     * @param babyModel the baby mesh that coordinate resolved to, or empty when it is unknown
     * @param entityId the entity the rows belong to, for diagnostics
     * @param diagnostics the scope read warnings are recorded to
     * @return the baby overlay passes, or an empty list when no row declares a baby form
     */
    private static @NotNull List<OverlayLayer> loadBabyOverlays(
        @NotNull List<RawOverlay> overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @Nullable String babyCoord,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (babyCoord == null) return List.of();
        List<RawOverlay> forms = new ArrayList<>();
        for (RawOverlay entry : overlays) {
            RawOverlayBaby baby = entry.baby();
            if (baby == null) continue;
            // A delta naming its own mesh takes that mesh's deformation as the tooling baked it, so the
            // row's grow is NOT inherited onto it - the adult inflate would land a second time on top
            // of the baby factory's own. A delta reusing the age.baby mesh still inherits it.
            Vector3f babyGrow = baby.grow() != null ? baby.grow()
                : baby.geometry() != null ? null
                : entry.grow();
            forms.add(new RawOverlay(
                baby.geometry(),
                baby.texture() == null ? entry.texture() : baby.texture(),
                entry.retainBones(), baby.noHatRoot(), entry.tint(), entry.tintBy(), entry.textureBy(),
                babyGrow, entry.pipeline(), entry.skipBounds(), entry.when(), null));
        }
        if (forms.isEmpty()) return List.of();
        // Rows declared a baby form but the mesh they would materialise against is missing - the same
        // drop loadOverlays warns about for an adult pass, warned about here rather than silently
        // returning an empty list that reads as "no row declares a baby form".
        if (babyModel.isEmpty()) {
            diagnostics.warn("entity '%s' baby overlay references geometry '%s' absent from entity_geometry", entityId, babyCoord);
            return List.of();
        }
        return loadOverlays(forms, geometries, babyCoord, babyModel.get(), entityId, diagnostics);
    }

    /**
     * Parses an overlay's {@code when} object into a typed {@link AppearanceGate}: {@code flag} maps to
     * {@link AppearanceGate.FlagGate}, {@code charged} to {@link AppearanceGate.ChargedGate}, and
     * {@code tinted} to {@link AppearanceGate.TintedGate} (carrying the overlay's tint axis token and
     * its baked tint, so the gate is self-contained). Absent or unrecognised yields empty
     * (unconditional).
     *
     * @param when the overlay's {@code when} object, or {@code null} when absent
     * @param tintBy the overlay's tint axis token, used to seed a {@link AppearanceGate.TintedGate}
     * @param tintArgb the overlay's baked tint - the colour a {@link AppearanceGate.TintedGate}
     *     selection has to differ from, being what the dye vanilla's own comparison names resolves to
     * @return the parsed gate, or empty when unconditional
     */
    private static @NotNull Optional<AppearanceGate> parseOverlayGate(
        @Nullable RawOverlayWhen when,
        @NotNull Optional<String> tintBy,
        int tintArgb
    ) {
        if (when == null) return Optional.empty();
        if (when.flag() != null)
            return Optional.of(new AppearanceGate.FlagGate(when.flag(), when.value()));
        if (when.charged())
            return Optional.of(new AppearanceGate.ChargedGate());
        if (when.tinted())
            return Optional.of(new AppearanceGate.TintedGate(tintBy.orElse(""), tintArgb));
        return Optional.empty();
    }

    /**
     * Parses an overlay's optional {@code blend} token into a {@link Composition}. {@code "additive"} maps
     * to {@link Composition#ADDITIVE} and {@code "cutout"} to {@link Composition#REPLACE};
     * {@code "translucent"}, {@code "normal"} and an absent token all map to {@link Composition#NORMAL}
     * source-over. An unrecognised value warns and falls back to {@link Composition#NORMAL}.
     *
     * <p>{@code translucent} and {@code normal} share a mapping because source-over is right for both -
     * a translucent pass carries its translucency in the texture's alpha. {@code cutout} is the token
     * that does not: vanilla draws such a pass through a pipeline declaring no blend function at all,
     * so every fragment surviving the alpha threshold is written over the destination rather than into
     * it, alpha included.
     */
    private static @NotNull Composition parseBlend(@Nullable String blend, @NotNull Diagnostics diagnostics) {
        if (blend == null) return Composition.NORMAL;
        return switch (blend.toLowerCase(Locale.ROOT)) {
            case "additive" -> Composition.ADDITIVE;
            case "cutout" -> Composition.REPLACE;
            case "translucent", "normal" -> Composition.NORMAL;
            default -> {
                diagnostics.warn("unknown overlay blend '%s' (expected normal/additive/translucent/cutout); using normal", blend);
                yield Composition.NORMAL;
            }
        };
    }

    // ------------------------------------------------------------------------------------
    // block overlays
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a {@code block_overlays} list into {@link BlockOverlayLayer} rows. A fixed row names its
     * {@code block}; a {@code selectable} row's block is supplied at render from the carried selection, so
     * its {@code block} may be omitted entirely (the enderman carried block). The {@code transforms} entries
     * are the tagged op objects the renderer pattern-matches.
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull List<RawBlockOverlay> array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (RawBlockOverlay row : array) {
            boolean selectable = row.selectable();
            if (row.block() == null && !selectable) continue;
            String blockId = row.block() == null ? "" : row.block();
            String attachedBone = row.attachedBone();
            List<TransformOp> ops = new ArrayList<>();
            for (RawTransform opObj : nullToEmpty(row.transforms())) {
                switch (opObj.op() == null ? "" : opObj.op()) {
                    case "translate" -> ops.add(new Translate(opObj.x(), opObj.y(), opObj.z()));
                    case "rotate_y" -> ops.add(new RotateY(opObj.degrees()));
                    case "rotate_x" -> ops.add(new RotateX(opObj.degrees()));
                    case "rotate_z" -> ops.add(new RotateZ(opObj.degrees()));
                    case "scale" -> ops.add(new Scale(opObj.x(), opObj.y(), opObj.z()));
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
     * Records a variant option's per-state textures ({@code wild} / {@code tame} / {@code angry} + the
     * per-option {@code baby_texture} under {@code "baby"}) when the option carries more than one - a genuine
     * multi-state family (wolf) or an ageable variant (cow). A single-texture option leaves the map empty;
     * the base {@code texture_ref} is the {@code wild} entry either way.
     */
    private static @NotNull Map<String, String> variantStateTextures(@NotNull RawVariantOption optionObj) {
        Map<String, String> states = new LinkedHashMap<>();
        if (optionObj.textures() != null)
            for (Map.Entry<String, String> texture : optionObj.textures().entrySet())
                states.put(texture.getKey(), stripEntity(texture.getValue()));
        if (optionObj.babyTexture() != null) states.put("baby", stripEntity(optionObj.babyTexture()));
        return states.size() > 1 ? states : Map.of();
    }

    /**
     * Returns a variant option's {@code textures.wild} as the base texture ref, or empty when absent.
     */
    private static @NotNull Optional<String> variantWildTexture(@NotNull RawVariantOption optionObj) {
        if (optionObj.textures() == null) return Optional.empty();
        String wild = optionObj.textures().get("wild");
        return wild == null ? Optional.empty() : Optional.of(stripEntity(wild));
    }

    /**
     * Returns the mandatory age axis' {@code options.adult} body - the family baseline (primary
     * {@code geometry}, and for non-variant families the adult {@code texture}).
     */
    private static @NotNull RawAgeOption adultOption(@NotNull RawModel family) {
        return family.axes().age().options().get("adult");
    }

    /** Returns the {@code axes.variant} object when the family carries a variant axis. */
    private static @Nullable RawVariantAxis variantAxis(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        return axes == null ? null : axes.variant();
    }

    /** Returns the {@code age.baby} option object, or {@code null} when the family has no age axis. */
    private static @Nullable RawAgeOption ageBaby(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        if (axes == null || axes.age() == null) return null;
        return axes.age().options().get("baby");
    }

    /** Returns the family's baby geometry coordinate from its {@code age} axis, or {@code null}. */
    private static @Nullable String babyGeometryOf(@NotNull RawModel family) {
        RawAgeOption baby = ageBaby(family);
        return baby == null ? null : baby.geometry();
    }

    /** Returns the family's single stripped baby texture from {@code age.baby.texture}, or {@code null}. */
    private static @Nullable String babyTextureOf(@NotNull RawModel family) {
        RawAgeOption baby = ageBaby(family);
        return baby == null || baby.texture() == null ? null : stripEntity(baby.texture());
    }

    /** Returns the dyed-collar layer's stripped texture, or empty when the family has no collar layer. */
    private static @NotNull Optional<String> collarTextureOf(@NotNull RawModel family) {
        for (RawLayer layer : nullToEmpty(family.layers())) {
            if (!"collar".equals(layer.id())) continue;
            if (layer.overlay() != null && layer.overlay().texture() != null)
                return Optional.of(stripEntity(layer.overlay().texture()));
        }
        return Optional.empty();
    }

    /** Returns whether the family carries a {@code markings} layer (the horse marking overlay). */
    private static boolean markingsOf(@NotNull RawModel family) {
        for (RawLayer layer : nullToEmpty(family.layers()))
            if ("markings".equals(layer.id())) return true;
        return false;
    }

    /**
     * Returns the worn-armor shell the family's armor row is dressed in - the row's mesh joined against
     * the geometry table, the two layer deformations, and the whole-mesh scale the set is registered
     * through - or empty when the family carries no armor row. Absence IS "wears none": being armored is
     * carrying a resolved shell, so a row whose mesh or deformations are missing warns and drops the
     * wearer rather than dressing it in a guess. An absent {@code scaled} is the identity, which is what
     * the eleven wearers vanilla registers unscaled omit.
     *
     * <p>The mesh arrives RAW - none of the entity mesh surgery (the hidden-bone strip, the
     * {@code retainExactParts} subset, the auto-emitted depth-clearance bump) touches it. Worn armor is
     * a shared set vanilla hands the wearer, not a derivative of the wearer's own mesh, so anything done
     * to the body must not follow it onto the shell.
     */
    private static @NotNull Optional<Entity.HumanoidArmor> humanoidArmorOf(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        for (RawLayer layer : nullToEmpty(family.layers())) {
            if (!"armor".equals(layer.id())) continue;
            RawLayerOverlay overlay = layer.overlay();
            RawArmorGrow grow = overlay == null ? null : overlay.grow();
            if (overlay == null || overlay.geometry() == null
                || grow == null || grow.inner() == null || grow.outer() == null) {
                diagnostics.warn("entity '%s' armor layer carries no mesh reference or deformations - wearer dropped", entityId);
                return Optional.empty();
            }
            EntityModelData mesh = geometries.get(overlay.geometry());
            if (mesh == null) {
                diagnostics.warn("entity '%s' armor layer references geometry '%s' absent from entity_geometry", entityId, overlay.geometry());
                return Optional.empty();
            }
            return Optional.of(new Entity.HumanoidArmor(mesh, grow.inner(), grow.outer(),
                overlay.scaled() == null ? 1f : overlay.scaled()));
        }
        return Optional.empty();
    }

    /**
     * Resolves the family's {@code when.equipment}-gated layers into {@link EquipmentOverlay}s, binding
     * each overlay's {@code geometry} coordinate to its baked mesh and decoding its render layer and
     * {@code material -> asset id} table. A layer naming an unknown geometry or an unknown layer type
     * warns and drops.
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        List<EquipmentOverlay> out = new ArrayList<>();
        for (RawLayer layer : nullToEmpty(family.layers())) {
            if (layer.when() == null || layer.overlay() == null) continue;
            if (layer.when().equipment() == null) continue;
            RawLayerOverlay overlay = layer.overlay();
            if (overlay.geometry() == null || overlay.layerType() == null || overlay.defaultMaterial() == null) continue;
            if (overlay.materialAssets() == null || overlay.materialAssets().isEmpty()) continue;
            String coord = overlay.geometry();
            EntityModelData model = geometries.get(coord);
            if (model == null) {
                diagnostics.warn("entity '%s' equipment layer references geometry '%s' absent from entity_geometry", entityId, coord);
                continue;
            }
            Optional<LayerType> layerType = LayerType.fromId(overlay.layerType());
            if (layerType.isEmpty()) {
                diagnostics.warn("entity '%s' equipment layer names unknown layer type '%s'", entityId, overlay.layerType());
                continue;
            }
            Map<String, ResourceId> materialAssets = new LinkedHashMap<>();
            overlay.materialAssets().forEach((material, assetId) -> materialAssets.put(material, ResourceId.parse(assetId)));
            out.add(new EquipmentOverlay(layer.when().equipment(), model, layerType.get(),
                Map.copyOf(materialAssets), overlay.defaultMaterial()));
        }
        return List.copyOf(out);
    }

    /**
     * Resolves the family's {@code shape.large} option (tropical fish) into a {@link LargeShape}: the large
     * body mesh, its stripped base texture, and the pattern overlays materialised on the large geometry.
     * Empty when the family has no shape axis or its large geometry is missing.
     */
    private static @NotNull Optional<LargeShape> buildLargeShape(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        RawAxes axes = family.axes();
        if (axes == null || axes.shape() == null) return Optional.empty();
        RawShapeOption large = axes.shape().options().get("large");
        if (large == null || large.geometry() == null) return Optional.empty();
        String coord = large.geometry();
        EntityModelData model = geometries.get(coord);
        if (model == null) return Optional.empty();
        List<OverlayLayer> overlays = loadOverlays(nullToEmpty(large.overlays()), geometries, coord, model, entityId, diagnostics);
        Optional<String> textureRef = large.texture() != null ? Optional.of(stripEntity(large.texture())) : Optional.of("");
        return Optional.of(new LargeShape(model, textureRef, overlays));
    }

    /**
     * Resolves the family's {@code size} axis geometry alternatives (pufferfish small / medium) into
     * {@code Size -> mesh}. Options carrying a {@code scale} (not a {@code geometry}) are skipped; the
     * default size is the base mesh and never appears here.
     */
    private static @NotNull Map<Size, EntityModelData> buildSizeModels(@NotNull RawModel family, @NotNull Map<String, EntityModelData> geometries) {
        Map<String, RawSizeOption> options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, RawSizeOption> option : options.entrySet()) {
            RawSizeOption body = option.getValue();
            if (body.geometry() == null) continue;
            EntityModelData mesh = geometries.get(body.geometry());
            if (mesh != null) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), mesh);
        }
        return out;
    }

    /**
     * Resolves the family's {@code size} axis scale alternatives (salmon / slime / magma_cube) into
     * {@code Size -> factor}. The default size is scale {@code 1.0} and never appears here.
     */
    private static @NotNull Map<Size, Float> buildSizeScales(@NotNull RawModel family) {
        Map<String, RawSizeOption> options = sizeOptions(family);
        if (options == null) return Map.of();
        Map<Size, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, RawSizeOption> option : options.entrySet()) {
            RawSizeOption body = option.getValue();
            if (body.scale() != null) out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), body.scale());
        }
        return out;
    }

    /** Returns the family's {@code axes.size.options} object, or {@code null} when it has no size axis. */
    private static @Nullable Map<String, RawSizeOption> sizeOptions(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        if (axes == null || axes.size() == null) return null;
        return axes.size().options();
    }

    /**
     * Returns the size the family's base mesh and unit scale represent.
     *
     * <p>The alternate maps hold only the other sizes, so without this name a caller cannot tell which
     * size the bare model already is - which is exactly what a reference key needs in order not to name
     * one appearance two ways.
     *
     * @param family the raw model
     * @return the declared default size, or empty when the family has no size axis
     */
    private static @NotNull Optional<String> sizeDefaultOf(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        if (axes == null || axes.size() == null) return Optional.empty();
        return Optional.ofNullable(axes.size().defaultOption());
    }

    /**
     * Returns the behavioural state the family's base textures represent.
     *
     * @param family the raw model
     * @return the declared default state, or empty when the family has no state axis
     */
    private static @NotNull Optional<String> stateDefaultOf(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        if (axes == null || axes.state() == null) return Optional.empty();
        return Optional.ofNullable(axes.state().defaultOption());
    }

    // ------------------------------------------------------------------------------------
    // bones + geometry surgery
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a family / variant geometry coordinate against the parsed geometry table, failing LOUD on a
     * dangling coordinate.
     */
    private static @NotNull EntityModelData resolveModel(@NotNull Map<String, EntityModelData> geometries, @NotNull String coord, @NotNull String entityId) {
        EntityModelData model = geometries.get(coord);
        if (model == null)
            throw new PipelineException("Entity '%s' references geometry '%s' which is absent from entity_geometry", entityId, coord);
        return model;
    }

    /**
     * Resolves a {@code bones.toggles} object into {@code toggle -> }{@link BoneToggle}, pulling each named
     * bone's {@link EntityModelData.Bone} from the FULL geometry BEFORE the {@code hidden} strip - so a
     * default-hidden toggle's bones are still present for the resolver to re-add (donkey / mule / llama
     * chest). A named bone absent from the geometry warns and drops; a toggle left with no resolvable bones
     * is omitted.
     */
    private static @NotNull Map<String, BoneToggle> loadBoneToggles(
        @Nullable Map<String, RawToggle> toggles,
        @NotNull EntityModelData fullModel,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (toggles == null) return Map.of();
        Map<String, BoneToggle> out = new LinkedHashMap<>();
        for (Map.Entry<String, RawToggle> entry : toggles.entrySet()) {
            RawToggle spec = entry.getValue();
            if (spec.bones() == null) continue;
            boolean defaultVisible = spec.defaultVisible();
            LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
            for (String boneName : spec.bones()) {
                if (boneName == null) continue;
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
     * Returns a copy of {@code model} with the {@code hidden} bones stripped, or {@code model} verbatim when
     * there are none. The Java geometries pack every optional render target into one tree and gate them by
     * entity state at render; the static renderer hides the unwanted ones through this list. A named bone
     * absent from the geometry warns.
     */
    private static @NotNull EntityModelData applyHiddenBones(
        @NotNull EntityModelData model,
        @Nullable List<String> hiddenBones,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        if (hiddenBones == null) return model;
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(model.getBones());
        for (String name : hiddenBones) {
            if (name == null) continue;
            if (bones.remove(name) == null)
                diagnostics.warn("entity '%s' hidden_bones names bone '%s' which is not on the geometry", entityId, name);
        }
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * Restricts an overlay model to the vanilla {@code retainExactParts} subset named by {@code retainBones}:
     * a bone keeps its cubes iff it is named AND no ancestor is (vanilla's {@code clearRecursively} empties a
     * retained part's descendant subtree). Every other bone is kept as a pose-only node so the transform
     * hierarchy stays intact.
     */
    private static @NotNull EntityModelData retainExactParts(@NotNull EntityModelData source, @NotNull List<String> retainBones) {
        Set<String> retain = new LinkedHashSet<>();
        for (String el : retainBones)
            if (el != null) retain.add(el);
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

    /**
     * Returns a copy of {@code source} with the cube list of {@code rootBone} and of every descendant
     * emptied - vanilla's {@code clearChild(name).clearRecursively()}. Every bone keeps its pivot,
     * rotation, bind-pose rotation, scale and parent so the transform hierarchy is untouched and a
     * cube-less bone simply emits nothing; every bone outside the subtree passes through by reference.
     * A {@code rootBone} absent from the mesh warns and yields empty. Package-private so the subtree
     * clear can be pinned on a fixture mesh directly.
     *
     * @param source the mesh to derive from
     * @param rootBone the subtree root whose cubes (and its descendants') are emptied
     * @param entityId the entity the overlay belongs to, for the diagnostic
     * @param diagnostics the load-time warning channel
     * @return the cleared mesh, or empty when {@code rootBone} is not on the geometry
     */
    static @NotNull Optional<EntityModelData> clearSubtreeCubes(
        @NotNull EntityModelData source,
        @NotNull String rootBone,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        Map<String, EntityModelData.Bone> bones = source.getBones();
        if (!bones.containsKey(rootBone)) {
            diagnostics.warn("entity '%s' overlay no_hat_root names bone '%s' which is not on the geometry", entityId, rootBone);
            return Optional.empty();
        }
        Set<String> root = Set.of(rootBone);
        LinkedHashMap<String, EntityModelData.Bone> out = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : bones.entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            boolean cleared = rootBone.equals(e.getKey()) || hasAncestorInSet(bones, bone, root);
            if (!cleared || bone.getCubes().isEmpty()) {
                out.put(e.getKey(), bone);
            } else {
                out.put(e.getKey(), new EntityModelData.Bone(
                    bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                    bone.getScale(), Concurrent.adoptList(new ArrayList<>()), bone.getParent()));
            }
        }
        return Optional.of(new EntityModelData(source.getTextureSize(), source.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(out), source.isCull()));
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
     * Reduces an overlay {@code grow} vector to a single inflate delta - the largest component, so a scalar
     * (broadcast to all axes by {@link EntityModelData.Cube.GrowAdapter}) returns itself and an asymmetric
     * array returns its dominant axis. The delta is applied per-axis by {@link #inflateModel}.
     */
    private static float growScalar(@NotNull Vector3f grow) {
        return Math.max(grow.x(), Math.max(grow.y(), grow.z()));
    }

    /**
     * Returns a deep-cloned copy of {@code model} with every cube's grow bumped by {@code delta} on every
     * axis - surrounding the base mesh with the inflated overlay instead of z-fighting it. Bones, pivots,
     * rotations, UVs, and parent links are preserved verbatim.
     */
    /**
     * Translates a whole mesh along Y by a vanilla {@code PoseStack} distance in blocks - the
     * {@code setupRotations} shift, applied as mesh surgery.
     *
     * <p>It lands on the mesh rather than on a render parameter because the renderer and the
     * canvas-sizing bounds walk both read the mesh: moving it is what keeps a subject and the frame
     * measured around it from disagreeing. That also makes the shift invisible for a subject measured
     * against its OWN silhouette, which is correct - the fit centres what it measured, so subject and
     * frame move together - and leaves it visible exactly where vanilla makes it visible, inside a
     * canvas unioned across group members whose shifts differ.
     *
     * <p>Vanilla translates in world space where {@code +Y} is up, and the mesh is authored Y-down, so
     * the sign flips on the way in. Only root bones move: a child's pivot is relative to its parent, so
     * the whole tree follows them.
     *
     * @param source the mesh to translate
     * @param blocks the vanilla translation in blocks, {@code 0f} to return the source untouched
     * @return the translated mesh, or {@code source} when there is nothing to apply
     */
    private static @NotNull EntityModelData shiftModel(@NotNull EntityModelData source, float blocks) {
        if (blocks == 0f) return source;
        float delta = -blocks * MODEL_UNITS_PER_BLOCK;
        LinkedHashMap<String, EntityModelData.Bone> shifted = new LinkedHashMap<>();
        for (Map.Entry<String, EntityModelData.Bone> e : source.getBones().entrySet()) {
            EntityModelData.Bone bone = e.getValue();
            if (bone.getParent() != null) {
                shifted.put(e.getKey(), bone);
                continue;
            }
            Vector3f pivot = bone.getPivot();
            shifted.put(e.getKey(), new EntityModelData.Bone(
                new Vector3f(pivot.x(), pivot.y() + delta, pivot.z()),
                bone.getRotation(), bone.getBindPoseRotation(),
                bone.getScale(), bone.getCubes(), bone.getParent()));
        }
        return new EntityModelData(source.getTextureSize(), source.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(shifted), source.isCull());
    }

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

    /**
     * Reduces a full entity texture path to the {@code textures/entity/}-relative sub-path the texture
     * resolver re-qualifies as {@code minecraft:entity/<ref>} - dropping the {@code minecraft:textures/entity/}
     * prefix and the {@code .png} suffix.
     */
    private static @NotNull String stripEntity(@NotNull String path) {
        if (!path.startsWith(TEXTURE_PREFIX) || !path.endsWith(TEXTURE_SUFFIX))
            throw new PipelineException("Unexpected entity texture path '%s' (expected '%s<sub>%s')", path, TEXTURE_PREFIX, TEXTURE_SUFFIX);
        return path.substring(TEXTURE_PREFIX.length(), path.length() - TEXTURE_SUFFIX.length());
    }

    /** Returns {@code list} unchanged, or an empty list when it is {@code null} (an absent JSON array). */
    private static <T> @NotNull List<T> nullToEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }
}
