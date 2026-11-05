package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.api.SweepRunner;
import lib.minecraft.refharness.api.TargetFilter;
import lib.minecraft.refharness.frame.BlockEntityFrameRenderer;
import lib.minecraft.refharness.frame.BlockFrameRenderer;
import lib.minecraft.refharness.pip.TextureReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Top-level orchestrator. Once the world is ready and the warmup ticks elapse, advances the
 * selected mode's sweeps one subject per client tick.
 *
 * <p>Every sweep renders through an offscreen picture-in-picture target using the vanilla GUI item
 * and entity pipelines, so none of them needs an in-world camera, a parked player or world
 * placement. The world still has to exist because entity construction requires a level reference,
 * but its rendering output is never captured.
 */
@UtilityClass
public final class RefHarnessRenderer {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private static final TargetFilter TARGETS = TargetFilter.parse(HarnessConfig.TARGETS);

    /** The built-in packs that redraw vanilla's own resources, which no run draws through. */
    private static final Set<String> REDRAWING_PACKS = Set.of("high_contrast", "programmer_art");

    /**
     * The run's sweeps, in the order they run. One step advances the first that is not finished, so
     * the list order is the sweep order.
     */
    private static List<SweepRunner<?>> runners = List.of();

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

        List<HarnessMode> modes = HarnessMode.resolve();
        requireVanillaClient(client);
        List<Sweep<?>> sweeps = HarnessMode.sweeps(modes);
        LOG.info("RefHarnessRenderer: modes={}, sweeps={}", modes,
            sweeps.stream().map(sweep -> sweep.outputDir() + "@" + sweep.gait()).toList());

        // Every sweep other than the glint sweep renders any foil subject it meets at whatever phase
        // the wall clock happened to be at, which made the always-foil items the only
        // non-reproducible references in the tree. Pin the glint to the same instant the glint sweep
        // captures as its frame 0. The glint sweep drives the clock itself, one value per frame.
        if (!modes.equals(List.of(HarnessMode.GLINT))) GlintClock.overrideT = 0;

        List<SweepRunner<?>> built = new ArrayList<>();
        for (Sweep<?> sweep : sweeps) built.add(SweepRunner.of(sweep));
        runners = List.copyOf(built);
    }

    /**
     * Refuses a client that does not draw as vanilla's own, before the run's first sweep exists.
     *
     * <p>Every run is read as the client drawing vanilla's own resources at vanilla's defaults, a pack
     * run with the packs it asked for over them. So a run is refused when its client selected a built-in
     * pack that redraws vanilla's resources, or a pack from its resource-pack folder the run did not ask
     * for - every one, for a run that names no pack - and when it runs at high contrast, with the forced
     * Unicode font or the Japanese glyph variants on, or in a language other than {@code en_us}. Each is
     * read rather than set: a pack and a font option take effect through a resource reload, and the run
     * directory every run shares would keep whatever a run set, so only a hand launch leaves one changed.
     *
     * <p>The check is the same for every mode, a mode that draws no text included: the pack selection and
     * the options are the shared run directory's state rather than any mode's, and a list of the modes
     * each one reaches would be one more statement a new sweep could be left out of. It is made before
     * any sweep exists, so a refused run writes no file.
     *
     * @param client the running client
     * @throws IllegalStateException if a redrawing built-in pack or a folder pack the run did not ask for
     *     is selected, or an option is not vanilla's default
     */
    private static void requireVanillaClient(Minecraft client) {
        requireVanillaPacks(client.getResourcePackRepository().getSelectedIds(), HarnessConfig.packIds());
        Options options = client.options;
        requireVanillaOptions(options.highContrast().get(), options.forceUnicodeFont().get(),
            options.japaneseGlyphVariants().get(), options.languageCode);
    }

    /**
     * Refuses a pack selection holding a built-in pack that redraws vanilla's resources, or a pack from
     * the client's resource-pack folder the run did not ask for.
     *
     * @param selected the ids of every pack the client selected
     * @param requested the ids of the folder packs the run asked for, empty for a run that names none
     * @throws IllegalStateException if a redrawing built-in pack or an unrequested folder pack is selected
     */
    static void requireVanillaPacks(Collection<String> selected, List<String> requested) {
        List<String> foreign = selected.stream()
            .filter(id -> REDRAWING_PACKS.contains(id) || (id.startsWith(HarnessConfig.FOLDER_PACK_PREFIX) && !requested.contains(id)))
            .toList();
        if (!foreign.isEmpty()) {
            throw new IllegalStateException(String.format(
                "Every run draws over vanilla's own resources and the packs it asked for, '%s', and this client also selected '%s' of '%s'",
                requested, foreign, selected));
        }
    }

    /**
     * Refuses text options other than vanilla's defaults.
     *
     * @param highContrast whether the client runs at high contrast
     * @param forcedUnicode whether the client forces the Unicode font
     * @param japaneseVariants whether the client draws the Japanese glyph variants
     * @param language the client's language code
     * @throws IllegalStateException if any option is on, or the language is not {@code en_us}
     */
    static void requireVanillaOptions(boolean highContrast, boolean forcedUnicode, boolean japaneseVariants, String language) {
        if (highContrast || forcedUnicode || japaneseVariants || !"en_us".equals(language)) {
            throw new IllegalStateException(String.format(
                "Every run draws at high contrast, forced Unicode font and Japanese glyph variants off in 'en_us', "
                    + "and this client runs them at '%s', '%s' and '%s' in '%s'",
                highContrast, forcedUnicode, japaneseVariants, language));
        }
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

    /**
     * Total subjects whose render or write failed, across every sweep this run drove.
     *
     * <p>A failed subject writes no PNG and leaves the previous run's file standing, so a partial
     * sweep produces a tree that hashes cleanly minus the files it did not refresh - which is
     * indistinguishable, in a manifest, from a whole one. The count was reachable only by reading
     * {@code failed=} out of a log nobody kept; it is the run's exit status now.
     *
     * <p>It sums two sources, because a subject can fail on either side of its copy. The runners'
     * tallies count a render that threw and a PNG directory that could not be made;
     * {@link TextureReadback#failedWrites()} counts a read-back or a PNG write that failed after the
     * copy landed, a frame or more after the runner had counted that subject rendered.
     *
     * @return the summed failure tally
     */
    static int failedSubjects() {
        return runners.stream().mapToInt(runner -> runner.tally().failed()).sum() + TextureReadback.failedWrites();
    }

    static void tick(Minecraft client) {
        SweepContext ctx = new SweepContext(client, HarnessConfig.OUTPUT_DIR, TARGETS);
        for (SweepRunner<?> runner : runners) {
            if (runner.isDone()) continue;
            // Armed before the step rather than once at start, because one boot renders sweeps at
            // more than one gait now. Every reader - both setupAnim redirects, the two model
            // cancels, the clock, the idle figures and the bounds walk - asks PoseState per render,
            // so arming here covers the enumerate and the canvas pre-pass as well as the draw.
            PoseState.arm(runner.gait());
            runner.stepBatch(ctx, HarnessConfig.RENDERS_PER_TICK);
            return;
        }
    }
}
