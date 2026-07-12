package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link GlintEvaluator} - the CIT glint decision (03-rules §7, D3.9): the highest-precedence
 * matching {@code type=enchantment} rule wins with {@link GlintPolicy.Replaced}, else a merged
 * {@code useGlint == false} suppresses, else the default vanilla glint. Also pins the
 * empty-{@code items} match that a {@code type=enchantment} glint rule relies on.
 */
class GlintEvaluatorTest {

    @Test
    @DisplayName("a matching type=enchantment rule replaces the glint texture")
    void enchantmentRuleReplaces() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "enchantments", "sharpness", "texture", "custom_glint"));
        GlintPolicy policy = GlintEvaluator.evaluate(rules, sword().enchantment("minecraft:sharpness", 3).build());
        assertThat(policy, is(instanceOf(GlintPolicy.Replaced.class)));
        assertThat(((GlintPolicy.Replaced) policy).texture().id(), is("minecraft:optifine/cit/custom_glint"));
    }

    @Test
    @DisplayName("a type=enchantment rule with no items matches any enchanted item")
    void enchantmentRuleEmptyItemsMatchesAny() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "enchantments", "sharpness", "texture", "g"));
        // No items= filter on the rule, yet it applies to the diamond sword via the enchantment.
        GlintPolicy policy = GlintEvaluator.evaluate(rules, sword().enchantment("minecraft:sharpness", 1).build());
        assertThat(policy, is(instanceOf(GlintPolicy.Replaced.class)));
    }

    @Test
    @DisplayName("useGlint=false with no matching enchantment rule suppresses the glint")
    void useGlintFalseSuppresses() {
        RuleSet rules = rules(Optional.of(false));
        assertThat(GlintEvaluator.evaluate(rules, sword().build()), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("no glint inputs leaves the default vanilla glint")
    void noInputsDefault() {
        assertThat(GlintEvaluator.evaluate(rules(Optional.empty()), sword().build()), is(GlintPolicy.DEFAULT));
        // useGlint=true is not a suppression signal.
        assertThat(GlintEvaluator.evaluate(rules(Optional.of(true)), sword().build()), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a matching enchantment rule beats a useGlint=false suppression")
    void enchantmentBeatsSuppression() {
        RuleSet rules = rules(Optional.of(false), cit("enchantment", "enchantments", "sharpness", "texture", "g"));
        assertThat(GlintEvaluator.evaluate(rules, sword().enchantment("minecraft:sharpness", 2).build()),
            is(instanceOf(GlintPolicy.Replaced.class)));
    }

    @Test
    @DisplayName("an enchantment rule that does not match falls through to the useGlint / default decision")
    void nonMatchingEnchantmentFallsThrough() {
        RuleSet rules = rules(Optional.of(false), cit("enchantment", "enchantments", "smite", "texture", "g"));
        // Item has sharpness, rule wants smite -> no replace -> useGlint=false suppresses.
        assertThat(GlintEvaluator.evaluate(rules, sword().enchantment("minecraft:sharpness", 2).build()), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("the first matching enchantment rule in merge order wins")
    void firstMatchWins() {
        CitRule first = cit("enchantment", "enchantments", "sharpness", "texture", "first_glint");
        CitRule second = cit("enchantment", "enchantments", "sharpness", "texture", "second_glint");
        RuleSet rules = rules(Optional.empty(), first, second);
        GlintPolicy policy = GlintEvaluator.evaluate(rules, sword().enchantment("minecraft:sharpness", 1).build());
        assertThat(((GlintPolicy.Replaced) policy).texture().id(), is("minecraft:optifine/cit/first_glint"));
    }

    @Test
    @DisplayName("item-type rules are ignored by the glint walk")
    void itemRulesIgnored() {
        RuleSet rules = rules(Optional.empty(), cit("item", "items", "diamond_sword", "texture", "reskin"));
        assertThat(GlintEvaluator.evaluate(rules, sword().build()), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("useGlint=false suppresses even for the default EMPTY context (global, context-independent)")
    void useGlintSuppressesEmptyContext() {
        // Regression pin for the resolveCit fix: a plainly-enchanted item renders with the default
        // ItemContext.EMPTY (no item NBT), yet the global useGlint=false must still suppress.
        assertThat(GlintEvaluator.evaluate(rules(Optional.of(false)), ItemContext.EMPTY), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("an item-list-less type=enchantment rule replaces the glint even for the EMPTY context")
    void filterlessEnchantmentReplacesEmptyContext() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "texture", "global_glint"));
        assertThat(GlintEvaluator.evaluate(rules, ItemContext.EMPTY), is(instanceOf(GlintPolicy.Replaced.class)));
    }

    private static @NotNull ItemContext.Builder sword() {
        return ItemContext.builder().itemId("minecraft:diamond_sword");
    }

    private static @NotNull RuleSet rules(@NotNull Optional<Boolean> useGlint, @NotNull CitRule... cit) {
        return new RuleSet(PackId.VANILLA,
            Concurrent.adoptList(new ArrayList<>(Arrays.asList(cit))).toUnmodifiable(),
            Concurrent.newUnmodifiableList(),
            RuleSet.empty(PackId.VANILLA).colors(),
            useGlint);
    }

    private static @NotNull CitRule cit(@NotNull String type, @NotNull String... keyValues) {
        Properties props = new Properties();
        props.setProperty("type", type);
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"), PackId.VANILLA, "optifine/cit", "optifine/cit")
            .orElseThrow();
    }

}
