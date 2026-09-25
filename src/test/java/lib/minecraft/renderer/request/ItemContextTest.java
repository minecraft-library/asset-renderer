package lib.minecraft.renderer.request;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("a context with no display name synthesises no NBT and yields an empty effective compound")
    void noDisplayNameYieldsEmptyNbt() {
        ItemContext context = ItemContext.ofItem("minecraft:stick");
        assertThat(context.nbt().isEmpty(), is(true));
        assertThat(context.effectiveNbt().isEmpty(), is(true));
    }

}
