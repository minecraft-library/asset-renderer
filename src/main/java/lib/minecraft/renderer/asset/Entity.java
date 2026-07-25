package lib.minecraft.renderer.asset;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.BlendMode;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.option.AppearanceGate;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.HorseMarking;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A fully-parsed entity definition - the base bone/cube geometry, its vanilla texture reference, and
 * the overlay / block-overlay / axis / layer structure a render appearance selects among - consumed by
 * {@link EntityRenderer}. Loaded from the bundled resources by {@link EntityModelLoader} (per-entity
 * metadata joined against the deduplicated bone/cube trees) and looked up by namespaced id via
 * {@link RendererContext#findEntity(String)}. Player skins are never stored on this DTO; they are
 * supplied at render time through the {@code PlayerOptions.skinBytes} / {@code skinUrl} /
 * {@code skinTextureId} fields.
 * <p>
 * A {@code texture_ref} is the vanilla {@code textures/entity/} sub-path (e.g. {@code "cow/cow"},
 * {@code "wither/wither"}); resolved at render time against the active pack stack via
 * {@link RendererContext#resolveTexture(String) resolveTexture} as {@code minecraft:entity/<ref>}.
 * <p>
 * Many Java {@code EntityType} registry rows share one geometry (e.g. {@code horse}, {@code donkey},
 * {@code mule}, {@code skeleton_horse}, {@code zombie_horse} all reference the same bone tree). Splitting
 * the data into two files lets each entity metadata row stay small while the potentially-multi-kilobyte
 * bone tree is stored exactly once.
 *
 * @param id the entity's namespaced identifier (e.g. {@code minecraft:zombie})
 * @param model the parsed bone/cube tree (shared across all entities with the same geometry_ref)
 * @param textureRef the vanilla {@code textures/entity/} sub-path (without the {@code .png} suffix),
 *     resolved at render time via {@link RendererContext#resolveTexture(String) resolveTexture} as
 *     {@code minecraft:entity/<ref>}, or empty when no default texture
 * @param overlays additional geometry/texture pairs rendered on top of the base model in declared
 *     order; populated by the bytecode-derived overlay scan ({@code EntityOverlayResolver}: emissive
 *     eyes, profession layers, pattern layers, equipment-driven decor layers). A baby render draws
 *     {@link Axes#babyOverlays()} instead - the passes materialised on the baby mesh
 * @param blockOverlays vanilla-block-shaped overlays rendered on top of the entity body (mooshroom
 *     mushrooms, iron golem poppy) at a transform-stack-applied position
 * @param baseTintArgb per-entity multiplicative tint applied to the base mesh, mirroring
 *     {@code LivingEntityRenderer.getModelTint(state)}. Defaults to {@code 0xFFFFFFFF} (white = no-op
 *     MULTIPLY)
 * @param setupYawAddend yaw rotation in degrees that the vanilla renderer's {@code setupRotations}
 *     override adds to the standard {@code bodyRot} before the super call. Extracted from the
 *     {@code super.setupRotations(state, ps, bodyRot + N, scale)} bytecode pattern by the tooling-side
 *     renderer scan. {@code ShulkerRenderer} is the canonical case ({@code +180.0F}); every other
 *     vanilla renderer leaves {@code bodyRot} unmodified and lands at {@code 0}. The renderer adds this
 *     to the user-supplied yaw before applying the iso pose - for shulker the addend collapses the
 *     default {@code rotateY(180-bodyRot)} body rotation to identity, exposing the lid's authored UV
 *     orientation unrotated against the viewer
 * @param rendererScale per-entity render-time scale extracted by {@code EntityRendererScaleResolver};
 *     defaults to {@code 1f} (identity)
 * @param boneToggles named bone-visibility toggles (toggle name -&gt; {@link BoneToggle}), flipped at
 *     render when {@code EntityAppearance.toggles} selects the toggle: a default-hidden toggle
 *     (donkey/mule/llama {@code chest}) re-adds its bones, a default-visible toggle (goat {@code horn})
 *     removes them. The default render is unchanged (chest stripped, horns present); empty for entities
 *     with no toggleable bones
 * @param axes the option-axis mesh / texture selections a render appearance chooses among (state
 *     textures, baby mesh, large shape, size meshes / scales) - see {@link Axes}
 * @param layers the conditional decoration layers drawn over the base body (collar, equipment,
 *     markings), each gated at render on its appearance axis - see {@link Layers}
 * @param members the self-inclusive canvas-group membership - every entity id that shares this
 *     entity's group-union fit window ({@code EntityOptions.FitMode.GROUP_BOUNDS}), the SAME list on
 *     each member of the group; empty for a singleton entity with no group
 */
@Builder(toBuilder = true)
public record Entity(
    @NotNull ResourceId id,
    @NotNull EntityModelData model,
    @NotNull Optional<String> textureRef,
    @NotNull List<OverlayLayer> overlays,
    @NotNull List<BlockOverlayLayer> blockOverlays,
    int baseTintArgb,
    float setupYawAddend,
    float rendererScale,
    @NotNull Map<String, BoneToggle> boneToggles,
    @NotNull Axes axes,
    @NotNull Layers layers,
    @NotNull List<String> members
) {

    /** Normalises a never-set {@link #members} to an empty (singleton) list so callers can omit it. */
    public Entity {
        members = members == null ? List.of() : members;
    }

    /**
     * Returns a copy with no {@link #blockOverlays() block overlays}, for the {@code carried} render
     * toggle (a sheared snow golem, an empty-handed enderman) - dropping both the rendered geometry and
     * its canvas-bounds contribution.
     *
     * @return an otherwise-identical definition with an empty block-overlay list
     */
    public @NotNull Entity withoutBlockOverlays() {
        return toBuilder().blockOverlays(List.of()).build();
    }

    /**
     * The worn-armor shell this entity is dressed in - a derived view over {@link #layers()}
     * (delegating to {@link Layers#humanoidArmor()}), so no top-level component stores it. Empty for an
     * entity vanilla arms with no {@code HumanoidArmorLayer}, which is what gates the worn-armor render
     * feature.
     *
     * @return the armor shell, or empty when this entity wears none
     */
    public @NotNull Optional<HumanoidArmor> humanoidArmor() {
        return this.layers.humanoidArmor();
    }

    /**
     * Folds this definition's render-axis selections for the given appearance into a single resolved
     * {@link Entity} the renderer iterates unconditionally, with no scattered {@code !baby} gates - the
     * render-time policy the reader deliberately leaves off the loaded data.
     *
     * <p>The nine axis semantics apply in a fixed short-circuit order: (1) a baby swaps in the baby mesh,
     * substitutes the {@link Axes#babyOverlays() baby overlay list} for the adult one, and DROPS block
     * overlays / collar / equipment - each carries adult geometry that would render adult-sized around
     * the smaller baby body, which is exactly why the overlay passes are a distinct list rather than the
     * adult one, and the substituted list is empty unless an overlay declares a baby form, so a pass with
     * none drops out structurally - and the whole non-baby branch is skipped bar the overlay gate filter
     * (2), which runs over whichever list is in play; else (2) sheared drops the wool overlay and charged
     * gates the swirl; (3) the sheared axis additionally
     * activates a {@code "sheared"} bone toggle (bogged); (4) selected bone toggles flip their bones'
     * visibility (donkey / mule / llama chest reveal, goat horns hide); (5) block overlays resolve against
     * the carried selection; (6) the shape axis swaps to the tropical-fish large body; (7) the size axis
     * swaps to the selected size's mesh (pufferfish, salmon); (8) the size axis multiplies the render scale
     * (slime / magma_cube); (9) the base-color axis overrides the baked base tint (tropical-fish dye),
     * applied OUTSIDE the baby fork so it affects both. A non-baby, non-carried appearance returns an
     * equivalent definition unchanged.
     *
     * @param appearance the axis selections to resolve against
     * @return the age / carried / sheared / shape / size / tint-resolved definition
     */
    public @NotNull Entity resolve(@NotNull EntityAppearance appearance) {
        // Variant fold (option-encoded coat / colour): a selected variant resolves against that option's
        // fully-built sub-definition, so every later axis (baby / size / tint) folds on top of the coat.
        // An absent or unknown option, and a non-variant model (empty variants map), keep the model
        // default coat.
        Entity definition = appearance.getVariant()
            .map(coat -> this.axes().variants().getOrDefault(coat, this))
            .orElse(this);
        EntityBuilder builder = definition.toBuilder();
        if (appearance.isBaby() && definition.axes().babyModel().isPresent()) {
            builder.model(definition.axes().babyModel().get())
                .overlays(gatedOverlays(definition.axes().babyOverlays(), appearance))
                .blockOverlays(List.of())
                .layers(new Layers(Optional.empty(), List.of(), definition.layers().markings(),
                    definition.layers().humanoidArmor()));
        } else {
            builder.overlays(gatedOverlays(definition.overlays(), appearance));
            // Selected bone toggles flip their bones' visibility (donkey/mule/llama chest reveal, goat
            // horns hide). Guarded to the non-baby path - the baby mesh has its own bones. The sheared axis
            // additionally activates the "sheared" toggle for entities that declare one (bogged drops its
            // mushrooms); entities whose sheared handling is overlay-only (sheep wool) declare no such
            // toggle and are left unchanged.
            Set<String> selectedToggles = appearance.getToggles();
            if (appearance.isSheared() && definition.boneToggles().containsKey("sheared")) {
                selectedToggles = new LinkedHashSet<>(selectedToggles);
                selectedToggles.add("sheared");
            }
            EntityModelData toggled = applyBoneToggles(definition, selectedToggles);
            if (toggled != null) builder.model(toggled);
            builder.blockOverlays(resolveBlockOverlays(definition, appearance));
            // The shape axis (tropical fish) swaps to the large body when the selected pattern's Shape is
            // large: the large mesh, tropical_b base texture, and the pattern overlays cloned onto the
            // large geometry (the pattern axis still picks the concrete overlay texture via texture_by). A
            // small/default pattern leaves the small body untouched.
            if (definition.axes().largeShape().isPresent()
                && appearance.getPattern().map(p -> p.shape() == TropicalFishPattern.Shape.LARGE).orElse(false)) {
                LargeShape large = definition.axes().largeShape().get();
                builder.model(large.model()).textureRef(large.textureRef()).overlays(large.overlays());
            }
            // The size axis (pufferfish) swaps to the selected size's distinct baked mesh. An unset size, or
            // the entity's default size (pufferfish large = the base mesh, absent from the map), leaves the
            // base model untouched.
            appearance.getSize().map(definition.axes().sizeModels()::get).ifPresent(builder::model);
            // The size axis (slime / magma_cube) instead multiplies rendererScale by the selected size's
            // factor. An unset / default size (scale 1.0, absent from the map) leaves rendererScale
            // untouched. The orthographic VANILLA_ISO parity path reads this off the resolved definition and
            // sizes a native pixels-per-block canvas from it, so a 2x size renders a 2x canvas and entity
            // rather than resolving self-similar to the default.
            appearance.getSize().map(definition.axes().sizeScales()::get)
                .ifPresent(scale -> builder.rendererScale(definition.rendererScale() * scale));
        }
        // The base_color axis (tropical fish) overrides the model base_tint with the selected dye; absent
        // (default) keeps the baked base_tint.
        appearance.tint(TintAxis.BASE).ifPresent(color -> builder.baseTintArgb(color.argb()));
        return builder.build();
    }

    /**
     * Drops the overlays an appearance does not activate: shearable overlays (the sheep wool) when
     * sheared - both the rendered geometry and its canvas-bounds contribution - and charged-only overlays
     * (the creeper energy swirl) unless the charged axis is set. A charged overlay renders only for a
     * lightning-struck entity. The list is only rebuilt when there is something to drop, so a list with no
     * shearable / charged overlay is returned as-is. Applied to the adult and the baby list alike, so a
     * gated pass that gains a baby form is gated on a baby too rather than drawing unconditionally.
     *
     * @param overlays the overlay list to gate
     * @param appearance the axis selections to gate against
     * @return the surviving overlays, or the given list itself when nothing drops
     */
    private static @NotNull List<OverlayLayer> gatedOverlays(@NotNull List<OverlayLayer> overlays, @NotNull EntityAppearance appearance) {
        boolean hasCharged = overlays.stream()
            .anyMatch(overlay -> overlay.gate().filter(AppearanceGate.ChargedGate.class::isInstance).isPresent());
        if (!appearance.isSheared() && !hasCharged) return overlays;
        return overlays.stream().filter(overlay -> rendersAtResolve(overlay, appearance)).toList();
    }

    /**
     * Whether an overlay survives the resolve-stage gate filter: an unconditional or tint-gated overlay
     * is kept here (a {@link AppearanceGate.TintedGate} is instead evaluated at render), while a flag /
     * charged gate that fails for this appearance drops the overlay (the sheared wool, the uncharged
     * creeper swirl).
     */
    private static boolean rendersAtResolve(@NotNull OverlayLayer overlay, @NotNull EntityAppearance appearance) {
        return overlay.gate()
            .filter(gate -> !(gate instanceof AppearanceGate.TintedGate))
            .map(gate -> gate.test(appearance))
            .orElse(true);
    }

    /**
     * Resolves the definition's block overlays against the appearance's carried selection. A
     * <b>fixed</b> overlay (mooshroom mushrooms, snow golem pumpkin) is kept unless {@code carried ==
     * "none"} drops it; a <b>selectable</b> overlay (enderman carried block, iron golem flower) is kept
     * only when a block is selected, with its block id replaced by that selection. The default (empty)
     * appearance therefore renders the fixed decorations and no selectable held block.
     */
    private static @NotNull List<BlockOverlayLayer> resolveBlockOverlays(@NotNull Entity definition, @NotNull EntityAppearance appearance) {
        if (definition.blockOverlays().isEmpty()) return definition.blockOverlays();
        Optional<String> selected = appearance.selectedCarriedBlock();
        boolean dropsFixed = appearance.dropsCarried();
        List<BlockOverlayLayer> out = new ArrayList<>(definition.blockOverlays().size());
        for (BlockOverlayLayer overlay : definition.blockOverlays()) {
            if (overlay.selectable())
                selected.ifPresent(id -> out.add(overlay.withBlockId(id)));
            else if (!dropsFixed)
                out.add(overlay);
        }
        return List.copyOf(out);
    }

    /**
     * Rebuilds the definition's model with the appearance's selected bone toggles flipped, or
     * {@code null} when no selected toggle applies (leaving the default model). A default-hidden toggle
     * re-adds its bones (chest); a default-visible toggle removes them (goat horns). Re-added bones'
     * parents are already present, so the kit resolves them by name; the rebuilt model grows / shrinks
     * the canvas bounds automatically.
     */
    private static @Nullable EntityModelData applyBoneToggles(@NotNull Entity definition, @NotNull Set<String> toggles) {
        if (toggles.isEmpty() || definition.boneToggles().isEmpty()) return null;
        LinkedHashMap<String, EntityModelData.Bone> bones = null;
        for (String toggle : toggles) {
            BoneToggle spec = definition.boneToggles().get(toggle);
            if (spec == null || spec.bones().isEmpty()) continue;
            if (bones == null) bones = new LinkedHashMap<>(definition.model().getBones());
            if (spec.defaultVisible())
                spec.bones().keySet().forEach(bones::remove);
            else
                bones.putAll(spec.bones());
        }
        if (bones == null) return null;
        return new EntityModelData(
            definition.model().getTextureSize(),
            definition.model().getInventoryYRotation(), Concurrent.adoptLinkedMap(bones), definition.model().isCull());
    }

    /**
     * The option-axis meshes and textures a render appearance selects among: {@code stateTextures},
     * {@code babyModel}, {@code babyOverlays}, {@code largeShape}, {@code sizeModels}, and
     * {@code sizeScales}.
     *
     * @param stateTextures alternate base textures keyed by behavioural state (wolf
     *     {@code wild}/{@code tame}/{@code angry}) plus the {@code baby} texture, populated for
     *     multi-state / ageable variant models; empty otherwise. The {@code wild} entry, when present,
     *     equals the definition's {@code textureRef}
     * @param babyModel the distinct baked baby mesh, used in place of the base model when the
     *     {@code age} axis selects {@code baby}; empty for entities with no dedicated baby mesh
     * @param babyOverlays the overlay passes materialised on the baby mesh (the villager biome robe, the
     *     trader llama's baby caparison), used in place of {@link Entity#overlays()} when the {@code age}
     *     axis selects {@code baby}; empty unless an overlay declares a baby form
     * @param largeShape the {@code shape} axis's large alternative (tropical fish): the large body mesh +
     *     {@code tropical_b} texture + pattern overlays cloned onto it; empty otherwise
     * @param sizeModels the {@code size} axis's non-default alternate meshes keyed by {@link Size}
     *     (pufferfish, salmon); the default size is the base model and absent here; empty for no-size-axis entities
     * @param sizeScales the {@code size} axis's non-default render scale factors keyed by {@link Size}
     *     (slime / magma_cube); the default size is scale {@code 1.0} and absent here; empty otherwise
     * @param variants the {@code variant} axis's option-encoded coat sub-definitions keyed by option
     *     (cow {@code temperate}/{@code cold}/{@code warm}, wolf coats, cat breeds), each a fully-built
     *     definition; the base definition IS the default option's build. Empty when {@code variant} is
     *     id-encoded (each coat a first-class
     *     pseudo-id) or the model has no variant axis. The render-time variant fold in
     *     {@link Entity#resolve} swaps to the selected
     *     option's sub-definition, and the group canvas union measures every option's silhouette
     * @param variantDefault the {@code variant} option the base definition was built from, empty for a
     *     model with no variant axis. The option maps carry every option including this one, so without
     *     the name there is no way to tell which of them the bare model already is
     * @param stateDefault the {@code state} option the base textures represent, empty for a model with no
     *     state axis
     * @param sizeDefault the {@code size} option the base mesh and unit scale represent, empty for a model
     *     with no size axis; {@code sizeModels} and {@code sizeScales} hold only the others
     */
    public record Axes(
        @NotNull Map<String, String> stateTextures,
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull List<OverlayLayer> babyOverlays,
        @NotNull Optional<LargeShape> largeShape,
        @NotNull Map<Size, EntityModelData> sizeModels,
        @NotNull Map<Size, Float> sizeScales,
        @NotNull Map<String, Entity> variants,
        @NotNull Optional<String> variantDefault,
        @NotNull Optional<String> stateDefault,
        @NotNull Optional<String> sizeDefault
    ) {}

    /**
     * The conditional decoration layers drawn over the base body ({@code collar}, {@code equipment},
     * {@code markings}), each gated at render on its appearance axis.
     *
     * @param collar the dyed-collar texture drawn on the body geometry and tinted by the collar colour
     *     (wolf, cat); empty for non-collar entities
     * @param equipment the saddle / body-armor overlays rendered when the {@code equipment} axis selects
     *     their slot; empty for entities with no equipment layer
     * @param markings whether the entity supports the horse {@code markings} axis (a same-geometry
     *     translucent overlay over the coat, textured by the selected
     *     {@link HorseMarking}); the default marking draws nothing
     * @param humanoidArmor the worn-armor shell this entity is dressed in (skeletons, zombies, piglins),
     *     joined from the {@code layers} armor row's geometry reference at load; empty for an entity
     *     vanilla arms with no {@code HumanoidArmorLayer}. Being armored IS carrying a shell, so a
     *     wearer whose mesh failed to resolve drops off the roster loudly rather than rendering a guess
     */
    public record Layers(
        @NotNull Optional<String> collar,
        @NotNull List<EquipmentOverlay> equipment,
        boolean markings,
        @NotNull Optional<HumanoidArmor> humanoidArmor
    ) {}

    /**
     * The worn-armor shell one humanoid is dressed in - the mesh plus the per-side growth each of the
     * two armor layers applies to it.
     *
     * <p>Vanilla never derives worn armor from the wearer's own model. It builds a handful of armor sets
     * and hands each renderer the one its subject wears, so a skeleton's narrow limbs and a giant's
     * scaled-up body dress in the very same boxes. Each set fans one base mesh out over the four
     * equipment slots at two deformations, which is the pair carried here: the leggings wear the inner
     * one, the other three slots the outer. The mesh itself is stored ungrown, so a cube's own
     * {@code CubeDeformation.extend} (a leg's {@code -0.1}, a helmet's second box) rides its
     * {@code grow} and is summed onto the slot's deformation at render.
     *
     * @param mesh the ungrown armor mesh, joined from the geometry store
     * @param innerGrow the per-side growth the leggings layer applies
     * @param outerGrow the per-side growth the helmet / chestplate / boots layer applies
     */
    public record HumanoidArmor(
        @NotNull EntityModelData mesh,
        @NotNull Vector3f innerGrow,
        @NotNull Vector3f outerGrow
    ) {}

    /**
     * One block-model overlay attached to an entity: a vanilla block (e.g. red mushroom block) rendered
     * at a specific transform on top of the entity body. Used by mooshroom (mushrooms on back / between
     * horns), enderman (carried block), iron golem (poppy), etc.
     *
     * <p>The {@code transforms} list is applied in order at render time, one push/pop scope per
     * block-overlay row (each row = one mushroom/flower/etc). Transforms operate in entity-local
     * coordinates - the block model's 0..1 unit cube is placed in the entity's frame after the transform
     * chain. Optionally pre-pended by an entity-bone pose ({@code attachedBone}) so head-attached
     * overlays (mooshroom's third mushroom between the horns) follow the head's runtime / bind-pose
     * rotation.
     *
     * @param blockId the block id to render (e.g. {@code "minecraft:red_mushroom_block"}); the documented
     *     default for a {@link #selectable} row (empty when the layer has no vanilla literal, as for the
     *     enderman carried block), always overridden at render by the caller's selection
     * @param attachedBone optional entity-bone whose pose stack pre-multiplies the transforms (e.g.
     *     {@code "head"} for the mooshroom horn-mushroom, {@code "right_arm"} for the iron golem flower).
     *     {@code null} when the overlay is positioned in the entity's root frame
     * @param transforms ordered list of {@code translate} / {@code rotate_y} / {@code rotate_x} /
     *     {@code scale} ops applied to the block model after the optional bone pose
     * @param selectable when {@code true} this overlay is a caller-selected held block (enderman carried
     *     block, iron golem flower) rather than an always-present body decoration (mooshroom mushrooms,
     *     snow golem pumpkin): it renders only when {@link EntityAppearance#selectedCarriedBlock()}
     *     supplies a block id, which replaces {@link #blockId}. The default (unselected) render draws no
     *     selectable overlay
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
     * {@code translate(F, F, F)} -> {@link Translate}, {@code mulPose(rotationDegrees(deg))} on the Y
     * axis -> {@link RotateY} / on the X axis -> {@link RotateX} / on the Z axis -> {@link RotateZ},
     * {@code scale(F, F, F)} -> {@link Scale}.
     *
     * <p>Sealed so each op knows how to {@link #appendTo(Matrix4f) append itself} to the chain, letting
     * the renderer compose a transform without a dispatch switch. Add a new op kind by adding a nested
     * record and updating the JSON parser.
     */
    public sealed interface TransformOp {

        /**
         * Post-multiplies this op onto {@code chain}, matching vanilla's {@code pose = pose * newOp} so
         * that, under the column-vector convention, the most-recently-appended op applies first to a
         * cube-local vertex.
         *
         * @param chain the accumulated block-unit chain
         * @return {@code chain} with this op appended
         */
        @NotNull Matrix4f appendTo(@NotNull Matrix4f chain);

        /**
         * Translation by {@code (x, y, z)} in entity-local units.
         */
        record Translate(float x, float y, float z) implements TransformOp {
            @Override
            public @NotNull Matrix4f appendTo(@NotNull Matrix4f chain) {
                return chain.translate(this.x, this.y, this.z);
            }
        }

        /**
         * Rotation around the Y axis by {@code degrees}.
         */
        record RotateY(float degrees) implements TransformOp {
            @Override
            public @NotNull Matrix4f appendTo(@NotNull Matrix4f chain) {
                return chain.rotateY((float) Math.toRadians(this.degrees));
            }
        }

        /**
         * Rotation around the X axis by {@code degrees} (the enderman carried block's {@code Axis.XP} tilt,
         * the iron golem flower's {@code Axis.XP} lay-flat).
         */
        record RotateX(float degrees) implements TransformOp {
            @Override
            public @NotNull Matrix4f appendTo(@NotNull Matrix4f chain) {
                return chain.rotateX((float) Math.toRadians(this.degrees));
            }
        }

        /**
         * Rotation around the Z axis by {@code degrees} (a {@code mulPose(rotationDegrees)} on
         * {@code Axis.ZP}). Vocabulary-only in 26.1 - no vanilla block-overlay layer emits a {@code rotate_z}
         * row - but present so a future one composes in the correct PoseStack order.
         */
        record RotateZ(float degrees) implements TransformOp {
            @Override
            public @NotNull Matrix4f appendTo(@NotNull Matrix4f chain) {
                return chain.rotateZ((float) Math.toRadians(this.degrees));
            }
        }

        /**
         * Per-axis scale {@code (x, y, z)}. Negative components flip the axis.
         */
        record Scale(float x, float y, float z) implements TransformOp {
            @Override
            public @NotNull Matrix4f appendTo(@NotNull Matrix4f chain) {
                return chain.scale(this.x, this.y, this.z);
            }
        }
    }

    /**
     * One overlay layer on an {@link Entity}: an independent geometry plus its own bundled texture
     * sub-path. Resolved from the tooling-emitted {@code overlays} array at load time.
     *
     * @param model the overlay's bone/cube tree, sharing the base model's coordinate frame so they
     *     co-register under the renderer's shared auto-fit transform
     * @param textureRef the bundled texture sub-path (without {@code .png}), or empty when the overlay
     *     should reuse the base entity's texture
     * @param emissive when {@code true} the overlay renders full-bright (unlit), mirroring vanilla Java's
     *     {@code RenderType.eyes} pattern, instead of shaded src-over. Tagged onto every triangle the
     *     overlay produces; the rasterizer keys off the per-triangle flag
     * @param tintArgb per-overlay multiplicative ARGB tint, mirroring vanilla's
     *     {@code coloredCutoutModelRender(..., color, ...)} colour argument (sheep wool colour,
     *     tropical-fish pattern colour). Defaults to {@code 0xFFFFFFFF} (white = no-op MULTIPLY)
     * @param skipBounds when {@code true} the overlay still renders but is excluded from the
     *     canvas-sizing bounds union - set for {@code skip_bounds=true} state-rendered decor layers the
     *     harness also skips (llama carpet), and for same-geometry overlays with no deformation of their
     *     own, whose silhouette the base mesh already covers
     * @param tintBy the render-axis token whose selected colour overrides {@link #tintArgb} at render
     *     (e.g. {@code "wool_color"} for the sheep wool, tinted by {@code EntityAppearance.woolColor}), or
     *     empty when the tint is fixed at {@link #tintArgb}
     * @param textureBy the render-axis token whose selection overrides {@link #textureRef} at render
     *     (e.g. {@code "pattern"} for the tropical-fish pattern, sourced from
     *     {@code EntityAppearance.pattern}), or empty when the overlay texture is fixed at
     *     {@link #textureRef}
     * @param blend the colour-composition mode the rasterizer composites this overlay with -
     *     {@link BlendMode#NORMAL} source-over (the default; also what a {@code translucent} node maps to,
     *     since the slime shell's translucency is in its texture alpha) or {@link BlendMode#ADD} for the
     *     additive energy-swirl glow ({@code blend: additive}). Parsed from the overlay's optional
     *     {@code blend} node, orthogonal to {@link #emissive}
     * @param alpha the per-fragment opacity multiplier in {@code [0, 1]} from the overlay's optional
     *     {@code alpha} node, multiplied into the sampled texel's alpha before the {@link #blend}
     *     composite. {@code 1.0} (no-op) except for an overlay carrying an explicit multiplier (the warden
     *     pulsating-spots glow at {@code 0.25}) - a fractional layer opacity that cannot ride the tint's
     *     alpha byte (the MULTIPLY tint blend preserves the texel alpha)
     * @param gate the render condition parsed from the overlay's {@code when} object (the sheep wool
     *     {@code sheared} flag, the wool undercoat {@code tinted} axis, the creeper {@code charged} axis),
     *     or empty when the overlay renders unconditionally
     * @param noHatModel the alternate mesh a suppressed pass draws instead - this overlay's mesh with the
     *     head-subtree cubes emptied (the villager robe pass under a hat-bearing profession), or empty
     *     when the overlay has no alternate
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
        @NotNull Optional<AppearanceGate> gate,
        @NotNull Optional<EntityModelData> noHatModel
    ) {}

    /**
     * One equipment overlay on an {@link Entity}: a saddle / body-armor mesh (its own baked geometry)
     * rendered on the body only when the {@code equipment} render axis selects its {@link #slot}. Unlike
     * an always-on {@link OverlayLayer}, the texture is chosen at render from the axis-selected material:
     * {@link #assetFor} names the equipment asset holding that material's layers, which the renderer
     * resolves and composites under {@link #layerType}. Sourced by {@link EntityModelLoader} from the
     * model form's {@code when.equipment}-gated {@code layers}.
     *
     * @param slot the equipment slot this overlay is gated on ({@code saddle} / {@code body})
     * @param model the equipment mesh, resolved from the layer's baked {@code geometry_ref}
     * @param layerType the render layer whose texture subdir this overlay's layers sit under
     * @param materialAssets the equipment asset id per selectable material - mostly the material's own
     *     name, but the llama's {@code white} carpet lives in {@code minecraft:white_carpet} and every
     *     saddle layer shares {@code minecraft:saddle}, so the mapping is data rather than convention
     * @param defaultMaterial the material substituted when the slot is selected without one
     *     ({@code saddle} for a saddle, {@code leather} for horse body armor)
     */
    public record EquipmentOverlay(
        @NotNull String slot,
        @NotNull EntityModelData model,
        @NotNull LayerType layerType,
        @NotNull Map<String, ResourceId> materialAssets,
        @NotNull String defaultMaterial
    ) {
        /**
         * Resolves the equipment asset id for a selected material; falls back to
         * {@link #defaultMaterial} when {@code material} is blank (the slot selected without an
         * explicit material). Empty when the material names no asset of this layer, which renders
         * nothing rather than substituting a stand-in texture.
         *
         * @param material the axis-selected material, or blank to use {@link #defaultMaterial}
         * @return the equipment asset id, or empty when the material is unknown to this layer
         */
        public @NotNull Optional<ResourceId> assetFor(@NotNull String material) {
            return Optional.ofNullable(this.materialAssets.get(material.isBlank() ? this.defaultMaterial : material));
        }
    }

    /**
     * The {@code shape} axis's large-body alternative (tropical fish), resolved eagerly at load: the
     * large body mesh, its base texture, and the pattern overlays materialised onto the large geometry.
     * the entity definition resolver swaps these in wholesale when the selected pattern's {@code Shape} is
     * large.
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
     * A named bone-visibility toggle resolved at load: the geometry {@link EntityModelData.Bone bones} it
     * flips (kept by name so the entity definition resolver can add or remove them) plus their default
     * visibility. {@code defaultVisible = false} (donkey chest) - the bones are stripped from the default
     * model and the toggle re-adds them; {@code defaultVisible = true} (goat horns) - the bones render by
     * default and the toggle removes them.
     *
     * @param bones the toggle's bones keyed by name
     * @param defaultVisible whether the bones render by default (true = toggle hides; false = toggle reveals)
     */
    public record BoneToggle(
        @NotNull Map<String, EntityModelData.Bone> bones,
        boolean defaultVisible
    ) {}

}
