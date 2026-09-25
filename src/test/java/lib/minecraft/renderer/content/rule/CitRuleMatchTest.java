package lib.minecraft.renderer.content.rule;

import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * End-to-end coverage of {@link ItemContext#matches(CitRule)} from parsed rules: item-id membership,
 * percentage damage, the two enchantment modes, the stack-size filter, the {@code hand=off}
 * never-matches rule (GUI rendering is the main hand), and a display-name rule matching a scalar-only
 * context through the NBT the builder synthesises.
 * <p>
 * Every fixture is built by the real {@link CitParser}, so a parse regression reddens this file too - it
 * is a parse-and-match pair rather than a pure unit test of {@code matches}.
 */
@DisplayName("ItemContext.matches over parsed rules")
class CitRuleMatchTest {

    @Test
    @DisplayName("a rule matches only its listed item ids")
    void itemMembership() {
        CitRule rule = rule("items", "diamond_sword");
        assertThat(item("minecraft:diamond_sword").matches(rule), is(true));
        assertThat(item("minecraft:iron_sword").matches(rule), is(false));
    }

    @Test
    @DisplayName("damage=% compares as a percentage of max durability")
    void percentageDamage() {
        CitRule rule = rule("items", "diamond_sword", "damage", "50%");
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").damage(100).maxDamage(200).build().matches(rule), is(true));
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").damage(10).maxDamage(200).build().matches(rule), is(false));
    }

    @Test
    @DisplayName("enchantments with a level list requires the listed enchantment at an in-range level")
    void enchantmentIdMode() {
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness", "enchantmentLevels", "3-5");
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 4).build().matches(rule), is(true));
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build().matches(rule), is(false));
        assertThat(item("minecraft:diamond_sword").matches(rule), is(false));
    }

    @Test
    @DisplayName("an enchantments list is ANY (at-least-one), not ALL")
    void enchantmentAnyMode() {
        // sharpness / smite are mutually exclusive in vanilla; an ALL read could never match.
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness smite");
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 5).build().matches(rule), is(true));
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:smite", 3).build().matches(rule), is(true));
        assertThat(item("minecraft:diamond_sword").matches(rule), is(false));
    }

    @Test
    @DisplayName("ANY with a level list matches if any listed enchantment is present and in range")
    void enchantmentAnyWithLevels() {
        CitRule rule = rule("items", "diamond_sword", "enchantments", "sharpness smite", "enchantmentLevels", "3-5");
        // sharpness present but out of range; smite present and in range -> matches via smite.
        ItemContext viaSmite = ItemContext.builder().itemId("minecraft:diamond_sword")
            .enchantment("minecraft:sharpness", 1).enchantment("minecraft:smite", 4).build();
        assertThat(viaSmite.matches(rule), is(true));
        // only a present-but-out-of-range enchantment -> no match.
        ItemContext outOfRange = ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build();
        assertThat(outOfRange.matches(rule), is(false));
    }

    @Test
    @DisplayName("enchantmentLevels with no ids matches the total level sum")
    void enchantmentSumMode() {
        CitRule rule = rule("items", "diamond_sword", "enchantmentLevels", "5-10");
        ItemContext heavy = ItemContext.builder().itemId("minecraft:diamond_sword")
            .enchantment("minecraft:sharpness", 4).enchantment("minecraft:looting", 3).build();
        assertThat(heavy.matches(rule), is(true));
        ItemContext light = ItemContext.builder().itemId("minecraft:diamond_sword").enchantment("minecraft:sharpness", 1).build();
        assertThat(light.matches(rule), is(false));
    }

    @Test
    @DisplayName("stackSize gates on the item's stack count")
    void stackSize() {
        CitRule rule = rule("items", "diamond_sword", "stackSize", "1-16");
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").stackCount(5).build().matches(rule), is(true));
        assertThat(ItemContext.builder().itemId("minecraft:diamond_sword").stackCount(64).build().matches(rule), is(false));
    }

    @Test
    @DisplayName("hand=off never matches (GUI rendering is the main hand)")
    void handOffNeverMatches() {
        CitRule rule = rule("items", "diamond_sword", "hand", "off");
        assertThat(item("minecraft:diamond_sword").matches(rule), is(false));
    }

    @Test
    @DisplayName("a display-name CIT rule matches a scalar-only context via the synthesised NBT")
    void displayNameRuleMatchesScalarContext() {
        ItemContext context = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Legendary Thunderbolt Blade").build();

        // nbt.display.Name is the CIT spelling for a display-name match; the parser rewrites it onto the
        // modern components.minecraft:custom_name path the builder synthesises.
        CitRule rule = ruleOn("items", "diamond_sword", "nbt.display.Name", "pattern:*Thunderbolt*");
        assertThat(context.matches(rule), is(true));

        ItemContext other = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Plain Sword").build();
        assertThat(other.matches(rule), is(false));
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

    private static CitRule ruleOn(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"),
            PackId.VANILLA, "optifine/cit", "optifine/cit").orElseThrow();
    }

}
