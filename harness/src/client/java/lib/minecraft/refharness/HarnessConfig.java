package lib.minecraft.refharness;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * System-property-driven configuration for the reference-render harness.
 *
 * <p>Read once at JVM start; the values mirror Loom-run-config properties
 * declared in {@code build.gradle}. Pass overrides on the command line as
 * {@code -Drefharness.<key>=<value>}.
 */
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
     * canvas is bigger than chicken's, which is bigger than silverfish's. Choose for HD output;
     * 256 keeps a 16×16 vanilla texel mapped to a 16×16 image region.
     */
    public static final int PIXELS_PER_BLOCK = Integer.getInteger("refharness.pixelsPerBlock", 256);

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
     */
    public static final int MAX_CANVAS_SIZE = Integer.getInteger("refharness.maxCanvasSize", 1024);

    /**
     * Optional comma-separated allowlist of {@code <namespace>:<id>} targets. When present,
     * only these blocks/entities are rendered. Useful for the Stage 3 verification gate
     * ({@code -Drefharness.targets=minecraft:stone,minecraft:cow}). Empty means "all".
     */
    public static final String TARGETS = System.getProperty("refharness.targets", "");

    /**
     * When {@code true}, the harness runs <em>only</em> the {@link GlintSweeper} (the 7 always-foil
     * GUI items + the 4 worn leather-armor diagnostics), each as an animated sequence of per-frame
     * PNGs under {@code references/glint/}, and skips the block / item / entity sweeps entirely. Keeps
     * glint iteration fast and decoupled from the ~5-minute full reference sweep. Pair with
     * {@code -PrefharnessGlintOnly=true} on {@code renderVanillaGlintReferences}.
     */
    public static final boolean GLINT_ONLY = Boolean.getBoolean("refharness.glintOnly");

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

    private HarnessConfig() {}
}
