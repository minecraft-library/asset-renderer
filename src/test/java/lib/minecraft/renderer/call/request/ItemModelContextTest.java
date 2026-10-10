package lib.minecraft.renderer.call.request;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import dev.simplified.util.Possible;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.ByteArrayTag;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.DoubleTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntArrayTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.LongArrayTag;
import lib.minecraft.nbt.tag.LongTag;
import lib.minecraft.nbt.tag.ShortTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.asset.item.ItemModelNode.ComponentPredicate;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.vanilla.DecodedComponent;
import lib.minecraft.renderer.vanilla.SunAngle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The degradation contract {@link ItemModelContext} answers a dispatch property with when it has no
 * way to evaluate it - what each accessor reads for a property, a component or a float-list index an
 * offline icon has no world, stack or gameplay state to resolve - and the component tests it answers
 * when the caller supplies a stack.
 *
 * <p>Each answer picks an arm of a shipped vanilla item tree, so a moved one re-points an icon at a
 * different model rather than failing. Two of them look neutral and are not: {@code context_dimension}
 * is answered rather than degraded, because a clock's fallback dispatches on a random source, and the
 * neutral time input is exactly {@code +0.0f}, because a {@code -0.0f} compares equal under {@code ==}
 * yet unequal under record equality and so costs every item its baked fast path.
 *
 * <p>Property ids are read namespace-exact, as vanilla parses an identifier: a bare id, an empty
 * namespace and {@code minecraft:} all name vanilla's property, and any other namespace names none, a
 * doubly qualified id included. One answer is pinned as observed rather than as documented: an explicit
 * {@code custom_model_data} override is read before the index is range-checked, so it wins at an index
 * no float list has.
 *
 * <p>The component tests are pinned to vanilla 26.1's measured answers. A {@code custom_data} value
 * decodes with vanilla's typing - a JSON {@code 1} a byte, an SNBT {@code 1} an int - and matches by a
 * port of {@code NbtUtils.compareNbt} with {@code partial} set, a stack with no custom data tested as
 * {@code {}}. A select on {@code dyed_color}, {@code custom_name}, {@code lore} or {@code item_model}
 * reduces both sides to one key, so equality is the decoded value's: a text component's style flags are
 * three-state, its colour compares by spelling and its structure counts, and an item model is an
 * identifier compared qualified. {@code item_model} is also the one default component the tests read:
 * a stack whose patch neither sets nor removes it holds its item's own id, as every 26.1 item does.
 *
 * <p>The {@link ItemModelContext#atTick(int)} view is pinned beside them: it samples the
 * {@link SunAngle} day curve into the time input, answers the neutral context unchanged at tick zero,
 * and carries every other override across. So is the
 * {@link ItemModelContext#withDisplayContext(String)} view, which swaps the display-context key alone
 * and leaves the neutral context neutral only at {@code gui}, and the component-patch views:
 * {@link ItemModelContext#withComponents(CompoundTag)} holds a deep copy of the caller's compound, so a
 * mutation after the call changes no answer, {@link ItemModelContext#withItemId(String)} holds the
 * item id qualified, and {@link ItemModelContext#withoutComponents()} drops both.
 */
@DisplayName("ItemModelContext degradation")
class ItemModelContextTest {

    /** The leaf a condition built here takes when true. */
    private static final ItemModelNode ON_TRUE = new ItemModelNode.Model("minecraft:item/on", Concurrent.newUnmodifiableList());

    /** The leaf a condition built here takes when false. */
    private static final ItemModelNode ON_FALSE = new ItemModelNode.Model("minecraft:item/off", Concurrent.newUnmodifiableList());

    /** A context carrying every caller override a tree can branch on, to tell an echoed answer from a fixed one. */
    private static ItemModelContext populated(CompoundTag components) {
        return new ItemModelContext("fixed", true, true, Optional.of("minecraft:iron"), 0.25f, 0.75f, Optional.empty(), Optional.ofNullable(components));
    }

    /** A neutral GUI context carrying a render-time component map. */
    private static ItemModelContext withComponents(CompoundTag components) {
        return new ItemModelContext(ItemModelContext.DISPLAY_CONTEXT_GUI,
            false, false, Optional.empty(), 0f, 0f, Optional.empty(), Optional.ofNullable(components));
    }

    /** A condition on a property carrying a component id and no component test. */
    private static ItemModelNode.Condition condition(String property, String component) {
        return new ItemModelNode.Condition(property, component, ON_TRUE, ON_FALSE);
    }

    /** The reader the loader decodes a definition with, its node adapter registered. */
    private static final Gson GSON = GsonSettings.defaults().create();

    /** A {@code minecraft:model} leaf, for the branches of a node decoded here. */
    private static final String LEAF = "{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/x\"}";

    /** Decodes a node's JSON through the definition reader. */
    private static ItemModelNode parse(String node) {
        return GSON.fromJson(JsonParser.parseString(node), ItemModelNode.class);
    }

    /** Decodes a component condition's test through the definition reader, from its predicate id and its value JSON, or with no value where that is {@code null}. */
    private static ComponentPredicate predicate(String predicate, String value) {
        String member = value == null ? "" : ",\"value\":" + value;
        ItemModelNode node = parse("{\"type\":\"minecraft:condition\",\"property\":\"minecraft:component\",\"predicate\":\"" + predicate + "\""
            + member + ",\"on_true\":" + LEAF + ",\"on_false\":" + LEAF + "}");
        return ((ItemModelNode.Condition) node).predicate().orElseThrow();
    }

    /** Reads a component id through the definition reader as the target of a {@code has_component} condition, which holds it as read. */
    private static String componentId(String component) {
        ItemModelNode node = parse("{\"type\":\"minecraft:condition\",\"property\":\"minecraft:has_component\",\"component\":\"" + component + "\","
            + "\"on_true\":" + LEAF + ",\"on_false\":" + LEAF + "}");
        return ((ItemModelNode.Condition) node).component();
    }

    /** Decodes a component select's one case through the definition reader, into the keys its {@code when} JSON holds. */
    private static ConcurrentList<String> cases(String component, String when) {
        ItemModelNode node = parse("{\"type\":\"minecraft:select\",\"property\":\"minecraft:component\",\"component\":\"" + component + "\","
            + "\"cases\":[{\"when\":" + when + ",\"model\":" + LEAF + "}]}");
        return ((ItemModelNode.Select) node).cases().getFirst().when();
    }

    /** A component map holding one component. */
    private static CompoundTag components(String id, Tag<?> value) {
        CompoundTag components = new CompoundTag();
        components.put(id, value);
        return components;
    }

    /** Decodes a {@code custom_data} predicate from a JSON or SNBT {@code value}. */
    private static ComponentPredicate customData(String value) {
        return predicate("minecraft:custom_data", value);
    }

    /** The expected compound a {@code custom_data} predicate decoded. */
    private static CompoundTag expected(String value) {
        return ((ComponentPredicate.CustomData) customData(value)).expected();
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
            assertThat(using.selectValue("minecraft:trim_material"), is(Possible.of("minecraft:iron")));
            assertThat(using.selectValue("trim_material"), is(Possible.of("minecraft:iron")));
            assertThat(using.rangeValue("minecraft:compass"), is(0.75f));
            assertThat(using.rangeValue("compass"), is(0.75f));
        }

        @Test
        @DisplayName("reads a foreign namespace as no vanilla property, even on a vanilla property's path")
        void readsAForeignNamespaceAsUnevaluable() {
            // Vanilla parses the id as an identifier, so hplus:using_item is a mod's property and not
            // using_item: the walk degrades it rather than answering the vanilla flag.
            ItemModelContext using = populated(null);
            assertThat(using.conditionValue("somepack:using_item"), is(false));
            assertThat(using.selectValue("somepack:display_context"), is(Possible.absent()));
            assertThat(using.rangeValue("somepack:time"), is(0f));
        }

        @Test
        @DisplayName("reads a doubly qualified id as no vanilla property")
        void readsADoublyQualifiedIdAsUnevaluable() {
            // A foreign namespace ahead of minecraft: is still foreign, and minecraft: ahead of another
            // minecraft: leaves a path that is no property.
            ItemModelContext using = populated(null);
            assertThat(using.conditionValue("somepack:minecraft:using_item"), is(false));
            assertThat(using.selectValue("somepack:minecraft:trim_material"), is(Possible.absent()));
            assertThat(using.rangeValue("minecraft:minecraft:time"), is(0f));
        }

        @Test
        @DisplayName("reads a bare leading colon as the vanilla namespace, as vanilla parses an identifier")
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
            // The property-id form is handed no component to look up, so it degrades even when the map
            // plainly carries one - only the node, which names the component, can answer.
            ItemModelContext carrying = withComponents(customModelData(1f));
            assertThat(carrying.conditionValue("has_component"), is(false));
            assertThat(carrying.conditionValue("minecraft:has_component"), is(false));
            assertThat(carrying.conditionValue(condition("has_component", "minecraft:custom_model_data")), is(true));
        }

        @Test
        @DisplayName("reads the component a node names for has_component alone, and its predicate for component alone")
        void ignoresTheComponentForOtherProperties() {
            // A node's component member means the has_component target and nothing else: a flag
            // property with one still answers its flag, and has_component under a foreign namespace is
            // no has_component at all.
            ItemModelContext carrying = populated(customModelData(1f));
            assertThat(carrying.conditionValue(condition("using_item", "minecraft:custom_model_data")), is(true));
            assertThat(carrying.conditionValue(condition("using_item", "minecraft:absent")), is(true));
            assertThat(carrying.conditionValue(condition("damaged", "minecraft:custom_model_data")), is(false));
            assertThat(carrying.conditionValue(condition("somepack:has_component", "minecraft:custom_model_data")), is(false));
            // A component condition built without a decoded predicate has nothing to test, and degrades.
            assertThat(carrying.conditionValue(condition("minecraft:component", "minecraft:custom_model_data")), is(false));
        }

    }

    @Nested
    @DisplayName("component presence")
    class ComponentPresence {

        @Test
        @DisplayName("reads every component as absent when the caller supplied no stack")
        void readsEveryComponentAsAbsentWithoutAStack() {
            assertThat(ItemModelContext.gui().hasComponent("minecraft:custom_model_data"), is(false));
            assertThat(ItemModelContext.gui().hasComponent("minecraft:custom_model_data", true), is(false));
            assertThat(ItemModelContext.gui().conditionValue(condition("has_component", "minecraft:damage")), is(false));
        }

        @Test
        @DisplayName("reads a removed component as absent, and as present under ignore_default")
        void readsARemovalPerIgnoreDefault() {
            // Vanilla answers ignore_default with patch.containsKey, under which a removal is an entry:
            // the component is gone and the flag still answers true.
            ItemModelContext removed = withComponents(components("!minecraft:dyed_color", new CompoundTag()));
            assertThat(removed.hasComponent("minecraft:dyed_color"), is(false));
            assertThat(removed.hasComponent("dyed_color", true), is(true));
            assertThat(removed.conditionValue(new ItemModelNode.Condition(
                "minecraft:has_component", "minecraft:dyed_color", true, Optional.empty(), ON_TRUE, ON_FALSE)), is(true));
            assertThat(removed.conditionValue(condition("minecraft:has_component", "minecraft:dyed_color")), is(false));

            ItemModelContext set = withComponents(components("minecraft:dyed_color", new IntTag(0xFF0000)));
            assertThat(set.hasComponent("dyed_color"), is(true));
            assertThat(set.hasComponent("dyed_color", true), is(true));
            assertThat(withComponents(new CompoundTag()).hasComponent("dyed_color", true), is(false));
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

        @Test
        @DisplayName("reads item_model as the item's own id where the patch neither sets nor removes it, and no other default")
        void readsTheItemModelDefault() {
            // Every 26.1 item holds its own id as its item_model by default, so has_component and the
            // presence form count it; ignore_default reads the patch alone, where the default is no entry.
            ItemModelContext diamond = ItemModelContext.gui().withItemId("diamond");
            assertThat(diamond.hasComponent("minecraft:item_model"), is(true));
            assertThat(diamond.hasComponent("item_model", true), is(false));
            assertThat(diamond.hasComponent("minecraft:max_stack_size"), is(false));
            assertThat(diamond.conditionValue(presence("item_model")), is(true));
            assertThat(ItemModelContext.gui().hasComponent("minecraft:item_model"), is(false));
            assertThat(ItemModelContext.gui().conditionValue(presence("item_model")), is(false));

            ItemModelContext removed = diamond.withComponents(components("!minecraft:item_model", new CompoundTag()));
            assertThat(removed.hasComponent("minecraft:item_model"), is(false));
            assertThat(removed.hasComponent("minecraft:item_model", true), is(true));
            assertThat(removed.conditionValue(presence("item_model")), is(false));

            ItemModelContext set = diamond.withComponents(components("minecraft:item_model", new StringTag("minecraft:stone")));
            assertThat(set.hasComponent("minecraft:item_model", true), is(true));
            assertThat(set.conditionValue(presence("item_model")), is(true));
        }

        /** A component condition testing a component's presence. */
        private static ItemModelNode.Condition presence(String component) {
            return new ItemModelNode.Condition("minecraft:component", "", false,
                Optional.of(predicate(component, "{}")), ON_TRUE, ON_FALSE);
        }

    }

    @Nested
    @DisplayName("custom_data predicate")
    class CustomDataPredicate {

        @Test
        @DisplayName("decodes a JSON 1 as a byte and an SNBT 1 as an int")
        void decodesWithVanillaTyping() {
            // Vanilla's lenient codec reads a string as SNBT, where an unsuffixed integer is an int, and an
            // object through JsonOps.convertTo, which narrows by magnitude - so the two spellings are two
            // different tests.
            assertThat(expected("{\"x\":1}").get("x"), instanceOf(ByteTag.class));
            assertThat(expected("\"{x:1}\"").get("x"), instanceOf(IntTag.class));
            assertThat(expected("\"{'edition': 1}\"").get("edition"), instanceOf(IntTag.class));
        }

        @Test
        @DisplayName("narrows a JSON number to the smallest tag that holds it")
        void narrowsJsonNumbers() {
            CompoundTag decoded = expected("{\"s\":300,\"i\":70000,\"l\":3000000000,\"f\":1.5,\"d\":0.1,\"w\":1.0,\"b\":true}");
            assertThat(decoded.get("s"), instanceOf(ShortTag.class));
            assertThat(decoded.get("i"), instanceOf(IntTag.class));
            assertThat(decoded.get("l"), instanceOf(LongTag.class));
            assertThat(decoded.get("f"), instanceOf(FloatTag.class));
            assertThat(decoded.get("d"), instanceOf(DoubleTag.class));
            assertThat("an integral 1.0 is an integer", decoded.get("w"), instanceOf(ByteTag.class));
            assertThat(decoded.get("b"), is(new ByteTag((byte) 1)));
        }

        @Test
        @DisplayName("matches a byte-typed value against a byte and never against an int")
        void matchesTagTypeStrictly() {
            assertThat(customData("{\"x\":1}").matches(Optional.of(components("minecraft:custom_data", compound("x", new IntTag(1))))), is(false));
            assertThat(customData("{\"x\":1}").matches(Optional.of(components("minecraft:custom_data", compound("x", new ByteTag((byte) 1))))), is(true));
            assertThat(customData("\"{x:1}\"").matches(Optional.of(components("minecraft:custom_data", compound("x", new IntTag(1))))), is(true));
        }

        @Test
        @DisplayName("decodes a JSON array to a plain list, not an array tag")
        void decodesAnArrayToAPlainList() {
            assertThat(expected("{\"e\":[1,2]}").get("e"), is(list(new ByteTag((byte) 1), new ByteTag((byte) 2))));
        }

        @Test
        @DisplayName("writes a mixed JSON array as vanilla's binary form, each element not already a compound wrapped")
        void wrapsAMixedArray() {
            // nbt-factory's list holds one element type, so the wrapped form is the one both sides can
            // carry - and the one a stack read from binary NBT holds.
            CompoundTag object = compound("k", new ByteTag((byte) 2));
            ListTag<Tag<?>> wrapped = list(compound("", new ByteTag((byte) 1)), compound("", new StringTag("a")), object);
            assertThat(expected("{\"m\":[1,\"a\",{\"k\":2}]}").get("m"), is(wrapped));
        }

        @Test
        @DisplayName("passes a compound subset whatever extra keys the stack holds")
        void passesACompoundSubset() {
            CompoundTag held = compound("id", new StringTag("ASPECT_OF_THE_END"));
            held.put("uuid", new StringTag("x"));
            assertThat(customData("{\"id\":\"ASPECT_OF_THE_END\"}").matches(Optional.of(components("minecraft:custom_data", held))), is(true));
            assertThat(customData("{\"id\":\"ASPECT_OF_THE_END\",\"edition\":1}").matches(Optional.of(components("minecraft:custom_data", held))), is(false));
            assertThat(customData("{\"id\":\"HYPERION\"}").matches(Optional.of(components("minecraft:custom_data", held))), is(false));
        }

        @Test
        @DisplayName("passes a list when every expected element meets some actual element, an empty list only an empty one")
        void matchesListsByAnyElement() {
            CompoundTag held = compound("l", list(new StringTag("a"), new StringTag("b")));
            Optional<CompoundTag> stack = Optional.of(components("minecraft:custom_data", held));
            assertThat(customData("{\"l\":[\"b\"]}").matches(stack), is(true));
            assertThat("one actual element serves twice", customData("{\"l\":[\"b\",\"b\"]}").matches(stack), is(true));
            assertThat(customData("{\"l\":[\"b\",\"a\"]}").matches(stack), is(true));
            assertThat(customData("{\"l\":[\"c\"]}").matches(stack), is(false));
            assertThat(customData("{\"l\":[]}").matches(stack), is(false));
            assertThat(customData("{\"l\":[\"a\",\"b\",\"b\"]}").matches(stack), is(false));
            assertThat(customData("{\"l\":[]}").matches(Optional.of(components("minecraft:custom_data", compound("l", new ListTag<>())))), is(true));
        }

        @Test
        @DisplayName("matches a borrowed tree the way it matches a decoded one")
        void matchesABorrowedTree() {
            // A borrowed tag's class differs from a decoded one's, so a class gate would fail every pair;
            // the id gate meets them.
            CompoundTag customData = compound("id", new StringTag("HYPERION"));
            customData.put("rarity_upgrades", new ByteTag((byte) 1));
            CompoundTag borrowed = NbtFactory.borrowFromByteArray(NbtFactory.toByteArray(components("minecraft:custom_data", customData)));
            assertThat("the borrowed compound is a subclass", borrowed.get("minecraft:custom_data").getClass() == CompoundTag.class, is(false));
            assertThat(customData("{\"id\":\"HYPERION\",\"rarity_upgrades\":1}").matches(Optional.of(borrowed)), is(true));
            assertThat(customData("\"{rarity_upgrades:1}\"").matches(Optional.of(borrowed)), is(false));
        }

        @Test
        @DisplayName("tests a stack with no custom data, and no stack at all, as an empty compound")
        void readsAbsentCustomDataAsEmpty() {
            // Vanilla reads getOrDefault(CUSTOM_DATA, CustomData.EMPTY), so {} passes every stack and a
            // non-empty test passes none without the component.
            assertThat(customData("{}").matches(Optional.empty()), is(true));
            assertThat(customData("{}").matches(Optional.of(new CompoundTag())), is(true));
            assertThat(customData("\"{}\"").matches(Optional.of(components("minecraft:damage", new IntTag(3)))), is(true));
            assertThat(customData("{\"id\":\"X\"}").matches(Optional.empty()), is(false));
            assertThat(withComponents(null).conditionValue(new ItemModelNode.Condition(
                "minecraft:component", "", false, Optional.of(customData("{}")), ON_TRUE, ON_FALSE)), is(true));
        }

        @Test
        @DisplayName("reads custom data held as a string tag as SNBT, and any other tag as nothing to match")
        void readsAStringStackValueAsSnbt() {
            assertThat(customData("{\"id\":\"X\"}").matches(Optional.of(components("minecraft:custom_data", new StringTag("{id:'X'}")))), is(true));
            assertThat(customData("{}").matches(Optional.of(components("minecraft:custom_data", new IntTag(1)))), is(false));
            assertThat(customData("{}").matches(Optional.of(components("minecraft:custom_data", new StringTag("not snbt {")))), is(false));
        }

        @Test
        @DisplayName("refuses a value that is not an SNBT compound or an object")
        void refusesAnUndecodableValue() {
            assertThrows(JsonParseException.class, () -> customData("\"{unclosed\""));
            assertThrows(JsonParseException.class, () -> customData("\"5\""));
            assertThrows(JsonParseException.class, () -> customData("5"));
            assertThrows(JsonParseException.class, () -> customData("{\"x\":null}"));
            assertThrows(JsonParseException.class, () -> predicate("minecraft:custom_data", null));
        }

    }

    @Nested
    @DisplayName("other component predicates")
    class OtherComponentPredicates {

        @Test
        @DisplayName("reads a component id as a presence test, refusing a value that is not an object")
        void readsAComponentIdAsPresence() {
            ComponentPredicate present = predicate("max_damage", "{}");
            assertThat(present, is(new ComponentPredicate.Present("minecraft:max_damage")));
            assertThat(present.matches(Optional.of(components("minecraft:max_damage", new IntTag(10)))), is(true));
            assertThat(present.matches(Optional.of(components("!minecraft:max_damage", new CompoundTag()))), is(false));
            assertThat(present.matches(Optional.of(new CompoundTag())), is(false));
            assertThat(present.matches(Optional.empty()), is(false));
            assertThrows(JsonParseException.class, () -> predicate("minecraft:max_damage", "5"));
        }

        @Test
        @DisplayName("refuses a vanilla-namespace id that is neither a predicate type nor a registered component, and keeps a mod's")
        void refusesAnUnregisteredId() {
            // Vanilla tries the id as a predicate type and then as a data component, and refuses an id
            // in neither registry; a mod's namespace is one this renderer cannot check.
            assertThrows(JsonParseException.class, () -> predicate("minecraft:mystery_component", "{}"));
            assertThrows(JsonParseException.class, () -> predicate("mystery_component", "{}"));
            assertThrows(JsonParseException.class, () -> predicate("minecraft:minecraft:max_damage", "{}"));
            assertThat(predicate("cat/collar", "{}"), is(new ComponentPredicate.Present("minecraft:cat/collar")));
            assertThat(predicate("somepack:marker", "{}"), is(new ComponentPredicate.Present("somepack:marker")));
        }

        @Test
        @DisplayName("reads a component id the registry codec's way: a registered one or a mod's passes as written, any other refuses")
        void readsAComponentIdNamespaceExact() {
            assertThat(componentId("dyed_color"), is("dyed_color"));
            assertThat(componentId("minecraft:shulker/color"), is("minecraft:shulker/color"));
            assertThat(componentId(":lore"), is(":lore"));
            assertThat(componentId("somepack:marker"), is("somepack:marker"));
            assertThrows(JsonParseException.class, () -> componentId("minecraft:dyed_colour"));
            assertThrows(JsonParseException.class, () -> componentId(""));
        }

        @Test
        @DisplayName("reads the other registered predicate types as unevaluable, passing nothing")
        void readsOtherTypesAsUnevaluable() {
            ComponentPredicate damage = predicate("minecraft:damage", "{\"durability\":{\"min\":1}}");
            assertThat(damage, is(new ComponentPredicate.Unevaluated("minecraft:damage")));
            assertThat(damage.matches(Optional.of(components("minecraft:damage", new IntTag(1)))), is(false));
            assertThat(predicate("villager/variant", "\"minecraft:plains\"").id(), is("minecraft:villager/variant"));
            assertThat(predicate("custom_data", "{}").id(), is("minecraft:custom_data"));
        }

    }

    @Nested
    @DisplayName("component select keys")
    class ComponentSelectKeys {

        @Test
        @DisplayName("names only the four components it decodes, bare or qualified")
        void namesTheModelledComponents() {
            assertThat(DecodedComponent.of("dyed_color"), is(Optional.of(DecodedComponent.DYED_COLOR)));
            assertThat(DecodedComponent.of("minecraft:custom_name"), is(Optional.of(DecodedComponent.CUSTOM_NAME)));
            assertThat(DecodedComponent.of(":lore"), is(Optional.of(DecodedComponent.LORE)));
            assertThat(DecodedComponent.of("minecraft:item_model"), is(Optional.of(DecodedComponent.ITEM_MODEL)));
            assertThat(DecodedComponent.of("minecraft:rarity"), is(Optional.empty()));
            assertThat(DecodedComponent.of("somepack:dyed_color"), is(Optional.empty()));
        }

        @Test
        @DisplayName("keys an item model as a qualified identifier, on both sides, and refuses one that is not a string")
        void keysAnItemModel() {
            assertThat(cases("minecraft:item_model", "\"stone_sword\""), contains("minecraft:stone_sword"));
            assertThat(cases("minecraft:item_model", "[\"stone_sword\",\"fsr:locked\"]"), contains("minecraft:stone_sword", "fsr:locked"));
            assertThrows(JsonParseException.class, () -> cases("minecraft:item_model", "5"));
            assertThrows(JsonParseException.class, () -> cases("minecraft:item_model", "[[\"stone_sword\"]]"));
            assertThat(DecodedComponent.ITEM_MODEL.key(Optional.of(components("minecraft:item_model", new StringTag("stone_sword")))),
                is(Possible.of("minecraft:stone_sword")));
            assertThat(DecodedComponent.ITEM_MODEL.key(Optional.of(components("minecraft:item_model", new IntTag(1)))), is(Possible.absent()));
        }

        @Test
        @DisplayName("reads a stack's item model from its patch, else its item's id, and none where the patch removes it")
        void readsTheItemModelForASelect() {
            ItemModelNode.Select select = new ItemModelNode.Select("minecraft:component", "", "item_model", Optional.of(DecodedComponent.ITEM_MODEL),
                Concurrent.newUnmodifiableList(new ItemModelNode.Select.Case(Concurrent.newUnmodifiableList("minecraft:diamond"), ON_TRUE)),
                ON_FALSE);
            ItemModelContext diamond = ItemModelContext.gui().withItemId("minecraft:diamond");
            assertThat(diamond.selectValue(select), is(Possible.of("minecraft:diamond")));
            assertThat(diamond.withComponents(components("minecraft:item_model", new StringTag("fsr:locked"))).selectValue(select),
                is(Possible.of("fsr:locked")));
            assertThat(diamond.withComponents(components("!minecraft:item_model", new CompoundTag())).selectValue(select), is(Possible.empty()));
            assertThat(diamond.withComponents(components("minecraft:custom_data", new CompoundTag())).selectValue(select),
                is(Possible.of("minecraft:diamond")));
            assertThat(ItemModelContext.gui().selectValue(select), is(Possible.empty()));
        }

        @Test
        @DisplayName("tells a component the stack does not hold from one this renderer cannot read, and falls back on both")
        void tellsAnUnheldComponentFromAnUnreadableOne() {
            // Not held is a value the context evaluated and found missing; a value that does not
            // decode, and a component no decoder here reads, are values it could not evaluate.
            ItemModelNode.Select dyed = new ItemModelNode.Select("minecraft:component", "", "dyed_color",
                Optional.of(DecodedComponent.DYED_COLOR),
                Concurrent.newUnmodifiableList(new ItemModelNode.Select.Case(Concurrent.newUnmodifiableList("255"), ON_TRUE)),
                ON_FALSE);
            ItemModelNode.Select rarity = new ItemModelNode.Select("minecraft:component", "", "rarity", Optional.empty(),
                Concurrent.newUnmodifiableList(new ItemModelNode.Select.Case(Concurrent.newUnmodifiableList("epic"), ON_TRUE)),
                ON_FALSE);
            ItemModelContext dyedRed = ItemModelContext.gui().withComponents(components("minecraft:dyed_color", new IntTag(255)));
            ItemModelContext unreadable = ItemModelContext.gui().withComponents(components("minecraft:dyed_color", new StringTag("red")));
            ItemModelContext undyed = ItemModelContext.gui().withComponents(components("minecraft:custom_data", new CompoundTag()));
            ItemModelContext epic = ItemModelContext.gui().withComponents(components("minecraft:rarity", new StringTag("epic")));

            assertThat(dyedRed.selectValue(dyed), is(Possible.of("255")));
            assertThat(undyed.selectValue(dyed), is(Possible.empty()));
            assertThat(unreadable.selectValue(dyed), is(Possible.absent()));
            assertThat(epic.selectValue(rarity), is(Possible.absent()));

            assertThat(dyedRed.resolve(dyed), is(dyedRed.resolve(ON_TRUE)));
            assertThat(undyed.resolve(dyed), is(undyed.resolve(ON_FALSE)));
            assertThat(unreadable.resolve(dyed), is(unreadable.resolve(ON_FALSE)));
            assertThat(epic.resolve(rarity), is(epic.resolve(ON_FALSE)));
        }

        @Test
        @DisplayName("keys a dyed colour by its integer, a three-float list folded as vanilla folds it")
        void keysADyedColour() {
            assertThat(cases("minecraft:dyed_color", "16711680"), contains("16711680"));
            assertThat(cases("minecraft:dyed_color", "[[1.0,0.0,0.0]]"), contains("-65536"));
            assertThat(cases("minecraft:dyed_color", "[16711680,255]"), contains("16711680", "255"));
            assertThrows(JsonParseException.class, () -> cases("minecraft:dyed_color", "{\"rgb\":16711680}"));
            assertThrows(JsonParseException.class, () -> cases("minecraft:dyed_color", "[]"));
        }

        @Test
        @DisplayName("reads a three-float when as three colours, the list form being tried first")
        void readsAFloatTripleAsThreeColours() {
            // Read as three colours, [1.0,0.0,0.0] repeats 0, so the definition holding it fails on the
            // duplicate; read as one colour, it would hold one key and decode.
            JsonParseException repeated = assertThrows(JsonParseException.class, () -> cases("minecraft:dyed_color", "[1.0,0.0,0.0]"));
            assertThat(repeated.getMessage(), is("Duplicate case value '0'"));
        }

        @Test
        @DisplayName("keys a stack's dyed colour from any numeric tag or three floats")
        void keysAStackDyedColour() {
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.of(components("minecraft:dyed_color", new IntTag(16711680)))), is(Possible.of("16711680")));
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.of(components("minecraft:dyed_color", new ShortTag((short) 255)))), is(Possible.of("255")));
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.of(components("minecraft:dyed_color",
                list(new FloatTag(1f), new FloatTag(0f), new FloatTag(0f))))), is(Possible.of("-65536")));
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.of(components("minecraft:dyed_color", new StringTag("red")))), is(Possible.absent()));
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.of(new CompoundTag())), is(Possible.empty()));
            assertThat(DecodedComponent.DYED_COLOR.key(Optional.empty()), is(Possible.empty()));
        }

        @Test
        @DisplayName("tells italic:false from an absent italic")
        void keepsStyleFlagsThreeState() {
            assertThat(nameKey("{\"text\":\"a\",\"italic\":false}"), is(not(nameKey("{\"text\":\"a\"}"))));
            assertThat(nameKey("{\"text\":\"a\",\"italic\":false}"), is(not(nameKey("{\"text\":\"a\",\"italic\":true}"))));
        }

        @Test
        @DisplayName("tells a root literal carrying a sibling from the flattened literal")
        void keepsTheStructure() {
            assertThat(nameKey("{\"text\":\"\",\"extra\":[\"a\"]}"), is(not(nameKey("\"a\""))));
            assertThat(nameKey("[\"\",\"a\"]"), is(nameKey("{\"text\":\"\",\"extra\":[\"a\"]}")));
            assertThat(nameKey("\"a\""), is(nameKey("{\"text\":\"a\"}")));
        }

        @Test
        @DisplayName("compares a colour by spelling: a name never meets its hex, two hex spellings of one value do")
        void comparesColourBySpelling() {
            assertThat(nameKey("{\"text\":\"a\",\"color\":\"green\"}"), is(not(nameKey("{\"text\":\"a\",\"color\":\"#55FF55\"}"))));
            assertThat(nameKey("{\"text\":\"a\",\"color\":\"#55ff55\"}"), is(nameKey("{\"text\":\"a\",\"color\":\"#55FF55\"}")));
            assertThat(nameKey("{\"text\":\"a\",\"color\":\"#5f5\"}"), is(nameKey("{\"text\":\"a\",\"color\":\"#0005F5\"}")));
            assertThrows(JsonParseException.class, () -> nameKey("{\"text\":\"a\",\"color\":\"chartreuse\"}"));
        }

        @Test
        @DisplayName("reads a stack's string tag as a plain literal and its byte flags as booleans")
        void readsTheStackSideAsNbt() {
            assertThat(stackNameKey(new StringTag("x")), is(Possible.of(nameKey("{\"text\":\"x\"}"))));
            CompoundTag styled = compound("text", new StringTag("a"));
            styled.put("italic", new ByteTag((byte) 0));
            assertThat(stackNameKey(styled), is(Possible.of(nameKey("{\"text\":\"a\",\"italic\":false}"))));
            // A JSON flag is a boolean and nothing else, so a numeric one refuses on the case side.
            assertThrows(JsonParseException.class, () -> nameKey("{\"text\":\"a\",\"italic\":0}"));
        }

        @Test
        @DisplayName("unwraps a stack's mixed sibling list from vanilla's binary form")
        void unwrapsTheStackWrapper() {
            // A 26.1 stack writes an extra list holding a string and an object as compounds, the string
            // wrapped under the empty key.
            CompoundTag red = compound("text", new StringTag("b"));
            red.put("color", new StringTag("red"));
            CompoundTag name = compound("text", new StringTag(""));
            name.put("extra", list(compound("", new StringTag("a")), red));
            assertThat(stackNameKey(name), is(Possible.of(nameKey("{\"text\":\"\",\"extra\":[\"a\",{\"text\":\"b\",\"color\":\"red\"}]}"))));
        }

        @Test
        @DisplayName("compares translatable contents and a hover event by their members")
        void comparesOtherContentsByMembers() {
            assertThat(nameKey("{\"translate\":\"k\",\"with\":[\"a\"]}"), is(nameKey("{\"type\":\"translatable\",\"with\":[\"a\"],\"translate\":\"k\"}")));
            assertThat(nameKey("{\"translate\":\"k\",\"with\":[\"a\"]}"), is(not(nameKey("{\"translate\":\"k\",\"with\":[\"b\"]}"))));
            CompoundTag translated = compound("translate", new StringTag("k"));
            translated.put("with", list(new StringTag("a")));
            assertThat(stackNameKey(translated), is(Possible.of(nameKey("{\"translate\":\"k\",\"with\":[\"a\"]}"))));

            String hovered = "{\"text\":\"a\",\"hover_event\":{\"action\":\"show_text\",\"value\":\"h\"}}";
            CompoundTag hover = compound("action", new StringTag("show_text"));
            hover.put("value", new StringTag("h"));
            CompoundTag stack = compound("text", new StringTag("a"));
            stack.put("hover_event", hover);
            assertThat(stackNameKey(stack), is(Possible.of(nameKey(hovered))));
            assertThat(nameKey(hovered), is(not(nameKey("{\"text\":\"a\"}"))));
        }

        @Test
        @DisplayName("reads a list of names as alternatives, the list form being tried first")
        void readsANameListAsAlternatives() {
            assertThat(cases("minecraft:custom_name", "[{\"text\":\"a\"},{\"text\":\"b\"}]"),
                contains(nameKey("\"a\""), nameKey("\"b\"")));
        }

        @Test
        @DisplayName("reads [{a},{b}] as one lore of two lines, and [[{a}],[{b}]] as two lores")
        void readsALoreWhen() {
            // An object is not a list, so the array fails as a list of lores and decodes as one lore.
            assertThat(cases("minecraft:lore", "[{\"text\":\"a\"},{\"text\":\"b\"}]").size(), is(1));
            assertThat(cases("minecraft:lore", "[[{\"text\":\"a\"}],[{\"text\":\"b\"}]]").size(), is(2));
            assertThat(DecodedComponent.LORE.key(Optional.of(components("minecraft:lore", list(new StringTag("a"), new StringTag("b"))))),
                is(Possible.of(cases("minecraft:lore", "[{\"text\":\"a\"},{\"text\":\"b\"}]").getFirst())));
        }

        /** A styled name setting every style member, written as a case value. */
        private static final String STYLED_NAME = "{\"text\":\"a\\tb\",\"color\":\"#5f5\",\"bold\":true,\"italic\":false,"
            + "\"shadow_color\":[1.0,0.50,0.0,1],\"insertion\":\"ins\",\"font\":\"minecraft:uniform\","
            + "\"click_event\":{\"action\":\"open_url\",\"url\":\"https://x.y/?a=1&b=<2>\"},\"hover_event\":{\"value\":\"h\",\"action\":\"show_text\"},"
            + "\"extra\":[\"s\",{\"text\":\"t\",\"color\":\"light_purple\",\"underlined\":true}]}";

        /** The key {@link #STYLED_NAME} reduces to, and a stack's copy of the same name with it. */
        private static final String STYLED_KEY = "[\"a\\tb\",{\"color\":\"#0005F5\",\"shadow_color\":[1,0.5,0,1],\"bold\":true,\"italic\":false,"
            + "\"click_event\":{\"action\":\"open_url\",\"url\":\"https://x.y/?a=1&b=<2>\"},\"hover_event\":{\"action\":\"show_text\",\"value\":\"h\"},"
            + "\"insertion\":\"ins\",\"font\":\"minecraft:uniform\"},[[\"s\",{},[]],[\"t\",{\"color\":\"light_purple\",\"underlined\":true},[]]]]";

        @Test
        @DisplayName("writes a case value's key in its canonical spelling, character for character")
        void writesTheCaseKeySpelling() {
            // The pipeline dump records every case key, so the spelling is pinned whole: a string
            // escaped as Gson writes one, compact and not HTML-safe, the style in vanilla's field
            // order, members sorted with their nulls dropped, and a number by its value.
            assertThat(nameKey("\"q\\\"b\\\\c\\u001fd\\u2028e<&>='\""), is("[\"q\\\"b\\\\c\\u001fd\\u2028e<&>='\",{},[]]"));
            assertThat(nameKey(STYLED_NAME), is(STYLED_KEY));
            assertThat(nameKey("{\"translate\":\"chat.k\",\"fallback\":\"F\",\"with\":[1,2.5,100,1.0E2,true,null,\"w\",{\"z\":null,\"b\":[],\"a\":-0.0}]}"),
                is("[{\"kind\":\"translatable\",\"members\":{\"fallback\":\"F\",\"translate\":\"chat.k\",\"with\":[1,2.5,1E+2,1E+2,1,null,\"w\",{\"a\":0,\"b\":[]}]}},{},[]]"));
            assertThat(cases("minecraft:lore", "[[{\"text\":\"a\",\"color\":\"gray\"},\"b\"]]"), contains("[[\"a\",{\"color\":\"gray\"},[]],[\"b\",{},[]]]"));
            assertThat(cases("minecraft:dyed_color", "[16711680,[0.5,0.25,1.0],-1,0]"), contains("16711680", "-8437761", "-1", "0"));
        }

        @Test
        @DisplayName("writes a stack value's key in the same spelling, from every numeric and array tag")
        void writesTheStackKeySpelling() {
            assertThat(stackNameKey(new StringTag("q\"b\\c\u001fd\u2028e<&>='")), is(Possible.of("[\"q\\\"b\\\\c\\u001fd\\u2028e<&>='\",{},[]]")));

            CompoundTag styled = compound("text", new StringTag("a\tb"));
            styled.put("color", new StringTag("#5f5"));
            styled.put("bold", new ByteTag((byte) 1));
            styled.put("italic", new ByteTag((byte) 0));
            styled.put("shadow_color", list(new FloatTag(1f), new FloatTag(0.5f), new FloatTag(0f), new FloatTag(1f)));
            styled.put("insertion", new StringTag("ins"));
            styled.put("font", new StringTag("minecraft:uniform"));
            CompoundTag click = compound("action", new StringTag("open_url"));
            click.put("url", new StringTag("https://x.y/?a=1&b=<2>"));
            styled.put("click_event", click);
            CompoundTag hover = compound("value", new StringTag("h"));
            hover.put("action", new StringTag("show_text"));
            styled.put("hover_event", hover);
            CompoundTag sibling = compound("text", new StringTag("t"));
            sibling.put("color", new StringTag("light_purple"));
            sibling.put("underlined", new ByteTag((byte) 1));
            styled.put("extra", list(compound("", new StringTag("s")), sibling));
            assertThat(stackNameKey(styled), is(Possible.of(STYLED_KEY)));

            CompoundTag translated = compound("translate", new StringTag("k"));
            translated.put("with", list(compound("", new IntTag(1)), compound("", new FloatTag(2.5f)), compound("", new StringTag("w"))));
            translated.put("bytes", new ByteArrayTag((byte) 1, (byte) 2));
            translated.put("ints", new IntArrayTag(3, 4));
            translated.put("longs", new LongArrayTag(5L));
            translated.put("s", new ShortTag((short) 7));
            translated.put("l", new LongTag(12345678901234L));
            translated.put("b", new ByteTag((byte) 1));
            translated.put("d", new DoubleTag(1.0E10));
            translated.put("nan", new FloatTag(Float.NaN));
            assertThat(stackNameKey(translated), is(Possible.of("[{\"kind\":\"translatable\",\"members\":{\"b\":1,\"bytes\":[1,2],\"d\":1E+10,"
                + "\"ints\":[3,4],\"l\":12345678901234,\"longs\":[5],\"nan\":\"NaN\",\"s\":7,\"translate\":\"k\",\"with\":[1,2.5,\"w\"]}},{},[]]")));
        }

        /** The key a {@code custom_name} case value decodes to. */
        private static String nameKey(String when) {
            return cases("minecraft:custom_name", "[" + when + "]").getFirst();
        }

        /** The key a stack's {@code custom_name} reduces to. */
        private static Possible<String> stackNameKey(Tag<?> name) {
            return DecodedComponent.CUSTOM_NAME.key(Optional.of(components("minecraft:custom_name", name)));
        }

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
                false, false, Optional.empty(), 0f, 0f, Optional.of(4f), Optional.of(customModelData(7f, 9f)));
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
                is(Possible.of(ItemModelContext.DISPLAY_CONTEXT_GUI)));
            assertThat(populated(null).selectValue("display_context"), is(Possible.of("fixed")));
        }

        @Test
        @DisplayName("answers the dimension as a fixed key no caller can move")
        void answersTheDimensionAsAFixedKey() {
            // Degrading here is not the neutral choice it looks like: a tree's dimension fallback is
            // written for where the item misbehaves, and a clock's dispatches on a random source.
            assertThat(populated(customModelData(1f)).selectValue("context_dimension"),
                is(Possible.of(ItemModelContext.DIMENSION_OVERWORLD)));
        }

        @Test
        @DisplayName("leaves an unsupplied trim material valueless")
        void leavesAnUnsuppliedTrimMaterialValueless() {
            // The context evaluates the trim and finds none, which is empty rather than absent - the
            // walk takes the fallback for either.
            assertThat(ItemModelContext.gui().selectValue("trim_material"), is(Possible.empty()));
            assertThat(populated(null).selectValue("trim_material"), is(Possible.of("minecraft:iron")));
        }

        @Test
        @DisplayName("leaves every other select property unevaluable, taking the no-case-match fallback")
        void leavesEveryOtherPropertyUnevaluable() {
            ItemModelContext carrying = populated(customModelData(1f));
            assertThat(carrying.selectValue("charge_type"), is(Possible.absent()));
            assertThat(carrying.selectValue("minecraft:block_state"), is(Possible.absent()));
            assertThat(carrying.selectValue("custom_model_data"), is(Possible.absent()));
            assertThat(carrying.selectValue("minecraft:mystery_future_key"), is(Possible.absent()));
        }

    }

    @Nested
    @DisplayName("tick view")
    class TickView {

        @Test
        @DisplayName("leaves the neutral context neutral at tick zero")
        void leavesNeutralContextNeutral() {
            // The render fast path short-circuits on the neutral context; perturbing it at tick 0 would
            // send every static item back through a tree walk and off its byte-parity baseline.
            assertThat(ItemModelContext.gui().atTick(0).isNeutral(), is(true));
            assertThat(ItemModelContext.gui().atTick(0), is(ItemModelContext.gui()));
        }

        @Test
        @DisplayName("advances the time input away from neutral at later ticks")
        void advancesTimeInput() {
            ItemModelContext advanced = ItemModelContext.gui().atTick(6_000);
            assertThat(advanced.isNeutral(), is(false));
            assertThat(advanced.time(), is(SunAngle.at(12_000)));
            assertThat(advanced.rangeValue("minecraft:time"), is(SunAngle.at(12_000)));
        }

        @Test
        @DisplayName("returns to the neutral time input after a whole day")
        void returnsAfterWholeDay() {
            assertThat(ItemModelContext.gui().atTick(SunAngle.TICKS_PER_DAY).isNeutral(), is(true));
        }

        @Test
        @DisplayName("renders in the overworld, the branch whose dispatch reads the day")
        void rendersInTheOverworld() {
            // Left unevaluable, a tree branching on the dimension would degrade to its fallback - and
            // vanilla writes that branch for where the item MISBEHAVES, not as a neutral default. The
            // clock's fallback dispatches on a random source; only the overworld case reads the daytime
            // computed here, so the branch has to be selected for the input to mean anything.
            assertThat(ItemModelContext.gui().selectValue("minecraft:context_dimension"),
                is(Possible.of(ItemModelContext.DIMENSION_OVERWORLD)));
            assertThat(ItemModelContext.gui().selectValue("context_dimension"),
                is(Possible.of("minecraft:overworld")));
            // A fixed answer, not a caller override - so it cannot perturb the neutral context.
            assertThat(ItemModelContext.gui().atTick(9_000).selectValue("context_dimension"),
                is(Possible.of(ItemModelContext.DIMENSION_OVERWORLD)));
            assertThat(ItemModelContext.gui().isNeutral(), is(true));
        }

        @Test
        @DisplayName("leaves the compass needle alone, which no passage of time turns")
        void leavesCompassAlone() {
            ItemModelContext held = new ItemModelContext(ItemModelContext.DISPLAY_CONTEXT_GUI,
                false, false, Optional.empty(), 0f, 0.25f, Optional.empty(), Optional.empty());
            assertThat(held.atTick(9_000).compassAngle(), is(0.25f));
            assertThat(held.atTick(9_000).rangeValue("minecraft:compass"), is(0.25f));
        }

        @Test
        @DisplayName("carries every other override across unchanged")
        void carriesOtherOverrides() {
            ItemModelContext custom = new ItemModelContext("fixed", true, true, Optional.of("minecraft:gold"),
                0.9f, 0.25f, Optional.of(4f), Optional.empty());
            ItemModelContext advanced = custom.atTick(1_234);
            assertThat(advanced.displayContext(), is("fixed"));
            assertThat(advanced.usingItem(), is(true));
            assertThat(advanced.broken(), is(true));
            assertThat(advanced.trimMaterial(), is(Optional.of("minecraft:gold")));
            assertThat(advanced.customModelData(), is(Optional.of(4f)));
            assertThat(advanced.time(), is(SunAngle.at(SunAngle.NOON_TICK + 1_234)));
        }

    }

    @Nested
    @DisplayName("component patch")
    class ComponentPatch {

        @Test
        @DisplayName("takes a copy of the caller's compound, so mutating it after changes no lookup")
        void copiesTheCallersCompound() {
            // The render memoises per context value, so a compound the caller goes on mutating would
            // move the key mid-render; the copy pins the answer to the call.
            CompoundTag customData = compound("id", new StringTag("ASPECT_OF_THE_END"));
            CompoundTag stack = components("minecraft:custom_data", customData);
            ItemModelContext context = ItemModelContext.gui().withComponents(stack);
            ItemModelNode.Condition condition = new ItemModelNode.Condition("minecraft:component", "", false,
                Optional.of(customData("{\"id\":\"ASPECT_OF_THE_END\"}")), ON_TRUE, ON_FALSE);
            ItemModelContext before = ItemModelContext.gui().withComponents(stack);

            customData.put("id", new StringTag("HYPERION"));
            stack.put("minecraft:dyed_color", new IntTag(0xFF0000));

            assertThat(context.conditionValue(condition), is(true));
            assertThat(context.hasComponent("minecraft:dyed_color"), is(false));
            assertThat(context, is(before));
            assertThat(context.components().orElseThrow(), is(not(stack)));
        }

        @Test
        @DisplayName("copies a list element by element and keeps an empty list's element type")
        void copiesListsDeeply() {
            ListTag<Tag<?>> lines = list(new StringTag("a"));
            ListTag<IntTag> empty = new ListTag<>();
            CompoundTag stack = components("minecraft:lore", lines);
            stack.put("minecraft:empty_list", empty);
            ItemModelContext context = ItemModelContext.gui().withComponents(stack);

            lines.add(new StringTag("b"));

            ListTag<?> copied = context.components().orElseThrow().getListTag("minecraft:lore");
            assertThat(copied.size(), is(1));
            assertThat(context.components().orElseThrow().getListTag("minecraft:empty_list").getListType(), is(empty.getListType()));
        }

        @Test
        @DisplayName("drops the patch and the item id without, keeping every other input, and answers itself where it carries neither")
        void dropsThePatchWithout() {
            ItemModelContext populated = populated(customModelData(1f)).withItemId("minecraft:diamond");
            ItemModelContext bare = populated.withoutComponents();
            assertThat(bare.components(), is(Optional.empty()));
            assertThat(bare.itemId(), is(Optional.empty()));
            assertThat(bare, is(populated(null)));
            assertThat(ItemModelContext.gui().withoutComponents(), is(sameInstance(ItemModelContext.gui())));
            assertThat(ItemModelContext.gui().withComponents(new CompoundTag()).withoutComponents(), is(ItemModelContext.gui()));
            assertThat(ItemModelContext.gui().withItemId("minecraft:diamond").withoutComponents(), is(ItemModelContext.gui()));
        }

        @Test
        @DisplayName("takes the neutral context off the fast path while it carries a patch or an item id")
        void aPatchIsNotNeutral() {
            assertThat(ItemModelContext.gui().withComponents(new CompoundTag()).isNeutral(), is(false));
            assertThat(ItemModelContext.gui().withItemId("minecraft:diamond").isNeutral(), is(false));
        }

        @Test
        @DisplayName("qualifies a bare item id and carries it through every other view")
        void carriesTheItemId() {
            ItemModelContext diamond = ItemModelContext.gui().withItemId("diamond");
            assertThat(diamond.itemId(), is(Optional.of("minecraft:diamond")));
            assertThat(diamond.withComponents(new CompoundTag()).itemId(), is(Optional.of("minecraft:diamond")));
            assertThat(diamond.atTick(1_234).itemId(), is(Optional.of("minecraft:diamond")));
            assertThat(diamond.withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND).itemId(),
                is(Optional.of("minecraft:diamond")));
            assertThat(ItemModelContext.gui().withItemId("fsr:locked").itemId(), is(Optional.of("fsr:locked")));
        }

    }

    @Nested
    @DisplayName("display view")
    class DisplayView {

        @Test
        @DisplayName("carries every other input across and answers the new key")
        void carriesEveryOtherInputAcross() {
            ItemModelContext custom = new ItemModelContext("fixed", true, true, Optional.of("minecraft:gold"),
                0.9f, 0.25f, Optional.of(4f), Optional.empty());
            ItemModelContext held = custom.withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND);
            assertThat(held.displayContext(), is(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND));
            assertThat(held.selectValue("display_context"),
                is(Possible.of(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND)));
            assertThat(held.usingItem(), is(true));
            assertThat(held.broken(), is(true));
            assertThat(held.trimMaterial(), is(Optional.of("minecraft:gold")));
            assertThat(held.time(), is(0.9f));
            assertThat(held.compassAngle(), is(0.25f));
            assertThat(held.customModelData(), is(Optional.of(4f)));
            assertThat(held.components(), is(Optional.empty()));
        }

        @Test
        @DisplayName("leaves the neutral context neutral at gui")
        void guiAtGuiStaysNeutral() {
            // A flat render resolves its absent context through this view, so gui at gui has to keep
            // the fast path that hands back the pipeline-baked item.
            ItemModelContext gui = ItemModelContext.gui().withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_GUI);
            assertThat(gui, is(ItemModelContext.gui()));
            assertThat(gui.isNeutral(), is(true));
        }

        @Test
        @DisplayName("takes the neutral context off the fast path at a held display")
        void heldIsNotNeutral() {
            // The fast path hands back the item baked at gui, which is the wrong model for a held spear.
            assertThat(ItemModelContext.gui()
                .withDisplayContext(ItemModelContext.DISPLAY_CONTEXT_THIRDPERSON_RIGHTHAND)
                .isNeutral(), is(false));
        }

    }

}
