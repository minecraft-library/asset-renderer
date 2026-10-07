package lib.minecraft.renderer.content.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.asset.item.ItemModelNode.ComponentPredicate;
import lib.minecraft.renderer.asset.item.ItemModelNode.SelectComponent;
import lib.minecraft.renderer.asset.item.ItemModelNode.SpecialTransform;
import lib.minecraft.renderer.asset.item.ItemModelNode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the {@code model} object of an {@code items/*.json} definition into an immutable
 * {@link ItemModelNode} tree, dispatching on the {@code type} discriminator and recursing through the
 * deserialization context, as vanilla's codec decodes one at load. A leaf's {@code model} and a special
 * node's {@code base} are read as identifiers, a bare id qualified to {@code minecraft:}, so the tree
 * holds every model id in the spelling the model lookup keys.
 *
 * <p><b>Ids are read namespace-exact.</b> A node type or dispatch property written bare or under
 * {@code minecraft:} names vanilla's vocabulary and must be one vanilla registers - eight node types,
 * thirteen condition, ten select and ten range properties. An id in any other namespace is a mod's,
 * which this renderer cannot read, so it degrades where it sits: a foreign node type parses to
 * {@link ItemModelNode.Empty#INSTANCE} and a foreign property to one the walk cannot evaluate. A data
 * component id and a special model kind are read the same way: a vanilla-namespace one must be one of
 * the 110 components or the sixteen kinds vanilla registers, a mod's component is looked up on the
 * stack as written, and a mod's kind is kept unchecked.
 *
 * <p><b>Component tests are decoded here, once.</b> A {@code minecraft:component} condition's
 * {@code predicate} and {@code value} become its {@link ComponentPredicate}, {@code has_component}'s
 * {@code ignore_default} is read, and a select's case values become canonical keys - a component
 * {@link SelectComponent} models through its own decoder, an identifier-keyed property's value
 * qualified to {@code minecraft:}, and any other value as written. A {@code when} is read as vanilla
 * reads it, an array taken as a list of values first and as one value only when that fails.
 *
 * <p><b>A definition vanilla's codec refuses throws {@link JsonParseException}</b>, and the loader drops
 * it whole: an unregistered vanilla-namespace type or property; a vanilla-namespace component id - a
 * {@code has_component} target, a component test's {@code predicate} that is no predicate type, a
 * component select's {@code component} - that names no component vanilla 26.1 registers, and a select
 * on one of the three it registers with no codec; a member vanilla requires that is absent or of the
 * wrong shape - a condition's {@code on_true} and {@code on_false}, a {@code has_component} target, a
 * component test's {@code predicate} and {@code value}, a component select's {@code component}, a
 * case's {@code when} and {@code model}, a range's {@code entries}, a composite's {@code models}, and a
 * special node's {@code base}, its {@code model} and that model's {@code type}, a vanilla-namespace one
 * naming a kind vanilla registers, with every field the kind requires; a child, case, entry or
 * fallback that is not a model object; an
 * {@code ignore_default} that is not a boolean; a value that does not decode, a component test's or a
 * vanilla property's case value; an any-component {@code value} that is not an object; and a select
 * with no cases, a case with an empty {@code when}, or a value repeated across or within the cases,
 * compared as decoded. The case values of a foreign property or an undecoded component are kept as
 * written, since only their own codec could refuse them. A
 * {@code select} or {@code range_dispatch} that declares no {@code fallback} carries
 * {@link ItemModelNode.Absent#INSTANCE}, which vanilla draws as the missing item model, kept apart from
 * an explicit {@code minecraft:empty}.
 * <p>
 * Registered globally so both the top-level {@code GSON.fromJson(model, }{@link ItemModelNode}{@code
 * .class)} read and every recursive {@code context.deserialize} child resolve through this one adapter.
 * The tree depth is bounded by the loader's own JSON parse: the Gson the build declares, 2.13.2, reads
 * with a nesting limit of 255, so a pathologically nested file fails that parse before this runs, and
 * the loader holds it as a refused definition. No explicit depth cap is carried here.
 *
 * <p><b>Parity.</b> Registered by a service file and reached only through the contributor that
 * names it, so no constant pool carries an edge to it. It parses the item model tree, which a block
 * icon is baked from as much as an item is drawn from.
 */
@Parity(subject = {Subject.BLOCK, Subject.ENTITY, Subject.ITEM, Subject.MENU})
@Parity(claim = "pack-resolution")
public final class ItemModelNodeDeserializer implements JsonDeserializer<ItemModelNode> {

    /** The {@code condition} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> CONDITION_PROPERTIES = Concurrent.newUnmodifiableSet(
        "custom_model_data", "using_item", "broken", "damaged", "fishing_rod/cast", "has_component",
        "bundle/has_selected_item", "selected", "carried", "extended_view", "keybind_down", "view_entity",
        "component");

    /** The {@code select} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> SELECT_PROPERTIES = Concurrent.newUnmodifiableSet(
        "custom_model_data", "main_hand", "charge_type", "trim_material", "block_state", "display_context",
        "local_time", "context_entity_type", "context_dimension", "component");

    /** The {@code range_dispatch} properties vanilla 26.1 registers. */
    private static final @NotNull ConcurrentSet<String> RANGE_PROPERTIES = Concurrent.newUnmodifiableSet(
        "custom_model_data", "bundle/fullness", "damage", "cooldown", "time", "compass", "crossbow/pull",
        "use_cycle", "use_duration", "count");

    /** The select properties whose case values are registry identifiers, compared qualified. */
    private static final @NotNull ConcurrentSet<String> IDENTIFIER_KEYED = Concurrent.newUnmodifiableSet(
        "trim_material", "context_dimension", "context_entity_type");

    /** The data components vanilla 26.1 registers with no codec, which a component select refuses, having none to decode a case value with. */
    private static final @NotNull ConcurrentSet<String> TRANSIENT_COMPONENTS = Concurrent.newUnmodifiableSet(
        "creative_slot_lock", "additional_trade_cost", "map_post_processing");

    /** The {@code special} model kinds vanilla 26.1 registers, by path, each with the fields its codec requires. */
    private static final @NotNull ConcurrentMap<String, ConcurrentList<String>> SPECIAL_KINDS = Concurrent.newUnmodifiableMap(Map.ofEntries(
        Map.entry("bed", Concurrent.newUnmodifiableList("texture", "part")),
        Map.entry("bell", Concurrent.<String>newUnmodifiableList()),
        Map.entry("banner", Concurrent.newUnmodifiableList("color")),
        Map.entry("book", Concurrent.newUnmodifiableList("open_angle", "page1", "page2")),
        Map.entry("conduit", Concurrent.<String>newUnmodifiableList()),
        Map.entry("chest", Concurrent.newUnmodifiableList("texture")),
        Map.entry("copper_golem_statue", Concurrent.newUnmodifiableList("texture", "pose")),
        Map.entry("head", Concurrent.newUnmodifiableList("kind")),
        Map.entry("player_head", Concurrent.<String>newUnmodifiableList()),
        Map.entry("shulker_box", Concurrent.newUnmodifiableList("texture")),
        Map.entry("shield", Concurrent.<String>newUnmodifiableList()),
        Map.entry("trident", Concurrent.<String>newUnmodifiableList()),
        Map.entry("decorated_pot", Concurrent.<String>newUnmodifiableList()),
        Map.entry("standing_sign", Concurrent.newUnmodifiableList("wood_type")),
        Map.entry("hanging_sign", Concurrent.newUnmodifiableList("wood_type")),
        Map.entry("end_cube", Concurrent.newUnmodifiableList("effect"))));

    @Override
    public @NotNull ItemModelNode deserialize(@NotNull JsonElement json, @NotNull Type type, @NotNull JsonDeserializationContext context) {
        if (!json.isJsonObject()) throw new JsonParseException(String.format("An item model is an object, not '%s'", json));
        JsonObject node = json.getAsJsonObject();
        String nodeType = string(node, "type");
        Optional<String> vanillaType = ItemModelNode.vanillaPath(nodeType);
        if (vanillaType.isEmpty()) return ItemModelNode.Empty.INSTANCE;

        return switch (vanillaType.get()) {
            case "model" -> new ItemModelNode.Model(modelId(node, "model"), tints(node, context));
            case "condition" -> condition(node, context);
            case "select" -> select(node, context);
            case "range_dispatch" -> new ItemModelNode.RangeDispatch(
                property(node, RANGE_PROPERTIES, "range_dispatch"), floatValue(node, "scale", 1f), string(node, "target"),
                intValue(node, "index", 0), entries(node, context), fallback(node, context));
            case "composite" -> new ItemModelNode.Composite(models(node, context));
            case "special" -> special(node);
            case "bundle/selected_item" -> new ItemModelNode.Bundle();
            case "empty" -> ItemModelNode.Empty.INSTANCE;
            default -> throw new JsonParseException(String.format("Unknown item model type '%s'", nodeType));
        };
    }

    /** Deserialises a {@code condition} node, decoding the component operands of {@code has_component} and {@code component}. */
    private static @NotNull ItemModelNode condition(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        String property = property(node, CONDITION_PROPERTIES, "condition");
        String path = ItemModelNode.vanillaPath(property).orElse("");
        boolean hasComponent = path.equals("has_component");
        String component = hasComponent
            ? ItemModelNode.componentId(requiredString(node, "component", "A has_component condition"))
            : string(node, "component");
        Optional<ComponentPredicate> predicate = path.equals("component")
            ? Optional.of(ComponentPredicate.of(requiredString(node, "predicate", "A component condition"), node.get("value")))
            : Optional.empty();
        return new ItemModelNode.Condition(
            property, component, hasComponent && flag(node, "ignore_default"), predicate,
            required(node, "on_true", context), required(node, "on_false", context));
    }

    /** Deserialises a {@code select} node, naming the component a {@code component} select keys on. */
    private static @NotNull ItemModelNode select(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        String property = property(node, SELECT_PROPERTIES, "select");
        Optional<String> path = ItemModelNode.vanillaPath(property);
        String component = path.filter("component"::equals).isPresent() ? selectComponent(requiredString(node, "component", "A component select")) : "";
        return new ItemModelNode.Select(
            property, string(node, "block_state_property"), component,
            cases(node, path, component, context), fallback(node, context));
    }

    /** Reads a component select's {@code component} as written, refusing one vanilla 26.1 does not register, or registers with no codec. */
    private static @NotNull String selectComponent(@NotNull String component) {
        if (ItemModelNode.vanillaPath(ItemModelNode.componentId(component)).filter(TRANSIENT_COMPONENTS::contains).isPresent())
            throw new JsonParseException(String.format("Data component '%s' has no codec, so a select cannot key on it", component));
        return component;
    }

    /** Reads a dispatch node's {@code property} as written, refusing a vanilla-namespace id the node type does not register. */
    private static @NotNull String property(@NotNull JsonObject node, @NotNull ConcurrentSet<String> registered, @NotNull String nodeType) {
        String property = string(node, "property");
        Optional<String> path = ItemModelNode.vanillaPath(property);
        if (path.isPresent() && !registered.contains(path.get()))
            throw new JsonParseException(String.format("Unknown %s property '%s'", nodeType, property));
        return property;
    }

    /**
     * Deserialises a {@code special} node, collecting its inline kind fields off the inner
     * {@code model}, as vanilla's codec reads one: a {@code base}, and a {@code model} object whose
     * {@code type} names one of the kinds {@link #SPECIAL_KINDS} lists and carries every field that
     * kind requires. A mod's kind is one this renderer cannot check, so it is kept as written. A
     * required field that is not a primitive is not one this decode carries, so it refuses as an
     * absent one does.
     */
    private static @NotNull ItemModelNode special(@NotNull JsonObject node) {
        requiredString(node, "base", "A special node");
        JsonElement innerElement = node.get("model");
        if (innerElement == null || !innerElement.isJsonObject())
            throw new JsonParseException(String.format("A special node's model is an object, not '%s'", innerElement));
        JsonObject inner = innerElement.getAsJsonObject();
        String kind = requiredString(inner, "type", "A special model");
        Optional<String> path = ItemModelNode.vanillaPath(kind);
        if (path.isPresent() && !SPECIAL_KINDS.containsKey(path.get()))
            throw new JsonParseException(String.format("Unknown special model type '%s'", kind));
        ConcurrentMap<String, String> fields = inner.entrySet()
            .stream()
            .filter(entry -> !entry.getKey().equals("type"))
            .filter(entry -> entry.getValue().isJsonPrimitive())
            .collect(Concurrent.toUnmodifiableLinkedMap(Map.Entry::getKey, entry -> entry.getValue().getAsString()));

        ConcurrentList<String> required = path.map(SPECIAL_KINDS::get).orElseGet(Concurrent::newUnmodifiableList);
        for (String field : required)
            if (!fields.containsKey(field)) throw new JsonParseException(String.format("Special model '%s' has no '%s'", kind, field));

        return new ItemModelNode.Special(kind, modelId(node, "base"), fields, transform(node));
    }

    /** Deserialises a node's {@code transformation}, or {@link SpecialTransform#IDENTITY} when absent / not an object. */
    private static @NotNull SpecialTransform transform(@NotNull JsonObject node) {
        JsonElement value = node.get("transformation");
        if (value == null || !value.isJsonObject()) return SpecialTransform.IDENTITY;
        JsonObject transformation = value.getAsJsonObject();
        return new SpecialTransform(
            floatArray(transformation, "left_rotation", SpecialTransform.IDENTITY.leftRotation()),
            floatArray(transformation, "right_rotation", SpecialTransform.IDENTITY.rightRotation()),
            floatArray(transformation, "scale", SpecialTransform.IDENTITY.scale()),
            floatArray(transformation, "translation", SpecialTransform.IDENTITY.translation()));
    }

    /**
     * Deserialises the {@code cases[]} array of a {@code select} node, as vanilla's codec reads it: at
     * least one case, each an object with a {@code when} and a {@code model}, and no case value repeated
     * across or within the cases, compared by decoded value.
     */
    private static @NotNull ConcurrentList<ItemModelNode.Select.Case> cases(
        @NotNull JsonObject node, @NotNull Optional<String> property, @NotNull String component,
        @NotNull JsonDeserializationContext context
    ) {
        JsonArray cases = array(node, "cases");
        if (cases.isEmpty()) throw new JsonParseException(String.format("Select on '%s' declares no cases", string(node, "property")));
        ConcurrentSet<String> seen = Concurrent.newSet();
        ConcurrentList<ItemModelNode.Select.Case> parsed = Concurrent.newList();
        for (JsonElement element : cases) {
            if (!element.isJsonObject()) throw new JsonParseException(String.format("A select case is an object, not '%s'", element));
            JsonObject entry = element.getAsJsonObject();
            ConcurrentList<String> when = when(entry.get("when"), property, component);
            for (String key : when)
                if (!seen.add(key)) throw new JsonParseException(String.format("Duplicate case value '%s'", key));
            parsed.add(new ItemModelNode.Select.Case(when, required(entry, "model", context)));
        }
        return parsed.toUnmodifiable();
    }

    /** Deserialises the {@code entries[]} array of a {@code range_dispatch} node, each entry an object with a {@code model}. */
    private static @NotNull ConcurrentList<ItemModelNode.RangeDispatch.Entry> entries(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return requiredArray(node, "entries", "A range_dispatch").asList()
            .stream()
            .map(element -> object(element, "range_dispatch entry"))
            .map(element -> new ItemModelNode.RangeDispatch.Entry(floatValue(element, "threshold", 0f), required(element, "model", context)))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Deserialises the {@code models[]} array of a {@code composite} node, each child a model object. */
    private static @NotNull ConcurrentList<ItemModelNode> models(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return requiredArray(node, "models", "A composite").asList()
            .stream()
            .map(element -> context.<ItemModelNode>deserialize(object(element, "composite child"), ItemModelNode.class))
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Decodes a case's {@code when} into its canonical keys. A component {@link SelectComponent} models
     * goes through its decoder, and a vanilla property's values must be strings, qualified when the
     * property keys on identifiers; an array is a list of values, a single value a list of one. A foreign
     * property's or an undecoded component's values are kept as written, their primitives read as strings.
     */
    private static @NotNull ConcurrentList<String> when(@Nullable JsonElement when, @NotNull Optional<String> property, @NotNull String component) {
        if (when == null || when.isJsonNull()) throw new JsonParseException("A select case has no 'when'");
        if (when.isJsonArray() && when.getAsJsonArray().isEmpty()) throw new JsonParseException("A select case has an empty 'when' list");
        if (property.isEmpty()) return written(when);
        if (property.get().equals("component"))
            return SelectComponent.of(component).map(decoder -> decoder.cases(when)).orElseGet(() -> written(when));

        boolean identifier = IDENTIFIER_KEYED.contains(property.get());
        ConcurrentList<JsonElement> values = when.isJsonArray() ? Concurrent.adoptList(when.getAsJsonArray().asList()) : Concurrent.newUnmodifiableList(when);
        return values.stream()
            .map(value -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new JsonParseException(String.format("A %s case value is a string, not '%s'", property.get(), when));
                return identifier ? ItemModelNode.qualify(value.getAsString()) : value.getAsString();
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Reads case values as written: a primitive, or an array of them, as strings; any other entry drops. */
    private static @NotNull ConcurrentList<String> written(@NotNull JsonElement when) {
        if (when.isJsonArray())
            return when.getAsJsonArray().asList()
                .stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .collect(Concurrent.toUnmodifiableList());
        return when.isJsonPrimitive() ? Concurrent.newUnmodifiableList(when.getAsString()) : Concurrent.newUnmodifiableList();
    }

    /** Deserialises the {@code tints[]} array of a {@code model} node into ordered per-layer tint rules. */
    private static @NotNull ConcurrentList<LayerTint> tints(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        return array(node, "tints").asList()
            .stream()
            .filter(JsonElement::isJsonObject)
            .map(element -> context.<LayerTint>deserialize(element, LayerTint.class))
            .collect(Concurrent.toUnmodifiableList());
    }

    /** Reads {@code node[key]} as a child node vanilla requires, refusing the definition when it is absent or not an object. */
    private static @NotNull ItemModelNode required(@NotNull JsonObject node, @NotNull String key, @NotNull JsonDeserializationContext context) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonObject())
            throw new JsonParseException(String.format("Item model member '%s' is required to be a model object, not '%s'", key, value));
        return context.deserialize(value, ItemModelNode.class);
    }

    /**
     * Reads a {@code select} or {@code range_dispatch} node's optional {@code fallback}, or
     * {@link ItemModelNode.Absent#INSTANCE} when it declares none, refusing one that is not an object.
     */
    private static @NotNull ItemModelNode fallback(@NotNull JsonObject node, @NotNull JsonDeserializationContext context) {
        JsonElement value = node.get("fallback");
        if (value == null || value.isJsonNull()) return ItemModelNode.Absent.INSTANCE;
        if (!value.isJsonObject()) throw new JsonParseException(String.format("Item model fallback is a model object, not '%s'", value));
        return context.deserialize(value, ItemModelNode.class);
    }

    /** An element that must be an object, refusing the definition otherwise. */
    private static @NotNull JsonObject object(@NotNull JsonElement element, @NotNull String what) {
        if (!element.isJsonObject()) throw new JsonParseException(String.format("A %s is an object, not '%s'", what, element));
        return element.getAsJsonObject();
    }

    /** The array member under {@code key}, or an empty array when absent / not a JSON array. */
    private static @NotNull JsonArray array(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /** The array member under {@code key}, refusing the definition when it is absent or not an array. */
    private static @NotNull JsonArray requiredArray(@NotNull JsonObject node, @NotNull String key, @NotNull String owner) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonArray()) throw new JsonParseException(String.format("%s has no '%s' list", owner, key));
        return value.getAsJsonArray();
    }

    /** The string member under {@code key} when it is a JSON primitive, or {@code ""} - matching {@code getString(key, "")}. */
    private static @NotNull String string(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** The string member under {@code key}, refusing the definition when it is absent or not a JSON string. */
    private static @NotNull String requiredString(@NotNull JsonObject node, @NotNull String key, @NotNull String owner) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new JsonParseException(String.format("%s has no string '%s'", owner, key));
        return value.getAsString();
    }

    /** The optional boolean member under {@code key}, {@code false} when absent, refusing the definition when it is not a JSON boolean. */
    private static boolean flag(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        if (value == null || value.isJsonNull()) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new JsonParseException(String.format("Item model member '%s' is a boolean, not '%s'", key, value));
        return value.getAsBoolean();
    }

    /**
     * Reads the model id under {@code key}, a bare id qualified to {@code minecraft:} as vanilla parses
     * the member as an identifier, or {@code ""} when the member is absent.
     */
    private static @NotNull String modelId(@NotNull JsonObject node, @NotNull String key) {
        String id = string(node, key);
        return id.isEmpty() ? id : ResourceId.parse(id).id();
    }

    /**
     * Reads {@code node[key]} as a float, or {@code fallback} when it is absent, a non-primitive, or a
     * non-numeric primitive (a quoted {@code "min"} threshold degrades rather than aborting the load).
     */
    private static float floatValue(@NotNull JsonObject node, @NotNull String key, float fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsFloat();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Reads {@code node[key]} as an int, or {@code fallback} when it is absent, a non-primitive, or a non-numeric primitive. */
    private static int intValue(@NotNull JsonObject node, @NotNull String key, int fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Reads {@code node[key]} as a float array, or a copy of {@code fallback} when it is absent, not an
     * array, or carries a non-numeric / nested element - one bad element degrades the whole array.
     */
    private static float @NotNull [] floatArray(@NotNull JsonObject node, @NotNull String key, float @NotNull [] fallback) {
        JsonElement value = node.get(key);
        if (value == null || !value.isJsonArray()) return fallback.clone();
        JsonArray array = value.getAsJsonArray();
        float[] out = new float[array.size()];
        try {
            for (int i = 0; i < array.size(); i++) out[i] = array.get(i).getAsFloat();
        } catch (RuntimeException ex) {
            // A non-numeric or nested element (getAsFloat throws NumberFormatException /
            // UnsupportedOperationException / IllegalStateException): fall back to the default array.
            return fallback.clone();
        }
        return out;
    }

}
