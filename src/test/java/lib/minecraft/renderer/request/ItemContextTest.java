package lib.minecraft.renderer.request;

import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.vanilla.DecodedComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of {@link ItemContext} as the one item stack a render reads: a Minecraft 26.1 stack
 * ({@code {id, count, components}}) read through {@link ItemContext#ofStack}, its component patch
 * answered by {@link ItemContext#components()}, and the display-name synthesis - the
 * {@link ItemContext.Builder} synthesises a minimal NBT compound from the display-name scalar (at
 * {@code components.minecraft:custom_name}) so a display-name CIT rule, and an item definition's name
 * select, keep matching a caller that supplied no explicit NBT.
 */
@DisplayName("ItemContext stack and display-name synthesis")
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
    @DisplayName("the synthesised name decodes as the plain literal a definition's name case writes")
    void theSynthesisedNameIsAPlainLiteral() {
        // A 26.1 stack writes an unstyled name as a bare string tag, which is what the synthesis puts
        // there, so a custom_name case written as the plain JSON string meets it.
        ItemContext context = ItemContext.builder().itemId("minecraft:diamond_sword").displayName("Excalibur").build();
        Possible<String> stackKey = DecodedComponent.CUSTOM_NAME.key(context.components());
        ItemModelNode.Select select = (ItemModelNode.Select) GsonSettings.defaults().create().fromJson(JsonParser.parseString(
            "{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\",\"component\":\"minecraft:custom_name\","
                + "\"cases\":[{\"when\":\"Excalibur\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/x\"}}]}"), ItemModelNode.class);
        assertThat(select.cases().getFirst().when(), contains(stackKey.orElseThrow()));
    }

    @Test
    @DisplayName("a context with no display name synthesises no NBT and yields an empty effective compound")
    void noDisplayNameYieldsEmptyNbt() {
        ItemContext context = ItemContext.ofItem("minecraft:stick");
        assertThat(context.nbt().isEmpty(), is(true));
        assertThat(context.effectiveNbt().isEmpty(), is(true));
        assertThat(context.components(), is(Optional.empty()));
    }

    @Test
    @DisplayName("a 26.1 stack gives the id, the count, the damage and the stack itself")
    void readsA26Stack() {
        CompoundTag components = new CompoundTag();
        components.put("minecraft:damage", new IntTag(12));
        components.put("minecraft:custom_data", compound("id", new StringTag("ASPECT_OF_THE_END")));
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag("minecraft:diamond_sword"));
        stack.put("count", new IntTag(3));
        stack.put("components", components);

        ItemContext context = ItemContext.ofStack(stack);

        assertThat(context.itemId(), is("minecraft:diamond_sword"));
        assertThat(context.stackCount(), is(3));
        assertThat(context.damage(), is(12));
        assertThat(context.nbt().orElseThrow(), is(sameInstance(stack)));
        assertThat(context.components().orElseThrow(), is(sameInstance(components)));
        assertThat("the max durability is a default component a patch does not carry", context.maxDamage(), is(0));
    }

    @Test
    @DisplayName("a stack with no count is one item, and one with no id keeps the id the builder holds")
    void readsTheStackDefaults() {
        CompoundTag stack = new CompoundTag();
        stack.put("components", new CompoundTag());

        ItemContext context = ItemContext.builder().itemId("minecraft:stick").stack(stack).build();

        assertThat(context.itemId(), is("minecraft:stick"));
        assertThat(context.stackCount(), is(1));
        assertThat(context.damage(), is(0));
    }

    @Test
    @DisplayName("a tree with no components compound answers no patch")
    void aTreeWithoutComponentsHasNoPatch() {
        // A stack written in any other shape - a pre-1.20.5 one keeps its data under tag - carries none
        // of the components an item definition tests.
        CompoundTag legacy = new CompoundTag();
        legacy.put("tag", compound("display", compound("Name", new StringTag("Excalibur"))));
        assertThat(ItemContext.builder().nbt(legacy).build().components(), is(Optional.empty()));

        CompoundTag misshapen = new CompoundTag();
        misshapen.put("components", new StringTag("not a compound"));
        assertThat(ItemContext.builder().nbt(misshapen).build().components(), is(Optional.empty()));
    }

    /**
     * Builds a compound holding one entry.
     *
     * @param key the entry's key
     * @param value the entry's value
     * @return the compound
     */
    private static CompoundTag compound(String key, Tag<?> value) {
        CompoundTag compound = new CompoundTag();
        compound.put(key, value);
        return compound;
    }

}
