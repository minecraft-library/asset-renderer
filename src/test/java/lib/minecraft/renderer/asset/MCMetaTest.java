package lib.minecraft.renderer.asset;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.asset.pack.FormatRange.FormatVersion;
import lib.minecraft.renderer.asset.MCMeta.Animation;
import lib.minecraft.renderer.asset.MCMeta.GuiScaling;
import lib.minecraft.renderer.asset.MCMeta.Pack;
import lib.minecraft.renderer.asset.pack.FormatRange;
import lib.minecraft.renderer.asset.MCMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link MCMeta} - the umbrella parser over every {@code .mcmeta} section. Covers the five
 * on-disk pack.mcmeta shapes (defrosted, hypixel-skyblock, both {@code .cats} decoys, vanilla synth)
 * as parse fixtures, the format-tolerance rows (absent pack_format, int/array format bounds,
 * coexisting generations, string/component/§ descriptions, tab indentation), and each sidecar section
 * (animation, texture, gui.scaling, villager) plus overlay/filter parsing.
 */
@DisplayName("MCMeta umbrella parsing")
class MCMetaTest {

    private static final int MAX = FormatVersion.MAX_MINOR;
    private static final ResourceId ID = new ResourceId("test", "pack");

    private static MCMeta parse(String json) {
        return MCMeta.parse(json, ID);
    }

    private static Pack pack(String json) {
        return parse(json).pack().orElseThrow();
    }

    // --- the five on-disk pack.mcmeta shapes ---

    @Test
    @DisplayName("defrosted: legacy+modern coexist (newest wins), § description preserved, tabs tolerated")
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
    @DisplayName("hypixel-skyblock: compact one-line, int min/max, clean name")
    void hypixelSkyblock() {
        Pack pack = pack("{\"pack\":{\"pack_format\":88,\"min_format\":88,\"max_format\":88,\"description\":\"Hypixel SkyBlock\"}}");
        assertThat(pack.formats(), is(new FormatRange(new FormatVersion(88, 0), new FormatVersion(88, MAX))));
        assertThat(pack.description().plain(), equalTo("Hypixel SkyBlock"));
    }

    @Test
    @DisplayName("eureka.cats: no pack_format, component-object description flattens to its text")
    void eurekaCats() {
        String json = "{\"pack\":{\"description\":{\"text\":\"Use Catharsis-1.0.0-beta.20 or higher!\",\"color\":\"red\",\"bold\":true},\"min_format\":69,\"max_format\":255}}";
        Pack pack = pack(json);
        assertThat(pack.formats(), is(new FormatRange(new FormatVersion(69, 0), new FormatVersion(255, MAX))));
        assertThat(pack.description().plain(), equalTo("Use Catharsis-1.0.0-beta.20 or higher!"));
    }

    @Test
    @DisplayName("FurSky Reborn.cats: same shape, no bold")
    void furSkyCats() {
        String json = "{\"pack\":{\"description\":{\"text\":\"Requires Catharsis Mod v1.0.0-beta.17 or higher\",\"color\":\"red\"},\"min_format\":69,\"max_format\":255}}";
        Pack pack = pack(json);
        assertThat(pack.formats(), is(new FormatRange(new FormatVersion(69, 0), new FormatVersion(255, MAX))));
        assertThat(pack.description().plain(), equalTo("Requires Catharsis Mod v1.0.0-beta.17 or higher"));
    }

    @Test
    @DisplayName("vanilla synth: bare pack_format, provenance-string description")
    void vanillaSynth() {
        Pack pack = pack("{\"pack\":{\"pack_format\":84,\"description\":\"Minecraft 26.1 vanilla resources (synthesised by asset-renderer ClientAcquisition.extractClientJar)\"}}");
        assertThat(pack.formats(), is(new FormatRange(new FormatVersion(84, 0), new FormatVersion(84, MAX))));
        assertThat(pack.description().plain(), equalTo("Minecraft 26.1 vanilla resources (synthesised by asset-renderer ClientAcquisition.extractClientJar)"));
    }

    // --- description normalization forms ---

    @Test
    @DisplayName("description as an array or a component with extra flattens depth-first")
    void descriptionForms() {
        assertThat(pack("{\"pack\":{\"pack_format\":1,\"description\":[\"a\",{\"text\":\"b\"}]}}").description().plain(), equalTo("ab"));
        assertThat(pack("{\"pack\":{\"pack_format\":1,\"description\":{\"text\":\"a\",\"extra\":[{\"text\":\"b\"},\"c\"]}}}").description().plain(), equalTo("abc"));
    }

    // --- overlays + filters ---

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
    @DisplayName("filter.block patterns compile")
    void filters() {
        Pack pack = pack("{\"pack\":{\"pack_format\":1},\"filter\":{\"block\":[{\"namespace\":\"minecraft\",\"path\":\"stone.*\"}]}}");
        assertThat(pack.filters().size(), is(1));
        assertThat(pack.filters().getFirst().namespace().isPresent(), is(true));
        assertThat(pack.filters().getFirst().path().orElseThrow().matcher("stone_bricks").matches(), is(true));
    }

    // --- sidecar sections ---

    @Test
    @DisplayName("animation frames normalize bare ints and explicit objects")
    void animationSidecar() {
        Animation a = parse("{\"animation\":{\"frametime\":2,\"interpolate\":true,\"frames\":[0,1,{\"index\":2,\"time\":5}]}}")
            .animation().orElseThrow();
        assertThat(a.frametime(), is(2));
        assertThat(a.interpolate(), is(true));
        assertThat(a.width(), is(-1));
        assertThat(a.frames().size(), is(3));
        assertThat(a.frames().getFirst(), is(new MCMeta.Frame(0, -1)));
        assertThat(a.frames().getLast(), is(new MCMeta.Frame(2, 5)));
    }

    @Test
    @DisplayName("texture flags default false")
    void textureSidecar() {
        MCMeta.TextureFlags flags = parse("{\"texture\":{\"blur\":true}}").texture().orElseThrow();
        assertThat(flags.blur(), is(true));
        assertThat(flags.clamp(), is(false));
    }

    @Test
    @DisplayName("gui.scaling nine_slice: int border widens to all four sides")
    void guiNineSliceIntBorder() {
        GuiScaling gui = parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":100,\"height\":100,\"border\":9,\"stretch_inner\":true}}}")
            .gui().orElseThrow();
        assertThat(gui.type(), is(GuiScaling.Type.NINE_SLICE));
        assertThat(gui.width(), is(100));
        assertThat(gui.height(), is(100));
        assertThat(gui.border(), is(new GuiScaling.Border(9, 9, 9, 9)));
        assertThat(gui.stretchInner(), is(true));
    }

    @Test
    @DisplayName("gui.scaling object border reads each side")
    void guiObjectBorder() {
        GuiScaling gui = parse("{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"border\":{\"left\":1,\"top\":2,\"right\":3,\"bottom\":4}}}}")
            .gui().orElseThrow();
        assertThat(gui.border(), is(new GuiScaling.Border(1, 2, 3, 4)));
    }

    @Test
    @DisplayName("villager hat parses; unknown defaults to NONE")
    void villagerSidecar() {
        assertThat(parse("{\"villager\":{\"hat\":\"full\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.FULL));
        assertThat(parse("{\"villager\":{\"hat\":\"weird\"}}").villager().orElseThrow().hat(), is(MCMeta.Villager.Hat.NONE));
    }

    @Test
    @DisplayName("a pack-root doc carries no sidecar sections and vice versa")
    void sectionsAreOrthogonal() {
        MCMeta root = parse("{\"pack\":{\"pack_format\":1}}");
        assertThat(root.pack().isPresent(), is(true));
        assertThat(root.animation().isPresent(), is(false));
        assertThat(root.gui().isPresent(), is(false));

        MCMeta sidecar = parse("{\"animation\":{}}");
        assertThat(sidecar.pack().isPresent(), is(false));
        assertThat(sidecar.animation().isPresent(), is(true));
    }

    @Test
    @DisplayName("EMPTY carries nothing")
    void empty() {
        assertThat(MCMeta.EMPTY.pack().isPresent(), is(false));
        assertThat(MCMeta.EMPTY.animation().isPresent(), is(false));
        assertThat(MCMeta.EMPTY.texture().isPresent(), is(false));
        assertThat(MCMeta.EMPTY.gui().isPresent(), is(false));
        assertThat(MCMeta.EMPTY.villager().isPresent(), is(false));
    }

    // --- hard errors ---

    @Test
    @DisplayName("unreadable JSON and malformed format encodings throw")
    void hardErrors() {
        assertThrows(PipelineException.class, () -> parse("{ bad json"));
        assertThrows(PipelineException.class, () -> parse("{\"pack\":{\"supported_formats\":[1,2,3]}}"));
    }

}
