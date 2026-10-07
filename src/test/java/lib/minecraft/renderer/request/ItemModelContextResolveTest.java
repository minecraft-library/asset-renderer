package lib.minecraft.renderer.request;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode.SpecialTransform;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static lib.minecraft.renderer.fixture.ItemModelFixtures.timeDispatch;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-node-type evaluation of {@link ItemModelContext#resolve(ItemModelNode)}, plus the
 * neutral-default resolutions the parity contract rests on (bow unpulled, leather_boots fallback+dye,
 * clock frame 0, compass neutral frame), the unknown-property fallback-branch degradation, and the
 * {@link ItemModelNode#timeDispatchSteps()} search that sees past the branch a context selects.
 *
 * <p>The decode a definition goes through on its way to the walk is pinned here too, through the real
 * deserializer: what vanilla's codec refuses throws - an unregistered vanilla-namespace type or
 * property, an undecodable component value, an empty case or {@code when} list, a case value repeated
 * as decoded, a condition missing a branch - while a mod's namespace degrades where it sits, and an
 * absent fallback stays apart from an explicit {@code minecraft:empty}. The component walks follow,
 * shaped as Hypixel+ writes its ladders: {@code custom_data} conditions in both spellings, and
 * {@code dyed_color}, {@code custom_name} and {@code lore} selects.
 */
@DisplayName("ItemModelContext resolve evaluation")
class ItemModelContextResolveTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    private static ItemModelNode parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return GSON.fromJson(root.getAsJsonObject("model"), ItemModelNode.class);
    }

    private static ItemModelNode.Resolution resolveNeutral(String json) {
        return ItemModelContext.gui().resolve(parse(json));
    }

    @Nested
    @DisplayName("per node type")
    class PerNodeType {

        @Test
        @DisplayName("model leaf resolves to its ref")
        void modelLeaf() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/diamond_sword"));
        }

        @Test
        @DisplayName("condition takes on_false for a neutral (unknown/false) property")
        void conditionOnFalse() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/on\"},"
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/off\"}}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/off"));
        }

        @Test
        @DisplayName("condition takes on_true when the property is true")
        void conditionOnTrue() {
            ItemModelContext using = new ItemModelContext("gui", true, false, null, null, 0f, 0f, null, null);
            var r = using.resolve(parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/on\"},"
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/off\"}}}"));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/on"));
        }

        @Test
        @DisplayName("select matches display_context=gui, else fallback for an unevaluable property")
        void selectCaseAndFallback() {
            var gui = resolveNeutral("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                + "\"cases\":[{\"when\":\"gui\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/flat\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/held\"}}}");
            assertThat("gui case matched", gui.modelId().orElseThrow(), is("minecraft:item/flat"));

            var trim = resolveNeutral("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":\"minecraft:iron\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/iron_trim\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/plain\"}}}");
            assertThat("absent trim_material -> fallback", trim.modelId().orElseThrow(), is("minecraft:item/plain"));
        }

        @Test
        @DisplayName("select honours a matching when-array and a caller trim override")
        void selectWhenArrayAndOverride() {
            ItemModelContext iron = new ItemModelContext("gui", false, false, "minecraft:iron", null, 0f, 0f, null, null);
            var r = iron.resolve(parse("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":[\"minecraft:gold\",\"minecraft:iron\"],\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/iron_trim\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/plain\"}}}"));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/iron_trim"));
        }

        @Test
        @DisplayName("range_dispatch picks the highest threshold <= scaled value, else fallback")
        void rangeDispatch() {
            String tree = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/f0\"}},"
                + "{\"threshold\":0.5,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/f1\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fb\"}}}";
            assertThat("time=0 -> frame 0", resolveNeutral(tree).modelId().orElseThrow(), is("minecraft:item/f0"));

            ItemModelContext half = new ItemModelContext("gui", false, false, null, null, 0.5f, 0f, null, null);
            assertThat("time=0.5 (scaled 32 >= 0.5) -> frame 1",
                half.resolve(parse(tree)).modelId().orElseThrow(), is("minecraft:item/f1"));
        }

        @Test
        @DisplayName("range_dispatch with all thresholds above the scaled value takes the fallback")
        void rangeDispatchFallback() {
            String tree = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":1.0,"
                + "\"entries\":[{\"threshold\":5.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/f5\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fb\"}}}";
            assertThat(resolveNeutral(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
        }

        @Test
        @DisplayName("composite resolves to its first non-empty child")
        void composite() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":["
                + "{\"type\":\"minecraft:bundle/selected_item\"},"
                + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/first\"}]}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/first"));
        }

        @Test
        @DisplayName("a vanilla-namespace node type vanilla does not register fails the definition, a mod's renders nothing")
        void unknownNode() {
            // Vanilla's dispatch codec refuses an id it does not register, so the definition is dropped
            // whole; a mod's node type - which the Catharsis overlays name - degrades to the empty node.
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:mystery_future_node\"}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"mystery_future_node\"}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"model\":\"minecraft:item/x\"}}"));

            ItemModelNode foreign = parse("{\"model\":{\"type\":\"catharsis:fallthrough\",\"models\":[]}}");
            assertThat(foreign, is(ItemModelNode.Empty.INSTANCE));
            assertThat(ItemModelContext.gui().resolve(foreign).isEmpty(), is(true));
        }

        @Test
        @DisplayName("special resolves to a special leaf carrying kind + base + transform")
        void specialLeaf() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/template_skull\","
                + "\"model\":{\"type\":\"minecraft:player_head\"},"
                + "\"transformation\":{\"left_rotation\":[1,0,0,0],\"right_rotation\":[0,0,0,1],\"scale\":[1,1,1],\"translation\":[0.5,0,0.5]}}}");
            ItemModelNode.Special special = r.special().orElseThrow();
            assertThat(special.kind(), is("minecraft:player_head"));
            assertThat(special.base(), is("minecraft:item/template_skull"));
            assertThat(special.transform().translation(), is(new float[]{0.5f, 0f, 0.5f}));
        }

        @Test
        @DisplayName("a bare model leaf and a bare special base parse as minecraft: ids, a foreign namespace kept")
        void bareIdsParseQualified() {
            var leaf = resolveNeutral("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"item/x\"}}");
            assertThat(leaf.modelId().orElseThrow(), is("minecraft:item/x"));

            var special = resolveNeutral("{\"model\":{\"type\":\"minecraft:special\",\"base\":\"item/template_skull\","
                + "\"model\":{\"type\":\"minecraft:player_head\"}}}");
            assertThat(special.special().orElseThrow().base(), is("minecraft:item/template_skull"));

            var foreign = resolveNeutral("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"hplus:skyblock/x\"}}");
            assertThat(foreign.modelId().orElseThrow(), is("hplus:skyblock/x"));
        }
    }

    @Nested
    @DisplayName("component-driven dispatch")
    class ComponentDispatch {

        @Test
        @DisplayName("has_component takes on_true when the component map carries it")
        void hasComponentTrue() {
            CompoundTag components = new CompoundTag();
            components.put("minecraft:damage", 5);
            var r = withComponents(components).resolve(parse(HAS_COMPONENT_TREE));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/has"));
        }

        @Test
        @DisplayName("has_component takes on_false when the map lacks it (present but empty)")
        void hasComponentAbsent() {
            var r = withComponents(new CompoundTag()).resolve(parse(HAS_COMPONENT_TREE));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/lacks"));
        }

        @Test
        @DisplayName("custom_model_data reads floats[0] from the component tree")
        void customModelDataFromComponents() {
            assertThat("floats[0]=2 -> threshold 2 -> custom",
                withComponents(customModelData(2f)).resolve(parse(CMD_TREE)).modelId().orElseThrow(), is("minecraft:item/custom"));
            assertThat("floats[0]=0 -> base",
                withComponents(customModelData(0f)).resolve(parse(CMD_TREE)).modelId().orElseThrow(), is("minecraft:item/base"));
        }

        @Test
        @DisplayName("an explicit custom_model_data override wins over the component tree")
        void customModelDataOverrideWins() {
            ItemModelContext override = new ItemModelContext("gui", false, false, null, null, 0f, 0f, 2f, customModelData(0f));
            assertThat(override.resolve(parse(CMD_TREE)).modelId().orElseThrow(), is("minecraft:item/custom"));
        }

        @Test
        @DisplayName("range_dispatch index selects the floats entry the node points at")
        void customModelDataIndex() {
            String indexed = CMD_TREE.replace("\"scale\":1.0,", "\"scale\":1.0,\"index\":1,");
            // floats[0]=0 (would pick base), floats[1]=2 (picks custom) - so index 1 must be honoured.
            assertThat(withComponents(customModelData(0f, 2f)).resolve(parse(indexed)).modelId().orElseThrow(), is("minecraft:item/custom"));
        }

        private static final String HAS_COMPONENT_TREE =
            "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:has_component\",\"component\":\"minecraft:damage\","
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/has\"},"
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/lacks\"}}}";

        private static final String CMD_TREE =
            "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\",\"scale\":1.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/base\"}},"
                + "{\"threshold\":2.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/custom\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fb\"}}}";

        private static ItemModelContext withComponents(CompoundTag components) {
            return new ItemModelContext("gui", false, false, null, null, 0f, 0f, null, components);
        }

        /** Builds a component map carrying a {@code minecraft:custom_model_data} component with the given {@code floats} list. */
        private static CompoundTag customModelData(float... floats) {
            ListTag<FloatTag> list = new ListTag<>();
            for (float value : floats) list.add(new FloatTag(value));
            CompoundTag customModelData = new CompoundTag();
            customModelData.put("floats", list);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_model_data", customModelData);
            return components;
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        @DisplayName("refuses a case value repeated across or within the cases")
        void refusesADuplicateCase() {
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:display_context",
                "{\"when\":\"gui\",\"model\":" + leaf("a") + "},{\"when\":\"gui\",\"model\":" + leaf("b") + "}")));
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:display_context",
                "{\"when\":[\"gui\",\"gui\"],\"model\":" + leaf("a") + "}")));
        }

        @Test
        @DisplayName("compares duplicate case values as decoded, so iron and minecraft:iron collide")
        void comparesDuplicatesDecoded() {
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:trim_material",
                "{\"when\":\"minecraft:iron\",\"model\":" + leaf("a") + "},{\"when\":\"iron\",\"model\":" + leaf("b") + "}")));
            // The key is the decoded identifier, so a bare case meets the caller's qualified trim.
            ItemModelContext iron = new ItemModelContext("gui", false, false, "minecraft:iron", null, 0f, 0f, null, null);
            ItemModelNode bare = parse(select("minecraft:trim_material", "{\"when\":\"iron\",\"model\":" + leaf("iron") + "}"));
            assertThat(iron.resolve(bare).modelId().orElseThrow(), is("minecraft:item/iron"));
        }

        @Test
        @DisplayName("refuses an empty when, an empty or absent case list and a non-string vanilla case value")
        void refusesEmptyAndUndecodableCases() {
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:display_context", "{\"when\":[],\"model\":" + leaf("a") + "}")));
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:display_context", "")));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                + "\"fallback\":" + leaf("fb") + "}}"));
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:custom_model_data", "{\"when\":5,\"model\":" + leaf("a") + "}")));
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:display_context", "{\"when\":\"gui\"}")));
        }

        @Test
        @DisplayName("refuses an undecodable custom_data value and a non-object presence value")
        void refusesAnUndecodableComponentValue() {
            assertThrows(JsonParseException.class, () -> parse(component("\"minecraft:custom_data\"", "\"{unclosed\"")));
            assertThrows(JsonParseException.class, () -> parse(component("\"minecraft:max_damage\"", "5")));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
                + "\"predicate\":\"minecraft:custom_data\",\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}"));
            ItemModelNode present = parse(component("\"minecraft:max_damage\"", "{}"));
            assertThat(((ItemModelNode.Condition) present).predicate().orElseThrow(),
                is(new ItemModelNode.ComponentPredicate.Present("minecraft:max_damage")));
        }

        @Test
        @DisplayName("refuses a dyed_color when of three floats on its repeated 0, reads [[r,g,b]] as one colour and refuses {rgb}")
        void decodesDyedColourWhens() {
            assertThrows(JsonParseException.class, () -> parse(componentSelect("minecraft:dyed_color", "[1.0,0.0,0.0]")));
            assertThrows(JsonParseException.class, () -> parse(componentSelect("minecraft:dyed_color", "{\"rgb\":16711680}")));
            ItemModelNode.Select vector = (ItemModelNode.Select) parse(componentSelect("minecraft:dyed_color", "[[1.0,0.0,0.0]]"));
            assertThat(vector.cases().getFirst().when(), contains("-65536"));
            assertThat(vector.component(), is("minecraft:dyed_color"));
        }

        @Test
        @DisplayName("refuses a condition missing a branch, and a has_component without its component")
        void refusesAMissingBranch() {
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_false\":" + leaf("f") + "}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_true\":" + leaf("t") + ",\"on_false\":\"minecraft:item/f\"}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:has_component\","
                + "\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}"));
        }

        @Test
        @DisplayName("reads ignore_default on has_component")
        void readsIgnoreDefault() {
            ItemModelNode.Condition condition = (ItemModelNode.Condition) parse("{\"model\":{\"type\":\"minecraft:condition\","
                + "\"property\":\"minecraft:has_component\",\"component\":\"minecraft:dyed_color\",\"ignore_default\":true,"
                + "\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}");
            assertThat(condition.ignoreDefault(), is(true));
            CompoundTag removed = components("!minecraft:dyed_color", new CompoundTag());
            assertThat(withComponents(removed).resolve(condition).modelId().orElseThrow(), is("minecraft:item/t"));
        }

        @Test
        @DisplayName("reads property and node type ids namespace-exact: a mod's degrades, an unregistered vanilla one fails")
        void readsIdsNamespaceExact() {
            // hplus:using_item is not using_item: it parses as a property the walk cannot evaluate.
            ItemModelContext using = new ItemModelContext("gui", true, false, null, null, 0f, 0f, null, null);
            ItemModelNode foreignFlag = parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"hplus:using_item\","
                + "\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}");
            assertThat(using.resolve(foreignFlag).modelId().orElseThrow(), is("minecraft:item/f"));

            ItemModelNode dataType = parse(select("catharsis:data_type", "{\"when\":\"a\",\"model\":" + leaf("a") + "}"));
            assertThat(using.resolve(dataType).modelId().orElseThrow(), is("minecraft:item/fb"));

            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"foo\","
                + "\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}"));
            assertThrows(JsonParseException.class, () -> parse(select("minecraft:mystery_key", "{\"when\":\"a\",\"model\":" + leaf("a") + "}")));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"use_count\","
                + "\"entries\":[]}}"));
        }

        @Test
        @DisplayName("marks an absent fallback apart from an explicit minecraft:empty, both rendering nothing")
        void marksAnAbsentFallback() {
            ItemModelNode.Select absent = (ItemModelNode.Select) parse("{\"model\":{\"type\":\"minecraft:select\","
                + "\"property\":\"minecraft:charge_type\",\"cases\":[{\"when\":\"rocket\",\"model\":" + leaf("r") + "}]}}");
            assertThat(absent.fallback(), is(ItemModelNode.Absent.INSTANCE));
            ItemModelNode.Select empty = (ItemModelNode.Select) parse(select("minecraft:charge_type",
                "{\"when\":\"rocket\",\"model\":" + leaf("r") + "}").replace(leaf("fb"), "{\"type\":\"minecraft:empty\"}"));
            assertThat(empty.fallback(), is(ItemModelNode.Empty.INSTANCE));
            assertThat(ItemModelContext.gui().resolve(absent).isEmpty(), is(true));
            assertThat(ItemModelContext.gui().resolve(empty).isEmpty(), is(true));

            ItemModelNode.RangeDispatch range = (ItemModelNode.RangeDispatch) parse("{\"model\":" + timeDispatch("minecraft:time", 4) + "}");
            assertThat(range.fallback(), is(ItemModelNode.Absent.INSTANCE));
        }

    }

    @Nested
    @DisplayName("component walks")
    class ComponentWalks {

        /** A Hypixel+-shaped ladder: an object-valued custom_data test, then an SNBT-valued one, then the plain item. */
        private static final String LADDER = "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
            + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"ASPECT_OF_THE_END\"},"
            + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"hplus:skyblock/aote\"},"
            + "\"on_false\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
            + "\"predicate\":\"minecraft:custom_data\",\"value\":\"{'edition': 1}\","
            + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"hplus:skyblock/edition\"},"
            + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/diamond_sword\"}}}}";

        @Test
        @DisplayName("walks a custom_data ladder to the branch the stack's custom data selects")
        void walksACustomDataLadder() {
            ItemModelNode ladder = parse(LADDER);
            assertThat(withComponents(components("minecraft:custom_data", compound("id", new StringTag("ASPECT_OF_THE_END"))))
                .resolve(ladder).modelId().orElseThrow(), is("hplus:skyblock/aote"));
            assertThat(withComponents(components("minecraft:custom_data", compound("edition", new IntTag(1))))
                .resolve(ladder).modelId().orElseThrow(), is("hplus:skyblock/edition"));
            assertThat("the SNBT test holds an int, so a byte misses it",
                withComponents(components("minecraft:custom_data", compound("edition", new ByteTag((byte) 1))))
                    .resolve(ladder).modelId().orElseThrow(), is("minecraft:item/diamond_sword"));
            assertThat(ItemModelContext.gui().resolve(ladder).modelId().orElseThrow(), is("minecraft:item/diamond_sword"));
        }

        @Test
        @DisplayName("walks a dyed_color select under a custom_data match")
        void walksADyedColourUnderACustomDataMatch() {
            ItemModelNode tree = parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\","
                + "\"predicate\":\"minecraft:custom_data\",\"value\":{\"id\":\"FARM_ARMOR_BOOTS\"},"
                + "\"on_true\":" + componentSelectNode("minecraft:dyed_color", "16711680")
                + ",\"on_false\":" + leaf("plain") + "}}");
            CompoundTag stack = components("minecraft:custom_data", compound("id", new StringTag("FARM_ARMOR_BOOTS")));
            stack.put("minecraft:dyed_color", new IntTag(16711680));
            assertThat(withComponents(stack).resolve(tree).modelId().orElseThrow(), is("minecraft:item/case"));
            stack.put("minecraft:dyed_color", new IntTag(255));
            assertThat(withComponents(stack).resolve(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
            assertThat(ItemModelContext.gui().resolve(tree).modelId().orElseThrow(), is("minecraft:item/plain"));
        }

        @Test
        @DisplayName("walks a custom_name case, the stack's name read from 26.1 NBT")
        void walksACustomNameCase() {
            ItemModelNode tree = parse(componentSelect("minecraft:custom_name",
                "[{\"extra\":[{\"color\":\"green\",\"text\":\"Reset Settings\"}],\"italic\":false,\"text\":\"\"}]"));
            CompoundTag sibling = compound("text", new StringTag("Reset Settings"));
            sibling.put("color", new StringTag("green"));
            CompoundTag name = compound("text", new StringTag(""));
            name.put("italic", new ByteTag((byte) 0));
            name.put("extra", list(sibling));
            assertThat(withComponents(components("minecraft:custom_name", name)).resolve(tree).modelId().orElseThrow(), is("minecraft:item/case"));

            name.remove("italic");
            assertThat("an absent italic is not italic:false",
                withComponents(components("minecraft:custom_name", name)).resolve(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
        }

        @Test
        @DisplayName("walks a lore case as one lore of two lines")
        void walksALoreCase() {
            ItemModelNode tree = parse(componentSelect("minecraft:lore", "[{\"text\":\"a\"},{\"text\":\"b\"}]"));
            assertThat(withComponents(components("minecraft:lore", list(new StringTag("a"), new StringTag("b"))))
                .resolve(tree).modelId().orElseThrow(), is("minecraft:item/case"));
            assertThat(withComponents(components("minecraft:lore", list(new StringTag("a"))))
                .resolve(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
        }

        @Test
        @DisplayName("takes the fallback of a select on a component it does not decode")
        void takesTheFallbackOfAnUndecodedComponent() {
            ItemModelNode tree = parse(componentSelect("minecraft:item_model", "\"minecraft:stone_sword\""));
            assertThat(withComponents(components("minecraft:item_model", new StringTag("minecraft:stone_sword")))
                .resolve(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
        }

        @Test
        @DisplayName("marks a resolution reached through a composite, and only that one")
        void marksACompositePath() {
            var composed = resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":[" + leaf("first") + "," + leaf("second") + "]}}");
            assertThat(composed.modelId().orElseThrow(), is("minecraft:item/first"));
            assertThat(composed.composed(), is(true));
            assertThat(resolveNeutral("{\"model\":" + leaf("plain") + "}").composed(), is(false));
            assertThat(resolveNeutral(select("minecraft:display_context", "{\"when\":\"gui\",\"model\":" + leaf("gui") + "}")).composed(), is(false));
        }

    }

    /** A {@code minecraft:model} leaf at {@code minecraft:item/<name>}. */
    private static String leaf(String name) {
        return "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/" + name + "\"}";
    }

    /** A select definition on a property, with the given case objects and a {@code fb} fallback. */
    private static String select(String property, String cases) {
        return "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"" + property + "\",\"cases\":[" + cases + "],"
            + "\"fallback\":" + leaf("fb") + "}}";
    }

    /** A component select definition on a component, one {@code case} case with the given {@code when}, and a {@code fb} fallback. */
    private static String componentSelect(String component, String when) {
        return "{\"model\":" + componentSelectNode(component, when) + "}";
    }

    /** The node of {@link #componentSelect(String, String)}, to nest under another node. */
    private static String componentSelectNode(String component, String when) {
        return "{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\",\"component\":\"" + component + "\","
            + "\"cases\":[{\"when\":" + when + ",\"model\":" + leaf("case") + "}],\"fallback\":" + leaf("fb") + "}";
    }

    /** A component condition definition with the given {@code predicate} and {@code value} JSON. */
    private static String component(String predicate, String value) {
        return "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\",\"predicate\":" + predicate
            + ",\"value\":" + value + ",\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}";
    }

    /** A neutral GUI context carrying a render-time component map. */
    private static ItemModelContext withComponents(CompoundTag components) {
        return new ItemModelContext("gui", false, false, null, null, 0f, 0f, null, components);
    }

    /** A component map holding one component. */
    private static CompoundTag components(String id, Tag<?> value) {
        return compound(id, value);
    }

    /** A compound holding one entry. */
    private static CompoundTag compound(String key, Tag<?> value) {
        CompoundTag compound = new CompoundTag();
        compound.put(key, value);
        return compound;
    }

    /** A list of tags, all of one type. */
    private static ListTag<Tag<?>> list(Tag<?>... elements) {
        ListTag<Tag<?>> list = new ListTag<>();
        for (Tag<?> element : elements) list.add(element);
        return list;
    }

    @Nested
    @DisplayName("malformed numeric fields degrade instead of crashing")
    class MalformedInputs {

        @Test
        @DisplayName("a quoted non-numeric tint value degrades to white, not a NumberFormatException")
        void quotedTintValueDegrades() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/x\","
                + "\"tints\":[{\"type\":\"minecraft:dye\",\"default\":\"#ffffff\"}]}}");
            assertThat(r.tints(), contains(instanceOf(LayerTint.Dye.class)));
            assertThat(((LayerTint.Dye) r.tints().getFirst()).defaultColor(), is(0xFFFFFFFF));
        }

        @Test
        @DisplayName("a non-numeric range_dispatch threshold degrades to 0 instead of crashing")
        void nonNumericThresholdDegrades() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":\"min\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/f0\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/fb\"}}}");
            // threshold parses to 0 (default); scaled value 0 >= 0 -> entry f0, not a crash.
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/f0"));
        }

        @Test
        @DisplayName("a malformed transformation array degrades to identity instead of crashing")
        void malformedTransformDegrades() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/x\","
                + "\"model\":{\"type\":\"minecraft:bed\"},"
                + "\"transformation\":{\"translation\":[\"a\",1,2],\"scale\":[1,1,1],"
                + "\"left_rotation\":[0,0,0,1],\"right_rotation\":[0,0,0,1]}}}");
            assertThat(r.special().orElseThrow().transform().translation(), is(SpecialTransform.IDENTITY.translation()));
        }
    }

    @Nested
    @DisplayName("neutral defaults (parity contract)")
    class NeutralDefaults {

        @Test
        @DisplayName("bow -> on_false -> item/bow (unpulled)")
        void bow() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/bow\"},"
                + "\"on_true\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:use_duration\",\"scale\":0.05,"
                + "\"entries\":[{\"threshold\":0.65,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/bow_pulling_1\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/bow_pulling_0\"}}}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/bow"));
        }

        @Test
        @DisplayName("leather_boots -> fallback + dye tint")
        void leatherBoots() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":\"minecraft:iron\",\"model\":{\"type\":\"minecraft:model\","
                + "\"model\":\"minecraft:item/leather_boots_iron_trim\",\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/leather_boots\","
                + "\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-6265536}]}}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/leather_boots"));
            assertThat(r.tints(), contains(instanceOf(LayerTint.Dye.class)));
        }

        @Test
        @DisplayName("clock -> context_dimension overworld -> time 0 -> clock_00")
        void clock() {
            // The branches are given DIFFERENT models on purpose. Vanilla ships identical tables on
            // both, so a fixture mirroring it could not tell which branch was walked - and the
            // overworld case is the one that matters, being the only one whose dispatch reads the
            // daytime this renderer computes. Its twin is what a clock does outside the overworld:
            // spin at random.
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:context_dimension\","
                + "\"cases\":[{\"when\":\"minecraft:overworld\",\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_00\"}},"
                + "{\"threshold\":0.5,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_01\"}}]}}],"
                + "\"fallback\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_spinning\"}}]}}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/clock_00"));
        }

        @Test
        @DisplayName("compass -> has_component false -> on_false range_dispatch -> compass_16")
        void compass() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:has_component\","
                + "\"component\":\"minecraft:lodestone_tracker\","
                + "\"on_false\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:compass\",\"scale\":32.0,\"target\":\"spawn\","
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/compass_16\"}}]},"
                + "\"on_true\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:compass\",\"scale\":32.0,\"target\":\"lodestone\","
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/compass_00\"}}]}}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/compass_16"));
        }
    }

    /**
     * The time-dispatch search a caller's "animate this item" request derives its frame count from -
     * which, unlike resolution, has to see branches no offline context can select.
     */
    @Nested
    @DisplayName("time dispatch search")
    class TimeDispatchSearch {

        @Test
        @DisplayName("sees into a case no offline context can select")
        void seesIntoUnselectableCase() {
            // The dispatch sits in a case whose property is unevaluable, so resolution walks straight
            // past it to the fallback. Derivation asks what an item COULD animate rather than what it
            // renders right now, so the search must not be limited to the branch that renders.
            String tree = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\","
                + "\"cases\":[{\"when\":\"rocket\",\"model\":" + timeDispatch("minecraft:time", 64) + "}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/plain\"}}}";
            assertThat(ItemModelContext.gui().resolve(parse(tree)).modelId().orElseThrow(),
                is("minecraft:item/plain"));
            assertThat(parse(tree).timeDispatchSteps(), is(OptionalInt.of(64)));
        }
    }

}
