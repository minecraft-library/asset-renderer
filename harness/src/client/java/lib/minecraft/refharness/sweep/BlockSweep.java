package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.BlockEntityFrameRenderer;
import lib.minecraft.refharness.frame.BlockFrameRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Block sweep. Every block's ground truth is its 3D in-world render at the standard iso pose - this
 * is a block-parity sweep, never an item-icon sweep. Per block:
 * <ol>
 *   <li><b>Plain blocks</b> render through {@link BlockFrameRenderer} - the vanilla block-model
 *       pipeline at the standard iso {@code display.gui} pose. This bypasses the item-model
 *       dispatch, so blocks whose item model uses {@code item/generated} as a parent (rails, vines,
 *       ladders, lily_pad, seagrass, sculk_vein, doors, hanging signs) render as actual 3D geometry
 *       rather than the flat 2D billboard the inventory icon would show.</li>
 *   <li><b>{@link EntityBlock} blocks</b> with a registered block-entity renderer (chest,
 *       shulker_box, banner, sign, decorated_pot, skull, bell, beacon, ...) render through
 *       {@link BlockEntityFrameRenderer} so their per-entity art is captured.</li>
 *   <li><b>{@link EntityBlock} blocks without a renderer</b> (barrel, hopper, brewing_stand,
 *       furnace, chiseled_bookshelf, calibrated_sculk_sensor, ...) fall back to the same plain-block
 *       path - NOT the item model. Their visible geometry is the static block model, and routing
 *       them through the item icon would draw a flat 2D sprite or a divergent inventory model that
 *       does not reflect the 3D block.</li>
 * </ol>
 *
 * <p>No block is ever placed in the world: there is no camera dependency, no per-tick re-snap, no
 * falling-block corner case and no support-block rig.
 */
public final class BlockSweep implements Sweep<Block> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final BlockFrameRenderer blockRenderer = new BlockFrameRenderer();
    private final BlockEntityFrameRenderer beRenderer = new BlockEntityFrameRenderer();

    @Override
    public String outputDir() {
        return "blocks";
    }

    @Override
    public List<Block> enumerate(SweepContext ctx) {
        List<Block> selected = new ArrayList<>();
        int noItem = 0;
        for (Holder.Reference<Block> holder : BuiltInRegistries.BLOCK.listElements().toList()) {
            Block block = holder.value();
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
            // Technical blocks (piston_head, moving_piston, fire, etc.) have no associated Item -
            // skip them, since the asset-renderer parity sweep keys on the item-form id.
            if (block.asItem() == Items.AIR) { noItem++; continue; }
            if (!ctx.targets().accepts(BuiltInRegistries.BLOCK.getKey(block).toString())) continue;
            selected.add(block);
        }
        LOG.info("BlockSweep built: {} targets (no-item={})", selected.size(), noItem);
        return selected;
    }

    @Override
    public RefKey key(Block block) {
        return RefKey.of(BuiltInRegistries.BLOCK.getKey(block));
    }

    @Override
    public Canvas canvas(SweepContext ctx, Block block) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, Block block, Canvas canvas, Path out) throws IOException {
        // Block-entity blocks: try the BE-renderer path first (the actual 3D in-world geometry for
        // signs / beds / banners / heads / shulker_boxes / bell). When it declines - no registered
        // renderer - fall back to the PLAIN-BLOCK 3D path, not the item-model path. This is a
        // block-parity sweep: the ground truth is the 3D in-world block at the iso pose, not the
        // inventory icon, which for these blocks is either a flat sprite or a divergent model.
        if (block instanceof EntityBlock
            && beRenderer.render(ctx.client(), block.defaultBlockState(), canvas, out)) return true;
        return blockRenderer.render(ctx.client(), block.defaultBlockState(), canvas, out);
    }
}
