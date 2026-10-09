package lib.minecraft.renderer.asset.item;

import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * One parsed {@code assets/<ns>/items/<id>.json} dispatch tree - the item's namespaced id plus the
 * root {@link ItemModelNode}. Built once at pipeline time from the item definition JSON and walked per
 * render by {@link ItemModelContext#resolve(ItemModelTree)}; the id is carried so the tree is
 * self-identifying in logs and the debugger even though resolution keys on the map entry.
 * <p>
 * A definition the loader refuses is held too, as a {@link #rejected(ResourceId) rejected} tree, because
 * vanilla reads only the top pack's file for an id: the refused file shadows every lower pack's copy,
 * and the item draws vanilla's missing item model. Its root is {@link ItemModelNode.Absent}, which every
 * walk resolves to {@link ItemModelNode.Resolution#MISSING}, so a pipeline walk passes it by - it
 * projects no block model and carries no tint. A definition whose root is a node type in a mod's
 * namespace parses to the same root, since vanilla's codec refuses that type and keeps no entry for
 * the file, so it is held and drawn as a refused one.
 *
 * @param id the item's namespaced identifier (e.g. {@code minecraft:compass})
 * @param root the root dispatch node (the {@code model} object of the item definition), {@link ItemModelNode.Absent} for a refused definition or one rooted at a mod's node type
 */
public record ItemModelTree(@NotNull ResourceId id, @NotNull ItemModelNode root) {

    /**
     * Builds the tree a refused definition leaves behind - the id, rooted at
     * {@link ItemModelNode.Absent}.
     *
     * @param id the refused definition's item id
     * @return the rejected tree
     */
    public static @NotNull ItemModelTree rejected(@NotNull ResourceId id) {
        return new ItemModelTree(id, ItemModelNode.Absent.INSTANCE);
    }

    /**
     * Whether this tree draws vanilla's missing item model whatever the context - a definition the
     * loader refused, or one whose root is a node type in a mod's namespace. Both root at
     * {@link ItemModelNode.Absent}.
     *
     * @return whether the definition was refused or roots at a mod's node type
     */
    public boolean isRejected() {
        return this.root instanceof ItemModelNode.Absent;
    }

}
