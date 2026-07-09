package lib.minecraft.renderer.tooling2.kernel;

import org.jetbrains.annotations.NotNull;

/**
 * THE only home for vanilla class / ASM / member-name strings in tooling2 (SPINE decision 6) -
 * one class, nested static groups, populated per flow session as each stray literal migrates
 * in.
 *
 * <p>Stays elsewhere BY DESIGN: JVM/JDK-spec constants in {@link AsmKit} [C8], FastTrig
 * constants nested in the geometry parser [C10], the JOML/math surface in the transform
 * walker [C12] - none are vanilla names. Entity- and block-specific constants that are
 * DERIVABLE do not live here either - they are derivations or SPINE 2.1 policy rows.
 */
public final class VanillaSourceClasses {

    private VanillaSourceClasses() {
    }

    /** Vanilla class internal names + compositional package roots [C1] + migrated strays [C2]. */
    public static final class Types {

        private Types() {
        }

        /** The model package root - the P29 invokestatic-follow gate's positive side. */
        public static final @NotNull String CLIENT_MODEL_ROOT = "net/minecraft/client/model/";

        /** The geometry-primitive package root - the P29 gate's negative side (never followed). */
        public static final @NotNull String CLIENT_MODEL_GEOM_ROOT = CLIENT_MODEL_ROOT + "geom/";

        private static final @NotNull String MODEL_BUILDERS_ROOT = CLIENT_MODEL_GEOM_ROOT + "builders/";

        /** {@code PartPose} - pivot / rotation / scale factories. */
        public static final @NotNull String PART_POSE = CLIENT_MODEL_GEOM_ROOT + "PartPose";

        /** {@code PartNames} - the indexed bone-name helper class. */
        public static final @NotNull String PART_NAMES = CLIENT_MODEL_GEOM_ROOT + "PartNames";

        /** {@code LayerDefinition} - the mesh + texture-dimension carrier. */
        public static final @NotNull String LAYER_DEFINITION = MODEL_BUILDERS_ROOT + "LayerDefinition";

        /** {@code MeshTransformer} - layer-level scale / mesh-mutation wraps. */
        public static final @NotNull String MESH_TRANSFORMER = MODEL_BUILDERS_ROOT + "MeshTransformer";

        /** {@code CubeListBuilder} - the cube-chain builder. */
        public static final @NotNull String CUBE_LIST_BUILDER = MODEL_BUILDERS_ROOT + "CubeListBuilder";

        /** {@code CubeDeformation} - the inflate carrier. */
        public static final @NotNull String CUBE_DEFORMATION = MODEL_BUILDERS_ROOT + "CubeDeformation";

        /** {@code PartDefinition} - the bone-tree node. */
        public static final @NotNull String PART_DEFINITION = MODEL_BUILDERS_ROOT + "PartDefinition";

        /** {@code Mth} - vanilla's 65536-entry table trig. */
        public static final @NotNull String MTH = "net/minecraft/util/Mth";

        /** {@code RandomSource} - vanilla's seeded random factory. */
        public static final @NotNull String RANDOM_SOURCE = "net/minecraft/util/RandomSource";

        /** {@code Identifier} - the resource-location wrapper every texture binding routes through. */
        public static final @NotNull String IDENTIFIER = "net/minecraft/resources/Identifier";

        /** {@code EntityType} - the entity registry class ({@code <clinit>} + static-field scan). */
        public static final @NotNull String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

        /** {@code EntityType$Builder} - the builder companion every registration calls {@code of} on. */
        public static final @NotNull String ENTITY_TYPE_BUILDER = ENTITY_TYPE + "$Builder";

        /** {@code MobCategory} - the per-mob spawn-category enum (MONSTER, CREATURE, ...). */
        public static final @NotNull String MOB_CATEGORY = "net/minecraft/world/entity/MobCategory";

        /** {@code LivingEntity} - the mob-discovery {@code extendsClass} predicate target. */
        public static final @NotNull String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";

        /** {@code EntityRenderers} - the entity-renderer registry class. */
        public static final @NotNull String ENTITY_RENDERERS = "net/minecraft/client/renderer/entity/EntityRenderers";

        /** {@code HumanoidArmorLayer} - the armor-mesh layer whose roster presence classifies {@code armor_type}. */
        public static final @NotNull String HUMANOID_ARMOR_LAYER = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";

        /** {@code LivingEntityRenderState} - the bridge-overload parameter type on renderer state methods. */
        public static final @NotNull String LIVING_ENTITY_RENDER_STATE = "net/minecraft/client/renderer/entity/state/LivingEntityRenderState";

        /** {@code PoseStack} - the render-transform stack the {@code scale} override chains on. */
        public static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";

        /** {@code DyeColor} - the dye enum whose WHITE diffuse colour backs the base-tint derivation. */
        public static final @NotNull String DYE_COLOR = "net/minecraft/world/item/DyeColor";

        /** {@code ModelPart} - the entity model bone primitive (its {@code visible:Z} field gates bones). */
        public static final @NotNull String MODEL_PART = CLIENT_MODEL_GEOM_ROOT + "ModelPart";

        /** {@code EntityModel} - the model-hierarchy walk sentinel (never walked past). */
        public static final @NotNull String ENTITY_MODEL = CLIENT_MODEL_ROOT + "EntityModel";

        /** {@code ModelLayers} - the static-field registry of baked {@code ModelLayerLocation}s. */
        public static final @NotNull String MODEL_LAYERS = CLIENT_MODEL_GEOM_ROOT + "ModelLayers";

        /** {@code ModelLayerLocation} - the identifier wrapping a model layer (key into {@code ModelLayers}). */
        public static final @NotNull String MODEL_LAYER_LOCATION = CLIENT_MODEL_GEOM_ROOT + "ModelLayerLocation";

        /** {@code EntityRendererProvider$Context} - the bake context every renderer ctor receives. */
        public static final @NotNull String RENDERER_PROVIDER_CONTEXT = "net/minecraft/client/renderer/entity/EntityRendererProvider$Context";

        /** {@code MapColor} - the map-palette colour class ({@code DyeColor} ctor-arg anchor). */
        public static final @NotNull String MAP_COLOR = "net/minecraft/world/level/material/MapColor";

        /** {@code SpriteId} - the sheet-sprite identifier the shulker texture routes through. */
        public static final @NotNull String SPRITE_ID = "net/minecraft/client/resources/model/sprite/SpriteId";

        /** {@code SpriteMapper} - the sheet-prefix mapper composing sprite texture stems. */
        public static final @NotNull String SPRITE_MAPPER = "net/minecraft/client/renderer/SpriteMapper";

        /** {@code LayerDefinitions} - the static {@code ModelLayers -> LayerDefinition} map builder. */
        public static final @NotNull String LAYER_DEFINITIONS = CLIENT_MODEL_GEOM_ROOT + "LayerDefinitions";

        /** {@code MeshDefinition} - the pre-compile mesh factory product {@code LayerDefinition.create} wraps. */
        public static final @NotNull String MESH_DEFINITION = MODEL_BUILDERS_ROOT + "MeshDefinition";

        /** {@code Block} - the block base class (the {@code register*} overloads' return type). */
        public static final @NotNull String BLOCK = "net/minecraft/world/level/block/Block";

        /** {@code Blocks} - the block registry class ({@code <clinit>} register walk). */
        public static final @NotNull String BLOCKS = "net/minecraft/world/level/block/Blocks";

        /**
         * {@code BlockIds} - the block-id {@code ResourceKey} table the 26.x
         * {@code register(ResourceKey, Function, Properties)} overload sources ids from.
         */
        public static final @NotNull String BLOCK_IDS = "net/minecraft/references/BlockIds";

    }

    /** Member-name pattern grammar [C3]: createRoots, addBox, texOffs, scaling, ... */
    public static final class Methods {

        private Methods() {
        }

        /** {@code MeshTransformer.scaling(F)} - the layer-scale factory. */
        public static final @NotNull String SCALING = "scaling";

        /** {@code EntityType$Builder.of(EntityFactory, MobCategory)} - the registration anchor. */
        public static final @NotNull String BUILDER_OF = "of";

        /** {@code LayerDefinitions.createRoots} - the ModelLayers-to-LayerDefinition map builder. */
        public static final @NotNull String CREATE_ROOTS = "createRoots";

        /** {@code LayerDefinition.create(MeshDefinition, W, H)} - the mesh-wrapping factory. */
        public static final @NotNull String CREATE = "create";

        /** {@code LayerDefinition.apply(MeshTransformer)} - the out-of-body scale chain. */
        public static final @NotNull String APPLY = "apply";

        /** {@code EntityRenderer.getTextureLocation} - the texture-binding override. */
        public static final @NotNull String GET_TEXTURE_LOCATION = "getTextureLocation";

        /** {@code Identifier.withDefaultNamespace(String)} - the texture-path wrapping factory. */
        public static final @NotNull String WITH_DEFAULT_NAMESPACE = "withDefaultNamespace";

        /** {@code LivingEntityRenderer.addLayer(RenderLayer)} - the layer-roster call. */
        public static final @NotNull String ADD_LAYER = "addLayer";

        /** {@code EntityRenderer.setupRotations} - the yaw-addend override. */
        public static final @NotNull String SETUP_ROTATIONS = "setupRotations";

        /** {@code EntityRenderer.scale} / {@code PoseStack.scale} - the scale-residue override + chain. */
        public static final @NotNull String SCALE = "scale";

        /** {@code ModelPart.getChild("<bone>")} - the bone-field cache builder. */
        public static final @NotNull String GET_CHILD = "getChild";

        /** {@code EntityRendererProvider$Context.bakeLayer(ModelLayerLocation)} - the mesh bake call. */
        public static final @NotNull String BAKE_LAYER = "bakeLayer";

        /** {@code SpriteId.texture()} - the sheet-sprite texture accessor. */
        public static final @NotNull String TEXTURE = "texture";

        /** {@code SpriteMapper.defaultNamespaceApply(String)} - the sprite-stem composer. */
        public static final @NotNull String DEFAULT_NAMESPACE_APPLY = "defaultNamespaceApply";

        /** {@code DyeColor.getTextureDiffuseColor} - the base-tint derivation anchor. */
        public static final @NotNull String GET_TEXTURE_DIFFUSE_COLOR = "getTextureDiffuseColor";

    }

    /** Descriptor composer [C4] - no hand-assembled descriptors survive anywhere. */
    public static final class Descs {

        private Descs() {
        }

        /** The {@code MeshTransformer} field / return-type reference descriptor. */
        public static final @NotNull String MESH_TRANSFORMER_REF = ref(Types.MESH_TRANSFORMER);

        /** The {@code EntityType} field descriptor every registry static field shares. */
        public static final @NotNull String ENTITY_TYPE_REF = ref(Types.ENTITY_TYPE);

        /** The {@code Identifier} field / return-type reference descriptor. */
        public static final @NotNull String IDENTIFIER_REF = ref(Types.IDENTIFIER);

        /** The {@code ModelPart} field / return-type reference descriptor. */
        public static final @NotNull String MODEL_PART_REF = ref(Types.MODEL_PART);

        /**
         * Composes a method descriptor from already-valid type descriptors.
         *
         * @param ret the return-type descriptor ({@code V}, {@code F}, {@code Lx/Y;})
         * @param params the parameter-type descriptors in order
         * @return the composed method descriptor
         */
        public static @NotNull String of(@NotNull String ret, @NotNull String @NotNull ... params) {
            StringBuilder out = new StringBuilder("(");
            for (String param : params) out.append(param);
            return out.append(')').append(ret).toString();
        }

        /**
         * Wraps a class internal name as a reference-type descriptor ({@code L<name>;}).
         *
         * @param internalName the class internal name
         * @return the reference descriptor
         */
        public static @NotNull String ref(@NotNull String internalName) {
            return "L" + internalName + ";";
        }

    }

    /** Jar resource-path grammar [C5]: textures/entity/, data/minecraft/, equipment/, colormap/. */
    public static final class Paths {

        private Paths() {
        }

        /** The vanilla asset-namespace jar prefix. */
        public static final @NotNull String ASSETS_ROOT = "assets/minecraft/";

        /** The vanilla data-namespace jar prefix (variant tables live under it). */
        public static final @NotNull String DATA_ROOT = "data/minecraft/";

        /** The entity-texture path prefix every renderer texture LDC starts with. */
        public static final @NotNull String TEXTURES_ENTITY = "textures/entity/";

        /** The vanilla resource namespace prefix. */
        public static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

        /** The data-driven variant-table directory suffix ({@code data/minecraft/<stem>_variant/}). */
        public static final @NotNull String VARIANT_DIR_SUFFIX = "_variant";

    }

    /** Vanilla data-schema keys [C6]: asset_id, spawn_conditions, minecraft:select, display.gui. */
    public static final class DataKeys {

        private DataKeys() {
        }

        /** Variant JSON - the single adult texture resource id. */
        public static final @NotNull String ASSET_ID = "asset_id";

        /** Variant JSON - the single baby texture resource id. */
        public static final @NotNull String BABY_ASSET_ID = "baby_asset_id";

        /** Variant JSON - the per-state adult texture map (wolf {@code wild}/{@code tame}/{@code angry}). */
        public static final @NotNull String ASSETS = "assets";

        /** Variant JSON - the per-state baby texture map. */
        public static final @NotNull String BABY_ASSETS = "baby_assets";

        /** Variant JSON - the model discriminator ({@code "cold"} selects {@code ColdCowModel}). */
        public static final @NotNull String MODEL = "model";

        /** Variant JSON - the runtime spawn-selection rules, carried VERBATIM into v2 [D64]. */
        public static final @NotNull String SPAWN_CONDITIONS = "spawn_conditions";

    }

    /** Shader defines [C7]: NO_CARDINAL_LIGHTING, TRANSLUCENT, withShaderDefine. */
    public static final class Defines {

        private Defines() {
        }

    }

}
