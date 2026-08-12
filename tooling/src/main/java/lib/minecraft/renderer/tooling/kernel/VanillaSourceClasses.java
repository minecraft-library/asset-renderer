package lib.minecraft.renderer.tooling.kernel;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The only home for vanilla class / ASM / member-name strings in tooling - one class, nested
 * static groups.
 *
 * <p>Stays elsewhere by design: JVM/JDK-spec constants in {@link ClassKit}, FastTrig constants
 * nested in the geometry parser, the JOML/math surface in the transform walker - none are
 * vanilla names. Entity- and block-specific constants that are derivable do not live here
 * either - they are derivations or policy rows.
 */
public final class VanillaSourceClasses {

    private VanillaSourceClasses() {
    }

    /** Vanilla class internal names and compositional package roots. */
    public static final class Types {

        private Types() {
        }

        /** The vanilla package root - the class-listing prefix jar-wide scans anchor on. */
        public static final @NotNull String MINECRAFT_ROOT = "net/minecraft/";

        /** The model package root - the invokestatic-follow gate's positive side. */
        public static final @NotNull String CLIENT_MODEL_ROOT = MINECRAFT_ROOT + "client/model/";

        /** The geometry-primitive package root - the invokestatic-follow gate's negative side (never followed). */
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

        /** {@code BabyModelTransform} - the aged-down whole-mesh transformer, a per-top-level-bone rewrite. */
        public static final @NotNull String BABY_MODEL_TRANSFORM = CLIENT_MODEL_ROOT + "BabyModelTransform";

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

        /** {@code HumanoidArmorLayer} - the layer whose roster presence emits a wearer's worn-armor row. */
        public static final @NotNull String HUMANOID_ARMOR_LAYER = "net/minecraft/client/renderer/entity/layers/HumanoidArmorLayer";

        /** {@code ArmorModelSet} - the set of armor meshes a renderer dresses its subject in. */
        public static final @NotNull String ARMOR_MODEL_SET = "net/minecraft/client/renderer/entity/ArmorModelSet";

        /** {@code EntityModelSet} - the per-renderer registry of baked layers ({@code bakeLayer} owner on layer ctors). */
        public static final @NotNull String ENTITY_MODEL_SET = CLIENT_MODEL_GEOM_ROOT + "EntityModelSet";

        /** {@code RenderTypes} - the render-type factory class the pipeline-trait walks anchor on. */
        public static final @NotNull String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";

        /** {@code RenderType} - the factory product ({@code RenderTypes.*} return type). */
        public static final @NotNull String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";

        /** {@code OverlayTexture} - the {@code submitModel} overlay argument, the anchor its colour follows. */
        public static final @NotNull String OVERLAY_TEXTURE = "net/minecraft/client/renderer/texture/OverlayTexture";

        /** {@code RenderPipelines} - the static pipeline registry whose {@code <clinit>} build blocks carry the traits. */
        public static final @NotNull String RENDER_PIPELINES = "net/minecraft/client/renderer/RenderPipelines";

        /** {@code BlendFunction} - the blend-mode enum ({@code TRANSLUCENT} / {@code ADDITIVE} constants). */
        public static final @NotNull String BLEND_FUNCTION = "com/mojang/blaze3d/pipeline/BlendFunction";

        /** {@code DepthStencilState} - the depth-test / depth-write state a build block declares. */
        public static final @NotNull String DEPTH_STENCIL_STATE = "com/mojang/blaze3d/pipeline/DepthStencilState";

        /** {@code RenderPipeline$Snippet} - the shared builder prefix a build block inherits its state from. */
        public static final @NotNull String RENDER_PIPELINE_SNIPPET = "com/mojang/blaze3d/pipeline/RenderPipeline$Snippet";

        /** {@code RenderSetup$RenderSetupBuilder} - the render-type builder carrying {@code sortOnUpload}. */
        public static final @NotNull String RENDER_SETUP_BUILDER =
            "net/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder";

        /** {@code EquipmentClientInfo$LayerType} - the equipment texture-subdir enum ({@code <clinit>} id LDCs). */
        public static final @NotNull String EQUIPMENT_LAYER_TYPE = "net/minecraft/client/resources/model/EquipmentClientInfo$LayerType";

        /** {@code EquipmentAssets} - the static holder of equipment-asset keys ({@code TRADER_LLAMA}, ...). */
        public static final @NotNull String EQUIPMENT_ASSETS = "net/minecraft/world/item/equipment/EquipmentAssets";

        /** {@code ColorLerper$Type} - the dyed-overlay tint evaluator ({@code getColor(DyeColor)}). */
        public static final @NotNull String COLOR_LERPER_TYPE = "net/minecraft/client/color/ColorLerper$Type";

        /** {@code BlockModelRenderState} - the per-block-overlay render state block-decoration layers read (roster row 14). */
        public static final @NotNull String BLOCK_MODEL_RENDER_STATE = "net/minecraft/client/renderer/block/BlockModelRenderState";

        /** {@code BlockModelResolver} - binds a block model into a {@code BlockModelRenderState} in {@code extractRenderState}. */
        public static final @NotNull String BLOCK_MODEL_RESOLVER = "net/minecraft/client/renderer/block/BlockModelResolver";

        /** {@code com.mojang.math.Axis} - the rotation-axis constants block-overlay transforms route through. */
        public static final @NotNull String MATH_AXIS = "com/mojang/math/Axis";

        /** {@code LivingEntityRenderer} - the base declarer of {@code setupRotations}, below every override. */
        public static final @NotNull String LIVING_ENTITY_RENDERER = "net/minecraft/client/renderer/entity/LivingEntityRenderer";

        /** {@code LivingEntityRenderState} - the parameter type on renderer state methods. */
        public static final @NotNull String LIVING_ENTITY_RENDER_STATE = "net/minecraft/client/renderer/entity/state/LivingEntityRenderState";

        /** {@code EntityRenderState} - the parameter type on layer {@code submit} methods. */
        public static final @NotNull String ENTITY_RENDER_STATE = "net/minecraft/client/renderer/entity/state/EntityRenderState";

        /** {@code PoseStack} - the render-transform stack the {@code scale} override chains on. */
        public static final @NotNull String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";

        /** {@code DyeColor} - the dye enum whose WHITE diffuse colour backs the base-tint derivation. */
        public static final @NotNull String DYE_COLOR = "net/minecraft/world/item/DyeColor";

        /** {@code BannerPattern} - the patterned-tint accessor owner (the block {@code tinted} signal). */
        public static final @NotNull String BANNER_PATTERN = "net/minecraft/world/level/block/entity/BannerPattern";

        /** {@code BannerPatternLayers} - any method returning it flags a patterned-tint pipeline. */
        public static final @NotNull String BANNER_PATTERN_LAYERS = "net/minecraft/world/level/block/entity/BannerPatternLayers";

        /** {@code ModelPart} - the entity model bone primitive (its {@code visible:Z} field gates bones). */
        public static final @NotNull String MODEL_PART = CLIENT_MODEL_GEOM_ROOT + "ModelPart";

        /** {@code Model} - the base class every renderer-submitted mesh extends. */
        public static final @NotNull String MODEL = CLIENT_MODEL_ROOT + "Model";

        /** {@code EntityModel} - the model-hierarchy walk sentinel (never walked past). */
        public static final @NotNull String ENTITY_MODEL = CLIENT_MODEL_ROOT + "EntityModel";

        /** {@code EntityDataAccessor} - the key a synched entity field is registered and read under. */
        public static final @NotNull String ENTITY_DATA_ACCESSOR = "net/minecraft/network/syncher/EntityDataAccessor";

        /** {@code Byte} - the boxed carrier a synched flags byte is defined and read as. */
        public static final @NotNull String BYTE = "java/lang/Byte";

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

        /** {@code BlockEntityType} - the block-entity registry class ({@code <clinit>} id + validBlocks walk). */
        public static final @NotNull String BLOCK_ENTITY_TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";

        /** {@code BlockEntityRenderers} - the block-entity-renderer registry ({@code <clinit>} type-to-renderer walk). */
        public static final @NotNull String BLOCK_ENTITY_RENDERERS = "net/minecraft/client/renderer/blockentity/BlockEntityRenderers";

        /** {@code HangingSignBlock$Attachment} - the enum the hanging-sign factory branches on (refParam CEILING / CEILING_MIDDLE / WALL). */
        public static final @NotNull String HANGING_SIGN_ATTACHMENT = "net/minecraft/world/level/block/HangingSignBlock$Attachment";

        /**
         * {@code BlockIds} - the block-id {@code ResourceKey} table the 26.x
         * {@code register(ResourceKey, Function, Properties)} overload sources ids from.
         */
        public static final @NotNull String BLOCK_IDS = "net/minecraft/references/BlockIds";

        /** The block-state property package prefix - the tightened "is this field a Property" gate. */
        public static final @NotNull String STATE_PROPERTIES_PACKAGE = "net/minecraft/world/level/block/state/properties/";

        /** {@code IntegerProperty} - the {@code create(name, min, max)} default reads the min. */
        public static final @NotNull String INTEGER_PROPERTY = STATE_PROPERTIES_PACKAGE + "IntegerProperty";

        /** {@code BooleanProperty} - the declared-but-unset any()-default is {@code false}. */
        public static final @NotNull String BOOLEAN_PROPERTY = STATE_PROPERTIES_PACKAGE + "BooleanProperty";

        /** {@code EnumProperty} - the {@code create} overloads (class / class+array / class+predicate). */
        public static final @NotNull String ENUM_PROPERTY = STATE_PROPERTIES_PACKAGE + "EnumProperty";

        /** {@code ResourceKey} - the typed registry key the 26.x register overloads take. */
        public static final @NotNull String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";

        /** {@code Holder} - the registry-entry wrapper data-class accessors return (villager type / profession). */
        public static final @NotNull String HOLDER = "net/minecraft/core/Holder";

        /** {@code ChestSpecialRenderer} - its {@code <clinit>} binds the chest variant texture base names. */
        public static final @NotNull String CHEST_SPECIAL_RENDERER = "net/minecraft/client/renderer/special/ChestSpecialRenderer";

        /** {@code CopperGolemOxidationLevels} - its {@code <clinit>} binds the per-weather statue texture paths. */
        public static final @NotNull String COPPER_GOLEM_OXIDATION_LEVELS = "net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevels";

        /** {@code ConduitRenderer} - its {@code <clinit>} binds the conduit shell texture. */
        public static final @NotNull String CONDUIT_RENDERER = "net/minecraft/client/renderer/blockentity/ConduitRenderer";

        /** {@code BellRenderer} - its {@code <clinit>} binds the bell body texture. */
        public static final @NotNull String BELL_RENDERER = "net/minecraft/client/renderer/blockentity/BellRenderer";

        /** {@code SkullBlockRenderer} - its {@code SKIN_BY_TYPE} populate lambda binds each skull type's skin. */
        public static final @NotNull String SKULL_BLOCK_RENDERER = "net/minecraft/client/renderer/blockentity/SkullBlockRenderer";

        /** {@code SkullBlock$Types} - the enum keying the skull skin map. */
        public static final @NotNull String SKULL_BLOCK_TYPES = "net/minecraft/world/level/block/SkullBlock$Types";

        /** {@code Direction} - the block-facing enum whose {@code toYRot} / {@code getRotation} the transform walk reads as a reference yaw. */
        public static final @NotNull String DIRECTION = "net/minecraft/core/Direction";

        /** {@code RotationSegment} - the 16-segment rotation helper whose {@code convertToDegrees} the skull transform reads. */
        public static final @NotNull String ROTATION_SEGMENT = "net/minecraft/world/level/block/state/properties/RotationSegment";

        /** {@code BlockColors} - the block-tint registry whose {@code createDefault} the tint walk anchors on. */
        public static final @NotNull String BLOCK_COLORS = "net/minecraft/client/color/block/BlockColors";

        /** {@code BlockTintSources} - the static tint-source factory ({@code grass}/{@code foliage}/{@code constant}/{@code stem}/...). */
        public static final @NotNull String BLOCK_TINT_SOURCES = "net/minecraft/client/color/block/BlockTintSources";

        /** {@code BiomeColors} - the biome-average colour helper the tint-source bodies call, deriving the colormap target. */
        public static final @NotNull String BIOME_COLORS = "net/minecraft/client/renderer/BiomeColors";

        /** {@code ARGB} - the colour-packing helper the stem tint body terminates in ({@code color(r,g,b)}). */
        public static final @NotNull String ARGB = "net/minecraft/util/ARGB";

        /** {@code BlockState} - the tint-source {@code color(BlockState)} parameter (its {@code getValue} feeds the stem eval). */
        public static final @NotNull String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";

        /** {@code MobEffects} - the effect registry whose {@code <clinit>} the potion-colour walk anchors on. */
        public static final @NotNull String MOB_EFFECTS = "net/minecraft/world/effect/MobEffects";

        /** {@code MobEffectCategory} - the first parameter of the colour-carrying {@code MobEffect} constructor. */
        public static final @NotNull String MOB_EFFECT_CATEGORY = "net/minecraft/world/effect/MobEffectCategory";

        /** The effect package prefix - the {@code NEW MobEffect-or-subclass} + {@code <init>} prefix-owner gate (trailing slash for {@code startsWith}). */
        public static final @NotNull String EFFECT_PACKAGE_PREFIX = "net/minecraft/world/effect/";

        /** {@code Items} - the item registry whose {@code <clinit>} the glint walk anchors on. */
        public static final @NotNull String ITEMS = "net/minecraft/world/item/Items";

        /** {@code Item} - the field type each {@code Items.<NAME>} {@code PUTSTATIC} terminates a registration into. */
        public static final @NotNull String ITEM = "net/minecraft/world/item/Item";

        /** {@code DataComponents} - the component registry holding {@code ENCHANTMENT_GLINT_OVERRIDE} the glint walk reads. */
        public static final @NotNull String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";

    }

    /** Member-name pattern grammar: createRoots, addBox, texOffs, scaling, ... */
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

        /** {@code Entity.defineSynchedData} - where a synched field's default value is registered. */
        public static final @NotNull String DEFINE_SYNCHED_DATA = "defineSynchedData";

        /** {@code Byte.valueOf} - the box a synched flags default is registered through. */
        public static final @NotNull String VALUE_OF = "valueOf";

        /** {@code Identifier.withDefaultNamespace(String)} - the texture-path wrapping factory. */
        public static final @NotNull String WITH_DEFAULT_NAMESPACE = "withDefaultNamespace";

        /** {@code LivingEntityRenderer.addLayer(RenderLayer)} - the layer-roster call. */
        public static final @NotNull String ADD_LAYER = "addLayer";

        /** {@code <Model>.createArmorMeshSet(CubeDeformation, CubeDeformation)} - the adult armor-set factory. */
        public static final @NotNull String CREATE_ARMOR_MESH_SET = "createArmorMeshSet";

        /**
         * {@code <Model>.createArmorLayerSet(CubeDeformation, CubeDeformation)} - the same adult
         * armor-set factory under the spelling the wearers with their own mesh use.
         */
        public static final @NotNull String CREATE_ARMOR_LAYER_SET = "createArmorLayerSet";

        /**
         * {@code <Model>.createBabyArmorMeshSet(CubeDeformation, CubeDeformation, PartPose)} - the
         * baby armor-set factory, distinguished from the adult one by the trailing pose argument it
         * seats the shell's arms through.
         */
        public static final @NotNull String CREATE_BABY_ARMOR_MESH_SET = "createBabyArmorMeshSet";

        /**
         * {@code PartPose.x()} / {@code y()} / {@code z()} - the three offset accessors a mesh
         * factory reads a pose argument through, indexed by the axis each answers.
         */
        public static final @NotNull List<String> PART_POSE_OFFSETS = List.of("x", "y", "z");

        /** {@code ArmorModelSet.putFrom(ArmorModelSet, ImmutableMap$Builder)} - the armor-set registration call. */
        public static final @NotNull String PUT_FROM = "putFrom";

        /** {@code ArmorModelSet.map(Function)} - the per-slot rewrite a scaled wearer registers through. */
        public static final @NotNull String MAP = "map";

        /** {@code EntityRenderer.setupRotations} - the yaw-addend override. */
        public static final @NotNull String SETUP_ROTATIONS = "setupRotations";

        /** {@code EntityRenderer.scale} / {@code PoseStack.scale} - the scale-residue override + chain. */
        public static final @NotNull String SCALE = "scale";

        /** {@code ModelPart.getChild("<bone>")} - the bone-field cache builder. */
        public static final @NotNull String GET_CHILD = "getChild";

        /** {@code EntityRendererProvider$Context.bakeLayer(ModelLayerLocation)} - the mesh bake call. */
        public static final @NotNull String BAKE_LAYER = "bakeLayer";

        /** {@code PartPose.offset(F, F, F)} - the pivot factory the block y-axis band heuristic reads. */
        public static final @NotNull String OFFSET = "offset";

        /** {@code Block.createBlockStateDefinition} - the property-declaration method the defaults walk scans. */
        public static final @NotNull String CREATE_BLOCK_STATE_DEFINITION = "createBlockStateDefinition";

        /** {@code StateDefinition.Builder} / {@code Block.registerDefaultState} - the setValue-chain terminator. */
        public static final @NotNull String REGISTER_DEFAULT_STATE = "registerDefaultState";

        /** {@code BlockState.setValue(Property, value)} - the explicit-override call in registerDefaultState. */
        public static final @NotNull String SET_VALUE = "setValue";

        /** {@code List.forEach} - the chiseled-bookshelf {@code SLOT_OCCUPIED.forEach(builder::add)} idiom. */
        public static final @NotNull String FOR_EACH = "forEach";

        /** {@code XProperty.create(...)} - the property factory the default-value decode reads. */
        public static final @NotNull String PROPERTY_CREATE = "create";

        /** {@code SpriteId.texture()} - the sheet-sprite texture accessor. */
        public static final @NotNull String TEXTURE = "texture";

        /** {@code SpriteMapper.defaultNamespaceApply(String)} - the sprite-stem composer. */
        public static final @NotNull String DEFAULT_NAMESPACE_APPLY = "defaultNamespaceApply";

        /** {@code DyeColor.getTextureDiffuseColor} - the base-tint derivation anchor. */
        public static final @NotNull String GET_TEXTURE_DIFFUSE_COLOR = "getTextureDiffuseColor";

        /** {@code DyeColor.getTextureDiffuseColors} - the static plural tint accessor (block {@code tinted} signal). */
        public static final @NotNull String GET_TEXTURE_DIFFUSE_COLORS = "getTextureDiffuseColors";

        /** {@code <X>Variants.createKey("id")} - the data-variant holder-class key factory. */
        public static final @NotNull String CREATE_KEY = "createKey";

        /** {@code RenderLayer.coloredCutoutModelCopyLayerRender} - the tinted cutout-copy helper (implies entityCutout). */
        public static final @NotNull String COLORED_CUTOUT_HELPER = "coloredCutoutModelCopyLayerRender";

        /** {@code RenderLayer.renderColoredCutoutModel} - the per-pass cutout submit a model select feeds. */
        public static final @NotNull String RENDER_COLORED_CUTOUT_MODEL = "renderColoredCutoutModel";

        /** {@code RenderLayer.submit} - the per-layer render entry the structural gates walk. */
        public static final @NotNull String SUBMIT = "submit";

        /** {@code EntityRenderer.extractRenderState} - the state-population hook (block / gate binds). */
        public static final @NotNull String EXTRACT_RENDER_STATE = "extractRenderState";

        /** {@code PartDefinition.retainExactParts} - the subset-mesh transformer (warden spots, creaking eyes). */
        public static final @NotNull String RETAIN_EXACT_PARTS = "retainExactParts";

        /** {@code PartDefinition.clearChild} - the cube-clearing child replace a head-stripped mesh opens with. */
        public static final @NotNull String CLEAR_CHILD = "clearChild";

        /** {@code ColorLerper$Type.getColor(DyeColor)} - the dyed-overlay tint accessor. */
        public static final @NotNull String GET_COLOR = "getColor";

        /** {@code ColorLerper.getModifiedColor(DyeColor, F)} - the WHITE-branch tint literal source. */
        public static final @NotNull String GET_MODIFIED_COLOR = "getModifiedColor";

        /** {@code PoseStack.pushPose} - the block-overlay transform-block opener. */
        public static final @NotNull String PUSH_POSE = "pushPose";

        /** {@code PoseStack.popPose} - the block-overlay transform-block closer. */
        public static final @NotNull String POP_POSE = "popPose";

        /** {@code PoseStack.translate(F, F, F)} - the block-overlay translate op. */
        public static final @NotNull String TRANSLATE = "translate";

        /** {@code Axis.rotationDegrees(F)} - the block-overlay rotation op source. */
        public static final @NotNull String ROTATION_DEGREES = "rotationDegrees";

        /** {@code ModelPart.translateAndRotate(PoseStack)} - the bone bind-pose pre-application. */
        public static final @NotNull String TRANSLATE_AND_ROTATE = "translateAndRotate";

        /** {@code BlockModelResolver.update(...)} - the literal-block bind in {@code extractRenderState}. */
        public static final @NotNull String UPDATE = "update";

        /** {@code BlockColors.createDefault} - the tint-registration method the tint walk scans. */
        public static final @NotNull String CREATE_DEFAULT = "createDefault";

        /** {@code BlockColors.register} / {@code MobEffects.register} - the registration-commit call each snapshot walk terminates on. */
        public static final @NotNull String REGISTER = "register";

        /** {@code BlockTintSources.constant} - the constant-colour tint-source factory (the in-hand pick source). */
        public static final @NotNull String CONSTANT = "constant";

        /** {@code BlockTintSources.stem} - the age-driven stem tint-source factory (its body is symbolically evaluated at age 0). */
        public static final @NotNull String STEM = "stem";

        /** {@code BlockTintSource.color} / {@code ARGB.color} - the tint colour body + the packing helper the stem eval walks into. */
        public static final @NotNull String COLOR = "color";

        /** {@code BiomeColors.getAverageGrassColor} - the grass-family colormap-target derivation call. */
        public static final @NotNull String GET_AVERAGE_GRASS_COLOR = "getAverageGrassColor";

        /** {@code BiomeColors.getAverageFoliageColor} - the foliage colormap-target derivation call. */
        public static final @NotNull String GET_AVERAGE_FOLIAGE_COLOR = "getAverageFoliageColor";

        /** {@code BiomeColors.getAverageDryFoliageColor} - the dry-foliage colormap-target derivation call. */
        public static final @NotNull String GET_AVERAGE_DRY_FOLIAGE_COLOR = "getAverageDryFoliageColor";

        /** {@code BlockState.getValue(Property)} - the property read the stem eval binds to the property min. */
        public static final @NotNull String GET_VALUE = "getValue";

    }

    /** Vanilla field-name pattern grammar: the render-state members the walks anchor on. */
    public static final class Fields {

        private Fields() {
        }

        /** {@code LivingEntityRenderState.isBaby} - the age-selection flag every baby-model dispatch reads. */
        public static final @NotNull String IS_BABY = "isBaby";

        /** {@code <X>RenderState.variant} - the enum-typed variant field on 26.1 render-state classes. */
        public static final @NotNull String VARIANT = "variant";

        /** {@code DataComponents.ENCHANTMENT_GLINT_OVERRIDE} - the always-foil component the glint walk keys on. */
        public static final @NotNull String ENCHANTMENT_GLINT_OVERRIDE = "ENCHANTMENT_GLINT_OVERRIDE";

        /** {@code OverlayTexture.NO_OVERLAY} - the {@code submitModel} argument its colour argument follows. */
        public static final @NotNull String NO_OVERLAY = "NO_OVERLAY";

        /**
         * Suffix of {@code HumanoidModel.ADULT_ARMOR_PARTS_PER_SLOT} /
         * {@code BABY_ARMOR_PARTS_PER_SLOT} - the per-slot part table an armor-set factory hands its
         * fan-out, and the one thing in {@code createRoots} that says which shell a set builds.
         */
        public static final @NotNull String ARMOR_PARTS_PER_SLOT_SUFFIX = "_ARMOR_PARTS_PER_SLOT";

        /** Prefix of the aged-down member of that pair. */
        public static final @NotNull String BABY_ARMOR_PARTS_PREFIX = "BABY";

    }

    /** Descriptor composer - no hand-assembled descriptors survive anywhere. */
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

        /** The {@code ArmorModelSet} field reference descriptor - the type that names an armor mesh. */
        public static final @NotNull String ARMOR_MODEL_SET_REF = ref(Types.ARMOR_MODEL_SET);

        /** The {@code CubeDeformation} field / parameter reference descriptor - the inflate carrier. */
        public static final @NotNull String CUBE_DEFORMATION_REF = ref(Types.CUBE_DEFORMATION);

        /** The {@code MeshDefinition} return-type reference descriptor. */
        public static final @NotNull String MESH_DEFINITION_REF = ref(Types.MESH_DEFINITION);

        /** The {@code EntityDataAccessor} field reference descriptor - a synched field's registration key. */
        public static final @NotNull String ENTITY_DATA_ACCESSOR_REF = ref(Types.ENTITY_DATA_ACCESSOR);

        /**
         * Descriptor of the adult armor-set factory
         * {@code (CubeDeformation inner, CubeDeformation outer)ArmorModelSet} - the shape that
         * separates an adult set from vanilla's three-argument baby one.
         */
        public static final @NotNull String ARMOR_MESH_SET_DESC =
            of(ARMOR_MODEL_SET_REF, CUBE_DEFORMATION_REF, CUBE_DEFORMATION_REF);

        /** Descriptor of the base armor-mesh factory {@code (CubeDeformation)MeshDefinition}. */
        public static final @NotNull String BASE_ARMOR_MESH_DESC =
            of(MESH_DEFINITION_REF, CUBE_DEFORMATION_REF);

        /** The {@code PartPose} field / parameter reference descriptor - the pose carrier. */
        public static final @NotNull String PART_POSE_REF = ref(Types.PART_POSE);

        /**
         * Descriptor of the baby armor-set factory
         * {@code (CubeDeformation inner, CubeDeformation outer, PartPose armOffset)ArmorModelSet} -
         * the trailing pose is what separates it from the adult two-argument shape.
         */
        public static final @NotNull String BABY_ARMOR_MESH_SET_DESC =
            of(ARMOR_MODEL_SET_REF, CUBE_DEFORMATION_REF, CUBE_DEFORMATION_REF, PART_POSE_REF);

        /** Descriptor of a no-argument {@code float} accessor - {@code PartPose}'s three offsets. */
        public static final @NotNull String FLOAT_ACCESSOR_DESC = of("F");

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

    /** Jar resource-path grammar: textures/entity/, data/minecraft/, equipment/, colormap/. */
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

        /**
         * Drops the {@link #MINECRAFT_NAMESPACE} prefix from an id, leaving the bare path.
         *
         * @param id the id to strip
         * @return the namespace-less id, or {@code id} itself when it carries another namespace
         */
        public static @NotNull String stripNamespace(@NotNull String id) {
            return id.startsWith(MINECRAFT_NAMESPACE) ? id.substring(MINECRAFT_NAMESPACE.length()) : id;
        }

        /** The data-driven variant-table directory suffix ({@code data/minecraft/<stem>_variant/}). */
        public static final @NotNull String VARIANT_DIR_SUFFIX = "_variant";

        /** The equipment-texture subtree under {@link #TEXTURES_ENTITY} ({@code equipment/<subdir>/<material>.png}). */
        public static final @NotNull String EQUIPMENT_DIR = "equipment/";

        /** The item-model-definition directory ({@code assets/minecraft/items/<id>.json}) - the display.gui walk root. */
        public static final @NotNull String ITEM_MODEL_DIR = ASSETS_ROOT + "items/";

        /** The block/item model directory ({@code assets/minecraft/models/<path>.json}) - the display.gui parent chain. */
        public static final @NotNull String MODEL_DIR = ASSETS_ROOT + "models/";

        /** The {@code .json} resource suffix the resource walks append. */
        public static final @NotNull String JSON_SUFFIX = ".json";

        /** The texture-tree jar prefix the full asset grammar carries ({@code textures/<stem>.png}). */
        public static final @NotNull String TEXTURE_DIR = "textures/";

        /** The {@code .png} texture suffix of the full asset grammar. */
        public static final @NotNull String PNG_SUFFIX = ".png";

        /** The biome-colormap directory ({@code assets/minecraft/textures/colormap/}) the colormap policies anchor their PNGs on. */
        public static final @NotNull String COLORMAP_DIR = ASSETS_ROOT + "textures/colormap/";

    }

    /** Vanilla data-schema keys: asset_id, spawn_conditions, minecraft:select, display.gui. */
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

        /** Variant JSON - the runtime spawn-selection rules, carried verbatim into the emitted resource. */
        public static final @NotNull String SPAWN_CONDITIONS = "spawn_conditions";

        /** Spawn-condition entry - the gate sub-object whose absence marks an unconditional variant. */
        public static final @NotNull String CONDITION = "condition";

        /** Item-model / model JSON - the component / sub-object discriminator key. */
        public static final @NotNull String TYPE = "type";

        /** Item-model component - the {@code minecraft:model} type drawing a plain model ref under {@link #MODEL}. */
        public static final @NotNull String MODEL_COMPONENT = "minecraft:model";

        /** Item-model component - the {@code minecraft:special} type drawing a BER model over a {@link #BASE} model. */
        public static final @NotNull String SPECIAL_COMPONENT = "minecraft:special";

        /** Item-model component - the {@code minecraft:select} type whose {@link #FALLBACK} is the reference-pose case. */
        public static final @NotNull String SELECT_COMPONENT = "minecraft:select";

        /** Item-model {@code minecraft:special} component - the base model ref key. */
        public static final @NotNull String BASE = "base";

        /** Item-model {@code minecraft:select} component - the default-case key (the reference pose). */
        public static final @NotNull String FALLBACK = "fallback";

        /** Model JSON - the parent-model ref key the display.gui walk follows (depth-bounded). */
        public static final @NotNull String PARENT = "parent";

        /** Model JSON - the per-context display-transform block. */
        public static final @NotNull String DISPLAY = "display";

        /** Model JSON {@code display} - the inventory-icon transform ({@code rotation}/{@code translation}/{@code scale}). */
        public static final @NotNull String GUI = "gui";

        /** Model JSON {@code display.gui} - the {@code [pitch, yaw, roll]} rotation triple; roll 180 = flip. */
        public static final @NotNull String ROTATION = "rotation";

    }

    /** Shader defines: NO_CARDINAL_LIGHTING, TRANSLUCENT, withShaderDefine. */
    public static final class Defines {

        private Defines() {
        }

        /** {@code RenderPipeline$Builder.withShaderDefine} - the define-application builder call. */
        public static final @NotNull String WITH_SHADER_DEFINE = "withShaderDefine";

        /** The full-bright define - the renderer-semantic {@code emissive} trait (skip shading). */
        public static final @NotNull String NO_CARDINAL_LIGHTING = "NO_CARDINAL_LIGHTING";

        /** The emissive shader define (EYES / ENERGY_SWIRL carry it alongside {@link #NO_CARDINAL_LIGHTING}). */
        public static final @NotNull String EMISSIVE = "EMISSIVE";

        /** {@code BlendFunction.TRANSLUCENT} - the source-over translucent blend constant. */
        public static final @NotNull String TRANSLUCENT = "TRANSLUCENT";

        /** {@code BlendFunction.ADDITIVE} - the additive-glow blend constant (energy swirl). */
        public static final @NotNull String ADDITIVE = "ADDITIVE";

        /** {@code DepthStencilState.DEFAULT} - the {@code LESS_THAN_OR_EQUAL}, depth-writing state. */
        public static final @NotNull String DEPTH_STENCIL_DEFAULT = "DEFAULT";

        /** {@code RenderSetup$RenderSetupBuilder.sortOnUpload} - the back-to-front quad-sort declaration. */
        public static final @NotNull String SORT_ON_UPLOAD = "sortOnUpload";

    }

}
