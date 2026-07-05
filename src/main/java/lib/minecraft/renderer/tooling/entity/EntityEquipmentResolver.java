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

        // Walk the constructor, opening a capture span at each `new SimpleEquipmentLayer` and closing
        // it at the matching `invokespecial SimpleEquipmentLayer.<init>`. Within a span the FIRST
        // LayerType static is the texture subdir and the FIRST ModelLayers static is the adult model's
        // geometry (a body-equipment layer may bake a second baby-model ModelLayers after it).
        boolean inSpan = false;
        String layerTypeField = null;
        String modelLayerField = null;
        for (AbstractInsnNode node = init.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof TypeInsnNode typeInsn
                && typeInsn.getOpcode() == Opcodes.NEW
                && VanillaSourceClasses.SIMPLE_EQUIPMENT_LAYER.equals(typeInsn.desc)) {
                inSpan = true;
                layerTypeField = null;
                modelLayerField = null;
                continue;
            }
            if (!inSpan) continue;
            if (node.getOpcode() == Opcodes.GETSTATIC && node instanceof FieldInsnNode field) {
                if (layerTypeField == null && VanillaSourceClasses.EQUIPMENT_LAYER_TYPE.equals(field.owner))
                    layerTypeField = field.name;
                else if (modelLayerField == null && VanillaSourceClasses.MODEL_LAYERS.equals(field.owner))
                    modelLayerField = field.name;
                continue;
            }
            if (node.getOpcode() == Opcodes.INVOKESPECIAL
                && node instanceof MethodInsnNode init2
                && VanillaSourceClasses.SIMPLE_EQUIPMENT_LAYER.equals(init2.owner)
                && AsmKit.INIT.equals(init2.name)) {
                inSpan = false;
                if (layerTypeField != null && modelLayerField != null)
                    out.add(build(layerTypeField, modelLayerField));
            }
        }
        return out;
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
        String defaultAsset = saddle ? DEFAULT_SADDLE_ASSET : DEFAULT_BODY_ASSET;
        String subdir = layerTypeField.toLowerCase(Locale.ROOT);
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
