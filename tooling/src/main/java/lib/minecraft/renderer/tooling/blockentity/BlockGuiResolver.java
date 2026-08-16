package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The GUI half of the {@code inventory} node plus the {@code icon} node:
 * {@code inventory.y_rotation} + {@code inventory.flip} and {@code icon} {@code {rotation?, additive?}}.
 * The bytecode {@code inventory.transform} is the other contributor (InventoryTransformResolver).
 *
 * <p>{@code y_rotation} is the camera-facing convention from {@link BlockTransformPolicies}
 * (not a {@code display.gui} yaw read - the yaw does not determine it). {@code flip} is TRUE for
 * every transform-bearing family (they never consult the icon); for the three raw-pose families
 * (chest / bell / copper_golem_statue) it reads the item-model {@code display.gui} roll (180 means
 * flip) via {@link ClassNodeCache#readJson}, with the bell suppressed and a flat-sprite default of
 * TRUE. Per-renderer icon rolls are memoised.
 */
@RequiredArgsConstructor
final class BlockGuiResolver {

    /** The parent-chain depth bound the model walk follows. */
    private static final int MAX_MODEL_PARENT_DEPTH = 8;

    private final @NotNull ClassNodeCache cache;

    /** split id -> resolved display.gui roll (absent = not yet computed; present-null = unresolved). */
    private final @NotNull Map<String, Float> rollBySplitId = new HashMap<>();

    /**
     * The split's inventory yaw (the camera-facing convention).
     *
     * @param splitId the models key
     * @return {@code 180f} for the camera-facing families, else {@code 0f}
     */
    float yRotation(@NotNull String splitId) {
        return BlockTransformPolicies.inventoryYRotation(splitId);
    }

    /**
     * The split's entity-flip gate.
     *
     * @param rendererClass the subject renderer's JVM internal name
     * @param splitId the models key
     * @return {@code true} when the icon renders flipped
     */
    boolean flip(@NotNull String rendererClass, @NotNull String splitId) {
        if (BlockTransformPolicies.isTransformTarget(rendererClass)) return true;
        if (BlockTransformPolicies.isFlipSuppressed(splitId)) return false;
        Float roll = displayGuiRoll(splitId);
        if (roll == null) return BlockTransformPolicies.entityFlipDefault();   // no display.gui
        return Math.abs(roll - 180f) < 0.5f;
    }

    /**
     * The split's {@code icon} node, or {@code null} when it carries no icon presentation facts.
     *
     * @param splitId the models key
     * @return the {@code {rotation?, additive?}} node, or {@code null}
     */
    @Nullable JsonTree icon(@NotNull String splitId) {
        Integer rotation = BlockTransformPolicies.iconRotation(splitId);
        boolean additive = BlockTransformPolicies.isIconAdditive(splitId);
        if (rotation == null && !additive) return null;
        JsonTree icon = JsonTree.object();
        if (rotation != null) icon.putInt("rotation", rotation);
        if (additive) icon.put("additive", true);
        return icon;
    }

    /** The {@code display.gui} roll of the split's item icon, or {@code null} when unresolved. */
    private @Nullable Float displayGuiRoll(@NotNull String splitId) {
        if (this.rollBySplitId.containsKey(splitId)) return this.rollBySplitId.get(splitId);
        Float roll = resolveRoll(stripNamespace(splitId));
        this.rollBySplitId.put(splitId, roll);
        return roll;
    }

    /** Reads the item definition, reduces its model component, and walks the parent chain for the roll. */
    private @Nullable Float resolveRoll(@NotNull String localId) {
        JsonTree item = this.cache.readJson(VanillaSourceClasses.Paths.ITEM_MODEL_DIR + localId + VanillaSourceClasses.Paths.JSON_SUFFIX);
        if (item == null) return null;
        String modelRef = resolveModelRef(item.find(VanillaSourceClasses.DataKeys.MODEL).orElse(null));
        return modelRef == null ? null : readDisplayGuiRoll(modelRef);
    }

    /**
     * Reduces an item-model component to the model ref it draws at the reference pose:
     * {@code minecraft:model} -> its model, {@code minecraft:special} -> its base,
     * {@code minecraft:select} -> its fallback (recursively). Other component types (the pose
     * selectors, never reached at the reference pose) yield {@code null}.
     */
    private @Nullable String resolveModelRef(@Nullable JsonTree component) {
        if (component == null) return null;
        String type = component.findString(VanillaSourceClasses.DataKeys.TYPE).orElse(null);
        if (type == null) return null;

        return switch (type) {
            case VanillaSourceClasses.DataKeys.MODEL_COMPONENT -> component.findString(VanillaSourceClasses.DataKeys.MODEL).orElse(null);
            case VanillaSourceClasses.DataKeys.SPECIAL_COMPONENT -> component.findString(VanillaSourceClasses.DataKeys.BASE).orElse(null);
            case VanillaSourceClasses.DataKeys.SELECT_COMPONENT -> resolveModelRef(component.find(VanillaSourceClasses.DataKeys.FALLBACK).orElse(null));
            default -> null;
        };
    }

    /** Walks {@code modelRef} and its {@code parent} chain (depth-bounded) for the first {@code display.gui.rotation} roll. */
    private @Nullable Float readDisplayGuiRoll(@NotNull String modelRef) {
        String path = stripNamespace(modelRef);
        for (int depth = 0; depth < MAX_MODEL_PARENT_DEPTH; depth++) {
            JsonTree model = this.cache.readJson(VanillaSourceClasses.Paths.MODEL_DIR + path + VanillaSourceClasses.Paths.JSON_SUFFIX);
            if (model == null) return null;
            var roll = model.findPath(
                VanillaSourceClasses.DataKeys.DISPLAY,
                VanillaSourceClasses.DataKeys.GUI,
                VanillaSourceClasses.DataKeys.ROTATION)
                .flatMap(rotation -> rotation.findAt(2));   // [pitch, yaw, roll]
            if (roll.isPresent()) return roll.get().asFloat(0f);
            String parent = model.findString(VanillaSourceClasses.DataKeys.PARENT).orElse(null);
            if (parent == null) return null;
            path = stripNamespace(parent);
        }

        return null;
    }

    /** Strips any {@code namespace:} prefix. */
    private static @NotNull String stripNamespace(@NotNull String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

}
