package lib.minecraft.refharness.frame;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
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
 * <p>Only a plain {@code minecraft:model} root naming a block model qualifies. A root that names an
 * {@code item/} model is a flat sprite in the inventory (doors, wall torches, comparators, levers),
 * and a {@code special} / {@code composite} / dispatch root is a block entity or a per-state choice;
 * for both the sweep keeps its own 3D render off the blockstate model, there being no vanilla 3D
 * icon to reproduce. That predicate reads the shipped {@code items/<name>.json} directly, so it is
 * the same test asset-renderer applies to the same file.
 *
 * <p>The quads themselves come off the baked {@link ItemModel} vanilla already built, read through
 * the private {@link QuadCollection} field the standard cuboid item model holds - so the reference
 * carries vanilla's own bake rather than a re-derivation of it.
 */
final class BlockIconGeometry {

    private BlockIconGeometry() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Per-item-model-class cache of the private {@link QuadCollection} field, discovered by walking
     * the class hierarchy and matching by field type rather than by a fixed owner / name. An empty
     * optional records that a given model class holds no quads (special / composite / select /
     * empty models), so those are not re-scanned.
     */
    private static final Map<Class<?>, Optional<Field>> QUADS_FIELD = new ConcurrentHashMap<>();

    /**
     * Per-item-model-identifier cache of the block-model predicate, keyed by the
     * {@code minecraft:item_model} identifier so the shipped {@code items/<name>.json} is read once
     * per item across the whole sweep.
     */
    private static final Map<Identifier, Boolean> MODEL_ICON = new ConcurrentHashMap<>();

    private static boolean loggedFailure;

    /**
     * Replaces {@code parts} with the block's icon geometry when vanilla draws one from a block
     * model, leaving the collected blockstate parts untouched when it does not. The two are
     * alternative geometries for the same block, so this swaps rather than appends; the particle
     * material rides over from the first collected part, nothing in these paths drawing particles.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param state the block state whose geometry is being submitted
     * @param parts the collected blockstate parts, replaced in place when an icon is found
     * @return whether the parts were replaced by the icon's
     */
    static boolean swapIn(Minecraft client, BlockState state, List<BlockStateModelPart> parts) {
        if (parts.isEmpty()) return false;
        Optional<QuadCollection> icon = resolve(client, state);
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
     * Resolves the quads vanilla's inventory icon for {@code state}'s block draws, or empty when
     * vanilla has no 3D icon for it and the sweep should render the blockstate model instead. Any
     * reflective or resource failure is swallowed (logged once) and degrades to empty, so a model
     * layout change can never break the sweep.
     *
     * @param client the active client, source of the model manager and resource manager
     * @param state the block state whose icon geometry to resolve
     * @return the icon's quads, or empty when vanilla's icon is not a block model
     */
    private static Optional<QuadCollection> resolve(Minecraft client, BlockState state) {
        try {
            Identifier modelId = new ItemStack(state.getBlock()).get(DataComponents.ITEM_MODEL);
            if (modelId == null) return Optional.empty();
            if (!isBlockModelIcon(client, modelId)) return Optional.empty();

            ItemModel model = client.getModelManager().getItemModel(modelId);
            if (model == null) return Optional.empty();

            Optional<Field> field = quadsField(model.getClass());
            if (field.isEmpty()) return Optional.empty();
            return Optional.ofNullable((QuadCollection) field.get().get(model));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logFailureOnce(state, e);
            return Optional.empty();
        }
    }

    /**
     * Reports whether an item's shipped definition is a plain {@code minecraft:model} root naming a
     * block model - the population whose inventory icon vanilla bakes from a block model. Reads
     * {@code assets/<ns>/items/<name>.json}; an absent or unreadable file answers {@code false}.
     *
     * @param client the active client, source of the resource manager
     * @param modelId the item's {@code minecraft:item_model} identifier
     * @return whether the item's icon is a block model
     */
    private static boolean isBlockModelIcon(Minecraft client, Identifier modelId) {
        return MODEL_ICON.computeIfAbsent(modelId, id -> {
            Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "items/" + id.getPath() + ".json");
            Optional<Resource> resource = client.getResourceManager().getResource(path);
            if (resource.isEmpty()) return false;
            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonObject root = GsonHelper.parse(reader);
                if (!root.has("model")) return false;
                JsonObject model = GsonHelper.getAsJsonObject(root, "model");
                if (!"minecraft:model".equals(GsonHelper.getAsString(model, "type", ""))) return false;
                String ref = GsonHelper.getAsString(model, "model", "");
                return Identifier.parse(ref).getPath().startsWith("block/");
            } catch (Exception e) {
                LOG.warn("BlockIconGeometry: unreadable item definition {}", path, e);
                return false;
            }
        });
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

    private static void logFailureOnce(BlockState state, Throwable e) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOG.warn("BlockIconGeometry: icon geometry lookup failed for {}; falling back to the blockstate model", state, e);
    }
}
