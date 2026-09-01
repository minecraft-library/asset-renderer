package lib.minecraft.renderer.pipeline.index;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
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
 * @param periodTicks the file header's {@code period_ticks} - the ticks one whole excursion spans,
 *     stated once for every family's style catalog - or {@code null} where the header names none
 */
public record RawEntityModelsFile(
    @NotNull Map<String, RawModel> models,
    @SerializedName("period_ticks") @Nullable Integer periodTicks
) {}

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
 * @param bones the model class the family's body is posed through, or {@code null} where the
 *     mesh coordinate already names it
 * @param overlays the body overlay layers in declared order, or {@code null} when absent
 * @param blockOverlays the vanilla-block-shaped overlays, or {@code null} when absent
 * @param armor the worn-armor shell node, or {@code null} for a subject vanilla never armors
 * @param equipment the equipment rows in roster order, or {@code null} when the subject wears none
 * @param axes the mandatory option-axis block ({@code age} plus optional {@code variant} / {@code shape} / {@code size})
 * @param members the self-inclusive canvas-group membership, the same list on every member of the
 *     group (stray beside skeleton), resolved at generation - or {@code null} for a singleton
 * @param styles the family's style catalog rows in shipped order, or {@code null} for a family
 *     whose only output style is the synthesized bind row
 */
record RawModel(
    @Nullable String renderer,
    @Nullable RawRender render,
    @Nullable Map<String, String> rest,
    @Nullable RawBones bones,
    @Nullable List<RawOverlay> overlays,
    @SerializedName("block_overlays") @Nullable List<RawBlockOverlay> blockOverlays,
    @Nullable RawArmor armor,
    @Nullable List<RawEquipmentRow> equipment,
    @NotNull RawAxes axes,
    @Nullable List<String> members,
    @Nullable List<RawStyleRow> styles
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
 */
record RawBones(
    @Nullable String pose
) {}

/**
 * One {@code styles} row - a uniquely identifiable output of the family, in the shape the loaded
 * catalog row is assembled from.
 *
 * @param id the style id a caller selects this output by
 * @param base the id of the sibling row this one composes over, or {@code null} for a root row
 * @param sources the row's mechanism inventory, each entry a bare token or a gated object, or
 *     {@code null} for a row nothing moves
 * @param drives the drives behind the row's own driven fields, or {@code null} where it adds none
 * @param toggles the appearance bone toggles the selection entails, or {@code null} where it
 *     entails none
 * @param age the age option this row applies to, or {@code null} to apply to both
 */
record RawStyleRow(
    @Nullable String id,
    @Nullable String base,
    @Nullable List<RawStyleSource> sources,
    @Nullable List<RawDrive> drives,
    @Nullable List<String> toggles,
    @Nullable String age
) {}

/**
 * One {@code drives} entry - the wave one render-state field travels under a style.
 *
 * @param field the render-state field name the drive answers
 * @param wave the wave token ({@code hold} / {@code ramp} / {@code sweep} / {@code cycle})
 * @param rest what the field holds at tick zero, boxed so an absent member falls to {@code 0f}
 * @param extent the far end of the travel, boxed so an absent member falls to {@code 1f}
 * @param group the exclusion group under which a composing row's drive replaces its base's, or
 *     {@code null} for an ungrouped field
 */
record RawDrive(
    @Nullable String field,
    @Nullable String wave,
    @Nullable Float rest,
    @Nullable Float extent,
    @Nullable String group
) {}

/**
 * One {@code sources} entry, in either of the two spellings the table fixes: a bare token
 * ({@code "tick"}) is an unconditional entry, and the object form carries the overlay gate that
 * admits it ({@code {"source": "scroll", "gate": "charged"}}).
 *
 * @param source the drive-kind token
 * @param gate the token of the overlay gate admitting this entry, or {@code null} for an
 *     unconditional one
 */
@JsonAdapter(RawStyleSource.Adapter.class)
record RawStyleSource(
    @NotNull String source,
    @Nullable String gate
) {

    /** Reads a bare token as an unconditional entry, and the object form with its gate. */
    static final class Adapter implements JsonDeserializer<RawStyleSource> {

        @Override
        public @NotNull RawStyleSource deserialize(
            @NotNull JsonElement node, @NotNull Type type, @NotNull JsonDeserializationContext context) {

            if (node.isJsonPrimitive()) return new RawStyleSource(node.getAsString(), null);
            if (!node.isJsonObject())
                throw new PipelineException("Entity style source is neither a token nor an object");
            JsonObject held = node.getAsJsonObject();
            JsonElement source = held.get("source");
            if (source == null)
                throw new PipelineException("Entity style source object names no 'source'");
            JsonElement gate = held.get("gate");
            return new RawStyleSource(source.getAsString(), gate == null ? null : gate.getAsString());
        }

    }

}

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
 * {@code texture}, a {@code variant} coat {@code textures} /
 * {@code baby_texture} / {@code geometry} / {@code block}, the {@code shape.large} option
 * {@code geometry} / {@code texture} / {@code overlays}, a {@code size} option {@code geometry} /
 * {@code scale}, and a {@code state} option nothing at all - its name is the datum.
 *
 * @param geometry the option's mesh coordinate, or {@code null}
 * @param texture the option's texture as the {@code textures/entity/} sub-path, settled at
 *     generation, or {@code null} when the option has none
 * @param scale a size option's render-scale factor, boxed so a mesh-only option leaves it absent
 * @param overlays the {@code shape.large} pattern overlays materialised on the large geometry, or
 *     {@code null}
 * @param textures a coat's per-state textures ({@code wild} / {@code tame} / {@code angry}), or
 *     {@code null}
 * @param babyTexture a coat's baby texture sub-path, or {@code null} when the option has none
 * @param block the block a coat's fixed block overlays draw (the mooshroom's brown mushroom), or
 *     {@code null} when they draw the one the {@code block_overlays[]} rows already name
 * @param pose the simple name of the model class whose pose says which way this form's bones
 *     point, stated explicitly per form in a format 3 table, or {@code null} where the geometry
 *     coordinate's own head (or the family {@code bones.pose}) still answers
 */
record RawOption(
    @Nullable String geometry,
    @Nullable String texture,
    @Nullable Float scale,
    @Nullable List<RawOverlay> overlays,
    @Nullable Map<String, String> textures,
    @SerializedName("baby_texture") @Nullable String babyTexture,
    @Nullable String block,
    @Nullable String pose
) {}

/**
 * One body {@code overlays} entry.
 *
 * <p>The mesh a pass draws is named rather than described: the subset it is restricted to, the
 * deformation it surrounds the body with and the emptied subtree of its suppressed form are all
 * baked, so a row names one mesh for each form it has and carries no instruction for building one.
 *
 * @param geometry the overlay geometry coordinate, or {@code null} to reuse the base coordinate
 * @param noHatGeometry the mesh the pass draws where it is suppressed, or {@code null} when the pass
 *     has no suppressed form. Named BESIDE {@code geometry} rather than in place of it, a pass
 *     drawing the body's own mesh being one the canvas union already measures through the body
 * @param texture the overlay texture sub-path, or {@code null} to reuse the base texture
 * @param tint the overlay tint as a hex string, or {@code null} for white
 * @param tintBy the render-axis token overriding the tint at render, or {@code null}
 * @param textureBy the render-axis token overriding the texture at render, or {@code null}
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
    @SerializedName("no_hat_geometry") @Nullable String noHatGeometry,
    @Nullable String texture,
    @Nullable String tint,
    @SerializedName("tint_by") @Nullable String tintBy,
    @SerializedName("texture_by") @Nullable String textureBy,
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
 * ({@code texture_by}, tint, pipeline, bounds skip, gate) inherited from the overlay row. A delta
 * carrying no {@code geometry} draws the {@code age.baby} mesh, which {@link EntityIndexBuilder}
 * supplies as the base coordinate.
 *
 * <p>A baby form's mesh is its own rather than the adult's under a baby-sized deformation: a baby
 * decoration is inflated by its own {@code CubeDeformation} (the trader llama's baby caparison
 * against the adult's), and one is baked its own {@code LayerDefinition} that no inflate of the baby
 * body reaches (the drowned's outer shell, whose factory hardcodes two head cubes' deformations
 * rather than driving them off its parameter). Both are meshes the tooling has already derived, so
 * the delta names one where it differs from the row's.
 *
 * @param geometry the baby mesh coordinate, or {@code null} to draw the {@code age.baby} mesh
 * @param noHatGeometry the mesh the baby pass draws where it is suppressed, or {@code null} when it
 *     has no suppressed form
 * @param texture the baby texture sub-path, or {@code null} to inherit the row's texture
 */
record RawOverlayBaby(
    @Nullable String geometry,
    @SerializedName("no_hat_geometry") @Nullable String noHatGeometry,
    @Nullable String texture
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
 * @param bones the model class the layer is posed through, which is not always the one that baked
 *     its mesh, or {@code null} where the two agree
 * @param layerType the equipment render layer's serialized id ({@code pig_saddle}), or {@code null}
 * @param materialAssets the equipment asset id per selectable material, or {@code null}
 * @param defaultMaterial the equipment default material, or {@code null}
 * @param pose the simple name of the model class this row is posed through, stated explicitly in a
 *     format 3 table, or {@code null} where the {@code bones} node (or the geometry coordinate)
 *     still answers
 */
record RawEquipmentRow(
    @Nullable String slot,
    @Nullable String geometry,
    @Nullable RawBones bones,
    @SerializedName("layer_type") @Nullable String layerType,
    @SerializedName("material_assets") @Nullable Map<String, String> materialAssets,
    @SerializedName("default_material") @Nullable String defaultMaterial,
    @Nullable String pose
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
