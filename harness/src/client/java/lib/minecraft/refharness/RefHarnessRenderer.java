package lib.minecraft.refharness;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level orchestrator. After the world is ready and the warmup ticks elapse,
 * advances the block, item, and entity sweepers one step per client tick. All three sweepers
 * render through PIP (offscreen GPU texture + readback) using the vanilla GUI item / entity
 * pipelines, so none of them needs an in-world camera, player parking, or world placement. The
 * world still has to exist because {@code EntityType.create} requires a {@code Level} reference
 * for entity initialization, but its rendering output is never captured.
 */
public final class RefHarnessRenderer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static BlockSweeper blockSweeper;
    private static ItemSweeper itemSweeper;
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
        itemSweeper = ItemSweeper.build();
        entitySweeper = EntitySweeper.build();
    }

    /**
     * Pins the overworld to noon and freezes the day/night cycle so the in-world LIGHTMAP the
     * block-entity render path samples is a stable neutral full-bright value.
     *
     * <p>{@link BlockEntityFrameRenderer} wires a transient block-entity to {@code client.level}
     * and submits it through the vanilla BE renderer, which samples the world lightmap. That
     * lightmap is time-of-day dependent, so with the daylight cycle running the time advances
     * across the sweep and each BE block bakes a different sky tint into its reference PNG - and a
     * later subset re-render lands at a different time (e.g. a night-blue lightmap) than the
     * block's original full-sweep position, silently corrupting the reference. Plain blocks
     * ({@link BlockFrameRenderer}, rendered in isolation) never sample the lightmap, so they were
     * already stable; this only matters for the in-world BE path.
     *
     * <p>Called once the moment the world loads - <em>before</em> the warmup ticks - because the
     * time change is applied on the server thread and needs a few ticks to propagate to the
     * client lightmap; the warmup window guarantees it has landed before the first block renders.
     * MC 26.1 renamed the cycle rule to {@code ADVANCE_TIME} and moved the day-time setter into
     * the reworked clock/timeline system, so noon is pinned through the {@code /time} command.
     */
    static void pinNoonLighting(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            LOG.warn("RefHarnessRenderer: no integrated server, cannot pin noon lighting");
            return;
        }
        server.execute(() -> {
            ServerLevel overworld = server.overworld();
            overworld.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        });
    }

    static boolean isDone() {
        return blockSweeper != null && blockSweeper.isDone()
            && itemSweeper != null && itemSweeper.isDone()
            && entitySweeper != null && entitySweeper.isDone();
    }

    static void tick(Minecraft client) {
        if (blockSweeper != null && !blockSweeper.isDone()) {
            blockSweeper.step(client);
            return;
        }
        if (itemSweeper != null && !itemSweeper.isDone()) {
            itemSweeper.step(client);
            return;
        }
        if (entitySweeper != null && !entitySweeper.isDone()) {
            entitySweeper.step(client);
        }
    }
}
