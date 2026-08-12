package lib.minecraft.renderer.engine.texture;

import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.Block;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.support.StubRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit coverage for {@link Textures#sampleColormap}, {@link Textures#sampleRedstoneTint} and
 * {@link Textures#sampleBiomeTint} - the vanilla-parity biome colormap sampler, the per-power
 * redstone tint resolver and the tint resolution order the first two feed. Pins the
 * temperature/downfall &rarr; {@code (x, y)} index math so a future change to the coordinate formula
 * fails loudly rather than silently shifting sampled tints, pins the big-endian channel order the
 * byte-addressed read depends on, pins the tint lookup against both a vanilla-only and an
 * override-bearing context so a broken {@link RendererContext#findColorOverride} cannot satisfy both
 * rows at once, and pins the four-way priority and the {@link Biome.GrassColorModifier} arms
 * {@code sampleBiomeTint} resolves through.
 */
@DisplayName("Textures colormap sampling + redstone tint resolution + biome tint priority")
class TexturesTest {

    /** pixel count of the 256x256 colormap every vanilla biome map ships as */
    private static final int COLORMAP_PIXELS = 256 * 256;

    /** row-major index of the {@code (127, 127)} centre pixel temperature 0.5 / downfall 1.0 resolves to */
    private static final int CENTRE_PIXEL = 127 * 256 + 127;

    /**
     * Pins the coordinate math against a two-pixel fixture. For {@code temperature=0.5},
     * {@code downfall=1.0}: {@code adjTemp = 0.5}, {@code adjRain = clamp(1.0)*0.5 = 0.5}, so
     * {@code x = floor((1-0.5)*255) = 127} and {@code y = floor((1-0.5)*255) = 127}. The sampler
     * must therefore read the pixel at {@code 127*256+127} ({@code 0xFF112233}), not the decoy
     * planted at the naive-centre {@code 128*256+128} that a temperature-only formula would hit.
     */
    @Test
    @DisplayName("sampleColormap returns the pixel at the expected temp/humidity coordinate")
    void sampleColormapReadsCorrectPixel() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 128 * 256 + 128, 0xFFAABBCC);
        writePixel(map, 127 * 256 + 127, 0xFF112233);

        int sampled = Textures.sampleColormap(map, 0.5f, 1.0f);
        assertThat(sampled, is(equalTo(0xFF112233)));
    }

    /**
     * Pins the channel order. Both fixture values above are chosen so every byte differs, so a
     * little-endian read, or one that sign-extends an unmasked channel, cannot return the expected
     * value by coincidence. {@code 0xFF112233} read little-endian is {@code 0x332211FF}; read with
     * the low three bytes unmasked it is not an ARGB colour at all.
     */
    @Test
    @DisplayName("sampleColormap reads each pixel's four bytes big-endian")
    void sampleColormapReadsBigEndian() {
        byte[] map = new byte[256 * 256 * Integer.BYTES];
        writePixel(map, 127 * 256 + 127, 0x8090A0B0);

        assertThat(Textures.sampleColormap(map, 0.5f, 1.0f), is(equalTo(0x8090A0B0)));
    }

    /**
     * Pins the vanilla row: with no override map every power level resolves to the bundled
     * {@link Textures#REDSTONE_TINTS} entry at its own index, so the table is consulted by power
     * rather than collapsed to one colour.
     */
    @Test
    @DisplayName("Vanilla context returns the bundled COLORS table for every power level")
    void vanillaContextMatchesBundledTable() {
        Textures textures = new Textures(stubContext(Map.of()));
        for (int power = 0; power < Textures.REDSTONE_TINTS.length; power++)
            assertThat("power " + power, textures.sampleRedstoneTint(power), equalTo(Textures.REDSTONE_TINTS[power]));
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
        Textures textures = new Textures(stubContext(overrides));

        for (int power = 0; power < Textures.REDSTONE_TINTS.length; power++)
            assertThat("power " + power, textures.sampleRedstoneTint(power), equalTo(syntheticOverrideForPower(power)));
    }

    /** Pins the guard at both ends of the 0..15 power domain, either side of a valid index. */
    @Test
    @DisplayName("sampleRedstoneTint rejects out-of-range power levels")
    void rejectsOutOfRange() {
        Textures textures = new Textures(stubContext(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> textures.sampleRedstoneTint(-1));
        assertThrows(IllegalArgumentException.class, () -> textures.sampleRedstoneTint(16));
    }

    /** Pins the table length the power domain and both rows above are indexed over. */
    @Test
    @DisplayName("REDSTONE_TINTS table has 16 entries")
    void tableHasSixteenEntries() {
        assertThat(Textures.REDSTONE_TINTS.length, is(16));
    }

    /**
     * Resolution-order and {@link Biome.GrassColorModifier} coverage for
     * {@link Textures#sampleBiomeTint}, the only production consumer of {@link Biome}. Every case
     * is pure - the colormaps are synthesised in memory, so nothing here reads the vanilla
     * extraction. Each fixture biome is built rather than taken from {@link Biome.Vanilla} so the
     * discriminating value is visible at the assertion instead of in a table the test does not own;
     * {@link Biome#INVENTORY_DEFAULT} is the exception, being itself one of the contracts pinned.
     */
    @Nested
    @DisplayName("sampleBiomeTint - priority order and grass modifiers")
    class BiomeTintSampling {

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
            Textures textures = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat("none", textures.sampleBiomeTint(Block.TintTarget.NONE, loud), is(equalTo(ColorMath.WHITE)));
            assertThat("constant", textures.sampleBiomeTint(Block.TintTarget.CONSTANT, loud), is(equalTo(ColorMath.WHITE)));
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
            ColorMap grass = colormapWithCentre(ColorMap.Type.GRASS, 0xFFDEC0DE, 0xFF3B7A1E);
            Textures textures = new Textures(stubContext(Map.of(), Map.of(ColorMap.Type.GRASS, grass)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.GRASS, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF3B7A1E)));
        }

        /**
         * Pins the other half of the same constant: in hand, foliage and dry foliage are fixed
         * colours rather than colormap samples, carried as biome overrides. All three colormaps are
         * registered and filled with decoys, so a target that sampled one would answer with it.
         */
        @Test
        @DisplayName("INVENTORY_DEFAULT foliage and dry foliage answer fixed overrides, not a colormap")
        void inventoryDefaultFoliageTargetsAnswerFixedOverrides() {
            Textures textures = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat("foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF48B518)));
            assertThat("dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF5C3C32)));
        }

        /**
         * Pins the water default. {@link Biome#INVENTORY_DEFAULT} declares no water override, so
         * water answers vanilla's {@code 0xFF3F76E4} - and answers it with every colormap
         * registered, because water short-circuits the colormap path entirely.
         */
        @Test
        @DisplayName("Water with no override answers the vanilla default and reads no colormap")
        void waterWithoutOverrideAnswersTheVanillaDefault() {
            Textures textures = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.WATER, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF3F76E4)));
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
            Textures textures = new Textures(stubContext(Map.of()));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.WATER, swampish), is(equalTo(0xFF617B64)));
        }

        /**
         * Pins the switch from tint target to {@link ColorMap.Type}. The three maps carry three
         * distinct fills, so swapping any two arms - the easy mistake between {@code FOLIAGE} and
         * {@code DRY_FOLIAGE} - fails. The biome declares no overrides, so all three targets reach
         * the colormap.
         */
        @Test
        @DisplayName("Each colormap target reads its own map")
        void eachTargetReadsItsOwnColormap() {
            Biome plain = Biome.of("mymod:plain", 0.5f, 1.0f);
            Textures textures = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat("grass", textures.sampleBiomeTint(Block.TintTarget.GRASS, plain), is(equalTo(0xFF010203)));
            assertThat("foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, plain), is(equalTo(0xFF040506)));
            assertThat("dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, plain), is(equalTo(0xFF070809)));
        }

        /**
         * Pins the last fallback: a colormap target whose map no pack supplies answers opaque white
         * rather than throwing or answering a missing-texture colour.
         */
        @Test
        @DisplayName("A colormap target with no registered map answers opaque white")
        void missingColormapAnswersWhite() {
            Biome plain = Biome.of("mymod:plain", 0.5f, 1.0f);
            Textures textures = new Textures(stubContext(Map.of()));

            assertThat("grass", textures.sampleBiomeTint(Block.TintTarget.GRASS, plain), is(equalTo(ColorMath.WHITE)));
            assertThat("foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, plain), is(equalTo(ColorMath.WHITE)));
            assertThat("dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, plain), is(equalTo(ColorMath.WHITE)));
        }

        /**
         * Pins the biome override above the colormap - the badlands / cherry-grove shape, where a
         * hardcoded colour replaces the lookup rather than tinting it.
         */
        @Test
        @DisplayName("A biome colour override beats the colormap")
        void biomeOverrideBeatsTheColormap() {
            Biome hardcoded = Biome.builder("minecraft:hardcoded").grassColorOverride(0xFF90814D).build();
            Textures textures = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.GRASS, hardcoded), is(equalTo(0xFF90814D)));
        }

        /**
         * Pins the pack override key grammar: one prefix per target - {@code grass.},
         * {@code foliage.}, {@code dryfoliage.} (no separator inside the word), {@code water.} -
         * followed by the biome's local name with the namespace dropped. The map plants a fifth
         * entry under the un-stripped {@code grass.minecraft:dark_forest}, so a key built from the
         * whole id answers with a value no assertion expects.
         */
        @Test
        @DisplayName("The pack override key is the target prefix plus the biome's local name")
        void packOverrideKeyIsPrefixPlusLocalName() {
            Biome dark = Biome.of("minecraft:dark_forest", 0.7f, 0.8f);
            Map<String, Integer> overrides = Map.of(
                "grass.dark_forest", 0xFF11AA11,
                "foliage.dark_forest", 0xFF22AA22,
                "dryfoliage.dark_forest", 0xFF33AA33,
                "water.dark_forest", 0xFF44AA44,
                "grass.minecraft:dark_forest", 0xFFDEAD00);
            Textures textures = new Textures(stubContext(overrides, allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat("grass", textures.sampleBiomeTint(Block.TintTarget.GRASS, dark), is(equalTo(0xFF11AA11)));
            assertThat("foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, dark), is(equalTo(0xFF22AA22)));
            assertThat("dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, dark), is(equalTo(0xFF33AA33)));
            assertThat("water", textures.sampleBiomeTint(Block.TintTarget.WATER, dark), is(equalTo(0xFF44AA44)));
        }

        /**
         * Pins the no-colon arm of the same key math against the one shipped biome that has no
         * namespace - {@link Biome#INVENTORY_DEFAULT}'s id is the bare {@code inventory_default},
         * so a substring taken past a colon that is not there would break it.
         */
        @Test
        @DisplayName("A biome id with no namespace is used whole in the pack override key")
        void unnamespacedBiomeIdIsUsedWholeInTheKey() {
            Textures textures = new Textures(stubContext(Map.of("grass.inventory_default", 0xFF5599FF)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.GRASS, Biome.INVENTORY_DEFAULT), is(equalTo(0xFF5599FF)));
        }

        /**
         * Pins the top of the priority order. The fixture stacks all three sources - a pack
         * override, a biome override and a registered colormap - on one target, so only the pack
         * override answering proves the order rather than an accident of which sources are absent.
         */
        @Test
        @DisplayName("A pack override beats both the biome override and the colormap")
        void packOverrideBeatsBiomeOverrideAndColormap() {
            Biome hardcoded = Biome.builder("minecraft:hardcoded").grassColorOverride(0xFF90814D).build();
            Textures textures = new Textures(stubContext(
                Map.of("grass.hardcoded", 0xFF0000FF),
                allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.GRASS, hardcoded), is(equalTo(0xFF0000FF)));
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
            Textures textures = new Textures(stubContext(Map.of()));

            for (int red : channels)
                for (int green : channels)
                    for (int blue : channels) {
                        int base = (red << 16) | (green << 8) | blue;
                        Biome dark = Biome.builder("minecraft:darkish")
                            .grassColorOverride(base)
                            .grassColorModifier(Biome.GrassColorModifier.DARK_FOREST)
                            .build();
                        assertThat("base 0x%08X".formatted(base),
                            textures.sampleBiomeTint(Block.TintTarget.GRASS, dark),
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
            ColorMap grass = colormapFilled(ColorMap.Type.GRASS, 0xFF3B7A1E);
            Textures textures = new Textures(stubContext(Map.of(), Map.of(ColorMap.Type.GRASS, grass)));

            assertThat(textures.sampleBiomeTint(Block.TintTarget.GRASS, dark), is(equalTo(vanillaDarkForest(0xFF3B7A1E))));
        }

        /**
         * Pins the {@code SWAMP} arm as a substitution rather than a transform: it discards whatever
         * reached it and answers {@link Biome#SWAMP_GRASS_WARM}, so all three grass sources - the
         * colormap, the biome override and the pack override - end at the same colour. The pack row
         * is the observed behaviour and the one easiest to read as a bug: a pack that recolours
         * swamp grass has its value dropped, because the modifier runs after the override rather
         * than instead of it. The cold variant needs a world-coordinate noise sample and is
         * unreachable here.
         */
        @Test
        @DisplayName("The SWAMP modifier discards the colormap, the biome override and the pack override")
        void swampModifierDiscardsEveryGrassSource() {
            Biome swampish = Biome.builder("minecraft:swampish")
                .grassColorModifier(Biome.GrassColorModifier.SWAMP)
                .build();
            Biome overridden = Biome.builder("minecraft:swampish")
                .grassColorOverride(0xFFAB12CD)
                .grassColorModifier(Biome.GrassColorModifier.SWAMP)
                .build();

            Textures mapped = new Textures(stubContext(Map.of(), allColormaps(0xFF010203, 0xFF040506, 0xFF070809)));
            Textures bare = new Textures(stubContext(Map.of()));
            Textures packed = new Textures(stubContext(Map.of("grass.swampish", 0xFFAB12CD)));

            assertThat("colormap sample", mapped.sampleBiomeTint(Block.TintTarget.GRASS, swampish), is(equalTo(Biome.SWAMP_GRASS_WARM)));
            assertThat("biome override", bare.sampleBiomeTint(Block.TintTarget.GRASS, overridden), is(equalTo(Biome.SWAMP_GRASS_WARM)));
            assertThat("pack override", packed.sampleBiomeTint(Block.TintTarget.GRASS, swampish), is(equalTo(Biome.SWAMP_GRASS_WARM)));
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
            Textures textures = new Textures(stubContext(Map.of()));

            assertThat("dark forest foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, dark), is(equalTo(0xFF102030)));
            assertThat("dark forest dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, dark), is(equalTo(0xFF405060)));
            assertThat("swamp foliage", textures.sampleBiomeTint(Block.TintTarget.FOLIAGE, swampish), is(equalTo(0xFF102030)));
            assertThat("swamp dry foliage", textures.sampleBiomeTint(Block.TintTarget.DRY_FOLIAGE, swampish), is(equalTo(0xFF405060)));
        }

    }

    /**
     * Writes one ARGB pixel big-endian at a pixel index, the layout {@code ColorMapLoader} packs.
     *
     * @param map the raw colormap bytes
     * @param pixelIndex the row-major pixel index
     * @param argb the pixel to write
     */
    private static void writePixel(byte[] map, int pixelIndex, int argb) {
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
     * @param type the colormap kind the context answers this map for
     * @param argb the colour every pixel carries
     * @return the synthesised colormap
     */
    private static @NotNull ColorMap colormapFilled(@NotNull ColorMap.Type type, int argb) {
        byte[] pixels = new byte[COLORMAP_PIXELS * Integer.BYTES];
        for (int index = 0; index < COLORMAP_PIXELS; index++)
            writePixel(pixels, index, argb);
        return new ColorMap("test:colormap/" + type.name(), "test", type, pixels);
    }

    /**
     * Builds a filled colormap with one distinct pixel planted at the centre, so a sample that
     * lands anywhere else answers with the fill instead.
     *
     * @param type the colormap kind the context answers this map for
     * @param fill the colour every other pixel carries
     * @param centre the colour planted at {@code (127, 127)}
     * @return the synthesised colormap
     */
    private static @NotNull ColorMap colormapWithCentre(@NotNull ColorMap.Type type, int fill, int centre) {
        ColorMap map = colormapFilled(type, fill);
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
     * @return the three maps keyed by kind
     */
    private static @NotNull Map<ColorMap.Type, ColorMap> allColormaps(int grass, int foliage, int dryFoliage) {
        return Map.of(
            ColorMap.Type.GRASS, colormapFilled(ColorMap.Type.GRASS, grass),
            ColorMap.Type.FOLIAGE, colormapFilled(ColorMap.Type.FOLIAGE, foliage),
            ColorMap.Type.DRY_FOLIAGE, colormapFilled(ColorMap.Type.DRY_FOLIAGE, dryFoliage));
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
     * {@link Textures#sampleRedstoneTint} consults.
     *
     * @param overrides the colour overrides the stub answers with, keyed as {@code redstone.<power>}
     * @return the stub context
     */
    static @NotNull RendererContext stubContext(@NotNull Map<String, Integer> overrides) {
        return stubContext(overrides, Map.of());
    }

    /**
     * Builds a minimal {@link RendererContext} stub whose every asset lookup returns empty, but
     * whose {@code findColorOverride} and {@code findColorMap} honour the supplied maps - the two
     * methods {@link Textures#sampleBiomeTint} consults.
     *
     * @param overrides the colour overrides the stub answers with, keyed by their
     *     {@code color.properties} key
     * @param colorMaps the colormaps the stub answers with, keyed by kind
     * @return the stub context
     */
    static @NotNull RendererContext stubContext(
        @NotNull Map<String, Integer> overrides,
        @NotNull Map<ColorMap.Type, ColorMap> colorMaps
    ) {
        return StubRendererContext.builder()
            .colorOverrides(overrides)
            .colorMaps(colorMaps)
            .build();
    }

}
