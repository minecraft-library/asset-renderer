package lib.minecraft.renderer.pipeline.index;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The faithful raw form of {@code entity_models.json} - the top-level {@code models} object decoded 1:1
 * into a tree of plain records, one per JSON member the loader reads, with every leaf either a scalar,
 * a nested raw record, or a reused adapter (the armor {@code grow} pair via
 * {@link EntityModelData.Cube.GrowAdapter}).
 *
 * <p>Nothing here is post-processed by Gson: the {@code geometry} coordinates stay coordinate strings,
 * tints stay hex strings, {@code blend} stays a token, and absent members that must differ from a
 * primitive zero ({@code render.scale}, {@code pipeline.alpha}, a {@code size} option's {@code scale})
 * are boxed so {@link EntityIndexBuilder} can substitute their real defaults. The geometry join, the mesh
 * surgery, the axes pivot into {@link Entity.Axes} and the per-variant sub-{@link Entity} fold all live
 * in {@link EntityIndexBuilder}, which reads these records.
 *
 * @param models the entity model catalog keyed by namespaced entity id, in file order
 */
public record RawEntityModelsFile(@NotNull Map<String, RawModel> models) {}

/**
 * One raw entity model (a family), 1:1 with a {@code models} member. Every {@code source} /
 * {@code layer_index} authoring hint is simply not declared, so Gson ignores it.
 *
 * @param renderer the vanilla renderer class's internal name, which is what the pose table keys its
 *     {@code renderers} transforms by - the one member here that names a class rather than an asset,
 *     because what a renderer composes above its meshes is its own fact and no model carries it
 * @param render the family render tuning ({@code scale} / {@code tint}), or {@code null}
 * @param rest which constant each of the subject's enum render-state fields holds before anything
 *     has happened to it, or {@code null} when its renderer fills none from a readable accessor
 * @param bones the {@code undrawn} strip and {@code toggles} specs, or {@code null} when the family has none
 * @param overlays the body overlay layers in declared order, or {@code null} when absent
 * @param blockOverlays the vanilla-block-shaped overlays, or {@code null} when absent
 * @param collar the dyed-collar node (its {@code texture} is read; the rest are authoring hints),
 *     or {@code null} - presence is the gate, mirroring vanilla's {@code collarColor != null} branch
 * @param markings the horse-marking node ({@code texture_by} / {@code textures_by_value} are
 *     authoring hints; presence is the datum), or {@code null}
 * @param armor the worn-armor shell node, or {@code null} for a subject vanilla never armors
 * @param equipment the equipment rows in roster order, or {@code null} when the subject wears none
 * @param axes the mandatory option-axis block ({@code age} plus optional {@code variant} / {@code shape} / {@code size})
 * @param members the self-inclusive canvas-group membership, the same list on every member of the
 *     group (stray beside skeleton), resolved at generation - or {@code null} for a singleton
 */
record RawModel(
    @Nullable String renderer,
    @Nullable RawRender render,
    @Nullable Map<String, String> rest,
    @Nullable RawBones bones,
    @Nullable List<RawOverlay> overlays,
    @SerializedName("block_overlays") @Nullable List<RawBlockOverlay> blockOverlays,
    @Nullable com.google.gson.JsonObject collar,
    @Nullable com.google.gson.JsonObject markings,
    @Nullable RawArmor armor,
    @Nullable List<RawEquipmentRow> equipment,
    @NotNull RawAxes axes,
    @Nullable List<String> members
) {}

/**
 * The family {@code render} tuning.
 *
 * @param scale the render-time scale, boxed so an absent member falls to {@code 1f} not {@code 0f}
 * @param tint the per-entity base tint as a hex string, or {@code null} for white
 */
record RawRender(
    @Nullable Float scale,
    @Nullable String tint
) {}

/**
 * A {@code bones} block, on the family or on an equipment layer's overlay.
 *
 * @param pose the simple name of the model class whose pose says which way these bones point,
 *     or {@code null} when the mesh's own geometry coordinate already names it. Written only
 *     where the two disagree, which is a saddle: vanilla declares a saddle's mesh factory on
 *     the wearer's model class and hands the layer a different class to pose it with
 * @param undrawn the bone names the subject rests not drawing - the never-drawn merged with what
 *     its pose rests hidden, resolved at generation - or {@code null} when it rests whole
 * @param toggles the named visibility toggles keyed by toggle name, or {@code null} when none
 */
record RawBones(
    @Nullable String pose,
    @Nullable List<String> undrawn,
    @Nullable Map<String, RawToggle> toggles
) {}

/**
 * One {@code bones.toggles} spec - which bones a toggle flips, and nothing about which way.
 *
 * <p>Which way it points is derived: a toggle names the state its subject is not resting in, and
 * what it rests without is what the site's {@code undrawn} list already says. A member declaring it
 * here would be a second answer to a question that already has one.
 *
 * @param bones the bone names this toggle flips
 */
record RawToggle(
    @Nullable List<String> bones
) {}

/**
 * The family {@code axes} block. The {@code state} axis carries only its option names and default - the
 * per-state textures are carried by each variant option's {@code textures} instead.
 *
 * @param variant the option-encoded coat / colour axis, or {@code null} when the family has none
 * @param age the mandatory adult / baby geometry axis
 * @param shape the tropical-fish large-body axis, or {@code null} when absent
 * @param size the pufferfish mesh / salmon-slime scale axis, or {@code null} when absent
 * @param state the behavioural-state axis (wolf wild / tame / angry), or {@code null} when absent
 */
record RawAxes(
    @Nullable RawAxis variant,
    @NotNull RawAxis age,
    @Nullable RawAxis shape,
    @Nullable RawAxis size,
    @Nullable RawAxis state
) {}

/**
 * One option axis - {@code default} naming the option the base row represents, and the options keyed
 * by option name. All five axes ({@code variant} / {@code age} / {@code shape} / {@code size} /
 * {@code state}) are this one shape; which of an option's members are populated is the axis's own
 * fact, stated on {@link RawOption}.
 *
 * @param defaultOption the option the base row represents, or {@code null} where the axis declares
 *     none (the age axis, whose baseline is always {@code adult})
 * @param options the options keyed by option name
 */
record RawAxis(
    @SerializedName("default") @Nullable String defaultOption,
    @NotNull Map<String, RawOption> options
) {}

/**
 * One axis option - the superset of what any axis's option carries, every member optional because
 * each axis populates its own subset: an {@code age} option carries {@code geometry} /
 * {@code texture} / {@code y_shift} / {@code undrawn}, a {@code variant} coat {@code textures} /
 * {@code baby_texture} / {@code geometry} / {@code block}, the {@code shape.large} option
 * {@code geometry} / {@code texture} / {@code overlays}, a {@code size} option {@code geometry} /
 * {@code scale} / {@code undrawn}, and a {@code state} option nothing at all - its name is the datum.
 *
 * @param geometry the option's mesh coordinate, or {@code null}
 * @param texture the option's texture as the {@code textures/entity/} sub-path, settled at
 *     generation, or {@code null} when the option has none
 * @param yShift the blocks the renderer's {@code setupRotations} translates this age along Y before
 *     drawing it, {@code 0f} when absent. It sits on the option rather than on {@code render}
 *     because vanilla brackets its rotations with translates the age selects between - the squid
 *     lifts an adult by a different pair than a baby
 * @param undrawn the bone names this option's mesh rests not drawing, resolved at generation, or
 *     {@code null} when it rests whole
 * @param scale a size option's render-scale factor, boxed so a mesh-only option leaves it absent
 * @param overlays the {@code shape.large} pattern overlays materialised on the large geometry, or
 *     {@code null}
 * @param textures a coat's per-state textures ({@code wild} / {@code tame} / {@code angry}), or
 *     {@code null}
 * @param babyTexture a coat's baby texture sub-path, or {@code null} when the option has none
 * @param block the block a coat's fixed block overlays draw (the mooshroom's brown mushroom), or
 *     {@code null} when they draw the one the {@code block_overlays[]} rows already name
 */
record RawOption(
    @Nullable String geometry,
    @Nullable String texture,
    @SerializedName("y_shift") float yShift,
    @Nullable List<String> undrawn,
    @Nullable Float scale,
    @Nullable List<RawOverlay> overlays,
    @Nullable Map<String, String> textures,
    @SerializedName("baby_texture") @Nullable String babyTexture,
    @Nullable String block
) {}

/**
 * One body {@code overlays} entry.
 *
 * @param geometry the overlay geometry coordinate, or {@code null} to reuse the base coordinate
 * @param texture the overlay texture sub-path, or {@code null} to reuse the base texture
 * @param retainBones the vanilla {@code retainExactParts} subset restricting the mesh, or {@code null}
 * @param noHatRoot the bone whose subtree the suppressed pass clears (vanilla's
 *     {@code clearChild(name).clearRecursively()}), or {@code null} when the pass has no alternate mesh
 * @param tint the overlay tint as a hex string, or {@code null} for white
 * @param tintBy the render-axis token overriding the tint at render, or {@code null}
 * @param textureBy the render-axis token overriding the texture at render, or {@code null}
 * @param grow the uniform cube inflate, or {@code null} when absent
 * @param pipeline the blend / alpha / emissive render pipeline, or {@code null}
 * @param textureScroll the texels-per-tick the render type translates this pass's texture by, or
 *     {@code null} when it translates none
 * @param skipBounds whether the overlay is excluded from the canvas-sizing bounds union
 * @param when the render condition, or {@code null} when unconditional
 * @param baby the age delta a baby render substitutes, or {@code null} when the overlay draws on an
 *     adult only
 */
record RawOverlay(
    @Nullable String geometry,
    @Nullable String texture,
    @SerializedName("retain_bones") @Nullable List<String> retainBones,
    @SerializedName("no_hat_root") @Nullable String noHatRoot,
    @Nullable String tint,
    @SerializedName("tint_by") @Nullable String tintBy,
    @SerializedName("texture_by") @Nullable String textureBy,
    @Nullable Float grow,
    @Nullable RawPipeline pipeline,
    @SerializedName("texture_scroll") @Nullable RawTextureScroll textureScroll,
    @SerializedName("skip_bounds") boolean skipBounds,
    @Nullable RawOverlayWhen when,
    @Nullable RawOverlayBaby baby
) {}

/**
 * An overlay's {@code texture_scroll} - what its render type translates the texture by, per tick.
 *
 * <p>A rate rather than an expression, because the corpus's three sites are one shape: vanilla
 * builds the render type with {@code (ageInTicks * k) % 1} on each axis, so the constant is the
 * whole of what varies between them and the wrap is the type's own.
 *
 * @param u the fraction of the sheet's width the pass scrolls along u each tick
 * @param v the fraction of its height the pass scrolls along v each tick
 */
record RawTextureScroll(float u, float v) {}

/**
 * An overlay's {@code baby} age delta - the members a baby render substitutes, with everything else
 * ({@code texture_by}, tint, pipeline, bounds skip, gate) inherited from the overlay row. Most deltas
 * carry no {@code geometry} and materialise against the {@code age.baby} mesh, which
 * {@link EntityIndexBuilder} supplies as the base coordinate; one is carried where vanilla bakes the
 * baby pass its own {@code LayerDefinition} that no inflate of the baby body reaches (the drowned's
 * outer shell, whose factory hardcodes two head cubes' deformations rather than driving them off its
 * parameter). {@code grow} is carried because a baby decoration inflates its baby mesh by its own
 * {@code CubeDeformation} (the trader llama's baby caparison at {@code 0.2}, against the adult's
 * {@code 0.5}), not the adult row's.
 *
 * @param geometry the baby mesh coordinate, or {@code null} to materialise against the
 *     {@code age.baby} mesh
 * @param texture the baby texture sub-path, or {@code null} to inherit the row's texture
 * @param noHatRoot the bone whose subtree the baby suppressed pass clears, or {@code null} when the
 *     baby pass has no alternate mesh
 * @param grow the baby cube inflate, or {@code null} to inherit the row's grow - and never
 *     inherited at all once {@code geometry} names a mesh of its own, whose deformation the tooling
 *     has already baked in
 */
record RawOverlayBaby(
    @Nullable String geometry,
    @Nullable String texture,
    @SerializedName("no_hat_root") @Nullable String noHatRoot,
    @Nullable Float grow
) {}

/**
 * An overlay's {@code pipeline} block.
 *
 * @param emissive whether the overlay renders full-bright
 * @param blend the composition token ({@code additive} / {@code translucent} / {@code normal}), or {@code null}
 * @param alpha the per-fragment opacity, boxed so an absent member falls to {@code 1f}
 * @param depthWrite whether the pass writes the depth buffer, boxed so an absent member falls to
 *     {@code true} - the value {@code DepthStencilState.DEFAULT} carries and all but the eyes-style
 *     passes declare
 * @param sorted whether the pass's quads are drawn back-to-front, from vanilla's
 *     {@code RenderSetup.sortOnUpload}; absent means submission order
 */
record RawPipeline(
    boolean emissive,
    @Nullable String blend,
    @Nullable Float alpha,
    @SerializedName("depth_write") @Nullable Boolean depthWrite,
    boolean sorted
) {}

/**
 * An overlay's {@code when} render condition. The first matching predicate wins in the assembler in the
 * order flag, charged, tinted.
 *
 * @param flag the flag axis token whose {@code value} must match, or {@code null}
 * @param value the required flag value
 * @param charged whether the overlay renders only for a charged (lightning-struck) entity
 * @param tinted whether the overlay is a tint-gated undercoat
 */
record RawOverlayWhen(
    @Nullable String flag,
    boolean value,
    boolean charged,
    boolean tinted
) {}

/**
 * One {@code block_overlays} entry.
 *
 * @param block the fixed block id to render, or {@code null} for a selectable held block
 * @param attachedBone the entity bone whose pose pre-multiplies the transforms, or {@code null}
 * @param transforms the ordered transform ops applied to the block model, each a single-member
 *     object whose name says what it does - {@code translate} / {@code scale} carry a three-float
 *     array and {@code rotate_x} / {@code rotate_y} / {@code rotate_z} carry degrees - the same
 *     shape a pose expression takes
 * @param selectable whether the block is supplied at render from the carried selection
 */
record RawBlockOverlay(
    @Nullable String block,
    @SerializedName("attached_bone") @Nullable String attachedBone,
    @Nullable List<Map<String, com.google.gson.JsonElement>> transforms,
    boolean selectable
) {}

/**
 * An armor row's {@code grow} pair - the per-side growth each of the two armor layers applies to the
 * shell, in the same scalar-or-array form a cube's own {@code grow} takes.
 *
 * @param inner the growth the leggings layer applies, or {@code null} when absent
 * @param outer the growth the helmet / chestplate / boots layer applies, or {@code null} when absent
 */
record RawArmorGrow(
    @JsonAdapter(EntityModelData.Cube.GrowAdapter.class) @Nullable Vector3f inner,
    @JsonAdapter(EntityModelData.Cube.GrowAdapter.class) @Nullable Vector3f outer
) {}

/**
 * The {@code when} gate on the armor node's second shell - the {@code age} or {@code size} option
 * that selects it.
 *
 * @param age the age option that selects this shell, or {@code null}
 * @param size the size option that selects this shell, or {@code null}
 */
record RawLayerWhen(@Nullable String age, @Nullable String size) {}

/**
 * The worn-armor {@code armor} node - the shell the wearer is dressed in.
 *
 * @param geometry the shell's geometry coordinate
 * @param grow the armor set's two layer deformations
 * @param scaled the whole-mesh scale the armor set is registered through, or {@code null} at the
 *     identity - the eleven wearers vanilla registers unscaled
 * @param alternate the shell this wearer's other form is dressed in, or {@code null} when vanilla
 *     hands its armor layer one shell twice
 */
record RawArmor(
    @Nullable String geometry,
    @Nullable RawArmorGrow grow,
    @Nullable Float scaled,
    @Nullable RawArmorAlternate alternate
) {}

/**
 * One {@code equipment} row - a saddle or body-armor layer, gated on its {@code slot}.
 *
 * @param slot the equipment slot this row is gated on
 * @param geometry the row's mesh coordinate
 * @param bones the {@code undrawn} strip and {@code toggles} specs the layer's own model class
 *     declares over that geometry, or {@code null} when it declares none
 * @param layerType the equipment render layer's serialized id ({@code pig_saddle}), or {@code null}
 * @param materialAssets the equipment asset id per selectable material, or {@code null}
 * @param defaultMaterial the equipment default material, or {@code null}
 */
record RawEquipmentRow(
    @Nullable String slot,
    @Nullable String geometry,
    @Nullable RawBones bones,
    @SerializedName("layer_type") @Nullable String layerType,
    @SerializedName("material_assets") @Nullable Map<String, String> materialAssets,
    @SerializedName("default_material") @Nullable String defaultMaterial
) {}

/**
 * The armor row's {@code alternate} node - the second shell vanilla hands this wearer's armor layer,
 * carrying the same three members the first writes into the row's {@code overlay} body plus the
 * selection that reaches it and which of the two shells it is.
 *
 * <p>Both extras are carried rather than assumed. Vanilla reaches every second set through one flag,
 * but the six aged-down ones answer this pipeline's {@code age} axis while the armor stand's answers
 * {@code size}; and the stand's shell is aged-down geometry on the <em>adult</em> per-slot parts,
 * sheets and trim, so the form does not follow from the selection either.
 *
 * @param when the appearance selection that swaps to this shell
 * @param geometry the shell's geometry coordinate
 * @param grow the set's two layer deformations
 * @param scaled the whole-mesh scale the set is registered through, or {@code null} at the identity
 * @param form which of vanilla's two shells this is ({@code adult} / {@code baby})
 */
record RawArmorAlternate(
    @Nullable RawLayerWhen when,
    @Nullable String geometry,
    @Nullable RawArmorGrow grow,
    @Nullable Float scaled,
    @Nullable String form
) {}
