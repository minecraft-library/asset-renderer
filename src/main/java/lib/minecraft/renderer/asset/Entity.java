package lib.minecraft.renderer.asset;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.appearance.AppearanceGate;
import lib.minecraft.renderer.asset.appearance.CopperWeathering;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.appearance.TextureAxis;
import lib.minecraft.renderer.asset.appearance.TintAxis;
import lib.minecraft.renderer.asset.appearance.TropicalFishPattern;
import lib.minecraft.renderer.asset.appearance.Villager;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.raster.PassDeclaration;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector2f;
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
 * @param model the parsed bone/cube tree (shared across all entities naming the same {@code geometry}
 *     coordinate)
 * @param overlays additional geometry/texture pairs rendered on top of the base model in declared
 *     order; populated by the bytecode-derived overlay scan ({@code EntityOverlayResolver}: emissive
 *     eyes, profession layers, pattern layers, equipment-driven decor layers). A baby render draws
 *     {@link Axes#babyOverlays()} instead - the passes materialised on the baby mesh
 * @param blockOverlays vanilla-block-shaped overlays rendered on top of the entity body (mooshroom
 *     mushrooms, iron golem poppy) at a transform-stack-applied position
 * @param baseTintArgb per-entity multiplicative tint applied to the base mesh, mirroring
 *     {@code LivingEntityRenderer.getModelTint(state)}. Defaults to {@code 0xFFFFFFFF} (white = no-op
 *     MULTIPLY)
 * @param setupYawAddend yaw rotation in degrees the vanilla renderer's {@code setupRotations} folds
 *     into the body rotation it delegates - the leading constant y turn of the pose table's
 *     {@code renderers} row, crossed back to degrees at index build. {@code ShulkerRenderer} is the
 *     canonical case ({@code +180.0F}); every other vanilla renderer passes {@code bodyRot} through
 *     and lands at {@code 0}. The renderer adds this to the user-supplied yaw before applying the
 *     iso pose - it is a facing fact, so it reaches every render mode where a pose container step
 *     never reaches BIND
 * @param rendererScale per-entity render-time scale extracted by {@code EntityRendererScaleResolver};
 *     defaults to {@code 1f} (identity)
 * @param axes the option-axis mesh / texture selections a render appearance chooses among (state
 *     textures, baby mesh, large shape, size meshes / scales) - see {@link Axes}
 * @param layers the conditional decoration layers drawn over the base body (equipment, worn armor), each
 *     gated at render on its appearance axis - see {@link Layers}
 * @param members the self-inclusive canvas-group membership - every entity id that shares this
 *     entity's group-union fit window ({@code EntityOptions.FitMode.GROUP_BOUNDS}), the SAME list on
 *     each member of the group; empty for a singleton entity with no group
 * @param pose what this entity's model does to its bones before it is drawn, joined from the model
 *     class the {@link #model} coordinate is headed with. A model that poses nothing and one whose
 *     pose could not be read are both {@link EntityPose#isReadable() distinguishable} here, because
 *     they render identically and only one of them is right
 */
@ClassBuilder
public record Entity(
    @NotNull ResourceId id,
    @NotNull EntityModelData model,
    @NotNull List<OverlayLayer> overlays,
    @NotNull List<BlockOverlayLayer> blockOverlays,
    int baseTintArgb,
    float setupYawAddend,
    float rendererScale,
    @NotNull Axes axes,
    @NotNull Layers layers,
    @NotNull List<String> members,
    @NotNull EntityPose pose
) {

    /**
     * The state every definition is in before a selection names another - the option the state axis
     * declares, and so the one {@link #textureRef()} reads.
     *
     * <p>It is vanilla's own word: the wolf's texture table keys its base coat {@code wild} beside
     * {@code tame} and {@code angry}, and thirteen further variant families key theirs the same way
     * with no second state to tell it apart from. A subject with no states at all is in this one
     * rather than in none, which is what lets every base texture be read the same way.
     */
    public static final @NotNull String BASE_STATE = "wild";

    /**
     * Normalises a never-set {@link #members} to an empty (singleton) list and a never-set
     * {@link #pose} to the pose of a model that poses nothing, so callers can omit either.
     */
    public Entity {
        members = members == null ? List.of() : members;
        pose = pose == null ? EntityPose.NONE : pose;
    }

    /**
     * Returns a copy with no {@link #blockOverlays() block overlays}, for the {@code carried} render
     * toggle (a sheared snow golem, an empty-handed enderman) - dropping both the rendered geometry and
     * its canvas-bounds contribution.
     *
     * @return an otherwise-identical definition with an empty block-overlay list
     */
    public @NotNull Entity withoutBlockOverlays() {
        return mutate().blockOverlays(List.of()).build();
    }

    /**
     * The vanilla {@code textures/entity/} sub-path this definition draws with by default (without the
     * {@code .png} suffix), resolved at render time via
     * {@link RendererContext#resolveTexture(String) resolveTexture} as {@code minecraft:entity/<ref>}.
     *
     * <p>A derived view over the state axis rather than a component of its own: the base texture is the
     * option that axis {@link Axis#declared() declares}, so it is one of the states rather than a
     * fourth thing beside them, and a caller that has resolved a state has already resolved this.
     *
     * @return the default texture ref, or empty when the definition names none
     */
    public @NotNull Optional<String> textureRef() {
        return this.axes.state().declared().flatMap(this.axes.state()::select);
    }

    /**
     * The worn-armor shell this entity is dressed in - a derived view over {@link #layers()}
     * (delegating to {@link Layers#humanoidArmor()}), so no top-level component stores it. Empty for an
     * entity vanilla arms with no {@code HumanoidArmorLayer}, which is what gates the worn-armor render
     * feature.
     *
     * @return the armor shell, or empty when this entity wears none
     */
    public @NotNull Optional<Shell> humanoidArmor() {
        return this.layers.humanoidArmor();
    }

    /**
     * Folds this definition's render-axis selections for the given appearance into a single resolved
     * {@link Entity} the renderer iterates unconditionally, with no scattered {@code !baby} gates - the
     * render-time policy the reader deliberately leaves off the loaded data.
     *
     * <p>The worn-armor shell resolves ahead of them all and outside the fork, against whichever
     * selection the wearer's own second shell names.
     *
     * <p>The nine axis semantics apply in a fixed short-circuit order: (1) a baby swaps in the baby mesh,
     * substitutes the {@link Axes#babyOverlays() baby overlay list} for the adult one, and DROPS block
     * overlays / equipment - each carries adult geometry that would render adult-sized around
     * the smaller baby body, which is exactly why the overlay passes are a distinct list rather than the
     * adult one, and the substituted list is empty unless an overlay declares a baby form, so a pass with
     * none drops out structurally - and the whole non-baby branch is skipped bar the overlay gate filter
     * (2), which runs over whichever list is in play; else (2) sheared drops the wool overlay, charged
     * gates the swirl and an unworn collar drops its row; (3) the sheared axis additionally
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
    public @NotNull Entity resolve(@NotNull AppearanceOptions appearance) {
        // Variant fold (option-encoded coat / colour): a selected variant resolves against that option's
        // fully-built sub-definition, so every later axis (baby / size / tint) folds on top of the coat.
        // An absent or unknown option, and a non-variant model (empty variants map), keep the model
        // default coat.
        Entity definition = appearance.getVariant()
            .flatMap(coat -> this.axes().variant().select(coat))
            .orElse(this);
        Builder builder = definition.mutate();
        // The worn shell resolves ahead of the age fork and outside it, because the axis that
        // selects a wearer's second shell is the wearer's own - six swap on age and the armor stand
        // on size - and vanilla picks the set off the flag alone rather than off the body mesh.
        Optional<Shell> armor = definition.layers()
            .humanoidArmor()
            .map(shell -> shell.forAppearance(appearance));
        if (appearance.isBaby() && definition.axes().babyModel().isPresent()) {
            // The pose swaps WITH the mesh and never without it. A baby is a different model class,
            // so it is a different pose, and two of the families that pose at all are posed through
            // the baby class alone - carrying the adult's pose onto a baby mesh would animate bones
            // by the names the adult happens to share.
            builder.model(definition.axes().babyModel().get())
                .pose(definition.axes().babyPose().orElse(EntityPose.NONE))
                .overlays(gatedOverlays(definition.axes().babyOverlays(), appearance))
                .blockOverlays(List.of())
                .layers(new Layers(List.of(), armor));
        } else {
            builder.overlays(gatedOverlays(definition.overlays(), appearance));
            // Selected bone toggles flip their bones' visibility (donkey/mule/llama chest reveal, goat
            // horns hide). Guarded to the non-baby path - the baby mesh has its own bones. The sheared axis
            // additionally activates the "sheared" toggle for entities that declare one (bogged drops its
            // mushrooms); entities whose sheared handling is overlay-only (sheep wool) declare no such
            // toggle and are left unchanged.
            Set<String> selectedToggles = appearance.getToggles();
            // Named unconditionally rather than gated on the subject declaring one: a mesh whose
            // bones name no "sheared" selection is left alone by the flip anyway, so asking first
            // would be a second roster of which subjects have the toggle.
            if (appearance.isSheared()) {
                selectedToggles = new LinkedHashSet<>(selectedToggles);
                selectedToggles.add("sheared");
            }
            EntityModelData flipped = toggled(definition.model(), selectedToggles);
            if (flipped != definition.model()) builder.model(flipped);
            builder.blockOverlays(resolveBlockOverlays(definition, appearance));
            // The shape axis (tropical fish) swaps to the large body when the selected pattern's Shape is
            // large: the large mesh, tropical_b base texture, and the pattern overlays cloned onto the
            // large geometry (the pattern axis still picks the concrete overlay texture via texture_by). A
            // small/default pattern leaves the small body untouched.
            if (definition.axes().largeShape().isPresent()
                && appearance.getPattern().map(p -> p.shape() == TropicalFishPattern.Shape.LARGE).orElse(false)) {
                LargeShape large = definition.axes().largeShape().get();
                builder.model(large.model())
                    .overlays(large.overlays())
                    .axes(drawing(definition.axes(), large.textureRef()));
            }
            // The size axis swaps to the selected size's form, which carries whichever of the two
            // vanilla mechanisms its subject uses: a distinct baked mesh (armor stand, pufferfish,
            // salmon) or the base mesh at a multiplied render scale (slime, magma_cube). Both are read
            // off the form because a subject uses one or the other and the form already holds the
            // resolved value - the selected size's own mesh, and its own already-multiplied scale.
            // Selecting the declared size resolves to a form equal to the base, so it changes nothing.
            //
            // The orthographic VANILLA_ISO parity path reads the scale off the resolved definition and
            // sizes a native pixels-per-block canvas from it, so a 2x size renders a 2x canvas and
            // entity rather than resolving self-similar to the default.
            appearance.getSize().flatMap(definition.axes().size()::select).ifPresent(form -> {
                builder.model(form.model());
                builder.rendererScale(form.rendererScale());
            });
            // A layer's own toggles ride the same selection the wearer's do, so an equipped saddle
            // draws its reins for a ridden subject and its chest panniers for a chested one.
            builder.layers(new Layers(toggledEquipment(definition.layers().equipment(), selectedToggles), armor));
        }
        // The base_color axis (tropical fish) overrides the model base_tint with the selected dye; absent
        // (default) keeps the baked base_tint.
        appearance.tint(TintAxis.BASE).ifPresent(color -> builder.baseTintArgb(color.argb()));
        return builder.build();
    }

    /**
     * The same axes drawing a different base texture - the state axis with its declared option
     * remapped, for a form that swaps the body texture along with the mesh.
     *
     * @param axes the axes to redraw
     * @param ref the texture the declared state should select, or empty to leave the axes alone
     * @return the axes selecting {@code ref} for the declared state
     */
    private static @NotNull Axes drawing(@NotNull Axes axes, @NotNull Optional<String> ref) {
        if (ref.isEmpty()) return axes;
        Axis<String, String> state = axes.state();
        String key = state.declared().orElse(BASE_STATE);
        LinkedHashMap<String, String> options = new LinkedHashMap<>(state.options());
        options.put(key, ref.get());
        return new Axes(axes.babyModel(), axes.babyPose(), axes.babyOverlays(), axes.largeShape(),
            new Axis<>(Map.copyOf(options), Optional.of(key)), axes.size(), axes.variant());
    }

    /**
     * Drops the overlays an appearance does not activate - the sheep wool once sheared, the creeper
     * swirl unless charged, the collar while none is worn - both the rendered geometry and its
     * canvas-bounds contribution. The list is only rebuilt when a resolve-stage gate is present, so
     * a list carrying none is returned as-is. Applied to the adult and the baby list alike, so a
     * gated pass that gains a baby form is gated on a baby too rather than drawing unconditionally.
     *
     * @param overlays the overlay list to gate
     * @param appearance the axis selections to gate against
     * @return the surviving overlays, or the given list itself when nothing drops
     */
    private static @NotNull List<OverlayLayer> gatedOverlays(@NotNull List<OverlayLayer> overlays, @NotNull AppearanceOptions appearance) {
        boolean gated = overlays.stream()
            .anyMatch(overlay -> overlay.gate()
                .filter(gate -> !(gate instanceof AppearanceGate.TintedGate))
                .isPresent());
        if (!gated) return overlays;
        return overlays.stream().filter(overlay -> rendersAtResolve(overlay, appearance)).toList();
    }

    /**
     * Whether an overlay survives the resolve-stage gate filter: an unconditional or tint-gated overlay
     * is kept here (a {@link AppearanceGate.TintedGate} is instead evaluated at render), while a flag /
     * charged gate that fails for this appearance drops the overlay (the sheared wool, the uncharged
     * creeper swirl).
     */
    private static boolean rendersAtResolve(@NotNull OverlayLayer overlay, @NotNull AppearanceOptions appearance) {
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
    private static @NotNull List<BlockOverlayLayer> resolveBlockOverlays(@NotNull Entity definition, @NotNull AppearanceOptions appearance) {
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
     * The mesh with every bone a selected toggle names drawing the other way, or the mesh itself
     * when no selection reaches one of its bones.
     *
     * <p>Which way a toggle points comes off the bone it moves - a donkey's chest rests undrawn and
     * its {@code chest} selection draws it, where a goat's horns rest drawn and its {@code horn}
     * selection hides them - so nothing is captured before the mesh is built, and a re-drawn bone
     * keeps the position its mesh authored it at rather than landing after everything that draws.
     *
     * <p>One arithmetic for the wearer and for what it wears: a saddle's own mesh names its own
     * selections, and a selection reaches both.
     *
     * @param model the mesh to flip
     * @param toggles the appearance's selected toggle names
     * @return the flipped mesh, or {@code model} when no selection names one of its bones
     */
    private static @NotNull EntityModelData toggled(
        @NotNull EntityModelData model, @NotNull Set<String> toggles) {

        if (toggles.isEmpty()) return model;
        LinkedHashMap<String, EntityModelData.Bone> bones = null;
        for (Map.Entry<String, EntityModelData.Bone> entry : model.getBones().entrySet()) {
            EntityModelData.Bone bone = entry.getValue();
            String toggle = bone.getToggle();
            if (toggle == null || !toggles.contains(toggle)) continue;
            if (bones == null) bones = new LinkedHashMap<>(model.getBones());
            bones.put(entry.getKey(), bone.withVisible(!bone.isVisible()));
        }
        if (bones == null) return model;
        return new EntityModelData(model.getTextureSize(), model.getInventoryYRotation(),
            Concurrent.adoptLinkedMap(bones), model.isCull());
    }

    /**
     * The equipment overlays with their selected toggles flipped, or the given list when nothing
     * moves.
     *
     * @param equipment the resolved definition's equipment overlays
     * @param toggles the appearance's selected toggle names
     * @return the overlays drawing what the selection asks for
     */
    private static @NotNull List<EquipmentOverlay> toggledEquipment(
        @NotNull List<EquipmentOverlay> equipment, @NotNull Set<String> toggles) {

        if (toggles.isEmpty() || equipment.isEmpty()) return equipment;
        List<EquipmentOverlay> out = new ArrayList<>(equipment.size());
        boolean moved = false;
        for (EquipmentOverlay overlay : equipment) {
            EquipmentOverlay flipped = overlay.withToggles(toggles);
            moved |= flipped != overlay;
            out.add(flipped);
        }
        return moved ? List.copyOf(out) : equipment;
    }

    /**
     * One option axis: what each option selects, and which option the bare definition already is.
     *
     * <p><b>The declared option is one of the options.</b> Every axis carries an entry for the option
     * its base row was built from, so a caller holding an axis can say which of them it is looking at
     * rather than inferring it from the data. That is what a reference key needs in order not to name
     * one appearance two ways, and it is why the size axis carries its default beside the alternates
     * where the shipped table lists only the others.
     *
     * <p>Empty is the honest shape for a definition with no such axis: no options and no declared
     * one, so every read answers absent rather than a value nothing selected. The state axis is the
     * exception and is never empty - a subject with no alternate textures is still in
     * {@link #BASE_STATE} rather than in no state, which is what lets one lookup answer for every
     * base texture in the corpus.
     *
     * @param <K> the option key - a name for the state and variant axes, {@link Size} for the size one
     * @param <V> what an option selects
     * @param options every option, including the declared one
     * @param declared the option the bare definition already is, empty when the definition has no
     *     such axis
     */
    public record Axis<K, V>(@NotNull Map<K, V> options, @NotNull Optional<K> declared) {

        /** An axis a definition does not carry, which selects nothing and declares nothing. */
        public static <K, V> @NotNull Axis<K, V> none() {
            return new Axis<>(Map.of(), Optional.empty());
        }

        /**
         * What one option selects, or empty where the axis does not carry it.
         *
         * @param option the selected option
         * @return what it selects, or empty when the axis names no such option
         */
        public @NotNull Optional<V> select(@NotNull K option) {
            return Optional.ofNullable(this.options.get(option));
        }

        /**
         * Whether one option is the one the bare definition already is.
         *
         * @param option the selected option
         * @return whether selecting it changes nothing
         */
        public boolean isDeclared(@NotNull K option) {
            return this.declared.equals(Optional.of(option));
        }

    }

    /**
     * The option-axis meshes and textures a render appearance selects among: the {@code state},
     * {@code size} and {@code variant} axes, the baby mesh and its pose and overlays, and the
     * {@code shape} axis's large alternative.
     *
     * @param babyPose the pose of the baby mesh's own model class, swapped in beside
     *     {@code babyModel} rather than derived from it - two of the families that pose at all are
     *     posed through their baby coordinate ALONE, so a single pose per entity could not say which
     *     of the two ages it was about. Empty when the family has no distinct baby mesh
     * @param babyModel the distinct baked baby mesh, used in place of the base model when the
     *     {@code age} axis selects {@code baby}; empty for entities with no dedicated baby mesh
     * @param babyOverlays the overlay passes materialised on the baby mesh (the villager biome robe, the
     *     trader llama's baby caparison), used in place of {@link Entity#overlays()} when the {@code age}
     *     axis selects {@code baby}; empty unless an overlay declares a baby form
     * @param largeShape the {@code shape} axis's large alternative (tropical fish): the large body mesh +
     *     {@code tropical_b} texture + pattern overlays cloned onto it; empty otherwise
     * @param state every base texture this definition can draw, keyed by the behavioural state that
     *     selects it (wolf {@code wild}/{@code tame}/{@code angry}) plus the {@code baby} texture.
     *     <b>The one axis every definition carries</b>: a subject with no alternates still names the
     *     state it is in, {@link #BASE_STATE}, so the default texture is a state like any other and
     *     {@link #textureRef()} reads it there rather than beside it
     * @param size the {@code size} axis's forms keyed by {@link Size}, each a sub-definition carrying
     *     what that size changes - its own baked mesh (armor stand, pufferfish, salmon) or the base
     *     mesh at a multiplied render scale (slime, magma_cube). Vanilla scales one at the mesh and
     *     the other at the render and the two are not interchangeable, so a form carries whichever
     *     its subject uses and the render reads both off it
     * @param variant the {@code variant} axis's option-encoded coat sub-definitions keyed by option
     *     (cow {@code temperate}/{@code cold}/{@code warm}, wolf coats, cat breeds), each a fully-built
     *     definition; the base definition IS the declared option's build. Empty when {@code variant} is
     *     id-encoded (each coat a first-class
     *     pseudo-id) or the model has no variant axis. The render-time variant fold in
     *     {@link Entity#resolve} swaps to the selected
     *     option's sub-definition, and the group canvas union measures every option's silhouette
     */
    public record Axes(
        @NotNull Optional<EntityModelData> babyModel,
        @NotNull Optional<EntityPose> babyPose,
        @NotNull List<OverlayLayer> babyOverlays,
        @NotNull Optional<LargeShape> largeShape,
        @NotNull Axis<String, String> state,
        @NotNull Axis<Size, Entity> size,
        @NotNull Axis<String, Entity> variant
    ) {}

    /**
     * The conditional decoration layers drawn over the base body ({@code equipment}, worn armor),
     * each gated at render on its appearance axis.
     *
     * @param equipment the saddle / body-armor overlays rendered when the {@code equipment} axis selects
     *     their slot; empty for entities with no equipment layer
     * @param humanoidArmor the worn-armor shell this entity is dressed in (skeletons, zombies, piglins),
     *     joined from the {@code layers} armor row's geometry reference at load; empty for an entity
     *     vanilla arms with no {@code HumanoidArmorLayer}. Being armored IS carrying a shell, so a
     *     wearer whose mesh failed to resolve drops off the roster loudly rather than rendering a guess
     */
    public record Layers(
        @NotNull List<EquipmentOverlay> equipment,
        @NotNull Optional<Shell> humanoidArmor
    ) {}

    /**
     * One block-model overlay attached to an entity: a vanilla block (e.g. red mushroom block) rendered
     * at a specific transform on top of the entity body. Used by mooshroom (mushrooms on back / between
     * horns), enderman (carried block), iron golem (poppy), etc.
     *
     * <p>{@link #transform} places the block model in entity-local coordinates: the block model's 0..1
     * unit cube is positioned in the entity's frame by it, optionally pre-multiplied by an entity-bone
     * pose ({@code attachedBone}) so head-attached overlays (mooshroom's third mushroom between the
     * horns) follow the head's runtime / bind-pose rotation. One push/pop scope per block-overlay row -
     * each row is one mushroom / flower.
     *
     * @param blockId the block id to render (e.g. {@code "minecraft:red_mushroom_block"}); the documented
     *     default for a {@link #selectable} row (empty when the layer has no vanilla literal, as for the
     *     enderman carried block), always overridden at render by the caller's selection
     * @param attachedBone optional entity-bone whose pose stack pre-multiplies the transform (e.g.
     *     {@code "head"} for the mooshroom horn-mushroom, {@code "right_arm"} for the iron golem flower).
     *     {@code null} when the overlay is positioned in the entity's root frame
     * @param transform the block-unit placement, in vanilla's {@code PoseStack} composition: the product
     *     of the {@code translate} / {@code rotate_x} / {@code rotate_y} / {@code rotate_z} /
     *     {@code scale} ops the layer's shipped row declares, post-multiplied in declared order so that
     *     under the column-vector convention the last-declared op applies first to a cube-local vertex
     * @param selectable when {@code true} this overlay is a caller-selected held block (enderman carried
     *     block, iron golem flower) rather than an always-present body decoration (mooshroom mushrooms,
     *     snow golem pumpkin): it renders only when {@link AppearanceOptions#selectedCarriedBlock()}
     *     supplies a block id, which replaces {@link #blockId}. The default (unselected) render draws no
     *     selectable overlay
     */
    public record BlockOverlayLayer(
        @NotNull String blockId,
        @Nullable String attachedBone,
        @NotNull Matrix4f transform,
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
            return new BlockOverlayLayer(newBlockId, this.attachedBone, this.transform, this.selectable);
        }
    }

    /**
     * The entity texture prefix (the first path segment of the definition's {@code texture_ref},
     * e.g. {@code villager/villager} -&gt; {@code villager}) prepended to the villager
     * profession-layer overlays' prefix-relative sub-paths, so one shared {@link Villager}
     * vocabulary serves the villager and the zombie villager. The empty string when no texture ref
     * is present.
     *
     * @return the texture prefix, or the empty string
     */
    public @NotNull String texturePrefix() {
        return textureRef().map(ref -> {
            int slash = ref.indexOf('/');
            return slash < 0 ? ref : ref.substring(0, slash);
        }).orElse("");
    }

    /**
     * The baby texture ref when this resolved definition renders the baby mesh - the baby mesh has
     * its own UV layout, so it binds the matching {@code <variant>_baby} texture carried in
     * {@link Axes#stateTextures()} under {@code "baby"}. Empty when the render is not a baby, the
     * entity has no baby mesh, or no baby texture is present, so a caller falls through to the
     * weathering / state / default texture.
     *
     * @param appearance the axis selections to resolve against
     * @return the baby texture ref, or empty
     */
    public @NotNull Optional<String> babyTextureRef(@NotNull AppearanceOptions appearance) {
        if (!appearance.isBaby() || this.axes.babyModel().isEmpty()) return Optional.empty();
        return this.axes.state().select("baby");
    }

    /**
     * The copper golem's weathered body base texture ref, when this definition supports weathering
     * (it carries a {@code texture_by: weathering} eye overlay) and a
     * non-{@link CopperWeathering#UNAFFECTED} state is chosen; empty otherwise, so a caller falls
     * back to the default {@link #textureRef}, which is the {@code UNAFFECTED} texture. Keeps the
     * default (unweathered) render unchanged.
     *
     * @param appearance the axis selections to resolve against
     * @return the weathered base texture ref, or empty
     */
    public @NotNull Optional<String> weatheringBaseRef(@NotNull AppearanceOptions appearance) {
        if (appearance.getWeathering() == CopperWeathering.UNAFFECTED) return Optional.empty();
        boolean supportsWeathering = this.overlays.stream()
            .anyMatch(o -> o.textureBy().filter(TextureAxis.WEATHERING::equals).isPresent());
        return supportsWeathering ? Optional.of(appearance.getWeathering().baseTexture()) : Optional.empty();
    }

    /**
     * The state-specific texture ref when {@link AppearanceOptions#getState()} names one this
     * definition carries; empty otherwise, so a caller falls back to the default
     * {@link #textureRef}. The default {@code wild} state resolves to the same path as
     * {@code texture_ref}, so an unset or {@code wild} state leaves the render unchanged.
     *
     * @param appearance the axis selections to resolve against
     * @return the state texture ref, or empty
     */
    public @NotNull Optional<String> stateTextureRef(@NotNull AppearanceOptions appearance) {
        return appearance.getState().flatMap(this.axes.state()::select);
    }

    /**
     * One overlay layer on an {@link Entity}: an independent geometry plus its own bundled texture
     * sub-path. Resolved from the tooling-emitted {@code overlays} array at load time.
     *
     * @param model the overlay's bone/cube tree, sharing the base model's coordinate frame so they
     *     co-register under the renderer's shared auto-fit transform
     * @param textureRef the bundled texture sub-path (without {@code .png}), or empty when the overlay
     *     should reuse the base entity's texture
     * @param pass what vanilla declared about the pass this overlay is submitted through - full-bright
     *     shading, colour composition, opacity multiplier, depth write and quad sort - parsed from the
     *     overlay's optional {@code pipeline} node and tagged onto every triangle the overlay produces.
     *     {@link PassDeclaration#DEFAULT} for an un-annotated overlay
     * @param tintArgb per-overlay multiplicative ARGB tint, mirroring vanilla's
     *     {@code coloredCutoutModelRender(..., color, ...)} colour argument (sheep wool colour,
     *     tropical-fish pattern colour). Defaults to {@code 0xFFFFFFFF} (white = no-op MULTIPLY)
     * @param skipBounds when {@code true} the overlay still renders but is excluded from the
     *     canvas-sizing bounds union - set for {@code skip_bounds=true} state-rendered decor layers the
     *     harness also skips (llama carpet), and for same-geometry overlays with no deformation of their
     *     own, whose silhouette the base mesh already covers
     * @param tintBy the tint axis whose selected colour overrides {@link #tintArgb} at render,
     *     resolved from the row's {@code tint_by} token at load
     *     (e.g. {@code "wool_color"} for the sheep wool, tinted by {@code AppearanceOptions.woolColor}), or
     *     empty when the tint is fixed at {@link #tintArgb}
     * @param textureBy the texture axis whose selection overrides {@link #textureRef} at render
     *     ({@link TextureAxis#PATTERN} for the tropical-fish pattern, sourced from
     *     {@code AppearanceOptions.pattern}), resolved from the row's {@code texture_by} token at
     *     load, or empty when the overlay texture is fixed at {@link #textureRef}
     * @param gate the render condition parsed from the overlay's {@code when} object (the sheep wool
     *     {@code sheared} flag, the wool undercoat {@code tinted} axis, the creeper {@code charged} axis),
     *     or empty when the overlay renders unconditionally
     * @param noHatModel the alternate mesh a suppressed pass draws instead - this overlay's mesh with the
     *     head-subtree cubes emptied (the villager robe pass under a hat-bearing profession), or empty
     *     when the overlay has no alternate
     * @param pose what this overlay's own model does to its bones before it is drawn. A layer poses its
     *     mesh with its own model class rather than borrowing the wearer's, so a pass carries a pose of
     *     its own - and an overlay sharing the body's mesh shares the body's pose with it, or the two
     *     would part company on a subject that moves
     * @param textureScroll the fraction of the sheet this pass's render type translates its texture by
     *     each tick, or empty where it translates none. A property of the render TYPE rather than of
     *     the mesh - vanilla builds it into the pipeline the layer submits through, so it moves the
     *     sample point and never the geometry, which is why the breeze's silhouette holds still while
     *     its wind turns
     */
    public record OverlayLayer(
        @NotNull EntityModelData model,
        @NotNull Optional<String> textureRef,
        @NotNull PassDeclaration pass,
        int tintArgb,
        boolean skipBounds,
        @NotNull Optional<TintAxis> tintBy,
        @NotNull Optional<TextureAxis> textureBy,
        @NotNull Optional<AppearanceGate> gate,
        @NotNull Optional<EntityModelData> noHatModel,
        @NotNull EntityPose pose,
        @NotNull Optional<Vector2f> textureScroll
    ) {

        /**
         * A pass whose render type translates its texture by nothing, which is every pass but one.
         *
         * @param model the pass's mesh
         * @param textureRef the pass's texture, or empty to reuse the body's
         * @param pass the declared pipeline state
         * @param tintArgb the pass's multiplicative tint
         * @param skipBounds whether the pass is excluded from the canvas-sizing union
         * @param tintBy the tint axis overriding the tint, or empty
         * @param textureBy the texture axis overriding the texture, or empty
         * @param gate the render condition, or empty when unconditional
         * @param noHatModel the alternate mesh a suppressed pass draws, or empty
         * @param pose the pose belonging to this pass's own mesh
         */
        public OverlayLayer(
            @NotNull EntityModelData model, @NotNull Optional<String> textureRef,
            @NotNull PassDeclaration pass, int tintArgb, boolean skipBounds,
            @NotNull Optional<TintAxis> tintBy, @NotNull Optional<TextureAxis> textureBy,
            @NotNull Optional<AppearanceGate> gate, @NotNull Optional<EntityModelData> noHatModel,
            @NotNull EntityPose pose
        ) {
            this(model, textureRef, pass, tintArgb, skipBounds, tintBy, textureBy, gate, noHatModel,
                pose, Optional.empty());
        }

        /**
         * Where this pass samples its texture from at one tick, in normalized sheet coordinates.
         *
         * <p>Vanilla builds the render type with the offset already wrapped -
         * {@code (ageInTicks * k) % 1} - so the wrap is reproduced here rather than left to the
         * fetch, which would wrap the sum of the offset and the authored coordinate instead and land
         * a different texel where the two disagree about which whole turn they are on.
         *
         * @param tick the frame's sample tick
         * @return the offset added to every UV this pass emits, or empty where it scrolls none
         */
        public @NotNull Optional<Vector2f> textureOffsetAt(int tick) {
            return this.textureScroll.map(rate ->
                new Vector2f(tick * rate.x() % 1f, tick * rate.y() % 1f));
        }

        /**
         * The ref whose {@code villager} sidecar supplies the type hat flag: for a {@code type}-axis
         * pass the ADULT {@code <prefix>/type/<biome>} robe ref, whatever the age, else the pass'
         * own resolved ref. Vanilla reads the type hat off a hardcoded {@code "type"} directory
         * token before it ever tests the age, and only the drawn TEXTURE swaps to {@code baby/} -
         * and the {@code baby/} directory ships no sidecars at all, so sourcing the flag from the
         * baby ref would silently read {@code NONE} and stop the desert / snow full-hat suppression
         * applying to a baby. For an adult {@code type} pass this recomputes the ref the pass
         * already holds, so the decision is unchanged.
         *
         * @param appearance the axis selections to resolve against
         * @param texturePrefix the entity texture prefix the type sub-path is qualified with
         * @param resolved this pass' already-resolved texture ref
         * @return the ref to read the type hat flag from
         */
        public @NotNull Optional<String> typeHatRef(
            @NotNull AppearanceOptions appearance, @NotNull String texturePrefix,
            @NotNull Optional<String> resolved) {

            if (this.textureBy.filter(TextureAxis.TYPE::equals).isEmpty()) return resolved;
            return Optional.of(texturePrefix + "/" + appearance.getVillagerType().overlaySubPath());
        }

    }

    /**
     * One equipment overlay on an {@link Entity}: a saddle / body-armor mesh (its own baked geometry)
     * rendered on the body only when the {@code equipment} render axis selects its {@link #slot}. Unlike
     * an always-on {@link OverlayLayer}, the texture is chosen at render from the axis-selected material:
     * {@link #assetFor} names the equipment asset holding that material's layers, which the renderer
     * resolves and composites under {@link #layerType}. Sourced by {@link EntityModelLoader} from the
     * model form's {@code when.equipment}-gated {@code layers}.
     *
     * @param slot the equipment slot this overlay is gated on ({@code saddle} / {@code body})
     * @param model the equipment mesh, resolved from the layer's baked {@code geometry} coordinate
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

        /**
         * This overlay with its selected toggles flipped, or itself when none of them is selected.
         *
         * @param toggles the appearance's selected toggle names
         * @return the overlay drawing what the selection asks for
         */
        @NotNull EquipmentOverlay withToggles(@NotNull Set<String> toggles) {
            EntityModelData flipped = toggled(this.model, toggles);
            return flipped == this.model ? this : new EquipmentOverlay(
                this.slot, flipped, this.layerType, this.materialAssets, this.defaultMaterial);
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

}
