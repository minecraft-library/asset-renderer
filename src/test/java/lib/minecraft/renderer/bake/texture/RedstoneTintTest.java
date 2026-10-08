package lib.minecraft.renderer.bake.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.index.IndexedRendererContext;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.PalettedPermutationLoader;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.content.rule.RuleScanner;
import lib.minecraft.renderer.fixture.PackFixtures;
import lib.minecraft.renderer.vanilla.RedstoneTint;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit coverage for {@link RedstoneTint} and the {@link Tints#redstone}
 * resolution it backs. Pins the tint lookup against both a vanilla-only and an override-bearing
 * context so a broken {@link RendererContext#findColorOverride} cannot satisfy both rows at once,
 * and once end to end, from packs on disk through the production context.
 */
@DisplayName("Redstone tint resolution")
class RedstoneTintTest {

    /** The redstone power levels vanilla defines, {@code 0} through {@code 15}. */
    private static final int POWER_LEVELS = 16;

    /**
     * Pins the vanilla row: with no override map every power level resolves to the bundled
     * {@link RedstoneTint#vanilla(int)} entry at its own index, so the table is consulted by power
     * rather than collapsed to one colour.
     */
    @Test
    @DisplayName("Vanilla context returns the bundled COLORS table for every power level")
    void vanillaContextMatchesBundledTable() {
        RendererContext context = stubContext(Map.of());
        for (int power = 0; power < POWER_LEVELS; power++)
            assertThat("power " + power, Tints.redstone(context, power), equalTo(RedstoneTint.vanilla(power)));
    }

    /**
     * Pins the override row against the vanilla one. The synthetic gradient is spaced evenly around
     * the HSV wheel, deliberately unlike vanilla's red ramp, so a leak of the bundled table is
     * visible; and because the two rows are asserted against each other a broken
     * {@link RendererContext#findColorOverride} cannot make both pass.
     */
    @Test
    @DisplayName("Override context returns the per-power override for every power level")
    void overrideContextReturnsOverrideTable() {
        Map<String, Integer> overrides = new HashMap<>();
        for (int power = 0; power < 16; power++)
            overrides.put("redstone." + power, syntheticOverrideForPower(power));
        RendererContext context = stubContext(overrides);

        for (int power = 0; power < POWER_LEVELS; power++)
            assertThat("power " + power, Tints.redstone(context, power), equalTo(syntheticOverrideForPower(power)));
    }

    /** Pins the guard at both ends of the 0..15 power domain, either side of a valid index. */
    @Test
    @DisplayName("Tints.redstone rejects out-of-range power levels")
    void rejectsOutOfRange() {
        RendererContext context = stubContext(Map.of());
        assertThrows(IllegalArgumentException.class, () -> Tints.redstone(context, -1));
        assertThrows(IllegalArgumentException.class, () -> Tints.redstone(context, 16));
    }

    /**
     * Pins that the range guard runs before the pack is consulted. A pack shipping a key for an
     * out-of-range power must not answer it - which is what a lazy {@code orElseGet} over the
     * vanilla lookup would let happen.
     */
    @Test
    @DisplayName("An out-of-range power is rejected even when a pack supplies its key")
    void rejectsOutOfRangeAheadOfThePackOverride() {
        RendererContext context = stubContext(Map.of("redstone.16", 0xFF00FF00));
        assertThrows(IllegalArgumentException.class, () -> Tints.redstone(context, 16));
    }

    /**
     * Pins the tint end to end, from two packs on disk that both ship
     * {@code optifine/color.properties} through the scanned stack rules and the production context.
     * The top pack's file is read whole: the power it writes answers its colour, and a power only
     * the lower pack's file writes answers vanilla's table rather than the lower pack's colour.
     */
    @Test
    @DisplayName("A pack's redstone.<power> reaches the tint through the production context")
    void packFileReachesTheTintThroughTheProductionContext(@TempDir Path tmp) throws IOException {
        ResourcePack lower = PackFixtures.rulePack(PackId.VANILLA, tmp.resolve("vanilla"));
        ResourcePack upper = PackFixtures.rulePack(new PackId("upper"), tmp.resolve("upper"));
        writeColorProperties(tmp.resolve("vanilla"), "redstone.0=0x111111\nredstone.1=0x111111");
        writeColorProperties(tmp.resolve("upper"), "redstone.0=0x222222");

        ConcurrentList<ResourcePack> ascending = Concurrent.newList(lower, upper);
        PackStack stack = PackStack.of(ascending).withRules(RuleScanner.mergeAll(ascending));
        RendererContext context = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), Concurrent.newMap(),
            new TextureSynthesizer(PalettedPermutationLoader.load(stack)), Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());

        assertThat("the top pack's power", Tints.redstone(context, 0), equalTo(0xFF222222));
        assertThat("a power only the lower pack writes", Tints.redstone(context, 1), equalTo(RedstoneTint.vanilla(1)));
    }

    /** Pins the table length the power domain and both rows above are indexed over. */
    @Test
    @DisplayName("The vanilla table has 16 entries")
    void tableHasSixteenEntries() {
        assertThat(RedstoneTint.vanilla(POWER_LEVELS - 1), is(RedstoneTint.vanilla(15)));
        assertThrows(IllegalArgumentException.class, () -> RedstoneTint.vanilla(POWER_LEVELS));
    }

    /**
     * Builds an HSV gradient evenly spaced around the wheel for a given power level. Used as the
     * synthetic override gradient so the test's expected values are derivable rather than baked.
     *
     * @param power the redstone power level
     * @return the opaque ARGB colour for that level
     */
    static int syntheticOverrideForPower(int power) {
        float hue = power / 16f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /**
     * Builds a minimal {@link RendererContext} stub whose every asset lookup returns empty, but
     * whose {@code findColorOverride} honours the supplied override map - the one method
     * {@link Tints#redstone} consults.
     *
     * @param overrides the colour overrides the stub answers with, keyed as {@code redstone.<power>}
     * @return the stub context
     */
    private static @NotNull RendererContext stubContext(@NotNull Map<String, Integer> overrides) {
        return RendererContext.builder()
            .colorOverrides(overrides)
            .build();
    }

    /**
     * Writes a pack's {@code assets/minecraft/optifine/color.properties}.
     *
     * @param packRoot the pack's root directory
     * @param body the file body
     * @throws IOException if the file cannot be written
     */
    private static void writeColorProperties(@NotNull Path packRoot, @NotNull String body) throws IOException {
        Path file = packRoot.resolve("assets/minecraft/optifine/color.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

}
