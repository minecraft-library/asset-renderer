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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven block sweep. Each iteration:
 * <ol>
 *   <li>Resolves the block's default {@link ItemStack} via {@link Block#asItem}.</li>
 *   <li>Renders it through {@link ItemFrameRenderer} - the same pipeline vanilla uses
 *       to draw inventory icons (GUI display transform {@code [30, 225, 0]} +
 *       {@code Lighting.Entry#ITEMS_3D ITEMS_3D} lighting + per-feature dispatch to an
 *       offscreen RGBA8 texture).</li>
 *   <li>Writes the texture to PNG.</li>
 * </ol>
 *
 * <p>Unlike the previous version, no block is ever placed in the world: there's no
 * camera dependency, no per-tick re-snap, no falling-block corner case, no support-block
 * rig. Block-entity renderers (chest, sign, banner, ...) get the same shading the player
 * sees in their inventory because the {@code BlockEntityWithoutLevelRenderer} path runs
 * end-to-end exactly as it does in {@code GuiItemAtlas.drawToSlot}.
 */
public final class BlockSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final List<Block> targets;
    private final ItemFrameRenderer frameRenderer;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private BlockSweeper(List<Block> targets) {
        this.targets = targets;
        this.frameRenderer = new ItemFrameRenderer();
        this.index = 0;
    }

    public static BlockSweeper build() {
        Set<String> filter = HarnessConfig.TARGETS.isBlank()
            ? Set.of()
            : Set.of(HarnessConfig.TARGETS.split("\\s*,\\s*"));

        List<Block> selected = new ArrayList<>();
        int noItem = 0;
        for (Holder.Reference<Block> holder : BuiltInRegistries.BLOCK.listElements().toList()) {
            Block block = holder.value();
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
            // Some blocks (technical ones like piston_head, moving_piston, fire) don't have
            // an associated Item, so block.asItem() returns AIR. The PIP renderer needs an
            // ItemStack so we filter these out at build time. They had no inventory icon to
            // parity-match against anyway.
            if (block.asItem() == net.minecraft.world.item.Items.AIR) { noItem++; continue; }

            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!filter.isEmpty() && !filter.contains(id)) continue;

            selected.add(block);
        }
        LOG.info("BlockSweeper built: {} targets (no-item={}, filter='{}')",
            selected.size(), noItem, HarnessConfig.TARGETS);
        return new BlockSweeper(selected);
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (index >= targets.size()) { finish(); return; }

        Block block = targets.get(index);
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String safeName = id.getNamespace() + "__" + id.getPath();
        Path out = HarnessConfig.OUTPUT_DIR.resolve("blocks").resolve(safeName + ".png");

        ItemStack stack = new ItemStack(block);
        if (stack.isEmpty()) {
            LOG.warn("BlockSweeper: empty stack for {}", id);
            skipped++;
        } else {
            try {
                frameRenderer.renderAndWrite(client, stack, HarnessConfig.IMAGE_SIZE, out);
                rendered++;
            } catch (IOException ex) {
                LOG.error("BlockSweeper: PNG write failed for {}", id, ex);
                failed++;
            } catch (RuntimeException ex) {
                LOG.error("BlockSweeper: GPU render failed for {}", id, ex);
                failed++;
            }
        }
        index++;
        if (index >= targets.size()) finish();
    }

    private void finish() {
        LOG.info("BlockSweeper: done. rendered={}, skipped={}, failed={}, total={}",
            rendered, skipped, failed, targets.size());
        done = true;
        // Deliberately do NOT close the frame renderer here: copyTextureToBuffer's PNG
        // write is async (runs on a later frame via RenderSystem.executePendingTasks), so
        // the last item's readback is still pending when finish() runs. Closing the GPU
        // textures now would crash the callback with NativeImage(0, 0). The textures are
        // small (~3 MiB total) and get reclaimed at JVM exit, which is fine for a one-shot
        // render harness.
    }

    @Override
    public void close() {
        // Same reasoning as finish(): leak the frame renderer's textures rather than risk
        // racing in-flight readbacks.
    }
}
