package lib.minecraft.renderer.sweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static lib.minecraft.renderer.fixture.TooltipFixtures.stubContext;
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
 * Coverage of {@link TooltipParitySweep} short of a client: the rows each configuration draws, the keys
 * and drawings they are compared under and the folder and drawing a row's comparison takes from them,
 * the resolution check that holds the renderer's chrome to the sprites the client drew with, the scaling
 * form both sides write, the roster's pack sections, and the pack run's handshake.
 */
@DisplayName("TooltipParitySweep resolution check and roster")
class TooltipParitySweepTest {

    private static final String SPRITE = "minecraft:tooltip/background";

    private static final String FILE = "minecraft:textures/gui/sprites/tooltip/background.png";

    private static final String TEXT_SHADER = "minecraft:shaders/core/rendertype_text.vsh";

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

    /**
     * Builds the handshake's {@code text_shader}.
     *
     * @param file the file the client compiles its text program from
     * @param packs the packs supplying it, lowest priority first
     * @return the client side
     */
    private static JsonObject clientText(String file, String... packs) {
        JsonObject client = new JsonObject();
        client.addProperty("file", file);
        JsonArray stack = new JsonArray();
        for (String pack : packs) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pack", pack);
            entry.addProperty("sha256", "a");
            stack.add(entry);
        }
        client.add("stack", stack);
        return client;
    }

    /**
     * Builds the renderer's reading of its text shader.
     *
     * @param state the state of the stack's palette
     * @param file the file it was read from, absent where none was read
     * @param pack the pack it was read from, absent where none was read
     * @return the renderer side
     */
    private static JsonObject renderedText(String state, String file, String pack) {
        JsonObject rendered = new JsonObject();
        rendered.addProperty("state", state);
        if (file != null) rendered.addProperty("file", file);
        if (pack != null) rendered.addProperty("pack", pack);
        return rendered;
    }

    @Test
    @DisplayName("a palette read from the client's text shader, from the pack on top of the client's stack, agrees")
    void textShaderOfTheClientsFileAndPackAgrees() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla", "file/p"),
            renderedText("present", TEXT_SHADER, "file/p")), is(empty()));
    }

    @Test
    @DisplayName("a palette read from another file than the client runs refuses, naming the client's file")
    void textShaderOfAnotherFileRefuses() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla", "file/p"),
                renderedText("present", "minecraft:shaders/core/text.vsh", "file/p")),
            contains(allOf(startsWith(TEXT_SHADER), containsString("text.vsh"))));
    }

    @Test
    @DisplayName("a palette read from a pack where the client runs vanilla's program refuses, naming the client's file")
    void textShaderOverAVanillaTopRefuses() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla"),
                renderedText("present", TEXT_SHADER, "file/p")),
            contains(allOf(startsWith(TEXT_SHADER), containsString("file/p"))));
    }

    @Test
    @DisplayName("no palette agrees where the client runs vanilla's program, and refuses where it runs a pack's")
    void absentPaletteAgreesOnlyOverAVanillaTop() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla"),
            renderedText("absent", null, null)), is(empty()));
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla", "file/p"),
                renderedText("absent", null, null)),
            contains(allOf(startsWith(TEXT_SHADER), containsString("reads no text shader"))));
    }

    @Test
    @DisplayName("a text shader the renderer could not read refuses, whichever pack the client runs it from")
    void emptyPaletteRefuses() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla"),
                renderedText("empty", null, null)),
            contains(allOf(startsWith(TEXT_SHADER), containsString("no colour swap"))));
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER, "vanilla", "file/p"),
                renderedText("empty", null, null)),
            contains(allOf(startsWith(TEXT_SHADER), containsString("no colour swap"))));
    }

    @Test
    @DisplayName("a text shader no pack supplies to the client refuses, naming the file")
    void textShaderNoPackSuppliesRefuses() {
        assertThat(TooltipParitySweep.textShaderMismatches(clientText(TEXT_SHADER), renderedText("absent", null, null)),
            contains(allOf(startsWith(TEXT_SHADER), containsString("no pack supplies"))));
    }

    /**
     * Builds the handshake's {@code shader_sources} for one file.
     *
     * @param file the shader file
     * @param stack the file's stack, lowest priority first
     * @return the client side
     */
    private static JsonObject clientShaders(String file, String stack) {
        return JsonParser.parseString("{\"" + file + "\": " + stack + "}").getAsJsonObject();
    }

    /**
     * Builds the renderer's resolution of one shader file.
     *
     * @param file the shader file
     * @param pack the pack supplying it
     * @param sha256 its bytes' digest
     * @return the renderer side
     */
    private static JsonObject renderedShaders(String file, String pack, String sha256) {
        return JsonParser.parseString("{\"" + file + "\": {\"pack\": \"" + pack + "\", \"sha256\": \"" + sha256 + "\"}}").getAsJsonObject();
    }

    /** The client side whose one shader file's top pack is the pack at digest {@code b}. */
    private static JsonObject shaderOverVanilla() {
        return clientShaders(TEXT_SHADER, "[{\"pack\": \"vanilla\", \"sha256\": \"a\"}, {\"pack\": \"file/p\", \"sha256\": \"b\"}]");
    }

    @Test
    @DisplayName("the renderer resolving every pack shader file from the client's top pack with its bytes agrees")
    void identicalShaderSourcesAgree() {
        assertThat(TooltipParitySweep.shaderMismatches(shaderOverVanilla(), renderedShaders(TEXT_SHADER, "file/p", "b")),
            is(empty()));
    }

    @Test
    @DisplayName("a shader file of other bytes, or from another pack, refuses, naming the file")
    void shaderOfOtherBytesOrPackRefuses() {
        assertThat(TooltipParitySweep.shaderMismatches(shaderOverVanilla(), renderedShaders(TEXT_SHADER, "file/p", "c")),
            contains(allOf(startsWith(TEXT_SHADER), containsString("bytes hash"))));
        assertThat(TooltipParitySweep.shaderMismatches(shaderOverVanilla(), renderedShaders(TEXT_SHADER, "file/q", "b")),
            contains(allOf(startsWith(TEXT_SHADER), containsString("takes it from"))));
    }

    @Test
    @DisplayName("a shader file the client takes from a pack and the renderer from none refuses, naming the file")
    void shaderTheRendererLacksRefuses() {
        assertThat(TooltipParitySweep.shaderMismatches(shaderOverVanilla(), new JsonObject()),
            contains(allOf(startsWith(TEXT_SHADER), containsString("from none"))));
    }

    @Test
    @DisplayName("a shader file the renderer takes from a pack and the client does not list refuses, naming the file")
    void shaderTheClientDoesNotListRefuses() {
        String other = "minecraft:shaders/include/swap.glsl";
        JsonObject both = renderedShaders(TEXT_SHADER, "file/p", "b");
        both.add(other, renderedShaders(other, "file/p", "d").get(other));

        assertThat(TooltipParitySweep.shaderMismatches(shaderOverVanilla(), both),
            contains(allOf(startsWith(other), containsString("lists no pack"))));
    }

    @Test
    @DisplayName("a shader file no pack supplied to the client refuses, naming the file")
    void emptyShaderStackRefuses() {
        assertThat(TooltipParitySweep.shaderMismatches(clientShaders(TEXT_SHADER, "[]"), new JsonObject()),
            contains(allOf(startsWith(TEXT_SHADER), containsString("no pack supplies"))));
    }

    @Test
    @DisplayName("a stretch writes its type alone")
    void stretchWritesItsTypeAlone() {
        JsonObject stretch = JsonParser.parseString("{\"type\": \"stretch\"}").getAsJsonObject();

        assertThat("the shape a sprite without a sidecar defaults to",
            TooltipParitySweep.scalingJson(new MCMeta.GuiScaling.Stretch()), equalTo(stretch));
    }

    @Test
    @DisplayName("a tile writes its type and its size")
    void tileWritesItsTypeAndSize() {
        assertThat(TooltipParitySweep.scalingJson(new MCMeta.GuiScaling.Tile(16, 8)),
            equalTo(JsonParser.parseString("{\"type\": \"tile\", \"width\": 16, \"height\": 8}").getAsJsonObject()));
    }

    @Test
    @DisplayName("a nine-slice writes its size, four border sides and its inner stretch")
    void nineSliceWritesEveryField() {
        MCMeta.GuiScaling nineSlice = new MCMeta.GuiScaling.NineSlice(100, 100,
            new MCMeta.GuiScaling.NineSlice.Border(9, 9, 9, 9), false);

        assertThat(TooltipParitySweep.scalingJson(nineSlice), equalTo(JsonParser.parseString(NINE_SLICE).getAsJsonObject()));
    }

    /**
     * Describes a subject as the row-set case compares it.
     *
     * @param subject the subject
     * @return its key, the style it names as {@code namespace:name} where it names one, and the name of
     *     the chrome it is drawn over
     */
    private static List<String> described(TooltipParitySweep.Subject subject) {
        List<String> described = new ArrayList<>();
        described.add(subject.key());
        subject.style().ifPresent(style -> described.add(style.namespace() + ":" + style.name()));
        described.add(subject.options().getChromeStyle().map(ChromeStyle::name).orElse("none"));
        return described;
    }

    @Test
    @DisplayName("the vanilla configuration draws every roster subject over the sprite box, then every unstyled one "
        + "over the code-drawn box, which draws no tooltip style")
    void vanillaDrawsEveryUnstyledSubjectOverTheCodeDrawnBoxAsWell() throws IOException {
        JsonObject roster = TooltipParitySweep.roster();
        List<JsonObject> entries = new ArrayList<>();
        roster.getAsJsonArray("subjects").forEach(element -> entries.add(element.getAsJsonObject()));

        assertThat("the roster has no styled subject, which would leave the code-drawn box's exclusion unexercised",
            entries.stream().anyMatch(entry -> entry.has("style")), is(true));

        List<List<String>> expected = new ArrayList<>();
        for (JsonObject entry : entries) {
            String key = entry.get("key").getAsString();
            expected.add(entry.has("style")
                ? List.of(key, entry.get("style").getAsString(), ChromeStyle.SPRITE.name())
                : List.of(key, ChromeStyle.SPRITE.name()));
        }
        for (JsonObject entry : entries)
            if (!entry.has("style")) expected.add(List.of(entry.get("key").getAsString(), ChromeStyle.PROCEDURAL.name()));

        assertThat(TooltipParitySweep.subjects(roster, "vanilla").stream().map(TooltipParitySweepTest::described).toList(),
            equalTo(expected));
    }

    @Test
    @DisplayName("a row over the code-drawn box is compared with the drawing its sprite twin is, under a key of its own")
    void codeDrawnRowsReadTheSpriteRowsDrawings() throws IOException {
        List<TooltipParitySweep.Subject> subjects = TooltipParitySweep.subjects(TooltipParitySweep.roster(), "vanilla");
        TooltipParitySweep.Subject oneLine = codeDrawn(subjects, "one_line");

        assertThat(List.of(oneLine.rowKey(false), oneLine.rowKey(true), oneLine.referenceStem(false), oneLine.referenceStem(true)),
            contains("one_line~chrome=procedural", "one_line_chrome~chrome=procedural", "one_line", "one_line_chrome"));

        Set<String> keys = new HashSet<>();
        List<String> repeatedKeys = new ArrayList<>();
        List<String> movedSpriteKeys = new ArrayList<>();
        Set<String> spriteDrawings = new HashSet<>();
        Set<String> codeDrawnDrawings = new HashSet<>();
        for (TooltipParitySweep.Subject subject : subjects) {
            for (boolean boxAlone : List.of(false, true)) {
                String key = subject.rowKey(boxAlone);
                String drawing = subject.referenceStem(boxAlone);
                if (!keys.add(key)) repeatedKeys.add(key);
                if (subject.options().getChromeStyle().equals(Optional.of(ChromeStyle.SPRITE))) {
                    spriteDrawings.add(drawing);
                    if (!key.equals(drawing)) movedSpriteKeys.add(key);
                } else
                    codeDrawnDrawings.add(drawing);
            }
        }
        codeDrawnDrawings.removeAll(spriteDrawings);

        assertThat("a row over the sprite box keyed other than its drawing, which re-keys a stored row", movedSpriteKeys, is(empty()));
        assertThat("two rows under one key, which write one folder and one report key", repeatedKeys, is(empty()));
        assertThat("a row over the code-drawn box reading a drawing no sprite row reads", codeDrawnDrawings, is(empty()));
    }

    @Test
    @DisplayName("a row over the code-drawn box writes its folder and reports under its own key, and measures the drawing "
        + "its sprite twin reads")
    void codeDrawnRowComparesUnderItsKeyAgainstItsTwinsDrawing(@TempDir Path dir) throws IOException {
        TooltipParitySweep.Subject oneLine = codeDrawn(TooltipParitySweep.subjects(TooltipParitySweep.roster(), "vanilla"), "one_line");
        Path references = Files.createDirectories(dir.resolve("references"));
        Path output = dir.resolve("output");
        // A sprite rect the client's margin fits inside with content to spare, the client's drawing a
        // 7 x 5 block within it, written only under the stem of the drawing the sprite twin reads.
        int scale = MinecraftFont.MC_PIXEL_SCALE;
        BufferedImage drawing = new BufferedImage(40 * scale, 30 * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 7; y++) {
            for (int x = 3; x < 10; x++)
                drawing.setRGB(x, y, 0xFF102030);
        }
        ImageIO.write(drawing, "PNG", references.resolve("one_line_chrome.png").toFile());

        TooltipParitySweep.Row row = TooltipParitySweep.renderAndCompare(
            new TooltipParitySweep.Configuration("vanilla", references, output, ClientOptions.defaults(), List.of(oneLine), Optional.empty()),
            oneLine, true, new TextRenderer(), stubContext(Map.of(), Map.of()));

        // The client's content is the rect less its 12 a side, 16 x 6; the code-drawn box pads it by the
        // request's padding a side, where the sprite box would span the whole 40 x 30 rect.
        int padding = oneLine.options().getPadding();
        assertThat("the row's report key", row.subject(), equalTo("one_line_chrome~chrome=procedural"));
        assertThat("the client's drawing to its first pixel", List.of(row.vanillaW(), row.vanillaH()), contains(7, 5));
        assertThat("the box alone drawn in the code-drawn chrome the subject names, round the client's content",
            List.of(row.javaW(), row.javaH()), contains((16 + 2 * padding) * scale, (6 + 2 * padding) * scale));
        try (Stream<Path> folders = Files.list(output)) {
            assertThat("the row's folder", folders.map(folder -> folder.getFileName().toString()).toList(),
                contains("one_line_chrome~chrome=procedural"));
        }
    }

    /**
     * Finds the vanilla configuration's subject drawn over the code-drawn box under a roster key.
     *
     * @param subjects the vanilla configuration's subjects
     * @param key the roster key
     * @return the subject
     */
    private static TooltipParitySweep.Subject codeDrawn(List<TooltipParitySweep.Subject> subjects, String key) {
        return subjects.stream()
            .filter(subject -> subject.key().equals(key) && subject.options().getChromeStyle().equals(Optional.of(ChromeStyle.PROCEDURAL)))
            .findFirst()
            .orElseThrow();
    }

    @Test
    @DisplayName("every pack section of the tracked roster draws unstyled subjects of the roster by key, "
        + "each in the style its entry names, over the sprite box")
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
            List<TooltipParitySweep.Subject> subjects = TooltipParitySweep.subjects(roster, label);
            List<List<String>> answered = subjects.stream()
                .map(subject -> subject.style()
                    .map(style -> List.of(subject.key(), style.namespace() + ":" + style.name()))
                    .orElse(List.of(subject.key())))
                .toList();

            assertThat(label, answered, is(not(empty())));
            assertThat(label + ": the key and style of each subject, in the section's order", answered, equalTo(listed));
            assertThat(label + ": every subject over the sprite box - the code-drawn box reads nothing from a pack",
                subjects.stream().map(subject -> subject.options().getChromeStyle()).distinct().toList(),
                contains(Optional.of(ChromeStyle.SPRITE)));
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
                "{\"format\": 2, " + directory + ", \"requested\": [\"file/p\"]}").getAsJsonObject()).size(), is(1));
        assertThrows(IllegalStateException.class, () -> TooltipParitySweep.packFiles(JsonParser.parseString(
            "{\"format\": 1, " + directory + ", \"requested\": [\"file/p\"]}").getAsJsonObject()));
        assertThrows(IllegalStateException.class, () -> TooltipParitySweep.packFiles(JsonParser.parseString(
            "{\"format\": 2, " + directory + ", \"requested\": [\"p\"]}").getAsJsonObject()));
    }

}
