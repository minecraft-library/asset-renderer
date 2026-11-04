package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.sweep.TooltipSweep;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * System-property-driven configuration for the reference-render harness.
 *
 * <p>Read once at JVM start; the values mirror Loom-run-config properties
 * declared in {@code build.gradle}. Pass overrides on the command line as
 * {@code -Drefharness.<key>=<value>}. The two canvas-sizing constants are the
 * exception - they are frozen literals rather than properties, for the reason
 * given on each.
 */
@UtilityClass
public final class HarnessConfig {

    /**
     * Master switch. Without this, the mod loads but never renders or exits.
     */
    public static final boolean ENABLED = Boolean.getBoolean("refharness.headless");

    /**
     * Where PNGs are written. Defaults to {@code build/refharness-output/} relative to the run dir.
     */
    public static final Path OUTPUT_DIR = Paths.get(
        System.getProperty("refharness.outputDir", "build/refharness-output"));

    /**
     * Square edge length (pixels) of every rendered <em>block</em> PNG. Entity renders use {@link #PIXELS_PER_BLOCK} instead.
     */
    public static final int IMAGE_SIZE = Integer.getInteger("refharness.size", 512);

    /**
     * Texel resolution (pixels per Minecraft block-unit) for entity renders. Each entity-family
     * canvas is sized to the family-max screen-space bounds × this constant, so all members of
     * a family render at the same scale and shared geometry is pixel-identical across variants
     * (cow body in {@code cow.png} is byte-for-byte the same as cow body region in
     * {@code mooshroom.png}). Different families produce different canvas sizes - cow's family
     * canvas is bigger than chicken's, which is bigger than silverfish's. 256 keeps a 16×16
     * vanilla texel mapped to a 16×16 image region.
     *
     * <p><b>Frozen, and deliberately not a system property.</b> asset-renderer's
     * {@code EntityOptions} holds the same number and the two size the same canvas from opposite
     * sides, so a value read independently on each JVM is a canvas that can decouple silently -
     * every entity reference would be measured at one scale and compared against renders taken at
     * another, and the comparison would report framing rather than the render. Nothing forwarded
     * either property, so the documented {@code -Drefharness.pixelsPerBlock=512} override was inert
     * in both repositories; a literal on each side makes the decoupled state unrepresentable rather
     * than merely undetected. Changing it means editing both constants in one commit.
     */
    public static final int PIXELS_PER_BLOCK = 256;

    /**
     * Hard cap (pixels) on either side of an entity-family canvas. Entities whose family-max
     * bounds × {@link #PIXELS_PER_BLOCK} would exceed this cap (ender_dragon, wither at full
     * scale, giant×6) are scaled down uniformly so the longer canvas side equals the cap;
     * shorter side and {@code scale} shrink proportionally so the entity still fits within
     * the canvas at the family's union centre. Below the cap, families render at the full
     * {@code PIXELS_PER_BLOCK} scale and parity-test against asset-renderer output remains
     * pixel-comparable; above the cap, large families lose the constant-scale property
     * relative to small ones (a hard but acceptable trade since cross-family parity was
     * already only approximate).
     *
     * <p><b>Frozen for the same reason as {@link #PIXELS_PER_BLOCK}</b>, and more sharply: above the
     * cap an over-measure stops being padding and becomes a uniform resize, so two sides holding
     * different caps would render the large families at different sizes rather than in different
     * frames. Changing it means editing this constant and asset-renderer's {@code EntityOptions} in
     * one commit.
     */
    public static final int MAX_CANVAS_SIZE = 1024;

    /**
     * Optional comma-separated allowlist of {@code <namespace>:<id>} targets. When present,
     * only these blocks/entities are rendered - scoping a run to a handful of subjects
     * ({@code -Drefharness.targets=minecraft:stone,minecraft:cow}). Empty means "all".
     */
    public static final String TARGETS = System.getProperty("refharness.targets", "");

    /**
     * Comma-separated {@link HarnessMode} names this run performs, case-insensitively - the way to
     * ask one boot for more than one sub-tree ({@code -Drefharness.modes=EVERY,ANIMATION,WALK}).
     *
     * <p>It is the one way a run names a reference mode, so the task named for a sub-tree cannot leave
     * out a line of its own and silently render {@link HarnessMode#FULL} instead. Empty, with
     * {@link #PITCH_ROLL_SWEEP} unset, is {@link HarnessMode#FULL}.
     *
     * <p><b>What a posed run needs is a {@link Gait}, and a gait is armed per sweep rather than per
     * JVM.</b> {@code SkipSetupAnimMixin}'s redirects both decide per render, so the freezes never
     * needed to be a property of the boot; {@link PoseState} holds the armed one and
     * {@link HarnessMode#resolve} orders a run so no sweep is handed a mesh a later gait mutated.
     */
    public static final String MODES = System.getProperty("refharness.modes", "");

    /**
     * The roster the {@link TooltipSweep} draws, by absolute path. The harness build defaults it to
     * the tracked roster under asset-renderer's {@code src/visual/resources},
     * {@code lib/minecraft/renderer/sweep/tooltip-roster.json}, so only a launch outside that build
     * leaves it empty - which the sweep refuses before it draws anything.
     */
    public static final String TOOLTIP_ROSTER = System.getProperty("refharness.tooltipRoster", "");

    /**
     * How many subjects one client tick renders, {@code 8} by default.
     *
     * <p>A client ticks twenty times a second whatever the machine can draw, so the tick clock prices
     * a run: at one render a tick, a 2310-reference tree waits nearly two minutes on it alone, ahead
     * of the boot. {@code PipTarget} retires a colour texture a canvas resize replaces rather than
     * closing it, and closes it only once enough renders are queued behind it that no copy in flight
     * can meet it, so this is a throughput knob rather than a correctness fence.
     *
     * <p>Measured on the whole tree on the machine the reference tree is rendered on, every reading
     * byte-identical to the stored tree: {@code 8} renders it in {@code 1m 25s} and {@code 16} in
     * {@code 52s}, and at both every sub-tree of more than a hundred references renders at the cap the
     * value sets - 160 and 320 a second. So on that machine the tick bounds the run at either value,
     * and the default is not the fastest one measured.
     *
     * <p>It stays at {@code 8} because a value past it is measured on one machine only, and what it
     * costs depends on the GPU: each render queues a copy the GPU has to execute, and a machine asked
     * for more per tick than its GPU retires moves the wait from the tick to the copies, while doubling
     * the value doubles the renders, {@code max(8, 2 x RENDERS_PER_TICK)}, that a replaced texture is
     * held open for. {@code -PrefharnessRendersPerTick} takes another value for one run.
     */
    public static final int RENDERS_PER_TICK = Math.max(1, Integer.getInteger("refharness.rendersPerTick", 8));

    /**
     * Diagnostic flag: when {@code true}, the entity sweeper renders the first filtered
     * target {@code 24 * 24 = 576} times - every combination of pitch (0°-345° in 15°
     * steps) and roll (0°-345° in 15° steps), holding yaw at the
     * {@code HarnessPose.ISO}-locked value. Each output named
     * {@code <ns>__<id>_pNNN_rNNN.png} so a file browser sorted by name shows pitch as
     * outer dimension. Used to find the right pitch+roll combination when neither axis
     * alone gives the desired screen orientation (Euler-angle gimbal interaction).
     */
    public static final boolean PITCH_ROLL_SWEEP = Boolean.getBoolean("refharness.pitchRollSweep");

    /**
     * Comma-separated file names of the resource packs the client selects over its own selection at
     * boot, lowest priority first - each one a zip or a directory in the folder the launch's
     * {@code --resourcePackDir} names, which is where the client discovers them exactly as it would a
     * player's. Empty selects none, and every reference run is one that selects none.
     *
     * <p>Only the {@linkplain HarnessMode#loadsPacks() pack modes} run with any: a reference rendered
     * through a pack is no longer vanilla's ground truth, so {@link HarnessMode#resolve} refuses a run
     * that names packs beside any other mode.
     */
    public static final String PACKS = System.getProperty("refharness.packs", "");

    /**
     * Returns the pack-repository ids of {@link #PACKS}, in the order given - the {@code file/}
     * prefix being how the client's folder source names each pack it discovers.
     *
     * @return the requested pack ids, empty when no pack is named
     */
    public static List<String> packIds() {
        List<String> ids = new ArrayList<>();
        for (String name : PACKS.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) ids.add("file/" + trimmed);
        }
        return List.copyOf(ids);
    }
}
