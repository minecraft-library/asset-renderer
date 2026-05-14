package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.EntityRendererJava;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-entity parity report comparing the Java pipeline output (via {@link EntityRendererJava} +
 * {@code entity_models_java.json} / {@code entity_geometry_java.json}) against the bedrock
 * pipeline output (via {@link EntityRenderer} + {@code entity_models.json} /
 * {@code entity_geometry.json}). For each entity that exists in both pipelines, both renderers
 * produce a same-size PNG and the harness computes the mean per-pixel ARGB delta on a 0-255
 * scale. The bedrock pipeline is the known-good baseline; lower deltas mean closer parity.
 *
 * <p>Output: a {@code parity-report.tsv} file next to {@code cache/visual/entity-parity/} plus
 * the side-by-side PNG pairs (one bedrock + one java per entity, named identically). The TSV
 * is sorted ascending by mean delta, so the closest-to-parity entities appear first - useful
 * for confirming the harness mechanism on entities that should already match.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:entityParity [-PrenderSize=256]
 * [-PentityId=minecraft:zombie]}. Default size is 256 to keep the sweep fast (~1-2 minutes for
 * all 90 mobs).
 */
@UtilityClass
public final class TestEntityParity {

    /** Output directory for the per-entity bedrock + java PNG pair plus the report file. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-parity");

    /** TSV report file path. */
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("parity-report.tsv");

    /** Default render size; smaller than the standard 512 to keep the per-entity cost low. */
    private static final int DEFAULT_SIZE = 256;

    /**
     * Entities the maintainer has manually verified to be at acceptable parity, even when their
     * automated mean-delta is non-zero. Reasons vary: bedrock authoring choices the Java pipeline
     * intentionally does not reproduce (glow_squid is washed-out in bedrock; pig has a bedrock
     * cube_offset override the Java side correctly does not need), float-precision noise from the
     * texture re-encoding step, or sub-pixel anti-aliasing differences that are below visual
     * detection threshold. Listing here separates "investigate further" mobs from "known
     * acceptable" - the parity sweep prints the achieved ones separately and the bucket summary
     * also breaks them out.
     *
     * <p>Add entries only after side-by-side visual inspection (cache/visual/entity-parity/
     * &lt;entity&gt;_diff.png is the fastest way to scrutinise). Removing an entry is the right
     * call when an existing fix raises a previously-acceptable variance into a new regression
     * class.
     */
    private static final @NotNull Set<String> ACHIEVED_PARITY = Set.of(
        "minecraft:allay",
        "minecraft:armadillo",
        "minecraft:armor_stand",
        "minecraft:axolotl",
        "minecraft:bat",
        "minecraft:bee",
        "minecraft:blaze",
        "minecraft:bogged",
        "minecraft:breeze",
        "minecraft:camel",
        "minecraft:camel_husk",
        "minecraft:cat",
        "minecraft:cave_spider",
        "minecraft:chicken",
        "minecraft:chicken_cold",
        "minecraft:chicken_temperate",
        "minecraft:chicken_warm",
        "minecraft:cod",
        "minecraft:cow",
        "minecraft:cow_cold",
        "minecraft:cow_temperate",
        "minecraft:cow_warm",
        "minecraft:creaking",
        "minecraft:creeper",
        "minecraft:dolphin",
        "minecraft:donkey",
        "minecraft:drowned",
        "minecraft:elder_guardian",
        "minecraft:ender_dragon",
        "minecraft:enderman",
        "minecraft:endermite",
        "minecraft:evoker",
        "minecraft:fox",
        "minecraft:frog",
        "minecraft:frog_cold",
        "minecraft:frog_temperate",
        "minecraft:frog_warm",
        "minecraft:ghast",
        "minecraft:giant",
        "minecraft:glow_squid",
        "minecraft:goat",
        "minecraft:guardian",
        "minecraft:happy_ghast",
        "minecraft:hoglin",
        "minecraft:horse",
        "minecraft:husk",
        "minecraft:illusioner",
        "minecraft:iron_golem",
        "minecraft:llama",
        "minecraft:magma_cube",
        "minecraft:mooshroom",
        "minecraft:mule",
        "minecraft:nautilus",
        "minecraft:ocelot",
        "minecraft:panda",
        "minecraft:parched",
        "minecraft:parrot",
        "minecraft:phantom",
        "minecraft:pig",
        "minecraft:pig_cold",
        "minecraft:pig_temperate",
        "minecraft:pig_warm",
        "minecraft:piglin",
        "minecraft:piglin_brute",
        "minecraft:pillager",
        "minecraft:polar_bear",
        "minecraft:pufferfish",
        "minecraft:rabbit",
        "minecraft:ravager",
        "minecraft:salmon",
        "minecraft:sheep",
        "minecraft:shulker",
        "minecraft:silverfish",
        "minecraft:skeleton",
        "minecraft:skeleton_horse",
        "minecraft:slime",
        "minecraft:sniffer",
        "minecraft:spider",
        "minecraft:squid",
        "minecraft:strider",
        "minecraft:stray",
        "minecraft:tadpole",
        "minecraft:trader_llama",
        "minecraft:tropical_fish",
        "minecraft:turtle",
        "minecraft:vex",
        "minecraft:vindicator",
        "minecraft:wandering_trader",
        "minecraft:warden",
        "minecraft:witch",
        "minecraft:wither",
        "minecraft:wither_skeleton",
        "minecraft:wolf",
        "minecraft:wolf_ashen",
        "minecraft:wolf_black",
        "minecraft:wolf_chestnut",
        "minecraft:wolf_pale",
        "minecraft:wolf_rusty",
        "minecraft:wolf_snowy",
        "minecraft:wolf_spotted",
        "minecraft:wolf_striped",
        "minecraft:wolf_woods",
        "minecraft:zoglin",
        "minecraft:zombie",
        "minecraft:zombie_horse",
        "minecraft:zombie_nautilus",
        "minecraft:zombie_villager",
        "minecraft:zombified_piglin"
    );

    /**
     * Runs the parity sweep.
     *
     * @param args {@code args[0]} optional render size; {@code args[1]} optional comma-separated
     *     list of entity ids ({@code minecraft:zombie,minecraft:cave_spider}) - skipped by
     *     the user when sweeping all entities. Multiple ids share one pipeline-bootstrap +
     *     renderer-construction pass instead of paying it per call.
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        List<String> entityIdFilter = args.length > 1
            ? List.of(args[1].split(","))
            : List.of();

        Files.createDirectories(OUTPUT_DIR);

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        EntityRenderer bedrockRenderer = new EntityRenderer(context);

        ConcurrentMap<String, EntityModelLoader.EntityDefinition> javaEntities = EntityModelLoader.loadJava();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models_java.json missing - run :asset-renderer:entityModelsJava first");
            return;
        }
        EntityRendererJava javaRenderer = new EntityRendererJava(context, javaEntities);

        TreeSet<String> bedrockKeys = new TreeSet<>(EntityModelLoader.load().keySet());
        TreeSet<String> javaKeys = new TreeSet<>(javaEntities.keySet());
        TreeSet<String> intersection = new TreeSet<>(bedrockKeys);
        intersection.retainAll(javaKeys);

        List<String> entityIds = entityIdFilter.isEmpty()
            ? List.copyOf(intersection)
            : entityIdFilter;

        System.out.printf("Parity sweep: %d entities at %dx%d to %s (bedrock-only: %d, java-only: %d)%n",
            entityIds.size(), size, size, OUTPUT_DIR.toAbsolutePath(),
            bedrockKeys.size() - intersection.size(),
            javaKeys.size() - intersection.size());

        List<Row> rows = new ArrayList<>();
        long t0 = System.nanoTime();
        for (String entityId : entityIds) {
            String safeName = entityId.replace(':', '_');
            EntityOptions options = EntityOptions.builder()
                .entityId(Optional.of(entityId))
                .outputSize(size)
                .antiAlias(true)
                .build();
            try {
                ImageData bedrock = bedrockRenderer.render(options);
                ImageData java = javaRenderer.render(options);
                BufferedImage bedrockImg = bedrock.toBufferedImage();
                BufferedImage javaImg = java.toBufferedImage();
                ImageIO.write(bedrockImg, "PNG", new File(OUTPUT_DIR.toFile(), safeName + "_bedrock.png"));
                ImageIO.write(javaImg, "PNG", new File(OUTPUT_DIR.toFile(), safeName + "_java.png"));
                BufferedImage diffImg = diffImage(bedrockImg, javaImg);
                ImageIO.write(diffImg, "PNG", new File(OUTPUT_DIR.toFile(), safeName + "_diff.png"));
                Stats stats = compareImages(bedrockImg, javaImg);
                boolean achieved = ACHIEVED_PARITY.contains(entityId);
                rows.add(new Row(entityId, stats.meanDelta(), stats.differingPixels(), stats.javaCoverage(), stats.bedrockCoverage(), achieved));
                System.out.printf("  %s %-40s mean delta %.2f  diff-px %d  java-cov %.1f%%  bedrock-cov %.1f%%%n",
                    achieved ? "[OK]" : "    ",
                    entityId, stats.meanDelta(), stats.differingPixels(),
                    stats.javaCoverage() * 100, stats.bedrockCoverage() * 100);
            } catch (Exception ex) {
                System.err.printf("       %-40s FAILED: %s%n", entityId, ex.getMessage());
                rows.add(new Row(entityId, Double.POSITIVE_INFINITY, -1, 0, 0, false));
            }
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;

        rows.sort((a, b) -> Double.compare(a.meanDelta(), b.meanDelta()));

        StringBuilder report = new StringBuilder();
        report.append("entity_id\tmean_argb_delta\tdiffering_pixels\tjava_coverage\tbedrock_coverage\tparity_achieved\n");
        for (Row r : rows)
            report.append(String.format("%s\t%.4f\t%d\t%.4f\t%.4f\t%s%n",
                r.entityId(), r.meanDelta(), r.differingPixels(), r.javaCoverage(), r.bedrockCoverage(), r.achieved()));
        Files.writeString(REPORT_FILE, report.toString());
        System.out.printf("Wrote %s (%d rows, %d ms total)%n", REPORT_FILE, rows.size(), totalMs);

        long below1 = rows.stream().filter(r -> r.meanDelta() < 1.0).count();
        long below5 = rows.stream().filter(r -> r.meanDelta() < 5.0).count();
        long below20 = rows.stream().filter(r -> r.meanDelta() < 20.0).count();
        long achieved = rows.stream().filter(Row::achieved).count();
        long achievedNotInRun = ACHIEVED_PARITY.size() - achieved;
        System.out.printf("Parity buckets: <1: %d / <5: %d / <20: %d / total: %d%n",
            below1, below5, below20, rows.size());
        System.out.printf("Achieved-parity allowlist: %d in this run / %d total%s%n",
            achieved, ACHIEVED_PARITY.size(),
            achievedNotInRun > 0 ? " (" + achievedNotInRun + " not rendered in this run - subset filter)" : "");
        // Surface mobs in the focus pool (NOT in the allowlist) sorted by delta DESC. These are
        // the parity-test failures that actively drive the next session's work.
        List<Row> focus = rows.stream()
            .filter(r -> !r.achieved())
            .sorted((a, b) -> Double.compare(b.meanDelta(), a.meanDelta()))
            .toList();
        System.out.println("Focus pool (not in achieved-parity list, worst first):");
        for (Row r : focus.subList(0, Math.min(15, focus.size())))
            System.out.printf("    %-40s mean delta %.2f%n", r.entityId(), r.meanDelta());
    }

    /**
     * Computes per-pixel ARGB statistics between two same-size images. Mean delta is the
     * average of {@code |Aj - Ab| + |Rj - Rb| + |Gj - Gb| + |Bj - Bb|} over all pixels - a
     * pixel that's pure transparent in both images contributes 0; a fully different pixel
     * contributes up to 4 * 255 = 1020. Coverage is the fraction of pixels with non-zero alpha.
     */
    private static @NotNull Stats compareImages(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long totalDelta = 0L;
        long differing = 0L;
        long javaCoveredPx = 0L;
        long bedrockCoveredPx = 0L;
        long count = (long) w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int da = Math.abs(ColorMath.alpha(pa) - ColorMath.alpha(pb));
                int dr = Math.abs(ColorMath.red(pa) - ColorMath.red(pb));
                int dg = Math.abs(ColorMath.green(pa) - ColorMath.green(pb));
                int db = Math.abs(ColorMath.blue(pa) - ColorMath.blue(pb));
                int sum = da + dr + dg + db;
                totalDelta += sum;
                if (sum > 0) differing++;
                if (ColorMath.alpha(pa) > 0) bedrockCoveredPx++;
                if (ColorMath.alpha(pb) > 0) javaCoveredPx++;
            }
        }
        double mean = (double) totalDelta / (double) count;
        return new Stats(mean, differing, (double) javaCoveredPx / count, (double) bedrockCoveredPx / count);
    }

    /**
     * Builds a per-pixel diff image: where bedrock and java agree the pixel is fully
     * transparent; where they disagree the pixel encodes the absolute ARGB delta amplified
     * 4x so small numeric differences are visible. Coverage-only mismatches (one image has
     * alpha, the other does not) get a magenta tint to distinguish them from colour
     * differences at covered pixels. Mirrors the helper in {@link TestEntityBodyPartParity}
     * so both harnesses produce comparable diff PNGs.
     */
    private static @NotNull BufferedImage diffImage(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa);
                int ab = ColorMath.alpha(pb);
                int dr = Math.abs(ColorMath.red(pa) - ColorMath.red(pb));
                int dg = Math.abs(ColorMath.green(pa) - ColorMath.green(pb));
                int db = Math.abs(ColorMath.blue(pa) - ColorMath.blue(pb));
                int da = Math.abs(aa - ab);
                if (da == 0 && dr == 0 && dg == 0 && db == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                int amp = 4;
                int rr = Math.min(255, dr * amp);
                int gg = Math.min(255, dg * amp);
                int bb = Math.min(255, db * amp);
                out.setRGB(x, y, (255 << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
        return out;
    }

    /** Per-entity row in the TSV report. */
    private record Row(@NotNull String entityId, double meanDelta, long differingPixels, double javaCoverage, double bedrockCoverage, boolean achieved) {}

    /** Aggregate stats from {@link #compareImages}. */
    private record Stats(double meanDelta, long differingPixels, double javaCoverage, double bedrockCoverage) {}

}
