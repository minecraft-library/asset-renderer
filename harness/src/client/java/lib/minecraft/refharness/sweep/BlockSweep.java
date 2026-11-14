package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.BlockEntityFrameRenderer;
import lib.minecraft.refharness.frame.BlockFrameRenderer;
import lib.minecraft.refharness.frame.ItemDefinitionWalk;
import lib.minecraft.refharness.frame.ItemFrameRenderer;
import lib.minecraft.refharness.frame.SlotLayers;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Block sweep. Every block's ground truth is a 3D render at the standard iso pose: the inventory
 * icon vanilla draws where it draws one from a block model or through special renderers, and the
 * block's own 3D model where that icon is a flat sprite. Per block:
 * <ol>
 *   <li><b>Blocks whose item the client draws through special renderers alone</b> (chest, bed,
 *       banner and wall banner, head and wall head, shulker_box, conduit, decorated_pot,
 *       copper_golem_statue) render through {@link ItemFrameRenderer}, the client's slot drawing:
 *       each layer posed by its special's {@code transformation} and its base's {@code display.gui},
 *       read by {@link SlotLayers}.</li>
 *   <li><b>Plain blocks</b> render through {@link BlockFrameRenderer} at the block's authored
 *       {@code display.gui} pose. A block whose item definition walks to a {@code model} leaf
 *       naming a block model - a plain root naming one, or a dispatch whose neutral branch does -
 *       draws vanilla's own bake of that leaf, at the identity model state; a block whose
 *       item model uses {@code item/generated} as a parent (rails, vines, ladders, lily_pad,
 *       seagrass, sculk_vein, doors, hanging signs) has no 3D icon to reproduce and draws its
 *       blockstate model instead, a flat 2D billboard being no use as block ground truth.</li>
 *   <li><b>Other {@link EntityBlock} blocks</b> with a registered block-entity renderer (sign,
 *       hanging_sign, bell, beacon, ...) render through
 *       {@link BlockEntityFrameRenderer} so their per-entity art is captured. Its static block
 *       half takes the same split as the plain path, so a shelf and a structure_block draw the
 *       icon vanilla bakes for them and their block entity still submits on top.</li>
 *   <li><b>{@link EntityBlock} blocks without a renderer</b> (barrel, hopper, brewing_stand,
 *       furnace, chiseled_bookshelf, calibrated_sculk_sensor, ...) fall back to the same plain-block
 *       path and take the same split there. Their visible geometry is a static block model either
 *       way; what the split decides is only whether the blockstate's orientation reaches it.</li>
 * </ol>
 *
 * <p>Each block draws once from a plain stack of its block, named for the block. A block-model icon
 * whose definition selects on the stack's {@code minecraft:block_state} draws once more for each
 * value a case landing on a block model names - {@code beehive} and {@code bee_nest} at
 * {@code honey_level=5}, {@code test_block} at {@code mode=log}, {@code fail} and {@code accept} -
 * from the same default state, with only the stack changed to carry the component vanilla gives a
 * picked full hive or {@code TestBlock.setModeOnStack} writes. Those are named
 * {@code <ns>__<block>~<property>=<value>}, which matches no block id, and render after every plain
 * reference. A route that ignored the component would land on the select's fallback, so a case
 * reference showing the plain icon is a broken route rather than a match.
 *
 * <p>No block is ever placed in the world: there is no camera dependency, no per-tick re-snap, no
 * falling-block corner case and no support-block rig.
 */
@Parity(claim = "harness-block-sweep", mode = Mode.DEMOTE, subject = Subject.BLOCK)
public final class BlockSweep implements Sweep<BlockSweep.Drawing> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final BlockFrameRenderer blockRenderer = new BlockFrameRenderer();
    private final BlockEntityFrameRenderer beRenderer = new BlockEntityFrameRenderer();
    private final ItemFrameRenderer itemRenderer = new ItemFrameRenderer();

    /**
     * One drawing, which is one reference: a block drawn from its default state, and the
     * block-state component the stack its icon is read from carries.
     *
     * @param block the block drawn
     * @param stackState the stack's {@code minecraft:block_state} component, or
     *                   {@link BlockItemStateProperties#EMPTY} for the block's plain icon, whose
     *                   stack carries none
     */
    public record Drawing(Block block, BlockItemStateProperties stackState) {

        /** The stack the icon is read from: the block's own item, carrying {@link #stackState} unless it is empty. */
        private ItemStack stack() {
            ItemStack stack = new ItemStack(block);
            if (!stackState.isEmpty()) stack.set(DataComponents.BLOCK_STATE, stackState);
            return stack;
        }
    }

    @Override
    public String outputDir() {
        return "blocks";
    }

    @Override
    public List<Drawing> enumerate(SweepContext ctx) {
        List<Drawing> selected = new ArrayList<>();
        List<Drawing> cases = new ArrayList<>();
        int noItem = 0;
        for (Holder.Reference<Block> holder : BuiltInRegistries.BLOCK.listElements().toList()) {
            Block block = holder.value();
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue;
            // Technical blocks (piston_head, moving_piston, fire, etc.) have no associated Item -
            // skip them, since the asset-renderer parity sweep keys on the item-form id.
            if (block.asItem() == Items.AIR) { noItem++; continue; }
            if (!ctx.targets().accepts(BuiltInRegistries.BLOCK.getKey(block).toString())) continue;
            selected.add(new Drawing(block, BlockItemStateProperties.EMPTY));
            for (BlockItemStateProperties stackState : ItemDefinitionWalk.stateCases(ctx.client(), block))
                cases.add(new Drawing(block, stackState));
        }
        selected.addAll(cases);
        LOG.info("BlockSweep built: {} targets (no-item={}, block-state cases={})", selected.size(), noItem, cases.size());
        return selected;
    }

    @Override
    public RefKey key(Drawing drawing) {
        RefKey key = RefKey.of(BuiltInRegistries.BLOCK.getKey(drawing.block()));
        for (Map.Entry<String, String> property : drawing.stackState().properties().entrySet())
            key = key.token(property.getKey(), property.getValue());
        return key;
    }

    @Override
    public Canvas canvas(SweepContext ctx, Drawing drawing) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, Drawing drawing, Canvas canvas, Path out) throws IOException {
        // A block whose item the client draws through special renderers alone - a chest, a bed, a
        // banner, a head, a shulker box, a statue - is drawn as the client's slot draws it: each layer
        // posed by its special's transformation and its base's display.gui, which nothing composed
        // here reproduces. A wall form's item is its standing form's, so it draws that icon.
        BlockState state = drawing.block().defaultBlockState();
        ItemStack stack = drawing.stack();
        if (SlotLayers.specialsAlone(ctx.client(), stack))
            return itemRenderer.render(ctx.client(), stack, canvas, out);

        // Block-entity blocks: try the BE-renderer path first (the actual 3D in-world geometry for
        // signs / bells / beacons). When it declines - no registered renderer - fall back to the
        // PLAIN-BLOCK 3D path, not the item-model path. This is a block-parity sweep: the ground
        // truth is the 3D in-world block at the iso pose, not the inventory icon, which for these
        // blocks is either a flat sprite or a divergent model.
        if (drawing.block() instanceof EntityBlock
            && beRenderer.render(ctx.client(), state, stack, canvas, out)) return true;
        return blockRenderer.render(ctx.client(), state, stack, canvas, out);
    }
}
