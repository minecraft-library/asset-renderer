/**
 * The item-definition dispatch-tree loaders - read {@code assets/<ns>/items/*.json} into the
 * {@link lib.minecraft.renderer.asset.pack.item asset.pack.item} DTOs at pipeline time. Pipeline-only;
 * the parsed trees are render-consumed from {@code asset.pack.item}.
 *
 * <p>{@link lib.minecraft.renderer.pipeline.pack.item.ItemModelTreeLoader ItemModelTreeLoader} scans
 * and merges each pack's trees (ascending priority, {@code filter.block} erasure) and derives the
 * block-item and tint projections the index builders consume; each {@code model} object deserialises
 * into a node tree through
 * {@link lib.minecraft.renderer.pipeline.pack.item.ItemModelNodeDeserializer ItemModelNodeDeserializer}
 * (a Gson type-discriminated read recursing through the context, registered globally);
 * {@link lib.minecraft.renderer.pipeline.pack.item.LegacyOverrideMapper LegacyOverrideMapper}
 * tolerate-and-maps a pre-format-46 {@code models/item} {@code overrides} array onto the same node
 * vocabulary so a legacy pack resolves like a native items file.
 */
package lib.minecraft.renderer.pipeline.pack.item;
