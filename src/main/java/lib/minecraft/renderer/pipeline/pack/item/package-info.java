/**
 * The item-definition dispatch-tree loaders - read {@code assets/<ns>/items/*.json} into the
 * {@link lib.minecraft.renderer.asset.pack.item asset.pack.item} DTOs at pipeline time. Pipeline-only;
 * the parsed trees are render-consumed from {@code asset.pack.item}.
 *
 * <p>{@link lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader ItemModelTreeLoader} scans
 * and merges each pack's trees (ascending priority, {@code filter.block} erasure) and derives the
 * block-item and tint projections the index builders consume;
 * {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelParser ItemModelParser} parses one
 * {@code model} object into a node tree (depth-capped against pathological pack nesting);
 * {@link lib.minecraft.renderer.pipeline.pack.item.LegacyOverrideMapper LegacyOverrideMapper}
 * tolerate-and-maps a pre-format-46 {@code models/item} {@code overrides} array onto the same node
 * vocabulary so a legacy pack resolves like a native items file.
 */
package lib.minecraft.renderer.pipeline.pack.item;
