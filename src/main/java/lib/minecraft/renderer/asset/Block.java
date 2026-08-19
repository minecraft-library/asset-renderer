package lib.minecraft.renderer.asset;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTransform;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.pipeline.loader.BlockModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fully-parsed block definition backed by its vanilla model JSON and blockstate variants.
 * <p>
 * Every field is populated once during {@code ClientAcquisition} bootstrap and stored verbatim; no
 * lazy or computed fields live on this DTO. Lookup happens through the active renderer
 * context.
 *
 * @param id the block's namespaced identifier (e.g. {@code minecraft:furnace})
 * @param model the resolved model supplying the block's geometry and texture bindings
 * @param textures the model's texture variable bindings, keyed by variable name
 * @param variants the blockstate variants keyed by their sorted {@code property=value} state key,
 *     each carrying a resolved model and whole-block rotation
 * @param multipart the multipart blockstate definition, or empty when the block uses discrete
 *     variants
 * @param tags tag names this block belongs to, e.g.
 *     {@code ["minecraft:stairs", "minecraft:wooden_stairs"]}
 * @param tint the biome tint binding selecting which colormap or constant the renderer samples for
 *     tinted faces
 * @param entity rendering override for blocks whose visual geometry comes from a vanilla
 *     {@code BlockEntityRenderer} (beds, chests, banners, shulkers, signs, skulls, conduit,
 *     decorated_pot, etc.). When present, renderers prefer {@link Entity#boneModel()} over
 *     {@link #model()}, multiply {@link Entity#tintArgb()} against sampled texels, honour
 *     {@link Entity#iconRotation()} for the atlas icon, and optionally compose {@link Entity#parts()}
 *     for multi-part atlas views (bed head + foot, decorated_pot body + sides, banner post + flag).
 *     Absent for plain blocks
 * @param source where this block's registration originated. Used by atlas tile classification to
 *     label the source path (block-model file, blockstate-only fallback, or block-entity geometry
 *     override) without forcing consumers to type-check the renderer-context implementation
 * @param defaultState the block's default blockstate as a parsed {@code property -> value} map (e.g.
 *     {@code {facing=north, half=lower, hinge=left, open=false, powered=false}}), or empty when the
 *     block has no properties. Sourced from {@code block_defaults.json} (an ASM bytewalk of
 *     {@code registerDefaultState}) and baked on at pipeline-context construction. The renderer falls
 *     back to this state when a caller supplies no explicit variant, so blocks with per-state models
 *     render their default rather than whichever state registered first
 * @param itemBlockId the id of the block whose inventory item this block's icon poses through - the
 *     block's own id for a block that owns an item, or the standing block's id for a secondary block
 *     that shares another block's item (wall banner to banner, wall skull to skull, filled cauldron
 *     to cauldron, {@code big_dripleaf_stem} to {@code big_dripleaf}). Sourced from
 *     {@code block_items.json} (an ASM walk of the {@code Items} registry) and baked on at
 *     pipeline-context construction, so the icon renderer resolves the shared item's
 *     {@code display.gui} the same way the in-game inventory does
 * @param iconGui the resolved {@code display.gui} transform this block's inventory icon poses through -
 *     the {@link #itemBlockId() item-block}'s gui (a {@code special} leaf resolves to its {@code base}
 *     model's gui, a plain leaf to its own resolved model), falling back to this block's own model gui,
 *     or empty when no gui is authored anywhere. Baked at index build so the icon renderer reads it
 *     without walking the item dispatch tree at render time
 * @param modelIcon whether vanilla draws this block's inventory icon from a block model rather than a
 *     flat item sprite - true exactly when the block-item's tree is a plain {@code model} root naming
 *     a block model, which is what {@link #model()} then holds. Vanilla bakes such an icon at the
 *     identity model state, so it carries neither a blockstate variant's rotation nor a multipart
 *     assembly; a false here means vanilla's icon is a sprite (doors, wall torches, comparators) or a
 *     block-entity renderer's mesh, and the 3D render is this pipeline's own stand-in
 */
public record Block(
    @NotNull ResourceId id,
    @NotNull ModelData model,
    @NotNull ConcurrentMap<String, String> textures,
    @NotNull ConcurrentMap<String, Variant> variants,
    @NotNull Optional<Multipart> multipart,
    @NotNull ConcurrentList<String> tags,
    @NotNull Tint tint,
    @NotNull Optional<Entity> entity,
    @NotNull Source source,
    @NotNull ConcurrentMap<String, String> defaultState,
    @NotNull ResourceId itemBlockId,
    @NotNull Optional<ModelTransform> iconGui,
    boolean modelIcon
) {

    /**
     * The canonical joined default-state key ({@code "prop=val,.."}, property-sorted; {@code ""} when
     * the block declares no properties) - the serialized projection of {@link #defaultState()} kept for
     * the parity dump and back-compat callers. The renderer reads {@link #defaultState()} directly
     * everywhere but the carried-block variant lookup, which keys {@link #variants()} by this string
     * once per block-overlay row per frame; a default state carries at most one property, so the join
     * it runs there is a single token.
     *
     * @return the joined default-state key
     */
    public @NotNull String defaultStateKey() {
        return BlockStateKey.join(this.defaultState);
    }

    /**
     * Returns this block's texture reference for the first of {@code directionKeys} that is bound in
     * {@link #textures()}, or {@code ""} when none is. Callers pass the fallback chain in priority
     * order - e.g. {@code textureRef(direction, "all", "side", "particle")} - so the block owns its
     * own first-bound resolution instead of each call site cascading {@code getOrDefault}.
     *
     * @param directionKeys the texture-map keys to try, in priority order
     * @return the first bound texture ref, or {@code ""} when none of the keys is bound
     */
    public @NotNull String textureRef(@NotNull String @NotNull ... directionKeys) {
        for (String key : directionKeys) {
            String ref = this.textures.get(key);
            if (ref != null) return ref;
        }
        return "";
    }

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
     *
     * <p>Each constant carries every answer the tint resolution asks of a target - the pack
     * {@code color.properties} key prefix, the colormap it samples, the colour it falls back to and
     * whether the biome's grass modifier reaches it - so resolving a tint reads this table rather
     * than switching on the constant. A target carrying no {@link #packKeyPrefix() key prefix}
     * carries no biome channel at all, which is what separates {@link #NONE} and {@link #CONSTANT}
     * from the four that resolve against a biome.
     */
    public enum TintTarget {

        /**
         * The face is not biome-tinted.
         */
        NONE(Optional.empty(), Optional.empty(), ColorMath.WHITE, false),

        /**
         * Sample the grass colormap. Applies to grass blocks, tall grass, ferns, etc. The one
         * target the biome's grass colour modifier reaches.
         */
        GRASS(Optional.of("grass."), Optional.of("grass"), ColorMath.WHITE, true),

        /**
         * Sample the foliage colormap. Applies to most leaves.
         */
        FOLIAGE(Optional.of("foliage."), Optional.of("foliage"), ColorMath.WHITE, false),

        /**
         * Sample the dry-foliage colormap. Applies to pale oak and a handful of other biomes.
         */
        DRY_FOLIAGE(Optional.of("dryfoliage."), Optional.of("dry_foliage"), ColorMath.WHITE, false),

        /**
         * Use the biome's water colour override when present, or the engine-level default
         * {@code 0xFF3F76E4} otherwise. Vanilla water has no colormap; biomes either carry an
         * explicit {@code water_color} value or inherit the default, which is why this is the one
         * target whose {@link #defaultArgb() default} is a colour rather than white.
         */
        WATER(Optional.of("water."), Optional.empty(), 0xFF3F76E4, false),

        /**
         * Use the {@link Tint#constant() constant} ARGB carried on the block's {@link Tint}
         * directly. Applies to redstone wire, stems, etc.
         */
        CONSTANT(Optional.empty(), Optional.empty(), ColorMath.WHITE, false);

        /**
         * The {@code color.properties} key prefix a pack addresses this target's per-biome override
         * under ({@code grass.}, {@code water.}), empty for a target with no biome channel. Note
         * {@code dryfoliage.} carries no separator inside the word where the colormap name does.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull Optional<String> packKeyPrefix;

        /**
         * The colormap this target samples, named as it appears under
         * {@code textures/colormap/<name>.png}, empty for a target that samples none.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final @NotNull Optional<String> colorMapName;

        /**
         * The ARGB this target resolves to when neither a pack nor the biome answered and no
         * colormap is registered.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final int defaultArgb;

        /**
         * Whether the biome's {@code GrassColorModifier} post-processes this target's colour.
         * Vanilla runs it on the grass tint alone.
         */
        @Getter(style = NamingStyle.FLUENT)
        private final boolean grassModified;

        TintTarget(
            @NotNull Optional<String> packKeyPrefix,
            @NotNull Optional<String> colorMapName,
            int defaultArgb,
            boolean grassModified
        ) {
            this.packKeyPrefix = packKeyPrefix;
            this.colorMapName = colorMapName;
            this.defaultArgb = defaultArgb;
            this.grassModified = grassModified;
        }

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
    public record Tint(@NotNull TintTarget target, @NotNull Optional<Color> constant) {

        /** Normalises an absent {@code constant} (a colormap-target tint, or a Gson-omitted member) to empty. */
        public Tint {
            if (constant == null) constant = Optional.empty();
        }
    }

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
     * @param boneModel the variant's relative bone geometry plus its render-time presentation
     */
    public record BoneGeometry(@NotNull Entity.BoneModel boneModel) implements VariantGeometry {}

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
     * @param properties the variant's blockstate key parsed once at load into a {@code property -> value}
     *     map ({@code facing=east,half=lower} to {@code {facing=east, half=lower}}); empty for a
     *     multipart apply, which matches through its {@code when} condition rather than a key
     * @param noPosition the entry vanilla draws for this key when the draw has no world position behind
     *     it - a block an entity holds or carries - resolved once at index build, or empty when the key
     *     authored a single variant and so offers no choice. Vanilla picks between an authored array's
     *     entries at random, seeded by the block's position; with no position it seeds with a constant
     *     instead, which makes the choice fixed rather than arbitrary. Present for the 34 blocks that
     *     author such an array, and its own {@code noPosition} is always empty
     */
    public record Variant(@NotNull String modelId, int x, int y, boolean uvlock,
                          @NotNull VariantGeometry geometry, @NotNull ConcurrentMap<String, String> properties,
                          @NotNull Optional<Variant> noPosition) {

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
         * @param when the parsed condition, {@link When.Always} for unconditional parts
         * @param apply the model reference and rotation to render when the condition matches
         */
        public record Part(@NotNull When when, @NotNull Variant apply) {}

        /**
         * A parsed multipart {@code "when"} condition, evaluated against a block's resolved property
         * map. The vanilla forms map one-to-one: an {@code "AND"} array to {@link All}, an {@code "OR"}
         * array to {@link Any}, a property object to {@link Match} (each value's {@code |} alternation
         * pre-split at load), and an absent condition to {@link Always}. {@code AND} takes precedence
         * over {@code OR}, which takes precedence over a plain property object.
         */
        public sealed interface When permits When.All, When.Any, When.Match, When.Always {

            /**
             * Reports whether this condition holds for a block's resolved properties. A property the
             * block does not declare reads as the empty string.
             *
             * @param properties the block's {@code property -> value} map
             * @return whether the condition matches
             */
            boolean matches(@NotNull ConcurrentMap<String, String> properties);

            /**
             * All sub-conditions must match - the {@code "AND"} form.
             *
             * @param terms the conjoined sub-conditions, in author order
             */
            record All(@NotNull List<When> terms) implements When {

                @Override
                public boolean matches(@NotNull ConcurrentMap<String, String> properties) {
                    for (When term : this.terms)
                        if (!term.matches(properties)) return false;
                    return true;
                }
            }

            /**
             * At least one sub-condition must match - the {@code "OR"} form.
             *
             * @param terms the alternative sub-conditions, in author order
             */
            record Any(@NotNull List<When> terms) implements When {

                @Override
                public boolean matches(@NotNull ConcurrentMap<String, String> properties) {
                    for (When term : this.terms)
                        if (term.matches(properties)) return true;
                    return false;
                }
            }

            /**
             * Every named property must equal one of its allowed values - a property object. Each
             * value's {@code |} alternation is pre-split at load, so {@code "side|up"} becomes
             * {@code ["side", "up"]} and a lone {@code "true"} becomes {@code ["true"]}.
             *
             * @param required each property name to its allowed values
             */
            record Match(@NotNull Map<String, List<String>> required) implements When {

                @Override
                public boolean matches(@NotNull ConcurrentMap<String, String> properties) {
                    for (Map.Entry<String, List<String>> entry : this.required.entrySet())
                        if (!entry.getValue().contains(properties.getOrDefault(entry.getKey(), ""))) return false;
                    return true;
                }
            }

            /**
             * The unconditional part - no {@code "when"} key; always matches.
             */
            record Always() implements When {

                @Override
                public boolean matches(@NotNull ConcurrentMap<String, String> properties) {
                    return true;
                }
            }
        }

    }

    /**
     * Rendering metadata for a block entity - carries the relative bone geometry extracted from a
     * vanilla {@code BlockEntityRenderer} plus per-block presentation knobs (entity texture, dye
     * tint, icon rotation, atlas-time composition parts). Populated by {@link BlockModelLoader} for
     * the ~180 block ids whose visual appearance comes from a tile-entity renderer rather than their
     * {@code block.json}.
     *
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
        @EqualsAndHashCode
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
         * @param boneModel the part's relative bone geometry + presentation; the renderer composes it
         *     via {@link BlockGeometryKit#buildFromBones}
         * @param texture absolute texture id that rebinds the part's {@code "#entity"} face refs
         * @param offset model-unit shift applied to every composed vertex ({@code [0, 0, 16]} to place
         *     the bed foot one block past the head)
         */
        @EqualsAndHashCode
        public record Part(
            @NotNull BoneModel boneModel,
            @NotNull String texture,
            float @NotNull [] offset
        ) {}

    }

}
