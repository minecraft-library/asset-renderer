package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * The store-canonical form, asserted on the bytes the Java writer emits.
 *
 * <p>Its Python twin has a suite of its own and this side had none, so nothing on this side of the
 * store held any clause of the form it writes. The asymmetry matters most where the two writers do
 * <b>not</b> agree: Python refuses a non-finite float outright and Gson is configured to emit one, so
 * the clause that keeps {@code Infinity} out of the store is enforced at the Python end alone and the
 * files this writer emits directly - the dump sections a manifest hashes byte for byte - are outside
 * it. That is recorded here as the case it is rather than left to be rediscovered.
 */
@DisplayName("The Java canonical writer emits the store-canonical form")
final class ParityJsonTest {

    /** A tree exercising each clause at once: nested objects, an array, and unsorted keys. */
    private static JsonObject sample() {
        JsonObject inner = new JsonObject();
        inner.addProperty("zulu", 1);
        inner.addProperty("alpha", 2);
        JsonArray rows = new JsonArray();
        rows.add("second");
        rows.add("first");
        JsonObject root = new JsonObject();
        root.add("rows", rows);
        root.add("inner", inner);
        root.addProperty("artifact", "sweep.entity");
        return root;
    }

    @Test
    @DisplayName("every object's keys are sorted recursively and no array is reordered")
    void keysSortAndArraysDoNot() {
        String text = ParityJson.text(sample());

        assertThat("the top-level keys, in the order they are emitted", text.indexOf("\"artifact\""),
            is(lessThanIndexOf(text, "\"inner\"")));
        assertThat("and the nested object's, which a shallow sort would leave as inserted",
            text.indexOf("\"alpha\""), is(lessThanIndexOf(text, "\"zulu\"")));
        assertThat("array order is semantic, so it is never touched: a map whose iteration order "
                + "carries meaning is emitted as an array of entries for exactly this reason",
            text.indexOf("second"), is(lessThanIndexOf(text, "first")));
    }

    @Test
    @DisplayName("the written file is LF, UTF-8 without a BOM, and ends in exactly one newline")
    void theFileFormIsTheOneTheStoreHolds(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("nested").resolve("artifact.json");
        ParityJson.write(file, sample());
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat("the parent directory is created rather than the write failing", text, is(not("")));
        assertThat("a CR anywhere, which is what a universal-newline writer produces on Windows and "
            + "what makes a digest answer differently on two checkouts of one commit",
            text.indexOf('\r'), is(-1));
        assertThat("a UTF-8 BOM, which no reader in this store strips on the way to a digest",
            bytes.length > 2 && bytes[0] == (byte) 0xEF, is(false));
        assertThat("exactly one trailing newline", text.endsWith("}\n") && !text.endsWith("}\n\n"),
            is(true));
        assertThat("and the text form is the file without it, so the two cannot part company",
            text, equalTo(ParityJson.text(sample()) + "\n"));
    }

    @Test
    @DisplayName("two-space pretty printing, and HTML escaping off")
    void theLayoutIsTwoSpacesAndNothingIsEscaped() {
        JsonObject root = new JsonObject();
        root.addProperty("reason", "a < b & c > d");
        String text = ParityJson.text(root);

        assertThat("the indent, read off the first member line", text.split("\n")[1],
            equalTo("  \"reason\": \"a < b & c > d\""));
        assertThat("HTML escaping would spell the angle brackets \\u003c and \\u003e, which is a "
            + "different byte sequence for the same reason text and a different digest",
            text.contains("\\u003"), is(false));
    }

    @Test
    @DisplayName("a captured float keeps its shortest round-trip spelling")
    void aFloatIsStoredAsItsOwnLiteralText() {
        assertThat("widening to a double first prints 0.4000000059604645 for the same float, and "
                + "reading that back invites a double rounding",
            ParityJson.float32(0.4f).getAsString(), equalTo("0.4"));
        assertThat("and a whole number keeps the decimal point that says it is a float",
            ParityJson.float32(30f).getAsString(), equalTo("30.0"));
    }

    @Test
    @DisplayName("a bit pattern is an uppercase 0x string of its low 32 bits")
    void aBitPatternIsHexAndNotADecimal() {
        assertThat("a CRC is a bit pattern rather than a quantity, and a negative int rendered as a "
                + "decimal is one sign-extension away from being wrong",
            ParityJson.bits(0xFFFFFFFFL).getAsString(), equalTo("0xFFFFFFFF"));
        assertThat("padded to eight digits, so two CRCs sort and grep alike",
            ParityJson.bits(0x2AL).getAsString(), equalTo("0x0000002A"));
    }

    @Test
    @DisplayName("a digest is lowercase hex over the file's normalized bytes")
    void aDigestIsTakenOverTheNormalizedForm(@TempDir Path directory) throws IOException {
        Path lf = directory.resolve("lf.json");
        Path crlf = directory.resolve("crlf.json");
        Files.writeString(lf, "{\n  \"a\": 1\n}\n", StandardCharsets.UTF_8);
        Files.writeString(crlf, "{\r\n  \"a\": 1\r\n}\r\n", StandardCharsets.UTF_8);

        assertThat("the two checkouts of one commit that git reports nothing about",
            ParityJson.sha256Normalized(crlf), equalTo(ParityJson.sha256Normalized(lf)));
        assertThat("lowercase hex, which is the one spelling every stored digest uses",
            ParityJson.sha256Normalized(lf), equalTo(ParityJson.sha256Normalized(lf).toLowerCase()));
    }

    @Test
    @DisplayName("this writer emits a non-finite float where the toolkit's writer refuses one")
    void theNonFiniteClauseIsEnforcedOnTheOtherSideAlone() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("delta", Double.POSITIVE_INFINITY);

        // Recorded rather than asserted away. JSON has no Infinity, and a failed subject is carried
        // as an explicit status field for exactly that reason - so the clause is real, and the
        // refusal that carries it lives at the other end. Everything self-captured is re-emitted
        // through the toolkit's writer on its way into the store, so the only bytes this one
        // contributes directly are the dump sections a manifest hashes, and their floor-5
        // determinism runs are what says the form they came out in is stable.
        assertThat("Gson is built here with serializeSpecialFloatingPointValues, so what this writer "
                + "produces is bare Infinity",
            ParityJson.text(root), equalTo("{\n  \"delta\": Infinity\n}"));
        assertThat("and the refusal really is at the capture, which is the whole reason the "
                + "asymmetry above is admissible rather than a hole. Read off the toolkit's writer, "
                + "because a sentence here saying so is not the mechanism",
            Files.readString(Path.of("parity/scripts/parity/norm.py")), containsString("allow_nan=False"));
    }

    /**
     * A matcher for one index being before another, so a failure prints both positions.
     *
     * @param text the rendered text
     * @param needle the substring the subject index must precede
     * @return the matcher
     */
    private static Matcher<Integer> lessThanIndexOf(String text, String needle) {
        return lessThan(text.indexOf(needle));
    }

}
