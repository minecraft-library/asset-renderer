package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Locale;

/**
 * Bytecode-driven discovery of the generic equipment overlays (saddle, body armor) a mob renderer
 * attaches via {@code addLayer(new SimpleEquipmentLayer(...))}. Each such constructor carries
 * everything the static-pose render needs, extractable from the {@code <init>} bytecode:
 *
 * <ul>
 *   <li>{@code GETSTATIC EquipmentClientInfo$LayerType.<X>} - the equipment texture <b>subdir</b>
 *       (the enum constant name lower-cased: {@code PIG_SADDLE} -&gt; {@code pig_saddle},
 *       {@code HORSE_BODY} -&gt; {@code horse_body}), and the {@link Result#slot() slot} (a name
 *       containing {@code SADDLE} is the saddle slot, otherwise the body slot).</li>
 *   <li>{@code GETSTATIC ModelLayers.<X>} - the equipment <b>geometry</b> field baked into the
 *       layer's adult {@code EntityModel} ({@code ModelLayers.PIG_SADDLE},
 *       {@code ModelLayers.HORSE_ARMOR}). This is the {@code _SADDLE}/{@code _ARMOR} field the
 *       primary-geometry picker in {@link EntityLayerDefinitionResolver} deliberately skips; the
 *       equipment overlay resolves it through {@code layerDefs} to bake a distinct mesh.</li>
 * </ul>
 *
 * <p>The layer constructor takes no static default item asset - the texture is chosen at render from
 * the equipped item's data component. For the static icon the resolver supplies a
 * {@link Result#defaultAsset() default asset} per slot ({@code saddle} for the single saddle item,
 * {@code leather} as the baseline armor material); a caller overrides it via the equipment axis.
 *
 * <p>Wolf armor is NOT a {@code SimpleEquipmentLayer} (it uses a bespoke {@code WolfArmorLayer}) and
 * is handled separately; the llama carpet ({@code LlamaDecorLayer}) is handled by
 * {@link EntityOverlayResolver}.
 */
@UtilityClass
public final class EntityEquipmentResolver {

    /** Equipment slot id for a saddle overlay ({@code *_SADDLE} layer types). */
    public static final @NotNull String SLOT_SADDLE = "saddle";

    /** Equipment slot id for a body-armor / harness overlay ({@code *_BODY} layer types). */
    public static final @NotNull String SLOT_BODY = "body";

    /** Default asset for the saddle slot - there is a single saddle item, asset id {@code saddle}. */
    private static final @NotNull String DEFAULT_SADDLE_ASSET = "saddle";

    /** Default armor material for the body slot - the baseline dyeable-leather armor. */
    private static final @NotNull String DEFAULT_BODY_ASSET = "leather";

    /** Default material for the wolf body slot - wolf armor is a single dyeable armadillo-scute item. */
    private static final @NotNull String DEFAULT_WOLF_BODY_ASSET = "armadillo_scute";

    /** The wolf body equipment subdir, whose only material is {@link #DEFAULT_WOLF_BODY_ASSET}. */
    private static final @NotNull String WOLF_BODY_SUBDIR = "wolf_body";

    /**
     * Resolves the equipment overlays a renderer attaches via {@code SimpleEquipmentLayer}. Returns
     * an empty list when the renderer attaches none (the common case).
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer class JVM internal name
     * @return one {@link Result} per {@code SimpleEquipmentLayer} the renderer's {@code <init>}
     *     constructs, in constructor order
     */
    public static @NotNull ConcurrentList<Result> resolve(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        ConcurrentList<Result> out = Concurrent.newList();
        ClassNode renderer = classNodes.load(rendererInternalName);
        if (renderer == null) return out;
        MethodNode init = AsmKit.findMethod(renderer, AsmKit.INIT);
        if (init == null) return out;

        // Walk the constructor tracking the current equipment candidate's LayerType + ModelLayers. A
        // `LayerType.<X>` static opens a candidate (it always precedes the equipment's geometry), and
        // the FIRST `ModelLayers.<X>` after it is the adult mesh (a body layer may bake a second
        // baby-model ModelLayers, ignored). The candidate emits when a SimpleEquipmentLayer is
        // constructed - either directly (`new SimpleEquipmentLayer(...)` -> its `<init>`) or through a
        // factory helper returning one (`createCamelSaddleLayer(...)` for camel / camel_husk, whose
        // LayerType + ModelLayers are passed at the call site rather than baked inline).
        String layerTypeField = null;
        String modelLayerField = null;
        for (AbstractInsnNode node = init.instructions.getFirst(); node != null; node = node.getNext()) {
            // Bespoke equipment layer: WolfArmorLayer isn't a SimpleEquipmentLayer - it bakes its own
            // ModelLayers.WOLF_ARMOR + uses LayerType.WOLF_BODY inside its own class, so resolve it from
            // there rather than from statics in this renderer's <init>.
            if (node instanceof TypeInsnNode typeInsn
                && typeInsn.getOpcode() == Opcodes.NEW
                && VanillaSourceClasses.WOLF_ARMOR_LAYER.equals(typeInsn.desc)) {
                Result bespoke = resolveBespokeLayer(classNodes, typeInsn.desc);
                if (bespoke != null) out.add(bespoke);
                continue;
            }
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode field) {
                if (VanillaSourceClasses.EQUIPMENT_LAYER_TYPE.equals(field.owner)) {
                    // A LayerType static starts a fresh candidate: reset the geometry so a primary /
                    // baby ModelLayers baked earlier (the super(...) body mesh) can't leak into it.
                    layerTypeField = field.name;
                    modelLayerField = null;
                } else if (layerTypeField != null && modelLayerField == null
                    && VanillaSourceClasses.MODEL_LAYERS.equals(field.owner)) {
                    modelLayerField = field.name;
                }
                continue;
            }
            if (isEquipmentConstruction(node)) {
                if (layerTypeField != null && modelLayerField != null && !isDeferredEquipment(layerTypeField))
                    out.add(build(layerTypeField, modelLayerField));
                layerTypeField = null;
                modelLayerField = null;
            }
        }
        return out;
    }

    /**
     * Whether a {@code SimpleEquipmentLayer} is deferred from equipment emission. The happy-ghast
     * harness ({@code HAPPY_GHAST_BODY}) is: its {@code createHarnessLayer} mesh is authored in the
     * body model's own (internally 4x-scaled) coordinate frame and dyed by per-colour
     * {@code <colour>_harness} textures, so it renders correctly only through the body model's pose -
     * the standalone-mesh equipment path renders it 4x too small and mispositioned. It needs the
     * body-pose render path (a follow-up), so it is excluded rather than emitted as a hidden layer.
     *
     * @param layerTypeField the {@code EquipmentClientInfo$LayerType} constant name
     * @return whether the equipment layer is deferred
     */
    private static boolean isDeferredEquipment(@NotNull String layerTypeField) {
        return layerTypeField.startsWith("HAPPY_GHAST");
    }

    /**
     * Resolves a bespoke equipment layer (WolfArmorLayer) that bakes its own geometry and references
     * its {@code LayerType} internally rather than via statics in the parent renderer's {@code <init>}.
     * Walks the layer class for the first {@code LayerType.<X>} (in its {@code submit}) + the first
     * {@code ModelLayers.<X>} (baked in its {@code <init>}).
     *
     * @param classNodes the ClassNode cache
     * @param layerInternalName the bespoke layer class JVM internal name
     * @return the resolved equipment result, or {@code null} when the class isn't loadable or the
     *     LayerType / ModelLayers pair can't be found
     */
    private static @Nullable Result resolveBespokeLayer(@NotNull ClassNodeCache classNodes, @NotNull String layerInternalName) {
        ClassNode layer = classNodes.load(layerInternalName);
        if (layer == null) return null;
        String layerTypeField = null;
        String modelLayerField = null;
        for (MethodNode method : layer.methods)
            for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
                if (node.getOpcode() != Opcodes.GETSTATIC || !(node instanceof FieldInsnNode field)) continue;
                if (layerTypeField == null && VanillaSourceClasses.EQUIPMENT_LAYER_TYPE.equals(field.owner))
                    layerTypeField = field.name;
                else if (modelLayerField == null && VanillaSourceClasses.MODEL_LAYERS.equals(field.owner))
                    modelLayerField = field.name;
            }
        return layerTypeField != null && modelLayerField != null ? build(layerTypeField, modelLayerField) : null;
    }

    /**
     * Whether {@code node} constructs a {@code SimpleEquipmentLayer} - directly (its {@code <init>})
     * or through a factory helper method that returns one (camel's {@code createCamelSaddleLayer}).
     *
     * @param node the instruction to test
     * @return whether it produces a {@code SimpleEquipmentLayer}
     */
    private static boolean isEquipmentConstruction(@NotNull AbstractInsnNode node) {
        if (!(node instanceof MethodInsnNode method)) return false;
        if (node.getOpcode() == Opcodes.INVOKESPECIAL
            && VanillaSourceClasses.SIMPLE_EQUIPMENT_LAYER.equals(method.owner)
            && AsmKit.INIT.equals(method.name))
            return true;
        return (node.getOpcode() == Opcodes.INVOKESTATIC || node.getOpcode() == Opcodes.INVOKEVIRTUAL)
            && AsmKit.descriptorReturns(method.desc, VanillaSourceClasses.SIMPLE_EQUIPMENT_LAYER);
    }

    /**
     * Builds a {@link Result} from a captured layer-type + model-layer field pair, deriving the slot
     * and default asset from the layer-type name.
     *
     * @param layerTypeField the {@code EquipmentClientInfo$LayerType} enum constant name (e.g. {@code PIG_SADDLE})
     * @param modelLayerField the {@code ModelLayers} geometry field name (e.g. {@code PIG_SADDLE})
     * @return the resolved equipment overlay descriptor
     */
    private static @NotNull Result build(@NotNull String layerTypeField, @NotNull String modelLayerField) {
        boolean saddle = layerTypeField.contains("SADDLE");
        String slot = saddle ? SLOT_SADDLE : SLOT_BODY;
        String subdir = layerTypeField.toLowerCase(Locale.ROOT);
        String defaultAsset = saddle ? DEFAULT_SADDLE_ASSET
            : WOLF_BODY_SUBDIR.equals(subdir) ? DEFAULT_WOLF_BODY_ASSET
            : DEFAULT_BODY_ASSET;
        return new Result(slot, subdir, modelLayerField, defaultAsset);
    }

    /**
     * One equipment overlay a renderer attaches: its slot, texture subdir, geometry field, and the
     * default asset the static icon shows when the caller selects the slot without a material.
     *
     * @param slot the equipment slot id ({@link #SLOT_SADDLE} / {@link #SLOT_BODY})
     * @param layerSubdir the equipment texture subdir ({@code pig_saddle}, {@code horse_body}); the
     *     texture path is {@code equipment/<layerSubdir>/<material>.png}
     * @param modelLayerField the {@code ModelLayers} field name whose baked mesh the overlay renders
     * @param defaultAsset the default material/asset id when the slot is selected without one
     */
    public record Result(
        @NotNull String slot,
        @NotNull String layerSubdir,
        @NotNull String modelLayerField,
        @NotNull String defaultAsset
    ) {}

    /**
     * Whether {@code layerClass} is the generic {@code SimpleEquipmentLayer} - used by callers that
     * scan a renderer's ordered layer-class list.
     *
     * @param layerClass the layer class JVM internal name
     * @return whether it is the {@code SimpleEquipmentLayer}
     */
    public static boolean isEquipmentLayer(@Nullable String layerClass) {
        return VanillaSourceClasses.SIMPLE_EQUIPMENT_LAYER.equals(layerClass);
    }

}
