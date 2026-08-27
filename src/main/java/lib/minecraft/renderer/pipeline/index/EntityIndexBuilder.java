package lib.minecraft.renderer.pipeline.index;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.adapter.ColorTypeAdapter;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.Entity.BlockOverlayLayer;
import lib.minecraft.renderer.asset.Entity.EquipmentOverlay;
import lib.minecraft.renderer.asset.Entity.LargeShape;
import lib.minecraft.renderer.asset.Entity.OverlayLayer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.Flag;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.appearance.TextureAxis;
import lib.minecraft.renderer.asset.appearance.TintAxis;
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
import lib.minecraft.renderer.tensor.Matrix4f;
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
 * coordinate against the geometry table, pivots the option axes into {@link Entity.Axes}, folds each
 * variant option into a sub-{@link Entity}, and stamps cross-entity canvas-group membership. The leaf
 * decodes (hex tint, {@code blend} token, texture strip, transform ops) happen here too - the raw
 * records carry them verbatim.
 *
 * <p>Every mesh a subject draws is JOINED rather than built. What a subject rests without, the subset
 * a pass is restricted to, the deformation it surrounds the body with and the emptied subtree of its
 * suppressed form are all baked into meshes of their own, so a row names one mesh per form it has and
 * nothing here reshapes one. A mesh that arrives is the mesh that draws.
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
        return Concurrent.adoptMap(definitions);
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
        RawOption adult = adultOption(family);
        String baseCoord = adult.geometry();

        RawRender render = family.render();
        float rendererScale = render == null || render.scale() == null ? 1f : render.scale();
        int baseTint = render == null || render.tint() == null ? WHITE : ColorTypeAdapter.parse(render.tint()).getRGB();
        List<Map<PoseChannel, PoseExpr>> composed =
            renderTransformOf(renderTransforms, family);
        float setupYawAddend = facingYawOf(composed);
        List<Map<PoseChannel, PoseExpr>> renderTransform = afterFacingYaw(composed);

        RawBones bones = family.bones();
        // The class the body's pose is read against, which is the renderer's own and not always the
        // one that baked the mesh - a model reusing its parent's layer is headed with the parent
        // everywhere a coordinate appears, and reading it there poses a zombie as a plain humanoid.
        // Written only where the two disagree, so a coordinate answers for the rest.
        String poseClass = bones == null ? null : bones.pose();

        List<RawOverlay> familyOverlays = nullToEmpty(family.overlays());
        List<BlockOverlayLayer> blockOverlays = family.blockOverlays() == null ? List.of() : loadBlockOverlays(family.blockOverlays());

        List<EquipmentOverlay> equipment = loadEquipment(family, geometries, familyId);
        Optional<Shell> humanoidArmor = humanoidArmorOf(family, geometries, familyId);
        String babyCoord = babyGeometryOf(family);
        // Beside the baby MESH rather than derived from it: a baby is its own model class, and two of
        // the families that pose at all are posed through that class alone.
        Optional<EntityPose> babyPose = babyCoord == null ? Optional.empty()
            : Optional.of(under(renderTransform, poseOf(poses, babyCoord)));
        Optional<EntityModelData> babyModel = babyCoord == null ? Optional.empty()
            : Optional.ofNullable(geometries.get(babyCoord));
        List<OverlayLayer> babyOverlays = loadBabyOverlays(familyOverlays, geometries, poses,
            renderTransform, babyPose.orElse(EntityPose.NONE), babyCoord, babyModel, familyId);

        FamilyContext ctx = new FamilyContext(family, familyId, poseClass, geometries, poses,
            renderTransform, familyOverlays, baseTint, setupYawAddend, rendererScale,
            babyModel, babyPose, babyOverlays,
            buildLargeShape(family, geometries, poses, renderTransform, familyId),
            equipment, humanoidArmor, stateDefaultOf(family));

        RawAxis variant = variantAxis(family);
        // A family is one row and the coats are forms of it, so both arms below build ROWS and differ
        // only in which forms there are and which of them the bare family already is.
        Entity row = variant == null
            ? buildRow(plainForm(family, adult, blockOverlays), ctx)
            : variantRow(variant, baseCoord, blockOverlays, ctx);
        definitions.put(familyId, row.mutate().members(membersOf(family)).build());
    }

    /**
     * The family row of a family whose coats are option-encoded: every coat built as a row of its own,
     * and the one the family declares carrying the whole option map.
     *
     * <p>One base row {@code minecraft:<id>}, the coat resolved at render - never a row per coat. The
     * reason is in this class's own javadoc and is a collision-safety rule rather than a convenience.
     */
    private static @NotNull Entity variantRow(
        @NotNull RawAxis variant,
        @NotNull String baseCoord,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        @NotNull FamilyContext ctx
    ) {
        LinkedHashMap<String, Entity> coats = new LinkedHashMap<>();
        for (Map.Entry<String, RawOption> option : variant.options().entrySet())
            coats.put(option.getKey(), buildRow(coatForm(option.getValue(), baseCoord, blockOverlays), ctx));
        Entity base = coats.getOrDefault(variant.defaultOption(), coats.values().iterator().next());
        Entity.Axes axes = base.axes();
        return base.mutate()
            .axes(new Entity.Axes(axes.babyModel(), axes.babyPose(), axes.babyOverlays(),
                axes.largeShape(), axes.state(), axes.size(),
                new Entity.Axis<>(Map.copyOf(coats), Optional.ofNullable(variant.defaultOption()))))
            .build();
    }

    /**
     * Builds one row of a family - the family's own, or one of its coats - from the mesh it draws and
     * everything the family shares.
     *
     * <p>Both arms of {@link #readDefinition} come through here, because a coat and the family row it
     * is a coat of are the same construction over a different {@link RowForm}: they resolve the same
     * pose against the same class, materialise the same overlay rows, and carry the same baby mesh,
     * equipment and armour. What differs is the four members a form names.
     *
     * <p>The size axis is derived from the row rather than beside it, so it can only ever hold forms
     * of THIS row. That is also what gives a coat its own size axis: a family that gained both would
     * otherwise resolve a coat to a leaf carrying no sizes, and the sizes it does carry would be the
     * family's rather than the coat's.
     *
     * @param form what this row differs from its siblings in
     * @param ctx what the whole family shares
     * @return the row, without the variant axis a family row is given afterwards
     */
    private static @NotNull Entity buildRow(@NotNull RowForm form, @NotNull FamilyContext ctx) {
        EntityModelData model = resolveModel(ctx.geometries(), form.coordinate(), ctx.familyId());
        // A coat swaps the mesh and never the renderer, so the class the pose is read against is the
        // family's whatever geometry the form names.
        EntityPose pose = under(ctx.renderTransform(),
            poseOf(ctx.poses(), ctx.poseClass() == null ? form.coordinate() : ctx.poseClass()));
        // Ahead of the size derivation so a same-geometry pass is materialised on the mesh this row
        // actually draws, and travels with it into every form derived from the row.
        List<OverlayLayer> overlays = loadOverlays(ctx.familyOverlays(), ctx.geometries(), ctx.poses(),
            ctx.renderTransform(), pose, form.coordinate(), model, ctx.familyId());

        Entity bare = Entity.builder()
            .id(ResourceId.parse(ctx.familyId()))
            .model(model).overlays(overlays)
            .blockOverlays(form.blockOverlays())
            .baseTintArgb(ctx.baseTint()).setupYawAddend(ctx.setupYawAddend())
            .rendererScale(ctx.rendererScale())
            .pose(pose)
            .axes(new Entity.Axes(ctx.babyModel(), ctx.babyPose(), ctx.babyOverlays(), ctx.largeShape(),
                new Entity.Axis<>(form.stateTextures(), declaredState(ctx.stateDefault(), form.stateTextures())),
                Entity.Axis.none(), Entity.Axis.none()))
            .layers(new Entity.Layers(ctx.equipment(), ctx.humanoidArmor()))
            .build();

        // Built once WITHOUT the size axis, because a size form is a sub-definition derived from this
        // row - its own baked mesh over the same overlays, or this row at a multiplied scale - so the
        // row it derives from has to exist first. A form carries no size axis of its own: it is a leaf.
        Entity.Axes axes = bare.axes();
        return bare.mutate()
            .axes(new Entity.Axes(axes.babyModel(), axes.babyPose(), axes.babyOverlays(),
                axes.largeShape(), axes.state(),
                buildSizeAxis(ctx.family(), ctx.geometries(), bare), axes.variant()))
            .build();
    }

    /**
     * The family's own row: the adult mesh and texture, and its block overlays as the rows declare
     * them.
     *
     * <p>A family carries its single baby texture on {@code age.baby.texture}; it is exposed under the
     * {@code "baby"} state key so the renderer binds it the same way as a coat's own
     * {@code baby_texture}.
     */
    private static @NotNull RowForm plainForm(
        @NotNull RawModel family, @NotNull RawOption adult,
        @NotNull List<BlockOverlayLayer> blockOverlays) {

        Map<String, String> stateTextures = new LinkedHashMap<>();
        if (adult.texture() != null) stateTextures.put(Entity.BASE_STATE, adult.texture());
        String babyTexture = babyTextureOf(family);
        if (babyTexture != null) stateTextures.put("baby", babyTexture);
        return new RowForm(adult.geometry(), stateTextures, blockOverlays);
    }

    /**
     * One coat's row: its own mesh where it names one, its {@code wild} texture and per-state
     * textures, and the family's fixed block overlays redrawn as the block it names.
     */
    private static @NotNull RowForm coatForm(
        @NotNull RawOption option, @NotNull String baseCoord,
        @NotNull List<BlockOverlayLayer> blockOverlays) {

        return new RowForm(
            option.geometry() == null ? baseCoord : option.geometry(),
            variantStateTextures(option),
            coatBlockOverlays(blockOverlays, option.block()));
    }

    /**
     * The steps this subject's renderer composes above every mesh it submits.
     *
     * <p>Joined on the renderer's simple name, which the model table carries per subject and the
     * pose table keys its transforms by. A subject whose renderer the walk refused, or which
     * composes nothing at all, has no row and stands where its mesh puts it.
     *
     * <p>A transform and a baked {@code setupRotations} translate are two spellings of one thing and
     * only one may answer, so a subject reaching both is refused where the translate is still known -
     * at generation, before it goes into the mesh.
     */
    private static @NotNull List<Map<PoseChannel, PoseExpr>> renderTransformOf(
        @NotNull Map<String, List<Map<PoseChannel, PoseExpr>>> transforms, @NotNull RawModel family) {

        String renderer = family.renderer();
        if (renderer == null) return List.of();
        int member = renderer.lastIndexOf('/');
        return transforms.getOrDefault(member < 0 ? renderer : renderer.substring(member + 1), List.of());
    }

    /**
     * Vanilla's own degrees-to-radians factor, which the pose table's steps are written through and
     * a facing yaw is divided back out by. Refused at generation where the division would not
     * recover the degrees the renderer wrote.
     */
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180d);

    /**
     * The facing yaw a row's leading steps carry, in the degrees the facing sum takes.
     *
     * <p>A leading constant turn about y is the addend a renderer folds into the delegation's own
     * body rotation - the shulker's {@code + 180f} - and the base applies the body rotation as the
     * subject's facing. So it reaches every render mode through the facing rather than through the
     * pose container, which a BIND render never reads, and it is crossed back out of the container
     * frame here: the container holds the negated radians.
     *
     * @param steps the renderer's composed steps, outermost first
     * @return the summed facing degrees of the leading constant y turns, {@code 0f} for none
     */
    private static float facingYawOf(@NotNull List<Map<PoseChannel, PoseExpr>> steps) {
        float degrees = 0f;
        for (Map<PoseChannel, PoseExpr> step : steps) {
            if (!isFacingYaw(step)) break;
            degrees += -((float) step.get(PoseChannel.Y_ROT).constantValue().orElseThrow()) / DEGREES_TO_RADIANS;
        }
        return degrees;
    }

    /**
     * The same steps with the facing prefix removed, which is what the pose container seats.
     *
     * @param steps the renderer's composed steps, outermost first
     * @return the steps after the leading constant y turns
     */
    private static @NotNull List<Map<PoseChannel, PoseExpr>> afterFacingYaw(
        @NotNull List<Map<PoseChannel, PoseExpr>> steps) {

        int from = 0;
        while (from < steps.size() && isFacingYaw(steps.get(from))) from++;
        return from == 0 ? steps : List.copyOf(steps.subList(from, steps.size()));
    }

    /** Whether one step is a constant turn about y alone, which is a facing fact rather than a pose one. */
    private static boolean isFacingYaw(@NotNull Map<PoseChannel, PoseExpr> step) {
        PoseExpr turn = step.get(PoseChannel.Y_ROT);
        return step.size() == 1 && turn != null && turn.constantValue().isPresent();
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
     * One pose composed under the steps its renderer puts above every mesh it submits.
     *
     * <p>Vanilla applies {@code setupRotations} to the pose stack before the body or any layer is
     * drawn, so what it composes is outermost: it goes at the FRONT of the container, and every pose
     * the subject's meshes take gets the same sequence, seated once here rather than per frame. A
     * renderer that composes nothing hands back the pose itself.
     *
     * <p>A pose that could not be read stays unreadable rather than becoming a container with no
     * bones under it: a subject whose model nothing could walk is not one a transform can place, and
     * placing it anyway would draw a mesh that is neither authored nor posed.
     *
     * @param steps what the subject's renderer composes above its meshes
     * @param pose the pose belonging to one of those meshes
     * @return the pose with the renderer's steps ahead of its own container
     */
    private static @NotNull EntityPose under(
        @NotNull List<Map<PoseChannel, PoseExpr>> steps, @NotNull EntityPose pose) {

        if (steps.isEmpty() || !pose.isReadable()) return pose;
        List<Map<PoseChannel, PoseExpr>> container = new ArrayList<>(steps);
        container.addAll(pose.container());
        return new EntityPose(List.copyOf(container), pose.bones(), pose.clips(), pose.refusal());
    }

    /**
     * What the whole family shares, so one row build restates none of it.
     *
     * <p>Held once per family and handed to every row of it, the family's own and each of its coats
     * alike - which is what lets the two arms of {@link #readDefinition} be one construction over
     * different {@link RowForm}s rather than two that drifted.
     */
    private record FamilyContext(
        @NotNull RawModel family,
        @NotNull String familyId,
        @Nullable String poseClass,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull List<Map<PoseChannel, PoseExpr>> renderTransform,
        @NotNull List<RawOverlay> familyOverlays,
        int baseTint,
        float setupYawAddend,
        float rendererScale,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull Optional<EntityPose> babyPose,
        @NotNull List<OverlayLayer> babyOverlays,
        @NotNull Optional<LargeShape> largeShape,
        @NotNull List<EquipmentOverlay> equipment,
        @NotNull Optional<Shell> humanoidArmor,
        @NotNull Optional<String> stateDefault
    ) {}

    /**
     * What one row of a family differs from its siblings in, and the whole of it.
     *
     * <p>Four members: everything else a row carries is the family's and travels on
     * {@link FamilyContext}. A coat that names no mesh of its own draws the family's, which is why the
     * coordinate is resolved into the form rather than left for the build to decide.
     *
     * @param coordinate the mesh this row draws
     * @param textureRef the row's own base texture, or empty where it has none
     * @param stateTextures the row's alternate textures by behavioural state
     * @param blockOverlays the block-shaped overlays as this row draws them - a coat naming its own
     *     {@code block} redraws the family's fixed rows as that block, the mooshroom's brown mushrooms
     *     against the red ones its rows carry, and a selectable row is left to the caller's selection
     */
    private record RowForm(
        @NotNull String coordinate,
        @NotNull Map<String, String> stateTextures,
        @NotNull List<BlockOverlayLayer> blockOverlays
    ) {}

    /**
     * Which state a row is already in: the one the shipped table declares where it declares one, else
     * the base state every row carrying a base texture is in.
     *
     * <p>Answers absent only for a row naming no base texture at all, which keeps the axis honest
     * rather than declaring an option it does not carry.
     *
     * @param shipped the {@code state.default} the table names, or empty
     * @param states the row's own state textures
     * @return the state the row is in, or empty when it names no base texture
     */
    private static @NotNull Optional<String> declaredState(
        @NotNull Optional<String> shipped, @NotNull Map<String, String> states) {

        if (shipped.isPresent()) return shipped;
        return states.containsKey(Entity.BASE_STATE) ? Optional.of(Entity.BASE_STATE) : Optional.empty();
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
     * member (or one naming the base coordinate) draws the base mesh itself so its cubes co-register
     * with the base; a distinct coordinate resolves fresh from the geometry table (a missing coordinate
     * warns and drops). A pass vanilla restricts to a subset or deforms names the mesh that is, so the
     * distinct coordinate is what says the two differ.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull List<RawOverlay> overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull List<Map<PoseChannel, PoseExpr>> renderTransform,
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
            // Either way the renderer's steps sit at the front, the body's arriving composed already.
            EntityPose overlayPose = sameGeometry ? bodyPose : under(renderTransform, poseOf(poses, coord));
            Optional<String> overlayTexture = Optional.ofNullable(entry.texture());
            boolean hasTint = entry.tint() != null;
            RawPipeline pipeline = entry.pipeline();
            boolean emissive = pipeline != null && pipeline.emissive();
            int overlayTint = hasTint ? ColorTypeAdapter.parse(entry.tint()).getRGB() : WHITE;
            // An overlay drawing the base mesh itself is excluded from the canvas-sizing bounds: it
            // renders the IDENTICAL cube tree as the base, so the base already contributes its full
            // silhouette extent. A pass vanilla deforms draws a mesh of its OWN, which vanilla's own
            // bounds walk includes, so it keeps contributing. An explicit skip_bounds (llama carpet,
            // NO_RENDER_LAYER_SUFFIXES) always wins.
            boolean skipBounds = entry.skipBounds() || sameGeometry;
            // Resolved here rather than re-parsed per frame: the token is the table's spelling and
            // the axis is what every reader asks.
            Optional<TintAxis> tintBy = TintAxis.ofToken(entry.tintBy());
            Optional<TextureAxis> textureBy = TextureAxis.ofToken(entry.textureBy());
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
            // The suppressed-pass mesh, named by a member of its OWN rather than by the coordinate this
            // pass draws: it differs from the primary in nothing but the emptied subtree, and naming it
            // as the pass's geometry would flip sameGeometry and take the bounds skip above with it.
            Optional<EntityModelData> noHatModel = entry.noHatGeometry() == null ? Optional.empty()
                : Optional.ofNullable(geometries.get(entry.noHatGeometry()));
            Optional<Vector2f> scroll = textureScroll(entry, entityId);
            // The wrap is the pass's own fact, decided here rather than inferred at render: vanilla
            // builds the offset into the render type's texture matrix, so a scrolled pass samples
            // past the sheet and wraps, and every other pass holds at the last texel.
            PassDeclaration pass =
                new PassDeclaration(emissive, blend, alpha, writesDepth, sorted, scroll.isPresent());
            out.add(new OverlayLayer(overlayModel, overlayTexture, pass, overlayTint, skipBounds, tintBy,
                textureBy, gate, noHatModel, overlayPose, scroll));
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
     * into an overlay whose meshes and texture come from the delta and whose every other member
     * ({@code texture_by}, tint, pipeline, bounds skip, gate) is inherited from the row, then handed to
     * {@link #loadOverlays} against the {@code age.baby} mesh. A row with no delta is absent from the
     * result, so a pass vanilla itself gates off a baby (the villager profession and profession-level
     * passes) drops out structurally.
     *
     * <p>A delta naming no geometry is left naming none rather than given the row's coordinate: the row
     * names the ADULT mesh, so carrying it would flip {@link #loadOverlays}'s {@code sameGeometry} false
     * and lose the derived bounds skip - the pass would re-enter the canvas union and move the baby
     * canvas. Left absent it defaults to {@code babyCoord}, which is the same mesh instance
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
        @NotNull List<Map<PoseChannel, PoseExpr>> renderTransform,
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
            forms.add(new RawOverlay(
                baby.geometry(), baby.noHatGeometry(),
                baby.texture() == null ? entry.texture() : baby.texture(),
                entry.tint(), entry.tintBy(), entry.textureBy(),
                entry.pipeline(), entry.textureScroll(), entry.skipBounds(), entry.when(), null));
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
        return loadOverlays(forms, geometries, poses, renderTransform, babyPose, babyCoord, babyModel.get(), entityId);
    }

    /**
     * Parses an overlay's {@code when} object into a typed {@link AppearanceGate}: {@code flag} maps
     * to a {@link AppearanceGate.Selected} on the named {@link Flag} carrying the row's own polarity,
     * {@code charged} to one on {@link Flag#CHARGED}, and {@code tinted} to
     * {@link AppearanceGate.TintedGate} (carrying the overlay's tint axis token and its baked tint,
     * so the gate is self-contained). Absent or unrecognised - a shape no arm reads, or a flag token
     * no {@link Flag} constant owns - yields empty (unconditional).
     *
     * @param when the overlay's {@code when} object, or {@code null} when absent
     * @param tintBy the overlay's tint axis, used to seed a {@link AppearanceGate.TintedGate}
     * @param tintArgb the overlay's baked tint - the colour a {@link AppearanceGate.TintedGate}
     *     selection has to differ from, being what the dye vanilla's own comparison names resolves to
     * @return the parsed gate, or empty when unconditional
     */
    private static @NotNull Optional<AppearanceGate> parseOverlayGate(
        @Nullable RawOverlayWhen when,
        @NotNull Optional<TintAxis> tintBy,
        int tintArgb
    ) {
        if (when == null) return Optional.empty();
        if (when.flag() != null)
            return enumOf(Flag.class, when.flag()).map(flag -> new AppearanceGate.Selected(flag, when.value()));
        if (when.charged())
            return Optional.of(new AppearanceGate.Selected(Flag.CHARGED, true));
        if (when.tinted())
            return Optional.of(new AppearanceGate.TintedGate(tintBy, tintArgb));
        return Optional.empty();
    }

    /**
     * Parses an overlay's optional {@code blend} token into a {@link BlendMode}. {@code "additive"} maps
     * to {@link BlendMode#ADD} and {@code "cutout"} to {@link BlendMode#REPLACE};
     * {@code "translucent"} and an absent token both map to {@link BlendMode#NORMAL} source-over, a
     * translucent pass carrying its translucency in the texture's alpha. {@code cutout} is the token
     * that does not: vanilla draws such a pass through a pipeline declaring no blend function at all,
     * so every fragment surviving the alpha threshold is written over the destination rather than into
     * it, alpha included. The tooling emits exactly these three, so anything else is a defect worth
     * failing over rather than a spelling to tolerate.
     *
     * @throws PipelineException if the token is not one the tooling emits
     */
    private static @NotNull BlendMode parseBlend(@Nullable String blend) {
        if (blend == null) return BlendMode.NORMAL;
        return switch (blend) {
            case "additive" -> BlendMode.ADD;
            case "cutout" -> BlendMode.REPLACE;
            case "translucent" -> BlendMode.NORMAL;
            default -> throw new PipelineException("Unknown overlay blend '%s'", blend);
        };
    }

    // ------------------------------------------------------------------------------------
    // block overlays
    // ------------------------------------------------------------------------------------

    /**
     * Resolves a {@code block_overlays} list into {@link BlockOverlayLayer} rows. A fixed row names its
     * {@code block}; a {@code selectable} row's block is supplied at render from the carried selection, so
     * its {@code block} may be omitted entirely (the enderman carried block). A {@code transforms} entry
     * is a single-member object dispatched on the member's own name, the shape a pose expression takes.
     *
     * <p>The ops compose here rather than at render: every operand is a shipped constant, so their
     * product is one too, and the row carries the product. Each arm applies the op the fluent way -
     * {@code Math.toRadians} on the degrees, post-multiply onto the accumulated chain - because that is
     * the arithmetic the render frame is entitled to, and the fluent path differs from the
     * {@code multiply} path in the last few bits.
     *
     * @throws PipelineException if a transform entry names an op the tooling does not emit
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull List<RawBlockOverlay> array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (RawBlockOverlay row : array) {
            boolean selectable = row.selectable();
            if (row.block() == null && !selectable) continue;
            String blockId = row.block() == null ? "" : row.block();
            String attachedBone = row.attachedBone();
            Matrix4f transform = Matrix4f.IDENTITY;
            for (Map<String, com.google.gson.JsonElement> opObj : nullToEmpty(row.transforms())) {
                Map.Entry<String, com.google.gson.JsonElement> only = opObj.entrySet().iterator().next();
                transform = switch (only.getKey()) {
                    case "translate" -> {
                        com.google.gson.JsonArray by = only.getValue().getAsJsonArray();
                        yield transform.translate(by.get(0).getAsFloat(), by.get(1).getAsFloat(), by.get(2).getAsFloat());
                    }
                    case "scale" -> {
                        com.google.gson.JsonArray by = only.getValue().getAsJsonArray();
                        yield transform.scale(by.get(0).getAsFloat(), by.get(1).getAsFloat(), by.get(2).getAsFloat());
                    }
                    case "rotate_x" -> transform.rotateX((float) Math.toRadians(only.getValue().getAsFloat()));
                    case "rotate_y" -> transform.rotateY((float) Math.toRadians(only.getValue().getAsFloat()));
                    case "rotate_z" -> transform.rotateZ((float) Math.toRadians(only.getValue().getAsFloat()));
                    default -> throw new PipelineException("Unknown block-overlay transform '%s'", only.getKey());
                };
            }
            out.add(new BlockOverlayLayer(blockId, attachedBone, transform, selectable));
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
    private static @NotNull Map<String, String> variantStateTextures(@NotNull RawOption optionObj) {
        Map<String, String> states = new LinkedHashMap<>();
        if (optionObj.textures() != null)
            for (Map.Entry<String, String> texture : optionObj.textures().entrySet())
                states.put(texture.getKey(), texture.getValue());
        if (optionObj.babyTexture() != null) states.put("baby", optionObj.babyTexture());
        return states;
    }

    /**
     * Returns the mandatory age axis' {@code options.adult} body - the family baseline (primary
     * {@code geometry}, and for non-variant families the adult {@code texture}).
     */
    private static @NotNull RawOption adultOption(@NotNull RawModel family) {
        return family.axes().age().options().get("adult");
    }

    /** Returns the {@code axes.variant} object when the family carries a variant axis. */
    private static @Nullable RawAxis variantAxis(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        return axes == null ? null : axes.variant();
    }

    /** Returns the {@code age.baby} option object, or {@code null} when the family has no age axis. */
    private static @Nullable RawOption ageBaby(@NotNull RawModel family) {
        RawAxes axes = family.axes();
        if (axes == null || axes.age() == null) return null;
        return axes.age().options().get("baby");
    }

    /** Returns the family's baby geometry coordinate from its {@code age} axis, or {@code null}. */
    private static @Nullable String babyGeometryOf(@NotNull RawModel family) {
        RawOption baby = ageBaby(family);
        return baby == null ? null : baby.geometry();
    }

    /** Returns the family's single baby texture ref from {@code age.baby.texture}, or {@code null}. */
    private static @Nullable String babyTextureOf(@NotNull RawModel family) {
        RawOption baby = ageBaby(family);
        return baby == null ? null : baby.texture();
    }

    /**
     * Returns the worn-armor shell the family's armor row is dressed in - the row's mesh joined against
     * the geometry table, the two layer deformations, and the whole-mesh scale the set is registered
     * through - or empty when the family carries no armor row. Absence IS "wears none": being armored is
     * carrying a resolved shell, so a row whose mesh or deformations are missing warns and drops the
     * wearer rather than dressing it in a guess. An absent {@code scaled} is the identity, which is what
     * the eleven wearers vanilla registers unscaled omit.
     *
     * <p>The mesh arrives as the shell's own - what a wearer rests without, the subset a pass of its is
     * restricted to and the deformation one surrounds it with are derived onto meshes the wearer names,
     * and this row names none of them. Worn armor is
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
        RawArmor armor = family.armor();
        if (armor == null) return Optional.empty();
        RawArmorAlternate raw = armor.alternate();
        Optional<Shell.Alternate> alternate = Optional.empty();
        if (raw != null) {
            alternate = alternateShellOf(raw, geometries, entityId);
            if (alternate.isEmpty()) return Optional.empty();
        }
        return shellOf(armor.geometry(), armor.grow(), armor.scaled(), ArmorForm.ADULT, alternate,
            geometries, entityId);
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
     * {@code age} and {@code size} each to a {@link AppearanceGate.Selected} on the named option.
     * Empty for an absent or unreadable option, which is a shell nothing could ever select rather
     * than one that always applies.
     */
    private static @NotNull Optional<AppearanceGate> parseAlternateGate(@Nullable RawLayerWhen when) {
        if (when == null) return Optional.empty();
        if (when.age() != null)
            return enumOf(Age.class, when.age()).map(age -> new AppearanceGate.Selected(age, true));
        if (when.size() != null)
            return enumOf(Size.class, when.size()).map(size -> new AppearanceGate.Selected(size, true));
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
     * Resolves the family's {@code equipment} rows into {@link EquipmentOverlay}s, binding each row's
     * {@code geometry} coordinate to its baked mesh and decoding its render layer and
     * {@code material -> asset id} table. A row naming an unknown geometry or an unknown layer type
     * warns and drops.
     *
     * <p>A row takes the same bone surgery the body does, off its own bones node: an equine saddle's
     * reins are drawn while something is riding and a donkey's saddle carries no chest until the
     * donkey does, and the mesh a row draws holds those bones either way. Its {@code undrawn} list is
     * resolved at generation against its OWN model class's pose, so a row posed by a class the walk
     * never looked at carries none and rests as it is baked.
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        List<EquipmentOverlay> out = new ArrayList<>();
        for (RawEquipmentRow row : nullToEmpty(family.equipment())) {
            if (row.slot() == null) continue;
            if (row.geometry() == null || row.layerType() == null || row.defaultMaterial() == null) continue;
            if (row.materialAssets() == null || row.materialAssets().isEmpty()) continue;
            String coord = row.geometry();
            EntityModelData model = geometries.get(coord);
            if (model == null) {
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("entity '%s' equipment row references geometry '%s' absent from entity_geometry", entityId, coord);
                continue;
            }
            Optional<LayerType> layerType = LayerType.fromId(row.layerType());
            if (layerType.isEmpty()) {
                // TODO: restore pipeline diagnostics
                // diagnostics.warn("entity '%s' equipment row names unknown layer type '%s'", entityId, row.layerType());
                continue;
            }
            Map<String, ResourceId> materialAssets = new LinkedHashMap<>();
            row.materialAssets().forEach((material, assetId) -> materialAssets.put(material, ResourceId.parse(assetId)));
            out.add(new EquipmentOverlay(row.slot(), model, layerType.get(),
                Map.copyOf(materialAssets), row.defaultMaterial()));
        }
        return List.copyOf(out);
    }

    /**
     * Resolves the family's {@code shape.large} option (tropical fish) into a {@link LargeShape}: the large
     * body mesh, its base texture ref, and the pattern overlays materialised on the large geometry.
     * Empty when the family has no shape axis or its large geometry is missing.
     */
    private static @NotNull Optional<LargeShape> buildLargeShape(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Map<String, EntityPose> poses,
        @NotNull List<Map<PoseChannel, PoseExpr>> renderTransform,
        @NotNull String entityId
    ) {
        RawAxes axes = family.axes();
        if (axes == null || axes.shape() == null) return Optional.empty();
        RawOption large = axes.shape().options().get("large");
        if (large == null || large.geometry() == null) return Optional.empty();
        String coord = large.geometry();
        EntityModelData model = geometries.get(coord);
        if (model == null) return Optional.empty();
        List<OverlayLayer> overlays = loadOverlays(nullToEmpty(large.overlays()), geometries, poses,
            renderTransform, under(renderTransform, poseOf(poses, coord)), coord, model, entityId);
        Optional<String> textureRef = large.texture() != null ? Optional.of(large.texture()) : Optional.of("");
        return Optional.of(new LargeShape(model, textureRef, overlays));
    }

    /**
     * Resolves the family's {@code size} axis into a form per size, each a sub-definition of the row
     * it is a size of.
     *
     * <p><b>Vanilla sizes a subject two ways and they are not interchangeable</b>, so a form carries
     * whichever its own subject uses. An option naming a {@code geometry} takes that baked mesh - the
     * small armor stand, the two pufferfish bodies, the salmon's own {@code @scaled=} meshes, which
     * vanilla registers through a {@code MeshTransformer} and which therefore carry the feet anchor.
     * An option naming a {@code scale} instead takes the base mesh at a multiplied render scale -
     * slime and magma_cube, which vanilla scales at the render, about the origin and with no anchor.
     * Folding either into the other moves the subject.
     *
     * <p><b>The declared size is a form like any other.</b> The shipped table lists only the other
     * sizes, because the declared one is what the bare row already is - but the axis carries it
     * anyway, mapped to that row, so a reader can ask which size it is holding rather than inferring
     * it from an absence. Selecting it resolves to a form equal to the base and changes nothing.
     *
     * <p>Order is preserved rather than left to an immutable map's own, which is salted per JVM: the
     * pipeline dump serialises this axis and a re-ordered map would flap its bytes between runs.
     *
     * @param family the raw model
     * @param geometries the geometry coordinate to bone tree table
     * @param bare the row these are sizes of, built without a size axis of its own
     * @return the form per size, empty for a family with no size axis
     */
    private static @NotNull Entity.Axis<Size, Entity> buildSizeAxis(
        @NotNull RawModel family,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull Entity bare
    ) {
        Map<String, RawOption> options = sizeOptions(family);
        if (options == null) return Entity.Axis.none();
        Optional<Size> declared = sizeDefaultOf(family).flatMap(name -> enumOf(Size.class, name));
        Map<Size, Entity> forms = new LinkedHashMap<>();
        declared.ifPresent(size -> forms.put(size, bare));
        for (Map.Entry<String, RawOption> option : options.entrySet()) {
            RawOption body = option.getValue();
            Optional<Size> size = enumOf(Size.class, option.getKey());
            if (size.isEmpty()) continue;
            if (body.geometry() != null) {
                EntityModelData mesh = geometries.get(body.geometry());
                if (mesh != null) forms.put(size.get(), bare.mutate().model(mesh).build());
            } else if (body.scale() != null) {
                forms.put(size.get(), bare.mutate()
                    .rendererScale(bare.rendererScale() * body.scale()).build());
            }
        }
        return new Entity.Axis<>(Collections.unmodifiableMap(forms), declared);
    }

    /** Returns the family's {@code axes.size.options} object, or {@code null} when it has no size axis. */
    private static @Nullable Map<String, RawOption> sizeOptions(@NotNull RawModel family) {
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

    // ------------------------------------------------------------------------------------
    // resource + text helpers
    // ------------------------------------------------------------------------------------

    /** Returns {@code list} unchanged, or an empty list when it is {@code null} (an absent JSON array). */
    private static <T> @NotNull List<T> nullToEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    /** The family's resolved canvas-group membership, verbatim from the table, empty for a singleton. */
    private static @NotNull List<String> membersOf(@NotNull RawModel family) {
        return family.members() == null ? List.of() : List.copyOf(family.members());
    }
}
