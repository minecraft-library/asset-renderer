package lib.minecraft.renderer.sweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link TooltipParitySweep}'s pure halves: the resolution check that holds the
 * renderer's chrome to the sprites the client drew with, the scaling form both sides write, the
 * roster's pack sections, and the pack run's handshake.
 */
@DisplayName("TooltipParitySweep resolution check and roster")
class TooltipParitySweepTest {

    private static final String SPRITE = "minecraft:tooltip/background";

    private static final String FILE = "minecraft:textures/gui/sprites/tooltip/background.png";

    private static final String NINE_SLICE =
        "{\"type\": \"nine_slice\", \"width\": 100, \"height\": 100, "
            + "\"border\": {\"left\": 9, \"top\": 9, \"right\": 9, \"bottom\": 9}, \"stretch_inner\": false}";

    /** The lowest printable ASCII codepoint, the space. */
    private static final int FIRST_PRINTABLE = 0x20;

    /** The highest printable ASCII codepoint, the tilde. */
    private static final int LAST_PRINTABLE = 0x7E;

    /**
     * Builds the handshake's {@code chrome_sources} for the one background sprite.
     *
     * @param stack the sprite file's stack, lowest priority first
     * @param scaling the sprite's scaling
     * @return the client side
     */
    private static JsonObject client(String stack, String scaling) {
        return JsonParser.parseString("{\"" + SPRITE + "\": {\"file\": \"" + FILE + "\", \"stack\": " + stack
            + ", \"scaling\": " + scaling + "}}").getAsJsonObject();
    }

    /**
     * Builds the renderer's resolution of one file.
     *
     * @param file the resolved file
     * @param pack the pack supplying it
     * @param sha256 its bytes' digest
     * @param scaling its scaling
     * @return the renderer side
     */
    private static JsonObject rendered(String file, String pack, String sha256, String scaling) {
        return JsonParser.parseString("{\"" + file + "\": {\"pack\": \"" + pack + "\", \"sha256\": \"" + sha256
            + "\", \"scaling\": " + scaling + "}}").getAsJsonObject();
    }

    /** The client side whose top pack is the pack at digest {@code b}. */
    private static JsonObject packOverVanilla() {
        return client("[{\"pack\": \"vanilla\", \"sha256\": \"a\"}, {\"pack\": \"file/p\", \"sha256\": \"b\"}]", NINE_SLICE);
    }

    @Test
    @DisplayName("the renderer resolving the client's top pack, bytes and scaling agrees")
    void identicalSidesAgree() {
        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), rendered(FILE, "file/p", "b", NINE_SLICE)),
            is(empty()));
    }

    @Test
    @DisplayName("a different top pack refuses, naming the file")
    void differentPackRefuses() {
        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), rendered(FILE, "vanilla", "b", NINE_SLICE)),
            contains(allOf(startsWith(FILE), containsString("takes it from"))));
    }

    @Test
    @DisplayName("different bytes refuse, naming the file")
    void differentBytesRefuse() {
        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), rendered(FILE, "file/p", "c", NINE_SLICE)),
            contains(allOf(startsWith(FILE), containsString("bytes hash"))));
    }

    @Test
    @DisplayName("a different border, or a different inner stretch, refuses, naming the file")
    void differentScalingRefuses() {
        String border = NINE_SLICE.replace("\"left\": 9", "\"left\": 24");
        String stretch = NINE_SLICE.replace("\"stretch_inner\": false", "\"stretch_inner\": true");

        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), rendered(FILE, "file/p", "b", border)),
            contains(allOf(startsWith(FILE), containsString("scales it as"))));
        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), rendered(FILE, "file/p", "b", stretch)),
            contains(allOf(startsWith(FILE), containsString("scales it as"))));
    }

    @Test
    @DisplayName("a sprite the client draws and the renderer does not resolve refuses, naming the file")
    void unresolvedSpriteRefuses() {
        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), new JsonObject()),
            contains(allOf(startsWith(FILE), containsString("does not resolve"))));
    }

    @Test
    @DisplayName("a sprite no pack supplied to the client refuses, naming the file")
    void emptyClientStackRefuses() {
        assertThat(TooltipParitySweep.resolutionMismatches(client("[]", "{\"type\": \"stretch\"}"),
                rendered(FILE, "vanilla", "a", "{\"type\": \"stretch\"}")),
            contains(allOf(startsWith(FILE), containsString("no pack supplies"))));
    }

    @Test
    @DisplayName("a sprite the renderer resolves and the client's tooltip does not draw refuses, naming the file")
    void undrawnSpriteRefuses() {
        String other = "minecraft:textures/gui/sprites/tooltip/frame.png";
        JsonObject both = rendered(FILE, "file/p", "b", NINE_SLICE);
        both.add(other, rendered(other, "vanilla", "d", NINE_SLICE).get(other));

        assertThat(TooltipParitySweep.resolutionMismatches(packOverVanilla(), both),
            contains(allOf(startsWith(other), containsString("does not draw"))));
    }

    @Test
    @DisplayName("a stretch writes its type alone, whatever size it carries")
    void stretchWritesItsTypeAlone() {
        JsonObject stretch = JsonParser.parseString("{\"type\": \"stretch\"}").getAsJsonObject();
        MCMeta.GuiScaling.Border none = new MCMeta.GuiScaling.Border(0, 0, 0, 0);

        assertThat("the shape a sprite without a sidecar defaults to, its size unspecified",
            TooltipParitySweep.scalingJson(new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.STRETCH, -1, -1, none, false)),
            equalTo(stretch));
        assertThat("a stretch sidecar naming a size the client's codec does not read",
            TooltipParitySweep.scalingJson(new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.STRETCH, 16, 16, none, false)),
            equalTo(stretch));
    }

    @Test
    @DisplayName("a nine-slice writes its size, four border sides and its inner stretch")
    void nineSliceWritesEveryField() {
        MCMeta.GuiScaling nineSlice = new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.NINE_SLICE, 100, 100,
            new MCMeta.GuiScaling.Border(9, 9, 9, 9), false);

        assertThat(TooltipParitySweep.scalingJson(nineSlice), equalTo(JsonParser.parseString(NINE_SLICE).getAsJsonObject()));
    }

    @Test
    @DisplayName("every pack section of the tracked roster draws unstyled subjects of the roster by key, "
        + "each in the style its entry names")
    void everyPackSectionResolves() throws IOException {
        JsonObject roster = TooltipParitySweep.roster();
        List<String> labels = List.copyOf(roster.getAsJsonObject("packs").keySet());

        assertThat("the roster has no pack section, which would leave this case vacuous", labels, is(not(empty())));
        for (String label : labels) {
            List<List<String>> listed = new ArrayList<>();
            for (JsonElement element : roster.getAsJsonObject("packs").getAsJsonObject(label).getAsJsonArray("subjects")) {
                JsonObject entry = element.getAsJsonObject();
                String key = entry.get("key").getAsString();
                listed.add(entry.has("style") ? List.of(key, entry.get("style").getAsString()) : List.of(key));
            }
            List<List<String>> answered = TooltipParitySweep.subjects(roster, label).stream()
                .map(subject -> subject.style()
                    .map(style -> List.of(subject.key(), style.namespace() + ":" + style.name()))
                    .orElse(List.of(subject.key())))
                .toList();

            assertThat(label, answered, is(not(empty())));
            assertThat(label + ": the key and style of each subject, in the section's order", answered, equalTo(listed));
        }
    }

    @Test
    @DisplayName("every line a pack section reaches is printable ASCII - the pack's font adds icon glyphs "
        + "the client would draw and the renderer draws no font from a pack")
    void everyPackLineIsPrintableAscii() throws IOException {
        JsonObject roster = TooltipParitySweep.roster();
        List<String> outside = new ArrayList<>();
        int read = 0;
        for (String label : roster.getAsJsonObject("packs").keySet()) {
            for (TooltipParitySweep.Subject subject : TooltipParitySweep.subjects(roster, label)) {
                for (LineSegment line : subject.options().getLines()) {
                    for (TextSegment segment : line.getSegments()) {
                        String text = segment.getText();
                        read += text.length();
                        text.codePoints()
                            .filter(codepoint -> codepoint < FIRST_PRINTABLE || codepoint > LAST_PRINTABLE)
                            .forEach(codepoint -> outside.add(String.format("%s %s U+%04X", label, subject.key(), codepoint)));
                    }
                }
            }
        }

        assertThat("no pack line carries any text, which would leave this case vacuous", read, is(not(0)));
        assertThat(outside, is(empty()));
    }

    @Test
    @DisplayName("a configuration the roster has no section for is refused")
    void unknownConfigurationRefuses() throws IOException {
        JsonObject roster = TooltipParitySweep.roster();

        assertThrows(IllegalArgumentException.class, () -> TooltipParitySweep.subjects(roster, "no-such-pack"));
    }

    @Test
    @DisplayName("a handshake of another format, and a requested pack that is no folder-source id, are refused")
    void handshakeRefusals() {
        String directory = "\"pack_directory\": \"packs\"";

        assertThat(TooltipParitySweep.packFiles(JsonParser.parseString(
                "{\"format\": 1, " + directory + ", \"requested\": [\"file/p\"]}").getAsJsonObject()).size(), is(1));
        assertThrows(IllegalStateException.class, () -> TooltipParitySweep.packFiles(JsonParser.parseString(
            "{\"format\": 2, " + directory + ", \"requested\": [\"file/p\"]}").getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> TooltipParitySweep.packFiles(JsonParser.parseString(
            "{\"format\": 1, " + directory + ", \"requested\": [\"p\"]}").getAsJsonObject()));
    }

}
