package lib.minecraft.renderer.bake.texture;

import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.vanilla.Biome;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Resolution-order and {@link Biome.GrassColorModifier} coverage for
 * {@link Tints#biome}, the only production consumer of {@link Biome}. Every case
 * is pure - the colormaps are synthesised in memory, so nothing here reads the vanilla extraction.
 * Each fixture biome is built rather than taken from {@link Biome.Vanilla} so the discriminating
 * value is visible at the assertion instead of in a table the test does not own;
 * {@link Biome#INVENTORY_DEFAULT} is the exception, being itself one of the contracts pinned.
 */
@DisplayName("Tints.biome - priority order and grass modifiers")
class BiomeTintTest {

    /** pixel count of the 256x256 colormap every vanilla biome map ships as */
    private static final int COLORMAP_PIXELS = 256 * 256;

    /** row-major index of the {@code (127, 127)} centre pixel temperature 0.5 / downfall 1.0 resolves to */
    private static final int CENTRE_PIXEL = 127 * 256 + 127;

    /**
     * Pins the two arms that return before any lookup. The biome deliberately carries a grass
     * override, a water override and a {@code DARK_FOREST} modifier, and the context a full set
     * of colormaps, so a target that fell through to any later branch would answer with one of
     * those rather than white.
     */
    @Test
    @DisplayName("NONE and CONSTANT return opaque white before any lookup")
    void untintedAndConstantTargetsReturnWhite() {
        Biome loud = Biome.builder("minecraft:loud")
            .grassColorOverride(0xFF102030)
            .waterColorOverride(0xFF405060)
            .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
            .build();
        RendererContext context = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));

        assertThat("none", Tints.biome(context, TintSource.NONE, loud), is(equalTo(ColorMath.WHITE)));
        assertThat("constant", Tints.biome(context, TintSource.CONSTANT, loud), is(equalTo(ColorMath.WHITE)));
    }

    /**
     * Pins the no-world-context grass point. {@link Biome#INVENTORY_DEFAULT} carries no grass
     * override, so grass falls through to the colormap at temperature {@code 0.5} / downfall
     * {@code 1.0}, which is the centre pixel {@code (127, 127)}. The rest of the map is filled
     * with a decoy, so any other coordinate answers with it instead.
     */
    @Test
    @DisplayName("INVENTORY_DEFAULT grass reads the colormap centre at (127, 127)")
    void inventoryDefaultGrassReadsTheColormapCentre() {
        ColorMap grass = colormapWithCentre(TintSource.GRASS, 0xFFDEC0DE, 0xFF3B7A1E);
        RendererContext context = stubContext(Map.of(TintSource.GRASS, grass));

        assertThat(Tints.biome(context, TintSource.GRASS, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF3B7A1E)));
    }

    /**
     * Pins the other half of the same constant: in hand, foliage and dry foliage are fixed
     * colours rather than colormap samples, carried as biome overrides. All three colormaps are
     * registered and filled with decoys, so a target that sampled one would answer with it.
     */
    @Test
    @DisplayName("INVENTORY_DEFAULT foliage and dry foliage answer fixed overrides, not a colormap")
    void inventoryDefaultFoliageTargetsAnswerFixedOverrides() {
        RendererContext context = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));

        assertThat("foliage", Tints.biome(context, TintSource.FOLIAGE, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF48B518)));
        assertThat("dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF5C3C32)));
    }

    /**
     * Pins the water default. {@link Biome#INVENTORY_DEFAULT} declares no water override, so
     * water answers vanilla's {@code 0xFF3F76E4} - and answers it with every colormap
     * registered, because water names no colormap to sample.
     */
    @Test
    @DisplayName("Water with no override answers the vanilla default and reads no colormap")
    void waterWithoutOverrideAnswersTheVanillaDefault() {
        RendererContext context = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));

        assertThat(Tints.biome(context, TintSource.WATER, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF3F76E4)));
    }

    /**
     * Pins that water takes the biome override and never the grass modifier. The fixture pairs
     * a water override with a {@code SWAMP} modifier, which would replace any colour it reached
     * with {@link Biome#SWAMP_GRASS_WARM}, so the override surviving is the whole claim.
     */
    @Test
    @DisplayName("Water answers the biome override and bypasses the grass modifier")
    void waterAnswersTheBiomeOverrideAndBypassesTheModifier() {
        Biome swampish = Biome.builder("minecraft:swampish")
            .waterColorOverride(0xFF617B64)
            .grassColorModifier(Biome.GrassColorModifier.SWAMP)
            .build();
        RendererContext context = stubContext();

        assertThat(Tints.biome(context, TintSource.WATER, swampish), is(equalTo(0xFF617B64)));
    }

    /**
     * Pins each target reading the colormap it names. The three maps carry three distinct fills, so
     * registering any two under each other's target - the easy mistake between {@code FOLIAGE} and
     * {@code DRY_FOLIAGE} - fails. The biome declares no overrides, so all three targets reach
     * the colormap.
     */
    @Test
    @DisplayName("Each colormap target reads its own map")
    void eachTargetReadsItsOwnColormap() {
        Biome plain = Biome.of("mymod:plain", 0.5f, 1.0f);
        RendererContext context = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));

        assertThat("grass", Tints.biome(context, TintSource.GRASS, plain), is(equalTo(0xFF010203)));
        assertThat("foliage", Tints.biome(context, TintSource.FOLIAGE, plain), is(equalTo(0xFF040506)));
        assertThat("dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, plain), is(equalTo(0xFF070809)));
    }

    /**
     * Pins the last fallback: a colormap target on a context built without the map answers opaque
     * white rather than throwing or answering a missing-texture colour.
     */
    @Test
    @DisplayName("A colormap target with no registered map answers opaque white")
    void missingColormapAnswersWhite() {
        Biome plain = Biome.of("mymod:plain", 0.5f, 1.0f);
        RendererContext context = stubContext();

        assertThat("grass", Tints.biome(context, TintSource.GRASS, plain), is(equalTo(ColorMath.WHITE)));
        assertThat("foliage", Tints.biome(context, TintSource.FOLIAGE, plain), is(equalTo(ColorMath.WHITE)));
        assertThat("dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, plain), is(equalTo(ColorMath.WHITE)));
    }

    /**
     * Pins that the missing-colormap fallback is the target's own default and not a colour the
     * modifier has been over. The biome carries {@code SWAMP}, which replaces whatever reaches it,
     * so a fallback routed through the modifier would answer {@link Biome#SWAMP_GRASS_WARM} - which
     * is what folding the fallback into the sampled arm would do.
     */
    @Test
    @DisplayName("The missing-colormap fallback bypasses the grass modifier")
    void missingColormapFallbackBypassesTheModifier() {
        Biome swampish = Biome.builder("minecraft:swampish")
            .grassColorModifier(Biome.GrassColorModifier.SWAMP)
            .build();
        RendererContext context = stubContext();

        assertThat(Tints.biome(context, TintSource.GRASS, swampish), is(equalTo(ColorMath.WHITE)));
    }

    /**
     * Pins the biome override above the colormap - the badlands / cherry-grove shape, where a
     * hardcoded colour replaces the lookup rather than tinting it.
     */
    @Test
    @DisplayName("A biome colour override beats the colormap")
    void biomeOverrideBeatsTheColormap() {
        Biome hardcoded = Biome.builder("minecraft:hardcoded").grassColorOverride(0xFF90814D).build();
        RendererContext context = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));

        assertThat(Tints.biome(context, TintSource.GRASS, hardcoded), is(equalTo(0xFF90814D)));
    }

    /**
     * Pins that no {@code color.properties} key reaches a biome tint. The context answers a colour
     * under every spelling a per-biome key could take - the target's prefix with the biome's id bare
     * and namespaced - and each target still answers its colormap, its biome override or its
     * default.
     */
    @Test
    @DisplayName("A color.properties key never reaches a biome tint")
    void colorPropertiesKeysNeverReachABiomeTint() {
        Biome hardcoded = Biome.builder("minecraft:hardcoded").grassColorOverride(0xFF90814D).build();
        RendererContext context = RendererContext.builder()
            .colorOverrides(Map.of(
                "grass.hardcoded", 0xFFDEAD01,
                "grass.minecraft:hardcoded", 0xFFDEAD02,
                "foliage.hardcoded", 0xFFDEAD03,
                "dryfoliage.hardcoded", 0xFFDEAD04,
                "water.hardcoded", 0xFFDEAD05))
            .colorMaps(allColormaps(0xFF010203, 0xFF040506, 0xFF070809))
            .build();

        assertThat("grass", Tints.biome(context, TintSource.GRASS, hardcoded), is(equalTo(0xFF90814D)));
        assertThat("foliage", Tints.biome(context, TintSource.FOLIAGE, hardcoded), is(equalTo(0xFF040506)));
        assertThat("dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, hardcoded), is(equalTo(0xFF070809)));
        assertThat("water", Tints.biome(context, TintSource.WATER, hardcoded), is(equalTo(0xFF3F76E4)));
    }

    /**
     * Pins the {@code DARK_FOREST} arm's per-channel decomposition against the single-expression
     * form vanilla writes it in, {@code opaque(((base & 0xFEFEFE) + 0x28340A) >> 1)}, over a
     * channel sweep that includes every value where a per-channel add overflows its byte - the
     * one place a channelwise rewrite could part company with a whole-int one. Each base is
     * built with a zero alpha, so the run also pins that the modifier forces the result opaque
     * instead of carrying the input's alpha.
     */
    @Test
    @DisplayName("The DARK_FOREST modifier reproduces vanilla's mask, add and halve exactly")
    void darkForestModifierMatchesTheSingleExpressionForm() {
        int[] channels = {0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF};
        RendererContext context = stubContext();

        for (int red : channels)
            for (int green : channels)
                for (int blue : channels) {
                    int base = (red << 16) | (green << 8) | blue;
                    Biome dark = Biome.builder("minecraft:darkish")
                        .grassColorOverride(base)
                        .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
                        .build();
                    assertThat("base 0x%08X".formatted(base),
                        Tints.biome(context, TintSource.GRASS, dark),
                        is(equalTo(vanillaDarkForest(base))));
                }
    }

    /**
     * Pins that the modifier runs over a colormap sample and not only over an override. The map
     * is filled uniformly so the assertion does not also depend on the sample coordinate.
     */
    @Test
    @DisplayName("The grass modifier applies to a colormap sample too")
    void grassModifierAppliesToTheColormapSample() {
        Biome dark = Biome.builder("minecraft:darkish")
            .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
            .build();
        ColorMap grass = colormapFilled(TintSource.GRASS, 0xFF3B7A1E);
        RendererContext context = stubContext(Map.of(TintSource.GRASS, grass));

        assertThat(Tints.biome(context, TintSource.GRASS, dark), is(equalTo(vanillaDarkForest(0xFF3B7A1E))));
    }

    /**
     * Pins the {@code SWAMP} arm as a substitution rather than a transform: it discards whatever
     * reached it and answers {@link Biome#SWAMP_GRASS_WARM}, so both grass sources - the colormap
     * and the biome override - end at the same colour. The cold variant needs a world-coordinate
     * noise sample and is unreachable here.
     */
    @Test
    @DisplayName("The SWAMP modifier discards the colormap and the biome override")
    void swampModifierDiscardsEveryGrassSource() {
        Biome swampish = Biome.builder("minecraft:swampish")
            .grassColorModifier(Biome.GrassColorModifier.SWAMP)
            .build();
        Biome overridden = Biome.builder("minecraft:swampish")
            .grassColorOverride(0xFFAB12CD)
            .grassColorModifier(Biome.GrassColorModifier.SWAMP)
            .build();

        RendererContext mapped = stubContext(allColormaps(0xFF010203, 0xFF040506, 0xFF070809));
        RendererContext bare = stubContext();

        assertThat("colormap sample", Tints.biome(mapped, TintSource.GRASS, swampish), is(equalTo(Biome.SWAMP_GRASS_WARM)));
        assertThat("biome override", Tints.biome(bare, TintSource.GRASS, overridden), is(equalTo(Biome.SWAMP_GRASS_WARM)));
    }

    /**
     * Pins the modifier as grass-only. Both fixtures carry a modifier that would be visible if
     * it ran - {@code SWAMP} replaces its input outright and {@code DARK_FOREST} halves it - so
     * foliage and dry foliage answering their own overrides unchanged is the claim.
     */
    @Test
    @DisplayName("Foliage and dry foliage bypass the grass modifier")
    void foliageTargetsBypassTheGrassModifier() {
        Biome dark = Biome.builder("minecraft:darkish")
            .foliageColorOverride(0xFF102030)
            .dryFoliageColorOverride(0xFF405060)
            .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
            .build();
        Biome swampish = Biome.builder("minecraft:swampish")
            .foliageColorOverride(0xFF102030)
            .dryFoliageColorOverride(0xFF405060)
            .grassColorModifier(Biome.GrassColorModifier.SWAMP)
            .build();
        RendererContext context = stubContext();

        assertThat("dark forest foliage", Tints.biome(context, TintSource.FOLIAGE, dark), is(equalTo(0xFF102030)));
        assertThat("dark forest dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, dark), is(equalTo(0xFF405060)));
        assertThat("swamp foliage", Tints.biome(context, TintSource.FOLIAGE, swampish), is(equalTo(0xFF102030)));
        assertThat("swamp dry foliage", Tints.biome(context, TintSource.DRY_FOLIAGE, swampish), is(equalTo(0xFF405060)));
    }

    /**
     * Writes one ARGB pixel big-endian at a pixel index, the layout {@code ColorMapLoader} packs.
     *
     * @param map the raw colormap bytes
     * @param pixelIndex the row-major pixel index
     * @param argb the pixel to write
     */
    private static void writePixel(byte @NotNull [] map, int pixelIndex, int argb) {
        int offset = pixelIndex * Integer.BYTES;
        map[offset] = (byte) (argb >>> 24);
        map[offset + 1] = (byte) (argb >>> 16);
        map[offset + 2] = (byte) (argb >>> 8);
        map[offset + 3] = (byte) argb;
    }

    /**
     * Builds a 256x256 colormap whose every pixel carries one colour, so a sample from it is
     * independent of the coordinate the biome's temperature and downfall resolve to.
     *
     * @param target the tint target the context answers this map for
     * @param argb the colour every pixel carries
     * @return the synthesised colormap
     */
    private static @NotNull ColorMap colormapFilled(@NotNull TintSource target, int argb) {
        byte[] pixels = new byte[COLORMAP_PIXELS * Integer.BYTES];
        for (int index = 0; index < COLORMAP_PIXELS; index++)
            writePixel(pixels, index, argb);
        return new ColorMap("test:colormap/" + target.name(), "test", target, pixels);
    }

    /**
     * Builds a filled colormap with one distinct pixel planted at the centre, so a sample that
     * lands anywhere else answers with the fill instead.
     *
     * @param target the tint target the context answers this map for
     * @param fill the colour every other pixel carries
     * @param centre the colour planted at {@code (127, 127)}
     * @return the synthesised colormap
     */
    private static @NotNull ColorMap colormapWithCentre(@NotNull TintSource target, int fill, int centre) {
        ColorMap map = colormapFilled(target, fill);
        writePixel(map.pixels(), CENTRE_PIXEL, centre);
        return map;
    }

    /**
     * Builds the full set of three colormaps, each uniformly filled with its own colour, so a
     * target reading the wrong map answers with a colour no assertion expects.
     *
     * @param grass the colour filling the grass map
     * @param foliage the colour filling the foliage map
     * @param dryFoliage the colour filling the dry-foliage map
     * @return the three maps keyed by the target each serves
     */
    private static @NotNull Map<TintSource, ColorMap> allColormaps(int grass, int foliage, int dryFoliage) {
        return Map.of(
            TintSource.GRASS, colormapFilled(TintSource.GRASS, grass),
            TintSource.FOLIAGE, colormapFilled(TintSource.FOLIAGE, foliage),
            TintSource.DRY_FOLIAGE, colormapFilled(TintSource.DRY_FOLIAGE, dryFoliage));
    }

    /**
     * Applies vanilla's dark-forest grass modifier in the one-expression form it is written in,
     * {@code opaque(((base & 0xFEFEFE) + 0x28340A) >> 1)}, as the independent reference the
     * production per-channel decomposition is compared against.
     *
     * @param argb the base colour, alpha ignored
     * @return the modified opaque colour
     */
    private static int vanillaDarkForest(int argb) {
        return 0xFF000000 | (((argb & 0xFEFEFE) + 0x28340A) >> 1);
    }

    /**
     * Builds a minimal {@link RendererContext} stub whose every asset lookup returns empty.
     *
     * @return the stub context
     */
    private static @NotNull RendererContext stubContext() {
        return stubContext(Map.of());
    }

    /**
     * Builds a minimal {@link RendererContext} stub whose every asset lookup returns empty, but
     * whose {@code findColorMap} honours the supplied maps - the one lookup {@link Tints#biome}
     * consults.
     *
     * @param colorMaps the colormaps the stub answers with, keyed by the target each serves
     * @return the stub context
     */
    private static @NotNull RendererContext stubContext(@NotNull Map<TintSource, ColorMap> colorMaps) {
        return RendererContext.builder()
            .colorMaps(colorMaps)
            .build();
    }

}
