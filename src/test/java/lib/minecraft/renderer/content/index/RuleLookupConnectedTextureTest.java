package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.rule.ColorProperties;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.asset.rule.TileRef;
import lib.minecraft.renderer.content.rule.CtmNeighbors;
import lib.minecraft.renderer.content.rule.CtmParser;
import lib.minecraft.renderer.content.rule.RuleScanner;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.fixture.PackFixtures;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Isolated-block CTM resolution - {@link RuleLookup#connectedTexture} walking the merged rules
 * first-match-wins and {@link CtmNeighbors#select} picking each method's no-neighbor tile. Rules
 * are built through the real {@link CtmParser} so parse and resolve are exercised together, and one
 * case takes its rule from a pack directory through {@link RuleScanner#mergeAll}, so a rule that has
 * been scanned and merged is resolved end to end.
 */
@DisplayName("RuleLookup.connectedTexture isolated-block resolution")
class RuleLookupConnectedTextureTest {

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

        ResourceId first = RuleLookup.connectedTexture(rules, tileCtx(Face.UP)).orElseThrow();
        ResourceId second = RuleLookup.connectedTexture(rules, tileCtx(Face.UP)).orElseThrow();
        assertThat(first, equalTo(expected));
        assertThat(second, equalTo(first));

        // A second subject id folds to its own residue - still formula-deterministic.
        ResourceId dirt = RuleLookup.connectedTexture(rules, new CtmContext("minecraft:dirt", Map.of(), STONE_TEXTURE, Face.UP)).orElseThrow();
        assertThat(dirt, equalTo(tileId(rule, Math.floorMod("minecraft:dirt".hashCode(), 3))));
    }

    @Test
    @DisplayName("overlay_* methods match nothing and never suppress a later base-replacing rule")
    void overlaysAreInertAndNonBlocking() {
        List<CtmRule> overlays = List.of(
            rule("ov", "method", "overlay", "matchTiles", "stone", "tiles", "custom"),
            rule("ovctm", "method", "overlay_ctm", "matchTiles", "stone", "tiles", "0-46"),
            rule("ovrand", "method", "overlay_random", "matchTiles", "stone", "tiles", "a b"),
            rule("ovrep", "method", "overlay_repeat", "matchTiles", "stone", "tiles", "0-3", "width", "2", "height", "2"),
            rule("ovfix", "method", "overlay_fixed", "matchTiles", "stone", "tiles", "custom"));
        for (CtmRule overlay : overlays)
            assertThat(RuleLookup.connectedTexture(ruleSet(overlay), tileCtx(Face.UP)).getState(), is(Possible.State.ABSENT));

        CtmRule fixed = rule("fx", "method", "fixed", "matchTiles", "stone", "tiles", "winner");
        CtmRule overlayFirst = rule("ov", "method", "overlay", "matchTiles", "stone", "tiles", "loser");
        assertThat(RuleLookup.connectedTexture(ruleSet(overlayFirst, fixed), tileCtx(Face.UP)).orElseThrow(), equalTo(tileId(fixed, 0)));
    }

    @Test
    @DisplayName("faces= targets only the listed faces on the icon")
    void facesTargeting() {
        CtmRule top = rule("t", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "top");
        assertThat(RuleLookup.connectedTexture(ruleSet(top), tileCtx(Face.UP)).isPresent(), is(true));
        assertThat(RuleLookup.connectedTexture(ruleSet(top), tileCtx(Face.NORTH)).getState(), is(Possible.State.ABSENT));

        CtmRule sides = rule("s", "method", "fixed", "matchTiles", "stone", "tiles", "custom", "faces", "sides");
        assertThat(RuleLookup.connectedTexture(ruleSet(sides), tileCtx(Face.EAST)).isPresent(), is(true));
        assertThat(RuleLookup.connectedTexture(ruleSet(sides), tileCtx(Face.UP)).getState(), is(Possible.State.ABSENT));
        assertThat(RuleLookup.connectedTexture(ruleSet(sides), tileCtx(Face.DOWN)).getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("<default> stops the walk with the base texture; <skip> falls through to the next rule")
    void sentinels() {
        CtmRule def = rule("def", "method", "fixed", "matchTiles", "stone", "tiles", "<default>");
        CtmRule after = rule("after", "method", "fixed", "matchTiles", "stone", "tiles", "custom");
        // <default> matched and keeps the base texture: empty, where no match at all is absent.
        assertThat(RuleLookup.connectedTexture(ruleSet(def), tileCtx(Face.UP)).getState(), is(Possible.State.EMPTY));
        // <default> matched and STOPS - the later concrete rule must not fire.
        assertThat(RuleLookup.connectedTexture(ruleSet(def, after), tileCtx(Face.UP)).getState(), is(Possible.State.EMPTY));

        CtmRule skip = rule("skip", "method", "fixed", "matchTiles", "stone", "tiles", "<skip>");
        // A lone <skip> falls through to no rule at all, so nothing matched.
        assertThat(RuleLookup.connectedTexture(ruleSet(skip), tileCtx(Face.UP)).getState(), is(Possible.State.ABSENT));
        // <skip> falls through - the later concrete rule DOES fire.
        assertThat(RuleLookup.connectedTexture(ruleSet(skip, after), tileCtx(Face.UP)).orElseThrow(), equalTo(tileId(after, 0)));
    }

    @Test
    @DisplayName("matchBlocks state filters: OR within a property, AND across properties, bare id matches any state")
    void matchBlocksStateFilters() {
        CtmRule rule = rule("stairs", "method", "fixed", "tiles", "custom",
            "matchBlocks", "minecraft:oak_stairs:facing=east,west:half=bottom");
        RuleSet rules = ruleSet(rule);
        assertThat(RuleLookup.connectedTexture(rules, blockCtx("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom"))).isPresent(), is(true));
        assertThat(RuleLookup.connectedTexture(rules, blockCtx("minecraft:oak_stairs", Map.of("facing", "west", "half", "bottom"))).isPresent(), is(true));
        assertThat(RuleLookup.connectedTexture(rules, blockCtx("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom"))).getState(), is(Possible.State.ABSENT));
        assertThat(RuleLookup.connectedTexture(rules, blockCtx("minecraft:oak_stairs", Map.of("facing", "east", "half", "top"))).getState(), is(Possible.State.ABSENT));
        assertThat(RuleLookup.connectedTexture(rules, blockCtx("minecraft:spruce_stairs", Map.of("facing", "east", "half", "bottom"))).getState(), is(Possible.State.ABSENT));

        CtmRule bare = rule("bare", "method", "fixed", "tiles", "custom", "matchBlocks", "minecraft:stone");
        assertThat(RuleLookup.connectedTexture(ruleSet(bare), blockCtx("minecraft:stone", Map.of("any", "value"))).isPresent(), is(true));
    }

    @Test
    @DisplayName("matchTiles matches short / path / namespaced tile names, not an unrelated tile")
    void matchTilesNameGrammar() {
        for (String token : List.of("glass", "block/glass", "minecraft:block/glass", "minecraft:glass")) {
            CtmRule rule = rule("g", "method", "fixed", "matchTiles", token, "tiles", "custom");
            assertThat("token " + token, RuleLookup.connectedTexture(ruleSet(rule),
                new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", Face.UP)).isPresent(), is(true));
        }
        CtmRule stone = rule("stone", "method", "fixed", "matchTiles", "stone", "tiles", "custom");
        assertThat(RuleLookup.connectedTexture(ruleSet(stone),
            new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", Face.UP)).getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("a tile-target rule wins over a block-target rule at the merged tile-before-block order")
    void tileTargetBeatsBlockTarget() {
        CtmRule tile = rule("tile", "method", "fixed", "matchTiles", "stone", "tiles", "tileTile");
        CtmRule block = rule("block_stone", "method", "fixed", "matchBlocks", "minecraft:stone", "tiles", "blockTile");
        RuleSet rules = ruleSet(tile, block);   // tiles precede blocks in the merged list
        assertThat(RuleLookup.connectedTexture(rules, new CtmContext("minecraft:stone", Map.of(), STONE_TEXTURE, Face.UP)).orElseThrow(),
            equalTo(tileId(tile, 0)));
    }

    @Test
    @DisplayName("a rule scanned out of a pack directory resolves its tile for the targeted face and nothing for others")
    void scannedRuleResolvesForItsFace(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve(PackId.VANILLA.value());
        Path file = root.resolve("assets/minecraft/optifine/ctm/glass.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "method=fixed\nmatchTiles=glass\ntiles=custom\nfaces=top");

        RuleSet merged = RuleScanner.mergeAll(Concurrent.newList(PackFixtures.rulePack(PackId.VANILLA, root)));
        ResourceId top = RuleLookup.connectedTexture(merged,
            new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", Face.UP)).orElseThrow();
        assertThat(top.name(), equalTo("optifine/ctm/custom"));
        assertThat(RuleLookup.connectedTexture(merged,
            new CtmContext("minecraft:glass", Map.of(), "minecraft:block/glass", Face.NORTH)).getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("an empty rule set substitutes nothing - the parity-neutral vanilla case")
    void emptyRuleSetIsInert() {
        RuleSet empty = RuleSet.empty(PackId.VANILLA);
        Face.forEach(face ->
            assertThat(RuleLookup.connectedTexture(empty, tileCtx(face)).getState(), is(Possible.State.ABSENT)));
    }

    private void assertFirstTile(@NotNull CtmRule rule) {
        assertThat(RuleLookup.connectedTexture(ruleSet(rule), tileCtx(Face.UP)).orElseThrow(), equalTo(tileId(rule, 0)));
    }

    private static @NotNull CtmContext tileCtx(@NotNull Face face) {
        return new CtmContext("minecraft:stone", Map.of(), STONE_TEXTURE, face);
    }

    private static @NotNull CtmContext blockCtx(@NotNull String blockId, @NotNull Map<String, String> state) {
        return new CtmContext(blockId, state, "minecraft:block/ignored", Face.UP);
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
