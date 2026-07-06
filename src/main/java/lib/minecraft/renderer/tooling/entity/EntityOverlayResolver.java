package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Walks each {@code RenderLayer} subclass attached to an entity renderer (per
 * {@link EntityBoneResolver#scanOverlayLayers}) and extracts the data needed to emit a
 * runtime overlay row into {@code entity_models.json}. Emissive eye overlays (e.g. {@code SpiderEyesLayer},
 * {@code EnderEyesLayer}, {@code PhantomEyesLayer}, {@code BreezeEyesLayer}) are detected
 * via the {@code <clinit>} bytecode - any layer whose static initializer pre-builds a
 * {@code RenderType} via an {@code RenderTypes.*eyes*(Identifier)} static factory call and
 * (in vanilla) stores it in a {@code static final} field. Detection runs on the bytecode
 * shape rather than class hierarchy - the matcher only requires the {@code LDC} + eyes-named
 * {@code RenderTypes.*eyes*(Identifier)} {@code INVOKESTATIC} pair, not the {@code PUTSTATIC} -
 * because not every eye layer extends {@code EyesLayer}: {@code BreezeEyesLayer} extends
 * {@code RenderLayer} directly but uses the same factory shape
 * ({@code RenderTypes.breezeEyes(...)}). See {@link #findEyesOverlayBinding}.
 *
 * <p>The same-geometry emissive fallback at the tail of {@link #resolve} additionally handles
 * two inline (no {@code addLayer(new EyesLayer(...))}) shapes: the
 * {@code EnderDragonRenderer}-style static {@code RenderType} field bound in the renderer's own
 * {@code <clinit>}, and the {@code CopperGolemRenderer}-style
 * {@code new LivingEntityEmissiveLayer(this, provider::eyeTextureLocationFor, ...)} whose
 * zero-state texture is resolved by {@link #findLivingEntityEmissiveTexture}.
 *
 * <p>Layer types deliberately not handled (deferred to later increments):
 * <ul>
 *   <li>{@code LivingEntityEmissiveLayer} for {@code creaking} / {@code warden} - unlike
 *       copper_golem (handled above), these bake a DISTINCT model layer
 *       ({@code WARDEN_BIOLUMINESCENT}, {@code CREAKING_EYES}) rather than reusing the base
 *       renderer's {@code ModelLayers}, so the shared-model-layer gate in
 *       {@link #findLivingEntityEmissiveTexture} rejects them; their separate-geometry
 *       overlays await the layer-class path.</li>
 *   <li>{@code CreeperPowerLayer}, {@code CopperGolemFlowerLayer} - have their own
 *       {@code LayerDefinition} requiring a separate geometry walk into
 *       {@code LayerDefinitions.createRoots} (kit work for a future increment).</li>
 *   <li>{@code HumanoidArmorLayer}, {@code SimpleEquipmentLayer}, {@code BeeStingerLayer},
 *       *ClothingLayer, *EquipmentLayer - driven by runtime player equipment, not part of the
 *       static-pose render contract. (The {@code SkeletonClothingLayer} family is the exception:
 *       it resolves via {@link #findParameterizedOverlayBinding} because its clothes texture is
 *       a hardcoded ctor arg rather than runtime equipment.)</li>
 * </ul>
 *
 * <p>{@code SheepWoolUndercoatLayer} IS walked via the generic composite-overlay path but is
 * then suppressed at zero render state by {@link #POLICY_SUPPRESS} (a default white sheep draws
 * no undercoat).
 */
@UtilityClass
public final class EntityOverlayResolver {

    /**
     * Resource path prefix for entity texture LDCs - matches what the texture resolver uses.
     */
    private static final @NotNull String TEXTURE_PATH_PREFIX = "textures/entity/";

    /**
     * JVM descriptor suffix for any method returning a {@code RenderType}.
     */
    private static final @NotNull String RENDER_TYPE_RETURN =
        ")L" + lib.minecraft.renderer.tooling.util.VanillaSourceClasses.RENDER_TYPE + ";";

    /**
     * Composite-overlay layers whose pipeline isn't statically cutout-safe (e.g., uses
     * {@code BlendFunction.TRANSLUCENT} without {@code NO_CARDINAL_LIGHTING}) but which
     * we emit anyway. Bytecode derivation in {@link #derivationAcceptsCompositeOverlay}
     * rejects them because it can't prove the translucent pipeline is cutout-safe; this
     * set re-allows the emit.
     * <p>Entries:
     * <ul>
     *   <li>{@code SLIME_OUTER} - the 8x8x8 translucent outer shell over the inner 6x6x6
     *       body (magma_cube shares the shape). Vanilla renders it via
     *       {@code RenderTypes.entityTranslucent} (constant 180/255 alpha multiplier). The
     *       runtime now renders it translucent - partial-alpha no-cull with a depth-write
     *       skip and back-to-front sort - so {@code minecraft:slime} / {@code minecraft:magma_cube}
     *       sit at ~0.065 / ~0.013 mean-delta parity with matched java/vanilla coverage, NOT the
     *       solid opaque cube an earlier build produced. The force-emit is still required only
     *       because the static bytecode gate can't tell the translucent pipeline is cutout-safe; a
     *       future per-overlay blend node (letting the shell declare its exact blend, so the shell
     *       drops out of this policy) would remove the need for it.</li>
     * </ul>
     */
    private static final @NotNull java.util.Set<String> POLICY_FORCE_EMIT = java.util.Set.of(
        "SLIME_OUTER"
    );

    /**
     * Composite-overlay layers that pass bytecode derivation (helper-based cutout) but
     * whose vanilla {@code submit()} has a state-gated early-return that bytecode walking can't
     * statically resolve, so at the zero/default render state vanilla draws nothing. Without a
     * state-default-aware detector, listing the no-emit cases here is more maintainable than
     * encoding the discriminator in bytecode walking.
     * <p>Entries:
     * <ul>
     *   <li>{@code CAT_COLLAR} - the {@code coloredCutoutModelCopyLayerRender}-based
     *       collar layer skips render when {@code state.collarColor == null} (the
     *       zero-state default). Visual impact at zero state would be a colored collar around
     *       every cat where vanilla shows none.</li>
     * </ul>
     *
     * <p>{@code SHEEP_WOOL_UNDERCOAT} used to live here (a default white sheep draws no undercoat),
     * but is now emitted with {@code requires_tint} (see {@link #detectRequiresTint}): the overlay
     * skips render at load unless a wool colour is selected, so a white sheep stays byte-identical
     * while a dyed sheep gets the tinted undercoat vanilla draws.
     */
    private static final @NotNull java.util.Set<String> POLICY_SUPPRESS = java.util.Set.of(
        "CAT_COLLAR"
    );

    /** JVM internal name of the {@code BlendFunction} enum carrying {@code TRANSLUCENT} etc. */
    private static final @NotNull String BLEND_FUNCTION = "com/mojang/blaze3d/pipeline/BlendFunction";

    /** Static helper on {@code RenderLayer} that renders a cutout model copy. */
    private static final @NotNull String COLORED_CUTOUT_HELPER = "coloredCutoutModelCopyLayerRender";

    /** Field-type descriptor for a {@code ModelLayerLocation}; used to spot parameterized-layer ctor args. */
    private static final @NotNull String MODEL_LAYER_LOCATION_DESC = "L" + VanillaSourceClasses.MODEL_LAYER_LOCATION + ";";

    /** Field-type descriptor for an {@code Identifier}; used to filter overlay-texture field references. */
    private static final @NotNull String IDENTIFIER_DESC = "L" + VanillaSourceClasses.IDENTIFIER + ";";

    /**
     * One overlay descriptor extracted from a layer class. The runtime emission step in
     * {@link ToolingEntityModels} maps this onto an
     * {@code overlays} entry in {@code entity_models.json}.
     *
     * @param layerClass JVM internal name of the source layer subclass (diagnostic provenance)
     * @param texturePath the raw texture path ({@code textures/entity/X/Y_eyes.png}) - callers
     *     strip the {@code textures/}+{@code entity/}+{@code .png} prefix/suffix to match the
     *     bundled-texture-ref convention before writing JSON
     * @param emissive {@code true} when the layer's render type is one of the emissive
     *     additive-blend variants ({@code RenderTypes.eyes} or {@code RenderTypes.breezeEyes});
     *     translates into the runtime {@code OverlayLayer.emissive} flag
     * @param modelLayerField the {@code ModelLayers.X} field name when the overlay's geometry comes
     *     from a separate {@code LayerDefinition} factory (e.g. {@code "SLIME_OUTER"},
     *     {@code "SHEEP_WOOL"}, {@code "SHEEP_WOOL_UNDERCOAT"}). {@code null} for eye overlays
     *     whose UVs reuse the base entity's geometry. {@link ToolingEntityModels} resolves this
     *     against {@link EntityLayerDefinitionResolver}'s layer-definition map to get an actual
     *     factory target, parses it as an extra geometry, and assigns a deduped geometry id
     * @param tintArgb the multiplicative ARGB tint vanilla applies to this overlay's sampled
     *     texels, extracted from the layer's submit-method bytecode by tracing the
     *     {@code color} argument to its source ({@code state.getXxxColor()} ->
     *     {@code ColorLerper.Type.X.getColor(defaultDye)}). {@code 0xFFFFFFFF} when the layer
     *     either doesn't tint (eye overlays use {@code RenderType.eyes} which ignores vertex
     *     color) or when extraction couldn't statically resolve a literal (e.g., the color
     *     comes from a runtime calculation we can't pre-compute)
     * @param inflate the per-cube {@code CubeDeformation} outward inflate baked into the
     *     overlay's geometry when vanilla renders it against a separately-deformed
     *     {@code LayerDefinition} (e.g. {@code 0.5} for {@code LlamaDecorLayer}, {@code 0.008}
     *     for {@code TropicalFishPatternLayer}'s {@code FISH_PATTERN_DEFORMATION}). {@code 0}
     *     when the overlay carries no extra deformation; {@link EntityRuntimeJsonWriter} then
     *     substitutes the {@code 0.001} same-geometry depth-fail clearance for base-geometry
     *     overlays
     * @param skipBounds {@code true} to exclude this overlay from the entity's projected
     *     bounds walk, matching the vanilla harness's {@code NO_RENDER_LAYER_SUFFIXES} skip list
     *     (e.g. {@code LlamaDecorLayer}); {@code false} for overlays that contribute to the
     *     silhouette. Emitted as the {@code skip_bounds} JSON property by
     *     {@link EntityRuntimeJsonWriter}
     * @param tintBy the render-axis token whose selected colour overrides this overlay's baked
     *     {@code tintArgb} at render (e.g. {@code "wool_color"} for the sheep wool, whose colour
     *     is a user-selectable dye). {@code null} when the tint is fixed. Emitted as the
     *     {@code tint_by} JSON property by {@link EntityRuntimeJsonWriter}
     * @param shearable {@code true} when the layer's render is gated off by the entity's
     *     {@code isSheared} state (the sheep wool), so the {@code sheared} render axis drops this
     *     overlay. Emitted as the {@code shearable} JSON property by {@link EntityRuntimeJsonWriter}
     * @param requiresTint {@code true} when the layer only renders once a {@link #tintBy} colour is
     *     selected (the sheep wool undercoat, gated on {@code isJebSheep || woolColor != WHITE}), so
     *     the overlay is skipped at render for the default (untinted) entity. Emitted as the
     *     {@code requires_tint} JSON property by {@link EntityRuntimeJsonWriter}
     * @param textureBy the render-axis token whose selection overrides this overlay's baked
     *     {@code texturePath} at render (e.g. {@code "pattern"} for the tropical-fish pattern, whose
     *     texture is a user-selectable {@code TropicalFishPattern}). {@code null} when the texture is
     *     fixed. Emitted as the {@code texture_by} JSON property by {@link EntityRuntimeJsonWriter}
     * @param requiresCharged {@code true} when the layer only renders for a charged (lightning-struck)
     *     entity (the creeper energy swirl), so the {@code charged} render axis gates it in and the
     *     default (uncharged) entity drops it. Emitted as the {@code requires_charged} JSON property by
     *     {@link EntityRuntimeJsonWriter}
     */
    public record Result(
        @NotNull String layerClass,
        @NotNull String texturePath,
        boolean emissive,
        @Nullable String modelLayerField,
        int tintArgb,
        float inflate,
        boolean skipBounds,
        @Nullable String tintBy,
        boolean shearable,
        boolean requiresTint,
        @Nullable String textureBy,
        boolean requiresCharged
    ) {
        /**
         * Constructs a {@code Result} with no extra deformation and no bounds skip - the common
         * case for eye and same-geometry composite overlays (defaults {@code inflate} to
         * {@code 0}, {@code skipBounds} to {@code false}, and {@code tintBy} to {@code null}).
         *
         * @param layerClass JVM internal name of the source layer subclass
         * @param texturePath the raw texture path
         * @param emissive whether the render type is an emissive additive-blend variant
         * @param modelLayerField the {@code ModelLayers.X} field name for separate-geometry
         *     overlays, or {@code null} for same-geometry overlays
         * @param tintArgb the multiplicative ARGB tint, or {@code 0xFFFFFFFF} for no tint
         */
        public Result(
            @NotNull String layerClass,
            @NotNull String texturePath,
            boolean emissive,
            @Nullable String modelLayerField,
            int tintArgb
        ) {
            this(layerClass, texturePath, emissive, modelLayerField, tintArgb, 0f, false, null, false, false, null, false);
        }

        /**
         * Constructs a {@code Result} carrying explicit deformation / bounds-skip but a fixed tint
         * (defaults {@code tintBy} to {@code null}) - the {@code LlamaDecorLayer} /
         * {@code TropicalFishPatternLayer} shape.
         *
         * @param layerClass JVM internal name of the source layer subclass
         * @param texturePath the raw texture path
         * @param emissive whether the render type is an emissive additive-blend variant
         * @param modelLayerField the {@code ModelLayers.X} field name, or {@code null}
         * @param tintArgb the multiplicative ARGB tint, or {@code 0xFFFFFFFF} for no tint
         * @param inflate the per-cube outward inflate baked into the overlay geometry
         * @param skipBounds whether to exclude this overlay from the projected bounds walk
         */
        public Result(
            @NotNull String layerClass,
            @NotNull String texturePath,
            boolean emissive,
            @Nullable String modelLayerField,
            int tintArgb,
            float inflate,
            boolean skipBounds
        ) {
            this(layerClass, texturePath, emissive, modelLayerField, tintArgb, inflate, skipBounds, null, false, false, null, false);
        }

        /**
         * Synthetic entity-id key used as a parser source for composite overlay layers
         * (slime outer shell, sheep wool, etc.). Keeps overlay geometries distinguishable
         * from real entity primaries while still flowing through the shared dedupe machinery
         * in {@link EntityRuntimeJsonWriter}.
         *
         * @param modelLayerField the {@code ModelLayers.X} field name of the overlay's body
         *     layer (the {@code modelLayerField} of a composite-overlay {@link Result})
         * @return the synthetic entity id, e.g. {@code "__overlay_SLIME_OUTER"}
         */
        public static @NotNull String entityKey(@NotNull String modelLayerField) {
            return "__overlay_" + modelLayerField;
        }
    }

    /**
     * Resolves the dyed-collar texture for an entity whose layer set includes a
     * {@code *CollarLayer} ({@code WolfCollarLayer}, {@code CatCollarLayer}). The collar is a
     * base-body cutout copy tinted at runtime by the wearer's dye colour, so only the texture is
     * needed here - the geometry is the entity's own body model and the tint is a render option.
     * Returned prefix-stripped ({@code "wolf/wolf_collar"}); {@code null} when the entity has no
     * collar layer or its texture cannot be extracted.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param layerClasses ordered list of layer-class internal names from
     *     {@link EntityBoneResolver#scanOverlayLayers}
     * @return the prefix-stripped collar texture path, or {@code null} when absent
     */
    public static @Nullable String resolveCollarTexture(
        @NotNull ClassNodeCache classNodes,
        @NotNull ConcurrentList<String> layerClasses
    ) {
        for (String layerClass : layerClasses) {
            if (!layerClass.endsWith("CollarLayer")) continue;
            ClassNode cn = classNodes.load(layerClass);
            if (cn == null) continue;
            String texture = findCompositeOverlayTexture(classNodes, cn);
            if (texture != null) return EntityTextureResolver.stripPrefix(texture);
        }
        return null;
    }

    /**
     * Resolves overlay descriptors from the layer class names produced by
     * {@link EntityBoneResolver#scanOverlayLayers}. Layer classes that don't match any known
     * overlay shape are silently dropped - they're either runtime-driven (armor / equipment /
     * item-in-hand) or deferred to a later increment.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer's JVM internal name
     * @param layerClasses ordered list of layer-class internal names from
     *     {@link EntityBoneResolver#scanOverlayLayers}
     * @param entityId the entity-id this layer set belongs to (diagnostic context only)
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return overlay descriptors in source order; empty when no recognised overlay was found
     */
    public static @NotNull ConcurrentList<Result> resolve(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName,
        @NotNull ConcurrentList<String> layerClasses,
        @NotNull String entityId,
        @NotNull Diagnostics diagnostics
    ) {
        ConcurrentList<Result> out = Concurrent.newList();
        for (String layerClass : layerClasses) {
            ClassNode cn = classNodes.load(layerClass);
            if (cn == null) continue;
            // Eye overlay first - shares the base entity's geometry, so no extra parse. The
            // factory-name discriminator marks fully-emissive variants (`RenderTypes.eyes` →
            // EMISSIVE + NO_CARDINAL_LIGHTING) as `emissive=true` so the rasterizer skips
            // shading; shaded variants (`RenderTypes.breezeEyes` → ENTITY_TRANSLUCENT_EMISSIVE
            // with PER_FACE_LIGHTING, no EMISSIVE / NO_CARDINAL_LIGHTING) get the standard
            // Lambertian shade so the eye darkens with the head's face normal exactly like the
            // skin texel below it.
            EyesOverlayBinding eyes = findEyesOverlayBinding(cn);
            if (eyes != null) {
                boolean fullyEmissive = factoryHasNoCardinalLighting(classNodes, eyes.factoryName());
                out.add(new Result(layerClass, eyes.texturePath(), fullyEmissive, null, 0xFFFFFFFF));
                continue;
            }

            // CreeperPowerLayer: the charged (lightning-struck) creeper's blue energy swirl, an
            // EnergySwirlLayer whose model is CreeperModel(bakeLayer(ModelLayers.CREEPER_ARMOR)) - the
            // base creeper createBodyLayer inflated CubeDeformation(2.0). Reuse the base geometry
            // (modelLayerField=null) + inflate 2.0 rather than walking the CREEPER_ARMOR LayerDefinition;
            // the swirl texture is the layer's getTextureLocation() LDC (creeper/creeper_armor). It is a
            // full-bright translucent glow (emissive=true; the indexed texture's tRNS drops the
            // background so only the electric grid shows), gated on the charged render axis via
            // requires_charged so the default (uncharged) creeper stays byte-identical. The swirl's
            // animated UV scroll reduces to frame 0 for a static icon. The generic composite gate rejects
            // EnergySwirlLayer (its render type lives in inherited code), so this dedicated handler is
            // the emit path.
            if (VanillaSourceClasses.CREEPER_POWER_LAYER.equals(layerClass)) {
                String swirlTexture = findEnergySwirlTexture(cn);
                if (swirlTexture != null)
                    out.add(new Result(layerClass, swirlTexture, true, null, 0xFFFFFFFF,
                        2.0f, false, null, false, false, null, true));
                continue;
            }

            // Specific class-name + ctor-shape handlers run BEFORE the generic
            // composite-overlay gate. The generic gate's bytecode derivation
            // ({@link #derivationAcceptsCompositeOverlay}) would also accept these layers
            // (they use the cutout helper), but they need per-class metadata (inflate, tint,
            // skipBounds, texture-prefix) that the generic emit path doesn't carry. By
            // running the specific handlers first, each one claims its class via {@code
            // continue} and the generic gate never sees it - no hardcoded exclusion list
            // needed in the gate itself.

            // LlamaDecorLayer: a same-geometry equipment-overlay layer rendering the llama's
            // body-slot armor / decor (carpet for trader_llama, dyed harnesses for player-saddled
            // llamas). The renderer class is shared by llama AND trader_llama (LlamaRenderer is
            // instantiated twice with different ModelLayers args), so this code path only fires
            // for the trader_llama entity-id - that's the only vanilla case with a hardcoded
            // default-equipment ResourceKey. Deriving the per-entity equipment default from
            // entity-class bytecode (TraderLlama.<init> -> ItemStack with TRADER_LLAMA asset) is
            // deferred for now; using the entity-id check here is defensible because
            // entity_id itself is bytecode-derived (EntityType registry walk).
            //
            // Texture composition: walk LlamaDecorLayer for the EquipmentClientInfo$LayerType
            // GETSTATIC ({@code LLAMA_BODY} -> "llama_body" subdir) and EquipmentAssets.<clinit>
            // for the TRADER_LLAMA static field's preceding LDC ("trader_llama"). Final path:
            // {@code textures/entity/equipment/<layer_subdir>/<asset_id>.png}.
            //
            // Inflate 0.5 mirrors {@code LlamaModel.createBodyLayer(CubeDeformation(0.5F))} which
            // the LayerDefinitions.<clinit> wires for {@code ModelLayers.LLAMA_DECOR}. The
            // bytecode walk for this constant is deferred (the LayerDefinitions <clinit> stack
            // would require constant-folding the local-variable slot the LayerDefinition is
            // stored in); 0.5 is hardcoded for now with the matching skip_bounds=true that the
            // vanilla harness's NO_RENDER_LAYER_SUFFIXES treats LlamaDecorLayer with.
            if (VanillaSourceClasses.LLAMA_DECOR_LAYER.equals(layerClass)
                && "minecraft:trader_llama".equals(entityId)) {
                String layerSubdir = findEquipmentLayerSubdir(cn);
                String assetId = findEquipmentAssetId(classNodes, "TRADER_LLAMA");
                if (layerSubdir != null && assetId != null) {
                    String texture = TEXTURE_PATH_PREFIX + "equipment/" + layerSubdir + "/" + assetId + ".png";
                    out.add(new Result(layerClass, texture, false, null, 0xFFFFFFFF, 0.5f, true));
                }
                continue;
            }

            // TropicalFishPatternLayer: a second textured pass drawn on top of the small / large
            // tropical fish body model. The pattern model is a separate LayerDefinition baked
            // with FISH_PATTERN_DEFORMATION = CubeDeformation(0.008F) - that's an inflate on
            // every pattern cube, not just a depth-test offset, so the pattern silhouette is
            // geometrically 0.008 wider than the base body per side. Both the body tint
            // (state.baseColor) and the pattern tint (state.patternColor) default to
            // DyeColor.WHITE.textureDiffuseColor = 0xFFF9FFFE at zero state. Emit as a
            // same-geometry overlay (the base geometry + 0.008 inflate is a static-renderer
            // approximation of vanilla's separate pattern LayerDefinition).
            if (VanillaSourceClasses.TROPICAL_FISH_PATTERN_LAYER.equals(layerClass)) {
                String patternTexture = findFirstNonBabyTextureLiteral(cn);
                if (patternTexture != null) {
                    float inflate = walkCubeDeformationFloat(classNodes,
                        VanillaSourceClasses.LAYER_DEFINITIONS, "FISH_PATTERN_DEFORMATION");
                    int tint = walkDyeColorWhiteTextureDiffuseColor(classNodes);
                    // tint_by: pattern_color lets the pattern_color render axis override the baked
                    // white-diffuse default tint at render (EntityAppearance's TintAxis.PATTERN),
                    // mirroring vanilla's state.patternColor. texture_by: pattern lets the pattern
                    // render axis swap the pattern overlay texture (TropicalFishPattern). Both
                    // default to the baked value (pattern_1 / white tint), so the unselected render
                    // is byte-identical.
                    out.add(new Result(layerClass, patternTexture, false, null, tint,
                        inflate != 0f ? inflate : 0.008f, false, "pattern_color", false, false, "pattern", false));
                }
                continue;
            }

            // VillagerProfessionLayer (used by VillagerRenderer + ZombieVillagerRenderer): the
            // layer renders an additional textured pass on top of the base villager geometry,
            // dispatched per-state to type/&lt;biome&gt;.png + optional profession + profession_level
            // PNGs. At zero state (PLAINS biome, NONE profession, level 1) only the
            // type/plains.png pass actually fires. The texture prefix ("villager" /
            // "zombie_villager") is the third constructor arg at the renderer's
            // `addLayer(new VillagerProfessionLayer(this, resourceManager, "<prefix>", ...))`
            // call site. Emit as a same-geometry overlay so the runtime gets the auto-applied
            // inflate=0.001 (equal-Z depth-fail clearance).
            if (VanillaSourceClasses.VILLAGER_PROFESSION_LAYER.equals(layerClass)) {
                String prefix = extractVillagerProfessionPrefix(classNodes, rendererInternalName);
                if (prefix != null) {
                    String texture = TEXTURE_PATH_PREFIX + prefix + "/type/plains.png";
                    out.add(new Result(layerClass, texture, false, null, 0xFFFFFFFF));
                }
                continue;
            }

            // Parameterized composite-model overlay (SkeletonClothingLayer family: stray, bogged).
            // Shape: layer constructor takes ({@code ModelLayerLocation layerLocation},
            // {@code Identifier clothesLocation}) parameters, baker calls {@code
            // bakeLayer(<param>)} on the parameter (not on a GETSTATIC ModelLayers field), and
            // submit calls {@code coloredCutoutModelCopyLayerRender(this.layerModel,
            // this.clothesLocation, ...)}. The actual ModelLayers field and texture come from
            // the renderer's {@code addLayer(new XLayer(this, modelSet, ModelLayers.Y,
            // Z_LOCATION))} call site rather than the layer class itself - matching by ctor
            // shape lets any future layer using the same pattern auto-resolve without an
            // allowlist update. Emitted unconditionally when both args resolve because the
            // {@code coloredCutoutModelCopyLayerRender} call site enforces the
            // {@code entityCutout} render type (no translucent / additive variant uses this
            // helper).
            ParameterizedOverlayBinding param = findParameterizedOverlayBinding(classNodes, cn, rendererInternalName);
            if (param != null) {
                int tintArgb = extractColoredCutoutTint(classNodes, cn);
                out.add(new Result(layerClass, param.texturePath(), false, param.modelLayerField(), tintArgb));
                continue;
            }

            // Composite-model overlay (sheep wool, sheep wool undercoat, drowned outer,
            // breeze wind, slime outer) - generic catch-all for any layer whose {@code <init>}
            // calls {@code modelSet.bakeLayer(ModelLayers.X)} and that wasn't claimed by a
            // more specific handler above. Emit when:
            //   - {@link #POLICY_FORCE_EMIT} explicitly allows it (translucent overlays
            //     accepted as known-divergence), OR
            //   - {@link #derivationAcceptsCompositeOverlay} accepts it (layer establishes
            //     its own render type via direct {@code RenderTypes.X} INVOKESTATIC or the
            //     {@code coloredCutoutModelCopyLayerRender} helper, AND every direct
            //     render-type call resolves to a cutout-safe pipeline).
            // Skip when {@link #POLICY_SUPPRESS} excludes it (state-gated runtime skips
            // that bytecode walking can't statically resolve).
            String modelLayerField = findOverlayModelLayerField(cn);
            if (modelLayerField != null
                && !POLICY_SUPPRESS.contains(modelLayerField)
                && (POLICY_FORCE_EMIT.contains(modelLayerField)
                    || derivationAcceptsCompositeOverlay(classNodes, cn))) {
                String compositeTexture = findCompositeOverlayTexture(classNodes, cn);
                if (compositeTexture == null) {
                    diagnostics.info("entity '%s' overlay '%s' bakes ModelLayers.%s but no texture path resolved", entityId, layerClass, modelLayerField);
                    continue;
                }
                int tintArgb = extractColoredCutoutTint(classNodes, cn);
                boolean unlit = layerInvokesNoCardinalLightingRenderType(classNodes, cn);
                String tintBy = extractTintBy(cn);
                boolean shearable = detectShearableGate(cn);
                boolean requiresTint = detectRequiresTint(cn);
                out.add(new Result(layerClass, compositeTexture, unlit, modelLayerField, tintArgb, 0f, false, tintBy, shearable, requiresTint, null, false));
                continue;
            }
        }

        // Inline same-geometry eye overlays not captured by any RenderLayer subclass.
        //   - EnderDragonRenderer style: a static RenderType field bound via
        //     {@code RenderTypes.eyes(LOCATION)} in {@code <clinit>}, dispatched directly from
        //     {@code submit()} (no {@code addLayer(new EyesLayer(...))}). The
        //     {@link #findEyesOverlayBinding} routine already used for layer classes works
        //     unchanged on the renderer's own {@code <clinit>}; the only difference is the
        //     <clinit> contains several earlier {@code LDC} texture literals, so we run the
        //     same {@code last-LDC + INVOKESTATIC *eyes*} pattern matcher.
        //   - CopperGolemRenderer style: {@code new LivingEntityEmissiveLayer(this,
        //     provider::eyeTextureLocationFor, ...)} with a same-{@code ModelLayers}
        //     state-driven texture provider. We pick the zero-state texture by walking the
        //     renderer + its transitively-INVOKESTATIC'd classes for the first {@code
        //     *_eyes.png} LDC, gated on the layer's {@code ModelLayers} arg matching the base
        //     renderer's (so warden/creaking - which use {@code WARDEN_BIOLUMINESCENT} /
        //     {@code CREAKING_EYES} - don't fire here; their distinct-geometry overlays are
        //     handled by the layer-class path).
        boolean hasEmissiveSameGeometryOverlay = false;
        for (Result d : out)
            if (d.modelLayerField() == null && d.emissive()) {
                hasEmissiveSameGeometryOverlay = true;
                break;
            }
        if (!hasEmissiveSameGeometryOverlay) {
            ClassNode rendererCn = classNodes.load(rendererInternalName);
            if (rendererCn != null) {
                EyesOverlayBinding direct = findEyesOverlayBinding(rendererCn);
                if (direct != null) {
                    out.add(new Result(rendererInternalName, direct.texturePath(), true, null, 0xFFFFFFFF));
                } else {
                    String emissiveTexture = findLivingEntityEmissiveTexture(classNodes, rendererCn);
                    if (emissiveTexture != null)
                        out.add(new Result(rendererInternalName, emissiveTexture, true, null, 0xFFFFFFFF));
                }
            }
        }
        return out;
    }

    /**
     * Returns the {@code ModelLayers.X} field name baked by the layer class's
     * {@code <init>(RenderLayerParent, EntityModelSet)} constructor, or {@code null} when the
     * layer doesn't bake its own model. Detection: walk the constructor for
     * {@code GETSTATIC ModelLayers.X} immediately preceding an
     * {@code INVOKEVIRTUAL EntityModelSet.bakeLayer(ModelLayerLocation) ModelPart} call. The
     * returned field name is what {@link EntityLayerDefinitionResolver#loadLayerDefinitions}
     * uses as its map key, so the caller can resolve it to a factory target.
     *
     * <p>Skips layers whose field name contains {@code "BABY"} - the baby-sized variants share
     * the same texture as the adult and only the adult layer needs an entry. (Resolver doesn't
     * emit baby-distinct entities; if that changes, the BABY filter would move into the caller.)
     */
    private static @Nullable String findOverlayModelLayerField(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            String pendingField = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.MODEL_LAYERS.equals(fi.owner)) {
                    pendingField = fi.name;
                    continue;
                }
                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && in instanceof MethodInsnNode mi
                    && VanillaSourceClasses.ENTITY_MODEL_SET.equals(mi.owner)
                    && "bakeLayer".equals(mi.name)
                    && pendingField != null
                    && !pendingField.contains("BABY"))
                    return pendingField;
            }
        }
        return null;
    }

    /**
     * Resolves the texture path for a composite-model overlay. Tries two strategies in order:
     *
     * <ol>
     *   <li><b>Own-clinit LDC</b>: walks the layer's {@code <clinit>} for the first
     *       {@code LDC textures/...png; INVOKESTATIC withDefaultNamespace; PUTSTATIC FIELD}
     *       chain whose target field name doesn't contain {@code "BABY"}. Catches
     *       {@code SheepWoolLayer.SHEEP_WOOL_LOCATION},
     *       {@code SheepWoolUndercoatLayer.SHEEP_WOOL_UNDERCOAT_LOCATION},
     *       {@code BreezeWindLayer.TEXTURE_LOCATION},
     *       {@code DrownedOuterLayer.DROWNED_OUTER_LAYER_LOCATION}.</li>
     *   <li><b>Sibling-renderer GETSTATIC</b>: walks the layer's non-init non-clinit methods
     *       for the first {@code GETSTATIC OtherClass.X_LOCATION} of an {@code Identifier}
     *       field, then recursively resolves the path bound to that field by walking the
     *       owner's own {@code <clinit>}. Catches {@code SlimeOuterLayer}, which renders
     *       against {@code SlimeRenderer.SLIME_LOCATION} (no own LDC).</li>
     * </ol>
     *
     * <p>Returns {@code null} when neither strategy yields a texture - the caller logs a
     * diagnostic and drops the overlay. An earlier iteration had a strategy-3 fallback
     * (the parent renderer's own {@code <clinit>}); it resolved equipment / charged-armor
     * layers (WingsLayer, RopesLayer) to the parent renderer's main entity texture,
     * producing visually wrong overlays (elytra geometry textured with zombie skin). Dropped
     * because {@link #derivationAcceptsCompositeOverlay} already filters those layers out
     * by requiring the layer to establish its own render type.
     */
    private static @Nullable String findCompositeOverlayTexture(
        @NotNull ClassNodeCache classNodes,
        @NotNull ClassNode layerClass
    ) {
        String own = findFirstNonBabyTextureLiteral(layerClass);
        if (own != null) return own;
        for (MethodNode method : layerClass.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC) continue;
                if (!(in instanceof FieldInsnNode fi)) continue;
                if (!IDENTIFIER_DESC.equals(fi.desc)) continue;
                String chased = chaseTextureFieldOwner(classNodes, fi.owner, fi.name);
                if (chased != null) return chased;
            }
        }
        return null;
    }

    /**
     * Walks {@code cn.<clinit>} for the first {@code LDC "textures/...png"} that flows through a
     * {@code withDefaultNamespace} call into a {@code PUTSTATIC} of a non-{@code BABY} field.
     * Returns the LDC's literal value, or {@code null} when no such pattern is present.
     */
    /**
     * Scans an {@code EnergySwirlLayer} subclass (e.g. {@code CreeperPowerLayer}) for its swirl
     * texture literal - the {@code textures/entity/X.png} LDC returned by the layer's
     * {@code getTextureLocation()} override. Unlike {@link #findFirstNonBabyTextureLiteral} (which
     * walks the {@code <clinit>} for a static {@code Identifier} field), the swirl texture is built
     * inline in an instance method, so this walks every method for the first entity-texture LDC.
     *
     * @param cn the layer subclass ClassNode
     * @return the raw {@code textures/entity/...png} path, or {@code null} when none is present
     */
    private static @Nullable String findEnergySwirlTexture(@NotNull ClassNode cn) {
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                String literal = AsmKit.readStringLiteral(in);
                if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png"))
                    return literal;
            }
        }
        return null;
    }

    private static @Nullable String findFirstNonBabyTextureLiteral(@NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.IDENTIFIER.equals(mi.owner)
                && "withDefaultNamespace".equals(mi.name)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && IDENTIFIER_DESC.equals(fi.desc)
                && pendingPath != null
                && pendingIdentifier) {
                if (!fi.name.contains("BABY")) return pendingPath;
                pendingPath = null;
                pendingIdentifier = false;
            }
        }
        return null;
    }

    /**
     * Resolves an {@code Identifier} static field on {@code owner} by walking its {@code <clinit>}
     * for the {@code LDC + withDefaultNamespace + PUTSTATIC <fieldName>} chain. Returns the LDC
     * literal bound to {@code fieldName}, or {@code null} when the owner's clinit doesn't bind
     * the field with the standard pattern.
     *
     * <p>Used by the sibling-renderer chase: {@code SlimeOuterLayer} renders against
     * {@code SlimeRenderer.SLIME_LOCATION}, so this walks {@code SlimeRenderer.<clinit>} and
     * picks up {@code "textures/entity/slime/slime.png"}.
     */
    private static @Nullable String chaseTextureFieldOwner(
        @NotNull ClassNodeCache classNodes,
        @NotNull String ownerInternalName,
        @NotNull String fieldName
    ) {
        ClassNode owner = classNodes.load(ownerInternalName);
        if (owner == null) return null;
        MethodNode clinit = AsmKit.findMethod(owner, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingPath = null;
        boolean pendingIdentifier = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingPath = literal;
                pendingIdentifier = false;
                continue;
            }
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && VanillaSourceClasses.IDENTIFIER.equals(mi.owner)
                && "withDefaultNamespace".equals(mi.name)) {
                pendingIdentifier = true;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && fi.name.equals(fieldName)
                && pendingPath != null
                && pendingIdentifier)
                return pendingPath;
        }
        return null;
    }

    /**
     * JVM internal name of {@code DyeColor} - the per-dye color enum whose {@code WHITE} constant tints to {@code 0xFFE6E6E6} under {@code ColorLerper}.
     */
    private static final @NotNull String DYE_COLOR = "net/minecraft/world/item/DyeColor";

    /**
     * The literal {@code ColorLerper.getModifiedColor(DyeColor.WHITE, brightness)} returns for the
     * {@code WHITE} branch - {@code -1644826} = {@code 0xFFE6E6E6} = {@code (230, 230, 230)} RGB.
     * The brightness parameter is ignored when the input color is {@code DyeColor.WHITE}; vanilla's
     * source returns this constant unconditionally for the WHITE branch.
     */
    private static final int COLOR_LERPER_WHITE_MODIFIED = -1644826;

    /**
     * Walks the composite-overlay layer's render-side methods to find a call to
     * {@code RenderLayer.coloredCutoutModelCopyLayerRender(... , int color, int packedOverlay)} and
     * statically resolves the {@code color} argument to a literal ARGB. Returns {@code 0xFFFFFFFF}
     * (no tint) when the layer doesn't call the helper, or when the color argument can't be
     * statically resolved (runtime-dependent expression).
     *
     * <p>Recognised chain - matches both {@code SheepWoolLayer} and {@code SheepWoolUndercoatLayer}:
     * <ol>
     *   <li>Layer's {@code submit} method contains {@code INVOKESTATIC coloredCutoutModelCopyLayerRender}
     *       with the {@code color} arg coming from {@code INVOKEVIRTUAL <stateClass>.get<X>Color()I};</li>
     *   <li>Recursing into that state method finds {@code INVOKEVIRTUAL ColorLerper$Type.getColor(DyeColor)I}
     *       whose receiver is one of the {@code Type} enum constants and whose dye-color argument is a
     *       {@code GETFIELD} on a state field;</li>
     *   <li>Walking the state class's {@code <init>} for that field finds its default
     *       {@code GETSTATIC DyeColor.<NAME> + PUTFIELD <field>} initialiser; if the default is
     *       {@code DyeColor.WHITE} we return {@link #COLOR_LERPER_WHITE_MODIFIED}
     *       ({@code 0xFFE6E6E6}) - the hardcoded vanilla return for WHITE under any
     *       {@code ColorLerper.Type} (the {@code brightness} parameter is ignored for WHITE).</li>
     * </ol>
     * Non-WHITE defaults aren't pre-computed: {@code ColorLerper.getModifiedColor} for non-WHITE
     * runs an srgb-to-linear multiplied by {@code Type}'s {@code brightness}, which we don't
     * static-eval here; the caller falls back to {@code 0xFFFFFFFF} so the runtime tints with
     * the un-modified texel (slight over-bright but better than failing the regen).
     */
    private static int extractColoredCutoutTint(@NotNull ClassNodeCache classNodes, @NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (!(in instanceof MethodInsnNode mi)) continue;
                if (!"coloredCutoutModelCopyLayerRender".equals(mi.name)) continue;
                // Color arg is second-to-last positional (just before packedOverlay int).
                // Walk back from the call to find the INVOKEVIRTUAL on a state class that supplied it.
                AbstractInsnNode stateColorCall = findPrecedingStateColorCall(in);
                if (stateColorCall == null) continue;
                int resolved = resolveStateColorMethod(classNodes, (MethodInsnNode) stateColorCall);
                if (resolved != 0xFFFFFFFF) return resolved;
            }
        }
        return 0xFFFFFFFF;
    }

    /**
     * The recognised {@code state.get<X>Color()} tint sources that map to a user-selectable render
     * axis, keyed by the state method name. An overlay whose {@code coloredCutoutModelCopyLayerRender}
     * colour comes from one of these emits the mapped {@code tint_by} token, so a render option can
     * override the baked default tint (the sheep wool's default {@code 0xFFE6E6E6} white). Sources
     * absent from this map keep only their fixed {@code tint_color}.
     */
    private static final @NotNull java.util.Map<String, String> TINT_SOURCE_TO_AXIS =
        java.util.Map.of("getWoolColor", "wool_color");

    /**
     * Extracts the {@code tint_by} render-axis token for a dyed composite overlay: the
     * {@code state.get<X>Color()} method feeding the layer's {@code coloredCutoutModelCopyLayerRender}
     * colour argument, mapped through {@link #TINT_SOURCE_TO_AXIS}. Mirrors
     * {@link #extractColoredCutoutTint}'s walk but returns the axis token instead of the baked
     * literal. Returns {@code null} when the layer does not use the cutout helper or its colour
     * source is not a recognised user-selectable dye axis.
     */
    private static @Nullable String extractTintBy(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (!(in instanceof MethodInsnNode mi)) continue;
                if (!"coloredCutoutModelCopyLayerRender".equals(mi.name)) continue;
                if (findPrecedingStateColorCall(in) instanceof MethodInsnNode colorCall) {
                    String axis = TINT_SOURCE_TO_AXIS.get(colorCall.name);
                    if (axis != null) return axis;
                }
            }
        }
        return null;
    }

    /**
     * Detects the {@code if (state.isSheared) return;} early-return gate at the top of a wool
     * layer's {@code submit} - the sheep wool renders nothing once the entity is sheared. Matched
     * shape, reading forwards: {@code GETFIELD <StateClass>.isSheared:Z; IFEQ <past>; RETURN}. When
     * present the overlay is {@code shearable}, so the {@code sheared} render axis drops it. Keys on
     * the {@code isSheared} field name + the branch-then-void-return shape so an unrelated boolean
     * read cannot match.
     */
    private static boolean detectShearableGate(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETFIELD) continue;
                if (!(in instanceof FieldInsnNode get)) continue;
                if (!"isSheared".equals(get.name) || !"Z".equals(get.desc)) continue;
                AbstractInsnNode branch = AsmKit.nextReal(in);
                if (branch == null || branch.getOpcode() != Opcodes.IFEQ) continue;
                AbstractInsnNode body = AsmKit.nextReal(branch);
                if (body != null && body.getOpcode() == Opcodes.RETURN) return true;
            }
        }
        return false;
    }

    /**
     * Detects the sheep wool undercoat's dye-conditional gate - the layer draws nothing unless a
     * non-default wool colour (or jeb) is selected ({@code if (!(isJebSheep || woolColor != WHITE))
     * return;}). Keyed on a {@code GETFIELD <state>.isJebSheep:Z} read, which is unique to the
     * undercoat among sheep layers (the main wool tints on {@code getWoolColor()} but always renders).
     * When present the overlay carries {@code requires_tint} and is skipped at render for the default
     * (untinted) entity, so a white sheep stays byte-identical.
     */
    private static boolean detectRequiresTint(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETFIELD) continue;
                if (!(in instanceof FieldInsnNode get)) continue;
                if ("isJebSheep".equals(get.name) && "Z".equals(get.desc)) return true;
            }
        }
        return false;
    }

    /**
     * Walks backwards from a {@code coloredCutoutModelCopyLayerRender} call site to find the
     * {@code INVOKEVIRTUAL} that supplied the {@code color} argument. Returns the matching
     * instruction or {@code null} when no state-method invocation precedes the call.
     *
     * <p>Resilient to the {@code isBaby ? 1 : 0} ternary that wraps {@code packedOverlay}: the
     * loop skips over branch / int-literal nodes until it finds the first parameterless
     * {@code INVOKEVIRTUAL} returning {@code int} (descriptor exactly {@code ()I}). That's the
     * color expression; the parameterless filter excludes getter-style calls on non-state
     * classes (e.g. {@code LivingEntityRenderer.getOverlayCoords}).
     */
    private static @Nullable AbstractInsnNode findPrecedingStateColorCall(@NotNull AbstractInsnNode call) {
        for (AbstractInsnNode prev = call.getPrevious(); prev != null; prev = prev.getPrevious()) {
            if (prev.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(prev instanceof MethodInsnNode mi)) continue;
            if (!mi.desc.endsWith(")I")) continue;
            // Skip getter-style methods on non-state classes (e.g., LivingEntityRenderer.getOverlayCoords);
            // matching by descriptor-shape `()I` keeps us on parameterless int-returning state methods.
            if (!"()I".equals(mi.desc)) continue;
            return prev;
        }
        return null;
    }

    /**
     * Recursively resolves the integer returned by a state class's parameterless int-method.
     * Looks for the {@code ColorLerper.Type.X.getColor(DyeColor)} chain and statically evaluates
     * the result for the field's default dye color. Returns {@code 0xFFFFFFFF} when the method
     * doesn't match the recognised pattern.
     */
    private static int resolveStateColorMethod(@NotNull ClassNodeCache classNodes, @NotNull MethodInsnNode stateColorCall) {
        ClassNode stateClass = classNodes.load(stateColorCall.owner);
        if (stateClass == null) return 0xFFFFFFFF;
        MethodNode stateMethod = AsmKit.findMethod(stateClass, stateColorCall.name, stateColorCall.desc);
        if (stateMethod == null) return 0xFFFFFFFF;
        for (AbstractInsnNode in = stateMethod.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!VanillaSourceClasses.COLOR_LERPER_TYPE.equals(mi.owner)) continue;
            if (!"getColor".equals(mi.name)) continue;
            // The dye-color argument is the immediately preceding GETFIELD on this.<field>.
            // Walk back from the getColor() call until we find the GETFIELD whose desc points at DyeColor.
            String dyeFieldName = findPrecedingDyeFieldRead(in);
            if (dyeFieldName == null) continue;
            String defaultDye = findFieldDefaultDyeColor(stateClass, dyeFieldName);
            if ("WHITE".equals(defaultDye)) return COLOR_LERPER_WHITE_MODIFIED;
        }
        return 0xFFFFFFFF;
    }

    /**
     * Walks backwards from a {@code ColorLerper.Type.getColor} call to find the
     * {@code GETFIELD this.<X>:DyeColor} that supplied the dye argument. Returns the field name or
     * {@code null} when no matching read precedes the call.
     */
    private static @Nullable String findPrecedingDyeFieldRead(@NotNull AbstractInsnNode call) {
        String dyeDesc = "L" + DYE_COLOR + ";";
        for (AbstractInsnNode prev = call.getPrevious(); prev != null; prev = prev.getPrevious()) {
            if (prev.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(prev instanceof FieldInsnNode fi)) continue;
            if (!dyeDesc.equals(fi.desc)) continue;
            return fi.name;
        }
        return null;
    }

    /**
     * Walks the class's {@code <init>} for a {@code GETSTATIC DyeColor.<NAME> + PUTFIELD <field>}
     * pair, returning the {@code DyeColor} enum constant name bound to {@code field} as the
     * declared default initialiser. Returns {@code null} when no such pair is found (no default
     * initialiser, or default comes from a non-literal expression).
     */
    private static @Nullable String findFieldDefaultDyeColor(@NotNull ClassNode stateClass, @NotNull String fieldName) {
        MethodNode init = AsmKit.findMethod(stateClass, AsmKit.INIT);
        if (init == null) return null;
        String pendingDye = null;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && DYE_COLOR.equals(fi.owner)) {
                pendingDye = fi.name;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTFIELD
                && in instanceof FieldInsnNode fi
                && fi.name.equals(fieldName)
                && pendingDye != null)
                return pendingDye;
        }
        return null;
    }

    /**
     * Eye overlay descriptor returned by {@link #findEyesOverlayBinding}: pairs the texture
     * path with the {@code RenderTypes.X(...)} factory name that constructed the render type
     * so callers can discriminate between fully-emissive variants ({@code RenderTypes.eyes}
     * → {@code RenderPipelines.EYES} with {@code EMISSIVE} + {@code NO_CARDINAL_LIGHTING})
     * and shaded translucent variants ({@code RenderTypes.breezeEyes} →
     * {@code RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE} with {@code PER_FACE_LIGHTING}
     * - cardinal Lambertian still applies).
     */
    private record EyesOverlayBinding(@NotNull String texturePath, @NotNull String factoryName) {}

    /**
     * Memoized cache for {@link #factoryHasNoCardinalLighting} - keyed on
     * {@code RenderTypes.<factoryName>} factory method names. Populated lazily by
     * {@link #factoryHasNoCardinalLighting} so the multi-hop {@code RenderTypes -&gt;
     * RenderPipelines.<clinit>} walk runs once per factory per tooling pass.
     */
    private static final @NotNull java.util.Map<String, Boolean> FACTORY_EMISSIVE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extracted bind data for a parameterized overlay layer (SkeletonClothingLayer-style):
     * which {@code ModelLayers.X} field the renderer's {@code addLayer(new XLayer(...))} call
     * passes for the layer's {@code bakeLayer} parameter, plus the texture-path literal bound
     * to the renderer's {@code X_LOCATION} static field that the same call site passes for the
     * layer's {@code clothesLocation} parameter. The downstream emission step keys the
     * {@code overlays} entry on the field name and writes the texture path as the
     * {@code texture_ref}, exactly like a statically-keyed composite overlay.
     */
    private record ParameterizedOverlayBinding(@NotNull String modelLayerField, @NotNull String texturePath) {}

    /**
     * Detects the SkeletonClothingLayer-family pattern - a layer class whose constructor
     * takes {@code ModelLayerLocation} + {@code Identifier} parameters and calls
     * {@code modelSet.bakeLayer(<modelLayerLocation_param>)} (rather than baking a static
     * {@code ModelLayers.X} reference) - and extracts the actual ModelLayers field name and
     * texture path from the renderer's {@code addLayer(new <layer>(...))} call site. Returns
     * {@code null} when the layer doesn't match the parameterized shape OR when either arg
     * can't be statically resolved at the call site (e.g., the renderer computes one of the
     * args at runtime, which never happens for the in-tree vanilla layers but a modded
     * subclass might).
     *
     * <p>Generic detection means any future layer wired to the same shape ({@code <init>}
     * takes both arg types + bakes the location param) auto-resolves without an allowlist
     * change, while purely-static composite layers (sheep wool, drowned outer) keep their
     * existing {@link #findOverlayModelLayerField}-driven path.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param layerCn the candidate layer class (its constructor descriptor is inspected)
     * @param rendererInternalName the renderer composing this layer (its constructor is
     *     scanned for the matching {@code addLayer(new layerCn(...))} call site)
     * @return resolved {@code (modelLayerField, texturePath)}, or {@code null} when the
     *     pattern doesn't match
     */
    private static @Nullable ParameterizedOverlayBinding findParameterizedOverlayBinding(
        @NotNull ClassNodeCache classNodes,
        @NotNull ClassNode layerCn,
        @NotNull String rendererInternalName
    ) {
        // Step 1: layer ctor must take ModelLayerLocation + Identifier and call bakeLayer on
        // the ModelLayerLocation parameter (rather than a static field).
        int[] paramSlots = findParameterizedCtorSlots(layerCn);
        if (paramSlots == null) return null;

        // Step 2: walk renderer constructors for the addLayer(new <layerCn>(...args)) call
        // site and pull out the GETSTATIC values pushed at the matching argument positions.
        ClassNode rendererCn = classNodes.load(rendererInternalName);
        if (rendererCn == null) return null;
        String layerInternalName = layerCn.name;
        for (MethodNode method : rendererCn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            ParameterizedOverlayBinding binding = scanRendererForParameterizedAddLayer(
                classNodes, method, layerInternalName);
            if (binding != null) return binding;
        }
        return null;
    }

    /**
     * Inspects a layer class to verify the SkeletonClothingLayer constructor shape:
     * <ol>
     *   <li>Has an {@code <init>} whose descriptor includes both
     *       {@code ModelLayerLocation} and {@code Identifier} as parameter types.</li>
     *   <li>That constructor calls {@code modelSet.bakeLayer(...)} with an {@code ALOAD} of
     *       the {@code ModelLayerLocation} parameter (not a {@code GETSTATIC ModelLayers.X}).</li>
     * </ol>
     * Returns a 2-element array {@code [modelLayerArgIndex, identifierArgIndex]} of the
     * matched parameter positions in the constructor's argument list ({@code this} excluded,
     * so the first declared parameter is index {@code 0}), or {@code null} when the layer
     * doesn't match.
     */
    private static int @Nullable [] findParameterizedCtorSlots(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            Type[] argTypes = Type.getArgumentTypes(method.desc);
            int modelLayerArg = -1;
            int identifierArg = -1;
            for (int i = 0; i < argTypes.length; i++) {
                String desc = argTypes[i].getDescriptor();
                if (modelLayerArg < 0 && MODEL_LAYER_LOCATION_DESC.equals(desc)) modelLayerArg = i;
                else if (identifierArg < 0 && IDENTIFIER_DESC.equals(desc)) identifierArg = i;
            }
            if (modelLayerArg < 0 || identifierArg < 0) continue;
            // Verify the bakeLayer call's receiver-arg pair is ALOAD of the
            // ModelLayerLocation parameter - the renderer's modelLayerArg+1 local slot
            // (slot 0 = this, then args in declared order). Static methods have no `this`
            // so we offset by 1 since constructors are instance methods.
            if (!ctorBakesParameter(method, modelLayerArg + 1)) continue;
            return new int[]{ modelLayerArg, identifierArg };
        }
        return null;
    }

    /**
     * Walks a constructor for {@code ALOAD <slot>; INVOKEVIRTUAL EntityModelSet.bakeLayer
     * (ModelLayerLocation)ModelPart} - i.e., the ModelLayerLocation argument coming directly
     * from a method parameter rather than a {@code GETSTATIC ModelLayers.X}. Returns
     * {@code true} when the pattern matches anywhere in the body, {@code false} otherwise.
     */
    private static boolean ctorBakesParameter(@NotNull MethodNode method, int parameterSlot) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!(in instanceof MethodInsnNode mi)) continue;
            if (!VanillaSourceClasses.ENTITY_MODEL_SET.equals(mi.owner) || !"bakeLayer".equals(mi.name)) continue;
            // Walk backwards past the receiver-load (modelSet) to find the
            // ModelLayerLocation arg push. The arg sits immediately under the receiver on
            // the stack, so the previous push instruction is the candidate.
            AbstractInsnNode prev = in.getPrevious();
            if (prev != null && prev.getOpcode() == Opcodes.ALOAD
                && prev instanceof VarInsnNode v
                && v.var == parameterSlot)
                return true;
        }
        return false;
    }

    /**
     * Scans one renderer constructor for {@code NEW <layerInternalName>; ... <args>; INVOKESPECIAL
     * <layerInternalName>.<init>; INVOKEVIRTUAL addLayer} chains and pulls out the
     * {@code GETSTATIC ModelLayers.X} + {@code GETSTATIC <Renderer>.<X>_LOCATION} args pushed
     * between {@code NEW} and {@code INVOKESPECIAL}. Returns the first match's
     * {@link ParameterizedOverlayBinding} or {@code null} when the args can't be statically
     * resolved.
     *
     * <p>Argument-order independence: matches by TYPE (the first GETSTATIC ModelLayers field
     * becomes {@code modelLayerField}, the first GETSTATIC Identifier field gets chased
     * through {@link #chaseTextureFieldOwner} to a texture-path literal). Robust to any
     * future renderer that reorders the ModelLayerLocation / Identifier args.
     */
    private static @Nullable ParameterizedOverlayBinding scanRendererForParameterizedAddLayer(
        @NotNull ClassNodeCache classNodes,
        @NotNull MethodNode rendererCtor,
        @NotNull String layerInternalName
    ) {
        for (AbstractInsnNode in = rendererCtor.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.NEW) continue;
            if (!(in instanceof TypeInsnNode type)) continue;
            if (!layerInternalName.equals(type.desc)) continue;
            // Walk forward from NEW until the matching INVOKESPECIAL <init>, collecting the
            // ModelLayers field and the Identifier field along the way.
            String modelLayerField = null;
            String identifierFieldOwner = null;
            String identifierFieldName = null;
            for (AbstractInsnNode arg = in.getNext(); arg != null; arg = arg.getNext()) {
                if (AsmKit.isInvokeSpecial(arg, layerInternalName, AsmKit.INIT)) break;
                if (arg.getOpcode() == Opcodes.GETSTATIC && arg instanceof FieldInsnNode fi) {
                    if (modelLayerField == null && VanillaSourceClasses.MODEL_LAYERS.equals(fi.owner)) {
                        modelLayerField = fi.name;
                    } else if (identifierFieldName == null && IDENTIFIER_DESC.equals(fi.desc)) {
                        identifierFieldOwner = fi.owner;
                        identifierFieldName = fi.name;
                    }
                }
            }
            if (modelLayerField == null || identifierFieldName == null) continue;
            String texturePath = chaseTextureFieldOwner(classNodes, identifierFieldOwner, identifierFieldName);
            if (texturePath == null) continue;
            return new ParameterizedOverlayBinding(modelLayerField, texturePath);
        }
        return null;
    }

    /**
     * Detects an emissive eye overlay by its {@code <clinit>} bytecode shape rather than class
     * hierarchy (not every eye layer extends {@code EyesLayer} - {@code BreezeEyesLayer} extends
     * {@code RenderLayer} directly). Walks {@code cn.<clinit>} for the pattern:
     * <pre>{@code
     *   LDC "textures/entity/.../X_eyes.png"     // texture literal (contains "eyes")
     *   ...
     *   INVOKESTATIC RenderTypes.*eyes*(Identifier)RenderType   // factory name contains "eyes"
     * }</pre>
     * and returns the last-seen texture literal paired with the matched factory name. The factory
     * name lets the caller discriminate fully-emissive {@code RenderTypes.eyes} from shaded
     * {@code RenderTypes.breezeEyes} (see {@link #factoryHasNoCardinalLighting}). Works unchanged
     * on both a {@code RenderLayer} subclass's {@code <clinit>} and a renderer's own
     * {@code <clinit>} (the inline {@code EnderDragonRenderer} style). Returns {@code null} when
     * the class has no {@code <clinit>} or the pattern doesn't match.
     */
    private static @Nullable EyesOverlayBinding findEyesOverlayBinding(@NotNull ClassNode cn) {
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingTexturePath = null;

        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && literal.startsWith(TEXTURE_PATH_PREFIX) && literal.endsWith(".png")
                && !literal.contains("%")) {
                pendingTexturePath = literal;
                continue;
            }

            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && mi.desc.endsWith(RENDER_TYPE_RETURN)
                && mi.name.toLowerCase(Locale.ROOT).contains("eyes")
                && pendingTexturePath != null
                && pendingTexturePath.contains("eyes"))
                return new EyesOverlayBinding(pendingTexturePath, mi.name);
        }

        return null;
    }

    /**
     * Walks a layer's non-init methods for {@code INVOKESTATIC RenderTypes.<factory>(...)
     * RenderType}, then resolves whether the matching pipeline carries the
     * {@code NO_CARDINAL_LIGHTING} shader define. Returns {@code true} when ANY RenderType
     * invocation in the layer body resolves to a no-cardinal-lighting pipeline (which the
     * runtime emits as an unlit overlay).
     */
    private static boolean layerInvokesNoCardinalLightingRenderType(@NotNull ClassNodeCache classNodes, @NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name)) continue;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (!(in instanceof MethodInsnNode mi)) continue;
                if (!VanillaSourceClasses.RENDER_TYPES.equals(mi.owner)) continue;
                if (factoryHasNoCardinalLighting(classNodes, mi.name)) return true;
            }
        }
        return false;
    }

    /**
     * Walks {@code RenderTypes.<factoryName>} for a {@code GETSTATIC RenderPipelines.X}
     * reference (the pipeline the factory builds its RenderType against), then walks
     * {@code RenderPipelines.<clinit>} for that field's build block and returns whether it
     * applies the {@code .withShaderDefine("NO_CARDINAL_LIGHTING")} call.
     *
     * <p>Memoized via {@link #FACTORY_EMISSIVE_CACHE}; the multi-hop walk is amortized across
     * every layer / eye-binding lookup. Returns {@code false} when the factory's pipeline
     * reference can't be resolved (e.g., {@code Function}-backed factories where the
     * pipeline reference lives behind an {@code InvokeDynamic} - the static walker treats
     * those as cardinal-lit).
     */
    private static boolean factoryHasNoCardinalLighting(@NotNull ClassNodeCache classNodes, @NotNull String factoryName) {
        Boolean cached = FACTORY_EMISSIVE_CACHE.get(factoryName);
        if (cached != null) return cached;
        ClassNode renderTypes = classNodes.load(VanillaSourceClasses.RENDER_TYPES);
        if (renderTypes == null) {
            FACTORY_EMISSIVE_CACHE.put(factoryName, false);
            return false;
        }

        String pipelineField = resolveRenderTypesFactoryPipeline(renderTypes, factoryName);
        if (pipelineField == null) {
            FACTORY_EMISSIVE_CACHE.put(factoryName, false);
            return false;
        }

        boolean result = pipelineHasNoCardinalLighting(classNodes, pipelineField);
        FACTORY_EMISSIVE_CACHE.put(factoryName, result);
        return result;
    }

    /**
     * Resolves the {@code RenderPipelines.X} field that the named {@code RenderTypes.X}
     * factory uses to construct its RenderType. Vanilla emits two factory shapes:
     * <ul>
     *   <li><b>Direct</b>: {@code public static RenderType breezeWind(...) { ... GETSTATIC
     *       RenderPipelines.BREEZE_WIND ... }} - the factory body itself references the
     *       pipeline.</li>
     *   <li><b>Function-backed</b>: {@code public static RenderType eyes(Identifier loc) {
     *       return EYES.apply(loc); }} where the {@code EYES:Function} static field is bound
     *       via {@code invokedynamic} to a synthetic {@code lambda$static$N(Identifier)} whose
     *       body starts with {@code LDC "eyes"; GETSTATIC RenderPipelines.EYES}.</li>
     * </ul>
     *
     * <p>Direct lookup succeeds first; if no {@code RenderPipelines.X} GETSTATIC appears in the
     * factory body, the fallback scans every {@code lambda$static$N} method whose first
     * {@code LDC} string equals {@code factoryName} and picks the GETSTATIC right after it.
     */
    private static @Nullable String resolveRenderTypesFactoryPipeline(@NotNull ClassNode renderTypes, @NotNull String factoryName) {
        for (MethodNode method : renderTypes.methods) {
            if (!factoryName.equals(method.name))
                continue;

            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.RENDER_PIPELINES.equals(fi.owner))
                    return fi.name;
            }
        }

        for (MethodNode method : renderTypes.methods) {
            if (!method.name.startsWith("lambda$static$")) continue;
            String firstLdc = null;

            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                String literal = AsmKit.readStringLiteral(in);

                if (literal != null) {
                    if (firstLdc == null) firstLdc = literal;
                    if (!factoryName.equals(firstLdc)) break;
                    continue;
                }

                if (firstLdc != null
                    && factoryName.equals(firstLdc)
                    && in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.RENDER_PIPELINES.equals(fi.owner))
                    return fi.name;
            }
        }

        return null;
    }

    /**
     * Walks {@code RenderPipelines.<clinit>} for the build block that ends with
     * {@code PUTSTATIC <pipelineFieldName>} and returns whether the chain applied
     * {@code .withShaderDefine("NO_CARDINAL_LIGHTING")}. Build-block boundaries are marked by
     * any {@code PUTSTATIC} on a {@code RenderPipeline}-typed field (each pipeline registration
     * ends with one). Traits accumulate until the block boundary; reset on every {@code
     * PUTSTATIC} so the previous pipeline's shader-defines don't leak into the next.
     */
    private static boolean pipelineHasNoCardinalLighting(@NotNull ClassNodeCache classNodes, @NotNull String pipelineFieldName) {
        ClassNode cn = classNodes.load(VanillaSourceClasses.RENDER_PIPELINES);
        if (cn == null) return false;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return false;
        boolean blockNoCardinal = false;
        String pendingShaderDefineName = null;

        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingShaderDefineName = literal;
                continue;
            }

            if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                && in instanceof MethodInsnNode mi
                && "withShaderDefine".equals(mi.name)
                && "NO_CARDINAL_LIGHTING".equals(pendingShaderDefineName)) {
                blockNoCardinal = true;
                pendingShaderDefineName = null;
                continue;
            }

            if (in.getOpcode() == Opcodes.PUTSTATIC && in instanceof FieldInsnNode fi) {
                if (pipelineFieldName.equals(fi.name)) return blockNoCardinal;
                // Build-block boundary - reset accumulated traits for the next pipeline.
                blockNoCardinal = false;
                pendingShaderDefineName = null;
            }
        }

        return false;
    }

    /**
     * Memoized cache for {@link #factoryHasTranslucentBlend} - keyed on
     * {@code RenderTypes.<factoryName>} factory method names so the multi-hop
     * {@code RenderTypes -> RenderPipelines.<clinit>} walk runs once per factory per
     * tooling pass.
     */
    private static final @NotNull java.util.Map<String, Boolean> FACTORY_TRANSLUCENT_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns whether the named {@code RenderTypes.X} factory builds against a pipeline
     * whose {@code <clinit>} build block pushes {@code GETSTATIC BlendFunction.TRANSLUCENT}
     * (into a {@code ColorTargetState} constructor). Used by
     * {@link #derivationAcceptsCompositeOverlay} to reject layers whose direct render-type
     * is translucent without {@code NO_CARDINAL_LIGHTING} (slime outer's
     * {@code entityTranslucent}, wolf armor's {@code armorTranslucent}).
     */
    private static boolean factoryHasTranslucentBlend(@NotNull ClassNodeCache classNodes, @NotNull String factoryName) {
        Boolean cached = FACTORY_TRANSLUCENT_CACHE.get(factoryName);
        if (cached != null) return cached;
        ClassNode renderTypes = classNodes.load(VanillaSourceClasses.RENDER_TYPES);

        if (renderTypes == null) {
            FACTORY_TRANSLUCENT_CACHE.put(factoryName, false);
            return false;
        }

        String pipelineField = resolveRenderTypesFactoryPipeline(renderTypes, factoryName);
        if (pipelineField == null) {
            FACTORY_TRANSLUCENT_CACHE.put(factoryName, false);
            return false;
        }

        boolean result = pipelineHasTranslucentBlend(classNodes, pipelineField);
        FACTORY_TRANSLUCENT_CACHE.put(factoryName, result);
        return result;
    }

    /**
     * Walks {@code RenderPipelines.<clinit>} for the build block ending at
     * {@code PUTSTATIC <pipelineFieldName>} and returns whether the chain pushed a
     * {@code GETSTATIC BlendFunction.TRANSLUCENT}. Build-block boundaries are marked by
     * any {@code PUTSTATIC} on a {@code RenderPipeline}-typed field.
     */
    private static boolean pipelineHasTranslucentBlend(@NotNull ClassNodeCache classNodes, @NotNull String pipelineFieldName) {
        ClassNode cn = classNodes.load(VanillaSourceClasses.RENDER_PIPELINES);
        if (cn == null) return false;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return false;
        boolean blockTranslucent = false;

        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.GETSTATIC
                && in instanceof FieldInsnNode fi
                && BLEND_FUNCTION.equals(fi.owner)
                && "TRANSLUCENT".equals(fi.name)) {
                blockTranslucent = true;
                continue;
            }

            if (in.getOpcode() == Opcodes.PUTSTATIC && in instanceof FieldInsnNode fi) {
                if (pipelineFieldName.equals(fi.name)) return blockTranslucent;
                blockTranslucent = false;
            }
        }

        return false;
    }

    /**
     * Decides whether a composite-overlay layer's bytecode is safe to emit via the
     * generic gate. Two conditions:
     * <ol>
     *   <li>The layer's non-init non-clinit body invokes either an
     *       {@code INVOKESTATIC RenderTypes.X} factory OR the
     *       {@code coloredCutoutModelCopyLayerRender} helper. Excludes EnergySwirlLayer
     *       subclasses (CreeperPowerLayer, WitherArmorLayer) and equipment-only layers
     *       (WingsLayer, RopesLayer) whose render type lives in inherited /
     *       runtime-dispatched code we can't walk statically.</li>
     *   <li>If any direct {@code RenderTypes.X} call resolves to a cutout-unsafe pipeline
     *       (translucent blend without {@code NO_CARDINAL_LIGHTING}), reject. Excludes
     *       SlimeOuterLayer's {@code entityTranslucent}; the policy override in
     *       {@link #POLICY_FORCE_EMIT} re-allows it. The helper is always cutout by
     *       construction so helper-only layers pass.</li>
     * </ol>
     * Callers run dedicated class-specific handlers (LlamaDecorLayer,
     * TropicalFishPatternLayer, VillagerProfessionLayer) BEFORE this generic gate so they
     * claim their classes via {@code continue} - no class-name short-circuit needed here.
     */
    private static boolean derivationAcceptsCompositeOverlay(@NotNull ClassNodeCache classNodes, @NotNull ClassNode layerCn) {
        boolean hasDirectRenderType = false;
        boolean usesHelper = false;

        for (MethodNode method : layerCn.methods) {
            if (AsmKit.INIT.equals(method.name) || AsmKit.CLINIT.equals(method.name))
                continue;

            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (!(in instanceof MethodInsnNode mi)) continue;

                if (VanillaSourceClasses.RENDER_TYPES.equals(mi.owner)) {
                    hasDirectRenderType = true;
                    if (factoryHasTranslucentBlend(classNodes, mi.name)
                        && !factoryHasNoCardinalLighting(classNodes, mi.name))
                        return false;
                } else if (COLORED_CUTOUT_HELPER.equals(mi.name))
                    usesHelper = true;
            }
        }

        return hasDirectRenderType || usesHelper;
    }

    /**
     * Walks a {@code RenderLayer} subclass for a {@code GETSTATIC
     * EquipmentClientInfo$LayerType.X} reference and returns the lowercase field name
     * ({@code "LLAMA_BODY"} -&gt; {@code "llama_body"}), which doubles as the equipment-texture
     * subdirectory ({@code textures/entity/equipment/llama_body/}). Returns {@code null} when
     * the layer doesn't reference the enum.
     */
    private static @Nullable String findEquipmentLayerSubdir(@NotNull ClassNode layerCn) {
        for (MethodNode method : layerCn.methods) {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && fi.owner.endsWith("EquipmentClientInfo$LayerType"))
                    return fi.name.toLowerCase(Locale.ROOT);
            }
        }

        return null;
    }

    /**
     * Walks {@code EquipmentAssets.<clinit>} for {@code LDC "<assetId>"; INVOKESTATIC createId;
     * PUTSTATIC <fieldName>} and returns the bound asset id (e.g., field {@code TRADER_LLAMA}
     * -&gt; LDC {@code "trader_llama"}). Returns {@code null} when the field isn't bound to a
     * literal id (e.g., {@code CARPETS} / {@code HARNESSES} are Map builders, not single ids).
     */
    private static @Nullable String findEquipmentAssetId(@NotNull ClassNodeCache classNodes, @NotNull String fieldName) {
        ClassNode cn = classNodes.load(VanillaSourceClasses.EQUIPMENT_ASSETS);
        if (cn == null) return null;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        String pendingLdc = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingLdc = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && fieldName.equals(fi.name)
                && pendingLdc != null)
                return pendingLdc;
        }
        return null;
    }

    /**
     * Resolves the per-entity multiplicative base tint (mirroring vanilla's
     * {@code LivingEntityRenderer.getModelTint(state)}) for entities whose renderer reads a
     * {@code DyeColor} state field. Currently only {@code TropicalFishRenderer} matches: its
     * {@code extractRenderState} calls {@code entity.getBaseColor().getTextureDiffuseColor()}
     * to populate {@code state.baseColor}, and {@code getModelTint} returns that field
     * directly. At zero state {@code entity.getBaseColor()} defaults to {@code DyeColor.WHITE}
     * whose {@code textureDiffuseColor} is the {@code 0xF9FFFE} constant inscribed in
     * {@code DyeColor.<clinit>}'s first allocation. We surface that as {@code 0xFFF9FFFE} (alpha
     * 0xFF prepended) so the runtime tint multiplier matches vanilla's zero-state harness output.
     *
     * <p>Returns {@code 0xFFFFFFFF} (the no-op multiplicative tint) when the renderer doesn't
     * reference {@code DyeColor.getTextureDiffuseColor}.
     */
    public static int resolveBaseTint(@NotNull ClassNodeCache classNodes, @NotNull String rendererInternalName) {
        ClassNode cn = classNodes.load(rendererInternalName);
        if (cn == null) return 0xFFFFFFFF;
        for (MethodNode method : cn.methods)
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext())
                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && in instanceof MethodInsnNode mi
                    && VanillaSourceClasses.DYE_COLOR.equals(mi.owner)
                    && "getTextureDiffuseColor".equals(mi.name))
                    return walkDyeColorWhiteTextureDiffuseColor(classNodes);
        return 0xFFFFFFFF;
    }

    /**
     * Walks the supplied class's {@code <clinit>} for a static field bound via {@code new
     * CubeDeformation(F); PUTSTATIC <fieldName>} and returns the literal float arg. Returns
     * {@code 0} when the field doesn't exist or isn't a CubeDeformation literal init.
     */
    private static float walkCubeDeformationFloat(@NotNull ClassNodeCache classNodes, @NotNull String ownerClass, @NotNull String fieldName) {
        ClassNode cn = classNodes.load(ownerClass);
        if (cn == null) return 0f;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return 0f;
        boolean inAlloc = false;
        Float pendingFloat = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.NEW
                && in instanceof TypeInsnNode ti
                && VanillaSourceClasses.CUBE_DEFORMATION.equals(ti.desc)) {
                inAlloc = true;
                pendingFloat = null;
                continue;
            }
            if (!inAlloc) continue;
            Float literal = AsmKit.readFloatLiteral(in);
            if (literal != null) {
                pendingFloat = literal;
                continue;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC
                && in instanceof FieldInsnNode fi
                && fieldName.equals(fi.name)
                && pendingFloat != null) {
                return pendingFloat;
            }
            if (in.getOpcode() == Opcodes.PUTSTATIC) {
                inAlloc = false;
                pendingFloat = null;
            }
        }
        return 0f;
    }

    /**
     * Walks {@code DyeColor.<clinit>} for the {@code WHITE} enum allocation and returns its
     * {@code textureDiffuseColor} (the 5th constructor arg - {@code "WHITE", 0, 0, "white",
     * <textureDiffuseColor>, MapColor.SNOW, ...}), with alpha {@code 0xFF} prepended so the
     * value matches the {@code ARGB} convention used by overlay {@code tint_color} / entity
     * {@code base_tint} JSON fields. Returns {@code 0xFFFFFFFF} (no-op tint) when the
     * pattern isn't matched.
     */
    private static int walkDyeColorWhiteTextureDiffuseColor(@NotNull ClassNodeCache classNodes) {
        ClassNode cn = classNodes.load(VanillaSourceClasses.DYE_COLOR);
        if (cn == null) return 0xFFFFFFFF;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return 0xFFFFFFFF;
        // First DyeColor allocation IS the WHITE entry (enum declaration order matches <clinit>
        // emit order). Walk forward to the first PUTSTATIC WHITE; the 5th literal arg
        // pushed since the NEW is the textureDiffuseColor.
        boolean inAlloc = false;
        int literalsSeen = 0;
        int textureDiffuseColor = -1;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.NEW
                && in instanceof TypeInsnNode ti
                && VanillaSourceClasses.DYE_COLOR.equals(ti.desc)) {
                inAlloc = true;
                literalsSeen = 0;
                continue;
            }
            if (!inAlloc) continue;
            if (in.getOpcode() == Opcodes.PUTSTATIC) break;  // first PUTSTATIC ends the WHITE init
            if (AsmKit.readStringLiteral(in) != null) {
                literalsSeen++;
                continue;
            }
            Integer intLit = AsmKit.readIntLiteral(in);
            if (intLit != null) {
                literalsSeen++;
                if (literalsSeen == 5) textureDiffuseColor = intLit;
            }
        }
        if (textureDiffuseColor < 0) return 0xFFFFFFFF;
        return 0xFF000000 | textureDiffuseColor;
    }

    /**
     * Walks the renderer's constructor for a {@code new VillagerProfessionLayer(this,
     * resourceManager, "&lt;prefix&gt;", ...)} allocation and returns the third constructor
     * argument - the texture-directory prefix the layer concatenates with
     * {@code "/type/&lt;biome&gt;.png"} at submit time. Vanilla source: VillagerRenderer
     * passes {@code "villager"}, ZombieVillagerRenderer passes {@code "zombie_villager"}.
     */
    private static @Nullable String extractVillagerProfessionPrefix(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        ClassNode renderer = classNodes.load(rendererInternalName);
        if (renderer == null) return null;

        for (MethodNode method : renderer.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            boolean inAlloc = false;
            String pendingLdc = null;

            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.NEW
                    && in instanceof TypeInsnNode ti
                    && VanillaSourceClasses.VILLAGER_PROFESSION_LAYER.equals(ti.desc)) {
                    inAlloc = true;
                    pendingLdc = null;
                    continue;
                }

                if (!inAlloc) continue;
                String literal = AsmKit.readStringLiteral(in);
                if (literal != null && !literal.startsWith("textures/") && !literal.contains("/")) {
                    pendingLdc = literal;
                    continue;
                }

                if (in.getOpcode() == Opcodes.INVOKESPECIAL
                    && in instanceof MethodInsnNode mi
                    && VanillaSourceClasses.VILLAGER_PROFESSION_LAYER.equals(mi.owner)
                    && AsmKit.INIT.equals(mi.name))
                    return pendingLdc;
            }
        }

        return null;
    }

    /**
     * Detects the {@code new LivingEntityEmissiveLayer(this, provider, ...)} same-geometry
     * emissive overlay pattern in a renderer's constructor and resolves the zero-state texture
     * path the provider returns at runtime. Returns the texture path when the pattern matches
     * AND the layer's {@code bakeLayer(ModelLayers.X)} arg matches the base renderer's
     * {@code ModelLayers.X} - the latter rules out warden / creaking which use a distinct
     * model layer ({@code WARDEN_BIOLUMINESCENT}, {@code CREAKING_EYES}) for their emissive
     * layer and need to flow through the separate-geometry overlay path.
     *
     * <p>Texture resolution: the provider lambda eventually dispatches through static method(s)
     * on a sibling data class ({@code CopperGolemOxidationLevels} for copper_golem) that binds
     * one Identifier-typed field per state. The zero-state texture is the first LDC matching
     * {@code *_eyes.png} (or {@code *_eye.png}) encountered in any reachable class's
     * {@code <clinit>} - because the data class's enum-like {@code <clinit>} allocates the
     * default-state instance first.
     */
    private static @Nullable String findLivingEntityEmissiveTexture(@NotNull ClassNodeCache classNodes, @NotNull ClassNode renderer) {
        // Step 1: require BOTH a `new LivingEntityEmissiveLayer` AND a duplicated
        // bakeLayer(ModelLayers.X) GETSTATIC in the same <init>. The duplication signals the
        // emissive layer reuses the base renderer's ModelLayers; renderers using a distinct
        // layer (warden's WARDEN_BIOLUMINESCENT, creaking's CREAKING_EYES) get unique GETSTATIC
        // field names and don't satisfy the duplicate check.
        boolean sawEmissiveLayer = false;
        java.util.HashMap<String, Integer> modelLayerCounts = new java.util.HashMap<>();

        for (MethodNode method : renderer.methods) {
            if (!AsmKit.INIT.equals(method.name)) continue;
            String pendingModelLayer = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.NEW
                    && in instanceof TypeInsnNode ti
                    && VanillaSourceClasses.LIVING_ENTITY_EMISSIVE_LAYER.equals(ti.desc)) {
                    sawEmissiveLayer = true;
                    continue;
                }

                if (in.getOpcode() == Opcodes.GETSTATIC
                    && in instanceof FieldInsnNode fi
                    && VanillaSourceClasses.MODEL_LAYERS.equals(fi.owner)) {
                    pendingModelLayer = fi.name;
                    continue;
                }

                if (in.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && in instanceof MethodInsnNode mi
                    && "bakeLayer".equals(mi.name)
                    && pendingModelLayer != null) {
                    modelLayerCounts.merge(pendingModelLayer, 1, Integer::sum);
                    pendingModelLayer = null;
                }
            }
        }

        if (!sawEmissiveLayer) return null;
        boolean sharedModelLayer = false;
        for (Integer count : modelLayerCounts.values()) {
            if (count != null && count >= 2) {
                sharedModelLayer = true;
                break;
            }
        }
        if (!sharedModelLayer) return null;

        // Step 2: collect candidate classes (renderer + every class invoked statically from any
        // of its methods - including the synthetic lambda methods that hold the texture-provider
        // body). Renderer first, then INVOKESTATIC targets in source order. The data class
        // (CopperGolemOxidationLevels) is reached via the lambda's INVOKESTATIC to
        // getOxidationLevel.
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(renderer.name);
        for (MethodNode method : renderer.methods) {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == Opcodes.INVOKESTATIC
                    && in instanceof MethodInsnNode mi
                    && !mi.owner.startsWith("java/")
                    && !mi.owner.startsWith("com/mojang/")
                    && !mi.owner.equals(renderer.name))
                    candidates.add(mi.owner);
            }
        }

        // Step 3: scan each candidate class's <clinit> for the first {@code *_eyes.png} (or
        // {@code *_eye.png}) LDC. The data class's <clinit> allocates the default-state
        // instance first, so its eye texture is the first such literal.
        for (String className : candidates) {
            ClassNode cn = classNodes.load(className);
            if (cn == null) continue;
            MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
            if (clinit == null) continue;

            for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
                String literal = AsmKit.readStringLiteral(in);
                if (literal == null) continue;
                if (!literal.startsWith(TEXTURE_PATH_PREFIX)) continue;
                if (!literal.endsWith(".png")) continue;
                String stem = literal.substring(0, literal.length() - ".png".length());
                if (stem.endsWith("_eyes") || stem.endsWith("_eye"))
                    return literal;
            }
        }

        return null;
    }

}
