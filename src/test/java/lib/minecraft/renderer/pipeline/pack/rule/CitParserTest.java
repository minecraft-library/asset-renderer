package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link CitParser} - the four-form texture path resolution (incl. the filename default),
 * the fail-closed parse polarity, and each parse edge case as a regression. With only vanilla loaded
 * the whole rule layer is inert, so these fixtures are the sole exercise of the CIT grammar.
 */
class CitParserTest {

    private static final @NotNull ResourceId RULE_ID = new ResourceId("minecraft", "optifine/cit/swords/excalibur.properties");
    private static final @NotNull String PROPS_DIR = "optifine/cit/swords";
    private static final @NotNull String CIT_ROOT = "optifine/cit";

    // --- four-form path resolution -------------------------------------------------------

    @Test
    @DisplayName("absent texture resolves to the <basename>.png filename default next to the props file")
    void filenameDefault() {
        assertThat(CitParser.resolveTexturePath(null, PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("minecraft:optifine/cit/swords/excalibur"));
    }

    @Test
    @DisplayName("a bare name resolves under optifine/cit, NOT to minecraft:<name> (defect #7)")
    void bareName() {
        assertThat(CitParser.resolveTexturePath("blade", PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("minecraft:optifine/cit/blade"));
    }

    @Test
    @DisplayName("a ./name resolves relative to the props file's own directory")
    void dotRelative() {
        assertThat(CitParser.resolveTexturePath("./blade", PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("minecraft:optifine/cit/swords/blade"));
    }

    @Test
    @DisplayName("a slashed path resolves relative to assets/minecraft/, folding a textures/ prefix into the id")
    void slashedRelativeToAssets() {
        assertThat(CitParser.resolveTexturePath("mypack/excalibur.png", PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("minecraft:mypack/excalibur"));
        assertThat(CitParser.resolveTexturePath("textures/item/sword.png", PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("minecraft:item/sword"));
    }

    @Test
    @DisplayName("an explicitly namespaced value is kept verbatim")
    void namespaced() {
        assertThat(CitParser.resolveTexturePath("mypack:item/sword", PROPS_DIR, CIT_ROOT, "excalibur").id(),
            equalTo("mypack:item/sword"));
    }

    @Test
    @DisplayName("a rule with no texture, no model, and items still gets the filename-default layer0 (defect #7)")
    void ruleWithoutTextureGetsFilenameDefault() {
        Properties props = props("items", "diamond_sword");
        CitRule rule = parse(props).orElseThrow();
        assertThat(rule.output().texture().isPresent(), is(true));
        assertThat(rule.output().texture().get().id(), equalTo("minecraft:optifine/cit/swords/excalibur"));
    }

    // --- parse edge cases -----------------------------------------------------------------

    @Test
    @DisplayName("#5 damage is a value/range list with % and mask, not a single range")
    void damageListPercentMask() {
        CitRule rule = parse(props("items", "diamond_sword", "damage", "1,3,5-7", "damageMask", "0xF")).orElseThrow();
        DamageSpec damage = rule.damage().orElseThrow();
        assertThat(damage.percent(), is(false));
        assertThat(damage.mask(), equalTo(0xF));
        assertThat(damage.ranges().contains(3), is(true));
        assertThat(damage.ranges().contains(6), is(true));
        assertThat(damage.ranges().contains(4), is(false));

        DamageSpec percent = parse(props("items", "diamond_sword", "damage", "50%")).orElseThrow().damage().orElseThrow();
        assertThat(percent.percent(), is(true));
    }

    @Test
    @DisplayName("#2/#3 enchantments read the modern list and enchantmentLevels is a plain range list")
    void enchantmentModernAndLevels() {
        CitRule rule = parse(props("items", "diamond_sword", "enchantments", "sharpness looting", "enchantmentLevels", "3-5")).orElseThrow();
        EnchantmentSpec ench = rule.enchantments().orElseThrow();
        assertThat(ench.ids().stream().map(ResourceId::id).toList(), equalTo(java.util.List.of("minecraft:sharpness", "minecraft:looting")));
        assertThat(ench.levels().orElseThrow().contains(4), is(true));
        assertThat(ench.levels().orElseThrow().contains(2), is(false));
    }

    @Test
    @DisplayName("#3 legacy enchantmentIDs is read as an alias for enchantments")
    void enchantmentLegacyAlias() {
        CitRule rule = parse(props("items", "diamond_sword", "enchantmentIDs", "unbreaking")).orElseThrow();
        assertThat(rule.enchantments().orElseThrow().ids().getFirst().id(), equalTo("minecraft:unbreaking"));
    }

    @Test
    @DisplayName("#9 nbt prefixes parse to first-class predicates, and ! negates")
    void nbtPredicatePrefixes() {
        CitRule range = parse(props("items", "diamond_sword", "nbt.foo", "range:1-5")).orElseThrow();
        assertThat(range.nbtRules().getFirst().predicate(), instanceOf(NbtPredicate.Range.class));

        CitRule exists = parse(props("items", "diamond_sword", "nbt.foo", "exists:true")).orElseThrow();
        assertThat(exists.nbtRules().getFirst().predicate(), instanceOf(NbtPredicate.Exists.class));

        CitRule negated = parse(props("items", "diamond_sword", "nbt.foo", "!bar")).orElseThrow();
        assertThat(negated.nbtRules().getFirst().negated(), is(true));

        CitRule raw = parse(props("items", "diamond_sword", "nbt.foo", "raw:pattern:*x*")).orElseThrow();
        assertThat(raw.nbtRules().getFirst().predicate(), instanceOf(NbtPredicate.Raw.class));
    }

    @Test
    @DisplayName("#9 a bracketed-negative range predicate parses without dropping the rule")
    void bracketedNegativeRangeKeepsRule() {
        CitRule rule = parse(props("items", "diamond_sword", "nbt.foo", "range:(-10)-10")).orElseThrow();
        assertThat(rule.nbtRules().getFirst().predicate(), instanceOf(NbtPredicate.Range.class));
    }

    @Test
    @DisplayName("texture.<name> parses into the sub-texture map and model into the model override")
    void subTexturesAndModel() {
        CitRule rule = parse(props("items", "diamond_sword", "texture.layer1", "overlay", "model", "custom")).orElseThrow();
        assertThat(rule.output().subTextures().getOptional("layer1").isPresent(), is(true));
        assertThat(rule.output().model().isPresent(), is(true));
        // A model-only-plus-sub rule leaves layer0 untouched (no filename default).
        assertThat(rule.output().texture().isPresent(), is(false));
    }

    @Test
    @DisplayName("hand=off parses to Hand.OFF")
    void handOff() {
        assertThat(parse(props("items", "diamond_sword", "hand", "off")).orElseThrow().hand(), equalTo(Hand.OFF));
    }

    @Test
    @DisplayName("components.<name> and legacy nbt.display.Name both target components.minecraft:custom_name")
    void componentsAndLegacyDisplayName() {
        CitRule modern = parse(props("items", "diamond_sword", "components.minecraft:custom_name", "Excalibur")).orElseThrow();
        assertThat(pathString(modern.nbtRules().getFirst().path()), equalTo("components/minecraft:custom_name"));

        CitRule legacy = parse(props("items", "diamond_sword", "nbt.display.Name", "Excalibur")).orElseThrow();
        assertThat(pathString(legacy.nbtRules().getFirst().path()), equalTo("components/minecraft:custom_name"));
    }

    // --- fail-closed ---------------------------------------------------------------------

    @Test
    @DisplayName("an unparseable damage rejects the whole rule (fail-closed)")
    void unparseableDamageRejectsRule() {
        assertThat(parse(props("items", "diamond_sword", "damage", "not-a-number")).isPresent(), is(false));
    }

    @Test
    @DisplayName("an unparseable weight rejects the whole rule (fail-closed)")
    void unparseableWeightRejectsRule() {
        assertThat(parse(props("items", "diamond_sword", "weight", "heavy")).isPresent(), is(false));
    }

    @Test
    @DisplayName("an unparseable enchantmentLevels rejects the whole rule (fail-closed)")
    void unparseableLevelsRejectsRule() {
        assertThat(parse(props("items", "diamond_sword", "enchantments", "sharpness", "enchantmentLevels", "high")).isPresent(), is(false));
    }

    @Test
    @DisplayName("an item rule matching no items is rejected")
    void noItemsRejected() {
        assertThat(parse(props("texture", "blade")).isPresent(), is(false));
    }

    // --- potion synthesis ----------------------------------------------------------------

    @Test
    @DisplayName("a potion shortcut synthesises a rule on the splash_potion item matching the effect")
    void potionSynthesis() {
        ResourceId texture = new ResourceId("minecraft", "optifine/cit/potion/splash/strength");
        CitRule rule = CitParser.synthesisePotion(texture, RULE_ID, PackId.VANILLA, new ResourceId("minecraft", "splash_potion"), "strength");
        assertThat(rule.items().getFirst().id(), equalTo("minecraft:splash_potion"));
        assertThat(rule.output().texture().orElseThrow().id(), equalTo("minecraft:optifine/cit/potion/splash/strength"));
        assertThat(rule.nbtRules().getFirst().predicate(), instanceOf(NbtPredicate.Exact.class));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static @NotNull Optional<CitRule> parse(@NotNull Properties props) {
        return CitParser.parse(props, RULE_ID, PackId.VANILLA, PROPS_DIR, CIT_ROOT);
    }

    private static @NotNull Properties props(@NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return props;
    }

    /** Renders an NbtPath as a {@code /}-joined step string for assertions. */
    private static @NotNull String pathString(@NotNull NbtPath path) {
        StringBuilder builder = new StringBuilder();
        for (NbtPath.Step step : path.steps()) {
            if (!builder.isEmpty()) builder.append('/');
            builder.append(switch (step) {
                case NbtPath.Key key -> key.name();
                case NbtPath.Index index -> Integer.toString(index.value());
                case NbtPath.Wildcard ignored -> "*";
                case NbtPath.Count ignored -> "count";
            });
        }
        return builder.toString();
    }

}
