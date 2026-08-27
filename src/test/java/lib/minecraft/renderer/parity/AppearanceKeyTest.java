package lib.minecraft.renderer.parity;

import lib.minecraft.renderer.asset.DyeColor;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.appearance.HorseMarking;
import lib.minecraft.renderer.asset.appearance.Size;
import lib.minecraft.renderer.asset.appearance.TintAxis;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.option.AppearanceOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * The grammar's own rules, over a hand-built index rather than the shipped one, so a failure here is
 * the grammar and never the data.
 */
@DisplayName("AppearanceKey grammar")
final class AppearanceKeyTest {

    /**
     * Builds a model whose axes carry only the members these fixtures populate, every other empty.
     *
     * <p>Each argument is labelled once here and the three fixtures below name theirs rather than
     * repeating the shape. The transposition hazard that made that necessary is mostly gone - the
     * three axes are distinct types now, so swapping two of them does not compile - but the four
     * baby / shape members ahead of them are still interchangeable {@code Optional.empty()}s.
     *
     * @param id the entity id, namespaced
     * @param variants the coat options, keyed by coat name
     * @param variantDefault the declared coat default
     * @param sizeDefault the declared size default
     * @return the model
     */
    private static Entity model(String id, Map<String, Entity> variants,
                                Optional<String> variantDefault, Optional<Size> sizeDefault) {
        return Entity.builder()
            .id(ResourceId.parse(id))
            .model(new EntityModelData())
            .axes(new Entity.Axes(
                Optional.empty(),  // babyModel
                Optional.empty(),  // babyPose
                List.of(),         // babyOverlays
                Entity.Axis.none(),                                 // shape
                Entity.Axis.none(),                                 // state
                new Entity.Axis<>(Map.of(), sizeDefault),           // size
                new Entity.Axis<>(variants, variantDefault)))       // variant
            .build();
    }

    /** A model with no axes at all - the plain case a bare head resolves to. */
    private static Entity plain(String id) {
        return model(id, Map.of(), Optional.empty(), Optional.empty());
    }

    /** A model carrying a variant axis with the given options and declared default. */
    private static Entity variantModel(String id, String defaultOption, String... options) {
        Map<String, Entity> coats = new LinkedHashMap<>();
        for (String option : options) coats.put(option, plain(id));
        return model(id, Map.copyOf(coats), Optional.of(defaultOption), Optional.empty());
    }

    /** A model carrying a size axis with a declared default. */
    private static Entity sizedModel(String id, Size defaultSize) {
        return model(id, Map.of(), Optional.empty(), Optional.of(defaultSize));
    }

    private static final AppearanceCodec CODEC = AppearanceCodec.over(Map.of(
        "minecraft:zombie", plain("minecraft:zombie"),
        "minecraft:wolf_spawn_egg", plain("minecraft:wolf_spawn_egg"),
        "minecraft:wolf", variantModel("minecraft:wolf", "pale", "pale", "ashen", "spotted"),
        "minecraft:nautilus", variantModel("minecraft:nautilus", "warm", "warm", "cold"),
        "minecraft:zombie_nautilus", variantModel("minecraft:zombie_nautilus", "warm", "warm", "cold"),
        "minecraft:pufferfish", sizedModel("minecraft:pufferfish", Size.LARGE)));

    private static AppearanceKey parsed(String name) {
        AppearanceKey.Result result = CODEC.parse(name);
        assertThat(name, result, is(instanceOf(AppearanceKey.Result.Parsed.class)));
        return ((AppearanceKey.Result.Parsed) result).key();
    }

    private static AppearanceKey.Result.Malformed rejected(String name) {
        AppearanceKey.Result result = CODEC.parse(name);
        assertThat(name, result, is(instanceOf(AppearanceKey.Result.Malformed.class)));
        return (AppearanceKey.Result.Malformed) result;
    }

    /** The head, the one part of a name that is not self-delimiting and needs the index to settle. */
    @Nested
    @DisplayName("the head")
    final class Head {

        @Test
        @DisplayName("a model with no variant axis takes the whole path, underscores and all")
        void plainHeadTakesWholePath() {
            assertThat(parsed("minecraft__wolf_spawn_egg.png").entityId(), is(equalTo("minecraft:wolf_spawn_egg")));
            assertThat(parsed("minecraft__wolf_spawn_egg.png").variant(), is(equalTo(Optional.empty())));
        }

        @Test
        @DisplayName("a variant family splits its coat off the tail")
        void variantHeadSplitsCoat() {
            AppearanceKey key = parsed("minecraft__wolf_ashen.png");
            assertThat(key.entityId(), is(equalTo("minecraft:wolf")));
            assertThat(key.variant(), is(equalTo(Optional.of("ashen"))));
        }

        @Test
        @DisplayName("the longest family wins, so a shorter one that is a prefix does not steal the name")
        void longestFamilyWins() {
            AppearanceKey key = parsed("minecraft__zombie_nautilus_cold.png");
            assertThat(key.entityId(), is(equalTo("minecraft:zombie_nautilus")));
            assertThat(key.variant(), is(equalTo(Optional.of("cold"))));
        }

        @Test
        @DisplayName("a variant family has no bare form - the coat is always named")
        void bareVariantFamilyIsRejected() {
            assertThat(rejected("minecraft__wolf.png").reason(), containsString("resolves to no model"));
        }

        @Test
        @DisplayName("a head naming no model is rejected rather than dropped")
        void unknownHeadIsRejected() {
            assertThat(rejected("minecraft__pheasant.png").reason(), containsString("resolves to no model"));
        }

        @Test
        @DisplayName("a head with no namespace separator is rejected")
        void headNeedsNamespace() {
            assertThat(rejected("zombie.png").reason(), containsString("resolves to no model"));
        }
    }

    /** The token vocabulary, and every spelling it refuses rather than reads past. */
    @Nested
    @DisplayName("tokens")
    final class Tokens {

        @Test
        @DisplayName("an unknown axis is rejected, not ignored")
        void unknownAxisIsRejected() {
            assertThat(rejected("minecraft__zombie~mood=grumpy.png").reason(), containsString("unknown axis 'mood'"));
        }

        @Test
        @DisplayName("a token with no '=' is rejected")
        void tokenNeedsSeparator() {
            assertThat(rejected("minecraft__zombie~sheared.png").reason(), containsString("no '='"));
        }

        @Test
        @DisplayName("a value outside its axis vocabulary is rejected")
        void unknownValueIsRejected() {
            assertThat(rejected("minecraft__zombie~markings=stripes.png").reason(),
                containsString("unknown markings 'stripes'"));
        }

        @Test
        @DisplayName("a duplicate token is rejected rather than resolved last-wins")
        void duplicateTokenIsRejected() {
            assertThat(rejected("minecraft__zombie~age=baby~age=baby.png").reason(),
                containsString("duplicate token"));
        }

        @Test
        @DisplayName("a repeated non-repeatable axis is rejected")
        void repeatedAxisIsRejected() {
            assertThat(rejected("minecraft__zombie~markings=white~markings=black.png").reason(),
                containsString("repeated"));
        }

        @Test
        @DisplayName("an uppercase character is rejected, because a case-blind filesystem would collide")
        void uppercaseIsRejected() {
            assertThat(rejected("minecraft__zombie~markings=WHITE.png").reason(), containsString("uppercase"));
        }

        @Test
        @DisplayName("a value splits at the first '=' only, so a value may contain one")
        void valueSplitsAtFirstSeparator() {
            assertThat(parsed("minecraft__zombie~toggle=a=b.png").appearance().getToggles(),
                is(equalTo(Set.of("a=b"))));
        }

        @Test
        @DisplayName("the .png suffix is stripped literally, so a value may contain a dot")
        void suffixIsStrippedLiterally() {
            assertThat(parsed("minecraft__zombie~equip=body.diamond.png").appearance().getEquipment(),
                is(equalTo(Map.of("body", "diamond"))));
        }
    }

    /** One name per appearance and one appearance per name, which is what makes a name a gate. */
    @Nested
    @DisplayName("ordering and round-trip")
    final class RoundTrip {

        @Test
        @DisplayName("tokens are emitted in ascending order of the whole token text")
        void tokensSortByWholeText() {
            AppearanceOptions appearance = AppearanceOptions.builder()
                .markings(HorseMarking.WHITE)
                .sheared(true)
                .toggles(Set.of("show_arms", "chest"))
                .build();
            assertThat(CODEC.format(new AppearanceKey("minecraft:zombie", Optional.empty(), appearance, Optional.empty())),
                is(equalTo("minecraft__zombie~markings=white~sheared=true~toggle=chest~toggle=show_arms")));
        }

        @Test
        @DisplayName("an unordered set of toggles has exactly one spelling")
        void repeatableAxesSelfOrder() {
            String a = CODEC.format(new AppearanceKey("minecraft:zombie", Optional.empty(),
                AppearanceOptions.builder().toggles(Set.of("chest", "show_arms")).build(), Optional.empty()));
            String b = CODEC.format(new AppearanceKey("minecraft:zombie", Optional.empty(),
                AppearanceOptions.builder().toggles(Set.of("show_arms", "chest")).build(), Optional.empty()));
            assertThat(a, is(equalTo(b)));
        }

        @Test
        @DisplayName("a size equal to the model's declared default is never written")
        void declaredDefaultSizeIsNotWritten() {
            AppearanceKey atDefault = new AppearanceKey("minecraft:pufferfish", Optional.empty(),
                AppearanceOptions.builder().size(Optional.of(Size.LARGE)).build(), Optional.empty());
            assertThat(CODEC.format(atDefault), is(equalTo("minecraft__pufferfish")));

            AppearanceKey offDefault = new AppearanceKey("minecraft:pufferfish", Optional.empty(),
                AppearanceOptions.builder().size(Optional.of(Size.SMALL)).build(), Optional.empty());
            assertThat(CODEC.format(offDefault), is(equalTo("minecraft__pufferfish~size=small")));
        }

        @Test
        @DisplayName("a dye round-trips through its name")
        void dyeRoundTrips() {
            AppearanceKey key = new AppearanceKey("minecraft:zombie", Optional.empty(),
                AppearanceOptions.builder().tints(Map.of(TintAxis.COLLAR, DyeColor.Vanilla.LIGHT_BLUE)).build(),
                Optional.empty());
            String name = CODEC.format(key);
            assertThat(name, is(equalTo("minecraft__zombie~collar_color=light_blue")));
            assertThat(parsed(name).appearance().getTints(),
                is(equalTo(Map.of(TintAxis.COLLAR, DyeColor.Vanilla.LIGHT_BLUE))));
        }

        @Test
        @DisplayName("formatting a parsed name reproduces it exactly")
        void formatOfParseIsIdentity() {
            List<String> names = List.of(
                "minecraft__zombie",
                "minecraft__wolf_ashen",
                "minecraft__zombie_nautilus_cold",
                "minecraft__zombie~age=baby",
                "minecraft__zombie~equip=body.diamond~equip=saddle",
                "minecraft__zombie~markings=white~sheared=true~toggle=chest~toggle=show_arms",
                "minecraft__pufferfish~size=small");
            for (String name : names)
                assertThat(name, CODEC.format(parsed(name)), is(equalTo(name)));
        }
    }
}
