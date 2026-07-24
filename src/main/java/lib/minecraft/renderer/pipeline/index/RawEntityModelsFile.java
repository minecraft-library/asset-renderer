package lib.minecraft.renderer.pipeline.index;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The faithful raw form of {@code entity_models.json} - the top-level {@code models} object decoded 1:1
 * into a tree of plain records, one per JSON member the loader reads, with every leaf either a scalar,
 * a nested raw record, or a reused adapter ({@code grow} via {@link EntityModelData.Cube.GrowAdapter}).
 *
 * <p>Nothing here is post-processed by Gson: the {@code geometry} coordinates stay coordinate strings,
 * tints stay hex strings, {@code blend} stays a token, and absent members that must differ from a
 * primitive zero ({@code render.scale}, {@code pipeline.alpha}, a {@code size} option's {@code scale})
 * are boxed so {@link EntityIndexBuilder} can substitute their real defaults. The geometry join, the mesh
 * surgery, the axes pivot into {@link Entity.Axes}, the per-variant sub-{@link Entity} fold, and the
 * cross-entity grouping all live in {@link EntityIndexBuilder}, which reads these records.
 *
 * @param models the entity model catalog keyed by namespaced entity id, in file order
 */
public record RawEntityModelsFile(@NotNull Map<String, RawModel> models) {}

/**
 * One raw entity model (a family), 1:1 with a {@code models} member. The {@code renderer} class name and
 * every {@code source} / {@code layer_index} authoring hint are simply not declared, so Gson ignores them.
 *
 * @param render the family render tuning ({@code scale} / {@code yaw_addend} / {@code tint}), or {@code null}
 * @param bones the {@code hidden} strip and {@code toggles} specs, or {@code null} when the family has none
 * @param overlays the body overlay layers in declared order, or {@code null} when absent
 * @param blockOverlays the vanilla-block-shaped overlays, or {@code null} when absent
 * @param layers the conditional decoration layers (collar / equipment / markings / armor), or {@code null}
 * @param axes the mandatory option-axis block ({@code age} plus optional {@code variant} / {@code shape} / {@code size})
 * @param groupOf the base entity this sub-species groups under (mooshroom -&gt; cow), or {@code null}
 */
record RawModel(
    @Nullable RawRender render,
    @Nullable RawBones bones,
    @Nullable List<RawOverlay> overlays,
    @SerializedName("block_overlays") @Nullable List<RawBlockOverlay> blockOverlays,
    @Nullable List<RawLayer> layers,
    @NotNull RawAxes axes,
    @SerializedName("group_of") @Nullable String groupOf
) {}

/**
 * The family {@code render} tuning.
 *
 * @param scale the render-time scale, boxed so an absent member falls to {@code 1f} not {@code 0f}
 * @param yawAddend the {@code setupRotations} yaw addend in degrees, {@code 0f} when absent
 * @param tint the per-entity base tint as a hex string, or {@code null} for white
 */
record RawRender(
    @Nullable Float scale,
    @SerializedName("yaw_addend") float yawAddend,
    @Nullable String tint
) {}

/**
 * The family {@code bones} block.
 *
 * @param hidden the bone names stripped from the default mesh, or {@code null} when none
 * @param toggles the named visibility toggles keyed by toggle name, or {@code null} when none
 */
record RawBones(
    @Nullable List<String> hidden,
    @Nullable Map<String, RawToggle> toggles
) {}

/**
 * One {@code bones.toggles} spec.
 *
 * @param bones the bone names this toggle flips
 * @param defaultVisible whether the bones render by default ({@code true} = toggle hides; {@code false} = reveals)
 */
record RawToggle(
    @Nullable List<String> bones,
    @SerializedName("default") boolean defaultVisible
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
    @Nullable RawVariantAxis variant,
    @NotNull RawAgeAxis age,
    @Nullable RawShapeAxis shape,
    @Nullable RawSizeAxis size,
    @Nullable RawStateAxis state
) {}

/**
 * The {@code axes.state} axis. Only the option names and the declared default are read - the textures a
 * state selects live on each variant option.
 *
 * @param defaultOption the state the base textures represent
 * @param options the state options keyed by state name
 */
record RawStateAxis(
    @SerializedName("default") @Nullable String defaultOption,
    @NotNull Map<String, com.google.gson.JsonElement> options
) {}

/**
 * The {@code axes.variant} axis.
 *
 * @param idEncoded whether each option is a first-class {@code minecraft:<id>_<opt>} pseudo-id (always
 *     {@code false} in 26.1 - the dead id-encoded branch)
 * @param defaultOption the option name carrying the model baseline
 * @param options the coat options keyed by option name
 */
record RawVariantAxis(
    @SerializedName("id_encoded") boolean idEncoded,
    @SerializedName("default") @Nullable String defaultOption,
    @NotNull Map<String, RawVariantOption> options
) {}

/**
 * One {@code variant} option (a coat).
 *
 * @param textures the per-state textures ({@code wild} / {@code tame} / {@code angry}), or {@code null}
 * @param babyTexture the option's baby texture path, or {@code null} when the option has none
 * @param geometry the option's own geometry coordinate overriding the family mesh, or {@code null}
 */
record RawVariantOption(
    @Nullable Map<String, String> textures,
    @SerializedName("baby_texture") @Nullable String babyTexture,
    @Nullable String geometry
) {}

/**
 * The {@code axes.age} axis - the {@code adult} option carries the family baseline geometry / texture and
 * the optional {@code baby} option the dedicated baby mesh.
 *
 * @param options the age options keyed by option name ({@code adult} / {@code baby})
 */
record RawAgeAxis(@NotNull Map<String, RawAgeOption> options) {}

/**
 * One {@code age} option.
 *
 * @param geometry the option's geometry coordinate
 * @param texture the option's base texture path, or {@code null} when the option has none
 */
record RawAgeOption(@Nullable String geometry, @Nullable String texture) {}

/**
 * The {@code axes.shape} axis (tropical fish).
 *
 * @param options the shape options keyed by option name (only {@code large} is read)
 */
record RawShapeAxis(@NotNull Map<String, RawShapeOption> options) {}

/**
 * The {@code shape.large} option.
 *
 * @param geometry the large body geometry coordinate, or {@code null}
 * @param texture the large body base texture path, or {@code null}
 * @param overlays the pattern overlays materialised on the large geometry, or {@code null}
 */
record RawShapeOption(
    @Nullable String geometry,
    @Nullable String texture,
    @Nullable List<RawOverlay> overlays
) {}

/**
 * The {@code axes.size} axis (pufferfish meshes / salmon / slime scales).
 *
 * @param defaultOption the size the base mesh and unit scale represent
 * @param options the size options keyed by {@link Size} name (lower-case)
 */
record RawSizeAxis(
    @SerializedName("default") @Nullable String defaultOption,
    @NotNull Map<String, RawSizeOption> options
) {}

/**
 * One {@code size} option - a mesh alternative carries {@code geometry}, a scale alternative carries
 * {@code scale}.
 *
 * @param geometry the size's distinct mesh coordinate, or {@code null} for a scale-only option
 * @param scale the size's render-scale factor, boxed so a mesh-only option leaves it absent
 */
record RawSizeOption(@Nullable String geometry, @Nullable Float scale) {}

/**
 * One body {@code overlays} entry.
 *
 * @param geometry the overlay geometry coordinate, or {@code null} to reuse the base coordinate
 * @param texture the overlay texture path, or {@code null} to reuse the base texture
 * @param retainBones the vanilla {@code retainExactParts} subset restricting the mesh, or {@code null}
 * @param noHatRoot the bone whose subtree the suppressed pass clears (vanilla's
 *     {@code clearChild(name).clearRecursively()}), or {@code null} when the pass has no alternate mesh
 * @param tint the overlay tint as a hex string, or {@code null} for white
 * @param tintBy the render-axis token overriding the tint at render, or {@code null}
 * @param textureBy the render-axis token overriding the texture at render, or {@code null}
 * @param grow the per-axis cube inflate (scalar broadcasts to all axes), or {@code null} when absent
 * @param pipeline the blend / alpha / emissive render pipeline, or {@code null}
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
    @JsonAdapter(EntityModelData.Cube.GrowAdapter.class) @Nullable Vector3f grow,
    @Nullable RawPipeline pipeline,
    @SerializedName("skip_bounds") boolean skipBounds,
    @Nullable RawOverlayWhen when,
    @Nullable RawOverlayBaby baby
) {}

/**
 * An overlay's {@code baby} age delta - the members a baby render substitutes, with everything else
 * ({@code texture_by}, tint, pipeline, bounds skip, gate) inherited from the overlay row. The
 * geometry is never carried: a baby overlay materialises against the {@code age.baby} mesh, which
 * {@link EntityIndexBuilder} supplies as the base coordinate. {@code grow} is carried because a baby
 * decoration inflates its baby mesh by its own {@code CubeDeformation} (the trader llama's baby
 * caparison at {@code 0.2}, against the adult's {@code 0.5}), not the adult row's.
 *
 * @param texture the baby texture path, or {@code null} to inherit the row's texture
 * @param noHatRoot the bone whose subtree the baby suppressed pass clears, or {@code null} when the
 *     baby pass has no alternate mesh
 * @param grow the baby cube inflate (scalar broadcasts to all axes), or {@code null} to inherit the
 *     row's grow
 */
record RawOverlayBaby(
    @Nullable String texture,
    @SerializedName("no_hat_root") @Nullable String noHatRoot,
    @JsonAdapter(EntityModelData.Cube.GrowAdapter.class) @Nullable Vector3f grow
) {}

/**
 * An overlay's {@code pipeline} block.
 *
 * @param emissive whether the overlay renders full-bright
 * @param blend the composition token ({@code additive} / {@code translucent} / {@code normal}), or {@code null}
 * @param alpha the per-fragment opacity, boxed so an absent member falls to {@code 1f}
 */
record RawPipeline(
    boolean emissive,
    @Nullable String blend,
    @Nullable Float alpha
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
 * @param transforms the ordered transform ops applied to the block model
 * @param selectable whether the block is supplied at render from the carried selection
 */
record RawBlockOverlay(
    @Nullable String block,
    @SerializedName("attached_bone") @Nullable String attachedBone,
    @Nullable List<RawTransform> transforms,
    boolean selectable
) {}

/**
 * One {@code transforms} op - the tagged union the assembler pattern-matches on {@code op}. Unused
 * components stay at their {@code 0f} default for a given op kind.
 *
 * @param op the op discriminator ({@code translate} / {@code rotate_x} / {@code rotate_y} / {@code rotate_z} / {@code scale})
 * @param x the translate / scale X component
 * @param y the translate / scale Y component
 * @param z the translate / scale Z component
 * @param degrees the rotate angle in degrees
 */
record RawTransform(
    @Nullable String op,
    float x,
    float y,
    float z,
    float degrees
) {}

/**
 * One {@code layers} entry - a heterogeneous row the assembler routes by {@code id} (collar / markings /
 * armor) or {@code when.equipment} (equipment).
 *
 * @param id the layer id ({@code collar} / {@code markings} / {@code armor} / ...), or {@code null}
 * @param when the layer gate carrying the {@code equipment} slot, or {@code null}
 * @param overlay the layer's overlay body - every row's payload lives here, so the row itself stays
 *     the routing shell; {@code null} for a row that carries none
 */
record RawLayer(
    @Nullable String id,
    @Nullable RawLayerWhen when,
    @Nullable RawLayerOverlay overlay
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
 * A {@code layers[].when} gate. Only the {@code equipment} slot is read; {@code collar_color} and
 * {@code markings} presence is derived from the layer {@code id} instead.
 *
 * @param equipment the equipment slot this layer is gated on, or {@code null}
 */
record RawLayerWhen(@Nullable String equipment) {}

/**
 * A {@code layers[].overlay} body. Serves the collar layer (reads {@code texture}), the equipment layers
 * (read {@code geometry} / {@code layer_type} / {@code material_assets} / {@code default_material}) and
 * the armor row (reads {@code geometry} / {@code grow}); the marking layer's {@code texture_by} /
 * {@code textures_by_value} are not read.
 *
 * @param texture the collar overlay texture path, or {@code null}
 * @param geometry the equipment or armor overlay geometry coordinate, or {@code null}
 * @param grow the armor row's two layer deformations, or {@code null} for every other row
 * @param layerType the equipment render layer's serialized id ({@code pig_saddle}), or {@code null}
 * @param materialAssets the equipment asset id per selectable material, or {@code null}
 * @param defaultMaterial the equipment default material, or {@code null}
 */
record RawLayerOverlay(
    @Nullable String texture,
    @Nullable String geometry,
    @Nullable RawArmorGrow grow,
    @SerializedName("layer_type") @Nullable String layerType,
    @SerializedName("material_assets") @Nullable Map<String, String> materialAssets,
    @SerializedName("default_material") @Nullable String defaultMaterial
) {}
