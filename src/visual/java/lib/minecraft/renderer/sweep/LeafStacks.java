package lib.minecraft.renderer.sweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.DoubleTag;
import lib.minecraft.nbt.tag.FloatTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.LongTag;
import lib.minecraft.nbt.tag.ShortTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The model leaves of an item definition's raw JSON, and the one 26.1 item stack each leaf's path
 * solves to - the stacks a pack's component tests choose their branches for, read from the pack's own
 * values rather than from the decoded tree the renderer walks.
 * <p>
 * {@link #leaves} gathers the steps on the path to each leaf, and {@link Reach#of} solves them for the
 * one stack that takes them, or finds that none can - a test an earlier sibling answers first, or two
 * guards that want different values of one component. The guards a stack answers are a
 * {@code custom_data} test, a custom name, a dyed colour and the display context it is drawn at. A
 * leaf behind any other guard - a held key, a cast rod, a use duration, a lore - needs state an icon
 * has no value for, and is reported as such rather than walked.
 */
@UtilityClass
public final class LeafStacks {

    /**
     * Vanilla's display contexts, the slot first, which is the order a leaf a display context leaves
     * open is walked at.
     */
    private static final @NotNull List<JsonElement> DISPLAY_CONTEXTS = Stream.of(
            "gui", "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand",
            "head", "ground", "fixed", "on_shelf", "none")
        .<JsonElement>map(JsonPrimitive::new)
        .toList();

    /**
     * Collects every {@code minecraft:model} leaf of one definition, with the steps the path to it
     * takes. Node types and dispatch properties are read namespace-exact, so a mod's node draws nothing
     * and a mod's property is a guard no stack answers.
     *
     * @param itemId the item the definition draws
     * @param model the definition's {@code model} member, or {@code null} where it declares none
     * @return the leaves, in the definition's own order
     */
    public static @NotNull List<Leaf> leaves(@NotNull String itemId, @Nullable JsonElement model) {
        List<Leaf> out = new ArrayList<>();
        walk(itemId, model, List.of(), out);
        return out;
    }

    /**
     * Builds a 26.1 item stack of one item.
     *
     * @param itemId the item id
     * @param components the stack's component patch
     * @return the stack
     */
    public static @NotNull CompoundTag itemStack(@NotNull String itemId, @NotNull CompoundTag components) {
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(itemId));
        stack.put("count", new IntTag(1));
        stack.put("components", components);
        return stack;
    }

    /** Walks one node, collecting every leaf below it with the steps the path to it takes. */
    private static void walk(@NotNull String itemId, @Nullable JsonElement element, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject node = element.getAsJsonObject();

        switch (vocabulary(node, "type")) {
            case "model" -> out.add(new Leaf(itemId, ResourceId.parse(string(node, "model")).id(), path));
            case "condition" -> condition(itemId, node, path, out);
            case "select" -> select(itemId, node, path, out);
            case "range_dispatch" -> range(itemId, node, path, out);
            case "composite" -> {
                JsonArray models = array(node, "models");
                for (int index = 0; index < models.size(); index++)
                    walk(itemId, models.get(index), then(path, new Step.Child(index)), out);
            }
            default -> { }
        }
    }

    /**
     * Walks both branches of a condition. A {@code custom_data} test is wanted on one branch and refused
     * on the other; a presence test, of a component or a {@code has_component}, asks on its false branch
     * that the stack not hold the component; and every true branch other than a {@code custom_data}
     * test's needs state an icon has no value for.
     */
    private static void condition(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        String property = vocabulary(node, "property");
        if (property.equals("component") && vocabulary(node, "predicate").equals("custom_data")) {
            CompoundTag data = customData(node.get("value"));
            walk(itemId, node.get("on_true"), then(path, new Step.Wants(data)), out);
            walk(itemId, node.get("on_false"), then(path, new Step.Refuses(data)), out);
            return;
        }

        Optional<String> presence = switch (property) {
            case "component" -> Optional.of(ResourceId.parse(string(node, "predicate")).id());
            case "has_component" -> Optional.of(ResourceId.parse(string(node, "component")).id());
            default -> Optional.empty();
        };
        walk(itemId, node.get("on_true"), then(path, new Step.Other()), out);
        walk(itemId, node.get("on_false"), presence.map(id -> then(path, new Step.Lacks(id))).orElse(path), out);
    }

    /**
     * Walks every case and the fallback of a select. A custom-name, dyed-colour or display-context case
     * takes its values and the fallback refuses every case's; the overworld case of a dimension select
     * is the one an icon is drawn in, so it takes nothing and the fallback beside it needs other state;
     * and any other case needs state an icon has no value for, while its fallback is where the walk
     * goes without it.
     */
    private static void select(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        String property = vocabulary(node, "property");
        Optional<Selected> selected = switch (property) {
            case "display_context" -> Optional.of(Selected.DISPLAY);
            case "component" -> Selected.of(ResourceId.parse(string(node, "component")).id());
            default -> Optional.empty();
        };
        boolean dimension = property.equals("context_dimension");

        List<JsonElement> every = new ArrayList<>();
        for (JsonElement option : array(node, "cases")) {
            JsonObject branch = option.getAsJsonObject();
            List<JsonElement> values = values(branch.get("when"));
            every.addAll(values);
            if (selected.isPresent())
                walk(itemId, branch.get("model"), then(path, new Step.Takes(selected.get(), values)), out);
            else
                walk(itemId, branch.get("model"), dimension && overworld(values) ? path : then(path, new Step.Other()), out);
        }

        if (selected.isPresent())
            walk(itemId, node.get("fallback"), then(path, new Step.Skips(selected.get(), every)), out);
        else
            walk(itemId, node.get("fallback"), dimension && overworld(every) ? then(path, new Step.Other()) : path, out);
    }

    /**
     * Walks every entry and the fallback of a range dispatch. Every range input an icon renders at is
     * zero, so the entry it lands on is the highest threshold at or below zero, else the fallback, and
     * every other branch needs state an icon has no value for.
     */
    private static void range(@NotNull String itemId, @NotNull JsonObject node, @NotNull List<Step> path, @NotNull List<Leaf> out) {
        JsonArray entries = array(node, "entries");
        int neutral = -1;
        float best = 0f;
        for (int index = 0; index < entries.size(); index++) {
            float threshold = entries.get(index).getAsJsonObject().get("threshold").getAsFloat();
            if (threshold <= 0f && (neutral < 0 || threshold > best)) {
                neutral = index;
                best = threshold;
            }
        }

        for (int index = 0; index < entries.size(); index++)
            walk(itemId, entries.get(index).getAsJsonObject().get("model"), index == neutral ? path : then(path, new Step.Other()), out);
        walk(itemId, node.get("fallback"), neutral < 0 ? path : then(path, new Step.Other()), out);
    }

    /** One step on the path from a definition's root to a leaf. */
    public sealed interface Step {

        /**
         * A {@code custom_data} test passed.
         *
         * @param data the custom data the test names
         */
        record Wants(@NotNull CompoundTag data) implements Step {}

        /**
         * A {@code custom_data} test failed.
         *
         * @param data the custom data the test names
         */
        record Refuses(@NotNull CompoundTag data) implements Step {}

        /**
         * A case of a select a stack or a display context answers taken.
         *
         * @param selected what the select keys on
         * @param values the values the case matches
         */
        record Takes(@NotNull Selected selected, @NotNull List<JsonElement> values) implements Step {}

        /**
         * The fallback of a select a stack or a display context answers taken.
         *
         * @param selected what the select keys on
         * @param values every value the select's cases match
         */
        record Skips(@NotNull Selected selected, @NotNull List<JsonElement> values) implements Step {}

        /**
         * A presence test failed.
         *
         * @param component the qualified id of the component the stack must not hold
         */
        record Lacks(@NotNull String component) implements Step {}

        /** A branch only state an icon has no value for takes. */
        record Other() implements Step {}

        /**
         * A child of a {@code composite} entered.
         *
         * @param index the child's index among the composite's models
         */
        record Child(int index) implements Step {}

    }

    /** What a select a stack or a display context answers keys on. */
    public enum Selected {

        /** The {@code minecraft:custom_name} component. */
        NAME,

        /** The {@code minecraft:dyed_color} component. */
        DYE,

        /** The display context. */
        DISPLAY;

        /**
         * Finds the component select a stack answers.
         *
         * @param component the qualified component id the select keys on
         * @return the select, or empty for a component the stack built here does not carry
         */
        static @NotNull Optional<Selected> of(@NotNull String component) {
            return switch (component) {
                case "minecraft:custom_name" -> Optional.of(NAME);
                case "minecraft:dyed_color" -> Optional.of(DYE);
                default -> Optional.empty();
            };
        }

    }

    /** Why no stack walks to a leaf. */
    public enum Unwalked {

        /** An earlier sibling answers the leaf's test first, or two of its guards disagree. */
        CONTRADICTED,

        /** The leaf sits behind a guard no stack built here answers. */
        OTHER_STATE,

        /** No guard a stack sets stands in front of the leaf, which the walk reaches without one. */
        NEUTRAL

    }

    /**
     * One model leaf of a definition and the path to it.
     *
     * @param itemId the item the definition draws
     * @param model the qualified model id the leaf names
     * @param path the steps from the definition's root to the leaf
     */
    public record Leaf(@NotNull String itemId, @NotNull String model, @NotNull List<Step> path) {}

    /**
     * The values a select may answer on a path - those the cases it takes leave, less those a fallback
     * it takes refuses.
     *
     * @param allowed the values every case taken matches, empty when the path takes no case
     * @param excluded the values a fallback taken refuses
     */
    private record Choice(@NotNull Optional<List<JsonElement>> allowed, @NotNull List<JsonElement> excluded) {

        /** No case and no fallback taken. */
        static final @NotNull Choice OPEN = new Choice(Optional.empty(), List.of());

        /** Narrows this choice to the values a case taken matches. */
        @NotNull Choice taking(@NotNull List<JsonElement> values) {
            return new Choice(Optional.of(this.allowed.map(held -> held.stream().filter(values::contains).toList()).orElse(values)), this.excluded);
        }

        /** Adds the values a fallback taken refuses. */
        @NotNull Choice skipping(@NotNull List<JsonElement> values) {
            return new Choice(this.allowed, Stream.concat(this.excluded.stream(), values.stream()).toList());
        }

        /** Picks the first value the path leaves, from {@code otherwise} where it takes no case. */
        @NotNull Optional<JsonElement> pick(@NotNull List<JsonElement> otherwise) {
            return this.allowed.orElse(otherwise).stream().filter(value -> !this.excluded.contains(value)).findFirst();
        }

    }

    /**
     * A leaf and the stack its path solves to: the components the path's guards ask for and the display
     * context its walk proceeds at, or why no stack walks to it.
     *
     * @param leaf the leaf
     * @param unwalked why no stack walks to the leaf, empty where one does
     * @param components the component patch of the stack that reaches the leaf, empty where none does
     * @param displayContext the display context the walk proceeds at
     * @param composed whether the path passes through a {@code composite}
     * @param later whether the path passes through a {@code composite} child past its first
     */
    public record Reach(
        @NotNull Leaf leaf, @NotNull Optional<Unwalked> unwalked, @NotNull CompoundTag components,
        @NotNull String displayContext, boolean composed, boolean later
    ) {

        /**
         * Solves a leaf's path for the one stack that reaches it.
         * <p>
         * The custom data is every passed test's compound merged, so it is the least a stack can carry
         * and pass them all; a key two tests give different values contradicts. A failed test whose
         * compound that custom data matches is one every stack reaching the passed tests matches too,
         * an earlier sibling answering first, so the leaf is shadowed. A custom name, a dyed colour and
         * a display context are each the first value the path's cases leave and its fallbacks do not
         * refuse, the display context defaulting to the slot.
         *
         * @param leaf the leaf
         * @return the solved reach
         */
        public static @NotNull Reach of(@NotNull Leaf leaf) {
            List<CompoundTag> wanted = new ArrayList<>();
            List<CompoundTag> refused = new ArrayList<>();
            Map<Selected, Choice> choices = new EnumMap<>(Selected.class);
            Set<String> lacking = new HashSet<>();
            boolean guarded = false;
            boolean other = false;
            boolean composed = false;
            boolean later = false;

            for (Step step : leaf.path()) {
                switch (step) {
                    case Step.Wants wants -> {
                        wanted.add(wants.data());
                        guarded = true;
                    }
                    case Step.Refuses refuses -> refused.add(refuses.data());
                    case Step.Takes takes -> {
                        choices.put(takes.selected(), choices.getOrDefault(takes.selected(), Choice.OPEN).taking(takes.values()));
                        guarded = true;
                    }
                    case Step.Skips skips -> choices.put(skips.selected(), choices.getOrDefault(skips.selected(), Choice.OPEN).skipping(skips.values()));
                    case Step.Lacks lacks -> lacking.add(lacks.component());
                    case Step.Other ignored -> other = true;
                    case Step.Child child -> {
                        composed = true;
                        later |= child.index() > 0;
                    }
                }
            }

            if (other) return unwalked(leaf, Unwalked.OTHER_STATE);
            if (!guarded) return unwalked(leaf, Unwalked.NEUTRAL);

            Optional<CompoundTag> merged = Optional.of(new CompoundTag());
            for (CompoundTag compound : wanted)
                merged = merged.flatMap(held -> merge(held, compound));
            if (merged.isEmpty()) return unwalked(leaf, Unwalked.CONTRADICTED);
            CompoundTag data = merged.get();
            if (refused.stream().anyMatch(compound -> matches(compound, data))) return unwalked(leaf, Unwalked.CONTRADICTED);

            Choice name = choices.getOrDefault(Selected.NAME, Choice.OPEN);
            Choice dye = choices.getOrDefault(Selected.DYE, Choice.OPEN);
            Optional<JsonElement> named = name.pick(List.of());
            Optional<JsonElement> dyed = dye.pick(List.of());
            Optional<JsonElement> display = choices.getOrDefault(Selected.DISPLAY, Choice.OPEN).pick(DISPLAY_CONTEXTS);
            if (name.allowed().isPresent() && named.isEmpty() || dye.allowed().isPresent() && dyed.isEmpty() || display.isEmpty())
                return unwalked(leaf, Unwalked.CONTRADICTED);

            CompoundTag components = new CompoundTag();
            if (!wanted.isEmpty()) components.put("minecraft:custom_data", data);
            named.ifPresent(value -> components.put("minecraft:custom_name", tag(value, false)));
            dyed.ifPresent(value -> components.put("minecraft:dyed_color", dyedColor(value)));
            if (lacking.stream().anyMatch(components::containsKey)) return unwalked(leaf, Unwalked.CONTRADICTED);

            return new Reach(leaf, Optional.empty(), components, display.get().getAsString(), composed, later);
        }

        /** Builds the reach of a leaf no stack walks to. */
        private static @NotNull Reach unwalked(@NotNull Leaf leaf, @NotNull Unwalked reason) {
            return new Reach(leaf, Optional.of(reason), new CompoundTag(), "", false, false);
        }

        /**
         * Whether a stack walks to the leaf.
         *
         * @return whether the path solved
         */
        public boolean walkable() {
            return this.unwalked.isEmpty();
        }

        /**
         * The 26.1 item stack that reaches the leaf.
         *
         * @return the stack, of the leaf's item and carrying the solved components
         */
        public @NotNull CompoundTag stack() {
            return itemStack(this.leaf.itemId(), this.components);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return this.leaf.itemId() + " -> " + this.leaf.model() + " at " + this.displayContext + " with " + NbtFactory.toSnbt(this.components);
        }

    }

    /**
     * Decodes a {@code custom_data} test's value: an SNBT string as SNBT, an object as vanilla's
     * {@code JsonOps.convertTo} converts one.
     */
    private static @NotNull CompoundTag customData(@NotNull JsonElement value) {
        if (value.isJsonPrimitive()) return NbtFactory.fromSnbt(value.getAsString());
        return (CompoundTag) tag(value, true);
    }

    /**
     * Converts one JSON value to the 26.1 NBT tag it is written as: a string to a string tag, a boolean
     * to a byte, a whole number to an int - or, converted as {@code JsonOps.convertTo} converts a
     * {@code custom_data} object, to the narrowest of byte, short, int and long that holds it - any
     * other number to a float where that is exact and a double otherwise, an object to a compound, and
     * an array to a list, each element of a list whose element types differ wrapped as the one entry of
     * a compound keyed by the empty string.
     */
    private static @NotNull Tag<?> tag(@NotNull JsonElement json, boolean narrowest) {
        if (json.isJsonObject()) {
            CompoundTag compound = new CompoundTag();
            json.getAsJsonObject().entrySet().forEach(entry -> compound.put(entry.getKey(), tag(entry.getValue(), narrowest)));
            return compound;
        }
        if (json.isJsonArray()) {
            List<Tag<?>> elements = json.getAsJsonArray().asList().stream().<Tag<?>>map(element -> tag(element, narrowest)).toList();
            boolean mixed = elements.stream().map(Tag::getId).distinct().count() > 1;
            ListTag<Tag<?>> list = new ListTag<>(elements.size());
            elements.forEach(element -> list.add(mixed ? wrapped(element) : element));
            return list;
        }

        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isString()) return new StringTag(primitive.getAsString());
        if (primitive.isBoolean()) return new ByteTag((byte) (primitive.getAsBoolean() ? 1 : 0));
        BigDecimal value = primitive.getAsBigDecimal();
        try {
            long whole = value.longValueExact();
            if (narrowest && (byte) whole == whole) return new ByteTag((byte) whole);
            if (narrowest && (short) whole == whole) return new ShortTag((short) whole);
            if ((int) whole == whole) return new IntTag((int) whole);
            return new LongTag(whole);
        } catch (ArithmeticException fractional) {
            double real = value.doubleValue();
            return (float) real == real ? new FloatTag((float) real) : new DoubleTag(real);
        }
    }

    /**
     * Wraps one element of a list whose element types differ, as vanilla's binary form writes it: a
     * compound that is not itself a wrapper as it is, anything else as the one entry of a compound
     * keyed by the empty string.
     */
    private static @NotNull Tag<?> wrapped(@NotNull Tag<?> element) {
        if (element instanceof CompoundTag compound && !(compound.size() == 1 && compound.containsKey(""))) return compound;
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("", element);
        return wrapper;
    }

    /**
     * Converts a dyed-colour case value to the {@code minecraft:dyed_color} a stack carries: a number as
     * the int vanilla writes, three floats as the list they are.
     */
    private static @NotNull Tag<?> dyedColor(@NotNull JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return new IntTag(value.getAsInt());
        return tag(value, false);
    }

    /**
     * Answers whether a custom data test passes a stack's custom data, as vanilla's partial
     * {@code NbtUtils.compareNbt} answers it: a compound when every expected key passes, a list when
     * every expected element passes against some actual one - an empty one only against an empty one -
     * and any other tag on equality, the tag type included.
     */
    private static boolean matches(@NotNull Tag<?> expected, @Nullable Tag<?> actual) {
        if (actual == null || expected.getId() != actual.getId()) return false;
        if (expected instanceof CompoundTag wanted)
            return wanted.entrySet().stream().allMatch(entry -> matches(entry.getValue(), ((CompoundTag) actual).get(entry.getKey())));
        if (expected instanceof ListTag<?> wanted) {
            ListTag<?> held = (ListTag<?>) actual;
            if (wanted.isEmpty()) return held.isEmpty();
            return wanted.stream().allMatch(element -> held.stream().anyMatch(candidate -> matches(element, candidate)));
        }
        return expected.equals(actual);
    }

    /** Merges two custom data compounds into the least one both pass against, or empty where a key holds two values. */
    private static @NotNull Optional<CompoundTag> merge(@NotNull CompoundTag left, @NotNull CompoundTag right) {
        CompoundTag merged = new CompoundTag();
        left.entrySet().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, Tag<?>> entry : right.entrySet()) {
            Tag<?> held = merged.get(entry.getKey());
            if (held == null) {
                merged.put(entry.getKey(), entry.getValue());
                continue;
            }

            Optional<? extends Tag<?>> both = held instanceof CompoundTag heldCompound && entry.getValue() instanceof CompoundTag added
                ? merge(heldCompound, added)
                : Optional.of(held).filter(entry.getValue()::equals);
            if (both.isEmpty()) return Optional.empty();
            merged.put(entry.getKey(), both.get());
        }
        return Optional.of(merged);
    }

    /**
     * Reads a case's {@code when} as the values it matches: an array as one value per element, which
     * is how vanilla reads one whose every element decodes, and anything else as one value.
     */
    private static @NotNull List<JsonElement> values(@NotNull JsonElement when) {
        return when.isJsonArray() ? when.getAsJsonArray().asList() : List.of(when);
    }

    /** Answers whether a dimension select's values name the overworld, the dimension an icon is drawn in. */
    private static boolean overworld(@NotNull List<JsonElement> values) {
        return values.stream().anyMatch(value -> ResourceId.parse(value.getAsString()).id().equals(ItemModelContext.DIMENSION_OVERWORLD));
    }

    /** Reads a node type or a dispatch property the way vanilla parses an identifier - its vanilla path, or {@code ""} for a mod's. */
    private static @NotNull String vocabulary(@NotNull JsonObject node, @NotNull String key) {
        return ResourceId.vanillaPath(string(node, key)).orElse("");
    }

    /** Reads a string member, {@code ""} where it is absent. */
    private static @NotNull String string(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** Reads an array member, empty where it is absent. */
    private static @NotNull JsonArray array(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /** Extends a path by one step. */
    private static @NotNull List<Step> then(@NotNull List<Step> path, @NotNull Step step) {
        return Stream.concat(path.stream(), Stream.of(step)).toList();
    }

}
