package lib.minecraft.renderer.option;

import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The degradation contract {@link ItemModelContext} answers a dispatch property with when it has no
 * way to evaluate it - what each accessor reads for a property, a component or a float-list index an
 * offline icon has no world, stack or gameplay state to resolve.
 *
 * <p>Each answer picks an arm of a shipped vanilla item tree, so a moved one re-points an icon at a
 * different model rather than failing. Two of them look neutral and are not: {@code context_dimension}
 * is answered rather than degraded, because a clock's fallback dispatches on a random source, and the
 * neutral time input is exactly {@code +0.0f}, because a {@code -0.0f} compares equal under {@code ==}
 * yet unequal under record equality and so costs every item its baked fast path.
 *
 * <p>Three answers are pinned as observed rather than as documented: property matching strips any
 * namespace and not only {@code minecraft:}; it cuts at the first colon alone, so a doubly qualified
 * id degrades; and an explicit {@code custom_model_data} override is read before the index is
 * range-checked, so it wins at an index no float list has.
 */
@DisplayName("ItemModelContext degradation")
class ItemModelContextTest {

    /** A context carrying every caller override a tree can branch on, to tell an echoed answer from a fixed one. */
    private static ItemModelContext populated(CompoundTag components) {
        return new ItemModelContext("fixed", true, true, "minecraft:iron", 0x112233, 0.25f, 0.75f, null, components);
    }

    /** A neutral GUI context carrying a render-time component map. */
    private static ItemModelContext withComponents(CompoundTag components) {
        return new ItemModelContext(ItemModelContext.DISPLAY_CONTEXT_GUI,
            false, false, null, null, 0f, 0f, null, components);
    }

    /** Builds a component map whose {@code minecraft:custom_model_data} entry carries the given {@code floats} list. */
    private static CompoundTag customModelData(float... floats) {
        ListTag<FloatTag> list = new ListTag<>();
        for (float value : floats) list.add(new FloatTag(value));
        CompoundTag component = new CompoundTag();
        component.put("floats", list);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_model_data", component);
        return components;
    }

    @Nested
    @DisplayName("property ids")
    class PropertyIds {

        @Test
        @DisplayName("accepts a property with or without its namespace")
        void acceptsBothIdForms() {
            ItemModelContext using = populated(null);
            assertThat(using.conditionValue("minecraft:using_item"), is(true));
            assertThat(using.conditionValue("using_item"), is(true));
            assertThat(using.selectValue("minecraft:trim_material"), is(Optional.of("minecraft:iron")));
            assertThat(using.selectValue("trim_material"), is(Optional.of("minecraft:iron")));
            assertThat(using.rangeValue("minecraft:compass"), is(0.75f));
            assertThat(using.rangeValue("compass"), is(0.75f));
        }

        @Test
        @DisplayName("strips any namespace, not only the vanilla one")
        void stripsAnyNamespace() {
            // Observed, and wider than the accessor javadoc's "leading minecraft: namespace": the match
            // is on whatever follows the colon, so a pack's own namespace resolves the vanilla property.
            ItemModelContext using = populated(null);
            assertThat(using.conditionValue("somepack:using_item"), is(true));
            assertThat(using.selectValue("somepack:display_context"), is(Optional.of("fixed")));
            assertThat(using.rangeValue("somepack:time"), is(0.25f));
        }

        @Test
        @DisplayName("cuts at the first colon alone, so a doubly qualified id degrades")
        void cutsAtTheFirstColon() {
            // One substring, not a loop: what is left still carries a colon and matches no case, so the
            // property falls through to its unevaluable answer rather than resolving.
            ItemModelContext using = populated(null);
            assertThat(using.conditionValue("somepack:minecraft:using_item"), is(false));
            assertThat(using.selectValue("somepack:minecraft:trim_material"), is(Optional.empty()));
            assertThat(using.rangeValue("somepack:minecraft:time"), is(0f));
        }

        @Test
        @DisplayName("treats a bare leading colon as a namespace")
        void treatsBareColonAsNamespace() {
            assertThat(populated(null).conditionValue(":using_item"), is(true));
        }

    }

    @Nested
    @DisplayName("condition properties")
    class ConditionProperties {

        @Test
        @DisplayName("reads the two flags a caller can supply")
        void readsTheSuppliedFlags() {
            assertThat(populated(null).conditionValue("using_item"), is(true));
            assertThat(populated(null).conditionValue("broken"), is(true));
            assertThat(ItemModelContext.gui().conditionValue("using_item"), is(false));
            assertThat(ItemModelContext.gui().conditionValue("broken"), is(false));
        }

        @Test
        @DisplayName("reads every live gameplay flag as false, taking the on_false branch")
        void readsGameplayFlagsAsFalse() {
            ItemModelContext carrying = populated(customModelData(1f));
            assertThat(carrying.conditionValue("damaged"), is(false));
            assertThat(carrying.conditionValue("fishing_rod/cast"), is(false));
            assertThat(carrying.conditionValue("minecraft:selected"), is(false));
        }

        @Test
        @DisplayName("reads a property no version of this renderer knows as false rather than failing")
        void readsAnUnknownPropertyAsFalse() {
            assertThat(ItemModelContext.gui().conditionValue("minecraft:mystery_future_flag"), is(false));
            assertThat(ItemModelContext.gui().conditionValue(""), is(false));
        }

        @Test
        @DisplayName("cannot evaluate has_component without the tested component id")
        void cannotEvaluateHasComponentWithoutTheComponentId() {
            // The single-argument form is handed no component to look up, so it degrades even when the
            // map plainly carries one - the two-argument form is the only one that can answer.
            ItemModelContext carrying = withComponents(customModelData(1f));
            assertThat(carrying.conditionValue("has_component"), is(false));
            assertThat(carrying.conditionValue("minecraft:has_component"), is(false));
            assertThat(carrying.conditionValue("has_component", "minecraft:custom_model_data"), is(true));
        }

        @Test
        @DisplayName("ignores the component argument for every property but has_component")
        void ignoresTheComponentForOtherProperties() {
            ItemModelContext carrying = populated(customModelData(1f));
            assertThat(carrying.conditionValue("using_item", "minecraft:custom_model_data"), is(true));
            assertThat(carrying.conditionValue("using_item", "minecraft:absent"), is(true));
            assertThat(carrying.conditionValue("damaged", "minecraft:custom_model_data"), is(false));
        }

    }

    @Nested
    @DisplayName("component presence")
    class ComponentPresence {

        @Test
        @DisplayName("reads every component as absent when the caller supplied no stack")
        void readsEveryComponentAsAbsentWithoutAStack() {
            assertThat(ItemModelContext.gui().hasComponent("minecraft:custom_model_data"), is(false));
            assertThat(ItemModelContext.gui().conditionValue("has_component", "minecraft:damage"), is(false));
        }

        @Test
        @DisplayName("reads an absent component as absent from a supplied map")
        void readsAnAbsentComponentFromASuppliedMap() {
            assertThat(withComponents(new CompoundTag()).hasComponent("minecraft:damage"), is(false));
            assertThat(withComponents(customModelData(1f)).hasComponent("minecraft:damage"), is(false));
        }

        @Test
        @DisplayName("qualifies an unprefixed component id with the vanilla namespace")
        void qualifiesAnUnprefixedComponentId() {
            CompoundTag components = new CompoundTag();
            components.put("minecraft:damage", 5);
            assertThat(withComponents(components).hasComponent("damage"), is(true));
            assertThat(withComponents(components).hasComponent("minecraft:damage"), is(true));
        }

        @Test
        @DisplayName("keeps a foreign namespace rather than requalifying it")
        void keepsAForeignNamespace() {
            CompoundTag components = new CompoundTag();
            components.put("somepack:marker", 1);
            assertThat(withComponents(components).hasComponent("somepack:marker"), is(true));
            assertThat(withComponents(components).hasComponent("marker"), is(false));
        }

        @Test
        @DisplayName("misses a map key that is itself unqualified, the lookup being qualified first")
        void missesAnUnqualifiedMapKey() {
            // Qualification runs on the id being looked up and never on the map's keys, so the two meet
            // only when the caller's map is keyed the way vanilla writes it - fully qualified.
            CompoundTag components = new CompoundTag();
            components.put("damage", 5);
            assertThat(withComponents(components).hasComponent("damage"), is(false));
            assertThat(withComponents(components).hasComponent("minecraft:damage"), is(false));
        }

        @Test
        @DisplayName("answers presence without reading the component's value")
        void answersPresenceWithoutReadingTheValue() {
            // The presence test is a key probe, so a component of the wrong shape is present here and
            // still degrades to 0 where its value is read.
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_model_data", "not-a-compound");
            assertThat(withComponents(components).hasComponent("custom_model_data"), is(true));
            assertThat(withComponents(components).rangeValue("custom_model_data"), is(0f));
        }

    }

    @Nested
    @DisplayName("range inputs")
    class RangeInputs {

        @Test
        @DisplayName("reads the two numeric inputs a caller can supply")
        void readsTheSuppliedInputs() {
            assertThat(populated(null).rangeValue("time"), is(0.25f));
            assertThat(populated(null).rangeValue("compass"), is(0.75f));
            assertThat(ItemModelContext.gui().rangeValue("time"), is(0f));
            assertThat(ItemModelContext.gui().rangeValue("compass"), is(0f));
        }

        @Test
        @DisplayName("reads every gameplay input as zero, the neutral end of its dispatch table")
        void readsGameplayInputsAsZero() {
            ItemModelContext carrying = populated(customModelData(9f));
            assertThat(carrying.rangeValue("use_duration"), is(0f));
            assertThat(carrying.rangeValue("minecraft:damage"), is(0f));
            assertThat(carrying.rangeValue("minecraft:mystery_future_input"), is(0f));
        }

        @Test
        @DisplayName("keeps the neutral time input at positive zero through the tick view")
        void keepsTheNeutralTimeInputPositiveZero() {
            // Not merely == 0f: a -0.0f passes that comparison and still breaks record equality, which
            // is what the baked fast path and its baked tints are selected by.
            float neutral = ItemModelContext.gui().atTick(0).rangeValue("minecraft:time");
            assertThat(Float.floatToRawIntBits(neutral), is(0));
        }

        @Test
        @DisplayName("reads the float list at index zero when the node names no index")
        void readsIndexZeroByDefault() {
            ItemModelContext carrying = withComponents(customModelData(7f, 9f));
            assertThat(carrying.rangeValue("custom_model_data"), is(7f));
            assertThat(carrying.rangeValue("minecraft:custom_model_data", 0), is(7f));
            assertThat(carrying.rangeValue("custom_model_data", 1), is(9f));
        }

        @Test
        @DisplayName("reads an index the float list does not reach as zero")
        void readsAnIndexOffTheListAsZero() {
            ItemModelContext carrying = withComponents(customModelData(7f, 9f));
            assertThat(carrying.rangeValue("custom_model_data", 2), is(0f));
            assertThat(carrying.rangeValue("custom_model_data", -1), is(0f));
            assertThat(withComponents(customModelData()).rangeValue("custom_model_data"), is(0f));
        }

        @Test
        @DisplayName("reads a missing map, component or float list as zero")
        void readsAMissingFloatListAsZero() {
            assertThat(ItemModelContext.gui().rangeValue("custom_model_data"), is(0f));
            assertThat(withComponents(new CompoundTag()).rangeValue("custom_model_data"), is(0f));

            CompoundTag component = new CompoundTag();
            component.put("colors", 1);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_model_data", component);
            assertThat(withComponents(components).rangeValue("custom_model_data"), is(0f));
        }

        @Test
        @DisplayName("reads a list of the wrong tag type as zero rather than coercing it")
        void readsANonFloatListAsZero() {
            // An int list is numerically fine and still degrades: the entry has to be a float tag, which
            // is what vanilla writes, so a hand-built component of the wrong type reads neutral.
            ListTag<IntTag> ints = new ListTag<>();
            ints.add(new IntTag(3));
            CompoundTag component = new CompoundTag();
            component.put("floats", ints);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:custom_model_data", component);
            assertThat(withComponents(components).rangeValue("custom_model_data"), is(0f));
        }

        @Test
        @DisplayName("lets an explicit override win at every index, including one no list reaches")
        void letsTheOverrideWinAtEveryIndex() {
            // Observed ordering: the override is read before the index is range-checked and before the
            // component map is touched, so it answers uniformly rather than only at index zero.
            ItemModelContext override = new ItemModelContext(ItemModelContext.DISPLAY_CONTEXT_GUI,
                false, false, null, null, 0f, 0f, 4f, customModelData(7f, 9f));
            assertThat(override.rangeValue("custom_model_data"), is(4f));
            assertThat(override.rangeValue("custom_model_data", 1), is(4f));
            assertThat(override.rangeValue("custom_model_data", 5), is(4f));
            assertThat(override.rangeValue("custom_model_data", -1), is(4f));
        }

    }

    @Nested
    @DisplayName("select keys")
    class SelectKeys {

        @Test
        @DisplayName("echoes the display context the caller renders at")
        void echoesTheDisplayContext() {
            assertThat(ItemModelContext.gui().selectValue("display_context"),
                is(Optional.of(ItemModelContext.DISPLAY_CONTEXT_GUI)));
            assertThat(populated(null).selectValue("display_context"), is(Optional.of("fixed")));
        }

        @Test
        @DisplayName("answers the dimension as a fixed key no caller can move")
        void answersTheDimensionAsAFixedKey() {
            // Degrading here is not the neutral choice it looks like: a tree's dimension fallback is
            // written for where the item misbehaves, and a clock's dispatches on a random source.
            assertThat(populated(customModelData(1f)).selectValue("context_dimension"),
                is(Optional.of(ItemModelContext.DIMENSION_OVERWORLD)));
        }

        @Test
        @DisplayName("leaves an unsupplied trim material unevaluable")
        void leavesAnUnsuppliedTrimMaterialUnevaluable() {
            assertThat(ItemModelContext.gui().selectValue("trim_material"), is(Optional.empty()));
            assertThat(populated(null).selectValue("trim_material"), is(Optional.of("minecraft:iron")));
        }

        @Test
        @DisplayName("leaves every other select property unevaluable, taking the no-case-match fallback")
        void leavesEveryOtherPropertyUnevaluable() {
            ItemModelContext carrying = populated(customModelData(1f));
            assertThat(carrying.selectValue("charge_type"), is(Optional.empty()));
            assertThat(carrying.selectValue("minecraft:block_state"), is(Optional.empty()));
            assertThat(carrying.selectValue("custom_model_data"), is(Optional.empty()));
            assertThat(carrying.selectValue("minecraft:mystery_future_key"), is(Optional.empty()));
        }

    }

}
