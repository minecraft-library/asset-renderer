package lib.minecraft.renderer.asset.pack.item;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.option.ItemModelContext;
import org.jetbrains.annotations.NotNull;

/**
 * One parsed {@code assets/<ns>/items/<id>.json} dispatch tree - the item's namespaced id plus the
 * root {@link ItemModelNode}. Built once at pipeline time ({@link ItemModelParser}) and evaluated per
 * render against an {@link ItemModelContext} (via {@link #resolve(ItemModelContext)}); the id is
 * carried so the tree is self-identifying in logs and the debugger even though resolution keys on the
 * map entry.
 *
 * @param id the item's namespaced identifier (e.g. {@code minecraft:compass})
 * @param root the root dispatch node (the {@code model} object of the item definition)
 */
public record ItemModelTree(@NotNull ResourceId id, @NotNull ItemModelNode root) {

    /**
     * Resolves this tree against a context - delegates to the {@linkplain #root() root node}.
     *
     * @param context the evaluation context
     * @return the resolved branch
     */
    public @NotNull ItemModelNode.Resolution resolve(@NotNull ItemModelContext context) {
        return this.root.resolve(context);
    }

}
