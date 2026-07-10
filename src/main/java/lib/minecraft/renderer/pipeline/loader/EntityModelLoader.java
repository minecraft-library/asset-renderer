package lib.minecraft.renderer.pipeline.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads bundled entity model definitions from two paired classpath resources produced by
 * {@code ToolingEntityModels}: {@link #MODELS_RESOURCE_PATH} holds per-entity metadata
 * (geometry reference, texture reference, optional {@code variant_of} back-link, overlays);
 * {@link #GEOMETRY_RESOURCE_PATH} holds the deduplicated bone/cube trees.
 * <p>
 * A {@code texture_ref} is the vanilla {@code textures/entity/} sub-path (e.g.
 * {@code "cow/cow"}, {@code "wither/wither"}); resolved at render time against the active pack
 * stack via
 * {@link RendererContext#resolveTexture(String) resolveTexture}
 * as {@code minecraft:entity/<ref>}.
 * <p>
 * Many Java {@code EntityType} registry rows share one geometry (e.g. {@code horse},
 * {@code donkey}, {@code mule}, {@code skeleton_horse}, {@code zombie_horse} all reference
 * {@code geometry.horse}). Splitting the data into two files lets each entity metadata row stay
 * small while the potentially-multi-kilobyte bone tree is stored exactly once.
 *
 * @see PipelineRendererContext
 */
@UtilityClass
public class EntityModelLoader {

    /**
     * Per-entity metadata file, in the normalized family form (one entry per base entity with axes
     * + layers); flattened to per-entity rows at load by {@link EntityFamilyFlattener}. Produced by
     * {@code ToolingEntityModels}.
     */
    private static final @NotNull String MODELS_RESOURCE_PATH = "/lib/minecraft/renderer/entity_models.json";

    /**
     * Per-geometry bone tree file; bones in Java-native Y-down absolute entity-root frame.
     */
    private static final @NotNull String GEOMETRY_RESOURCE_PATH = "/lib/minecraft/renderer/entity_geometry.json";

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * The auto-emitted depth-clearance inflate applied to same-geometry overlays (eyes, clothing
     * patterns) so they win the coplanar depth tie against the base mesh. This is OUR artifact -
     * vanilla submits the identical {@code ModelPart} with no deformation - so a same-geometry
     * overlay carrying at most this much inflate is excluded from canvas-sizing bounds (see
     * {@link #loadOverlays}). A larger inflate is a real vanilla {@code CubeDeformation}
     * (tropical_fish 0.008, llama carpet 0.5) that vanilla's bounds walk includes, so it keeps
     * contributing.
     *
     * <p>Public solely so the temporary tooling2 bridge ({@code EntityModelsBridge}) re-adds this
     * exact value onto same-mesh grow-less overlays instead of re-minting a literal (decision 22);
     * the widening reverts to private when the bridge retires (10-bridge SS9).
     */
    public static final float DEPTH_CLEARANCE_INFLATE = 0.001f;

    /**
     * An entity definition loaded from the bundled resources.
     *
     * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
     * @param textureRef the vanilla {@code textures/entity/} sub-path (without the {@code .png}
     *     suffix), resolved at render time via
     *     {@link RendererContext#resolveTexture(String)
     *     resolveTexture} as {@code minecraft:entity/<ref>}, or empty when no default texture
     * @param overlays additional geometry/texture pairs rendered on top of the base model in
     *     declared order; populated by the bytecode-derived overlay scan
     *     ({@code EntityOverlayResolver}: emissive eyes, profession layers, pattern layers,
     *     equipment-driven decor layers)
     * @param blockOverlays vanilla-block-shaped overlays rendered on top of the entity body
     *     (mooshroom mushrooms, iron golem poppy) at a transform-stack-applied position
     * @param baseTintArgb per-entity multiplicative tint applied to the base mesh, mirroring
     *     {@code LivingEntityRenderer.getModelTint(state)}. Defaults to {@code 0xFFFFFFFF}
     *     (white = no-op MULTIPLY)
     * @param setupYawAddend yaw rotation in degrees that the vanilla renderer's
     *     {@code setupRotations} override adds to the standard {@code bodyRot} before the super
     *     call. Extracted from the {@code super.setupRotations(state, ps, bodyRot + N, scale)}
     *     bytecode pattern by the tooling-side renderer scan. {@code ShulkerRenderer} is the
     *     canonical case ({@code +180.0F}); every other vanilla renderer leaves {@code bodyRot}
     *     unmodified and lands at {@code 0}. The renderer adds this to the user-supplied yaw
     *     before applying the iso pose - for shulker the addend collapses the default
     *     {@code rotateY(180-bodyRot)} body rotation to identity, exposing the lid's authored
     *     UV orientation unrotated against the viewer
     * @param rendererScale per-entity render-time scale extracted by
     *     {@code EntityRendererScaleResolver}; defaults to {@code 1f} (identity)
     * @param stateTextures alternate base textures keyed by behavioural state (wolf
     *     {@code wild}/{@code tame}/{@code angry}), populated for multi-state variant families.
     *     Empty for every other entity; the {@code wild}
     *     entry (when present) equals {@link #textureRef}. Consulted by the renderer when
     *     {@code EntityAppearance.state} selects a non-default state, else {@link #textureRef} is used
     * @param collarTexture dyed-collar texture drawn on the body geometry and tinted at render by
     *     {@code EntityAppearance.collar} (wolf, cat). Present only in the family form
     *     for collar-bearing entities; empty otherwise
     * @param babyModel the distinct baked baby mesh, used in place of {@link #model} when
     *     {@code EntityAppearance.age} selects {@code baby}. Present only in the family form
     *     for ageable entities with a dedicated {@code Baby<X>Model}; empty otherwise
     * @param boneToggles named bone-visibility toggles (toggle name -&gt; {@link BoneToggle}), flipped
     *     at render when {@code EntityAppearance.toggles} selects the toggle: a default-hidden toggle
     *     (donkey/mule/llama {@code chest}) re-adds its bones, a default-visible toggle (goat
     *     {@code horn}) removes them. The default render is unchanged (chest stripped, horns present);
     *     empty for entities with no toggleable bones
     * @param largeShape the {@code shape} axis's large alternative (tropical fish): the large body
     *     mesh + {@code tropical_b} texture + pattern overlays cloned onto it, swapped in by
     *     {@link #resolveFor} when the selected pattern's {@code Shape} is large; empty otherwise
     * @param sizeModels the {@code size} axis's non-default alternate meshes keyed by {@link Size}
     *     (pufferfish {@link Size#SMALL} / {@link Size#MEDIUM}), swapped in by {@link #resolveFor} when
     *     {@code EntityAppearance.size} selects one. The entity's default size is the base {@link #model}
     *     and is absent from the map, so an unset / default size leaves the render byte-identical; empty
     *     for entities with no size axis
     * @param sizeScales the {@code size} axis's non-default render scale factors keyed by {@link Size}
     *     (salmon {@link Size#SMALL} 0.5 / {@link Size#LARGE} 1.5; slime + magma_cube {@link Size#MEDIUM}
     *     2 / {@link Size#LARGE} 4), multiplied onto {@link #rendererScale} by {@link #resolveFor} when
     *     {@code EntityAppearance.size} selects one. The default size is scale {@code 1.0} (absent), so an
     *     unset / default size leaves the render byte-identical; empty for mesh-select and no-size-axis
     *     entities. (A uniform scale is a visual no-op under the auto-fit renderer - see the size axis note)
     * @param markings whether the entity supports the horse {@code markings} axis - a same-geometry
     *     translucent overlay (white socks / blaze / patches) whose texture the renderer picks from
     *     {@code EntityAppearance.markings} and composites over the coat. {@code true} only for the
     *     horse; the default marking ({@code NONE}) draws nothing, so the default render is
     *     byte-identical
     */
    @Builder(toBuilder = true)
    public record EntityDefinition(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull List<OverlayLayer> overlays,
        @NotNull List<BlockOverlayLayer> blockOverlays,
        int baseTintArgb,
        float setupYawAddend,
        float rendererScale,
        @NotNull Map<String, String> stateTextures,
        @NotNull Optional<String> collarTexture,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull Map<String, BoneToggle> boneToggles,
        @NotNull List<EquipmentOverlay> equipment,
        @NotNull Optional<LargeShape> largeShape,
        @NotNull Map<Size, EntityModelData> sizeModels,
        @NotNull Map<Size, Float> sizeScales,
        boolean markings
    ) {
        /**
         * Returns a copy with no {@link #blockOverlays() block overlays}, for the {@code carried}
         * render toggle (a sheared snow golem, an empty-handed enderman) - dropping both the
         * rendered geometry and its canvas-bounds contribution.
         *
         * @return an otherwise-identical definition with an empty block-overlay list
         */
        public @NotNull EntityDefinition withoutBlockOverlays() {
            return toBuilder().blockOverlays(List.of()).build();
        }

        /**
         * Resolves this definition for the given {@link EntityAppearance}, folding the age / carried
         * policy into the returned definition so the renderer iterates its data unconditionally
         * (no scattered {@code !baby} gates). A baby swaps in the {@link #babyModel} and drops the
         * model overlays, block overlays, and collar - each carries adult geometry that would render
         * adult-sized around the smaller baby body. The block overlays are resolved against the
         * carried selection (see {@link #resolveBlockOverlays}): {@code carried == "none"} drops the
         * always-present decorations, and a selected block id fills the selectable held-block slots
         * (enderman carried block, iron golem flower). A non-baby, non-carried appearance returns an
         * equivalent definition unchanged.
         *
         * @param appearance the axis selections to resolve against
         * @return the age / carried / sheared-resolved definition
         */
        public @NotNull EntityDefinition resolveFor(@NotNull EntityAppearance appearance) {
            EntityDefinitionBuilder builder = toBuilder();
            if (appearance.isBaby() && this.babyModel.isPresent()) {
                builder.model(this.babyModel.get()).overlays(List.of()).blockOverlays(List.of()).collarTexture(Optional.empty()).equipment(List.of());
            } else {
                // Drop overlays the appearance doesn't activate: shearable overlays (the sheep wool)
                // when sheared - both the rendered geometry and its canvas-bounds contribution - and
                // charged-only overlays (the creeper energy swirl) unless the charged axis is set. A
                // charged overlay renders only for a lightning-struck entity, so the default (uncharged)
                // render is byte-identical. Only rebuilds the list when there is something to drop, so an
                // entity with no shearable / charged overlay keeps its exact overlay list.
                boolean hasCharged = this.overlays.stream().anyMatch(OverlayLayer::requiresCharged);
                if (appearance.isSheared() || hasCharged)
                    builder.overlays(this.overlays.stream()
                        .filter(overlay -> !(overlay.shearable() && appearance.isSheared()))
                        .filter(overlay -> !(overlay.requiresCharged() && !appearance.isCharged()))
                        .toList());
                // Selected bone toggles flip their bones' visibility (donkey/mule/llama chest reveal,
                // goat horns hide). Guarded to the non-baby path - the baby mesh has its own bones.
                // The sheared axis additionally activates the "sheared" toggle for entities that
                // declare one (bogged drops its mushrooms); entities whose sheared handling is
                // overlay-only (sheep wool) declare no such toggle and are left byte-identical.
                Set<String> selectedToggles = appearance.getToggles();
                if (appearance.isSheared() && this.boneToggles.containsKey("sheared")) {
                    selectedToggles = new LinkedHashSet<>(selectedToggles);
                    selectedToggles.add("sheared");
                }
                EntityModelData toggled = applyBoneToggles(selectedToggles);
                if (toggled != null) builder.model(toggled);
                builder.blockOverlays(resolveBlockOverlays(appearance));
                // The shape axis (tropical fish) swaps to the large body when the selected pattern's
                // Shape is large: the large mesh, tropical_b base texture, and the pattern overlays
                // cloned onto the large geometry (the pattern axis still picks the concrete overlay
                // texture via texture_by). A small/default pattern leaves the small body untouched, so
                // the default render is byte-identical.
                if (this.largeShape.isPresent()
                    && appearance.getPattern().map(p -> p.shape() == TropicalFishPattern.Shape.LARGE).orElse(false)) {
                    LargeShape large = this.largeShape.get();
                    builder.model(large.model()).textureRef(large.textureRef()).overlays(large.overlays());
                }
                // The size axis (pufferfish) swaps to the selected size's distinct baked mesh. An unset
                // size, or the entity's default size (pufferfish large = the base mesh, absent from the
                // map), leaves the base model untouched, so the default render is byte-identical.
                appearance.getSize().map(this.sizeModels::get).ifPresent(builder::model);
                // The size axis (salmon / slime / magma_cube) instead multiplies rendererScale by the
                // selected size's factor. An unset / default size (scale 1.0, absent from the map) leaves
                // rendererScale untouched, so the default render is byte-identical. A uniform scale is a
                // visual no-op under the auto-fit renderer (self-similar); the factor is applied for a
                // future absolute-scale scene renderer.
                appearance.getSize().map(this.sizeScales::get)
                    .ifPresent(scale -> builder.rendererScale(this.rendererScale * scale));
            }
            // The base_color axis (tropical fish) overrides the family base_tint with the selected
            // dye; absent (default) keeps the baked base_tint, so the default render is byte-identical.
            appearance.tint(TintAxis.BASE).ifPresent(color -> builder.baseTintArgb(color.argb()));
            return builder.build();
        }

        /**
         * Resolves this definition's block overlays against the appearance's carried selection. A
         * <b>fixed</b> overlay (mooshroom mushrooms, snow golem pumpkin) is kept unless
         * {@link EntityAppearance#dropsCarried() carried == "none"} drops it; a <b>selectable</b>
         * overlay (enderman carried block, iron golem flower) is kept only when
         * {@link EntityAppearance#selectedCarriedBlock() a block is selected}, with its block id
         * replaced by that selection. The default (empty) appearance therefore renders the fixed
         * decorations and no selectable held block - byte-identical to the pre-selectable behaviour.
         *
         * @param appearance the axis selections to resolve against
         * @return the resolved block-overlay list
         */
        private @NotNull List<BlockOverlayLayer> resolveBlockOverlays(@NotNull EntityAppearance appearance) {
            if (this.blockOverlays.isEmpty()) return this.blockOverlays;
            Optional<String> selected = appearance.selectedCarriedBlock();
            boolean dropsFixed = appearance.dropsCarried();
            List<BlockOverlayLayer> out = new ArrayList<>(this.blockOverlays.size());
            for (BlockOverlayLayer overlay : this.blockOverlays) {
                if (overlay.selectable())
                    selected.ifPresent(id -> out.add(overlay.withBlockId(id)));
                else if (!dropsFixed)
                    out.add(overlay);
            }
            return List.copyOf(out);
        }

        /**
         * Rebuilds {@link #model} with the appearance's selected {@link #boneToggles bone toggles}
         * flipped, or {@code null} when no selected toggle applies (leaving the default model). A
         * default-hidden toggle re-adds its bones (chest); a default-visible toggle removes them
         * (goat horns). Re-added bones' parents are already present, so the kit resolves them by name;
         * the rebuilt model grows/shrinks the canvas bounds automatically (the bounds walk reads the
         * resolved model).
         *
         * @param toggles the appearance's selected toggle names
         * @return the model with the selected toggle bones flipped, or {@code null} when none apply
         */
        private @Nullable EntityModelData applyBoneToggles(@NotNull Set<String> toggles) {
            if (toggles.isEmpty() || this.boneToggles.isEmpty()) return null;
            LinkedHashMap<String, EntityModelData.Bone> bones = null;
            for (String toggle : toggles) {
                BoneToggle spec = this.boneToggles.get(toggle);
                if (spec == null || spec.bones().isEmpty()) continue;
                if (bones == null) bones = new LinkedHashMap<>(this.model.getBones());
                if (spec.defaultVisible())
                    spec.bones().keySet().forEach(bones::remove);
                else
                    bones.putAll(spec.bones());
            }
            if (bones == null) return null;
            return new EntityModelData(
                this.model.getTextureWidth(), this.model.getTextureHeight(),
                this.model.getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), this.model.isCull());
        }
    }

    /**
     * One block-model overlay attached to an entity: a vanilla block (e.g. red mushroom block)
     * rendered at a specific transform on top of the entity body. Used by mooshroom (mushrooms
     * on back / between horns), enderman (carried block), iron golem (poppy), etc.
     *
     * <p>The {@code transforms} list is applied in order at render time, one push/pop scope per
     * block-overlay row (each row = one mushroom/flower/etc). Transforms operate in entity-local
     * coordinates - the block model's 0..1 unit cube is placed in the entity's frame after the
     * transform chain. Optionally pre-pended by an entity-bone pose ({@code attachedBone}) so
     * head-attached overlays (mooshroom's third mushroom between the horns) follow the head's
     * runtime / bind-pose rotation.
     *
     * @param blockId the block id to render (e.g. {@code "minecraft:red_mushroom_block"}); the
     *     documented default for a {@link #selectable} row (empty when the layer has no vanilla
     *     literal, as for the enderman carried block), always overridden at render by the caller's
     *     selection
     * @param attachedBone optional entity-bone whose pose stack pre-multiplies the transforms
     *     (e.g. {@code "head"} for the mooshroom horn-mushroom, {@code "right_arm"} for the iron
     *     golem flower). {@code null} when the overlay is positioned in the entity's root frame
     * @param transforms ordered list of {@code translate} / {@code rotate_y} / {@code rotate_x} /
     *     {@code scale} ops applied to the block model after the optional bone pose
     * @param selectable when {@code true} this overlay is a caller-selected held block (enderman
     *     carried block, iron golem flower) rather than an always-present body decoration
     *     (mooshroom mushrooms, snow golem pumpkin): it renders only when
     *     {@link EntityAppearance#selectedCarriedBlock()} supplies a block id, which replaces
     *     {@link #blockId}. The default (unselected) render draws no selectable overlay
     */
    public record BlockOverlayLayer(
        @NotNull String blockId,
        @Nullable String attachedBone,
        @NotNull List<TransformOp> transforms,
        boolean selectable
    ) {
        /**
         * Returns a copy with {@link #blockId} replaced by {@code newBlockId}, for resolving a
         * {@link #selectable} overlay against the caller's chosen carried block.
         *
         * @param newBlockId the block id to render in place of the documented default
         * @return an otherwise-identical overlay rendering {@code newBlockId}
         */
        public @NotNull BlockOverlayLayer withBlockId(@NotNull String newBlockId) {
            return new BlockOverlayLayer(newBlockId, this.attachedBone, this.transforms, this.selectable);
        }
    }

    /**
     * One transform operation in a {@link BlockOverlayLayer}'s chain. Mirrors the vanilla
     * {@code PoseStack} ops a render layer issues between {@code pushPose} / {@code popPose}:
     * {@code translate(F, F, F)} -> {@link Translate}, {@code mulPose(rotationDegrees(deg))} on
     * the Y axis -> {@link RotateY} / on the X axis -> {@link RotateX}, {@code scale(F, F, F)} ->
     * {@link Scale}.
     *
     * <p>Sealed so the renderer can pattern-match without a default branch. Add a new op kind
     * by extending the seal and updating both the JSON serialiser and the renderer dispatch.
     */
    public sealed interface TransformOp permits Translate, RotateY, RotateX, Scale {}

    /**
     * Translation by {@code (x, y, z)} in entity-local units.
     */
    public record Translate(float x, float y, float z) implements TransformOp {}

    /**
     * Rotation around the Y axis by {@code degrees}.
     */
    public record RotateY(float degrees) implements TransformOp {}

    /**
     * Rotation around the X axis by {@code degrees} (the enderman carried block's {@code Axis.XP}
     * tilt, the iron golem flower's {@code Axis.XP} lay-flat).
     */
    public record RotateX(float degrees) implements TransformOp {}

    /**
     * Per-axis scale {@code (x, y, z)}. Negative components flip the axis.
     */
    public record Scale(float x, float y, float z) implements TransformOp {}

    /**
     * One overlay layer on an {@link EntityDefinition}: an independent geometry plus its own
     * bundled texture sub-path. Resolved from the tooling-emitted {@code overlays} array at
     * load time.
     *
     * @param model the overlay's bone/cube tree, sharing the base model's coordinate frame so
     *     they co-register under the renderer's shared auto-fit transform
     * @param textureRef the bundled texture sub-path (without {@code .png}), or empty when the
     *     overlay should reuse the base entity's texture
     * @param emissive when {@code true} the overlay renders full-bright (unlit), mirroring vanilla
     *     Java's {@code RenderType.eyes} pattern, instead of shaded src-over. Tagged onto every
     *     triangle the overlay produces; the rasterizer keys off the per-triangle flag
     * @param tintArgb per-overlay multiplicative ARGB tint, mirroring vanilla's
     *     {@code coloredCutoutModelRender(..., color, ...)} colour argument (sheep wool colour,
     *     tropical-fish pattern colour). Defaults to {@code 0xFFFFFFFF} (white = no-op MULTIPLY)
     * @param skipBounds when {@code true} the overlay still renders but is excluded from the
     *     canvas-sizing bounds union - set for {@code skip_bounds=true} state-rendered decor layers
     *     the harness also skips (llama carpet), and for same-geometry overlays carrying only the
     *     auto-emitted {@value #DEPTH_CLEARANCE_INFLATE} depth-clearance inflate whose silhouette
     *     the base mesh already covers
     * @param tintBy the render-axis token whose selected colour overrides {@link #tintArgb} at
     *     render (e.g. {@code "wool_color"} for the sheep wool, tinted by
     *     {@code EntityAppearance.woolColor}), or empty when the tint is fixed at {@link #tintArgb}
     * @param shearable when {@code true} this overlay is dropped by the {@code sheared} render axis
     *     (the sheep wool, gated off by vanilla's {@code isSheared} state); {@code false} for
     *     overlays that always render
     * @param requiresTint when {@code true} this overlay only renders once its {@link #tintBy} colour
     *     is selected (the sheep wool undercoat); it is skipped for the default (untinted) entity so
     *     the default render stays byte-identical
     * @param textureBy the render-axis token whose selection overrides {@link #textureRef} at render
     *     (e.g. {@code "pattern"} for the tropical-fish pattern, sourced from
     *     {@code EntityAppearance.pattern}), or empty when the overlay texture is fixed at
     *     {@link #textureRef}
     * @param requiresCharged when {@code true} this overlay renders only for a charged
     *     (lightning-struck) entity - the creeper energy swirl, gated on {@code EntityAppearance.charged};
     *     {@link EntityDefinition#resolveFor} drops it for the default (uncharged) entity so that render
     *     stays byte-identical
     * @param blend the colour-composition mode the rasterizer composites this overlay with -
     *     {@link BlendMode#NORMAL} source-over (the default; also what a {@code translucent} node maps
     *     to, since the slime shell's translucency is in its texture alpha) or {@link BlendMode#ADD}
     *     for the additive energy-swirl glow ({@code blend: additive}). Parsed from the overlay's
     *     optional {@code blend} node, orthogonal to {@link #emissive}
     * @param alpha the per-fragment opacity multiplier in {@code [0, 1]} from the overlay's optional
     *     {@code alpha} node, multiplied into the sampled texel's alpha before the {@link #blend}
     *     composite. {@code 1.0} (no-op) except for an overlay carrying an explicit multiplier (the
     *     warden pulsating-spots glow at {@code 0.25}) - a fractional layer opacity that cannot ride
     *     the tint's alpha byte (the MULTIPLY tint blend preserves the texel alpha)
     */
    public record OverlayLayer(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        boolean emissive,
        int tintArgb,
        boolean skipBounds,
        @NotNull Optional<String> tintBy,
        boolean shearable,
        boolean requiresTint,
        @NotNull Optional<String> textureBy,
        boolean requiresCharged,
        @NotNull BlendMode blend,
        float alpha
    ) {}

    /**
     * One equipment overlay on an {@link EntityDefinition}: a saddle / body-armor mesh (its own baked
     * geometry) rendered on the body only when the {@code equipment} render axis selects its
     * {@link #slot}. Unlike an always-on {@link OverlayLayer}, the texture is chosen at render from
     * the axis-selected material through {@link #textureTemplate} (or {@link #defaultMaterial} when
     * the slot is selected without a material). Sourced from the family form's {@code equipment}
     * layers (see {@link EntityFamilyFlattener.EquipmentSpec}).
     *
     * @param slot the equipment slot this overlay is gated on ({@code saddle} / {@code body})
     * @param model the equipment mesh, resolved from the layer's baked {@code geometry_ref}
     * @param textureTemplate the equipment texture sub-path with a {@code <material>} placeholder
     *     ({@code equipment/pig_saddle/<material>}), resolved against {@code textures/entity/}
     * @param defaultMaterial the material substituted when the slot is selected without one
     *     ({@code saddle} for a saddle, {@code leather} for body armor)
     */
    public record EquipmentOverlay(
        @NotNull String slot,
        @NotNull EntityModelData model,
        @NotNull String textureTemplate,
        @NotNull String defaultMaterial
    ) {
        /**
         * Resolves the {@code textures/entity/} sub-path for a selected material, substituting it into
         * {@link #textureTemplate}; falls back to {@link #defaultMaterial} when {@code material} is
         * blank (the slot selected without an explicit material).
         *
         * @param material the axis-selected material, or blank to use {@link #defaultMaterial}
         * @return the resolved texture sub-path (without the {@code minecraft:entity/} prefix)
         */
        public @NotNull String textureFor(@NotNull String material) {
            String chosen = material.isBlank() ? this.defaultMaterial : material;
            return this.textureTemplate.replace("<material>", chosen);
        }
    }

    /**
     * Resolves an overlays JSON array into a list of {@link OverlayLayer}s. Each entry is an
     * object with a {@code geometry_ref} (resolved against the geometry table), an optional
     * {@code texture_ref} (the vanilla {@code textures/entity/} sub-path; absent means the
     * overlay reuses the base entity's texture), and an optional {@code inflate} (additive cube
     * inflate applied to every cube on the overlay so it surrounds the base mesh instead of
     * z-fighting - matches Java's armor-layer convention). Entries that name an unknown geometry
     * log a warning and drop so a stale override after a geometry regen doesn't abort the load.
     */
    private static @NotNull List<OverlayLayer> loadOverlays(
        @NotNull JsonArray overlays,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String baseGeometryRef,
        @NotNull EntityModelData baseModel,
        @NotNull String entityId
    ) {
        List<OverlayLayer> out = new ArrayList<>();
        for (JsonElement el : overlays) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (!entry.has("geometry_ref")) {
                System.err.printf("  Warning: entity '%s' overlay missing 'geometry_ref'%n", entityId);
                continue;
            }
            String geometryRef = entry.get("geometry_ref").getAsString();
            // When the overlay shares the base entity's geometry, reuse the post-override
            // base model so overlay cubes inherit bone_overrides / bind_poses / extra_bones
            // applied to the base. Otherwise (different geometry, e.g. slime outer shell or
            // creeper power overlay) resolve fresh from the geometry table.
            EntityModelData overlayModel;
            boolean sameGeometry = geometryRef.equals(baseGeometryRef);
            if (sameGeometry) {
                overlayModel = baseModel;
            } else {
                overlayModel = geometries.get(geometryRef);
                if (overlayModel == null) {
                    System.err.printf("  Warning: entity '%s' overlay references geometry '%s' which is not present in '%s'%n",
                        entityId, geometryRef, GEOMETRY_RESOURCE_PATH);
                    continue;
                }
            }
            Optional<String> overlayTexture = entry.has("texture_ref")
                ? Optional.of(entry.get("texture_ref").getAsString())
                : Optional.empty();
            // retain_bones (warden pulsating spots) restricts the overlay to a vanilla
            // retainExactParts subset of the shared mesh, so the glow texture draws only where
            // vanilla's subset LayerDefinition does (body + legs) instead of over-drawing the
            // whole silhouette. Applied before inflate so the surviving cubes inflate together.
            EntityModelData retained = entry.has("retain_bones") && entry.get("retain_bones").isJsonArray()
                ? retainExactParts(overlayModel, entry.getAsJsonArray("retain_bones"))
                : overlayModel;
            float inflate = entry.has("inflate") ? entry.get("inflate").getAsFloat() : 0f;
            EntityModelData materialised = inflate != 0f ? inflateModel(retained, inflate) : retained;
            boolean emissive = entry.has("emissive") && entry.get("emissive").getAsBoolean();
            // Per-overlay multiplicative tint. Vanilla wires per-layer color through
            // {@code coloredCutoutModelRender(model, texture, ..., color, order)} - sheep wool
            // gets {@code state.getWoolColor()}, tropical fish pattern gets {@code state.patternColor},
            // etc. JSON expects a hex string ("0xFFFFFFFF" / "#F9FFFE" / "0xF9FFFE") so it survives
            // round-trip with hand-edits. Defaults to 0xFFFFFFFF (white = no-op MULTIPLY tint).
            int overlayTint = entry.has("tint_color") ? parseTintArgb(entry.get("tint_color").getAsString()) : 0xFFFFFFFF;
            // Some equipment-driven overlays (LlamaDecorLayer carpet, possibly future similar
            // layers) are state-rendered by vanilla but the harness skips them from bounds via
            // NO_RENDER_LAYER_SUFFIXES - the carpet's inflate-0.5 mesh would over-pad the canvas
            // around a body that doesn't actually need that margin. `skip_bounds=true` mirrors
            // that policy here: the overlay still renders, but EntityRenderer.computeUnionScreen
            // Bounds ignores it when sizing the canvas.
            //
            // Same-geometry overlays (eyes / clothing-pattern - geometry_ref == base) carrying
            // ONLY the auto-emitted depth-clearance inflate are skipped from bounds: they render
            // the IDENTICAL cube tree as the base (vanilla submits the same ModelPart through a
            // second render type with NO inflate), so the base already contributes their full
            // silhouette extent. The {@value #DEPTH_CLEARANCE_INFLATE} we add to win the coplanar
            // depth tie is OUR artifact, not vanilla geometry; letting it pad the bounds shifts
            // the whole silhouette off-centre - breeze's solid head, sitting at the canvas top,
            // rendered 1px short because its inflated eyes overlay defined a canvas top ~0.016px
            // higher than the bare head (breeze 0.41 -> 0.18; the eye silhouette coverage now
            // matches vanilla exactly). The inflate is preserved for the render, only excluded
            // from canvas sizing.
            //
            // A LARGER inflate on a same-geometry overlay is a real vanilla {@code CubeDeformation}
            // (tropical_fish FISH_PATTERN_DEFORMATION 0.008, llama carpet 0.5) that vanilla's own
            // bounds walk includes - those MUST keep contributing or the canvas comes out 1px
            // short (tropical_fish 103 -> 102, delta 0.31 -> 6.20). So gate the auto-skip on the
            // inflate being the small depth-clearance value; explicit skip_bounds still wins.
            boolean depthClearanceOnly = sameGeometry && inflate <= DEPTH_CLEARANCE_INFLATE;
            boolean skipBounds = (entry.has("skip_bounds") && entry.get("skip_bounds").getAsBoolean())
                || depthClearanceOnly;
            Optional<String> tintBy = entry.has("tint_by")
                ? Optional.of(entry.get("tint_by").getAsString())
                : Optional.empty();
            boolean shearable = entry.has("shearable") && entry.get("shearable").getAsBoolean();
            boolean requiresTint = entry.has("requires_tint") && entry.get("requires_tint").getAsBoolean();
            Optional<String> textureBy = entry.has("texture_by")
                ? Optional.of(entry.get("texture_by").getAsString())
                : Optional.empty();
            boolean requiresCharged = entry.has("requires_charged") && entry.get("requires_charged").getAsBoolean();
            // blend / alpha (optional; default NORMAL / 1.0). An overlay declares its exact vanilla
            // colour-composition mode and opacity multiplier here instead of relying on the single
            // hardcoded NORMAL: `additive` -> the energy-swirl glow, `translucent` / `normal` ->
            // source-over (the slime shell's translucency lives in its texture alpha). An un-annotated
            // overlay keeps NORMAL / 1.0, so it renders byte-identical.
            BlendMode blend = parseBlend(entry.has("blend") ? entry.get("blend").getAsString() : null);
            float alpha = entry.has("alpha") ? entry.get("alpha").getAsFloat() : 1f;
            out.add(new OverlayLayer(materialised, overlayTexture, emissive, overlayTint, skipBounds, tintBy, shearable, requiresTint, textureBy, requiresCharged, blend, alpha));
        }
        return out;
    }

    /**
     * The {@code shape} axis's large-body alternative (tropical fish), resolved eagerly at load: the
     * large body mesh, its base texture, and the pattern overlays materialised onto the large
     * geometry. {@link EntityDefinition#resolveFor} swaps these in wholesale when the selected
     * pattern's {@code Shape} is large.
     *
     * @param model the large body mesh
     * @param textureRef the large body base texture ({@code fish/tropical_b})
     * @param overlays the pattern overlays materialised on the large mesh
     */
    public record LargeShape(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull List<OverlayLayer> overlays
    ) {}

    /**
     * Builds the {@link LargeShape} from a flattened {@link EntityFamilyFlattener.ShapeSpec}, resolving
     * the large geometry and materialising the large overlays through {@link #loadOverlays}. Returns
     * empty when the spec is absent or its geometry is missing.
     *
     * @param spec the flattened shape spec (may be {@code null})
     * @param geometries the geometry table
     * @param entityId the owning entity id, for overlay warnings
     * @return the resolved large shape, or empty
     */
    private static @NotNull Optional<LargeShape> buildLargeShape(
        @Nullable EntityFamilyFlattener.ShapeSpec spec,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        if (spec == null) return Optional.empty();
        EntityModelData largeModel = geometries.get(spec.geometryRef());
        if (largeModel == null) return Optional.empty();
        List<OverlayLayer> largeOverlays = loadOverlays(spec.overlays(), geometries, spec.geometryRef(), largeModel, entityId);
        return Optional.of(new LargeShape(largeModel, Optional.of(spec.textureRef()), largeOverlays));
    }

    /**
     * Resolves a family's flattened {@code size} axis alternatives ({@code size option -> geometry ref})
     * into {@code Size -> mesh}, looking each geometry up in the table. Absent / unresolvable meshes are
     * skipped; the entity's default size is the base mesh and never appears here.
     *
     * @param alternatives the flattened {@code size option -> geometry ref} map (may be {@code null})
     * @param geometries the geometry table
     * @return the resolved per-size meshes, empty when the entity has no size axis
     */
    private static @NotNull Map<Size, EntityModelData> buildSizeModels(
        @Nullable Map<String, String> alternatives,
        @NotNull Map<String, EntityModelData> geometries
    ) {
        if (alternatives == null || alternatives.isEmpty()) return Map.of();
        Map<Size, EntityModelData> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : alternatives.entrySet()) {
            EntityModelData mesh = geometries.get(entry.getValue());
            if (mesh != null) out.put(Size.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), mesh);
        }
        return out;
    }

    /**
     * Resolves a family's flattened {@code size} axis scale factors ({@code size option -> factor}) into
     * {@code Size -> factor} (salmon / slime / magma_cube). The entity's default size is scale {@code 1.0}
     * and never appears here.
     *
     * @param scales the flattened {@code size option -> scale factor} map (may be {@code null})
     * @return the resolved per-size scale factors, empty when the entity has no scale-based size axis
     */
    private static @NotNull Map<Size, Float> buildSizeScales(@Nullable Map<String, Float> scales) {
        if (scales == null || scales.isEmpty()) return Map.of();
        Map<Size, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : scales.entrySet())
            out.put(Size.valueOf(entry.getKey().toUpperCase(Locale.ROOT)), entry.getValue());
        return out;
    }

    /**
     * Parses an overlay's optional {@code blend} node into a {@link BlendMode}. {@code "additive"}
     * maps to {@link BlendMode#ADD} (the energy-swirl glow); {@code "translucent"}, {@code "normal"},
     * and an absent node all map to {@link BlendMode#NORMAL} source-over - {@code translucent} composites
     * exactly like {@code normal} at the fragment level (its shell translucency is carried in the
     * texture's own alpha, not a blend-function difference), and the node exists so the tooling can
     * declare the vanilla blend and drop the shell out of its force-emit policy. An unrecognised value
     * logs a warning and falls back to {@link BlendMode#NORMAL}.
     *
     * @param blend the {@code blend} node value, or {@code null} when absent
     * @return the mapped blend mode
     */
    private static @NotNull BlendMode parseBlend(@Nullable String blend) {
        if (blend == null) return BlendMode.NORMAL;
        return switch (blend.toLowerCase(Locale.ROOT)) {
            case "additive" -> BlendMode.ADD;
            case "translucent", "normal" -> BlendMode.NORMAL;
            default -> {
                System.err.printf("  Warning: unknown overlay blend '%s' (expected normal/additive/translucent); using normal%n", blend);
                yield BlendMode.NORMAL;
            }
        };
    }

    /**
     * Restricts an overlay model to the vanilla {@code PartDefinition.retainExactParts} subset named by
     * {@code retainBones}, mirroring the {@code createBodyLayer().apply(retainExactParts(...))} bake a
     * subset {@code LayerDefinition} carries (the warden pulsating spots). A bone keeps its cubes iff it
     * is named in the set <b>and</b> no ancestor is - because vanilla's {@code retainExactParts} clears a
     * retained part's whole descendant subtree ({@code clearRecursively}), so a nested named part
     * (warden {@code head} under the retained {@code body}) is emptied. Every other bone is kept as a
     * pose-only node (cubes removed) so the surviving bones' transform hierarchy stays intact. Reuses the
     * shared mesh, so no distinct geometry is baked (the geometry table stays byte-stable).
     *
     * @param source the overlay's shared base mesh
     * @param retainBones the {@code retain_bones} JSON array (the vanilla {@code retainExactParts} set)
     * @return the subset model with cubes only on the effectively-retained bones
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
        return new EntityModelData(
            source.getTextureWidth(), source.getTextureHeight(),
            source.getInventoryYRotation(), Concurrent.adoptLinkedMap(out), source.isCull());
    }

    /**
     * Reports whether any proper ancestor of {@code bone} (walking the {@link EntityModelData.Bone#getParent()
     * parent} chain) is named in {@code retain} - the condition under which {@code retainExactParts}
     * clears the bone's cubes even though it is itself retained.
     *
     * @param bones the bone table (parent-name lookup)
     * @param bone the bone whose ancestry to test
     * @param retain the retain set
     * @return {@code true} when a proper ancestor is in the set
     */
    private static boolean hasAncestorInSet(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull EntityModelData.Bone bone,
        @NotNull Set<String> retain
    ) {
        for (String parent = bone.getParent(); parent != null; ) {
            if (retain.contains(parent)) return true;
            EntityModelData.Bone p = bones.get(parent);
            if (p == null) return false;
            parent = p.getParent();
        }
        return false;
    }

    /**
     * Returns a deep-cloned copy of {@code model} with every cube's {@link
     * EntityModelData.Cube#getInflate() inflate} field bumped by {@code delta}. Used by the
     * overlay loader to surround the base mesh with an inflated overlay (creeper armor mesh
     * needs ~2 units of inflate so its translucent lightning grid sits around the base creeper
     * instead of z-fighting with it). Bones, pivots, rotations, UVs, and parent links are
     * preserved verbatim - only the per-cube inflate changes.
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
                    cube.getPivot(), cube.getRotation(), cube.getFaceUv()
                ));
            inflated.put(e.getKey(), new EntityModelData.Bone(
                bone.getPivot(), bone.getRotation(), bone.getBindPoseRotation(),
                bone.getScale(), Concurrent.adoptList(cubes), bone.getParent()
            ));
        }
        return new EntityModelData(
            source.getTextureWidth(), source.getTextureHeight(),
            source.getInventoryYRotation(), Concurrent.adoptLinkedMap(inflated), source.isCull()
        );
    }

    /**
     * Resolves the flattened equipment specs for an entity into {@link EquipmentOverlay}s, binding
     * each spec's {@code geometry_ref} to its baked mesh from the geometry table. A spec naming an
     * unknown geometry logs a warning and drops (a stale layer after a geometry regen).
     *
     * @param specs the entity's equipment specs from the flattener (may be {@code null} for none)
     * @param geometries the deduped geometry table
     * @param entityId the entity id being loaded (diagnostics)
     * @return the resolved equipment overlays (empty when the entity has none)
     */
    private static @NotNull List<EquipmentOverlay> loadEquipment(
        @Nullable List<EntityFamilyFlattener.EquipmentSpec> specs,
        @NotNull Map<String, EntityModelData> geometries,
        @NotNull String entityId
    ) {
        if (specs == null || specs.isEmpty()) return List.of();
        List<EquipmentOverlay> out = new ArrayList<>(specs.size());
        for (EntityFamilyFlattener.EquipmentSpec spec : specs) {
            EntityModelData model = geometries.get(spec.geometryRef());
            if (model == null) {
                System.err.printf("  Warning: entity '%s' equipment layer references geometry '%s' which is not present in '%s'%n",
                    entityId, spec.geometryRef(), GEOMETRY_RESOURCE_PATH);
                continue;
            }
            out.add(new EquipmentOverlay(spec.slot(), model, spec.textureTemplate(), spec.defaultMaterial()));
        }
        return List.copyOf(out);
    }

    /**
     * Parses a JSON tint string into an ARGB int the rasterizer's {@code MULTIPLY} blend
     * consumes. Accepted forms:
     * <ul>
     * <li>{@code "0xRRGGBB"} / {@code "RRGGBB"} - 6 hex digits, alpha defaults to {@code FF};</li>
     * <li>{@code "0xAARRGGBB"} / {@code "AARRGGBB"} - 8 hex digits, full ARGB;</li>
     * <li>{@code "#RRGGBB"} / {@code "#AARRGGBB"} - CSS-style hash prefix.</li>
     * </ul>
     * Malformed strings log a warning and return {@code 0xFFFFFFFF} (the white = no-op
     * MULTIPLY tint) so a typo in a tooling-emitted tint value doesn't tint the entity
     * entirely black.
     */
    private static int parseTintArgb(@NotNull String spec) {
        String hex = spec.startsWith("0x") || spec.startsWith("0X") ? spec.substring(2)
            : spec.startsWith("#") ? spec.substring(1)
            : spec;
        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() <= 6) value |= 0xFF000000L;
            return (int) value;
        } catch (NumberFormatException ex) {
            System.err.printf("  Warning: malformed tint_color / base_tint value '%s' (expected hex); falling back to 0xFFFFFFFF%n", spec);
            return 0xFFFFFFFF;
        }
    }

    /**
     * Strips bones named in the {@code hidden_bones} array from the cloned bone map. The Java
     * pipeline's geometries pack every optional render target into one tree (humanoid outer-layer
     * over inner, equine saddle bones over base body) and rely on entity-state flags at render
     * time to gate them. The static renderer has no live entity state, so unwanted bones must be
     * hidden via this list (populated by {@code EntityHiddenBonesResolver} at tooling time).
     * Missing bones log a warning.
     */
    private static void applyHiddenBones(
        @NotNull Map<String, EntityModelData.Bone> bones,
        @NotNull JsonArray hiddenBones,
        @NotNull String entityId
    ) {
        for (JsonElement el : hiddenBones) {
            if (!el.isJsonPrimitive()) continue;
            String name = el.getAsString();
            if (bones.remove(name) == null)
                System.err.printf("  Warning: entity '%s' hidden_bones names bone '%s' which is not on the geometry%n",
                    entityId, name);
        }
    }


    /**
     * Loads bundled entity definitions, joining per-entity metadata against the deduplicated
     * geometry table.
     *
     * @return definitions keyed by namespaced entity id (empty when geometry resource absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an
     *     entity references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        Map<String, EntityModelData> geometries = loadGeometries();
        if (geometries.isEmpty()) return Concurrent.newMap();

        EntityFamilyFlattener.Flat flat = loadFlat();
        JsonObject entities = flat.entities();
        Map<String, Map<String, String>> stateTextures = flat.stateTextures();
        Map<String, String> collarTextures = flat.collarTextures();
        Map<String, String> babyGeometryRefs = flat.babyGeometry();
        Map<String, List<EntityFamilyFlattener.EquipmentSpec>> equipmentSpecs = flat.equipment();
        Map<String, EntityFamilyFlattener.ShapeSpec> shapeAlternatives = flat.shapeAlternatives();
        Map<String, Map<String, String>> sizeAlternatives = flat.sizeAlternatives();
        Map<String, Map<String, Float>> sizeScaleRefs = flat.sizeScales();
        Set<String> markingsRows = flat.markingsRows();
        HashMap<String, EntityDefinition> definitions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
            String entityId = entry.getKey();
            JsonObject entityJson = entry.getValue().getAsJsonObject();
            if (!entityJson.has("geometry_ref")) continue;

            String geometryRef = entityJson.get("geometry_ref").getAsString();
            EntityModelData baseModel = geometries.get(geometryRef);
            if (baseModel == null)
                throw new PipelineException(
                    "Entity '%s' references geometry '%s' which is not present in '%s'",
                    entityId, geometryRef, GEOMETRY_RESOURCE_PATH
                );

            Optional<String> textureRef = entityJson.has("texture_ref")
                ? Optional.of(entityJson.get("texture_ref").getAsString())
                : Optional.empty();

            // bone_toggles: capture each toggle's Bone objects from the FULL geometry BEFORE the
            // hidden-bones strip below, so resolveFor can re-insert them when a render option selects
            // the toggle (donkey/mule/llama chest). The bones also appear in hidden_bones and are
            // stripped just below, so the default model - and its render - stay byte-identical.
            Map<String, BoneToggle> boneToggles =
                entityJson.has("bone_toggles") && entityJson.get("bone_toggles").isJsonObject()
                    ? loadBoneToggles(entityJson.getAsJsonObject("bone_toggles"), baseModel, entityId)
                    : Map.of();

            // Tooling-derived hidden bones from the model class's <init>
            // (EntityHiddenBonesResolver: visible = false unconditionally, plus the
            // setupAnim equipment-gated scan for state.hasChest etc.).
            if (entityJson.has("hidden_bones") && entityJson.get("hidden_bones").isJsonArray()) {
                LinkedHashMap<String, EntityModelData.Bone> bones = new LinkedHashMap<>(baseModel.getBones());
                applyHiddenBones(bones, entityJson.getAsJsonArray("hidden_bones"), entityId);
                baseModel = new EntityModelData(
                    baseModel.getTextureWidth(),
                    baseModel.getTextureHeight(),
                    baseModel.getInventoryYRotation(),
                    Concurrent.adoptLinkedMap(bones),
                    baseModel.isCull()
                );
            }

            // Overlays emitted by EntityOverlayResolver during tooling (emissive eye layers +
            // composite layers). An overlay sharing the base geometry_ref reuses baseModel
            // verbatim (eye PNGs land on the same UV layout); a distinct geometry_ref resolves
            // freshly from the geometry table.
            List<OverlayLayer> overlays = entityJson.has("overlays") && entityJson.get("overlays").isJsonArray()
                ? loadOverlays(entityJson.getAsJsonArray("overlays"), geometries, geometryRef, baseModel, entityId)
                : List.of();

            List<BlockOverlayLayer> blockOverlays = entityJson.has("block_overlays") && entityJson.get("block_overlays").isJsonArray()
                ? loadBlockOverlays(entityJson.getAsJsonArray("block_overlays"))
                : List.of();

            int baseTint = entityJson.has("base_tint")
                ? parseTintArgb(entityJson.get("base_tint").getAsString())
                : 0xFFFFFFFF;

            // Bytecode-extracted bodyRot addend from the vanilla renderer's setupRotations
            // override - currently only Shulker uses this (+180F).
            float setupYawAddend = entityJson.has("setup_yaw_addend")
                ? entityJson.get("setup_yaw_addend").getAsFloat()
                : 0f;

            // Per-entity render-time scale extracted by EntityRendererScaleResolver from the
            // renderer's scale(state, poseStack) override (wither 2.0; slime 0.999).
            float rendererScale = entityJson.has("renderer_scale")
                ? entityJson.get("renderer_scale").getAsFloat()
                : 1f;

            String babyRef = babyGeometryRefs.get(entityId);
            Optional<EntityModelData> babyModel = babyRef == null ? Optional.empty() : Optional.ofNullable(geometries.get(babyRef));
            List<EquipmentOverlay> equipment = loadEquipment(equipmentSpecs.get(entityId), geometries, entityId);
            // The shape axis's large alternative (tropical fish): the large body mesh + tropical_b
            // texture + the pattern overlays cloned onto the large geometry, resolved eagerly so
            // resolveFor can swap them in when the selected pattern's Shape is large.
            Optional<LargeShape> largeShape = buildLargeShape(shapeAlternatives.get(entityId), geometries, entityId);
            // The size axis's non-default meshes (pufferfish small / medium), resolved so resolveFor can
            // swap the base mesh for the selected size.
            Map<Size, EntityModelData> sizeModels = buildSizeModels(sizeAlternatives.get(entityId), geometries);
            // The size axis's non-default scale factors (salmon / slime / magma_cube), so resolveFor can
            // multiply rendererScale by the selected size.
            Map<Size, Float> sizeScales = buildSizeScales(sizeScaleRefs.get(entityId));
            definitions.put(entityId, new EntityDefinition(baseModel, textureRef, overlays, blockOverlays, baseTint, setupYawAddend, rendererScale,
                stateTextures.getOrDefault(entityId, Map.of()), Optional.ofNullable(collarTextures.get(entityId)), babyModel, boneToggles, equipment, largeShape, sizeModels, sizeScales,
                markingsRows.contains(entityId)));
        }
        return Concurrent.adoptMap(definitions);
    }

    /**
     * A named bone-visibility toggle resolved at load: the geometry {@link EntityModelData.Bone bones}
     * it flips (kept by name so {@link EntityDefinition#resolveFor} can add or remove them) plus their
     * default visibility. {@code defaultVisible = false} (donkey chest) - the bones are stripped from
     * the default model and the toggle re-adds them; {@code defaultVisible = true} (goat horns) - the
     * bones render by default and the toggle removes them.
     *
     * @param bones the toggle's bones keyed by name
     * @param defaultVisible whether the bones render by default (true = toggle hides; false = toggle reveals)
     */
    public record BoneToggle(
        @NotNull Map<String, EntityModelData.Bone> bones,
        boolean defaultVisible
    ) {}

    /**
     * Resolves a {@code bone_toggles} JSON object ({@code toggle -> {bones:[...], default:<bool>}})
     * into {@code toggle -> }{@link BoneToggle}, pulling each named bone's {@link EntityModelData.Bone}
     * from the {@code fullModel} (the geometry BEFORE the {@code hidden_bones} strip, so a
     * default-hidden toggle's bones are still present to re-add). A named bone absent from the
     * geometry logs a warning and drops; a toggle left with no resolvable bones is omitted.
     *
     * @param toggles the {@code bone_toggles} object
     * @param fullModel the full (pre-strip) geometry the toggle bones live on
     * @param entityId the owning entity id, for warnings
     * @return toggle name -&gt; {@link BoneToggle}, empty when nothing resolves
     */
    private static @NotNull Map<String, BoneToggle> loadBoneToggles(
        @NotNull JsonObject toggles,
        @NotNull EntityModelData fullModel,
        @NotNull String entityId
    ) {
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
                    System.err.printf("  Warning: entity '%s' bone_toggles '%s' names bone '%s' which is not on the geometry%n",
                        entityId, entry.getKey(), boneName);
                    continue;
                }
                bones.put(boneName, bone);
            }
            if (!bones.isEmpty()) out.put(entry.getKey(), new BoneToggle(bones, defaultVisible));
        }
        return out;
    }

    /**
     * Resolves the Java pipeline's {@code block_overlays} JSON array into {@link BlockOverlayLayer}
     * rows. Each entry has a {@code block_id}, optional {@code attached_bone}, and a
     * {@code transforms} array whose entries are tagged objects: {@code {"op":"translate","x":...,"y":...,"z":...}}
     * / {@code {"op":"rotate_y","degrees":...}} / {@code {"op":"scale","x":...,"y":...,"z":...}}.
     */
    private static @NotNull List<BlockOverlayLayer> loadBlockOverlays(@NotNull JsonArray array) {
        List<BlockOverlayLayer> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            boolean selectable = row.has("selectable") && row.get("selectable").getAsBoolean();
            // A fixed row needs its block_id; a selectable row's block is supplied at render from
            // EntityAppearance.carried, so its emitted block_id (poppy for the iron golem flower) is
            // only a documented default and may be omitted entirely (the enderman carried block has
            // no vanilla literal). Skip only rows that carry neither.
            if (!row.has("block_id") && !selectable) continue;
            String blockId = row.has("block_id") ? row.get("block_id").getAsString() : "";
            String attachedBone = row.has("attached_bone") && !row.get("attached_bone").isJsonNull()
                ? row.get("attached_bone").getAsString()
                : null;
            List<TransformOp> ops = new ArrayList<>();
            if (row.has("transforms") && row.get("transforms").isJsonArray()) {
                for (JsonElement opElement : row.getAsJsonArray("transforms")) {
                    if (!opElement.isJsonObject()) continue;
                    JsonObject opObj = opElement.getAsJsonObject();
                    String kind = opObj.has("op") ? opObj.get("op").getAsString() : "";
                    switch (kind) {
                        case "translate" -> ops.add(new Translate(
                            opObj.get("x").getAsFloat(),
                            opObj.get("y").getAsFloat(),
                            opObj.get("z").getAsFloat()));
                        case "rotate_y" -> ops.add(new RotateY(opObj.get("degrees").getAsFloat()));
                        case "rotate_x" -> ops.add(new RotateX(opObj.get("degrees").getAsFloat()));
                        case "scale" -> ops.add(new Scale(
                            opObj.get("x").getAsFloat(),
                            opObj.get("y").getAsFloat(),
                            opObj.get("z").getAsFloat()));
                        default -> { }
                    }
                }
            }
            out.add(new BlockOverlayLayer(blockId, attachedBone, List.copyOf(ops), selectable));
        }
        return List.copyOf(out);
    }

    /**
     * Reads {@link #GEOMETRY_RESOURCE_PATH}. Returns an empty map when the file is absent (so
     * {@link #load()} can short-circuit to "no Java pipeline available" without throwing during
     * environments that haven't run {@code ToolingEntityModels} yet).
     */
    private static @NotNull Map<String, EntityModelData> loadGeometries() {
        return readGeometriesJsonResource(GEOMETRY_RESOURCE_PATH, /*required*/ false);
    }

    /**
     * Lazily-computed {@code entityId -> familyMembers} result of {@link #loadFamilies()}, cached
     * for the JVM lifetime (the underlying JSON never changes at runtime).
     */
    private static volatile Map<String, List<String>> FAMILIES_CACHE;

    /**
     * Folds a cross-entity {@code families} JSON object (derivative id -&gt; family root) into a
     * string map, skipping non-primitive entries.
     */
    private static @NotNull Map<String, String> familiesToMap(@NotNull JsonObject families) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : families.entrySet())
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    /**
     * Returns {@code entityId -> familyMembers} keyed by every Java-pipeline entity id. Family
     * membership is derived from {@code variant_of} in entity_models.json (variant entities
     * roll up to their declared root) plus the top-level {@code families} table emitted by
     * ToolingEntityModels (cross-entity groupings like mooshroom -> cow). Singletons return a
     * single-element list containing themselves so callers can iterate uniformly without
     * special-casing. The result is cached on first call - the JSON is loaded once for the
     * lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamilies() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            EntityFamilyFlattener.Flat flat = loadFlat();
            JsonObject entities = flat.entities();
            Map<String, String> familiesTable = familiesToMap(flat.families());
            // Two-pass: first pass assigns each entity to its family root via variant_of /
            // families table; second pass inverts the map to family -> [members] so any member
            // looking up by its own id sees the whole family.
            Map<String, String> entityToFamily = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : entities.entrySet()) {
                String entityId = entry.getKey();
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                String family = familiesTable.get(entityId);
                if (family == null && obj.has("variant_of"))
                    family = obj.get("variant_of").getAsString();
                if (family == null) family = entityId;
                entityToFamily.put(entityId, family);
            }
            Map<String, List<String>> familyToMembers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : entityToFamily.entrySet())
                familyToMembers.computeIfAbsent(e.getValue(), k -> new java.util.ArrayList<>()).add(e.getKey());
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : entityToFamily.entrySet())
                result.put(e.getKey(), List.copyOf(familyToMembers.get(e.getValue())));
            FAMILIES_CACHE = result;
            return result;
        }
    }

    /**
     * Parses the {@code geometries} table from the JSON resource at {@code path} into
     * {@code geometryRef -> }{@link EntityModelData}. Comment keys (those starting with {@code //})
     * are skipped so hand-annotated fixtures survive the parse. Returns an empty map when the
     * resource is absent and {@code required} is {@code false}.
     *
     * @param path the classpath resource path to read
     * @param required when {@code true} a missing resource throws instead of returning empty
     * @return the parsed geometry table, empty when the optional resource is absent
     * @throws PipelineException when {@code required} and the resource is missing, or when the JSON
     *     is present but unparseable
     */
    private static @NotNull Map<String, EntityModelData> readGeometriesJsonResource(@NotNull String path, boolean required) {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(path)) {
            if (stream == null) {
                if (required)
                    throw new PipelineException("Entity geometry resource '%s' not found on the classpath", path);
                return new LinkedHashMap<>();
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("geometries")) return new LinkedHashMap<>();
            JsonObject geometriesJson = root.getAsJsonObject("geometries");
            Map<String, EntityModelData> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : geometriesJson.entrySet()) {
                if (entry.getKey().startsWith("//")) continue;
                out.put(entry.getKey(), GSON.fromJson(entry.getValue(), EntityModelData.class));
            }
            return out;
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity geometry resource '%s'", path);
        }
    }

    /**
     * Reads the family-form {@link #MODELS_RESOURCE_PATH} and flattens it via
     * {@link EntityFamilyFlattener} into the runtime tables (flat entities + cross-entity families +
     * the option side-channels: state textures, collar textures, baby geometry). Returns an empty
     * {@link EntityFamilyFlattener.Flat} when the resource is absent.
     */
    private static EntityFamilyFlattener.@NotNull Flat loadFlat() {
        try (InputStream stream = EntityModelLoader.class.getResourceAsStream(MODELS_RESOURCE_PATH)) {
            if (stream == null)
                return EntityFamilyFlattener.Flat.empty();
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null || !root.has("families"))
                return EntityFamilyFlattener.Flat.empty();
            return EntityFamilyFlattener.flatten(root.getAsJsonObject("families"));
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Failed to load entity models resource '%s'", MODELS_RESOURCE_PATH);
        }
    }

}
