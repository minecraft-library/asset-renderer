package lib.minecraft.refharness;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level orchestrator. After the world is ready and the warmup ticks elapse,
 * advances the block and entity sweepers one step per client tick. Both sweepers now
 * render through PIP (offscreen GPU texture + readback) using the vanilla GUI item /
 * entity pipelines, so neither needs an in-world camera, player parking, or world
 * placement. The world still has to exist because {@code EntityType.create} requires a
 * {@code Level} reference for entity initialization, but its rendering output is never
 * captured.
 */
public final class RefHarnessRenderer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static BlockSweeper blockSweeper;
    private static EntitySweeper entitySweeper;

    private RefHarnessRenderer() {}

    static void start(Minecraft client) {
        LOG.info("RefHarnessRenderer.start: building sweepers. level={}, player={}",
            client.level == null ? "null" : "ClientLevel",
            client.player == null ? "null" : client.player.getName().getString());
        if (client.level == null || client.player == null) {
            LOG.error("RefHarnessRenderer: cannot start, world not loaded");
            return;
        }

        // Park the player out of the way + hide HUD so the main framebuffer (which we
        // don't capture but MC still renders each frame) doesn't churn through chunk
        // geometry under the player. Mainly a perf hint; PIP captures are independent.
        client.player.getAbilities().mayfly = true;
        client.player.getAbilities().flying = true;
        client.player.getAbilities().invulnerable = true;
        client.player.setNoGravity(true);
        client.player.setDeltaMovement(0, 0, 0);
        client.options.hideGui = true;

        blockSweeper = BlockSweeper.build();
        entitySweeper = EntitySweeper.build();
    }

    static boolean isDone() {
        return blockSweeper != null && blockSweeper.isDone()
            && entitySweeper != null && entitySweeper.isDone();
    }

    static void tick(Minecraft client) {
        if (blockSweeper != null && !blockSweeper.isDone()) {
            blockSweeper.step(client);
            return;
        }
        if (entitySweeper != null && !entitySweeper.isDone()) {
            entitySweeper.step(client);
        }
    }
}
