package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.FormatRange.FormatVersion;
import lib.minecraft.renderer.asset.pack.FormatRange;
import lib.minecraft.renderer.asset.pack.MCMeta.Animation;
import lib.minecraft.renderer.asset.pack.MCMeta.Frame;
import lib.minecraft.renderer.asset.pack.MCMeta.GuiScaling.NineSlice.Border;
import lib.minecraft.renderer.asset.pack.MCMeta.GuiScaling.NineSlice;
import lib.minecraft.renderer.asset.pack.MCMeta.GuiScaling.Stretch;
import lib.minecraft.renderer.asset.pack.MCMeta.GuiScaling.Tile;
import lib.minecraft.renderer.asset.pack.MCMeta.GuiScaling;
import lib.minecraft.renderer.asset.pack.MCMeta.Pack;
import lib.minecraft.renderer.asset.pack.MCMeta.TextureSampling.MipmapStrategy;
import lib.minecraft.renderer.asset.pack.MCMeta.TextureSampling;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of {@link MCMetaParser}, which reads every {@code .mcmeta} section into an {@link MCMeta}: the
 * five on-disk pack.mcmeta shapes (defrosted, hypixel-skyblock, both {@code .cats} decoys, vanilla synth)
 * as description fixtures - plain string, component object, component array, {@code extra} nesting, §
 * formatting codes, tab indentation - each sidecar section (animation, texture, gui.scaling, villager),
 * overlay and filter parsing, and the hard errors - unreadable JSON, and a value of the wrong type in a
 * pack root - and each sidecar section the client's codec refuses, held as refused.
 * <p>
 * One format row is kept, on the defrosted fixture where all three generations coexist, to pin that the
 * parser delegates to {@link FormatRange} at all. Every other normalization row is owned row-by-row by
 * {@code FormatRangeTest} in {@link FormatRange}'s own package, against the same fixture strings.
 */
@DisplayName("MCMetaParser section parsing")
class MCMetaParserTest {

    private static final int MAX = FormatVersion.MAX_MINOR;
    private static final ResourceId ID = new ResourceId("test", "pack");

    private static MCMeta parse(String json) {
        return MCMetaParser.parse(json, ID);
    }

    private static Pack pack(String json) {
        return parse(json).pack().orElseThrow();
    }

    // the five on-disk pack.mcmeta shapes

    @Test
    @DisplayName("defrosted: legacy and modern coexist (newest wins), § description preserved, tabs tolerated")
    void defrosted() {
        String json = "{\n"
            + "  \"pack\": {\n"
            + "    \"pack_format\": 34,\n"
            + "\t\"supported_formats\": {\"min_inclusive\": 34, \"max_inclusive\": 64},\n"
            + "\t\"min_format\": [34,1],\n"
            + "\t\"max_format\": [75,1],\n"
            + "    \"description\": \"§3§k.§r§8 by §3keno §8& §3looshy §3§k.§r\"\n"
            + "  }\n"
            + "}";
        Pack pack = pack(json);
        assertThat(pack.formats(), is(new FormatRange(new FormatVersion(34, 1), new FormatVersion(75, 1))));
        assertThat(pack.description().plain(), equalTo("§3§k.§r§8 by §3keno §8& §3looshy §3§k.§r"));
    }

    @Test
    @DisplayName("hypixel-skyblock: compact one-line, clean name")
    void hypixelSkyblock() {
        Pack pack = pack("{\"pack\":{\"pack_format\":88,\"min_format\":88,\"max_format\":88,\"description\":\"Hypixel SkyBlock\"}}");
        assertThat(pack.description().plain(), equalTo("Hypixel SkyBlock"));
    }

    @Test
    @DisplayName("eureka.cats: a component-object description flattens to its text")
    void eurekaCats() {
        String json = "{\"pack\":{\"description\":{\"text\":\"Use Catharsis-1.0.0-beta.20 or higher!\",\"color\":\"red\",\"bold\":true},\"min_format\":69,\"max_format\":255}}";
        assertThat(pack(json).description().plain(), equalTo("Use Catharsis-1.0.0-beta.20 or higher!"));
    }

    @Test
    @DisplayName("FurSky Reborn.cats: same shape, no bold")
    void furSkyCats() {
        String json = "{\"pack\":{\"description\":{\"text\":\"Requires Catharsis Mod v1.0.0-beta.17 or higher\",\"color\":\"red\"},\"min_format\":69,\"max_format\":255}}";
        assertThat(pack(json).description().plain(), equalTo("Requires Catharsis Mod v1.0.0-beta.17 or higher"));
    }

    @Test
    @DisplayName("vanilla synth: provenance-string description")
    void vanillaSynth() {
        Pack pack = pack("{\"pack\":{\"pack_format\":84,\"description\":\"Minecraft 26.1 vanilla resources (synthesised by asset-renderer ClientAcquisition.extractClientJar)\"}}");
        assertThat(pack.description().plain(), equalTo("Minecraft 26.1 vanilla resources (synthesised by asset-renderer ClientAcquisition.extractClientJar)"));
    }

    // description normalization forms

    @Test
    @DisplayName("description as an array or a component with extra flattens depth-first")
    void descriptionForms() {
        assertThat(pack("{\"pack\":{\"pack_format\":1,\"description\":[\"a\",{\"text\":\"b\"}]}}").description().plain(), equalTo("ab"));
        assertThat(pack("{\"pack\":{\"pack_format\":1,\"description\":{\"text\":\"a\",\"extra\":[{\"text\":\"b\"},\"c\"]}}}").description().plain(), equalTo("abc"));
    }

    // overlays and filters

    /**
     * Verifies a malformed overlay entry is skipped with a diagnostic rather than failing the parse.
     * <p>
     * Swaps {@link System#err} process-wide to capture that diagnostic, which is safe only because the
     * suite ships no {@code junit-platform.properties}, so JUnit runs one test at a time. Adding one that
     * enables parallel execution would let this method steal every concurrent test's stderr.
     */
    @Test
    @DisplayName("overlay entries parse; a malformed entry is skipped WITH a diagnostic, not fatal")
    void overlaysSkipMalformed() {
        String json = "{\"pack\":{\"pack_format\":1},\"overlays\":{\"entries\":[{\"directory\":\"o1\",\"formats\":[3,5]},{\"directory\":\"o2\"}]}}";
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        Pack pack;
        try {
            pack = pack(json);
        } finally {
            System.setErr(original);
        }
        assertThat(pack.overlays().size(), is(1));
        assertThat(pack.overlays().getFirst().directory(), equalTo("o1"));
        assertThat(pack.overlays().getFirst().formats(), is(new FormatRange(new FormatVersion(3, 0), new FormatVersion(5, MAX))));
        assertThat("the skipped entry is logged, not silent", buffer.toString(StandardCharsets.UTF_8), containsString("skipping overlay entry"));
    }

    @Test
    @DisplayName("an overlay entry declaring only min_format/max_format parses, as the client decodes it")
    void overlayModernPair() {
        Pack pack = pack("{\"pack\":{\"min_format\":84,\"max_format\":84},"
            + "\"overlays\":{\"entries\":[{\"directory\":\"o84\",\"min_format\":84,\"max_format\":84}]}}");
        assertThat(pack.overlays().size(), is(1));
        assertThat(pack.overlays().getFirst().directory(), equalTo("o84"));
        assertThat(pack.overlays().getFirst().formats(), is(new FormatRange(new FormatVersion(84, 0), new FormatVersion(84, MAX))));
    }

    @Test
    @DisplayName("filter.block patterns compile")
    void filters() {
        Pack pack = pack("{\"pack\":{\"pack_format\":1},\"filter\":{\"block\":[{\"namespace\":\"minecraft\",\"path\":\"stone.*\"}]}}");
        assertThat(pack.filters().size(), is(1));
        assertThat(pack.filters().getFirst().namespace().isPresent(), is(true));
        assertThat(pack.filters().getFirst().path().orElseThrow().matcher("stone_bricks").matches(), is(true));
    }

    // sidecar sections

    @Test
    @DisplayName("animation frames normalize bare ints and explicit objects")
    void animationSidecar() {
        Animation a = parse("{\"animation\":{\"frametime\":2,\"interpolate\":true,\"frames\":[0,1,{\"index\":2,\"time\":5}]}}")
            .animation().orElseThrow();
        assertThat(a.frametime(), is(2));
        assertThat(a.interpolate(), is(true));
        assertThat(a.width(), is(Optional.empty()));
        assertThat(a.frames().orElseThrow().size(), is(3));
        assertThat(a.frames().orElseThrow().getFirst(), is(new Frame(0, Optional.empty())));
        assertThat(a.frames().orElseThrow().getLast(), is(new Frame(2, Optional.of(5))));
    }

    @Test
    @DisplayName("texture section: blur declared, the rest default")
    void textureSidecar() {
        TextureSampling sampling = parse("{\"texture\":{\"blur\":true}}").texture().orElseThrow();
        assertThat(sampling.blur(), is(true));
        assertThat(sampling.clamp(), is(false));
        assertThat(sampling.mipmapStrategy(), is(MipmapStrategy.AUTO));
        assertThat(sampling.alphaCutoffBias(), is(0f));
    }

    @Test
    @DisplayName("an animation section reads what it declares, a member written null as undeclared and an empty frame list as declared")
    void anAnimationSectionReadsWhatItDeclares() {
        assertThat("a section declaring nothing", parse("{\"animation\":{}}").animation().orElseThrow(),
            is(new Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty())));
        assertThat("an empty frame list is a declared one",
            parse("{\"animation\":{\"frames\":[]}}").animation().orElseThrow().frames(), is(Optional.of(Concurrent.newList())));
        assertThat("members written null are undeclared",
            parse("{\"animation\":{\"frames\":null,\"width\":null,\"frametime\":null,\"interpolate\":null}}").animation().orElseThrow(),
            is(new Animation(1, false, Optional.empty(), Optional.empty(), Optional.empty())));
        assertThat("a frame time written null is undeclared",
            parse("{\"animation\":{\"frames\":[{\"index\":1,\"time\":null}]}}").animation().orElseThrow().frames().orElseThrow().getFirst(),
            is(new Frame(1, Optional.empty())));
        assertThat("a fractional frametime reads its integer part",
            parse("{\"animation\":{\"frametime\":2.5}}").animation().orElseThrow().frametime(), is(2));
        assertThat("a bare frame and an object frame",
            parse("{\"animation\":{\"frames\":[0,{\"index\":2,\"time\":5}]}}").animation().orElseThrow().frames().orElseThrow(),
            is(Concurrent.newList(new Frame(0, Optional.empty()), new Frame(2, Optional.of(5)))));

        Animation sized = parse("{\"animation\":{\"width\":8,\"height\":4}}").animation().orElseThrow();
        assertThat("a declared width", sized.width(), is(Optional.of(8)));
        assertThat("a declared height", sized.height(), is(Optional.of(4)));
    }

    @Test
    @DisplayName("a texture section reads what it declares, each mipmap strategy by its exact name and a member written null as its default")
    void aTextureSectionReadsWhatItDeclares() {
        assertThat("a section declaring nothing", parse("{\"texture\":{}}").texture().orElseThrow(),
            is(new TextureSampling(false, false, MipmapStrategy.AUTO, 0f)));

        for (MipmapStrategy strategy : MipmapStrategy.values()) {
            String name = strategy.name().toLowerCase(Locale.ROOT);
            assertThat(name, parse("{\"texture\":{\"mipmap_strategy\":\"" + name + "\"}}").texture().orElseThrow().mipmapStrategy(),
                is(strategy));
        }

        assertThat("an alpha cutoff bias", parse("{\"texture\":{\"alpha_cutoff_bias\":0.1}}").texture().orElseThrow().alphaCutoffBias(),
            is(0.1f));
        assertThat("members written null read their defaults",
            parse("{\"texture\":{\"blur\":null,\"clamp\":null,\"mipmap_strategy\":null,\"alpha_cutoff_bias\":null}}").texture().orElseThrow(),
            is(new TextureSampling(false, false, MipmapStrategy.AUTO, 0f)));
        assertThat("both flags declared", parse("{\"texture\":{\"blur\":true,\"clamp\":true}}").texture().orElseThrow(),
            is(new TextureSampling(true, true, MipmapStrategy.AUTO, 0f)));
    }

    @Test
    @DisplayName("the animation records refuse what the client's codec refuses")
    void theAnimationRecordsRefuseWhatTheCodecRefuses() {
        IllegalArgumentException frametime = assertThrows(IllegalArgumentException.class,
            () -> new Animation(0, false, Optional.empty(), Optional.empty(), Optional.empty()), "a frametime of 0");
        assertThat(frametime.getMessage(), is("Value must be positive: '0'"));
        assertThrows(IllegalArgumentException.class,
            () -> new Animation(1, false, Optional.of(0), Optional.empty(), Optional.empty()), "a width of 0");
        assertThrows(IllegalArgumentException.class,
            () -> new Animation(1, false, Optional.empty(), Optional.of(-1), Optional.empty()), "a negative height");
        assertThrows(IllegalArgumentException.class, () -> new Frame(-1, Optional.empty()), "a negative index");
        assertThrows(IllegalArgumentException.class, () -> new Frame(0, Optional.of(0)), "a frame time of 0");
    }

    @Test
    @DisplayName("an animation section the client's codec refuses is held as refused, and the document's other sections stand")
    void anAnimationSectionTheClientRefusesIsHeldAsRefused() {
        String[][] cases = {
            {"a frametime of 0", "{\"animation\":{\"frametime\":0}}"},
            {"a negative height", "{\"animation\":{\"height\":-1}}"},
            {"a width of 0", "{\"animation\":{\"width\":0}}"},
            {"a frametime written as text", "{\"animation\":{\"frametime\":\"2\"}}"},
            {"an object where the frametime goes", "{\"animation\":{\"frametime\":{}}}"},
            {"text where the frametime goes", "{\"animation\":{\"frametime\":\"x\"}}"},
            {"a frametime of true", "{\"animation\":{\"frametime\":true}}"},
            {"an interpolate written as text", "{\"animation\":{\"interpolate\":\"true\"}}"},
            {"an interpolate of 1", "{\"animation\":{\"interpolate\":1}}"},
            {"frames that are an object", "{\"animation\":{\"frames\":{}}}"},
            {"a frame entry that is neither a strip index nor an object", "{\"animation\":{\"frames\":[0,\"x\"]}}"},
            {"a frame that is null", "{\"animation\":{\"frames\":[0,null]}}"},
            {"a bare frame of -1", "{\"animation\":{\"frames\":[-1]}}"},
            {"a frame object with no index", "{\"animation\":{\"frames\":[{\"time\":2}]}}"},
            {"text where a frame's index goes", "{\"animation\":{\"frames\":[{\"index\":\"x\"}]}}"},
            {"an index written as text", "{\"animation\":{\"frames\":[{\"index\":\"1\"}]}}"},
            {"an index of -1", "{\"animation\":{\"frames\":[{\"index\":-1}]}}"},
            {"a frame time of 0", "{\"animation\":{\"frames\":[{\"index\":0,\"time\":0}]}}"},
            {"an animation that is a number", "{\"animation\":5}"},
            {"an animation that is null", "{\"animation\":null}"}
        };

        for (String[] refused : cases)
            assertThat(refused[0], parse(refused[1]).animation().getState(), is(Possible.State.EMPTY));

        MCMeta beside = parse("{\"animation\":{\"frametime\":0},\"texture\":{\"blur\":true},"
            + "\"villager\":{\"hat\":\"full\"},\"gui\":{\"scaling\":{\"type\":\"stretch\"}}}");
        assertThat("the refused section", beside.animation().getState(), is(Possible.State.EMPTY));
        assertThat("the texture section beside it stands", beside.texture().isPresent(), is(true));
        assertThat("the villager section beside it stands", beside.villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.FULL));
        assertThat("the gui section beside it stands", beside.gui().isPresent(), is(true));
    }

    @Test
    @DisplayName("a texture section the client's codec refuses is held as refused, and the document's other sections stand")
    void aTextureSectionTheClientRefusesIsHeldAsRefused() {
        String[][] cases = {
            {"a blur of 1", "{\"texture\":{\"blur\":1}}"},
            {"a blur written as text", "{\"texture\":{\"blur\":\"true\"}}"},
            {"an object where the clamp goes", "{\"texture\":{\"clamp\":{}}}"},
            {"a mipmap strategy in the wrong case", "{\"texture\":{\"mipmap_strategy\":\"Mean\"}}"},
            {"a mipmap strategy naming no strategy", "{\"texture\":{\"mipmap_strategy\":\"weird\"}}"},
            {"a number where the mipmap strategy goes", "{\"texture\":{\"mipmap_strategy\":5}}"},
            {"an alpha cutoff bias written as text", "{\"texture\":{\"alpha_cutoff_bias\":\"0.1\"}}"},
            {"an alpha cutoff bias of true", "{\"texture\":{\"alpha_cutoff_bias\":true}}"},
            {"a texture that is a number", "{\"texture\":5}"},
            {"a texture that is null", "{\"texture\":null}"}
        };

        for (String[] refused : cases)
            assertThat(refused[0], parse(refused[1]).texture().getState(), is(Possible.State.EMPTY));

        MCMeta beside = parse("{\"texture\":{\"mipmap_strategy\":\"weird\"},\"animation\":{},\"villager\":{\"hat\":\"full\"}}");
        assertThat("the refused section", beside.texture().getState(), is(Possible.State.EMPTY));
        assertThat("the animation section beside it stands", beside.animation().isPresent(), is(true));
        assertThat("the villager section beside it stands", beside.villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.FULL));
    }

    @Test
    @DisplayName("gui.scaling nine_slice: int border widens to all four sides")
    void guiNineSliceIntBorder() {
        GuiScaling gui = parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":100,\"height\":100,\"border\":9,\"stretch_inner\":true}}}")
            .gui().orElseThrow();
        assertThat(gui, is(new NineSlice(100, 100, new Border(9, 9, 9, 9), true)));
    }

    @Test
    @DisplayName("gui.scaling object border reads each side")
    void guiObjectBorder() {
        GuiScaling gui = parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":8,\"height\":8,\"border\":{\"left\":1,\"top\":2,\"right\":3,\"bottom\":4}}}}")
            .gui().orElseThrow();
        assertThat(gui, is(new NineSlice(8, 8, new Border(1, 2, 3, 4), false)));
    }

    @Test
    @DisplayName("a gui section the client's codec refuses is held as refused, and the document's other sections stand")
    void aGuiSectionTheClientRefusesIsHeldAsRefused() {
        String[][] cases = {
            {"left + right >= width", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":20,\"border\":{\"left\":5,\"top\":0,\"right\":5,\"bottom\":0}}}}"},
            {"top + bottom >= height", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":20,\"height\":10,\"border\":{\"left\":0,\"top\":4,\"right\":0,\"bottom\":6}}}}"},
            {"a nine_slice with no width", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"height\":10,\"border\":1}}}"},
            {"a tile with no height", "{\"gui\":{\"scaling\":{\"type\":\"tile\",\"width\":10}}}"},
            {"a nine_slice with no border", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10}}}"},
            {"a width of 0", "{\"gui\":{\"scaling\":{\"type\":\"tile\",\"width\":0,\"height\":10}}}"},
            {"a negative height", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":-1,\"border\":1}}}"},
            {"a border of 0 in the int form", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":0}}}"},
            {"a negative side", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":{\"left\":-1,\"top\":0,\"right\":0,\"bottom\":0}}}}"},
            {"an object border missing its bottom", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":{\"left\":1,\"top\":1,\"right\":1}}}}"},
            {"a scaling with no type", "{\"gui\":{\"scaling\":{\"width\":10,\"height\":10}}}"},
            {"a type in the wrong case", "{\"gui\":{\"scaling\":{\"type\":\"Tile\",\"width\":10,\"height\":10}}}"},
            {"a type naming no mode", "{\"gui\":{\"scaling\":{\"type\":\"weird\"}}}"},
            {"an object where the type goes", "{\"gui\":{\"scaling\":{\"type\":{}}}}"},
            {"a number where the type goes", "{\"gui\":{\"scaling\":{\"type\":9}}}"},
            {"text where the border goes", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":\"x\"}}}"},
            {"a width written as text", "{\"gui\":{\"scaling\":{\"type\":\"tile\",\"width\":\"10\",\"height\":10}}}"},
            {"a stretch_inner written as text", "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":1,\"stretch_inner\":\"true\"}}}"},
            {"a gui that is a number", "{\"gui\":5}"},
            {"a gui that is null", "{\"gui\":null}"},
            {"a scaling that is a number", "{\"gui\":{\"scaling\":5}}"}
        };

        for (String[] refused : cases)
            assertThat(refused[0], parse(refused[1]).gui().getState(), is(Possible.State.EMPTY));

        MCMeta beside = parse("{\"animation\":{},\"gui\":{\"scaling\":{\"type\":{}}}}");
        assertThat("the refused section", beside.gui().getState(), is(Possible.State.EMPTY));
        assertThat("the animation beside it stands", beside.animation().isPresent(), is(true));
    }

    @Test
    @DisplayName("a gui section reads what its mode reads, a missing scaling as stretch and a missing inner stretch as false")
    void aGuiSectionReadsWhatItsModeReads() {
        assertThat("a gui section with no scaling", parse("{\"gui\":{}}").gui().orElseThrow(), is(GuiScaling.DEFAULT));
        assertThat("a scaling written null", parse("{\"gui\":{\"scaling\":null}}").gui().orElseThrow(), is(GuiScaling.DEFAULT));
        assertThat("a stretch declaring a size and a border",
            parse("{\"gui\":{\"scaling\":{\"type\":\"stretch\",\"width\":16,\"height\":16,\"border\":3}}}").gui().orElseThrow(),
            is(new Stretch()));
        assertThat("a stretch declaring its width as text",
            parse("{\"gui\":{\"scaling\":{\"type\":\"stretch\",\"width\":\"x\"}}}").gui().orElseThrow(), is(new Stretch()));
        assertThat("a tile", parse("{\"gui\":{\"scaling\":{\"type\":\"tile\",\"width\":16,\"height\":8}}}").gui().orElseThrow(),
            is(new Tile(16, 8)));
        assertThat("a nine_slice with no stretch_inner",
            parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":1}}}").gui().orElseThrow(),
            is(new NineSlice(10, 10, new Border(1, 1, 1, 1), false)));
        assertThat("a nine_slice with a stretch_inner written null",
            parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":1,\"stretch_inner\":null}}}")
                .gui().orElseThrow(),
            is(new NineSlice(10, 10, new Border(1, 1, 1, 1), false)));
        assertThat("a nine_slice whose left and right leave a centre slice one texel wide",
            parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":10,\"height\":10,\"border\":{\"left\":4,\"top\":0,\"right\":5,\"bottom\":0}}}}")
                .gui().orElseThrow(),
            is(new NineSlice(10, 10, new Border(4, 0, 5, 0), false)));
    }

    @Test
    @DisplayName("the scaling records refuse what the client's codec refuses")
    void theScalingRecordsRefuseWhatTheCodecRefuses() {
        assertThrows(IllegalArgumentException.class, () -> new Tile(0, 1), "a tile with no width");
        assertThrows(IllegalArgumentException.class, () -> new NineSlice(1, 0, new Border(0, 0, 0, 0), false),
            "a nine-slice with no height");
        assertThrows(IllegalArgumentException.class, () -> new Border(-1, 0, 0, 0), "a negative side");
        assertThrows(IllegalArgumentException.class, () -> new NineSlice(10, 10, new Border(0, 5, 0, 5), false),
            "a top and bottom leaving no centre slice");

        IllegalArgumentException horizontal = assertThrows(IllegalArgumentException.class,
            () -> new NineSlice(10, 10, new Border(5, 0, 5, 0), false), "a left and right leaving no centre slice");
        assertThat(horizontal.getMessage(), is("Nine-sliced texture has no horizontal center slice: '5' + '5' >= '10'"));
    }

    @Test
    @DisplayName("villager hat parses; unknown defaults to NONE")
    void villagerSidecar() {
        assertThat(parse("{\"villager\":{\"hat\":\"full\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.FULL));
        assertThat(parse("{\"villager\":{\"hat\":\"weird\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
    }

    @Test
    @DisplayName("villager hat: partial round-trips, case folds, absent and non-string members read NONE")
    void villagerHatArms() {
        assertThat("the partial arm the profession mesh select keys off",
            parse("{\"villager\":{\"hat\":\"partial\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.PARTIAL));
        assertThat("an upper-case value folds",
            parse("{\"villager\":{\"hat\":\"FULL\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.FULL));
        assertThat("a section without a hat member is present and reads NONE",
            parse("{\"villager\":{}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
        assertThat("a numeric value coerces rather than throwing",
            parse("{\"villager\":{\"hat\":42}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
        assertThat("an object value reads NONE instead of failing",
            parse("{\"villager\":{\"hat\":{}}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
        assertThat("an array value reads NONE instead of failing",
            parse("{\"villager\":{\"hat\":[]}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
        assertThat("an explicit null reads NONE instead of failing",
            parse("{\"villager\":{\"hat\":null}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
    }

    @Test
    @DisplayName("a pack-root doc carries no sidecar sections and vice versa")
    void sectionsAreOrthogonal() {
        MCMeta root = parse("{\"pack\":{\"pack_format\":1}}");
        assertThat(root.pack().isPresent(), is(true));
        assertThat(root.animation().getState(), is(Possible.State.ABSENT));
        assertThat(root.texture().getState(), is(Possible.State.ABSENT));
        assertThat(root.gui().getState(), is(Possible.State.ABSENT));

        MCMeta sidecar = parse("{\"animation\":{}}");
        assertThat(sidecar.pack().isPresent(), is(false));
        assertThat(sidecar.animation().isPresent(), is(true));
    }

    @Test
    @DisplayName("EMPTY carries nothing")
    void empty() {
        assertThat(MCMeta.EMPTY.pack().isPresent(), is(false));
        assertThat(MCMeta.EMPTY.animation().getState(), is(Possible.State.ABSENT));
        assertThat(MCMeta.EMPTY.texture().getState(), is(Possible.State.ABSENT));
        assertThat(MCMeta.EMPTY.gui().getState(), is(Possible.State.ABSENT));
        assertThat(MCMeta.EMPTY.villager().isPresent(), is(false));
    }

    // hard errors

    @Test
    @DisplayName("unreadable JSON throws")
    void hardErrors() {
        assertThrows(ContentException.class, () -> parse("{ bad json"));
    }

    @Test
    @DisplayName("a value of the wrong type in the pack section is malformed and names the document")
    void wrongTypedValuesThrow() {
        // Text inside a [major, minor] format bound, which the format range reads as an integer.
        ContentException refusal = assertThrows(ContentException.class,
            () -> parse("{\"pack\":{\"min_format\":[\"x\",0],\"max_format\":84}}"));
        assertThat(refusal.getMessage(), containsString("'" + ID + "'"));
    }

}
