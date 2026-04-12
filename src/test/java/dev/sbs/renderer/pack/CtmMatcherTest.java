package dev.sbs.renderer.pack;

import dev.sbs.renderer.pipeline.pack.CtmMatcher;
import dev.sbs.renderer.pipeline.pack.CtmMethod;
import dev.sbs.renderer.pipeline.pack.CtmResolution;
import dev.sbs.renderer.pipeline.pack.CtmRule;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class CtmMatcherTest {

    @Test
    @DisplayName("FIXED method returns tiles[0] with no overlay")
    void fixedReturnsFirstTile() {
        CtmRule rule = rule(CtmMethod.FIXED, "pack:block/stone_custom_0", "pack:block/stone_custom_1");
        Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, "minecraft:stone", "minecraft:block/stone");
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("pack:block/stone_custom_0"));
        assertThat(resolution.get().overlayTextureId().isPresent(), is(false));
    }

    @Test
    @DisplayName("RANDOM is deterministic for the same block id")
    void randomIsDeterministic() {
        CtmRule rule = rule(CtmMethod.RANDOM, "tile_0", "tile_1", "tile_2", "tile_3");
        Optional<CtmResolution> first = CtmMatcher.resolve(rule, "minecraft:stone_bricks", "minecraft:block/stone_bricks");
        Optional<CtmResolution> second = CtmMatcher.resolve(rule, "minecraft:stone_bricks", "minecraft:block/stone_bricks");
        assertThat(first.isPresent(), is(true));
        assertThat(second.isPresent(), is(true));
        assertThat(first.get().textureId(), equalTo(second.get().textureId()));
    }

    @Test
    @DisplayName("OVERLAY_FIXED returns base texture with overlay populated")
    void overlayFixedPopulatesOverlay() {
        CtmRule rule = rule(CtmMethod.OVERLAY_FIXED, "pack:block/moss_overlay");
        Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, "minecraft:cobblestone", "minecraft:block/cobblestone");
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("minecraft:block/cobblestone"));
        assertThat(resolution.get().overlayTextureId().isPresent(), is(true));
        assertThat(resolution.get().overlayTextureId().get(), equalTo("pack:block/moss_overlay"));
    }

    @Test
    @DisplayName("UNSUPPORTED method falls back to tiles[0]")
    void unsupportedFallsBackToFirstTile() {
        CtmRule rule = rule(CtmMethod.UNSUPPORTED, "fallback_tile");
        Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, "minecraft:glass", "minecraft:block/glass");
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("fallback_tile"));
    }

    @Test
    @DisplayName("empty tiles list returns empty resolution")
    void emptyTilesReturnsEmpty() {
        CtmRule rule = new CtmRule(
            "test.properties",
            0,
            CtmMethod.FIXED,
            Concurrent.newList(),
            Concurrent.newList(),
            Concurrent.newList(),
            Concurrent.newList(),
            EnumSet.of(CtmRule.Face.ALL)
        );
        Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, "minecraft:stone", "minecraft:block/stone");
        assertThat(resolution.isPresent(), is(false));
    }

    @Test
    @DisplayName("CtmMethod.parse recognizes supported methods")
    void parseRecognizesSupportedMethods() {
        assertThat(CtmMethod.parse("fixed"), is(CtmMethod.FIXED));
        assertThat(CtmMethod.parse("random"), is(CtmMethod.RANDOM));
        assertThat(CtmMethod.parse("repeat"), is(CtmMethod.REPEAT));
        assertThat(CtmMethod.parse("overlay"), is(CtmMethod.OVERLAY));
        assertThat(CtmMethod.parse("overlay_fixed"), is(CtmMethod.OVERLAY_FIXED));
    }

    @Test
    @DisplayName("CtmMethod.parse maps neighbor-dependent methods to UNSUPPORTED")
    void parseMapsNeighborMethodsToUnsupported() {
        assertThat(CtmMethod.parse("ctm"), is(CtmMethod.UNSUPPORTED));
        assertThat(CtmMethod.parse("ctm_compact"), is(CtmMethod.UNSUPPORTED));
        assertThat(CtmMethod.parse("horizontal"), is(CtmMethod.UNSUPPORTED));
        assertThat(CtmMethod.parse("vertical"), is(CtmMethod.UNSUPPORTED));
        assertThat(CtmMethod.parse("top"), is(CtmMethod.UNSUPPORTED));
    }

    private static @org.jetbrains.annotations.NotNull CtmRule rule(@org.jetbrains.annotations.NotNull CtmMethod method, @org.jetbrains.annotations.NotNull String @org.jetbrains.annotations.NotNull ... tileIds) {
        ConcurrentList<String> tiles = Concurrent.newList(tileIds);

        ConcurrentList<String> blocks = Concurrent.newList();
        blocks.add("minecraft:stone");
        blocks.add("minecraft:stone_bricks");
        blocks.add("minecraft:cobblestone");
        blocks.add("minecraft:glass");

        return new CtmRule(
            "test.properties",
            0,
            method,
            blocks,
            Concurrent.newList(),
            tiles,
            Concurrent.newList(),
            EnumSet.of(CtmRule.Face.ALL)
        );
    }

}
