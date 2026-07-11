package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.load.entity.EntityFamilyReader;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the runtime entity model records ({@link EntityDefinition} and its nested overlay / layer /
 * transform types) and delegates loading to the native v2 reader {@link EntityFamilyReader}, which
 * reads {@code v2/entity_models.json} (per-entity metadata: geometry reference, texture reference,
 * overlays, axes, layers) joined against {@code v2/entity_geometry.json} (the deduplicated bone/cube
 * trees) directly into the {@link EntityDefinition} map.
 * <p>
 * A {@code texture_ref} is the vanilla {@code textures/entity/} sub-path (e.g.
 * {@code "cow/cow"}, {@code "wither/wither"}); resolved at render time against the active pack
 * stack via
 * {@link RendererContext#resolveTexture(String) resolveTexture}
 * as {@code minecraft:entity/<ref>}.
 * <p>
 * Many Java {@code EntityType} registry rows share one geometry (e.g. {@code horse},
 * {@code donkey}, {@code mule}, {@code skeleton_horse}, {@code zombie_horse} all reference the same
 * bone tree). Splitting the data into two files lets each entity metadata row stay small while the
 * potentially-multi-kilobyte bone tree is stored exactly once.
 *
 * @see PipelineRendererContext
 * @see EntityFamilyReader
 */
@UtilityClass
public class EntityModelLoader {

    /**
     * The auto-emitted depth-clearance inflate applied to same-geometry overlays (eyes, clothing
     * patterns) so they win the coplanar depth tie against the base mesh. This is OUR artifact -
     * vanilla submits the identical {@code ModelPart} with no deformation - so a same-geometry
     * overlay carrying at most this much inflate is excluded from canvas-sizing bounds. A larger
     * inflate is a real vanilla {@code CubeDeformation} (tropical_fish 0.008, llama carpet 0.5) that
     * vanilla's bounds walk includes, so it keeps contributing.
     *
     * <p>Narrowed back to private now the tooling2 bridge (its only former external reader) is
     * retired; {@link EntityFamilyReader} carries its own mirror constant for the native read.
     */
    private static final float DEPTH_CLEARANCE_INFLATE = 0.001f;

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
     * @param boneToggles named bone-visibility toggles (toggle name -&gt; {@link BoneToggle}), flipped
     *     at render when {@code EntityAppearance.toggles} selects the toggle: a default-hidden toggle
     *     (donkey/mule/llama {@code chest}) re-adds its bones, a default-visible toggle (goat
     *     {@code horn}) removes them. The default render is unchanged (chest stripped, horns present);
     *     empty for entities with no toggleable bones
     * @param axes the option-axis mesh / texture selections a render appearance chooses among (state
     *     textures, baby mesh, large shape, size meshes / scales) - see {@link Axes}
     * @param layers the conditional decoration layers drawn over the base body (collar, equipment,
     *     markings), each gated at render on its appearance axis - see {@link Layers}
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
        @NotNull Map<String, BoneToggle> boneToggles,
        @NotNull Axes axes,
        @NotNull Layers layers
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
         * Whether a vanilla {@code HumanoidArmorLayer} classifies this entity - a derived view over
         * {@link #layers()} (delegating to {@link Layers#humanoidArmor()}), so no top-level component
         * stores the classification. The successor to the former top-level {@code armor_type} member
         * [LOCKED 3], populated by the native reader off the v2 {@code layers} armor row.
         *
         * @return {@code true} when a humanoid-armor layer classifies this entity
         */
        public boolean humanoidArmor() {
            return this.layers.humanoidArmor();
        }

        /**
         * The option-axis meshes and textures a render appearance selects among (the former
         * {@code stateTextures} / {@code babyModel} / {@code largeShape} / {@code sizeModels} /
         * {@code sizeScales} side-channels, nested into first-class structure).
         *
         * @param stateTextures alternate base textures keyed by behavioural state (wolf
         *     {@code wild}/{@code tame}/{@code angry}) plus the {@code baby} texture, populated for
         *     multi-state / ageable variant families; empty otherwise. The {@code wild} entry, when
         *     present, equals the definition's {@code textureRef}
         * @param babyModel the distinct baked baby mesh, used in place of the base model when the
         *     {@code age} axis selects {@code baby}; empty for entities with no dedicated baby mesh
         * @param largeShape the {@code shape} axis's large alternative (tropical fish): the large body
         *     mesh + {@code tropical_b} texture + pattern overlays cloned onto it; empty otherwise
         * @param sizeModels the {@code size} axis's non-default alternate meshes keyed by {@link Size}
         *     (pufferfish); the default size is the base model and absent here; empty for no-size-axis entities
         * @param sizeScales the {@code size} axis's non-default render scale factors keyed by {@link Size}
         *     (salmon / slime / magma_cube); the default size is scale {@code 1.0} and absent here; empty otherwise
         * @param variants the {@code variant} axis's option-encoded coat sub-definitions keyed by option
         *     (cow {@code temperate}/{@code cold}/{@code warm}, wolf coats, cat breeds), each a fully-built
         *     definition byte-identical to the id-encoded pseudo-id it replaces; the base definition IS the
         *     default option's build. Empty when {@code variant} is id-encoded (each coat a first-class
         *     pseudo-id) or the family has no variant axis. The render-time variant fold in
         *     {@link lib.minecraft.renderer.pipeline.resolve.EntityDefinitionResolver} swaps to the selected
         *     option's sub-definition, and the family canvas union measures every option's silhouette
         */
        public record Axes(
            @NotNull Map<String, String> stateTextures,
            @NotNull Optional<EntityModelData> babyModel,
            @NotNull Optional<LargeShape> largeShape,
            @NotNull Map<Size, EntityModelData> sizeModels,
            @NotNull Map<Size, Float> sizeScales,
            @NotNull Map<String, EntityDefinition> variants
        ) {}

        /**
         * The conditional decoration layers drawn over the base body (the former {@code collarTexture}
         * / {@code equipment} / {@code markings} side-channels, nested into first-class structure),
         * each gated at render on its appearance axis.
         *
         * @param collar the dyed-collar texture drawn on the body geometry and tinted by the collar
         *     colour (wolf, cat); empty for non-collar entities
         * @param equipment the saddle / body-armor overlays rendered when the {@code equipment} axis
         *     selects their slot; empty for entities with no equipment layer
         * @param markings whether the entity supports the horse {@code markings} axis (a same-geometry
         *     translucent overlay over the coat); the default marking draws nothing
         * @param humanoidArmor whether a vanilla {@code HumanoidArmorLayer} classifies this entity
         *     (skeletons, zombies, piglins) - the {@code armor_type: "humanoid"} classification the v2
         *     {@code layers} armor row carries [LOCKED 3], read off it at load. No 26.1 render consumes
         *     it (humanoid armor is not drawn); it is the located, first-class successor to the former
         *     required-but-unconsumed top-level {@code armor_type} member
         * @param markingTextures the horse {@code markings} axis value-name -&gt; overlay texture sub-path
         *     table, read from the v2 {@code layers} markings row's {@code textures_by_value} (dir 4c) and
         *     load-validated equal to the {@link lib.minecraft.renderer.option.HorseMarking} enum table
         *     (the enum survives as a value-name validator); empty for the legacy flag-on path, which
         *     strips {@code textures_by_value} and falls back to the enum
         */
        public record Layers(
            @NotNull Optional<String> collar,
            @NotNull List<EquipmentOverlay> equipment,
            boolean markings,
            boolean humanoidArmor,
            @NotNull Map<String, String> markingTextures
        ) {}
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
     * the Y axis -> {@link RotateY} / on the X axis -> {@link RotateX} / on the Z axis ->
     * {@link RotateZ}, {@code scale(F, F, F)} -> {@link Scale}.
     *
     * <p>Sealed so the renderer can pattern-match without a default branch. Add a new op kind
     * by extending the seal and updating both the JSON serialiser and the renderer dispatch.
     */
    public sealed interface TransformOp permits Translate, RotateY, RotateX, RotateZ, Scale {}

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
     * Rotation around the Z axis by {@code degrees} (a {@code mulPose(rotationDegrees)} on
     * {@code Axis.ZP}). Vocabulary-only in 26.1 - no vanilla block-overlay layer emits a
     * {@code rotate_z} row - but present so a future one composes in the correct PoseStack order.
     */
    public record RotateZ(float degrees) implements TransformOp {}

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
     * @param textureBy the render-axis token whose selection overrides {@link #textureRef} at render
     *     (e.g. {@code "pattern"} for the tropical-fish pattern, sourced from
     *     {@code EntityAppearance.pattern}), or empty when the overlay texture is fixed at
     *     {@link #textureRef}
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
     * @param gate the render condition parsed from the overlay's {@code when} object (the sheep wool
     *     {@code sheared} flag, the wool undercoat {@code tinted} axis, the creeper {@code charged}
     *     axis), or empty when the overlay renders unconditionally. Retires the former
     *     {@code shearable} / {@code requiresTint} / {@code requiresCharged} booleans
     */
    public record OverlayLayer(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        boolean emissive,
        int tintArgb,
        boolean skipBounds,
        @NotNull Optional<String> tintBy,
        @NotNull Optional<String> textureBy,
        @NotNull BlendMode blend,
        float alpha,
        @NotNull Optional<AppearanceGate> gate
    ) {}

    /**
     * One equipment overlay on an {@link EntityDefinition}: a saddle / body-armor mesh (its own baked
     * geometry) rendered on the body only when the {@code equipment} render axis selects its
     * {@link #slot}. Unlike an always-on {@link OverlayLayer}, the texture is chosen at render from
     * the axis-selected material through {@link #textureTemplate} (or {@link #defaultMaterial} when
     * the slot is selected without a material). Sourced by {@link EntityFamilyReader} from the family
     * form's {@code when.equipment}-gated {@code layers}.
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
     * The {@code shape} axis's large-body alternative (tropical fish), resolved eagerly at load: the
     * large body mesh, its base texture, and the pattern overlays materialised onto the large
     * geometry. the entity definition resolver swaps these in wholesale when the selected
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
     * Loads the bundled entity definitions natively from the v2 resources, delegating to
     * {@link EntityFamilyReader}.
     *
     * @return definitions keyed by namespaced entity id (empty when the geometry resource is absent)
     * @throws PipelineException when a resource file is present but unparseable, or when an
     *     entity references a geometry id not in the geometry file
     */
    public static @NotNull ConcurrentMap<String, EntityDefinition> load() {
        return EntityFamilyReader.load(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * A named bone-visibility toggle resolved at load: the geometry {@link EntityModelData.Bone bones}
     * it flips (kept by name so the entity definition resolver can add or remove them) plus their
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
     * Lazily-computed {@code entityId -> familyMembers} result of {@link #loadFamilies()}, cached
     * for the JVM lifetime (the underlying JSON never changes at runtime).
     */
    private static volatile Map<String, List<String>> FAMILIES_CACHE;

    /**
     * Returns {@code entityId -> familyMembers} keyed by every native entity id. Family membership is
     * derived from {@code variant_of} (variant siblings roll up to their base row) plus the
     * cross-entity {@code family_of} groupings (mooshroom -&gt; cow) by {@link EntityFamilyReader}.
     * Singletons return a single-element list containing themselves so callers can iterate uniformly
     * without special-casing. The result is cached on first call - the JSON is loaded once for the
     * lifetime of the JVM.
     */
    public static @NotNull Map<String, List<String>> loadFamilies() {
        Map<String, List<String>> cached = FAMILIES_CACHE;
        if (cached != null) return cached;
        synchronized (EntityModelLoader.class) {
            if (FAMILIES_CACHE != null) return FAMILIES_CACHE;
            Map<String, List<String>> result =
                EntityFamilyReader.loadFamilies(Diagnostics.root("entity_models", Diagnostics.Output.CONSOLE, null));
            FAMILIES_CACHE = result;
            return result;
        }
    }

}
