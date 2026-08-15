package lib.minecraft.renderer.engine.compose;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.compose.ChromeDecomposition.Border;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Runs {@link ChromeSlicer} over a corpus of chrome shapes authored to break it.
 * <p>
 * The shipped container art proves nothing about the part of the slicer that is not a nine-slice:
 * every feature vanilla draws is a corner. These fixtures are the ones that are not - a crest
 * centred on an edge, a stud at a period dividing nothing, a motif straddling a corner, a feature
 * covering most of its band - and each carries resize ground truth authored with it rather than read
 * back off a decomposition.
 * <p>
 * The round trip is deliberately not the assertion here. It holds for every choice of band depth, so
 * it is blind to the one number a resize is a function of. What is asserted is what resizing is for:
 * corner art survives unscaled, a unique motif appears once where its anchor puts it, a periodic band
 * keeps its period, and a ramp does not restart.
 */
@DisplayName("ChromeSlicer over the adversarial corpus")
class ChromeSlicerCorpusTest {

    /** where the fixtures and their ground truth sit on the test classpath */
    private static final String ROOT = "/lib/minecraft/renderer/chrome/";

    /**
     * Fixtures the slicer is known to get wrong. A row here is a defect the corpus has measured and
     * the algorithm has not closed, kept visible rather than deleted - and the assertion is inverted
     * for it, so closing the defect turns this red and the row has to go.
     * <p>
     * {@code a24} is a 60 slice uniform feature in a 90 slice band. The per-residue modal slice is
     * therefore the feature's rather than the background's, the feature becomes what the band
     * repeats, and growing the band to 192 finds 97 copies of something that should appear once. The
     * band-start tile recovers it and costs 1300 bytes against the modal's 780, so minimum
     * description length picks the wrong one.
     * <p>
     * It is not a missing tie-break. {@code a28} is the same shape - one contiguous conforming middle
     * between two deviating end runs - with the roles reversed, its end runs being the features and
     * its middle the background. Preferring fewer runs, or requiring the tile to conform at a band
     * end, resolves {@code a24} and breaks {@code a28} by exactly the same argument. What separates
     * them is which region the author meant, and one sample of each does not carry it.
     */
    private static final List<String> KNOWN_OPEN = List.of("a24_majority_edge_feature.png");

    private static JsonObject manifest() {
        try (InputStream in = ChromeSlicerCorpusTest.class.getResourceAsStream(ROOT + "manifest.json")) {
            if (in == null) throw new IllegalStateException("corpus manifest is not on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception failure) {
            throw new IllegalStateException("corpus manifest did not parse", failure);
        }
    }

    private static PixelBuffer fixture(String name) {
        try (InputStream in = ChromeSlicerCorpusTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IllegalStateException("fixture '" + name + "' is not on the test classpath");
            return PixelBuffer.wrap(ImageIO.read(in));
        } catch (Exception failure) {
            throw new IllegalStateException("fixture '" + name + "' did not load", failure);
        }
    }

    private static Optional<Border> declaredBorder(JsonObject row) {
        if (!row.has("border") || row.get("border").isJsonNull()) return Optional.empty();
        if (row.get("border").isJsonPrimitive()) {
            int all = row.get("border").getAsInt();
            return Optional.of(new Border(all, all, all, all));
        }
        JsonObject b = row.getAsJsonObject("border");
        return Optional.of(new Border(b.get("left").getAsInt(), b.get("top").getAsInt(),
            b.get("right").getAsInt(), b.get("bottom").getAsInt()));
    }

    private static ChromeDecomposition derive(JsonObject row, PixelBuffer art) {
        boolean stretchInner = !row.has("stretch_inner") || row.get("stretch_inner").isJsonNull()
            || row.get("stretch_inner").getAsBoolean();
        return ChromeSlicer.decompose(art, declaredBorder(row), stretchInner);
    }

    // ------------------------------------------------------------------ the four resize checks

    private static boolean blockEquals(PixelBuffer a, int ax, int ay, PixelBuffer b, int bx, int by, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (a.getPixel(ax + x, ay + y) != b.getPixel(bx + x, by + y)) return false;

        return true;
    }

    private static void checkCorners(List<String> failures, PixelBuffer art, PixelBuffer out,
                                     int[] corner, int w2, int h2) {
        int cl = corner[0], ct = corner[1], cr = corner[2], cb = corner[3];
        int w = art.width(), h = art.height();
        if (cl + cr > Math.min(w2, w) || ct + cb > Math.min(h2, h)) return;

        int[][] blocks = {
            { 0, 0, 0, 0, cl, ct }, { w - cr, 0, w2 - cr, 0, cr, ct },
            { 0, h - cb, 0, h2 - cb, cl, cb }, { w - cr, h - cb, w2 - cr, h2 - cb, cr, cb },
        };
        String[] tags = { "TL", "TR", "BL", "BR" };

        for (int i = 0; i < blocks.length; i++) {
            int[] blk = blocks[i];
            if (blk[4] == 0 || blk[5] == 0) continue;
            if (!blockEquals(art, blk[0], blk[1], out, blk[2], blk[3], blk[4], blk[5]))
                failures.add("corner " + tags[i]);
        }
    }

    /** Every position along the band where the motif appears verbatim. */
    private static List<Integer> motifHits(PixelBuffer out, PixelBuffer art, JsonObject motif,
                                           int[] corner, int w2, int h2, boolean horizontal) {
        int cl = corner[0], ct = corner[1], cr = corner[2], cb = corner[3];
        int off = motif.get("off").getAsInt(), mw = motif.get("width").getAsInt();
        int depth = horizontal ? (motif.get("edge").getAsString().equals("TOP") ? ct : cb)
                               : (motif.get("edge").getAsString().equals("LEFT") ? cl : cr);
        if (depth == 0) return List.of();

        int lo = horizontal ? cl : ct;
        int hi = horizontal ? w2 - cr : h2 - cb;
        int srcBase = horizontal ? cl + off : ct + off;
        int srcDepth0 = horizontal ? (motif.get("edge").getAsString().equals("TOP") ? 0 : art.height() - cb)
                                   : (motif.get("edge").getAsString().equals("LEFT") ? 0 : art.width() - cr);
        int outDepth0 = horizontal ? (motif.get("edge").getAsString().equals("TOP") ? 0 : h2 - cb)
                                   : (motif.get("edge").getAsString().equals("LEFT") ? 0 : w2 - cr);

        List<Integer> hits = new ArrayList<>();
        for (int at = lo; at + mw <= hi; at++) {
            boolean same = true;
            for (int k = 0; k < mw && same; k++)
                for (int d = 0; d < depth; d++) {
                    int want = horizontal ? art.getPixel(srcBase + k, srcDepth0 + d) : art.getPixel(srcDepth0 + d, srcBase + k);
                    int got = horizontal ? out.getPixel(at + k, outDepth0 + d) : out.getPixel(outDepth0 + d, at + k);
                    if (want != got) {
                        same = false;
                        break;
                    }
                }
            if (same) hits.add(at);
        }
        return hits;
    }

    private static void checkMotifs(List<String> failures, PixelBuffer art, PixelBuffer out,
                                    JsonArray motifs, int[] corner, int w2, int h2) {
        for (var element : motifs) {
            JsonObject motif = element.getAsJsonObject();
            String edge = motif.get("edge").getAsString();
            boolean horizontal = edge.equals("TOP") || edge.equals("BOTTOM");
            int mw = motif.get("width").getAsInt();
            int lo = horizontal ? corner[0] : corner[1];
            int hi = horizontal ? w2 - corner[2] : h2 - corner[3];
            if (hi - lo < mw) continue;

            List<Integer> hits = motifHits(out, art, motif, corner, w2, h2, horizontal);
            int want = motif.get("count").getAsInt();
            String label = "motif " + edge.charAt(0) + "[" + motif.get("off").getAsInt() + "+" + mw + "]"
                + motif.get("anchor").getAsString().charAt(0);

            // A motif the corpus itself records as beyond the model is not a regression. The reason
            // travels with the fixture, so this stays a statement about the algorithm's edge rather
            // than a silently skipped case.
            if (motif.has("expect_fail")) continue;

            if (hits.size() != want) {
                failures.add(label + " count " + hits.size() + " of " + want);
                continue;
            }
            if (hits.isEmpty()) continue;

            switch (motif.get("anchor").getAsString()) {
                case "CENTER" -> {
                    double mid = hits.getFirst() + mw / 2.0;
                    if (Math.abs(mid - (lo + hi) / 2.0) > 1.0) failures.add(label + " off centre");
                }
                case "START" -> {
                    int near = horizontal ? corner[0] : corner[1];
                    if (hits.getFirst() != near + motif.get("off").getAsInt()) failures.add(label + " off start");
                }
                case "END" -> {
                    int srcFar = (horizontal ? art.width() : art.height())
                        - ((horizontal ? corner[0] : corner[1]) + motif.get("off").getAsInt() + mw);
                    if (hits.getFirst() != (horizontal ? w2 : h2) - srcFar - mw) failures.add(label + " off end");
                }
                default -> { }
            }
        }
    }

    /** One band of the output, as a slice-major array of its pixels. */
    private static int[][] band(PixelBuffer img, String edge, int lo, int hi, int depth, int w2, int h2) {
        int n = hi - lo;
        int[][] out = new int[Math.max(0, n)][depth];
        for (int i = 0; i < n; i++)
            for (int d = 0; d < depth; d++)
                out[i][d] = switch (edge) {
                    case "TOP" -> img.getPixel(lo + i, d);
                    case "BOTTOM" -> img.getPixel(lo + i, h2 - depth + d);
                    case "LEFT" -> img.getPixel(d, lo + i);
                    default -> img.getPixel(w2 - depth + d, lo + i);
                };
        return out;
    }

    private static int bandDepth(String edge, int[] corner) {
        return switch (edge) {
            case "TOP" -> corner[1];
            case "BOTTOM" -> corner[3];
            case "LEFT" -> corner[0];
            default -> corner[2];
        };
    }

    private static void checkPeriods(List<String> failures, PixelBuffer out, JsonObject periods,
                                     int[] corner, int w2, int h2) {
        for (String edge : periods.keySet()) {
            int p = periods.get(edge).getAsInt();
            int depth = bandDepth(edge, corner);
            boolean horizontal = edge.equals("TOP") || edge.equals("BOTTOM");
            int lo = horizontal ? corner[0] : corner[1];
            int hi = horizontal ? w2 - corner[2] : h2 - corner[3];
            if (depth == 0 || hi - lo <= p) continue;

            int[][] slices = band(out, edge, lo, hi, depth, w2, h2);
            int breaks = 0;
            for (int i = p; i < slices.length; i++)
                if (!java.util.Arrays.equals(slices[i], slices[i - p])) breaks++;

            if (breaks != 0) failures.add("period " + edge.charAt(0) + "=" + p + " broken on " + breaks);
        }
    }

    private static void checkMonotone(List<String> failures, PixelBuffer out, JsonArray edges,
                                      int[] corner, int w2, int h2) {
        for (var element : edges) {
            String edge = element.getAsString();
            int depth = bandDepth(edge, corner);
            boolean horizontal = edge.equals("TOP") || edge.equals("BOTTOM");
            int lo = horizontal ? corner[0] : corner[1];
            int hi = horizontal ? w2 - corner[2] : h2 - corner[3];
            if (depth == 0 || hi - lo < 3) continue;

            int[][] slices = band(out, edge, lo, hi, depth, w2, h2);
            long[][] sums = new long[slices.length][4];
            for (int i = 0; i < slices.length; i++)
                for (int px : slices[i]) {
                    sums[i][0] += (px >>> 24) & 0xFF;
                    sums[i][1] += (px >>> 16) & 0xFF;
                    sums[i][2] += (px >>> 8) & 0xFF;
                    sums[i][3] += px & 0xFF;
                }

            int reversals = 0;
            for (int c = 0; c < 4; c++) {
                int up = 0, down = 0;
                for (int i = 1; i < sums.length; i++) {
                    if (sums[i][c] > sums[i - 1][c]) up++;
                    if (sums[i][c] < sums[i - 1][c]) down++;
                }
                reversals += Math.min(up, down);
            }
            if (reversals != 0) failures.add("monotone " + edge.charAt(0) + " reverses " + reversals);
        }
    }

    // ------------------------------------------------------------------ the suite

    @TestFactory
    @DisplayName("every fixture round-trips at its authored size")
    Stream<DynamicTest> everyFixtureRoundTrips() {
        return manifest().getAsJsonArray("fixtures").asList().stream()
            .map(element -> element.getAsJsonObject())
            .map(row -> DynamicTest.dynamicTest(row.get("name").getAsString(), () -> {
                PixelBuffer art = fixture(row.get("name").getAsString());
                ChromeDecomposition d = derive(row, art);
                PixelBuffer back = ChromeSlicer.assemble(d, art, art.width(), art.height());

                int differing = 0;
                for (int y = 0; y < art.height(); y++)
                    for (int x = 0; x < art.width(); x++)
                        if (art.getPixel(x, y) != back.getPixel(x, y)) differing++;

                assertThat("differing pixels", differing, is(equalTo(0)));
            }));
    }

    @TestFactory
    @DisplayName("every fixture survives its declared resize targets")
    Stream<DynamicTest> everyFixtureSurvivesItsResizeTargets() {
        return manifest().getAsJsonArray("fixtures").asList().stream()
            .map(element -> element.getAsJsonObject())
            .map(row -> DynamicTest.dynamicTest(row.get("name").getAsString(), () -> {
                String name = row.get("name").getAsString();
                PixelBuffer art = fixture(name);
                ChromeDecomposition d = derive(row, art);
                JsonObject truth = row.getAsJsonObject("resize");

                // A fixture with no declared corner - a pure tile, which has no corner rects to
                // preserve - carries no corner, motif or period ground truth to assert.
                if (truth.get("corner").isJsonNull()) return;

                int[] corner = new int[4];
                JsonArray declaredCorner = truth.getAsJsonArray("corner");
                for (int i = 0; i < 4; i++) corner[i] = declaredCorner.get(i).getAsInt();

                List<String> failures = new ArrayList<>();
                for (var target : row.getAsJsonArray("targets")) {
                    int w2 = target.getAsJsonArray().get(0).getAsInt();
                    int h2 = target.getAsJsonArray().get(1).getAsInt();
                    PixelBuffer out = ChromeSlicer.assemble(d, art, w2, h2);
                    List<String> here = new ArrayList<>();

                    checkCorners(here, art, out, corner, w2, h2);
                    checkMotifs(here, art, out, truth.getAsJsonArray("motifs"), corner, w2, h2);
                    checkPeriods(here, out, truth.getAsJsonObject("periods"), corner, w2, h2);
                    checkMonotone(here, out, truth.getAsJsonArray("monotone"), corner, w2, h2);

                    for (String failure : here) failures.add(w2 + "x" + h2 + ": " + failure);
                }

                if (KNOWN_OPEN.contains(name)) {
                    assertThat(name + " is recorded as an open defect, so it must still fail - "
                        + "delete its row when it is fixed", failures, is(not(empty())));
                    return;
                }
                assertThat(name, failures, is(empty()));
            }));
    }

    @TestFactory
    @DisplayName("every detector step the corpus names has a fixture that exercises it")
    Stream<DynamicTest> everyStepHasAFixture() {
        JsonObject steps = manifest().getAsJsonObject("steps");
        List<String> names = manifest().getAsJsonArray("fixtures").asList().stream()
            .map(element -> element.getAsJsonObject().get("name").getAsString())
            .toList();

        return steps.keySet().stream().map(step -> DynamicTest.dynamicTest(step, () -> {
            for (String id : steps.get(step).getAsString().split(",\\s*")) {
                boolean present = names.stream().anyMatch(name -> name.startsWith(id.trim() + "_"));
                assertThat("step '" + step + "' names fixture '" + id + "', which the corpus holds", present, is(true));
            }
        }));
    }

    /** Guards the corpus itself: a fixture nobody lists is a fixture nobody runs. */
    @TestFactory
    @DisplayName("the corpus is whole")
    Stream<DynamicTest> theCorpusIsWhole() {
        JsonArray fixtures = manifest().getAsJsonArray("fixtures");
        return Stream.of(
            DynamicTest.dynamicTest("thirty-one fixtures", () ->
                assertThat(fixtures.size(), is(equalTo(31)))),
            DynamicTest.dynamicTest("every fixture loads and matches its declared size", () -> {
                List<String> wrong = new ArrayList<>();
                for (var element : fixtures) {
                    JsonObject row = element.getAsJsonObject();
                    PixelBuffer art = fixture(row.get("name").getAsString());
                    String actual = art.width() + "x" + art.height();
                    if (!actual.equals(row.get("size").getAsString()))
                        wrong.add(row.get("name").getAsString() + " is " + actual);
                }
                assertThat(wrong, is(empty()));
            }),
            DynamicTest.dynamicTest("every fixture is named by some detector step", () -> {
                JsonObject steps = manifest().getAsJsonObject("steps");
                List<String> claimed = new ArrayList<>();
                for (String step : steps.keySet())
                    for (String id : steps.get(step).getAsString().split(",\\s*")) claimed.add(id.trim());

                List<String> unclaimed = new ArrayList<>();
                for (var element : fixtures) {
                    String name = element.getAsJsonObject().get("name").getAsString();
                    String id = name.substring(0, name.indexOf('_'));
                    if (!claimed.contains(id)) unclaimed.add(name);
                }
                assertThat("fixtures no step exercises, which are fixtures nobody runs for a reason",
                    unclaimed, is(empty()));
            }));
    }

}
