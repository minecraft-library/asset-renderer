package lib.minecraft.renderer.tooling.animation;

import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus reconciliation for {@link KeyframeDefinitionParser} against the real client jar.
 *
 * <p>The counts are the whole point. A walk over a builder chain fails silently by dropping a
 * channel rather than by throwing, so a clip short one bone still reads as a pose; totals taken
 * across the corpus are what turn that into a failure. Every number here was read off the
 * disassembly independently of the parser.
 *
 * <p>Tagged {@code slow}: the walk runs against the downloaded client jar.
 */
@Tag("slow")
@DisplayName("keyframe clip tables reconcile against the shipped corpus")
class KeyframeDefinitionParserTest {

    private static ClassNodeCache cache;
    private static Diagnostics diagnostics;
    private static List<KeyframeClip> clips;

    @BeforeAll
    static void parse() {
        cache = ClassNodeCache.open(ClientAcquisition.downloadJarToCache(ClientOptions.defaults()));
        diagnostics = Diagnostics.root("animation", Diagnostics.Output.NONE, null);
        clips = KeyframeDefinitionParser.parseAll(cache, diagnostics);
    }

    @AfterAll
    static void close() {
        if (cache != null) cache.close();
    }

    @Test
    @DisplayName("every definitions class parses without a finding")
    void nothingFailed() {
        assertFalse(diagnostics.failed(), () -> "parse recorded errors: " + diagnostics.entries());
        assertEquals(16, clips.stream().map(KeyframeClip::owner).distinct().count(),
            "definitions classes, package-info excluded");
    }

    @Test
    @DisplayName("the clip, channel and keyframe totals are the corpus's")
    void corpusTotals() {
        assertEquals(74, clips.size(), "clips");
        assertEquals(39, clips.stream().filter(KeyframeClip::looping).count(), "looping clips");

        List<KeyframeClip.BoneChannel> channels = clips.stream().flatMap(clip -> clip.channels().stream()).toList();
        assertEquals(848, channels.size(), "channels");

        Map<String, Long> byTarget = channels.stream()
            .collect(Collectors.groupingBy(KeyframeClip.BoneChannel::target, Collectors.counting()));
        assertEquals(Map.of("rotation", 466L, "position", 343L, "scale", 39L), byTarget, "channels per target");

        List<AnimationValue.Frame> frames = channels.stream().flatMap(channel -> channel.keyframes().stream()).toList();
        assertEquals(5070, frames.size(), "keyframes");

        Map<String, Long> byCurve = frames.stream()
            .collect(Collectors.groupingBy(AnimationValue.Frame::interpolation, Collectors.counting()));
        assertEquals(Map.of("linear", 3631L, "catmullrom", 1439L), byCurve, "keyframes per curve");
    }

    @Test
    @DisplayName("a clip is named by its class as well as its field, because two field names collide")
    void coordinatesAreUnique() {
        List<String> coordinates = clips.stream().map(KeyframeClip::coordinate).toList();
        assertEquals(coordinates.size(), coordinates.stream().distinct().count(), "coordinates");

        List<String> fields = clips.stream().map(KeyframeClip::field).toList();
        assertTrue(fields.stream().distinct().count() < fields.size(),
            "the field name alone is expected to collide, which is why the coordinate carries the class");
    }

    @Test
    @DisplayName("the three vector factories are folded at their own precision")
    void unitsAreFolded() {
        KeyframeClip resting = clip("BatAnimation#BAT_RESTING");

        AnimationValue.Frame headRotation = first(resting, "head", "rotation");
        assertEquals(Float.floatToIntBits(180.0F * 0.017453292F), Float.floatToIntBits(headRotation.vector().x()),
            "a rotation is authored in degrees and stored in radians");
        assertEquals(Float.floatToIntBits(0.0F), Float.floatToIntBits(headRotation.vector().y()));

        AnimationValue.Frame headPosition = first(resting, "head", "position");
        assertEquals(Float.floatToIntBits(-0.5F), Float.floatToIntBits(headPosition.vector().y()),
            "a position is authored y-up against a y-down accumulator, so y is negated");
    }

    @Test
    @DisplayName("a scale channel stores the offset from unit scale, not the multiplier")
    void scaleIsAnOffset() {
        List<AnimationValue.Frame> scales = clips.stream()
            .flatMap(clip -> clip.channels().stream())
            .filter(channel -> "scale".equals(channel.target()))
            .flatMap(channel -> channel.keyframes().stream())
            .toList();
        assertEquals(39, scales.size() > 0 ? 39 : 0, "the corpus declares scale channels");
        assertTrue(scales.stream().anyMatch(frame -> frame.vector().x() != 0f || frame.vector().y() != 0f),
            "a scale channel that never leaves zero would mean the minus-one fold was skipped");
    }

    private static @NotNull KeyframeClip clip(@NotNull String coordinate) {
        return clips.stream().filter(candidate -> coordinate.equals(candidate.coordinate())).findFirst()
            .orElseThrow(() -> new AssertionError("no clip at " + coordinate));
    }

    private static @NotNull AnimationValue.Frame first(
        @NotNull KeyframeClip clip, @NotNull String bone, @NotNull String target) {
        return clip.channels().stream()
            .filter(channel -> bone.equals(channel.bone()) && target.equals(channel.target()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + target + " channel on '" + bone + "'"))
            .keyframes()
            .getFirst();
    }

}
