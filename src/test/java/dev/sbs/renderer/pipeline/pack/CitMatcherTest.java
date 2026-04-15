package dev.sbs.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class CitMatcherTest {

    @Test
    @DisplayName("EMPTY context never matches any rule")
    void emptyContextNeverMatches() {
        CitRule rule = simpleRule("minecraft:diamond_sword");
        assertThat(CitMatcher.match(rule, ItemContext.EMPTY), is(false));
    }

    @Test
    @DisplayName("matching item id with no other conditions matches")
    void itemIdOnly() {
        CitRule rule = simpleRule("minecraft:diamond_sword");
        ItemContext context = ItemContext.ofItem("minecraft:diamond_sword");
        assertThat(CitMatcher.match(rule, context), is(true));
    }

    @Test
    @DisplayName("non-matching item id rejects the rule")
    void wrongItemIdRejects() {
        CitRule rule = simpleRule("minecraft:diamond_sword");
        ItemContext context = ItemContext.ofItem("minecraft:iron_sword");
        assertThat(CitMatcher.match(rule, context), is(false));
    }

    @Test
    @DisplayName("damage range accepts in-range values")
    void damageRangeInRange() {
        ConcurrentList<String> items = Concurrent.newList();
        items.add("minecraft:diamond_sword");
        CitRule rule = new CitRule(
            "test.properties",
            0,
            items,
            Optional.of(new IntRange(0, 100)),
            Optional.empty(),
            Concurrent.newMap(),
            Concurrent.newList(),
            Concurrent.newMap(),
            "custom_sword"
        );
        ItemContext context = new ItemContext(
            "minecraft:diamond_sword", 50, 1, Optional.empty(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newList()
        );
        assertThat(CitMatcher.match(rule, context), is(true));
    }

    @Test
    @DisplayName("damage range rejects out-of-range values")
    void damageRangeOutOfRange() {
        ConcurrentList<String> items = Concurrent.newList();
        items.add("minecraft:diamond_sword");
        CitRule rule = new CitRule(
            "test.properties",
            0,
            items,
            Optional.of(new IntRange(0, 10)),
            Optional.empty(),
            Concurrent.newMap(),
            Concurrent.newList(),
            Concurrent.newMap(),
            "custom_sword"
        );
        ItemContext context = new ItemContext(
            "minecraft:diamond_sword", 50, 1, Optional.empty(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newList()
        );
        assertThat(CitMatcher.match(rule, context), is(false));
    }

    @Test
    @DisplayName("NBT display.Name ipattern matches on display name")
    void nbtDisplayNameIpattern() {
        ConcurrentList<String> items = Concurrent.newList();
        items.add("minecraft:diamond_sword");
        ConcurrentMap<String, NbtCondition> conditions = Concurrent.newMap();
        conditions.put("display.Name", NbtCondition.parse("ipattern:*Thunderbolt*"));

        CitRule rule = new CitRule(
            "test.properties",
            0,
            items,
            Optional.empty(),
            Optional.empty(),
            conditions,
            Concurrent.newList(),
            Concurrent.newMap(),
            "custom_sword"
        );
        ItemContext matching = new ItemContext(
            "minecraft:diamond_sword", 0, 1, Optional.of("Legendary Thunderbolt Blade"), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newList()
        );
        ItemContext nonMatching = new ItemContext(
            "minecraft:diamond_sword", 0, 1, Optional.of("Plain Sword"), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newList()
        );

        assertThat(CitMatcher.match(rule, matching), is(true));
        assertThat(CitMatcher.match(rule, nonMatching), is(false));
    }

    @Test
    @DisplayName("required enchantment id must be present")
    void enchantmentIdRequired() {
        ConcurrentList<String> items = Concurrent.newList();
        items.add("minecraft:diamond_sword");
        ConcurrentList<String> enchantments = Concurrent.newList();
        enchantments.add("minecraft:sharpness");

        CitRule rule = new CitRule(
            "test.properties",
            0,
            items,
            Optional.empty(),
            Optional.empty(),
            Concurrent.newMap(),
            enchantments,
            Concurrent.newMap(),
            "custom_sword"
        );
        ConcurrentMap<String, Integer> present = Concurrent.newMap();
        present.put("minecraft:sharpness", 5);
        ConcurrentMap<String, Integer> missing = Concurrent.newMap();

        ItemContext withEnch = new ItemContext("minecraft:diamond_sword", 0, 1, Optional.empty(), Concurrent.newMap(), present, Concurrent.newList());
        ItemContext withoutEnch = new ItemContext("minecraft:diamond_sword", 0, 1, Optional.empty(), Concurrent.newMap(), missing, Concurrent.newList());

        assertThat(CitMatcher.match(rule, withEnch), is(true));
        assertThat(CitMatcher.match(rule, withoutEnch), is(false));
    }

    private static @NotNull CitRule simpleRule(@NotNull String itemId) {
        ConcurrentList<String> items = Concurrent.newList();
        items.add(itemId);
        return new CitRule(
            "test.properties",
            0,
            items,
            Optional.empty(),
            Optional.empty(),
            Concurrent.newMap(),
            Concurrent.newList(),
            Concurrent.newMap(),
            "custom_texture"
        );
    }

}
