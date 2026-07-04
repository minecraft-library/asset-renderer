package lib.minecraft.renderer.asset;

import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.options.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A fully-parsed block definition backed by its vanilla model JSON and blockstate variants.
 * <p>
 * Every field is populated once during {@code Pipeline} bootstrap and stored verbatim; no
 * lazy or computed fields live on this DTO. Lookup happens through the active renderer
 * context.
 */
@Getter
@AllArgsConstructor
@EqualsAndHashCode
public final class Block {

    /**
     * The block's namespaced identifier (e.g. {@code minecraft:furnace}).
     */
    private final @NotNull ResourceId id;

    /**
     * The resolved model supplying the block's geometry and texture bindings.
     */
    private final @NotNull ModelData model;

    /**
     * The model's texture variable bindings, keyed by variable name.
     */
    private final @NotNull ConcurrentMap<String, String> textures;

    /**
     * The blockstate variants keyed by their sorted {@code property=value} state key, each carrying
     * a resolved model and whole-block rotation.
     */
    private final @NotNull ConcurrentMap<String, Variant> variants;

    /**
     * The multipart blockstate definition, or empty when the block uses discrete variants.
     */
    private final @NotNull Optional<Multipart> multipart;

    /**
     * Tag names this block belongs to, e.g. {@code ["minecraft:stairs", "minecraft:wooden_stairs"]}.
     */
    private final @NotNull ConcurrentList<String> tags;

    /**
     * The biome tint binding selecting which colormap or constant the renderer samples for tinted faces.
     */
    private final @NotNull Tint tint;

    /**
     * Rendering override for blocks whose visual geometry comes from a vanilla
     * {@code BlockEntityRenderer} (beds, chests, banners, shulkers, signs, skulls, conduit,
     * decorated_pot, etc.). When present, renderers prefer {@link Entity#model()} over
     * {@link #getModel()}, multiply {@link Entity#tintArgb()} against sampled texels, honour
     * {@link Entity#iconRotation()} for the atlas icon, and optionally compose
     * {@link Entity#parts()} for multi-part atlas views (bed head + foot, decorated_pot body +
     * sides, banner post + flag). Absent for plain blocks.
     */
    private final @NotNull Optional<Entity> entity;

    /**
     * Where this block's registration originated. Used by atlas tile classification to label the
     * source path (block-model file, blockstate-only fallback, or block-entity geometry override)
     * without forcing consumers to type-check the renderer-context implementation.
     */
    private final @NotNull Source source;

    /**
     * The block's canonical default blockstate key as {@code property=value} pairs sorted
     * alphabetically (e.g. {@code "facing=north,half=lower,hinge=left,open=false,powered=false"}),
     * or empty when the block has no properties. Sourced from {@code block_defaults.json} (an ASM
     * bytewalk of {@code registerDefaultState}) and baked on at pipeline-context construction. The
     * renderer falls back to this key when a caller supplies no explicit variant, so blocks with
     * per-state models render their default rather than whichever state registered first.
     */
    private final @NotNull String defaultStateKey;

    /**
     * The provenance of a {@link Block}'s registration. Drives atlas tile classification and any
     * future caller that needs to know how the block reached the renderer context.
     */
    public enum Source {

        /**
         * Registered from a primary {@code block/<id>.json} model file.
         */
        PRIMARY,

        /**
         * Registered via the blockstate-only fallback path - the blockstate definition exists but
         * no matching block-model file was found, so the model is resolved through the first
         * variant or multipart entry instead.
         */
        BLOCKSTATE_ONLY,

        /**
         * Geometry is sourced from a vanilla {@code BlockEntityRenderer} (beds, chests, banners,
         * shulkers, signs, skulls, conduit, decorated_pot, etc.) rather than a model file.
         */
        TILE_ENTITY

    }

    /**
     * Identifies which biome colormap drives a block face's tint, or flags that the tint comes
     * from a hardcoded constant on the block DTO.
     */
    public enum TintTarget {

        /**
         * The face is not biome-tinted.
         */
        NONE,

        /**
         * Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc.
         */
        GRASS,

        /**
         * Sample the foliage colormap. Applies to most leaves.
         */
        FOLIAGE,

        /**
         * Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes.
         */
        DRY_FOLIAGE,

        /**
         * Use the biome's water colour override when present, or the engine-level default
         * {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap; biomes either carry an
         * explicit {@code water_color} value or inherit the default.
         */
        WATER,

        /**
         * Use the {@link Tint#constant() constant} ARGB carried on the block's {@link Tint}
         * directly. Applies to redstone wire, stems, etc.
         */
        CONSTANT

    }

    /**
     * The biome tint binding for a block, selecting which colormap (or hardcoded constant) the
     * renderer samples for tinted faces.
     *
     * @param target the tint source - {@link TintTarget#NONE NONE} for untinted blocks,
     *     {@link TintTarget#CONSTANT CONSTANT} for a hardcoded ARGB value, or a colormap
     *     target like {@link TintTarget#GRASS GRASS} / {@link TintTarget#FOLIAGE FOLIAGE}
     * @param constant the hardcoded ARGB value, present only when {@code target} is
     *     {@link TintTarget#CONSTANT CONSTANT} and empty otherwise
     */
    public record Tint(@NotNull TintTarget target, @NotNull Optional<Integer> constant) {}

    /**
     * The geometry a {@link Variant} carries - either a resolved element model (plain blockstate
     * variants) or a relative bone tree + presentation (a block entity's state-conditional bone
     * mesh). A variant holds exactly one, so the renderer pattern-matches on the geometry kind
     * instead of disambiguating an empty-{@link ModelData} sentinel against an {@link Optional}.
     */
    public sealed interface VariantGeometry permits ElementGeometry, BoneGeometry {}

    /**
     * A {@link Variant} backed by a resolved element {@link ModelData} - the plain blockstate case.
     * The {@link #model()} is element-less when the variant's {@code modelId} did not resolve against
     * the loaded model set, in which case the renderer falls back to the block's primary model.
     *
     * @param model the resolved element model, or element-less when unresolved
     */
    public record ElementGeometry(@NotNull ModelData model) implements VariantGeometry {}

    /**
     * A {@link Variant} backed by a relative bone tree - a block entity's state-conditional bone
     * mesh (the ceiling hanging sign's straight-chain mesh under {@code attached=true}), composed at
     * render time via {@link BlockGeometryKit#buildFromBones}.
     *
     * @param bone the variant's relative bone geometry plus its render-time presentation
     */
    public record BoneGeometry(@NotNull Entity.BoneModel bone) implements VariantGeometry {}

    /**
     * A single blockstate variant entry, specifying which model to use and what whole-block
     * rotation to apply. Parsed from blockstate JSON files like
     * {@code assets/minecraft/blockstates/furnace.json}.
     * <p>
     * The {@code x} and {@code y} rotations are multiples of 90 degrees applied to the entire
     * model before rendering. These are distinct from element-level rotations in the model JSON.
     * <p>
     * The {@code geometry} - baked in at pipeline-context construction time so a variant reaches its
     * geometry through its owning {@link Block} rather than a context-level registry - is either an
     * {@link ElementGeometry} (plain blockstate variants) or a {@link BoneGeometry} (a block entity's
     * state-conditional bone mesh, e.g. the ceiling hanging sign under {@code attached=true}).
     *
     * @param modelId the namespaced model reference (e.g. {@code "minecraft:block/furnace"})
     * @param x the whole-model X rotation in degrees (0, 90, 180, or 270)
     * @param y the whole-model Y rotation in degrees (0, 90, 180, or 270)
     * @param uvlock whether UVs should be locked to the block grid during rotation
     * @param geometry the variant's geometry - an {@link ElementGeometry} or a {@link BoneGeometry}
     */
    public record Variant(@NotNull String modelId, int x, int y, boolean uvlock, @NotNull VariantGeometry geometry) {

        /**
         * Reports whether this variant applies a whole-model rotation.
         *
         * @return {@code true} when either {@code x} or {@code y} is non-zero
         */
        public boolean hasRotation() {
            return this.x != 0 || this.y != 0;
        }

    }

    /**
     * A parsed {@code "multipart"} blockstate definition. Each part carries an optional condition
     * and a model reference (with rotation) to apply when the condition matches the block's
     * properties. Parts without a condition are unconditional and always rendered.
     *
     * @param parts the ordered list of conditional or unconditional parts
     */
    public record Multipart(@NotNull ConcurrentList<Part> parts) {

        /**
         * A single entry in a multipart blockstate.
         *
         * @param when the raw condition JSON, or {@code null} for unconditional parts
         * @param apply the model reference and rotation to render when the condition matches
         */
        public record Part(@Nullable JsonObject when, @NotNull Variant apply) {}

    }

    /**
     * Rendering metadata for a block entity - carries the relative bone geometry extracted from a
     * vanilla {@code BlockEntityRenderer} plus per-block presentation knobs (entity texture, dye
     * tint, icon rotation, atlas-time composition parts). Populated by {@link BlockModelLoader} for
     * the ~180 block ids whose visual appearance comes from a tile-entity renderer rather than their
     * {@code block.json}.
     *
     * @param beType vanilla {@code BlockEntityType} reference for diagnostics ({@code "minecraft:bed"})
     * @param boneModel the relative bone/cube geometry plus its render-time presentation; the renderer
     *     composes it via {@link BlockGeometryKit#buildFromBones}
     * @param textureId entity texture id bound to the {@code "#entity"} texture variable, e.g
     *     {@code "minecraft:entity/bed/red"}
     * @param tintArgb ARGB tint multiplied against every sampled texel - used for per-dye banner
     *     colouring; {@link ColorMath#WHITE} for no tint
     * @param iconRotation Y-axis rotation in degrees applied only to the atlas icon (beds use 90°
     *     to angle the headboard toward the camera)
     * @param parts atlas-time composition instructions - additional entity models merged at an
     *     offset (bed foot merged onto bed head, decorated_pot sides onto the base, banner flag
     *     onto the post). Empty for single-piece entities.
     * @param additive when {@code true}, the entity {@link #boneModel()} is merged ON TOP of the
     *     block's blockstate-resolved primary model rather than replacing it. Used for blocks
     *     whose vanilla render is "blockstate fixture + entity overlay" - the bell hangs from
     *     posts in {@code block/bell_floor.json} but its bell-cup body comes from
     *     {@code BellModel.createBodyLayer}. Default {@code false} preserves the original
     *     replace-the-model semantics used by chests / beds / banners / shulkers / signs / skulls.
     */
    public record Entity(
        @NotNull String beType,
        @NotNull BoneModel boneModel,
        @NotNull String textureId,
        int tintArgb,
        int iconRotation,
        @NotNull ConcurrentList<Part> parts,
        boolean additive
    ) {

        /**
         * The bone-format geometry for a block entity migrated onto the shared entity bone tree,
         * plus the render-time presentation transform that reproduces vanilla's
         * {@code BlockEntityRenderer} pose (formerly baked at tooling time, now applied at render).
         * The renderer composes the relative {@link #model()} through
         * {@link BlockGeometryKit#buildFromBones}, applying a {@code [0, 16]}-space presentation
         * built from {@link #inventoryYRotation()} / {@link #entityFlip()} /
         * {@link #inventoryTransform()}.
         *
         * @param model the relative bone/cube geometry in its native source frame (same schema as
         *     {@code entity_geometry.json}); {@link #sourceYUp()} says which Y convention it uses
         * @param sourceYUp whether the bones are authored Y-up (block space already, no Y-flip) or
         *     Y-down (entity space, needing the presentation's {@code cy = -cy} to reach block space)
         * @param inventoryYRotation the GUI-facing yaw in degrees applied about block centre
         *     {@code (8, 8, 8)} to face the model at the standard {@code [30, 225, 0]} iso pose
         *     (the chest's {@code +180}); the block presentation-yaw home, distinct from the entity
         *     camera-yaw {@link EntityModelData#getInventoryYRotation()} (same name, two renderers,
         *     two frames - block render reads only this one)
         * @param entityFlip whether the entity-render {@code scale(-1, -1, 1)} X negation applies on
         *     the no-inventory-transform path
         * @param inventoryTransform the decomposed {@code [tx, ty, tz, pitch, yaw, roll, scale?]}
         *     inventory transform, or {@code null} when the model takes the entity-flip path
         * @param tinted whether this model's faces carry the block's tint (vanilla {@code tintindex}
         *     0 - the banner flag's dyed cloth); when {@code false} the geometry samples its texture
         *     untinted (the banner post's wood), so {@link BlockGeometryKit#buildFromBones} receives
         *     the dye tint only for a tinted model
         */
        public record BoneModel(
            @NotNull EntityModelData model,
            boolean sourceYUp,
            float inventoryYRotation,
            boolean entityFlip,
            float @Nullable [] inventoryTransform,
            boolean tinted
        ) {

            /**
             * Builds this bone model's {@code [0, 16]}-space presentation transform - the render-time
             * pose vanilla's {@code BlockEntityRenderer} applies around the bone geometry (the bone
             * chain itself is composed by {@link BlockGeometryKit#buildFromBones}). Column-vector
             * order matches vanilla: the entity-render {@code scale(-1, -1, 1)} flip (or a decomposed
             * {@link #inventoryTransform()} of {@code scale -> Rx(pitch) -> translate}) applies first,
             * then the {@link #inventoryYRotation()} yaw about block centre {@code (8, 8, 8)}.
             *
             * @return the presentation matrix in the {@code [0, 16]} block-authoring frame
             */
            public @NotNull Matrix4f presentation() {
                float[] inv = this.inventoryTransform();
                Matrix4f pre;
                if (inv != null) {
                    // scale(invScale) -> Rx(pitch) -> translate(tx, ty, tz), matching vanilla's
                    // translate * rotate * scale composition.
                    float invScale = inv.length > 6 && inv[6] != 0f ? inv[6] : 1f;
                    float pitch = (float) Math.toRadians(inv[3]);
                    pre = Matrix4f.createTranslation(inv[0], inv[1], inv[2])
                        .multiply(Matrix4f.createRotationX(pitch))
                        .multiply(Matrix4f.createScale(invScale, invScale, invScale));
                } else {
                    // No inventory transform: vanilla's entity-render flip scale(-1, -1, 1). The X
                    // negation is gated on entityFlip (read from the item icon's display.gui roll). The
                    // Y negation maps the bones' source frame to block Y-up - needed only for Y-DOWN
                    // (entity-space) sources; a Y-UP source (chest) is already block-Y-up, so its Y stays
                    // positive (matching the former element bake's net orientation).
                    float sx = this.entityFlip() ? -1f : 1f;
                    float sy = this.sourceYUp() ? 1f : -1f;
                    pre = Matrix4f.createScale(sx, sy, 1f);
                }

                // Inventory yaw about block centre (8, 8, 8) - the chest's +180 that faces the model
                // under the standard [30, 225, 0] iso pose. All current block-entity yaws are 180
                // (symmetric, so the createRotationY sign is immaterial).
                float yaw = this.inventoryYRotation();
                if (yaw != 0f) {
                    Matrix4f yawAboutCentre = Matrix4f.createTranslation(8f, 8f, 8f)
                        .multiply(Matrix4f.createRotationY((float) Math.toRadians(yaw)))
                        .multiply(Matrix4f.createTranslation(-8f, -8f, -8f));
                    pre = yawAboutCentre.multiply(pre);
                }
                return pre;
            }

            /**
             * {@inheritDoc}
             *
             * <p>Overrides the record's generated {@code equals} so {@code inventoryTransform}
             * compares by element ({@link Arrays#equals}) rather than by reference identity.
             */
            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                BoneModel that = (BoneModel) o;
                return Float.compare(this.inventoryYRotation, that.inventoryYRotation) == 0
                    && this.sourceYUp == that.sourceYUp
                    && this.entityFlip == that.entityFlip
                    && this.tinted == that.tinted
                    && Objects.equals(this.model, that.model)
                    && Arrays.equals(this.inventoryTransform, that.inventoryTransform);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Overrides the record's generated {@code hashCode} so {@code inventoryTransform}
             * hashes by content ({@link Arrays#hashCode}), staying consistent with {@link #equals}.
             */
            @Override
            public int hashCode() {
                return Objects.hash(this.model, this.sourceYUp, this.inventoryYRotation, this.entityFlip, Arrays.hashCode(this.inventoryTransform), this.tinted);
            }

        }

        /**
         * An atlas-time composition instruction - additional geometry merged into the parent
         * {@link Entity} at a positional offset. Used when vanilla's {@code BlockEntityRenderer}
         * stitches multiple {@code LayerDefinition}s into the same in-world block render (bed head
         * + foot, decorated_pot base + sides, banner post + flag).
         * <p>
         * The renderer merges these at render time - gated on
         * {@link BlockOptions#isMergeParts()}. Atlas callers pass
         * {@code mergeParts=true} (default) to produce the composed icon; future scene callers
         * pass {@code false} to render one variant's geometry at a time.
         *
         * @param modelId source entity model id for diagnostics ({@code "minecraft:bed_foot"})
         * @param boneModel the part's relative bone geometry + presentation; the renderer composes it
         *     via {@link BlockGeometryKit#buildFromBones}
         * @param texture absolute texture id that rebinds the part's {@code "#entity"} face refs
         * @param offset model-unit shift applied to every composed vertex ({@code [0, 0, 16]} to place
         *     the bed foot one block past the head)
         */
        public record Part(
            @NotNull String modelId,
            @NotNull BoneModel boneModel,
            @NotNull String texture,
            float @NotNull [] offset
        ) {

            /**
             * {@inheritDoc}
             *
             * <p>Overrides the record's generated {@code equals} so the {@code offset} float array
             * compares by element ({@link Arrays#equals}) rather than by reference identity.
             */
            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                Part part = (Part) o;
                return Objects.equals(this.modelId, part.modelId)
                    && Objects.equals(this.boneModel, part.boneModel)
                    && Objects.equals(this.texture, part.texture)
                    && Arrays.equals(this.offset, part.offset);
            }

            /**
             * {@inheritDoc}
             *
             * <p>Overrides the record's generated {@code hashCode} so the {@code offset} float array
             * hashes by content ({@link Arrays#hashCode}), staying consistent with {@link #equals}.
             */
            @Override
            public int hashCode() {
                return Objects.hash(this.modelId, this.boneModel, this.texture, Arrays.hashCode(this.offset));
            }

        }

    }

}
