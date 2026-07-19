package lib.minecraft.renderer.asset.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.pack.FormatRange.FormatVersion;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.JsonTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link FormatRange} - the one normalization of all three pack-format generations. Covers
 * every row of the tolerance table: legacy {@code pack_format}, the three {@code supported_formats}
 * encodings, the modern int and {@code [major,minor]} {@code min_format}/{@code max_format} forms,
 * the minor-widened bare-int max, newest-generation-wins when keys coexist, and inclusive
 * containment.
 */
@DisplayName("FormatRange three-generation normalization")
class FormatRangeTest {

    private static final int MAX = FormatVersion.MAX_MINOR;

    private static FormatRange fromPack(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return FormatRange.fromPackObject(JsonTree.wrap(root.getAsJsonObject("pack")), "test");
    }

    private static FormatRange expect(int minMajor, int minMinor, int maxMajor, int maxMinor) {
        return new FormatRange(new FormatVersion(minMajor, minMinor), new FormatVersion(maxMajor, maxMinor));
    }

    @Test
    @DisplayName("legacy pack_format widens the max minor")
    void legacyPackFormat() {
        assertThat(fromPack("{\"pack\":{\"pack_format\":84}}"), is(expect(84, 0, 84, MAX)));
    }

    @Test
    @DisplayName("supported_formats int, [min,max], and {min_inclusive,max_inclusive}")
    void supportedFormats() {
        assertThat(fromPack("{\"pack\":{\"supported_formats\":46}}"), is(expect(46, 0, 46, MAX)));
        assertThat(fromPack("{\"pack\":{\"supported_formats\":[40,50]}}"), is(expect(40, 0, 50, MAX)));
        assertThat(fromPack("{\"pack\":{\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":64}}}"), is(expect(34, 0, 64, MAX)));
    }

    @Test
    @DisplayName("modern min_format/max_format as ints minor-widen the max (D5)")
    void modernInts() {
        assertThat(fromPack("{\"pack\":{\"min_format\":88,\"max_format\":88}}"), is(expect(88, 0, 88, MAX)));
        assertThat(fromPack("{\"pack\":{\"min_format\":69,\"max_format\":255}}"), is(expect(69, 0, 255, MAX)));
    }

    @Test
    @DisplayName("modern [major,minor] arrays are taken exactly")
    void modernArrays() {
        assertThat(fromPack("{\"pack\":{\"min_format\":[34,1],\"max_format\":[75,1]}}"), is(expect(34, 1, 75, 1)));
    }

    @Test
    @DisplayName("newest generation wins when all three coexist (defrosted)")
    void newestGenerationWins() {
        String defrosted = "{\"pack\":{\"pack_format\":34,"
            + "\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":64},"
            + "\"min_format\":[34,1],\"max_format\":[75,1]}}";
        assertThat(fromPack(defrosted), is(expect(34, 1, 75, 1)));
    }

    @Test
    @DisplayName("no format key of any generation yields ANY")
    void absentIsAny() {
        assertThat(fromPack("{\"pack\":{\"description\":\"x\"}}"), is(FormatRange.ANY));
    }

    @Test
    @DisplayName("containment is inclusive; the widened max admits any minor")
    void containment() {
        FormatRange r = expect(88, 0, 88, MAX);
        assertThat(r.contains(new FormatVersion(88, 0)), is(true));
        assertThat(r.contains(new FormatVersion(88, 5)), is(true));
        assertThat(r.contains(new FormatVersion(89, 0)), is(false));
        assertThat(r.contains(new FormatVersion(87, MAX)), is(false));

        assertThat(expect(69, 0, 255, MAX).contains(new FormatVersion(255, 1)), is(true));
        assertThat(expect(34, 1, 75, 1).contains(new FormatVersion(34, 0)), is(false));
        assertThat(expect(34, 1, 75, 1).contains(new FormatVersion(34, 1)), is(true));
    }

    @Test
    @DisplayName("malformed format encodings are hard errors")
    void malformed() {
        assertThrows(PipelineException.class,
            () -> fromPack("{\"pack\":{\"supported_formats\":[1,2,3]}}"));
        assertThrows(PipelineException.class,
            () -> fromPack("{\"pack\":{\"supported_formats\":{\"min_inclusive\":1}}}"));
        assertThrows(PipelineException.class,
            () -> fromPack("{\"pack\":{\"supported_formats\":\"nonsense\"}}"));
    }

}
