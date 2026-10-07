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

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static lib.minecraft.renderer.fixture.ItemModelFixtures.timeDispatch;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-node-type evaluation of {@link ItemModelContext#resolve(ItemModelNode)}, plus the
 * neutral-default resolutions the parity contract rests on (bow unpulled, leather_boots fallback+dye,
 * clock frame 0, compass neutral frame), the unknown-property fallback-branch degradation, and the
 * {@link ItemModelContext#timeDispatchSteps(ItemModelNode)} search that follows the branch a context
 * walks to the time table its frames are drawn from.
 *
 * <p>The decode a definition goes through on its way to the walk is pinned here too, through the real
 * deserializer: what vanilla's codec refuses throws - an unregistered vanilla-namespace type,
 * property or data component, a select on a component with no codec, an undecodable component value,
 * an empty case or {@code when} list, a case value repeated as decoded, a condition missing a branch,
 * a special model naming a kind vanilla does not register or missing a field its kind requires -
 * while a mod's namespace degrades where it sits, and an absent fallback stays apart from an explicit
 * {@code minecraft:empty}. The component walks follow, shaped as Hypixel+ writes its ladders:
 * {@code custom_data} conditions in both spellings, and {@code dyed_color}, {@code custom_name} and
 * {@code lore} selects; and as FurSky writes its {@code item_model} select, keyed by the stack's item.
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
            ItemModelContext using = new ItemModelContext("gui", true, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.empty());
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
            ItemModelContext iron = new ItemModelContext("gui", false, false, Optional.of("minecraft:iron"), 0f, 0f, Optional.empty(), Optional.empty());
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

            ItemModelContext half = new ItemModelContext("gui", false, false, Optional.empty(), 0.5f, 0f, Optional.empty(), Optional.empty());
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
        @DisplayName("composite answers every child that draws, in order, its first the resolution's own leaf")
        void composite() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":["
                + "{\"type\":\"minecraft:bundle/selected_item\"},"
                + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/first\"},"
                + "{\"type\":\"minecraft:empty\"},"
                + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/second\"}]}}");
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/first"));
            assertThat("a child that draws nothing is no layer", r.layers().stream().map(layer -> layer.modelId().orElseThrow()).toList(),
                contains("minecraft:item/first", "minecraft:item/second"));
            assertThat(r.later().stream().map(layer -> layer.modelId().orElseThrow()).toList(), contains("minecraft:item/second"));
        }

        @Test
        @DisplayName("a composite's layers each keep their own tints, a nested composite's in its place, and a later miss stays a layer")
        void compositeLayers() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":["
                + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/a\",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":16711680}]},"
                + "{\"type\":\"minecraft:composite\",\"models\":[" + leaf("b") + ",{\"type\":\"minecraft:empty\"}," + leaf("c") + "]},"
                + "{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\",\"cases\":[{\"when\":\"rocket\",\"model\":" + leaf("r") + "}]},"
                + "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/d\",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":255}]}]}}");

            List<ItemModelNode.Resolution> layers = r.layers();
            assertThat(layers.stream().map(layer -> layer.modelId().orElse("missing")).toList(),
                contains("minecraft:item/a", "minecraft:item/b", "minecraft:item/c", "missing", "minecraft:item/d"));
            assertThat("every layer is reached through the composite", layers.stream().allMatch(ItemModelNode.Resolution::composed), is(true));
            assertThat("no layer holds later layers of its own", layers.stream().allMatch(layer -> layer.later().isEmpty()), is(true));
            assertThat(layers.get(3).missing(), is(true));
            assertThat(layers.getFirst().tints(), is(r.tints()));
            assertThat(layers.getFirst().tints(), is(not(layers.getLast().tints())));
            assertThat(layers.get(1).tints(), is(empty()));

            assertThat("a composite whose children all draw nothing renders nothing",
                resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:empty\"}]}}").layers(), is(empty()));
            assertThat("a plain leaf is its own one layer", resolveNeutral("{\"model\":" + leaf("plain") + "}").layers(),
                contains(resolveNeutral("{\"model\":" + leaf("plain") + "}")));
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
            ItemModelContext override = new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.of(2f), Optional.of(customModelData(0f)));
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
            return new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.ofNullable(components));
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
            ItemModelContext iron = new ItemModelContext("gui", false, false, Optional.of("minecraft:iron"), 0f, 0f, Optional.empty(), Optional.empty());
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
        @DisplayName("refuses a vanilla-namespace component vanilla does not register, in every test that names one, and keeps a mod's")
        void refusesAnUnregisteredComponent() {
            String hasComponent = "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:has_component\","
                + "\"component\":\"%s\",\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}";
            assertThrows(JsonParseException.class, () -> parse(String.format(hasComponent, "minecraft:mystery_component")));
            assertThrows(JsonParseException.class, () -> parse(String.format(hasComponent, "mystery_component")));
            assertThrows(JsonParseException.class, () -> parse(component("\"minecraft:mystery_component\"", "{}")));
            assertThrows(JsonParseException.class, () -> parse(componentSelect("minecraft:mystery_component", "\"a\"")));
            assertThat(((ItemModelNode.Condition) parse(String.format(hasComponent, "somepack:marker"))).component(), is("somepack:marker"));
            assertThat(((ItemModelNode.Condition) parse(String.format(hasComponent, "lodestone_tracker"))).component(), is("lodestone_tracker"));
            assertThat(((ItemModelNode.Select) parse(componentSelect("somepack:marker", "\"a\""))).cases().getFirst().when(), contains("a"));
        }

        @Test
        @DisplayName("refuses a select on a component vanilla registers with no codec, which has_component still names")
        void refusesASelectOnATransientComponent() {
            // A select decodes its case values with the component's codec, and these three have none.
            for (String component : List.of("minecraft:creative_slot_lock", "additional_trade_cost", "map_post_processing"))
                assertThrows(JsonParseException.class, () -> parse(componentSelect(component, "\"a\"")));
            ItemModelNode.Condition condition = (ItemModelNode.Condition) parse("{\"model\":{\"type\":\"minecraft:condition\","
                + "\"property\":\"minecraft:has_component\",\"component\":\"minecraft:creative_slot_lock\","
                + "\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}");
            assertThat(condition.component(), is("minecraft:creative_slot_lock"));
        }

        @Test
        @DisplayName("refuses a special model missing a field its kind's codec requires, as vanilla refuses Hypixel+'s red_bed")
        void refusesASpecialMissingAField() {
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:bed\",\"texture\":\"minecraft:red\"}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"bed\",\"texture\":\"minecraft:red\",\"part\":{}}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:head\"}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:banner\"}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:book\",\"open_angle\":0,\"page1\":0}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:hanging_sign\"}")));

            ItemModelNode.Special bed = (ItemModelNode.Special) parse(special(
                "{\"type\":\"minecraft:bed\",\"texture\":\"minecraft:red\",\"part\":\"foot\"}"));
            assertThat(bed.fields().get("part"), is("foot"));
            assertThat(parse(special("{\"type\":\"minecraft:player_head\"}")), is(instanceOf(ItemModelNode.Special.class)));
            assertThat(parse(special("{\"type\":\"minecraft:bell\"}")), is(instanceOf(ItemModelNode.Special.class)));
            assertThat("a mod's kind is not one this decode can check", parse(special("{\"type\":\"somepack:statue\"}")),
                is(instanceOf(ItemModelNode.Special.class)));
        }

        @Test
        @DisplayName("refuses a special node with no base, no model object, or a model naming no type or a vanilla-namespace kind vanilla does not register")
        void refusesASpecialMissingItsMembers() {
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:shield\"}}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/x\"}}"));
            assertThrows(JsonParseException.class, () -> parse("{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/x\",\"model\":\"minecraft:shield\"}}"));
            assertThrows(JsonParseException.class, () -> parse(special("{}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"minecraft:statue\"}")));
            assertThrows(JsonParseException.class, () -> parse(special("{\"type\":\"statue\"}")));
            assertThat(parse(special("{\"type\":\"end_cube\",\"effect\":\"portal\"}")), is(instanceOf(ItemModelNode.Special.class)));
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
            ItemModelContext using = new ItemModelContext("gui", true, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.empty());
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
        @DisplayName("marks an absent fallback apart from an explicit minecraft:empty: the missing item model, and nothing")
        void marksAnAbsentFallback() {
            ItemModelNode.Select absent = (ItemModelNode.Select) parse("{\"model\":{\"type\":\"minecraft:select\","
                + "\"property\":\"minecraft:charge_type\",\"cases\":[{\"when\":\"rocket\",\"model\":" + leaf("r") + "}]}}");
            assertThat(absent.fallback(), is(ItemModelNode.Absent.INSTANCE));
            ItemModelNode.Select empty = (ItemModelNode.Select) parse(select("minecraft:charge_type",
                "{\"when\":\"rocket\",\"model\":" + leaf("r") + "}").replace(leaf("fb"), "{\"type\":\"minecraft:empty\"}"));
            assertThat(empty.fallback(), is(ItemModelNode.Empty.INSTANCE));
            // Vanilla bakes an absent fallback as its missing item model and an explicit empty as nothing.
            assertThat(ItemModelContext.gui().resolve(absent), is(ItemModelNode.Resolution.MISSING));
            assertThat(ItemModelContext.gui().resolve(absent).isEmpty(), is(false));
            assertThat(ItemModelContext.gui().resolve(empty), is(ItemModelNode.Resolution.NOTHING));
            assertThat(ItemModelContext.gui().resolve(empty).isEmpty(), is(true));
            // A composite whose only child misses reads as missing rather than skipping it as empty.
            ItemModelNode composite = parse("{\"model\":{\"type\":\"minecraft:composite\",\"models\":["
                + "{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\",\"cases\":[{\"when\":\"rocket\",\"model\":" + leaf("r") + "}]}]}}");
            assertThat(ItemModelContext.gui().resolve(composite).missing(), is(true));

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
            ItemModelNode tree = parse(componentSelect("minecraft:rarity", "\"rare\""));
            assertThat(withComponents(components("minecraft:rarity", new StringTag("rare")))
                .resolve(tree).modelId().orElseThrow(), is("minecraft:item/fb"));
        }

        @Test
        @DisplayName("walks an item_model select by the stack's own item model, else its item's id, missing where it declares no fallback")
        void walksAnItemModelSelect() {
            // FurSky's shape: a select at the root on item_model with no fallback, its cases the vanilla
            // items whose stacks reach the definition, and one case empty.
            ItemModelNode tree = parse("{\"model\":{\"type\":\"select\",\"property\":\"component\",\"component\":\"item_model\","
                + "\"cases\":[{\"when\":\"minecraft:lime_stained_glass_pane\",\"model\":" + leaf("unlocked") + "},"
                + "{\"when\":\"red_stained_glass_pane\",\"model\":{\"type\":\"empty\"}}]}}");
            ItemModelContext lime = ItemModelContext.gui().withItemId("lime_stained_glass_pane");
            assertThat(lime.resolve(tree).modelId().orElseThrow(), is("minecraft:item/unlocked"));
            assertThat(ItemModelContext.gui().withItemId("minecraft:red_stained_glass_pane").resolve(tree), is(ItemModelNode.Resolution.NOTHING));
            assertThat("the stack's own item model wins over its item's",
                lime.withComponents(components("minecraft:item_model", new StringTag("red_stained_glass_pane"))).resolve(tree),
                is(ItemModelNode.Resolution.NOTHING));
            assertThat("a removed item model reads as none",
                lime.withComponents(components("!minecraft:item_model", new CompoundTag())).resolve(tree), is(ItemModelNode.Resolution.MISSING));
            assertThat(ItemModelContext.gui().withItemId("minecraft:stone").resolve(tree), is(ItemModelNode.Resolution.MISSING));
            assertThat(ItemModelContext.gui().resolve(tree), is(ItemModelNode.Resolution.MISSING));
        }

        @Test
        @DisplayName("marks a resolution reached through a composite, and only that one")
        void marksACompositePath() {
            var composed = resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":[" + leaf("first") + "," + leaf("second") + "]}}");
            assertThat(composed.modelId().orElseThrow(), is("minecraft:item/first"));
            assertThat(composed.composed(), is(true));
            assertThat(composed.later().getFirst().modelId().orElseThrow(), is("minecraft:item/second"));
            assertThat(composed.later().getFirst().composed(), is(true));
            assertThat("a composite is never one block model",
                resolveNeutral("{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/stone\"}]}}")
                    .blockModel(), is(Optional.empty()));
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

    /** A special node definition over {@code minecraft:item/x} with the given inner {@code model} JSON. */
    private static String special(String model) {
        return "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/x\",\"model\":" + model + "}}";
    }

    /** A component condition definition with the given {@code predicate} and {@code value} JSON. */
    private static String component(String predicate, String value) {
        return "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\",\"predicate\":" + predicate
            + ",\"value\":" + value + ",\"on_true\":" + leaf("t") + ",\"on_false\":" + leaf("f") + "}}";
    }

    /** A neutral GUI context carrying a render-time component map. */
    private static ItemModelContext withComponents(CompoundTag components) {
        return new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.ofNullable(components));
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
                + "\"model\":{\"type\":\"minecraft:bed\",\"texture\":\"minecraft:red\",\"part\":\"head\"},"
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
     * The time-dispatch search a caller's "animate this item" request derives its frame count from,
     * which follows the branch the walk takes, because that branch is the one the frames are drawn
     * from.
     */
    @Nested
    @DisplayName("time dispatch search")
    class TimeDispatchSearch {

        @Test
        @DisplayName("follows the walk past a case no offline context can select")
        void followsTheWalkPastAnUnselectableCase() {
            // The table sits in a case whose property is unevaluable, so the walk takes the fallback,
            // and every frame draws it: a plain model with nothing to animate.
            String tree = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:charge_type\","
                + "\"cases\":[{\"when\":\"rocket\",\"model\":" + timeDispatch("minecraft:time", 64) + "}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/plain\"}}}";
            assertThat(ItemModelContext.gui().resolve(parse(tree)).modelId().orElseThrow(),
                is("minecraft:item/plain"));
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse(tree)), is(OptionalInt.empty()));
        }

        @Test
        @DisplayName("reaches the clock's table through the overworld pin")
        void reachesTheClocksTableThroughTheOverworldPin() {
            // The fallback stands for what a clock does outside the overworld, given a table of another
            // size so that the count says which branch was searched.
            String tree = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:context_dimension\","
                + "\"cases\":[{\"when\":\"minecraft:overworld\",\"model\":" + timeDispatch("minecraft:time", 64) + "}],"
                + "\"fallback\":" + timeDispatch("minecraft:time", 16) + "}}";
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse(tree)), is(OptionalInt.of(64)));
        }

        @Test
        @DisplayName("follows the case the display context selects")
        void followsTheDisplayContext() {
            String tree = select("minecraft:display_context",
                "{\"when\":\"thirdperson_righthand\",\"model\":" + timeDispatch("minecraft:time", 32) + "}");
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse(tree)), is(OptionalInt.empty()));
            assertThat(ItemModelContext.gui().withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND)
                .timeDispatchSteps(parse(tree)), is(OptionalInt.of(32)));
        }

        @Test
        @DisplayName("follows the branch a stack's components pick, as Hypixel+'s calendar-named clock does")
        void followsTheStacksBranch() {
            // Hypixel+'s shape: a custom_name select ahead of vanilla's tree, whose two named cases draw
            // one still calendar.
            String tree = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\","
                + "\"component\":\"minecraft:custom_name\",\"cases\":["
                + "{\"when\":\"Calendar\",\"model\":" + leaf("calendar") + "},"
                + "{\"when\":\"Calendar and Events\",\"model\":" + leaf("calendar") + "}],"
                + "\"fallback\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:context_dimension\","
                + "\"cases\":[{\"when\":\"minecraft:overworld\",\"model\":" + timeDispatch("minecraft:time", 64) + "}]}}}";
            ItemModelNode clock = parse(tree);
            for (String name : List.of("Calendar", "Calendar and Events"))
                assertThat(name, withComponents(components("minecraft:custom_name", new StringTag(name))).timeDispatchSteps(clock),
                    is(OptionalInt.empty()));
            assertThat(withComponents(components("minecraft:custom_name", new StringTag("Clock"))).timeDispatchSteps(clock),
                is(OptionalInt.of(64)));
            assertThat(ItemModelContext.gui().timeDispatchSteps(clock), is(OptionalInt.of(64)));
        }

        @Test
        @DisplayName("looks through every child of a composite, down the branch a condition takes")
        void looksThroughEveryChildOfAComposite() {
            // The composite's first child draws, and the table sits in its second, which vanilla draws
            // beside it. The branch the condition does not take holds a table of another size.
            String tree = "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:broken\","
                + "\"on_true\":" + timeDispatch("minecraft:time", 32) + ","
                + "\"on_false\":{\"type\":\"minecraft:composite\",\"models\":["
                + leaf("base") + "," + timeDispatch("minecraft:time", 8) + "]}}}";
            ItemModelContext broken = new ItemModelContext("gui", false, true, Optional.empty(), 0f, 0f, Optional.empty(), Optional.empty());
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse(tree)), is(OptionalInt.of(8)));
            assertThat(broken.timeDispatchSteps(parse(tree)), is(OptionalInt.of(32)));
        }

        @Test
        @DisplayName("follows the entry a range dispatch's input reaches")
        void followsTheEntryARangeDispatchReaches() {
            // A custom_model_data table whose second entry holds the time table, so only an input that
            // reaches it animates.
            String tree = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\","
                + "\"entries\":[{\"threshold\":0.0,\"model\":" + leaf("plain") + "},"
                + "{\"threshold\":1.0,\"model\":" + timeDispatch("minecraft:time", 32) + "}]}}";
            ItemModelContext reaches = new ItemModelContext("gui", false, false, Optional.empty(), 0f, 0f, Optional.of(1f), Optional.empty());
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse(tree)), is(OptionalInt.empty()));
            assertThat(reaches.timeDispatchSteps(parse(tree)), is(OptionalInt.of(32)));
        }

        @Test
        @DisplayName("finds nothing to animate in a plain model")
        void ignoresPlainModel() {
            assertThat(ItemModelContext.gui().timeDispatchSteps(parse("{\"model\":" + leaf("diamond_sword") + "}")),
                is(OptionalInt.empty()));
        }

    }

}
