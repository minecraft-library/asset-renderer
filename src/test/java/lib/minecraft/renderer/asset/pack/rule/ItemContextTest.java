package lib.minecraft.renderer.asset.pack.rule;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.pipeline.pack.rule.CitParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Coverage of {@link ItemContext} display-name synthesis - the {@link ItemContext.Builder} synthesises a
 * minimal NBT compound from the display-name scalar (at {@code components.minecraft:custom_name}) so a
 * display-name CIT rule keeps matching a caller that supplied no explicit NBT.
 */
@DisplayName("ItemContext display-name synthesis")
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

        // nbt.display.Name is the CIT spelling for a display-name match; the parser rewrites it onto the
        // modern components.minecraft:custom_name path the builder synthesises.
        CitRule rule = ruleOn("items", "diamond_sword", "nbt.display.Name", "pattern:*Thunderbolt*");
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

    private static CitRule ruleOn(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) props.setProperty(keyValues[i], keyValues[i + 1]);
        return CitParser.parse(props, new ResourceId("minecraft", "optifine/cit/x.properties"),
            PackId.VANILLA, "optifine/cit", "optifine/cit").orElseThrow();
    }

}
