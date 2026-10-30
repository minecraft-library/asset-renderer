package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link PackAcquisition} over synthetic directory packs: renderer-target overlay
 * activation (matching the game format, not the pack's own), namespace discovery across active roots,
 * capability detection, and the text palette read from the text shader of the pack drawing the
 * stack's text. Acquisition is virtual - a directory source is served in place through its
 * {@link PackContainer.Directory}, with no extraction or cache copy.
 */
@DisplayName("PackAcquisition over synthetic directory packs")
class PackAcquisitionTest {

    @Test
    @DisplayName("overlays activate against the renderer target; namespaces and capabilities are detected")
    void acquireDirectoryPack(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("mypack");
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84},\"overlays\":{\"entries\":["
            + "{\"directory\":\"ov_hi\",\"formats\":[84,84]},"
            + "{\"directory\":\"ov_lo\",\"formats\":[1,1]},"
            + "{\"directory\":\"ov_gone\",\"formats\":[84,84]}]}}");
        write(user.resolve("assets/minecraft/optifine/color.properties"), "redstone.0=0x00ff00");
        write(user.resolve("assets/testns/textures/item/x.png"), "png");
        write(user.resolve("ov_hi/assets/minecraft/textures/block/stone.png"), "png"); // active overlay
        write(user.resolve("ov_lo/assets/minecraft/textures/block/dirt.png"), "png");   // format 1 -> inactive
        // ov_gone is declared but never created on disk -> inactive

        Path cache = dir.resolve("cache");
        ClientOptions options = ClientOptions.builder()
            .cacheRoot(cache.toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));

        assertThat(stack.size(), is(2));
        assertThat(stack.vanilla().id(), is(PackId.VANILLA));

        ResourcePack mypack = stack.byId(new PackId("mypack")).orElseThrow();
        assertThat("only the format-matching, on-disk overlay activates",
            mypack.roots(), contains(PackRoot.BASE, PackRoot.overlay("ov_hi")));
        assertThat(mypack.namespaces(), containsInAnyOrder("minecraft", "testns"));
        assertThat(mypack.capabilities(), containsInAnyOrder(PackCapability.VANILLA_CORE, PackCapability.OPTIFINE_RULES));

        // directory source is served in place (virtual - no extraction, no cache copy)
        assertThat(mypack.container(), is(instanceOf(PackContainer.Directory.class)));
        assertThat(((PackContainer.Directory) mypack.container()).root(), is(user));
    }

    @Test
    @DisplayName("every path signal is detected from the one listing, and a pack with none detects none")
    void everyPathSignalFromOneListing(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path all = dir.resolve("allsignals");
        write(all.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(all.resolve("config.catharsis.json"), "[]");
        write(all.resolve("assets/minecraft/mcpatcher/color.properties"), "redstone.0=0x00ff00");
        write(all.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path none = dir.resolve("nosignals");
        write(none.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(none.resolve("credits.txt"), "nothing a capability reads");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(all.toFile(), none.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));

        assertThat(stack.byId(new PackId("allsignals")).orElseThrow().capabilities(),
            containsInAnyOrder(PackCapability.VANILLA_CORE, PackCapability.OPTIFINE_RULES, PackCapability.CATHARSIS_CONVENTIONS));
        assertThat(stack.byId(new PackId("nosignals")).orElseThrow().capabilities(), is(empty()));
    }

    @Test
    @DisplayName("Catharsis fabric:overlays activate against config.catharsis.json defaults for a CATHARSIS pack")
    void acquireCatharsisPack(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"block.ore\",\"value\":\"on\"}},"
            + "{\"directory\":\"theme_dark\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"theme.dark\",\"value\":\"on\"}},"
            + "{\"directory\":\"never_built\",\"condition\":{\"condition\":\"catharsis:config\",\"pack\":\"catpack\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        // config.catharsis.json: block.ore on by default, theme.dark off -> only block_ore activates.
        write(user.resolve("config.catharsis.json"), "[{\"type\":\"tab\",\"elements\":["
            + "{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true},"
            + "{\"type\":\"boolean\",\"id\":\"theme.dark\",\"default\":false}]}]");
        write(user.resolve("assets/skyblock/items/adaptive_boots.json"), "{}"); // a CATHARSIS signal + a discoverable item def
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");  // condition holds + on disk -> active
        write(user.resolve("theme_dark/assets/minecraft/textures/block/dirt.png"), "png");  // condition fails -> inactive
        // never_built: condition holds but the directory is not on disk -> inactive

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();

        assertThat(catpack.capabilities(), hasItem(PackCapability.CATHARSIS_CONVENTIONS));
        assertThat("only the config-active, on-disk Catharsis overlay stacks over the base root",
            catpack.roots(), contains(PackRoot.BASE, PackRoot.overlay("block_ore")));
    }

    @Test
    @DisplayName("a Catharsis pack detected only via the mcmeta catharsis:pack section activates overlays from its mcmeta config")
    void catharsisDetectedViaMcmetaOnly(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // No config.catharsis.json and no assets/skyblock/items: both detection AND the config defaults
        // must come from the mcmeta (catharsis:pack/v1 section + fabric:overlays catharsis condition).
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},"
            + "\"catharsis:pack/v1\":{\"config\":[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]},"
            + "\"fabric:overlays\":{\"entries\":[{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla));
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("detected via the mcmeta signal (no path signal present)", catpack.capabilities(), hasItem(PackCapability.CATHARSIS_CONVENTIONS));
        assertThat("overlay activated from the mcmeta catharsis:pack/v1.config fallback",
            catpack.roots(), contains(PackRoot.BASE, PackRoot.overlay("block_ore")));
    }

    @Test
    @DisplayName("a syntactically-malformed config.catharsis.json degrades to no overlays without breaking acquisition")
    void catharsisMalformedConfigDegrades(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // Valid pack.mcmeta + fabric:overlays (a catharsis:* condition is itself a detection signal),
        // but the config.catharsis.json is garbage - the overlay evaluation must degrade, never throw.
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"block_ore\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("config.catharsis.json"), "{ this is : not, valid json ]");
        write(user.resolve("block_ore/assets/minecraft/textures/block/stone.png"), "png");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla)); // must not throw
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("mcmeta fabric:overlays catharsis condition is a detection signal", catpack.capabilities(), hasItem(PackCapability.CATHARSIS_CONVENTIONS));
        assertThat("no config defaults -> the condition fails -> no overlays activate", catpack.roots(), contains(PackRoot.BASE));
    }

    @Test
    @DisplayName("an illegal overlay directory whose condition holds degrades without breaking acquisition")
    void catharsisIllegalOverlayDirectoryDegrades(@TempDir Path dir) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");

        Path user = dir.resolve("catpack");
        // The condition holds (block.ore default on), but the directory carries a NUL char, so
        // packRoot.resolve(directory) throws InvalidPathException - acquisition must degrade, not abort.
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":69,\"max_format\":255},\"fabric:overlays\":{\"entries\":["
            + "{\"directory\":\"\\u0000bad\",\"condition\":{\"condition\":\"catharsis:config\",\"id\":\"block.ore\",\"value\":\"on\"}}]}}");
        write(user.resolve("config.catharsis.json"), "[{\"type\":\"boolean\",\"id\":\"block.ore\",\"default\":true}]");
        write(user.resolve("assets/skyblock/items/foo.json"), "{}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(user.toFile())))
            .build();
        PackStack stack = PackAcquisition.acquire(new ClientAssets(options, vanilla)); // must not throw
        ResourcePack catpack = stack.byId(new PackId("catpack")).orElseThrow();
        assertThat("illegal overlay directory is skipped, base root survives", catpack.roots(), contains(PackRoot.BASE));
    }

    /** The 26.1 text program's file, under the {@code minecraft} namespace. */
    private static final String PROGRAM_FILE = "shaders/core/rendertype_text.vsh";

    /** The newer client's text program's file, under the {@code minecraft} namespace. */
    private static final String NEWER_PROGRAM_FILE = "shaders/core/text.vsh";

    /** The swaps {@link #SWAP} states - two colours and their shadows, each shadow the swap quartered per channel. */
    private static final Map<Integer, Integer> SWAPS = Map.of(0xAA0000, 0xD13228, 0x2A0000, 0x340C0A, 0xAAAAAA, 0xA8BFD2, 0x2A2A2A, 0x2A2F34);

    /** A text program of the swap shape: vanilla's {@code main} with the swapped colour declared, every {@code vertexColor} reading it. */
    private static final String PROGRAM = """
        #version 330

        #moj_import <swap.glsl>

        #if !defined(IS_GUI)
        #moj_import <minecraft:fog.glsl>
        #moj_import <minecraft:sample_lightmap.glsl>
        #endif

        in vec3 Position;
        in vec4 Color;
        in vec2 UV0;
        in ivec2 UV2;

        uniform sampler2D Sampler2;

        out float sphericalVertexDistance;
        out vec4 vertexColor;
        out vec2 texCoord0;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            vec4 swapped = swapColor(Color);

        #if !defined(IS_GUI)
            sphericalVertexDistance = fog_spherical_distance(Position);
            vertexColor = swapped * sample_lightmap(Sampler2, UV2);
        #else
            vertexColor = swapped;
        #endif
            texCoord0 = UV0;
        }
        """;

    /** The include {@link #PROGRAM} imports: the key packing, both rebuilding helpers and the switch. */
    private static final String SWAP = """
        // Keys a colour by its red, green and blue.
        int keyOf(vec3 rgb) {
            int r = int(rgb.r * 255.0);
            int g = int(rgb.g * 255.0);
            int b = int(rgb.b * 255.0);
            return (r << 16) + (g << 8) + (b);
        }

        vec4 rebuilt(int rgb, float alpha) {
            return vec4((rgb >> 16 & 0xFF) / 255.0, (rgb >> 8 & 0xFF) / 255.0, (rgb & 0xFF) / 255.0, alpha);
        }

        /* The shadow form, each channel quartered. */
        vec4 rebuiltDark(int rgb, float alpha) {
            return vec4(((rgb >> 16 & 0xFF) / 4) / 255.0, ((rgb >> 8 & 0xFF) / 4) / 255.0, ((rgb & 0xFF) / 4) / 255.0, alpha);
        }

        vec4 swapColor(vec4 color) {
            int key = keyOf(color.rgb);
            switch (key) {
                case 0xAA0000: return rebuilt(0xD13228, color.a);
                case 0x2A0000: return rebuiltDark(0xD13228, color.a);
                case 0xAAAAAA: return rebuilt(0xA8BFD2, color.a);
                case 0x2A2A2A: return rebuiltDark(0xA8BFD2, color.a);
                default: return color;
            }
        }
        """;

    /** Vanilla's own text program, which swaps nothing. */
    private static final String VANILLA_PROGRAM = """
        #version 330

        #moj_import <minecraft:fog.glsl>
        #moj_import <minecraft:sample_lightmap.glsl>

        in vec3 Position;
        in vec4 Color;
        in vec2 UV0;
        in ivec2 UV2;

        uniform sampler2D Sampler2;

        out float sphericalVertexDistance;
        out vec4 vertexColor;
        out vec2 texCoord0;

        void main() {
            gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

            sphericalVertexDistance = fog_spherical_distance(Position);
            vertexColor = Color * sample_lightmap(Sampler2, UV2);
            texCoord0 = UV0;
        }
        """;

    @Test
    @DisplayName("a text shader of the swap shape is read into the table its switch states, each shadow key the swap quartered per channel")
    void swapShapeIsRead(@TempDir Path dir) throws IOException {
        Path user = shaderPack(dir, "mypack", PROGRAM_FILE, PROGRAM, SWAP);
        TextPalette palette = acquire(dir, user).textPalette().get();

        assertThat("the user pack's table alone - the synthetic vanilla's own swap-shaped program is never read",
            Map.copyOf(palette.swaps()), is(equalTo(SWAPS)));
        assertThat(palette.id(), is(new ResourceId("minecraft", PROGRAM_FILE)));
        assertThat(palette.pack(), is(new PackId("mypack")));
    }

    @Test
    @DisplayName("a pack shipping only the newer program is read from it")
    void newerProgramIsRead(@TempDir Path dir) throws IOException {
        Path user = shaderPack(dir, "mypack", NEWER_PROGRAM_FILE, PROGRAM, SWAP);
        TextPalette palette = acquire(dir, user).textPalette().get();

        assertThat(palette.id(), is(new ResourceId("minecraft", NEWER_PROGRAM_FILE)));
        assertThat(Map.copyOf(palette.swaps()), is(equalTo(SWAPS)));
    }

    @Test
    @DisplayName("the 26.1 program wins in a pack shipping both, and a lower pack's 26.1 program over a higher pack's newer one")
    void the261ProgramWins(@TempDir Path dir) throws IOException {
        String otherProgram = PROGRAM.replace("<swap.glsl>", "<other_swap.glsl>");
        String otherSwap = SWAP.replace("0xA8BFD2", "0x123456");

        Path both = shaderPack(dir, "both", PROGRAM_FILE, PROGRAM, SWAP);
        write(both.resolve("assets/minecraft/" + NEWER_PROGRAM_FILE), otherProgram);
        write(both.resolve("assets/minecraft/shaders/include/other_swap.glsl"), otherSwap);
        TextPalette shippingBoth = acquire(dir.resolve("one"), both).textPalette().get();
        assertThat(shippingBoth.id(), is(new ResourceId("minecraft", PROGRAM_FILE)));
        assertThat(Map.copyOf(shippingBoth.swaps()), is(equalTo(SWAPS)));

        Path lower = shaderPack(dir, "lower", PROGRAM_FILE, PROGRAM, SWAP);
        Path higher = dir.resolve("higher");
        write(higher.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(higher.resolve("assets/minecraft/" + NEWER_PROGRAM_FILE), otherProgram);
        write(higher.resolve("assets/minecraft/shaders/include/other_swap.glsl"), otherSwap);
        TextPalette acrossPacks = acquire(dir.resolve("two"), lower, higher).textPalette().get();
        assertThat(acrossPacks.id(), is(new ResourceId("minecraft", PROGRAM_FILE)));
        assertThat(acrossPacks.pack(), is(new PackId("lower")));
    }

    @Test
    @DisplayName("a quoted import is read from the program's own directory")
    void quotedImportIsReadBesideTheProgram(@TempDir Path dir) throws IOException {
        Path user = shaderPack(dir, "mypack", PROGRAM_FILE, PROGRAM.replace("#moj_import <swap.glsl>", "#moj_import \"swap.glsl\""),
            SWAP.replace("0xA8BFD2", "0x123456"));
        write(user.resolve("assets/minecraft/shaders/core/swap.glsl"), SWAP);
        Possible<TextPalette> palette = acquire(dir, user).textPalette();

        assertThat(palette.getState(), is(Possible.State.PRESENT));
        assertThat("the include beside the program is read, and not the one of its name under shaders/include/",
            Map.copyOf(palette.get().swaps()), is(equalTo(SWAPS)));
    }

    @Test
    @DisplayName("a file imported twice, directly and through another include, is imported once")
    void repeatImportImportsNothing(@TempDir Path dir) throws IOException {
        Path user = shaderPack(dir, "mypack", PROGRAM_FILE, PROGRAM.replace("#moj_import <swap.glsl>", "#moj_import <swap.glsl>\n#moj_import <again.glsl>"), SWAP);
        write(user.resolve("assets/minecraft/shaders/include/again.glsl"), "#moj_import <swap.glsl>\n");
        Possible<TextPalette> palette = acquire(dir, user).textPalette();

        assertThat("a second import would define every function of the swap twice", palette.getState(), is(Possible.State.PRESENT));
        assertThat(Map.copyOf(palette.get().swaps()), is(equalTo(SWAPS)));
    }

    @Test
    @DisplayName("a text shader of another shape answers empty and says so once")
    void anotherShapeIsReported(@TempDir Path dir) throws IOException {
        Map<String, Path> refused = new LinkedHashMap<>();
        refused.put("a case returning another colour",
            shaderPack(dir, "constant", PROGRAM_FILE, PROGRAM, SWAP.replace("case 0xAA0000: return rebuilt(0xD13228, color.a);", "case 0xAA0000: return vec4(1.0);")));
        refused.put("a shadow helper dividing by 3", shaderPack(dir, "thirds", PROGRAM_FILE, PROGRAM, SWAP.replace("/ 4)", "/ 3)")));
        refused.put("an added statement in main",
            shaderPack(dir, "added", PROGRAM_FILE, PROGRAM.replace("    texCoord0 = UV0;\n", "    texCoord0 = UV0;\n    gl_PointSize = 1.0;\n"), SWAP));
        refused.put("the swap call under a preprocessor condition",
            shaderPack(dir, "conditional", PROGRAM_FILE, PROGRAM.replace("    vec4 swapped = swapColor(Color);\n", "#ifdef IS_GUI\n    vec4 swapped = swapColor(Color);\n#endif\n"), SWAP));
        Path fragment = shaderPack(dir, "fragment", PROGRAM_FILE, PROGRAM, SWAP);
        write(fragment.resolve("assets/minecraft/shaders/core/rendertype_text.fsh"), "#version 330\n\nvoid main() {\n}\n");
        refused.put("a pack-shipped fragment program", fragment);
        refused.put("an import no pack ships",
            shaderPack(dir, "unresolved", PROGRAM_FILE, PROGRAM.replace("#moj_import <swap.glsl>", "#moj_import <swap.glsl>\n#moj_import <missing.glsl>"), SWAP));
        refused.put("a key packing the channels in another order",
            shaderPack(dir, "selector", PROGRAM_FILE, PROGRAM, SWAP.replace("return (r << 16) + (g << 8) + (b);", "return (b << 16) + (g << 8) + (r);")));
        refused.put("two cases for one key", shaderPack(dir, "duplicate", PROGRAM_FILE, PROGRAM, SWAP.replace("case 0xAAAAAA:", "case 0xAA0000:")));
        refused.put("a main naming the swapped colour before it declares it",
            shaderPack(dir, "early", PROGRAM_FILE, PROGRAM.replace("    vec4 swapped = swapColor(Color);\n", "    vertexColor = swapped;\n    vec4 swapped = swapColor(Color);\n"), SWAP));
        refused.put("a main declaring the swapped colour whose vertexColor still reads Color",
            shaderPack(dir, "unswapped", PROGRAM_FILE, PROGRAM.replace("    vertexColor = swapped;\n", "    vertexColor = Color;\n"), SWAP));
        refused.put("a helper defined under a preprocessor condition",
            shaderPack(dir, "conditionalhelper", PROGRAM_FILE, PROGRAM, SWAP.replace("/* The shadow form, each channel quartered. */\n", "#ifdef IS_GUI\n")
                .replace("vec4 swapColor(vec4 color) {", "#endif\n\nvec4 swapColor(vec4 color) {")));
        refused.put("a directive beyond #version and the conditionals",
            shaderPack(dir, "define", PROGRAM_FILE, PROGRAM.replace("#version 330\n", "#version 330\n#define Color vec4(1.0)\n"), SWAP));

        for (Map.Entry<String, Path> shape : refused.entrySet()) {
            Path pack = shape.getValue();
            PrintStream original = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            PackStack stack;
            try {
                stack = acquire(dir.resolve("stack-" + pack.getFileName()), pack);
            } finally {
                System.setErr(original);
            }
            List<String> lines = captured.toString(StandardCharsets.UTF_8).lines().toList();

            assertThat(shape.getKey(), stack.textPalette().getState(), is(Possible.State.EMPTY));
            assertThat(shape.getKey() + " is reported once", lines.size(), is(1));
            assertThat(shape.getKey() + " is reported naming the pack and the file", lines.getFirst(),
                allOf(containsString("'" + pack.getFileName() + "'"), containsString("minecraft:" + PROGRAM_FILE)));
        }
    }

    @Test
    @DisplayName("a stack whose packs ship no text shader answers absent, vanilla's own never read")
    void noTextShaderIsAbsent(@TempDir Path dir) throws IOException {
        Path user = dir.resolve("mypack");
        write(user.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(user.resolve("assets/minecraft/textures/block/stone.png"), "png");

        assertThat(acquire(dir, user).textPalette().getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("a pack shipping vanilla's own text program reads as a table of no swaps")
    void vanillasOwnProgramSwapsNothing(@TempDir Path dir) throws IOException {
        Path user = shaderPack(dir, "mypack", PROGRAM_FILE, VANILLA_PROGRAM, SWAP);
        Possible<TextPalette> palette = acquire(dir, user).textPalette();

        assertThat(palette.getState(), is(Possible.State.PRESENT));
        assertThat(palette.get().swaps().isEmpty(), is(true));
    }

    /**
     * Writes a pack shipping a text program under one of its names, and the swap include it imports.
     *
     * @param dir the directory the pack is written under
     * @param name the pack's directory name, which its id derives from
     * @param programFile the program's file under {@code assets/minecraft/}
     * @param program the program's body
     * @param swap the body of the include {@code swap.glsl}
     * @return the pack's directory
     * @throws IOException if a file cannot be written
     */
    private static Path shaderPack(Path dir, String name, String programFile, String program, String swap) throws IOException {
        Path pack = dir.resolve(name);
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(pack.resolve("assets/minecraft/" + programFile), program);
        write(pack.resolve("assets/minecraft/shaders/include/swap.glsl"), swap);
        return pack;
    }

    /**
     * Acquires a stack of packs over a synthetic vanilla, which ships the two includes the programs
     * import and a swap-shaped text program of its own, with other values, that the stack must never
     * read.
     *
     * @param dir the directory the vanilla pack and the cache are written under
     * @param packs the user packs, lowest priority first
     * @return the acquired stack
     * @throws IOException if a file cannot be written
     */
    private static PackStack acquire(Path dir, Path... packs) throws IOException {
        Path vanilla = dir.resolve("vanilla");
        write(vanilla.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84}}");
        write(vanilla.resolve("assets/minecraft/textures/block/stone.png"), "png");
        write(vanilla.resolve("assets/minecraft/shaders/include/fog.glsl"), "float fog_spherical_distance(vec3 pos) {\n    return length(pos);\n}\n");
        write(vanilla.resolve("assets/minecraft/shaders/include/sample_lightmap.glsl"),
            "vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {\n    return texture(lightMap, uv / 256.0);\n}\n");
        write(vanilla.resolve("assets/minecraft/" + PROGRAM_FILE), PROGRAM.replace("<swap.glsl>", "<vanilla_swap.glsl>"));
        write(vanilla.resolve("assets/minecraft/shaders/include/vanilla_swap.glsl"), SWAP.replace("0xA8BFD2", "0x123456").replace("0xD13228", "0x654321"));

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(dir.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(Arrays.stream(packs).map(Path::toFile).toList()))
            .build();
        return PackAcquisition.acquire(new ClientAssets(options, vanilla));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

}
