package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import org.jetbrains.annotations.NotNull;
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
    @DisplayName("neighbor-based methods called via resolve (no pattern) fall back to tiles[0]")
    void neighborMethodsWithoutPatternFallBack() {
        for (CtmMethod method : new CtmMethod[]{
            CtmMethod.CTM, CtmMethod.CTM_COMPACT, CtmMethod.HORIZONTAL, CtmMethod.VERTICAL,
            CtmMethod.TOP, CtmMethod.HORIZONTAL_VERTICAL, CtmMethod.VERTICAL_HORIZONTAL
        }) {
            CtmRule rule = rule(method, "first", "second");
            Optional<CtmResolution> resolution = CtmMatcher.resolve(rule, "minecraft:stone", "minecraft:block/stone");
            assertThat("method=" + method, resolution.isPresent(), is(true));
            assertThat("method=" + method, resolution.get().textureId(), equalTo("first"));
        }
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
    @DisplayName("CtmMethod.parse recognises every neighbor-based method as its concrete value")
    void parseRecognisesNeighborMethods() {
        assertThat(CtmMethod.parse("ctm"), is(CtmMethod.CTM));
        assertThat(CtmMethod.parse("ctm_compact"), is(CtmMethod.CTM_COMPACT));
        assertThat(CtmMethod.parse("horizontal"), is(CtmMethod.HORIZONTAL));
        assertThat(CtmMethod.parse("vertical"), is(CtmMethod.VERTICAL));
        assertThat(CtmMethod.parse("top"), is(CtmMethod.TOP));
        assertThat(CtmMethod.parse("horizontal+vertical"), is(CtmMethod.HORIZONTAL_VERTICAL));
        assertThat(CtmMethod.parse("horizontal_vertical"), is(CtmMethod.HORIZONTAL_VERTICAL));
        assertThat(CtmMethod.parse("vertical+horizontal"), is(CtmMethod.VERTICAL_HORIZONTAL));
        assertThat(CtmMethod.parse("vertical_horizontal"), is(CtmMethod.VERTICAL_HORIZONTAL));
    }

    @Test
    @DisplayName("unknown method strings still parse to UNSUPPORTED")
    void parseUnknownFallsBackToUnsupported() {
        assertThat(CtmMethod.parse("not_a_real_method"), is(CtmMethod.UNSUPPORTED));
        assertThat(CtmMethod.parse(""), is(CtmMethod.UNSUPPORTED));
    }

    @Test
    @DisplayName("isNeighborBased returns true exactly for the seven neighbor methods")
    void isNeighborBasedClassification() {
        for (CtmMethod method : CtmMethod.values()) {
            boolean expected = switch (method) {
                case CTM, CTM_COMPACT, HORIZONTAL, VERTICAL, TOP, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL -> true;
                default -> false;
            };
            assertThat("method=" + method, method.isNeighborBased(), is(expected));
        }
    }

    @Test
    @DisplayName("HORIZONTAL: 4-tile selection from W/E bits")
    void horizontalTileSelection() {
        CtmRule rule = rule(CtmMethod.HORIZONTAL, "h0", "h1", "h2", "h3");
        assertHorizontal(rule, NeighborPattern.EMPTY, "h0");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W), "h1");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E), "h2");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W | NeighborPattern.E), "h3");
    }

    @Test
    @DisplayName("VERTICAL: 4-tile selection from N/S bits")
    void verticalTileSelection() {
        CtmRule rule = rule(CtmMethod.VERTICAL, "v0", "v1", "v2", "v3");
        assertHorizontal(rule, NeighborPattern.EMPTY, "v0");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.S), "v1");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N), "v2");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N | NeighborPattern.S), "v3");
    }

    @Test
    @DisplayName("TOP: 2-tile selection from N bit")
    void topTileSelection() {
        CtmRule rule = rule(CtmMethod.TOP, "t_off", "t_on");
        assertHorizontal(rule, NeighborPattern.EMPTY, "t_off");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N), "t_on");
        // Other bits don't matter for TOP.
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.S | NeighborPattern.E | NeighborPattern.W), "t_off");
    }

    @Test
    @DisplayName("CTM_COMPACT: 5-tile classification across solo/edge/interior/corner")
    void ctmCompactTileSelection() {
        CtmRule rule = rule(CtmMethod.CTM_COMPACT, "solo", "vEdge", "hEdge", "interior", "corner");
        assertHorizontal(rule, NeighborPattern.EMPTY, "solo");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N), "vEdge");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N | NeighborPattern.S), "vEdge");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E), "hEdge");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E | NeighborPattern.W), "hEdge");
        assertHorizontal(rule, new NeighborPattern(
            NeighborPattern.N | NeighborPattern.E | NeighborPattern.S | NeighborPattern.W
        ), "interior");
        // Three cardinals or partial corners route through "corner".
        assertHorizontal(rule, new NeighborPattern(
            NeighborPattern.N | NeighborPattern.E | NeighborPattern.S
        ), "corner");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N | NeighborPattern.E), "corner");
    }

    @Test
    @DisplayName("HORIZONTAL_VERTICAL: H result wins, V extends only when H is index 0")
    void horizontalVerticalTileSelection() {
        CtmRule rule = rule(CtmMethod.HORIZONTAL_VERTICAL, "hv0", "hv1", "hv2", "hv3", "hv4", "hv5", "hv6");
        // Horizontal carries the result when any horizontal neighbor connects.
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W), "hv1");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E), "hv2");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W | NeighborPattern.E), "hv3");
        // Vertical extension only when horizontal is empty.
        assertHorizontal(rule, NeighborPattern.EMPTY, "hv0");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.S), "hv4");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N), "hv5");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N | NeighborPattern.S), "hv6");
        // Both axes connecting still routes through HORIZONTAL.
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W | NeighborPattern.N), "hv1");
    }

    @Test
    @DisplayName("VERTICAL_HORIZONTAL: V result wins, H extends only when V is index 0")
    void verticalHorizontalTileSelection() {
        CtmRule rule = rule(CtmMethod.VERTICAL_HORIZONTAL, "vh0", "vh1", "vh2", "vh3", "vh4", "vh5", "vh6");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.S), "vh1");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N), "vh2");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.N | NeighborPattern.S), "vh3");
        assertHorizontal(rule, NeighborPattern.EMPTY, "vh0");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W), "vh4");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E), "vh5");
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.E | NeighborPattern.W), "vh6");
        // Both axes connecting still routes through VERTICAL.
        assertHorizontal(rule, new NeighborPattern(NeighborPattern.W | NeighborPattern.N), "vh2");
    }

    @Test
    @DisplayName("CTM: validateCtmPattern masks diagonals whose cardinals are missing")
    void ctmValidationMasksOrphanDiagonals() {
        // Diagonal NE present but only N cardinal connects: NE should be masked off.
        int validatedNoCardinalE = CtmMatcher.validateCtmPattern(NeighborPattern.N | NeighborPattern.NE);
        assertThat(validatedNoCardinalE & NeighborPattern.NE, is(0));
        assertThat(validatedNoCardinalE & NeighborPattern.N, is(NeighborPattern.N));

        // Both N and E set: NE bit survives validation.
        int validatedFull = CtmMatcher.validateCtmPattern(NeighborPattern.N | NeighborPattern.E | NeighborPattern.NE);
        assertThat(validatedFull & NeighborPattern.NE, is(NeighborPattern.NE));
    }

    @Test
    @DisplayName("CTM: 47 distinct validated patterns produce 47 distinct tile indices")
    void ctmTileTableHas47DistinctIndices() {
        java.util.Set<Integer> validatedPatterns = new java.util.HashSet<>();
        for (int raw = 0; raw < 256; raw++)
            validatedPatterns.add(CtmMatcher.validateCtmPattern(raw));
        assertThat(validatedPatterns.size(), equalTo(47));

        // Every raw input maps deterministically through CTM, and all 47 distinct validated values
        // get distinct indices in [0, 46].
        CtmRule rule = ctmRule47();
        java.util.Set<String> selectedTiles = new java.util.HashSet<>();
        for (int raw = 0; raw < 256; raw++) {
            Optional<CtmResolution> resolution = CtmMatcher.resolveWithPattern(
                rule, "minecraft:stone", "minecraft:block/stone", new NeighborPattern(raw)
            );
            assertThat(resolution.isPresent(), is(true));
            selectedTiles.add(resolution.get().textureId());
        }
        assertThat(selectedTiles.size(), equalTo(47));
    }

    @Test
    @DisplayName("CTM: solo pattern (no neighbors) lands on a single deterministic tile")
    void ctmSoloPatternIsDeterministic() {
        CtmRule rule = ctmRule47();
        Optional<CtmResolution> first = CtmMatcher.resolveWithPattern(
            rule, "minecraft:stone", "minecraft:block/stone", NeighborPattern.EMPTY
        );
        Optional<CtmResolution> second = CtmMatcher.resolveWithPattern(
            rule, "minecraft:granite", "minecraft:block/granite", NeighborPattern.EMPTY
        );
        assertThat(first.isPresent(), is(true));
        assertThat(second.isPresent(), is(true));
        assertThat(first.get().textureId(), equalTo(second.get().textureId()));
    }

    @Test
    @DisplayName("resolveWithPattern: tile index clamps to last tile when pack ships fewer than expected")
    void resolveWithPatternClampsShortTileLists() {
        // CTM_COMPACT expects 5 tiles; pack ships only 2. Pattern that would route to interior (index 3)
        // gets clamped to the last tile (index 1).
        CtmRule rule = rule(CtmMethod.CTM_COMPACT, "first", "last");
        Optional<CtmResolution> resolution = CtmMatcher.resolveWithPattern(
            rule, "minecraft:stone", "minecraft:block/stone",
            new NeighborPattern(NeighborPattern.N | NeighborPattern.E | NeighborPattern.S | NeighborPattern.W)
        );
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("last"));
    }

    @Test
    @DisplayName("resolveWithPattern: non-neighbor methods ignore the pattern and delegate to resolve")
    void resolveWithPatternDelegatesNonNeighborMethods() {
        CtmRule rule = rule(CtmMethod.FIXED, "fixed_tile");
        Optional<CtmResolution> resolution = CtmMatcher.resolveWithPattern(
            rule, "minecraft:stone", "minecraft:block/stone",
            new NeighborPattern(NeighborPattern.N | NeighborPattern.E)
        );
        assertThat(resolution.isPresent(), is(true));
        assertThat(resolution.get().textureId(), equalTo("fixed_tile"));
    }

    private static void assertHorizontal(@NotNull CtmRule rule, @NotNull NeighborPattern pattern, @NotNull String expectedTile) {
        Optional<CtmResolution> resolution = CtmMatcher.resolveWithPattern(
            rule, "minecraft:stone", "minecraft:block/stone", pattern
        );
        assertThat("pattern bits=" + pattern.bits(), resolution.isPresent(), is(true));
        assertThat("pattern bits=" + pattern.bits(), resolution.get().textureId(), equalTo(expectedTile));
    }

    private static @NotNull CtmRule ctmRule47() {
        ConcurrentList<String> tiles = Concurrent.newList();
        for (int i = 0; i < 47; i++) tiles.add("ctm/tile_" + i);
        return new CtmRule(
            "ctm.properties", 0, CtmMethod.CTM,
            Concurrent.newList("minecraft:stone", "minecraft:granite"),
            Concurrent.newList(),
            tiles,
            Concurrent.newList(),
            EnumSet.of(CtmRule.Face.ALL)
        );
    }

    private static @NotNull CtmRule rule(@NotNull CtmMethod method, @NotNull String @NotNull ... tileIds) {
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
