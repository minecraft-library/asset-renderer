package lib.minecraft.refharness.frame;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolver for the geometry a block's inventory icon is drawn from, shared by
 * {@link BlockFrameRenderer} and by the static block half of {@link BlockEntityFrameRenderer} - a
 * block entity does not stop a block from having an icon vanilla bakes from a block model.
 *
 * <p>Vanilla draws a block-item icon from the item model named by the stack's
 * {@code minecraft:item_model} component, baked at {@code BlockModelRotation.IDENTITY} - it never
 * consults a {@code BlockStateModel}, so no blockstate variant's rotation and no multipart assembly
 * reaches an icon. That is a different object from the one {@code BlockStateModelSet.get(state)}
 * hands back: for {@code oak_stairs} the blockstate model carries the default state's
 * {@code y: 270}, and for {@code oak_fence} it assembles a post and four arms where the item model
 * is {@code block/oak_fence_inventory}'s post and two.
 *
 * <p>An item qualifies when its definition, walked as asset-renderer walks it at its neutral gui
 * context, lands on a {@code minecraft:model} leaf naming a block model without passing through a
 * {@code composite}. A plain root naming one does, and so does a dispatch whose neutral branch names
 * one - {@code beehive}, {@code bee_nest} and {@code test_block} select on the stack's block state
 * and draw their fallback block model for a stack carrying none. A leaf that names an
 * {@code item/} model is a flat sprite in the inventory (doors, wall torches, comparators, levers),
 * a {@code special} leaf is a block entity, and a composite paints every child rather than one block
 * model; for all three the sweep keeps its own 3D render off the blockstate model, there being no
 * vanilla 3D block icon to reproduce. That predicate is {@link ItemDefinitionWalk#isBlockModelIcon},
 * which reads the shipped {@code items/<name>.json} directly, so it is the same test asset-renderer
 * applies to the same file.
 *
 * <p>The quads themselves are the ones vanilla bakes for the leaf its own walk lands on.
 * {@link ItemModelResolver} resolves the stack it is given at {@link ItemDisplayContext#GUI} into a
 * {@link TrackingItemStackRenderState}, which records every model the walk passes through, each
 * node before the branch it delegates to; the last is the leaf, and its private
 * {@link QuadCollection} field is read off it - so the reference carries vanilla's own bake rather
 * than a re-derivation of it, a select's fallback included.
 *
 * <p>The stack is the caller's, so a stack carrying a component a dispatch selects on draws the
 * branch vanilla picks for it. {@link ItemDefinitionWalk#stateCases} lists the
 * {@code minecraft:block_state} components that steer a block-model icon onto another block model -
 * a full {@code beehive} or {@code bee_nest}, a {@code test_block} in {@code log}, {@code fail} or
 * {@code accept} mode - and a stack carrying one draws that case only when vanilla's own select reads
 * the component; a walk that ignored it would land on the fallback and draw the plain icon.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE)
@UtilityClass
final class BlockIconGeometry {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Per-item-model-class cache of the private {@link QuadCollection} field, discovered by walking
     * the class hierarchy and matching by field type rather than by a fixed owner / name. An empty
     * optional records that a given model class holds no quads (special / composite / select /
     * empty models), so those are not re-scanned.
     */
    private static final Map<Class<?>, Optional<Field>> QUADS_FIELD = new ConcurrentHashMap<>();

    private static boolean loggedFailure;

    /**
     * Replaces {@code parts} with the icon geometry vanilla draws for {@code stack} when it draws one
     * from a block model, leaving the collected blockstate parts untouched when it does not. The two
     * are alternative geometries for the same block, so this swaps rather than appends; the particle
     * material rides over from the first collected part, nothing in these paths drawing particles.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param stack the stack the icon is read from - the block's own item, carrying whatever
     *              components the subject gives it
     * @param parts the collected blockstate parts, replaced in place when an icon is found
     * @return whether the parts were replaced by the icon's
     */
    static boolean swapIn(Minecraft client, ItemStack stack, List<BlockStateModelPart> parts) {
        if (parts.isEmpty()) return false;
        Optional<QuadCollection> icon = resolve(client, stack);
        if (icon.isEmpty()) return false;
        Material.Baked particle = parts.getFirst().particleMaterial();
        parts.clear();
        parts.add(new IconPart(icon.get(), particle));
        return true;
    }

    /**
     * The baked icon quads of a block whose inventory icon vanilla draws from a block model,
     * presented as the single {@link BlockStateModelPart} {@code submitBlockModel} consumes. Ambient
     * occlusion is off: it is a chunk-lighting term, and these paths submit at full-bright.
     *
     * @param quads the item model's baked quads, at the identity model state
     * @param particle the particle material carried over from the block's own blockstate part
     */
    private record IconPart(QuadCollection quads, Material.Baked particle) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction face) { return quads.getQuads(face); }

        @Override
        public boolean useAmbientOcclusion() { return false; }

        @Override
        public Material.Baked particleMaterial() { return particle; }

        @Override
        public int materialFlags() { return quads.materialFlags(); }
    }

    /**
     * Resolves the quads vanilla's inventory icon for {@code stack} draws, or empty when vanilla has
     * no 3D icon for its block and the sweep should render the blockstate model instead. Any
     * reflective or resource failure is swallowed (logged once) and degrades to empty, so a model
     * layout change can never break the sweep.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param stack the stack whose icon geometry to resolve
     * @return the icon's quads, or empty when vanilla's icon is not a block model
     */
    private static Optional<QuadCollection> resolve(Minecraft client, ItemStack stack) {
        try {
            Identifier modelId = stack.get(DataComponents.ITEM_MODEL);
            if (modelId == null) return Optional.empty();
            if (!ItemDefinitionWalk.isBlockModelIcon(client, modelId)) return Optional.empty();

            Optional<ItemModel> leaf = guiLeaf(client, stack);
            if (leaf.isEmpty()) return Optional.empty();

            Optional<Field> field = quadsField(leaf.get().getClass());
            if (field.isEmpty()) return Optional.empty();
            return Optional.ofNullable((QuadCollection) field.get().get(leaf.get()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce(stack, e);
            return Optional.empty();
        }
    }

    /**
     * Resolves the item model vanilla's own walk lands on for a stack in a GUI slot. Every baked node
     * appends itself to a {@link TrackingItemStackRenderState}'s identity before it delegates, and a
     * leaf appends itself before anything it adds of its own, so the last {@link ItemModel} recorded is
     * the leaf - a plain root's own model, or the branch a dispatch selects.
     *
     * @param client the active client, source of the item model resolver and the level
     * @param stack the stack to walk, its components included
     * @return the leaf, or empty when the walk passes through a {@link CompositeModel} or records no model
     */
    private static Optional<ItemModel> guiLeaf(Minecraft client, ItemStack stack) {
        TrackingItemStackRenderState rendered = new TrackingItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(rendered, stack, ItemDisplayContext.GUI, client.level, /*owner*/ null, /*seed*/ 0);
        ItemModel leaf = null;
        for (Object element : (List<?>) rendered.getModelIdentity()) {
            if (element instanceof CompositeModel) return Optional.empty();
            if (element instanceof ItemModel model) leaf = model;
        }
        return Optional.ofNullable(leaf);
    }

    private static Optional<Field> quadsField(Class<?> modelClass) {
        return QUADS_FIELD.computeIfAbsent(modelClass, BlockIconGeometry::findQuadsField);
    }

    private static Optional<Field> findQuadsField(Class<?> modelClass) {
        for (Class<?> c = modelClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == QuadCollection.class) {
                    f.setAccessible(true);
                    return Optional.of(f);
                }
            }
        }
        return Optional.empty();
    }

    private static void logFailureOnce(ItemStack stack, Throwable e) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOG.warn("BlockIconGeometry: icon geometry lookup failed for {} {}; falling back to the blockstate model", stack, stack.getComponentsPatch(), e);
    }
}
