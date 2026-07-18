package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies {@link ItemContext} display-name synthesis - the {@link ItemContext.Builder} synthesises a
 * minimal NBT compound from the display-name scalar (at {@code components.minecraft:custom_name}) so a
 * display-name CIT rule keeps matching a caller that supplied no explicit NBT.
 */
class ItemContextTest {

    @Test
    @DisplayName("a scalar-only context synthesises components.minecraft:custom_name from the display name")
    void scalarSynthesisesCustomName() {
        ItemContext context = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Excalibur").build();
        CompoundTag components = (CompoundTag) context.effectiveNbt().get("components");
        assertThat(components, is(notNullValue()));
        Tag<?> customName = components.get("minecraft:custom_name");
        assertThat(customName, is(notNullValue()));
        assertThat(customName.getValue().toString(), equalTo("Excalibur"));
    }

    @Test
    @DisplayName("a display-name CIT rule matches a scalar-only context via the synthesised NBT")
    void displayNameRuleMatchesScalarContext() {
        ItemContext context = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Legendary Thunderbolt Blade").build();

        NbtPath path = componentsCustomName();
        CitRule rule = ruleOn("minecraft:diamond_sword",
            new NbtRule(path, NbtPredicate.glob("*Thunderbolt*", false), false));
        assertThat(rule.matches(context), is(true));

        ItemContext other = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Plain Sword").build();
        assertThat(rule.matches(other), is(false));
    }

    @Test
    @DisplayName("a context with no display name synthesises no NBT and yields an empty effective compound")
    void noDisplayNameYieldsEmptyNbt() {
        ItemContext context = ItemContext.ofItem("minecraft:stick");
        assertThat(context.nbt().isEmpty(), is(true));
        assertThat(context.effectiveNbt().isEmpty(), is(true));
    }

    private static NbtPath componentsCustomName() {
        return new NbtPath(dev.simplified.collection.Concurrent.<NbtPath.Step>newList(
            new NbtPath.Key("components"), new NbtPath.Key("minecraft:custom_name")).toUnmodifiable());
    }

    private static CitRule ruleOn(String itemId, NbtRule nbtRule) {
        return new CitRule(
            new lib.minecraft.renderer.asset.ResourceId("minecraft", "x.properties"),
            lib.minecraft.renderer.asset.pack.PackId.VANILLA,
            CitType.ITEM,
            dev.simplified.collection.Concurrent.newList(lib.minecraft.renderer.asset.ResourceId.parse(itemId)),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            Hand.ANY,
            dev.simplified.collection.Concurrent.newList(nbtRule),
            CitOutput.EMPTY,
            0);
    }

}
