package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.ItemFrameRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-block item sweep. Walks the item registry and renders every entry that is <em>not</em> a
 * {@link BlockItem} - those are already covered by {@link BlockSweep}, which routes through the same
 * renderer but writes to the block directory.
 *
 * <p>Each item is stacked and rendered through {@link ItemFrameRenderer}, the vanilla GUI inventory
 * icon pipeline. Output dimensions are fixed and square. Most 2D item icons are unit-scale 16x16
 * sprites; the larger canvas lets the inventory display transform breathe without clipping.
 */
public final class ItemSweep implements Sweep<Item> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final ItemFrameRenderer frameRenderer = new ItemFrameRenderer();

    @Override
    public String outputDir() {
        return "items";
    }

    @Override
    public List<Item> enumerate(SweepContext ctx) {
        List<Item> selected = new ArrayList<>();
        int blockItems = 0;
        for (Holder.Reference<Item> holder : BuiltInRegistries.ITEM.listElements().toList()) {
            Item item = holder.value();
            if (item == Items.AIR) continue;
            // BlockItems are inventory icons of placeable blocks. BlockSweep already renders one
            // PNG per block, so emitting them again here would duplicate work and pollute the
            // item directory.
            if (item instanceof BlockItem) { blockItems++; continue; }
            if (!ctx.targets().accepts(BuiltInRegistries.ITEM.getKey(item).toString())) continue;
            selected.add(item);
        }
        LOG.info("ItemSweep built: {} targets (block-items={})", selected.size(), blockItems);
        return selected;
    }

    @Override
    public RefKey key(Item item) {
        return RefKey.of(BuiltInRegistries.ITEM.getKey(item));
    }

    @Override
    public Canvas canvas(SweepContext ctx, Item item) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, Item item, Canvas canvas, Path out) throws IOException {
        return frameRenderer.render(ctx.client(), new ItemStack(item), canvas, out);
    }
}
