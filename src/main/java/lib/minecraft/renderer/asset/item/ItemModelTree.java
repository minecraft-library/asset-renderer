package lib.minecraft.renderer.asset.item;

import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

/**
 * One parsed {@code assets/<ns>/items/<id>.json} dispatch tree - the item's namespaced id plus the
 * root {@link ItemModelNode}. Built once at pipeline time from the item definition JSON and walked per
 * render by {@link ItemModelContext#resolve(ItemModelTree)}; the id is carried so the tree is
 * self-identifying in logs and the debugger even though resolution keys on the map entry.
 *
 * @param id the item's namespaced identifier (e.g. {@code minecraft:compass})
 * @param root the root dispatch node (the {@code model} object of the item definition)
 */
public record ItemModelTree(@NotNull ResourceId id, @NotNull ItemModelNode root) {}
