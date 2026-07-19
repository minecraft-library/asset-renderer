package lib.minecraft.renderer.asset.pack.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.option.ItemModelContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Per-node-type evaluation of {@link ItemModelNode#resolve(ItemModelContext)} against
 * {@link ItemModelContext}, plus the neutral-default resolutions the parity contract rests on (bow
 * unpulled, leather_boots fallback+dye, clock frame 0, compass neutral frame) and the unknown-property
 * fallback-branch degradation.
 */
@DisplayName("ItemModelNode resolve evaluation")
class ItemModelNodeResolveTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    private static ItemModelNode parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return GSON.fromJson(root.getAsJsonObject("model"), ItemModelNode.class);
    }

    private static ItemModelNode.Resolution resolveNeutral(String json) {
        return parse(json).resolve(ItemModelContext.gui());
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
            var r = parse("{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                + "\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/on\"},"
                + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/off\"}}}").resolve(using);
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
            var r = parse("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:trim_material\","
                + "\"cases\":[{\"when\":[\"minecraft:gold\",\"minecraft:iron\"],\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/iron_trim\"}}],"
                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/plain\"}}}").resolve(iron);
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
                parse(tree).resolve(half).modelId().orElseThrow(), is("minecraft:item/f1"));
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
        @DisplayName("an unknown node type renders nothing (no fallback)")
        void unknownNode() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:mystery_future_node\"}}");
            assertThat(r.isEmpty(), is(true));
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
    }

    @Nested
    @DisplayName("component-driven dispatch (rung 3)")
    class ComponentDispatch {

        @Test
        @DisplayName("has_component takes on_true when the component map carries it")
        void hasComponentTrue() {
            CompoundTag components = new CompoundTag();
            components.put("minecraft:damage", 5);
            var r = parse(HAS_COMPONENT_TREE).resolve(withComponents(components));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/has"));
        }

        @Test
        @DisplayName("has_component takes on_false when the map lacks it (present but empty)")
        void hasComponentAbsent() {
            var r = parse(HAS_COMPONENT_TREE).resolve(withComponents(new CompoundTag()));
            assertThat(r.modelId().orElseThrow(), is("minecraft:item/lacks"));
        }

        @Test
        @DisplayName("custom_model_data reads floats[0] from the component tree")
        void customModelDataFromComponents() {
            assertThat("floats[0]=2 -> threshold 2 -> custom",
                parse(CMD_TREE).resolve(withComponents(customModelData(2f))).modelId().orElseThrow(), is("minecraft:item/custom"));
            assertThat("floats[0]=0 -> base",
                parse(CMD_TREE).resolve(withComponents(customModelData(0f))).modelId().orElseThrow(), is("minecraft:item/base"));
        }

        @Test
        @DisplayName("an explicit custom_model_data override wins over the component tree")
        void customModelDataOverrideWins() {
            ItemModelContext override = new ItemModelContext("gui", false, false, null, null, 0f, 0f, 2f, customModelData(0f));
            assertThat(parse(CMD_TREE).resolve(override).modelId().orElseThrow(), is("minecraft:item/custom"));
        }

        @Test
        @DisplayName("range_dispatch index selects the floats entry the node points at")
        void customModelDataIndex() {
            String indexed = CMD_TREE.replace("\"scale\":1.0,", "\"scale\":1.0,\"index\":1,");
            // floats[0]=0 (would pick base), floats[1]=2 (picks custom) - so index 1 must be honoured.
            assertThat(parse(indexed).resolve(withComponents(customModelData(0f, 2f))).modelId().orElseThrow(), is("minecraft:item/custom"));
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
        @DisplayName("clock -> context_dimension fallback -> time 0 -> clock_00")
        void clock() {
            var r = resolveNeutral("{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:context_dimension\","
                + "\"cases\":[{\"when\":\"minecraft:overworld\",\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_00\"}},"
                + "{\"threshold\":0.5,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_01\"}}]}}],"
                + "\"fallback\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\",\"scale\":64.0,"
                + "\"entries\":[{\"threshold\":0.0,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_00\"}}]}}}");
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

}
