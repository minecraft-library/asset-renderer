package lib.minecraft.renderer.pipeline.pack.rule;

import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies {@link CitRule#matches} end-to-end from parsed rules: item-id membership, percentage damage,
 * the two enchantment modes, the stack-size filter, and the {@code hand=off} never-matches rule
 * (GUI rendering is the main hand).
 */
class CitRuleMatchTest {

    @Test
    @DisplayName("a rule matches only its listed item ids")
    void itemMembership() {
        CitRule rule = rule("items", "diamond_sword");
        assertThat(rule.matches(item("minecraft:diamond_sword")), is(true));
        assertThat(rule.matches(item("minecraft:iron_sword")), is(false));
    }

    @Test
    @DisplayName("damage=% compares as a percentage of max durability")
    void percentageDamage() {
        CitRule rule = rule("items", "diamond_sword", "damage", "50%");
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").damage(100).maxDamage(200).build()), is(true));
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").damage(10).maxDamage(200).build()), is(false));
    }

    @Test
    @DisplayName("enchantments with a level list requires the listed enchantment at an in-range level")
    void enchantmentIdMode() {
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness", "enchantmentLevels", "3-5");
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 4).build()), is(true));
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build()), is(false));
        assertThat(rule.matches(item("minecraft:diamond_sword")), is(false));
    }

    @Test
    @DisplayName("an enchantments list is ANY (at-least-one), not ALL")
    void enchantmentAnyMode() {
        // sharpness / smite are mutually exclusive in vanilla; an ALL read could never match.
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness smite");
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 5).build()), is(true));
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:smite", 3).build()), is(true));
        assertThat(rule.matches(item("minecraft:diamond_sword")), is(false));
    }

    @Test
    @DisplayName("ANY with a level list matches if any listed enchantment is present and in range")
    void enchantmentAnyWithLevels() {
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness smite", "enchantmentLevels", "3-5");
        // sharpness present but out of range; smite present and in range -> matches via smite.
        ItemContext viaSmite = ItemContext.builder().itemId("minecraft:diamond_sword")
            .enchantment("minecraft:sharpness", 1).enchantment("minecraft:smite", 4).build();
        assertThat(rule.matches(viaSmite), is(true));
        // only a present-but-out-of-range enchantment -> no match.
        ItemContext outOfRange = ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build();
        assertThat(rule.matches(outOfRange), is(false));
    }

    @Test
    @DisplayName("enchantmentLevels with no ids matches the total level sum")
    void enchantmentSumMode() {
        CitRule rule = rule("items", "diamond_sword", "enchantmentLevels", "5-10");
        ItemContext heavy = ItemContext.builder().itemId("minecraft:diamond_sword")
            .enchantment("minecraft:sharpness", 4).enchantment("minecraft:looting", 3).build();
        assertThat(rule.matches(heavy), is(true));
        ItemContext light = ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build();
        assertThat(rule.matches(light), is(false));
    }

    @Test
    @DisplayName("stackSize gates on the item's stack count")
    void stackSize() {
        CitRule rule = rule("items", "diamond_sword", "stackSize", "1-16");
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").stackCount(5).build()), is(true));
        assertThat(rule.matches(ItemContext.builder().itemId("minecraft:diamond_sword").stackCount(64).build()), is(false));
    }

    @Test
    @DisplayName("hand=off never matches (GUI rendering is the main hand)")
    void handOffNeverMatches() {
        CitRule rule = rule("items", "diamond_sword", "hand", "off");
        assertThat(rule.matches(item("minecraft:diamond_sword")), is(false));
    }

    private static @NotNull ItemContext item(@NotNull String id) {
        return ItemContext.ofItem(id);
    }

    private static @NotNull CitRule rule(@NotNull String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        Optional<CitRule> rule = CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"), PackId.VANILLA, "optifine/cit", "optifine/cit");
        return rule.orElseThrow();
    }

}
