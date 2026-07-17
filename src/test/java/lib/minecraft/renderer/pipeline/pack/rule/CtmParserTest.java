package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link CtmParser} - range expansion, correct {@code top} / {@code bottom} face mapping,
 * per-method fail-closed tile-count validation, filename inference, and {@code matchBlocks}
 * state-filter parsing. CTM renders nothing, so these fixtures are the sole exercise of the CTM
 * grammar.
 */
class CtmParserTest {

    private static final @NotNull String PROPS_DIR = "optifine/ctm/stone";

    @Test
    @DisplayName("#1 tiles=0-46 unrolls to 47 texture entries for method=ctm")
    void rangeExpansion() {
        CtmRule rule = parse("stone", "method", "ctm", "matchTiles", "stone", "tiles", "0-46").orElseThrow();
        assertThat(rule.tiles().size(), equalTo(47));
        assertThat(rule.tiles().getFirst(), instanceOf(TileRef.Texture.class));
    }

    @Test
    @DisplayName("#4 faces=top maps to the TOP face only, never ALL")
    void facesTopIsNotAll() {
        CtmRule rule = parse("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "top").orElseThrow();
        assertThat(rule.faces(), equalTo(EnumSet.of(CtmFace.TOP)));
    }

    @Test
    @DisplayName("faces=sides expands to N/S/E/W; absent faces defaults to all six")
    void facesSidesAndDefault() {
        CtmRule sides = parse("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "sides").orElseThrow();
        assertThat(sides.faces(), equalTo(EnumSet.of(CtmFace.NORTH, CtmFace.SOUTH, CtmFace.EAST, CtmFace.WEST)));

        CtmRule all = parse("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom").orElseThrow();
        assertThat(all.faces(), equalTo(EnumSet.allOf(CtmFace.class)));
    }

    @Test
    @DisplayName("an unknown face token rejects the rule (fail-closed)")
    void unknownFaceRejected() {
        assertThat(parse("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "diagonal").isPresent(), is(false));
    }

    @Test
    @DisplayName("method=ctm with the wrong tile count is rejected (fail-closed)")
    void wrongTileCountRejected() {
        assertThat(parse("stone", "method", "ctm", "matchTiles", "stone", "tiles", "0-45").isPresent(), is(false));
        assertThat(parse("stone", "method", "ctm_compact", "matchTiles", "stone", "tiles", "0-3").isPresent(), is(false));
        assertThat(parse("stone", "method", "top", "matchTiles", "stone", "tiles", "a b").isPresent(), is(false));
    }

    @Test
    @DisplayName("an unknown method is rejected (fail-closed)")
    void unknownMethodRejected() {
        assertThat(parse("stone", "method", "sideways", "matchTiles", "stone", "tiles", "custom").isPresent(), is(false));
    }

    @Test
    @DisplayName("overlay_* methods are first-class constants")
    void overlayFirstClass() {
        CtmRule rule = parse("stone", "method", "overlay_ctm", "matchTiles", "stone", "tiles", "0-46").orElseThrow();
        assertThat(rule.method(), equalTo(CtmMethod.OVERLAY_CTM));
    }

    @Test
    @DisplayName("#8 filename inference: block_<name> is a block target, <name> is a tile target")
    void filenameInference() {
        CtmRule block = parse("block_stone", "method", "fixed", "tiles", "custom").orElseThrow();
        assertThat(block.target(), instanceOf(CtmTarget.Blocks.class));
        assertThat(((CtmTarget.Blocks) block.target()).blocks().getFirst().block().id(), equalTo("minecraft:stone"));

        CtmRule tile = parse("glass", "method", "fixed", "tiles", "custom").orElseThrow();
        assertThat(tile.target(), instanceOf(CtmTarget.Tiles.class));
        assertThat(((CtmTarget.Tiles) tile.target()).names().getFirst(), equalTo("glass"));
    }

    @Test
    @DisplayName("matchBlocks state filters parse into per-property value lists")
    void matchBlocksStateFilters() {
        CtmRule rule = parse("stairs", "method", "fixed", "tiles", "custom",
            "matchBlocks", "minecraft:oak_stairs:facing=east,west:half=bottom").orElseThrow();
        CtmTarget.Blocks blocks = (CtmTarget.Blocks) rule.target();
        BlockMatch match = blocks.blocks().getFirst();
        assertThat(match.block().id(), equalTo("minecraft:oak_stairs"));
        assertThat(match.properties().getOptional("facing").orElseThrow(), equalTo(java.util.List.of("east", "west")));
        assertThat(match.properties().getOptional("half").orElseThrow(), equalTo(java.util.List.of("bottom")));
    }

    @Test
    @DisplayName("<skip> and <default> tile sentinels resolve to their marker refs")
    void tileSentinels() {
        CtmRule rule = parse("stone", "method", "random", "matchTiles", "stone", "tiles", "<skip> tex <default>").orElseThrow();
        assertThat(rule.tiles().get(0), instanceOf(TileRef.Skip.class));
        assertThat(rule.tiles().get(1), instanceOf(TileRef.Texture.class));
        assertThat(rule.tiles().get(2), instanceOf(TileRef.Default.class));
    }

    private static @NotNull Optional<CtmRule> parse(@NotNull String basename, @NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        ResourceId ruleId = new ResourceId("minecraft", PROPS_DIR + "/" + basename + ".properties");
        return CtmParser.parse(props, ruleId, PackId.VANILLA, PROPS_DIR, basename);
    }

}
