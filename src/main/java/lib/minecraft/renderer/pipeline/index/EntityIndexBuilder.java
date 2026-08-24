package lib.minecraft.renderer.pipeline.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
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
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.equipment.ArmorForm;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseChannel;
import lib.minecraft.renderer.asset.pose.PoseExpr;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.pipeline.util.ArgbHex;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the runtime entity index from the two pure reads: joins each {@link RawModel}'s geometry
 * coordinate against the geometry table, runs the mesh surgery (undrawn strip, {@code retainExactParts}
 * subset, {@code grow} inflate plus the auto-emitted depth-clearance bump), pivots the option axes into
 * {@link Entity.Axes}, folds each variant option into a sub-{@link Entity}, and stamps cross-entity
 * canvas-group membership. The leaf decodes (hex tint, {@code blend} token, texture strip, transform ops)
 * happen here too - the raw records carry them verbatim.
 *
 * <p>Every family folds to one base row: option-encoded coats live on {@code axes.variants} and are
 * measured by the group canvas union rather than expanded into member rows of their own. That is a
 * <b>collision-safety</b> rule rather than a convenience. Expanding a coat into a row would have to
 * synthesize its key by concatenation - {@code minecraft:wolf} plus {@code ashen} - into the same flat
 * keyspace real entity ids occupy, and nothing would then distinguish the two: a future vanilla entity
 * of that shape, or third-party {@code namespace:id} content, gives one key two claimants. The index
 * keyspace <b>is</b> the vanilla entity registry, only Minecraft declares a top-level row in it, and a
 * coat is therefore a selection over a row rather than a row. That makes the collision unrepresentable
 * instead of merely unlikely, which is why it must not be reintroduced as a convenience.
 */
@Parity(claim = "index-resolution")
@UtilityClass
public final class EntityIndexBuilder {

    private static final @NotNull String TEXTURE_PREFIX = "minecraft:textures/entity/";
    private static final @NotNull String TEXTURE_SUFFIX = ".png";

    /** Model units per block - the scale a vanilla {@code PoseStack} translate is expressed against. */
    private static final float MODEL_UNITS_PER_BLOCK = 16f;

    private static final int WHITE = 0xFFFFFFFF;

    /**
     * Assembles the entity index from the raw model tree and the geometry table.
     *
     * @param geometries the geometry coordinate to bone tree table
     * @param rawFile the raw model catalog
     * @param poses the pose of each model class, by the simple name a coordinate is headed with
     * @param renderTransforms the steps each renderer composes above its meshes, by renderer simple name
     * @return definitions keyed by namespaced entity id, in file order
     * @throws PipelineException if an entity references a geometry coordinate absent from the geometry file
     */
    public static @NotNull ConcurrentMap<String, Entity> assemble(
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull RawEntityModelsFile rawFile,
        @NotNull Map<String, EntityPose> poses,
        @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> renderTransforms
    ) {
        Map<String, RawModel> models = rawFile.models();
        if (models == null) return Concurrent.newMap();
        LinkedHashMap<String, Entity> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, RawModel> entry : models.entrySet()) {
            if (entry.getValue() == null) continue;
            readDefinition(entry.getKey(), entry.getValue(), geometries, poses, renderTransforms, definitions);
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
            definitions.put(group.getKey(), entity.mutate().members(group.getValue()).build());
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
            // One base row per model, variant or not. Option-encoded coats live on the base
            // definition's axes.variants and are measured by the group canvas union.
            variantOf.put(familyId, null);
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
     * Reads one model into its single {@link Entity} row and adds it to {@code definitions}. A variant
     * model's coats are built into the row's option map rather than into rows of their own.
     */
    private static void readDefinition(
        @NotNull String familyId,
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> renderTransforms,
        @NotNull Map<String, Entity> definitions
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
        int baseTint = render == null || render.tint() == null ? WHITE : ArgbHex.parse(render.tint());
        List<Map<PoseChannel, PoseExpr>> renderTransform =
            renderTransformOf(renderTransforms, family, familyId, adult.yShift(), babyYShift);

        RawBones bones = family.bones();
        List<String> undrawnBones = bones == null ? null : bones.undrawn();
        Map<String, RawToggle> boneToggleSpecs = bones == null ? null : bones.toggles();
        // The class the body's pose is read against, which is the renderer's own and not always the
        // one that baked the mesh - a model reusing its parent's layer is headed with the parent
        // everywhere a coordinate appears, and reading it there poses a zombie as a plain humanoid.
        // Written only where the two disagree, so a coordinate answers for the rest.
        String poseClass = bones == null ? null : bones.pose();

        List<RawOverlay> familyOverlays = nullToEmpty(family.overlays());
        List<BlockOverlayLayer> blockOverlays = family.blockOverlays() == null ? List.of() : loadBlockOverlays(family.blockOverlays());

        Optional<String> collarTexture = collarTextureOf(family);
        List<EquipmentOverlay> equipment = loadEquipment(family, geometries, familyId);
        boolean markings = markingsOf(family);
        Optional<Shell> humanoidArmor = humanoidArmorOf(family, geometries, familyId);
        String babyCoord = babyGeometryOf(family);
        // Beside the baby MESH rather than derived from it: a baby is its own model class, and two of
        // the families that pose at all are posed through that class alone.
        Optional<EntityPose> babyPose = babyCoord == null ? Optional.empty() : Optional.of(poseOf(poses, babyCoord));
        // A baby takes the strip its own option carries, the way the body and every size mesh do - a
        // baby armadillo rests walking rather than curled, and its shell is one bone of the mesh
        // either way. Not the family's list, though: that is read off the ADULT's model class, where
        // the baby option's is read off the baby's own.
        Optional<EntityModelData> babyModel = babyCoord == null ? Optional.empty()
            : Optional.ofNullable(geometries.get(babyCoord))
                .map(baby -> shiftModel(
                    applyUndrawn(baby, babyAge == null ? null : babyAge.undrawn(), familyId),
                    babyYShift));
        List<OverlayLayer> babyOverlays = loadBabyOverlays(familyOverlays, geometries, poses,
            babyPose.orElse(EntityPose.NONE), babyCoord, babyModel, familyId);

        RawVariantAxis variant = variantAxis(family);
        if (variant != null) {
            String defaultOption = variant.defaultOption();
            Map<String, RawVariantOption> options = variant.options();
            VariantContext ctx = new VariantContext(baseCoord, poseClass, geometries, poses, renderTransform, undrawnBones, boneToggleSpecs, familyOverlays,
                blockOverlays, baseTint, setupYawAddend, rendererScale, babyModel, babyPose, babyOverlays, collarTexture, equipment, markings, humanoidArmor,
                stateDefaultOf(family));
            // one base row minecraft:<id>, the coat resolved at render. Every option
            // is built into a sub-definition; the base row IS the default coat carrying the full option map.
            LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
            for (Map.Entry<String, RawVariantOption> option : options.entrySet())
                coats.put(option.getKey(), buildVariantRow(familyId, option.getValue(), ctx));
            Entity base = coats.getOrDefault(defaultOption, coats.values().iterator().next());
            Entity.Axes baseAxes = base.axes();
            definitions.put(familyId, base.mutate()
                .axes(new Entity.Axes(baseAxes.stateTextures(), baseAxes.babyModel(), baseAxes.babyPose(), baseAxes.babyOverlays(),
                    baseAxes.largeShape(), baseAxes.sizeModels(), baseAxes.sizeScales(), Map.copyOf(coats),
                    Optional.ofNullable(defaultOption), stateDefaultOf(family), sizeDefaultOf(family)))
                .build());
            return;
        }

        // Plain family: one row. The size / shape axes attach only to plain families, so they resolve here.
        EntityModelData model = resolveModel(geometries, baseCoord, familyId);
        EntityPose pose = poseOf(poses, poseClass == null ? baseCoord : poseClass);
        Map<String, BoneToggle> toggles = loadBoneToggles(boneToggleSpecs, model, undrawnBones, familyId);
        model = applyUndrawn(model, undrawnBones, familyId);
        // Ahead of the overlay load so a same-geometry pass is materialised on the shifted mesh and
        // travels with it; a pass on a mesh of its OWN would not, which the shift warns about.
        model = shiftModel(model, adult.yShift());
        List<OverlayLayer> overlays = loadOverlays(familyOverlays, geometries, poses, pose, baseCoord, model, familyId);
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
            .pose(pose)
            .renderTransform(renderTransform)
            .axes(new Entity.Axes(stateTextures, babyModel, babyPose, babyOverlays,
                buildLargeShape(family, geometries, poses, familyId),
                buildSizeModels(family, geometries, familyId),
                buildSizeScales(family), Map.of(), Optional.empty(), stateDefaultOf(family), sizeDefaultOf(family)))
            .layers(new Entity.Layers(collarTexture, equipment, markings, humanoidArmor))
            .build());
    }

    /**
     * The steps this subject's renderer composes above every mesh it submits.
     *
     * <p>Joined on the renderer's simple name, which the model table carries per subject and the
     * pose table keys its transforms by. A subject whose renderer the walk refused, or which
     * composes nothing at all, has no row and stands where its mesh puts it.
     *
     * <p><b>A transform and a {@code y_shift} are two spellings of one {@code setupRotations} and
     * only one of them may answer.</b> The shift is applied to the mesh at load because the bounds
     * walk reads the mesh; the transform composes above it at render. Both together would move the
     * subject twice, so a subject carrying both is refused rather than silently doubled - the
     * corpus has none, every renderer the shift claims being one the transform walk declines.
     *
     * @throws PipelineException if a subject carries both a render transform and an age shift
     */
    private static @NotNull List<Map<PoseChannel, PoseExpr>> renderTransformOf(
        @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> transforms, @NotNull RawModel family,
        @NotNull String familyId, float adultYShift, float babyYShift) {

        String renderer = family.renderer();
        if (renderer == null) return List.of();
        int member = renderer.lastIndexOf('/');
        List<Map<PoseChannel, PoseExpr>> steps =
            transforms.getOrDefault(member < 0 ? renderer : renderer.substring(member + 1), List.of());
        if (!steps.isEmpty() && (adultYShift != 0f || babyYShift != 0f))
            throw new PipelineException(
                "entity index: '%s' carries both a render transform and a setupRotations y shift, "
                    + "which would move it twice",
                familyId);
        return steps;
    }

    /**
     * The pose of whatever model a geometry coordinate names.
     *
     * <p>The join is the coordinate's own head: a coordinate is {@code Class#member} and a pose is
     * keyed by that class, so nothing had to be threaded through the model table to say which pose
     * a mesh takes. A coordinate whose class the pose table does not name poses nothing, which is
     * the honest answer for every mesh the walk never looked at - a worn shell, a saddle, a mesh
     * derived under a suffix.
     *
     * @param poses the pose of each model class, by simple name
     * @param coordinate the geometry coordinate to resolve
     * @return the pose that model takes, or the pose of a model that poses nothing
     */
    private static @NotNull EntityPose poseOf(@NotNull Map<String, EntityPose> poses, @NotNull String coordinate) {
        int member = coordinate.indexOf('#');
        return poses.getOrDefault(member < 0 ? coordinate : coordinate.substring(0, member), EntityPose.NONE);
    }

    /**
     * The family-level render context shared by every variant option's build, so one coat build fills
     * the whole option map without restating what the coats have in common.
     */
    private record VariantContext(
        @NotNull String baseCoord,
        @Nullable String poseClass,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull List<Map<PoseChannel, PoseExpr>> renderTransform,
        @Nullable List<String> undrawnBones,
        @Nullable Map<String, RawToggle> boneToggleSpecs,
        @NotNull List<RawOverlay> familyOverlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        int baseTint,
        float setupYawAddend,
        float rendererScale,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull Optional<EntityPose> babyPose,
        @NotNull List<OverlayLayer> babyOverlays,
        @NotNull Optional<String> collarTexture,
        @NotNull List<EquipmentOverlay> equipment,
        boolean markings,
        @NotNull Optional<Shell> humanoidArmor,
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
        @NotNull VariantContext ctx
    ) {
        String rowCoord = optionObj.geometry() == null ? ctx.baseCoord() : optionObj.geometry();
        EntityModelData model = resolveModel(ctx.geometries(), rowCoord, rowId);
        // A coat swaps the mesh and never the renderer, so the class the pose is read against is the
        // family's whatever geometry this option names.
        EntityPose pose = poseOf(ctx.poses(), ctx.poseClass() == null ? rowCoord : ctx.poseClass());
        Map<String, BoneToggle> toggles = loadBoneToggles(ctx.boneToggleSpecs(), model, ctx.undrawnBones(), rowId);
        model = applyUndrawn(model, ctx.undrawnBones(), rowId);
        List<OverlayLayer> overlays = loadOverlays(ctx.familyOverlays(), ctx.geometries(), ctx.poses(), pose, rowCoord, model, rowId);
        Map<String, String> stateTextures = variantStateTextures(optionObj);
        Optional<String> textureRef = variantWildTexture(optionObj);
        return Entity.builder()
            .id(ResourceId.parse(rowId))
            .model(model).textureRef(textureRef).overlays(overlays)
            .blockOverlays(coatBlockOverlays(ctx.blockOverlays(), optionObj.block()))
            .baseTintArgb(ctx.baseTint()).setupYawAddend(ctx.setupYawAddend()).rendererScale(ctx.rendererScale())
            .boneToggles(toggles)
            .pose(pose)
            .renderTransform(ctx.renderTransform())
            .axes(new Entity.Axes(stateTextures, ctx.babyModel(), ctx.babyPose(), ctx.babyOverlays(), Optional.empty(),
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
        @NotNull Map<String, EntityPose> poses,
        @NotNull EntityPose bodyPose,
        @NotNull String baseCoord,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId
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
                    // TODO: restore pipeline diagnostics
                    // diagnostics.warn("entity '%s' overlay references geometry '%s' absent from entity_geometry", entityId, coord);
                    continue;
                }
            }
            // A pass poses its mesh with its own model class, so it reads the pose its own coordinate
            // names - and a pass drawing the body's mesh takes the BODY's pose, which is the one the
            // family's own posing class was resolved against rather than whatever the coordinate says.
            EntityPose overlayPose = sameGeometry ? bodyPose : poseOf(poses, coord);
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
            int overlayTint = hasTint ? ArgbHex.parse(entry.tint()) : WHITE;
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
            BlendMode blend = parseBlend(pipeline == null ? null : pipeline.blend());
            float alpha = pipeline == null || pipeline.alpha() == null ? 1f : pipeline.alpha();
            // depth_write / sorted are vanilla's own DepthStencilState.writeDepth and
            // RenderSetup.sortOnUpload, each omitted at its identity: an un-annotated pass writes depth
            // (what DepthStencilState.DEFAULT declares) and draws in emission order.
            boolean writesDepth = pipeline == null || pipeline.depthWrite() == null || pipeline.depthWrite();
            boolean sorted = pipeline != null && pipeline.sorted();
            // The suppressed-pass mesh: vanilla's clearChild(root).clearRecursively() over the SAME
            // materialised mesh, so the alternate differs from the primary in nothing but the emptied
            // subtree. Derived here rather than from a second geometry coordinate precisely so
            // sameGeometry, the depth-clearance inflate and the derived skipBounds above all stand.
            Optional<EntityModelData> noHatModel = entry.noHatRoot() == null ? Optional.empty()
                : clearSubtreeCubes(materialised, entry.noHatRoot(), entityId);
            PassDeclaration pass = new PassDeclaration(emissive, blend, alpha, writesDepth, sorted);
            out.add(new OverlayLayer(materialised, overlayTexture, pass, overlayTint, skipBounds, tintBy,
                textureBy, gate, noHatModel, overlayPose, textureScroll(entry, entityId)));
        }
        return out;
    }

    /**
     * The fraction of the sheet an overlay's render type translates its texture by each tick.
     *
     * <p>Refused when either axis is negative. The scroll is added to a UV before the fetch takes a
     * texel index, and a negative offset would carry the sample point below the sheet - where the
     * truncation the index is taken by rounds toward zero rather than down, so it would sample the
     * wrong texel rather than wrap. No shipped row is negative, vanilla accumulating age forward.
     *
     * @throws PipelineException if a row declares a scroll along either axis in the negative
     */
    private static @NotNull Optional<Vector2f> textureScroll(
        @NotNull RawOverlay entry, @NotNull String entityId) {

        RawTextureScroll declared = entry.textureScroll();
        if (declared == null) return Optional.empty();
        if (declared.u() < 0f || declared.v() < 0f)
            throw new PipelineException(
                "entity index: '%s' scrolls a pass by (%s, %s), and a scroll runs forward",
                entityId, declared.u(), declared.v());
        return Optional.of(new Vector2f(declared.u(), declared.v()));
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
     * @return the baby overlay passes, or an empty list when no row declares a baby form
     */
    private static @NotNull List<OverlayLayer> loadBabyOverlays(
        @NotNull List<RawOverlay> overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull EntityPose babyPose,
        @Nullable String babyCoord,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull String entityId
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
                babyGrow, entry.pipeline(), entry.textureScroll(), entry.skipBounds(), entry.when(), null));
        }
        if (forms.isEmpty()) return List.of();
        // Rows declared a baby form but the mesh they would materialise against is missing - the same
        // drop loadOverlays warns about for an adult pass, warned about here rather than silently
        // returning an empty list that reads as "no row declares a baby form".
        if (babyModel.isEmpty()) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' baby overlay references geometry '%s' absent from entity_geometry", entityId, babyCoord);
            return List.of();
        }
        return loadOverlays(forms, geometries, poses, babyPose, babyCoord, babyModel.get(), entityId);
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
     * Parses an overlay's optional {@code blend} token into a {@link BlendMode}. {@code "additive"} maps
     * to {@link BlendMode#ADD} and {@code "cutout"} to {@link BlendMode#REPLACE};
     * {@code "translucent"}, {@code "normal"} and an absent token all map to {@link BlendMode#NORMAL}
     * source-over. An unrecognised value warns and falls back to {@link BlendMode#NORMAL}.
     *
     * <p>{@code translucent} and {@code normal} share a mapping because source-over is right for both -
     * a translucent pass carries its translucency in the texture's alpha. {@code cutout} is the token
     * that does not: vanilla draws such a pass through a pipeline declaring no blend function at all,
     * so every fragment surviving the alpha threshold is written over the destination rather than into
     * it, alpha included.
     */
    private static @NotNull BlendMode parseBlend(@Nullable String blend) {
        if (blend == null) return BlendMode.NORMAL;
        return switch (blend.toLowerCase(Locale.ROOT)) {
            case "additive" -> BlendMode.ADD;
            case "cutout" -> BlendMode.REPLACE;
            case "translucent", "normal" -> BlendMode.NORMAL;
            default -> {
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("unknown overlay blend '%s' (expected normal/additive/translucent/cutout); using normal", blend);
                yield BlendMode.NORMAL;
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
     * <p>The mesh arrives RAW - none of the entity mesh surgery (the undrawn strip, the
     * {@code retainExactParts} subset, the auto-emitted depth-clearance bump) touches it. Worn armor is
     * a shared set vanilla hands the wearer, not a derivative of the wearer's own mesh, so anything done
     * to the body must not follow it onto the shell.
     *
     * <p>The row's {@code alternate} node, where it carries one, is folded in as a second shell the
     * axis it names swaps to. A wearer without one dresses both its forms in the same shell, which
     * is what vanilla does when it hands its armor layer one set twice.
     */
    private static @NotNull Optional<Shell> humanoidArmorOf(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        for (RawLayer layer : nullToEmpty(family.layers())) {
            if (!"armor".equals(layer.id())) continue;
            RawLayerOverlay overlay = layer.overlay();
            if (overlay == null) {
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("entity '%s' armor layer carries no mesh reference or deformations - wearer dropped", entityId);
                return Optional.empty();
            }
            RawArmorAlternate raw = overlay.alternate();
            Optional<Shell.Alternate> alternate = Optional.empty();
            if (raw != null) {
                alternate = alternateShellOf(raw, geometries, entityId);
                if (alternate.isEmpty()) return Optional.empty();
            }
            return shellOf(overlay.geometry(), overlay.grow(), overlay.scaled(), ArmorForm.ADULT, alternate,
                geometries, entityId);
        }
        return Optional.empty();
    }

    /**
     * The second shell an armor row carries, paired with the selection that reaches it. Empty when the
     * mesh, the selection or the form is unreadable, which drops the whole wearer rather than dressing
     * one of its forms in the other's shell.
     *
     * <p>An <em>absent</em> {@code form} is the adult one, which is what the shipped file omits it to
     * mean; a {@code form} that names no shell is a defect and is reported as one. Reading an unknown
     * token as adult instead would silently give a baby wearer the wrong part table, the wrong sheet
     * and a trim vanilla never draws.
     */
    private static @NotNull Optional<Shell.Alternate> alternateShellOf(
        @NotNull RawArmorAlternate raw,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        Optional<AppearanceGate> when = parseAlternateGate(raw.when());
        if (when.isEmpty()) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' alternate armor shell names no appearance selection - wearer dropped", entityId);
            return Optional.empty();
        }
        Optional<ArmorForm> form = raw.form() == null
            ? Optional.of(ArmorForm.ADULT)
            : enumOf(ArmorForm.class, raw.form());
        if (form.isEmpty()) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' alternate armor shell names unknown form '%s' - wearer dropped",
            // entityId, raw.form());
            return Optional.empty();
        }
        return shellOf(raw.geometry(), raw.grow(), raw.scaled(), form.get(), Optional.empty(),
            geometries, entityId)
            .map(shell -> new Shell.Alternate(when.get(), shell));
    }

    /**
     * Parses an alternate shell's {@code when} object into the typed selection that swaps to it -
     * {@code age} to an {@link AppearanceGate.AgeGate} and {@code size} to an
     * {@link AppearanceGate.SizeGate}. Empty for an absent or unreadable option, which is a shell
     * nothing could ever select rather than one that always applies.
     */
    private static @NotNull Optional<AppearanceGate> parseAlternateGate(@Nullable RawLayerWhen when) {
        if (when == null) return Optional.empty();
        if (when.age() != null)
            return enumOf(Age.class, when.age()).map(AppearanceGate.AgeGate::new);
        if (when.size() != null)
            return enumOf(Size.class, when.size()).map(AppearanceGate.SizeGate::new);
        return Optional.empty();
    }

    /** The constant of an enum matching a lower-case option token, or empty when it names none. */
    private static <E extends Enum<E>> @NotNull Optional<E> enumOf(@NotNull Class<E> type, @NotNull String token) {
        for (E constant : type.getEnumConstants())
            if (constant.name().equalsIgnoreCase(token)) return Optional.of(constant);
        return Optional.empty();
    }

    /**
     * One shell of an armor row - its mesh joined against the geometry table, the two layer
     * deformations, and the whole-mesh scale the set is registered through. Absence IS "wears none":
     * being armored is carrying a resolved shell, so a shell whose mesh or deformations are missing
     * warns and drops the wearer rather than dressing it in a guess.
     */
    private static @NotNull Optional<Shell> shellOf(
        @Nullable String geometry,
        @Nullable RawArmorGrow grow,
        @Nullable Float scaled,
        @NotNull ArmorForm form,
        @NotNull Optional<Shell.Alternate> alternate,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        if (geometry == null || grow == null || grow.inner() == null || grow.outer() == null) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' %s armor shell carries no mesh reference or deformations - wearer dropped",
            // entityId, form.name().toLowerCase(Locale.ROOT));
            return Optional.empty();
        }
        EntityModelData mesh = geometries.get(geometry);
        if (mesh == null) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' %s armor shell references geometry '%s' absent from entity_geometry",
            // entityId, form.name().toLowerCase(Locale.ROOT), geometry);
            return Optional.empty();
        }
        return Optional.of(new Shell(mesh, grow.inner(), grow.outer(),
            scaled == null ? 1f : scaled, form, alternate));
    }

    /**
     * Resolves the family's {@code when.equipment}-gated layers into {@link EquipmentOverlay}s, binding
     * each overlay's {@code geometry} coordinate to its baked mesh and decoding its render layer and
     * {@code material -> asset id} table. A layer naming an unknown geometry or an unknown layer type
     * warns and drops.
     *
     * <p>A layer takes the same bone surgery the body does, off its own bones node: an equine
     * saddle's reins are drawn while something is riding and a donkey's saddle carries no chest
     * until the donkey does, and the mesh a layer draws holds those bones either way. Its
     * {@code undrawn} list is resolved at generation against its OWN model class's pose, so a layer
     * posed by a class the walk never looked at carries none and rests as it is baked.
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
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
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("entity '%s' equipment layer references geometry '%s' absent from entity_geometry", entityId, coord);
                continue;
            }
            Optional<LayerType> layerType = LayerType.fromId(overlay.layerType());
            if (layerType.isEmpty()) {
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("entity '%s' equipment layer names unknown layer type '%s'", entityId, overlay.layerType());
                continue;
            }
            RawBones bones = overlay.bones();
            // Off the FULL mesh, ahead of the strip, for the reason the body's are: a toggle whose
            // bones rest undrawn has to keep them somewhere to re-add them from.
            Map<String, BoneToggle> toggles = loadBoneToggles(
                bones == null ? null : bones.toggles(), model,
                bones == null ? null : bones.undrawn(), entityId);
            model = applyUndrawn(model, bones == null ? null : bones.undrawn(), entityId);
            Map<String, ResourceId> materialAssets = new LinkedHashMap<>();
            overlay.materialAssets().forEach((material, assetId) -> materialAssets.put(material, ResourceId.parse(assetId)));
            out.add(new EquipmentOverlay(layer.when().equipment(), model, layerType.get(),
                Map.copyOf(materialAssets), overlay.defaultMaterial(), toggles));
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
        @NotNull Map<String, EntityPose> poses,
        @NotNull String entityId
    ) {
        RawAxes axes = family.axes();
        if (axes == null || axes.shape() == null) return Optional.empty();
        RawShapeOption large = axes.shape().options().get("large");
        if (large == null || large.geometry() == null) return Optional.empty();
        String coord = large.geometry();
        EntityModelData model = geometries.get(coord);
        if (model == null) return Optional.empty();
        List<OverlayLayer> overlays = loadOverlays(nullToEmpty(large.overlays()), geometries, poses,
            poseOf(poses, coord), coord, model, entityId);
        Optional<String> textureRef = large.texture() != null ? Optional.of(stripEntity(large.texture())) : Optional.of("");
        return Optional.of(new LargeShape(model, textureRef, overlays));
    }

    /**
     * Resolves the family's {@code size} axis geometry alternatives (pufferfish small / medium, the
     * small armor stand) into {@code Size -> mesh}. Options carrying a {@code scale} (not a
     * {@code geometry}) are skipped; the default size is the base mesh and never appears here.
     *
     * <p>A size mesh is the same subject's body at another size, so it takes the same surgery the
     * base body does - its option's undrawn strip and the age's Y shift. Reaching for the raw mesh
     * instead drew the armor stand's arms on its small form and not on its full one, off one mesh the
     * strip had run over and one it had not. The two fish carry no list and no shift, so both passes
     * are the identity on every subject that had a size axis before.
     */
    private static @NotNull Map<Size, EntityModelData> buildSizeModels(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        Map<String, RawSizeOption> options = sizeOptions(family);
        if (options == null) return Map.of();
        float yShift = adultOption(family).yShift();
        Map<Size, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, RawSizeOption> option : options.entrySet()) {
            RawSizeOption body = option.getValue();
            if (body.geometry() == null) continue;
            EntityModelData mesh = geometries.get(body.geometry());
            if (mesh == null) continue;
            mesh = applyUndrawn(mesh, body.undrawn(), entityId);
            mesh = shiftModel(mesh, yShift);
            out.put(Size.valueOf(option.getKey().toUpperCase(Locale.ROOT)), mesh);
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
     * bone's {@link EntityModelData.Bone} from the FULL geometry BEFORE the {@code undrawn} strip - so a
     * default-hidden toggle's bones are still present for the resolver to re-add (donkey / mule / llama
     * chest). A named bone absent from the geometry warns and drops; a toggle left with no resolvable bones
     * is omitted.
     *
     * <p><b>Which way a toggle points is READ OFF THE UNDRAWN LIST rather than declared beside
     * it.</b> A toggle is a name for the state a subject is not resting in, and what it rests
     * without is what the site's list already says: a donkey's chest is not drawn because its list
     * names it, and a goat's horns are because no list does. Deriving it keeps one answer to that
     * question instead of two that can drift apart - and they had, on the bee.
     */
    private static @NotNull Map<String, BoneToggle> loadBoneToggles(
        @Nullable Map<String, RawToggle> toggles,
        @NotNull EntityModelData fullModel,
        @Nullable List<String> undrawn,
        @NotNull String entityId
    ) {
        if (toggles == null) return Map.of();
        Map<String, BoneToggle> out = new LinkedHashMap<>();
        for (Map.Entry<String, RawToggle> entry : toggles.entrySet()) {
            RawToggle spec = entry.getValue();
            if (spec.bones() == null) continue;
            boolean defaultVisible = restsDrawn(undrawn, spec.bones());
            LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>();
            for (String boneName : spec.bones()) {
                if (boneName == null) continue;
                EntityModelData.Bone bone = fullModel.getBones().get(boneName);
                if (bone == null) {
                    // TODO: restore pipeline diagnostics
                    // diagnostics.warn("entity '%s' bone_toggles '%s' names bone '%s' which is not on the geometry", entityId, entry.getKey(), boneName);
                    continue;
                }
                bones.put(boneName, bone);
            }
            if (!bones.isEmpty()) out.put(entry.getKey(), new BoneToggle(bones, defaultVisible));
        }
        return out;
    }

    /**
     * Whether the bones one toggle names are drawn at rest.
     *
     * <p>The bones a toggle holds are one thing shown or hidden together, so they answer together;
     * a toggle whose bones disagreed would be two toggles wearing one name. A named bone the site's
     * undrawn list carries answers hidden for them all, and a toggle naming none of them rests
     * drawn - a subtree is undrawn by its own root, and the list carries the root where its
     * children are simply carried along.
     */
    private static boolean restsDrawn(@Nullable List<String> undrawn, @NotNull List<String> named) {
        if (undrawn == null || undrawn.isEmpty()) return true;
        for (String bone : named)
            if (bone != null && undrawn.contains(bone)) return false;
        return true;
    }

    /**
     * Strips the bones a site's {@code undrawn} list names, or returns {@code model} verbatim when
     * it names none the mesh has. The list carries what the subject rests not drawing, resolved at
     * generation; a toggle re-adds its bones from the pre-strip mesh where a selection flips them.
     *
     * <p><b>A bone that does not draw takes its subtree with it.</b> Vanilla's {@code visible = false}
     * skips the part and everything hanging off it, and a name-only removal does something else
     * entirely: an orphan's parent lookup misses, so {@link lib.minecraft.renderer.engine.kit.BoneKit
     * BoneKit} anchors it at the root instead, and geometry that should have vanished lands somewhere
     * the subject is not. A zombie nautilus is what that looks like - its {@code corals} group left,
     * and four sprigs of coral floating clear of the shell. The closure runs here, per mesh, because
     * one family list serves the body and every coat and a subtree is each mesh's own.
     */
    private static @NotNull EntityModelData applyUndrawn(
        @NotNull EntityModelData model,
        @Nullable List<String> undrawn,
        @NotNull String entityId
    ) {
        if (undrawn == null || undrawn.isEmpty()) return model;
        LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(model.getBones());
        Set<String> named = new LinkedHashSet<>();
        for (String name : undrawn)
            if (name != null && bones.containsKey(name)) named.add(name);
        if (named.isEmpty()) return model;
        bones.keySet().removeAll(withDescendants(bones, named));
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * Closes a set of bones downwards over the bone forest, so what it holds is whole subtrees.
     *
     * <p>A fixpoint rather than one pass, because a bone map is in the tooling's own
     * {@code addOrReplaceChild} order and a child can precede its parent in it.
     *
     * @param bones the mesh's bones keyed by name
     * @param seed the bones to close over, which is left untouched
     * @return the seed and every bone reached from it through a parent chain
     */
    private static @NotNull Set<String> withDescendants(
        @NotNull Map<String, EntityModelData.Bone> bones, @NotNull Set<String> seed) {

        Set<String> closed = new LinkedHashSet<>(seed);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, EntityModelData.Bone> bone : bones.entrySet()) {
                if (closed.contains(bone.getKey())) continue;
                String parent = bone.getValue().getParent();
                if (parent != null && closed.contains(parent)) {
                    closed.add(bone.getKey());
                    grew = true;
                }
            }
        }
        return closed;
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
     * @return the cleared mesh, or empty when {@code rootBone} is not on the geometry
     */
    static @NotNull Optional<EntityModelData> clearSubtreeCubes(
        @NotNull EntityModelData source,
        @NotNull String rootBone,
        @NotNull String entityId
    ) {
        Map<String, EntityModelData.Bone> bones = source.getBones();
        if (!bones.containsKey(rootBone)) {
            // TODO: restore pipeline diagnostics
            // diagnostics.warn("entity '%s' overlay no_hat_root names bone '%s' which is not on the geometry", entityId, rootBone);
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
