package lib.minecraft.refharness;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolver for a block's authored {@code display.gui} transform, shared by
 * {@link BlockFrameRenderer} and {@link BlockEntityFrameRenderer}.
 *
 * <p>A block's gui transform is not carried by its baked {@code BlockStateModel} (geometry only);
 * it survives only on the block's baked item model as a {@link ModelRenderProperties}. This resolves
 * a {@link BlockState} to its item model via {@link net.minecraft.client.renderer.item.ItemModelResolver}'s
 * lookup ({@code DataComponents.ITEM_MODEL} identifier plus {@code ModelManager.getItemModel}), then
 * reads the private {@code ModelRenderProperties} field the standard cuboid / special / missing item
 * models hold and pulls its {@link ItemDisplayContext#GUI} {@link ItemTransform}.
 *
 * <p>Stairs resolve to {@code [30,135,0]}, fence gates to {@code [30,45,0]}; standard full-cube
 * blocks inherit {@code block/block.json}'s {@code [30,225,0]} + scale {@code 0.625}. When the item
 * has no model identifier, the item model exposes no readable properties, or the gui transform is the
 * identity sentinel, the resolver returns {@link #DEFAULT_BLOCK_GUI} - the same {@code [30,225,0]}
 * pose the harness applied unconditionally before.
 */
final class BlockGuiTransform {

    private BlockGuiTransform() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Degrees-to-radians factor {@link ItemTransform#apply} itself uses, mirrored by {@link #applyFitNeutral}. */
    private static final float DEG_TO_RAD = 0.017453292f;

    /**
     * Fallback gui transform for blocks with no readable authored one - vanilla's
     * {@code block/block.json} default: rotation {@code [30,225,0]} degrees, no translation, uniform
     * scale {@code 0.625}. Applying this via {@link ItemTransform#apply} reproduces the harness's
     * former hard-coded iso pose (rotation, {@code 0.625} scale, {@code T(-0.5)} centring).
     */
    static final ItemTransform DEFAULT_BLOCK_GUI = new ItemTransform(
        new Vector3f(30f, 225f, 0f),
        new Vector3f(0f, 0f, 0f),
        new Vector3f(0.625f));

    /**
     * Per-item-model-class cache of the private {@code ModelRenderProperties} field. The field is
     * declared on several unrelated concrete item models ({@code CuboidItemModelWrapper},
     * {@code MissingItemModel}, {@code SpecialModelWrapper}), so it is discovered by walking the
     * class hierarchy and matching by field type rather than by a fixed owner / name. An empty
     * optional records that a given model class has no such field (composite / select / empty
     * models), so those fall back to {@link #DEFAULT_BLOCK_GUI} without re-scanning.
     */
    private static final Map<Class<?>, Optional<Field>> PROPERTIES_FIELD = new ConcurrentHashMap<>();

    private static boolean loggedFailure;

    /**
     * Resolves the {@link ItemDisplayContext#GUI} {@link ItemTransform} authored for {@code state}'s
     * block, or {@link #DEFAULT_BLOCK_GUI} when none is readable. Any reflective failure is swallowed
     * (logged once) and degrades to the default so a model-layout change can never break the sweep.
     *
     * @param client the active client, source of the model manager
     * @param state the block state whose gui transform to resolve
     * @return the block's authored gui transform, or {@link #DEFAULT_BLOCK_GUI}
     */
    static ItemTransform resolve(Minecraft client, BlockState state) {
        try {
            Identifier modelId = new ItemStack(state.getBlock()).get(DataComponents.ITEM_MODEL);
            if (modelId == null) return DEFAULT_BLOCK_GUI;
            ItemModel model = client.getModelManager().getItemModel(modelId);
            if (model == null) return DEFAULT_BLOCK_GUI;

            Optional<Field> field = propertiesField(model.getClass());
            if (field.isEmpty()) return DEFAULT_BLOCK_GUI;
            ModelRenderProperties properties = (ModelRenderProperties) field.get().get(model);
            if (properties == null) return DEFAULT_BLOCK_GUI;

            ItemTransforms transforms = properties.transforms();
            if (transforms == null) return DEFAULT_BLOCK_GUI;
            ItemTransform gui = transforms.getTransform(ItemDisplayContext.GUI);
            return gui == ItemTransform.NO_TRANSFORM ? DEFAULT_BLOCK_GUI : gui;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce(state, e);
            return DEFAULT_BLOCK_GUI;
        }
    }

    /**
     * Applies {@code gui}'s translation, {@code rotationXYZ}, and scale to {@code pose} <b>without</b>
     * the trailing {@code T(-0.5)} block-centring that {@link ItemTransform#apply} appends. Callers
     * that recentre on a measured geometry bbox (the icon-fit pose) supply their own centring and must
     * not receive the fixed {@code -0.5} shift. The rotation math mirrors {@link ItemTransform#apply}
     * exactly (same {@code rotationXYZ} factory, same degrees-to-radians factor) so the two paths agree.
     *
     * @param gui the gui transform to apply
     * @param pose the pose to transform in place
     */
    static void applyFitNeutral(ItemTransform gui, PoseStack.Pose pose) {
        Vector3fc translation = gui.translation();
        Vector3fc rotation = gui.rotation();
        Vector3fc scale = gui.scale();
        pose.translate(translation.x(), translation.y(), translation.z());
        pose.rotate(new Quaternionf().rotationXYZ(
            rotation.x() * DEG_TO_RAD, rotation.y() * DEG_TO_RAD, rotation.z() * DEG_TO_RAD));
        pose.scale(scale.x(), scale.y(), scale.z());
    }

    private static Optional<Field> propertiesField(Class<?> modelClass) {
        return PROPERTIES_FIELD.computeIfAbsent(modelClass, BlockGuiTransform::findPropertiesField);
    }

    private static Optional<Field> findPropertiesField(Class<?> modelClass) {
        for (Class<?> c = modelClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == ModelRenderProperties.class) {
                    f.setAccessible(true);
                    return Optional.of(f);
                }
            }
        }
        return Optional.empty();
    }

    private static void logFailureOnce(BlockState state, Throwable e) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOG.warn("BlockGuiTransform: display.gui lookup failed for {}; falling back to the default iso pose", state, e);
    }
}
