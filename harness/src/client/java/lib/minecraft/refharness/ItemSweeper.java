package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven non-block item sweep. Walks the item registry and renders every entry that is
 * <em>not</em> a {@link BlockItem} - those are already covered by {@link BlockSweeper} which
 * routes through the same {@link ItemFrameRenderer} but writes to {@code references/blocks/}.
 *
 * <p>For each remaining item:
 * <ol>
 *   <li>Builds a default {@link ItemStack}.</li>
 *   <li>Renders through {@link ItemFrameRenderer} - the vanilla GUI inventory icon pipeline
 *       ({@code ItemDisplayContext.GUI} + {@code ITEMS_FLAT} / {@code ITEMS_3D} lighting +
 *       FeatureRenderDispatcher to an offscreen RGBA8 texture).</li>
 *   <li>Writes the texture to {@code references/items/<namespace>__<path>.png}.</li>
 * </ol>
 *
 * <p>Output PNG dimensions are fixed at {@link HarnessConfig#IMAGE_SIZE} (square), matching the
 * block sweeper. Most 2D item icons are unit-scale 16x16 sprites; the larger canvas size lets the
 * inventory display transform breathe without clipping.
 */
public final class ItemSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final List<Item> targets;
    private final ItemFrameRenderer frameRenderer;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private ItemSweeper(List<Item> targets) {
        this.targets = targets;
        this.frameRenderer = new ItemFrameRenderer();
        this.index = 0;
    }

    public static ItemSweeper build() {
        Set<String> filter = HarnessConfig.TARGETS.isBlank()
            ? Set.of()
            : Set.of(HarnessConfig.TARGETS.split("\\s*,\\s*"));

        List<Item> selected = new ArrayList<>();
        int blockItems = 0;
        for (Holder.Reference<Item> holder : BuiltInRegistries.ITEM.listElements().toList()) {
            Item item = holder.value();
            if (item == Items.AIR) continue;
            // BlockItems are inventory icons of placeable blocks. BlockSweeper already
            // renders one PNG per block by stacking the block's asItem(), so emitting them
            // again here would just duplicate work and pollute the items/ folder.
            if (item instanceof BlockItem) { blockItems++; continue; }

            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!filter.isEmpty() && !filter.contains(id)) continue;

            selected.add(item);
        }
        LOG.info("ItemSweeper built: {} targets (block-items={}, filter='{}')",
            selected.size(), blockItems, HarnessConfig.TARGETS);
        return new ItemSweeper(selected);
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (index >= targets.size()) { finish(); return; }

        Item item = targets.get(index);
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String safeName = id.getNamespace() + "__" + id.getPath();
        Path out = HarnessConfig.OUTPUT_DIR.resolve("items").resolve(safeName + ".png");

        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty()) {
            LOG.warn("ItemSweeper: empty stack for {}", id);
            skipped++;
        } else {
            try {
                frameRenderer.renderAndWrite(client, stack, HarnessConfig.IMAGE_SIZE, out);
                rendered++;
            } catch (IOException ex) {
                LOG.error("ItemSweeper: PNG write failed for {}", id, ex);
                failed++;
            } catch (RuntimeException ex) {
                LOG.error("ItemSweeper: GPU render failed for {}", id, ex);
                failed++;
            }
        }
        index++;
        if (index >= targets.size()) finish();
    }

    private void finish() {
        LOG.info("ItemSweeper: done. rendered={}, skipped={}, failed={}, total={}",
            rendered, skipped, failed, targets.size());
        done = true;
        // Same reasoning as BlockSweeper.finish(): the last item's PNG-write callback is
        // still pending when we reach finish(), so closing the GPU textures here would crash
        // the callback. Leak ~3 MiB until JVM exit.
    }

    @Override
    public void close() {
        // Same reasoning as finish(): leak the frame renderer's textures rather than risk
        // racing in-flight readbacks.
    }
}
