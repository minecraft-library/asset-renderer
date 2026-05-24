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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven block sweep. Per block:
 * <ol>
 *   <li><b>Plain blocks</b> render through {@link BlockFrameRenderer} - the vanilla block-model
 *       rendering pipeline ({@code SubmitNodeStorage.submitBlockModel}) at the standard iso
 *       {@code display.gui} pose ({@code [30, 225, 0]} + scale {@code 0.625}) under
 *       {@link com.mojang.blaze3d.platform.Lighting.Entry#ITEMS_3D ITEMS_3D} lighting. This
 *       bypasses the item-model dispatch, so blocks whose item model uses
 *       {@code item/generated} as a parent (rails, vines, ladders, lily_pad, seagrass,
 *       sculk_vein, doors, hanging signs) render as actual 3D geometry rather than the flat
 *       2D billboard the inventory icon would show.</li>
 *   <li><b>{@link EntityBlock} blocks</b> (chest, shulker_box, banner, sign, decorated_pot,
 *       skull, bell, beacon, ...) render through {@link ItemFrameRenderer} - the vanilla GUI
 *       inventory pipeline - because their visible geometry comes from a
 *       {@code BlockEntityWithoutLevelRenderer}, not the static block model. The block-model
 *       path would emit only a placeholder cube (or empty geometry), losing the per-entity
 *       art.</li>
 * </ol>
 *
 * <p>No block is ever placed in the world: there's no camera dependency, no per-tick
 * re-snap, no falling-block corner case, no support-block rig.
 */
public final class BlockSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final List<Block> targets;
    private final BlockFrameRenderer blockRenderer;
    private final ItemFrameRenderer entityBlockRenderer;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private BlockSweeper(List<Block> targets) {
        this.targets = targets;
        this.blockRenderer = new BlockFrameRenderer();
        this.entityBlockRenderer = new ItemFrameRenderer();
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
            // Technical blocks (piston_head, moving_piston, fire, etc.) have no associated
            // Item - skip them since the asset-renderer parity sweep keys on the item-form id.
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

        try {
            if (block instanceof EntityBlock) {
                ItemStack stack = new ItemStack(block);
                if (stack.isEmpty()) {
                    LOG.warn("BlockSweeper: empty stack for entity-block {}", id);
                    skipped++;
                } else {
                    entityBlockRenderer.renderAndWrite(client, stack, HarnessConfig.IMAGE_SIZE, out);
                    rendered++;
                }
            } else {
                blockRenderer.renderAndWrite(client, block.defaultBlockState(), HarnessConfig.IMAGE_SIZE, out);
                rendered++;
            }
        } catch (IOException ex) {
            LOG.error("BlockSweeper: PNG write failed for {}", id, ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("BlockSweeper: GPU render failed for {}", id, ex);
            failed++;
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
