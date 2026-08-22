package lib.minecraft.refharness;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.sweep.ArmorSweep;
import lib.minecraft.refharness.sweep.EntityAnimationSweep;
import lib.minecraft.refharness.sweep.GlintSweep;
import lib.minecraft.refharness.sweep.MenuSweep;
import lib.minecraft.refharness.sweep.PlayerSweep;

import java.nio.file.Path;
import java.nio.file.Paths;

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
     * When {@code true}, the harness runs <em>only</em> the {@link GlintSweep} (the 7 always-foil
     * GUI items + the 4 worn leather-armor diagnostics), each as an animated sequence of per-frame
     * PNGs under {@code references/glint/}, and skips the block / item / entity sweeps entirely. Keeps
     * glint iteration fast and decoupled from the ~5-minute full reference sweep. Pair with
     * {@code -PrefharnessGlintOnly=true} on {@code renderVanillaGlintReferences}.
     */
    public static final boolean GLINT_ONLY = Boolean.getBoolean("refharness.glintOnly");

    /**
     * When {@code true}, the harness runs <em>only</em> the {@link PlayerSweep} (the vanilla player
     * FULL + SKULL references under {@code players/}), skipping the block / item / entity sweeps.
     * Keeps player-lighting iteration fast and decoupled from the full reference sweep. Pair with
     * {@code -PrefharnessPlayersOnly=true} on {@code renderVanillaPlayerReferences}.
     */
    public static final boolean PLAYERS_ONLY = Boolean.getBoolean("refharness.playersOnly");

    /**
     * When {@code true}, the harness runs <em>only</em> the {@link ArmorSweep} (armored mobs under
     * {@code armor/}), skipping the block / item / entity / player sweeps. The main entity sweep
     * equips nothing and renders no babies, so worn armor - and in particular vanilla's separate
     * baby armor model - has no ground truth without this mode. Pair with
     * {@code -PrefharnessArmorOnly=true} on {@code renderVanillaArmorReferences}.
     */
    public static final boolean ARMOR_ONLY = Boolean.getBoolean("refharness.armorOnly");

    /**
     * When {@code true}, the harness runs <em>only</em> the {@link MenuSweep} (the shipped container
     * screens under {@code menus/}), skipping every other sweep. A menu is the one subject drawn
     * through the client's own GUI pipeline rather than by submitting geometry, so it is the sweep
     * whose mechanism is worth iterating on alone. Pair with {@code -PrefharnessMenusOnly=true} on
     * {@code renderVanillaMenuReferences}.
     */
    public static final boolean MENUS_ONLY = Boolean.getBoolean("refharness.menusOnly");

    /**
     * When {@code true}, the harness runs <em>every</em> reference sweep in one boot - the block /
     * item / entity / player sweeps plus {@link GlintSweep} and {@link ArmorSweep}, which no other
     * mode runs alongside them. Each narrowing mode above renders part of the tree, so a change to a
     * frame renderer two of them share leaves whichever sweep nobody re-ran holding ground truth
     * recorded by the old code; this mode is the one that cannot. Pair with
     * {@code -PrefharnessEverySweep=true} on {@code renderVanillaAllReferences}.
     */
    public static final boolean EVERY_SWEEP = Boolean.getBoolean("refharness.everySweep");

    /**
     * When {@code true}, the harness runs <em>only</em> the {@link EntityAnimationSweep} - each
     * entity posed at every tick of one shared schedule, under {@code animation/} - and, being the
     * one run that wants vanilla's own animation, it is also what turns the two freezes off:
     * {@code SkipSetupAnimMixin} lets {@code setupAnim} through and
     * {@code FreezeAnimationStateMixin} answers {@code ageInTicks} as the frame's tick rather than
     * as zero.
     *
     * <p><b>An animated run and a frozen one cannot share a boot.</b> Both mixins decide per render
     * from a value read once per JVM, so a run that poses one subject poses every subject - and the
     * seven static sub-trees are defined by those freezes being in force. That is what makes this a
     * mode of its own rather than an option on {@link lib.minecraft.refharness.sweep.EntitySweep},
     * and why {@link #EVERY_SWEEP} does not run it: the task that refreshes the whole tree boots the
     * client twice. Pair with {@code -PrefharnessAnimated=true} on
     * {@code renderVanillaAnimationReferences}.
     */
    public static final boolean ANIMATED = Boolean.getBoolean("refharness.animated");

    /**
     * Diagnostic flag: when {@code true}, the entity sweeper renders the first filtered
     * target {@code 24 * 24 = 576} times - every combination of pitch (0°-345° in 15°
     * steps) and roll (0°-345° in 15° steps), holding yaw at the
     * {@code ISO_ROTATION}-locked value. Each output named
     * {@code <ns>__<id>_pNNN_rNNN.png} so a file browser sorted by name shows pitch as
     * outer dimension. Used to find the right pitch+roll combination when neither axis
     * alone gives the desired screen orientation (Euler-angle gimbal interaction).
     */
    public static final boolean PITCH_ROLL_SWEEP = Boolean.getBoolean("refharness.pitchRollSweep");

    /**
     * Diagnostic flag: when {@code true}, the run renders the depth-quantum probe instead of any
     * reference sweep - two overlapping quads a swept distance apart, whose contested band says how
     * finely the depth test can tell two surfaces apart. Writes outside the reference tree and
     * re-renders nothing.
     */
    public static final boolean DEPTH_QUANTUM_PROBE = Boolean.getBoolean("refharness.depthQuantumProbe");
}
