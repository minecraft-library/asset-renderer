package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.content.rule.CitParser;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
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
 * Coverage of {@link RuleLookup#glint}, the CIT glint decision: the highest-precedence
 * matching {@code type=enchantment} rule wins with {@link GlintPolicy.Replaced}, else a merged
 * {@code useGlint == false} suppresses, else the default vanilla glint. Also pins the
 * empty-{@code items} match that a {@code type=enchantment} glint rule relies on.
 */
@DisplayName("RuleLookup.glint CIT glint decision")
class RuleLookupGlintTest {

    @Test
    @DisplayName("a matching type=enchantment rule replaces the glint texture")
    void enchantmentRuleReplaces() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "enchantments", "sharpness", "texture", "custom_glint"));
        GlintPolicy policy = RuleLookup.glint(rules, sword().enchantment("minecraft:sharpness", 3).build());
        assertThat(policy, is(instanceOf(GlintPolicy.Replaced.class)));
        assertThat(((GlintPolicy.Replaced) policy).texture().id(), is("minecraft:optifine/cit/custom_glint"));
    }

    @Test
    @DisplayName("a type=enchantment rule with no items matches any enchanted item")
    void enchantmentRuleEmptyItemsMatchesAny() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "enchantments", "sharpness", "texture", "g"));
        // No items= filter on the rule, yet it applies to the diamond sword via the enchantment.
        GlintPolicy policy = RuleLookup.glint(rules, sword().enchantment("minecraft:sharpness", 1).build());
        assertThat(policy, is(instanceOf(GlintPolicy.Replaced.class)));
    }

    @Test
    @DisplayName("useGlint=false with no matching enchantment rule suppresses the glint")
    void useGlintFalseSuppresses() {
        RuleSet rules = rules(Optional.of(false));
        assertThat(RuleLookup.glint(rules, sword().build()), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("no glint inputs leaves the default vanilla glint")
    void noInputsDefault() {
        assertThat(RuleLookup.glint(rules(Optional.empty()), sword().build()), is(GlintPolicy.DEFAULT));
        // useGlint=true is not a suppression signal.
        assertThat(RuleLookup.glint(rules(Optional.of(true)), sword().build()), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("a matching enchantment rule beats a useGlint=false suppression")
    void enchantmentBeatsSuppression() {
        RuleSet rules = rules(Optional.of(false), cit("enchantment", "enchantments", "sharpness", "texture", "g"));
        assertThat(RuleLookup.glint(rules, sword().enchantment("minecraft:sharpness", 2).build()),
            is(instanceOf(GlintPolicy.Replaced.class)));
    }

    @Test
    @DisplayName("an enchantment rule that does not match falls through to the useGlint / default decision")
    void nonMatchingEnchantmentFallsThrough() {
        RuleSet rules = rules(Optional.of(false), cit("enchantment", "enchantments", "smite", "texture", "g"));
        // Item has sharpness, rule wants smite -> no replace -> useGlint=false suppresses.
        assertThat(RuleLookup.glint(rules, sword().enchantment("minecraft:sharpness", 2).build()), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("the first matching enchantment rule in merge order wins")
    void firstMatchWins() {
        CitRule first = cit("enchantment", "enchantments", "sharpness", "texture", "first_glint");
        CitRule second = cit("enchantment", "enchantments", "sharpness", "texture", "second_glint");
        RuleSet rules = rules(Optional.empty(), first, second);
        GlintPolicy policy = RuleLookup.glint(rules, sword().enchantment("minecraft:sharpness", 1).build());
        assertThat(((GlintPolicy.Replaced) policy).texture().id(), is("minecraft:optifine/cit/first_glint"));
    }

    @Test
    @DisplayName("item-type rules are ignored by the glint walk")
    void itemRulesIgnored() {
        RuleSet rules = rules(Optional.empty(), cit("item", "items", "diamond_sword", "texture", "reskin"));
        assertThat(RuleLookup.glint(rules, sword().build()), is(GlintPolicy.DEFAULT));
    }

    @Test
    @DisplayName("useGlint=false suppresses even for the default EMPTY context (global, context-independent)")
    void useGlintSuppressesEmptyContext() {
        // A plainly-enchanted item renders with the default ItemContext.EMPTY (no item NBT), yet the
        // global useGlint=false must still suppress.
        assertThat(RuleLookup.glint(rules(Optional.of(false)), ItemContext.EMPTY), is(GlintPolicy.SUPPRESSED));
    }

    @Test
    @DisplayName("an item-list-less type=enchantment rule replaces the glint even for the EMPTY context")
    void filterlessEnchantmentReplacesEmptyContext() {
        RuleSet rules = rules(Optional.empty(), cit("enchantment", "texture", "global_glint"));
        assertThat(RuleLookup.glint(rules, ItemContext.EMPTY), is(instanceOf(GlintPolicy.Replaced.class)));
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
