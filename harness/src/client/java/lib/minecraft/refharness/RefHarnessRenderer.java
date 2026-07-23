package lib.minecraft.refharness;

import java.util.ArrayList;
import java.util.List;

import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.api.SweepRunner;
import lib.minecraft.refharness.api.TargetFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level orchestrator. Once the world is ready and the warmup ticks elapse, advances the
 * selected mode's sweeps one subject per client tick.
 *
 * <p>Every sweep renders through an offscreen picture-in-picture target using the vanilla GUI item
 * and entity pipelines, so none of them needs an in-world camera, a parked player or world
 * placement. The world still has to exist because entity construction requires a level reference,
 * but its rendering output is never captured.
 */
public final class RefHarnessRenderer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final TargetFilter TARGETS = TargetFilter.parse(HarnessConfig.TARGETS);

    /**
     * The run's sweeps, in the order they run. One step advances the first that is not finished, so
     * the list order is the sweep order.
     */
    private static List<SweepRunner<?>> runners = List.of();

    private RefHarnessRenderer() {}

    static void start(Minecraft client) {
        LOG.info("RefHarnessRenderer.start: building sweeps. level={}, player={}",
            client.level == null ? "null" : "ClientLevel",
            client.player == null ? "null" : client.player.getName().getString());
        if (client.level == null || client.player == null) {
            LOG.error("RefHarnessRenderer: cannot start, world not loaded");
            return;
        }

        // Park the player out of the way + hide HUD so the main framebuffer (which we don't capture
        // but MC still renders each frame) doesn't churn through chunk geometry under the player.
        // Mainly a perf hint; PIP captures are independent.
        client.player.getAbilities().mayfly = true;
        client.player.getAbilities().flying = true;
        client.player.getAbilities().invulnerable = true;
        client.player.setNoGravity(true);
        client.player.setDeltaMovement(0, 0, 0);
        client.options.hideGui = true;

        HarnessMode mode = HarnessMode.resolve();
        LOG.info("RefHarnessRenderer: mode={}", mode);

        // Every sweep other than the glint sweep renders any foil subject it meets at whatever phase
        // the wall clock happened to be at, which made the always-foil items the only
        // non-reproducible references in the tree. Pin the glint to the same instant the glint sweep
        // captures as its frame 0. The glint sweep drives the clock itself, one value per frame.
        if (mode != HarnessMode.GLINT) GlintClock.overrideT = 0;

        List<SweepRunner<?>> built = new ArrayList<>();
        for (Sweep<?> sweep : mode.sweeps()) built.add(SweepRunner.of(sweep));
        runners = List.copyOf(built);
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
     *
     * @param client the running client
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
        return !runners.isEmpty() && runners.stream().allMatch(SweepRunner::isDone);
    }

    static void tick(Minecraft client) {
        SweepContext ctx = new SweepContext(client, HarnessConfig.OUTPUT_DIR, TARGETS);
        for (SweepRunner<?> runner : runners) {
            // Returning after the first incomplete runner is what preserves the strictly sequential,
            // one-subject-per-tick pacing the asynchronous read-back requires.
            if (!runner.isDone()) { runner.step(ctx); return; }
        }
    }
}
