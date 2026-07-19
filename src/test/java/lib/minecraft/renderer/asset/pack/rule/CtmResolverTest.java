package lib.minecraft.renderer.asset.pack.rule;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.face.BlockFace;
import lib.minecraft.renderer.pipeline.pack.rule.CtmParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the isolated-block CTM resolution path - {@link RuleSet#connectedTextureFor} walking the
 * merged rules first-match-wins and {@link CtmNeighborResolver#select} picking each method's no-neighbor
 * tile. Rules are built through the real {@link CtmParser} so parse and resolve are exercised together.
 */
class CtmResolverTest {

    private static final @NotNull String PROPS_DIR = "optifine/ctm/stone";
    private static final @NotNull String STONE_TEXTURE = "minecraft:block/stone";

    @Test
    @DisplayName("every non-overlay connection method resolves the no-neighbor slot tiles[0]")
    void nonOverlayResolvesFirstTile() {
        assertFirstTile(rule("fixed", "method", "fixed", "matchTiles", "stone", "tiles", "custom"));
        assertFirstTile(rule("top", "method", "top", "matchTiles", "stone", "tiles", "custom"));
        assertFirstTile(rule("ctm", "method", "ctm", "matchTiles", "stone", "tiles", "0-46"));
        assertFirstTile(rule("compact", "method", "ctm_compact", "matchTiles", "stone", "tiles", "0-4"));
        assertFirstTile(rule("horizontal", "method", "horizontal", "matchTiles", "stone", "tiles", "0-3"));
        assertFirstTile(rule("vertical", "method", "vertical", "matchTiles", "stone", "tiles", "0-3"));
        assertFirstTile(rule("hv", "method", "horizontal+vertical", "matchTiles", "stone", "tiles", "0-6"));
        assertFirstTile(rule("vh", "method", "vertical+horizontal", "matchTiles", "stone", "tiles", "0-6"));
        assertFirstTile(rule("repeat", "method", "repeat", "matchTiles", "stone", "tiles", "0-3", "width", "2", "height", "2"));
    }

    @Test
    @DisplayName("random picks deterministically from blockId.hashCode(), stable across calls")
    void randomIsDeterministic() {
        CtmRule rule = rule("rand", "method", "random", "matchTiles", "stone", "tiles", "a b c");
        RuleSet rules = ruleSet(rule);
        ResourceId expected = tileId(rule, Math.floorMod("minecraft:stone".hashCode(), 3));

        ResourceId first = rules.connectedTextureFor(tileCtx(CtmFace.TOP)).orElseThrow();
        ResourceId second = rules.connectedTextureFor(tileCtx(CtmFace.TOP)).orElseThrow();
        assertThat(first, equalTo(expected));
        assertThat(second, equalTo(first));

        // A second subject id folds to its own residue - still formula-deterministic.
        ResourceId dirt = rules.connectedTextureFor(new CtmContext("minecraft:dirt", Map.of(), STONE_TEXTURE, CtmFace.TOP)).orElseThrow();
        assertThat(dirt, equalTo(tileId(rule, Math.floorMod("minecraft:dirt".hashCode(), 3))));
    }

    @Test
    @DisplayName("overlay_* methods resolve empty and never suppress a later base-replacing rule")
    void overlaysAreInertAndNonBlocking() {
        List<CtmRule> overlays = List.of(
            rule("ov", "method", "overlay", "matchTiles", "stone", "tiles", "custom"),
            rule("ovctm", "method", "overlay_ctm", "matchTiles", "stone", "tiles", "0-46"),
            rule("ovrand", "method", "overlay_random", "matchTiles", "stone", "tiles", "a b"),
            rule("ovrep", "method", "overlay_repeat", "matchTiles", "stone", "tiles", "0-3", "width", "2", "height", "2"),
            rule("ovfix", "method", "overlay_fixed", "matchTiles", "stone", "tiles", "custom"));
        for (CtmRule overlay : overlays)
            assertThat(ruleSet(overlay).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(false));

        CtmRule fixed = rule("fx", "method", "fixed", "matchTiles", "stone", "tiles", "winner");
        CtmRule overlayFirst = rule("ov", "method", "overlay", "matchTiles", "stone", "tiles", "loser");
        assertThat(ruleSet(overlayFirst, fixed).connectedTextureFor(tileCtx(CtmFace.TOP)).orElseThrow(), equalTo(tileId(fixed, 0)));
    }

    @Test
    @DisplayName("faces= targets only the listed faces on the icon")
    void facesTargeting() {
        CtmRule top = rule("t", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "top");
        assertThat(ruleSet(top).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(true));
        assertThat(ruleSet(top).connectedTextureFor(tileCtx(CtmFace.NORTH)).isPresent(), is(false));

        CtmRule sides = rule("s", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "sides");
        assertThat(ruleSet(sides).connectedTextureFor(tileCtx(CtmFace.EAST)).isPresent(), is(true));
        assertThat(ruleSet(sides).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(false));
        assertThat(ruleSet(sides).connectedTextureFor(tileCtx(CtmFace.BOTTOM)).isPresent(), is(false));
    }

    @Test
    @DisplayName("<default> stops the walk with the base texture; <skip> falls through to the next rule")
    void sentinels() {
        CtmRule def = rule("def", "method", "fixed", "matchTiles", "stone", "tiles", "<default>");
        CtmRule after = rule("after", "method", "fixed", "matchTiles", "stone", "tiles", "custom");
        assertThat(ruleSet(def).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(false));
        // <default> matched and STOPS - the later concrete rule must not fire.
        assertThat(ruleSet(def, after).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(false));

        CtmRule skip = rule("skip", "method", "fixed", "matchTiles", "stone", "tiles", "<skip>");
        assertThat(ruleSet(skip).connectedTextureFor(tileCtx(CtmFace.TOP)).isPresent(), is(false));
        // <skip> falls through - the later concrete rule DOES fire.
        assertThat(ruleSet(skip, after).connectedTextureFor(tileCtx(CtmFace.TOP)).orElseThrow(), equalTo(tileId(after, 0)));
    }

    @Test
    @DisplayName("matchBlocks state filters: OR within a property, AND across properties, bare id matches any state")
    void matchBlocksStateFilters() {
        CtmRule rule = rule("stairs", "method", "fixed", "tiles", "custom",
            "matchBlocks", "minecraft:oak_stairs:facing=east,west:half=bottom");
        RuleSet rules = ruleSet(rule);
        assertThat(rules.connectedTextureFor(blockCtx("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom"))).isPresent(), is(true));
        assertThat(rules.connectedTextureFor(blockCtx("minecraft:oak_stairs", Map.of("facing", "west", "half", "bottom"))).isPresent(), is(true));
        assertThat(rules.connectedTextureFor(blockCtx("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom"))).isPresent(), is(false));
        assertThat(rules.connectedTextureFor(blockCtx("minecraft:oak_stairs", Map.of("facing", "east", "half", "top"))).isPresent(), is(false));
        assertThat(rules.connectedTextureFor(blockCtx("minecraft:spruce_stairs", Map.of("facing", "east", "half", "bottom"))).isPresent(), is(false));

        CtmRule bare = rule("bare", "method", "fixed", "tiles", "custom", "matchBlocks", "minecraft:stone");
        assertThat(ruleSet(bare).connectedTextureFor(blockCtx("minecraft:stone", Map.of("any", "value"))).isPresent(), is(true));
    }

    @Test
    @DisplayName("matchTiles matches short / path / namespaced tile names, not an unrelated tile")
    void matchTilesNameGrammar() {
        for (String token : List.of("glass", "block/glass", "minecraft:block/glass", "minecraft:glass")) {
            CtmRule rule = rule("g", "method", "fixed", "matchTiles", token, "tiles", "custom");
            assertThat("token " + token, ruleSet(rule).connectedTextureFor(
                new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", CtmFace.TOP)).isPresent(), is(true));
        }
        CtmRule stone = rule("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom");
        assertThat(ruleSet(stone).connectedTextureFor(
            new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", CtmFace.TOP)).isPresent(), is(false));
    }

    @Test
    @DisplayName("a tile-target rule wins over a block-target rule at the merged tile-before-block order")
    void tileTargetBeatsBlockTarget() {
        CtmRule tile = rule("tile", "method", "fixed", "matchTiles", "stone", "tiles", "tileTile");
        CtmRule block = rule("block_stone", "method", "fixed", "matchBlocks", "minecraft:stone", "tiles", "blockTile");
        RuleSet rules = ruleSet(tile, block);   // tiles precede blocks in the merged list
        assertThat(rules.connectedTextureFor(new CtmContext("minecraft:stone", Map.of(), STONE_TEXTURE, CtmFace.TOP)).orElseThrow(),
            equalTo(tileId(tile, 0)));
    }

    @Test
    @DisplayName("CtmFace.fromBlockFace maps UP/DOWN to TOP/BOTTOM and keeps horizontal names")
    void fromBlockFaceMapping() {
        assertThat(CtmFace.fromBlockFace(BlockFace.UP), equalTo(CtmFace.TOP));
        assertThat(CtmFace.fromBlockFace(BlockFace.DOWN), equalTo(CtmFace.BOTTOM));
        assertThat(CtmFace.fromBlockFace(BlockFace.NORTH), equalTo(CtmFace.NORTH));
        assertThat(CtmFace.fromBlockFace(BlockFace.SOUTH), equalTo(CtmFace.SOUTH));
        assertThat(CtmFace.fromBlockFace(BlockFace.EAST), equalTo(CtmFace.EAST));
        assertThat(CtmFace.fromBlockFace(BlockFace.WEST), equalTo(CtmFace.WEST));
    }

    @Test
    @DisplayName("an empty rule set substitutes nothing - the parity-neutral vanilla case")
    void emptyRuleSetIsInert() {
        RuleSet empty = RuleSet.empty(PackId.VANILLA);
        for (BlockFace face : BlockFace.CACHED_VALUES)
            assertThat(empty.connectedTextureFor(tileCtx(CtmFace.fromBlockFace(face))).isPresent(), is(false));
    }

    private void assertFirstTile(@NotNull CtmRule rule) {
        assertThat(ruleSet(rule).connectedTextureFor(tileCtx(CtmFace.TOP)).orElseThrow(), equalTo(tileId(rule, 0)));
    }

    private static @NotNull CtmContext tileCtx(@NotNull CtmFace face) {
        return new CtmContext("minecraft:stone", Map.of(), STONE_TEXTURE, face);
    }

    private static @NotNull CtmContext blockCtx(@NotNull String blockId, @NotNull Map<String, String> state) {
        return new CtmContext(blockId, state, "minecraft:block/ignored", CtmFace.TOP);
    }

    private static @NotNull ResourceId tileId(@NotNull CtmRule rule, int index) {
        return ((TileRef.Texture) rule.tiles().get(index)).id();
    }

    private static @NotNull RuleSet ruleSet(@NotNull CtmRule... rules) {
        return new RuleSet(PackId.VANILLA,
            Concurrent.newUnmodifiableList(),
            Concurrent.adoptList(new ArrayList<>(List.of(rules))).toUnmodifiable(),
            new ColorProperties(new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.<String, Integer>newMap().toUnmodifiable()),
            Optional.empty());
    }

    private static @NotNull CtmRule rule(@NotNull String basename, @NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        ResourceId ruleId = new ResourceId("minecraft", PROPS_DIR + "/" + basename + ".properties");
        return CtmParser.parse(props, ruleId, PackId.VANILLA, PROPS_DIR, basename).orElseThrow();
    }

}
