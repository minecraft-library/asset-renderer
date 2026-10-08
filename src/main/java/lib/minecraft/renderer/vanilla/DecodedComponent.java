package lib.minecraft.renderer.vanilla;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.nbt.tag.ByteArrayTag;
import lib.minecraft.nbt.tag.ByteTag;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.EndTag;
import lib.minecraft.nbt.tag.IntArrayTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.ListTag;
import lib.minecraft.nbt.tag.LongArrayTag;
import lib.minecraft.nbt.tag.LongTag;
import lib.minecraft.nbt.tag.NumericalTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.nbt.tag.Tag;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.ChatColor;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The data components a {@code minecraft:component} select keys on that this renderer decodes, each
 * read as vanilla's codec for it reads a value. Vanilla matches a stack's value against the case values
 * by the decoded values' equality, and here both sides reduce to one canonical key - a case value at
 * load, through {@link #caseKey(Tag)}, and the stack's value at the walk, through {@link #key(Optional)}
 * - so two values are equal exactly when their keys are.
 *
 * <ul>
 *   <li><b>{@link #DYED_COLOR}</b> - an RGB integer, keyed by its decimal spelling.</li>
 *   <li><b>{@link #CUSTOM_NAME}</b> - a text component, compared on its contents, then its style,
 *       then its siblings in order.</li>
 *   <li><b>{@link #LORE}</b> - a list of text components, compared line by line.</li>
 *   <li><b>{@link #ITEM_MODEL}</b> - an identifier, keyed qualified to {@code minecraft:}. A stack
 *       whose patch neither sets nor removes it holds its item's own id, as every 26.1 item does
 *       by default, which the walk fills in before the key is read.</li>
 * </ul>
 *
 * <p>Both sides are read as NBT, a case value in the form its JSON converts to and a stack's value in
 * its own 26.1 form. A text component reads the same way from either. A string is a plain literal with
 * no style; a list is its first element with the rest appended as siblings; a compound picks its
 * contents by vanilla's order - {@code text}, then {@code translate}, {@code keybind}, {@code score},
 * {@code selector}, {@code nbt} and the {@code object} forms, or the kind its {@code type} names - and
 * carries its siblings in {@code extra}. A literal compares on its text, and every other contents kind
 * on its members. The five style flags are three-state, so {@code italic:false} is not an absent italic.
 * A colour compares by its spelling, a named colour by name and a hex one by its six-digit value,
 * because vanilla's select map hashes the name and so misses where a named and a hex spelling of one
 * colour meet. Shadow colour, click and hover events, insertion and font compare on their members.
 *
 * <p>The two sides differ where JSON and NBT type a value differently. A case value converts with every
 * whole number an int or a long, so the one byte tag it holds is a JSON boolean: there a style flag is
 * that byte and nothing else, and a number is any other numeric tag. On the stack side a flag is any
 * numeric tag, non-zero meaning set, and every numeric tag is a number. On both, the end tag is a null,
 * which a compound's member reads as absent, and a list element wrapped as the one entry of a compound
 * keyed by the empty string - vanilla's binary form of a list whose elements differ in type - is read
 * as the element it wraps.
 *
 * <p>Any other component's select is unevaluable here and takes its fallback.
 */
@Parity(claim = "asset-layer")
@RequiredArgsConstructor
public enum DecodedComponent {

    /** {@code minecraft:dyed_color} - an integer, or three floats folded to one as vanilla's {@code ARGB.colorFromFloat} folds them. */
    DYED_COLOR("minecraft:dyed_color"),

    /** {@code minecraft:custom_name} - a text component. */
    CUSTOM_NAME("minecraft:custom_name"),

    /** {@code minecraft:lore} - a list of at most {@code 256} text components. */
    LORE("minecraft:lore"),

    /** {@code minecraft:item_model} - the identifier of the item definition a stack draws. */
    ITEM_MODEL("minecraft:item_model");

    /** The text style members a canonical style writes after the colour and the flags, in vanilla's field order. */
    private static final @NotNull ConcurrentList<String> STYLE_MEMBERS =
        Concurrent.newUnmodifiableList("click_event", "hover_event", "insertion", "font");

    /** The three-state style flags, in vanilla's field order. */
    private static final @NotNull ConcurrentList<String> FLAGS =
        Concurrent.newUnmodifiableList("bold", "italic", "underlined", "strikethrough", "obfuscated");

    /** The named text colours vanilla parses, its chat formatting colours, each spelled as vanilla's {@code ChatFormatting.getName} lowercases its constant's name, in the root locale. */
    private static final @NotNull ConcurrentSet<String> NAMED_COLOURS = Arrays.stream(ChatColor.Legacy.values())
        .map(colour -> colour.name().toLowerCase(Locale.ROOT))
        .collect(Concurrent.toUnmodifiableSet());

    /** The text contents kinds a {@code type} member may name. */
    private static final @NotNull ConcurrentSet<String> CONTENT_KINDS = Concurrent.newUnmodifiableSet(
        "text", "translatable", "keybind", "score", "selector", "nbt", "object");

    /** The most lines a lore holds. */
    private static final int MAX_LORE_LINES = 256;

    /** The line separator, which a written string escapes. */
    private static final char LINE_SEPARATOR = '\u2028';

    /** The paragraph separator, which a written string escapes. */
    private static final char PARAGRAPH_SEPARATOR = '\u2029';

    /** The qualified component id, the key a stack's component map holds this component's value under. */
    @Getter(style = NamingStyle.FLUENT)
    private final @NotNull String id;

    /**
     * Finds the decoded component a select names.
     *
     * @param componentId the select's {@code component} id, qualified to {@code minecraft:} when bare
     * @return the decoded component, or empty when this renderer does not decode it
     */
    public static @NotNull Optional<DecodedComponent> of(@NotNull String componentId) {
        String id = ResourceId.parse(componentId).id();
        return Arrays.stream(values()).filter(component -> component.id.equals(id)).findFirst();
    }

    /**
     * Reduces a case value of this component to its key, the value read in the form its JSON converts
     * to, which the class doc describes.
     *
     * @param value the case value, converted from its JSON
     * @return the key
     * @throws IllegalArgumentException if the value does not decode as this component
     */
    public @NotNull String caseKey(@NotNull Tag<?> value) {
        return this.reduce(value, false);
    }

    /**
     * Reduces a stack's value of this component to the key its case values are compared with.
     *
     * @param components the stack's component map keyed by qualified component id, or empty when the caller supplies no stack
     * @return the key, or empty when the stack does not hold the component or its value does not decode
     */
    public @NotNull Optional<String> key(@NotNull Optional<CompoundTag> components) {
        return components.map(map -> map.get(this.id)).flatMap(value -> {
            try {
                return Optional.of(this.reduce(value, true));
            } catch (IllegalArgumentException unreadable) {
                return Optional.empty();
            }
        });
    }

    /**
     * Reads the {@code minecraft:dyed_color} a stack's patch sets, decoded as vanilla's
     * {@code RGB_COLOR_CODEC} decodes it - the colour a {@code minecraft:dye} tint source reads
     * before its default.
     *
     * @param components the stack's component map keyed by qualified component id, or empty when the caller supplies no stack
     * @return the colour, or empty when the patch does not set the component or its value does not decode
     */
    public static @NotNull OptionalInt dyedColor(@NotNull Optional<CompoundTag> components) {
        Optional<Tag<?>> value = components.map(map -> map.get(DYED_COLOR.id));
        if (value.isEmpty()) return OptionalInt.empty();

        try {
            return OptionalInt.of(rgb(value.get(), true));
        } catch (IllegalArgumentException unreadable) {
            return OptionalInt.empty();
        }
    }

    /**
     * Whether a compound is vanilla's list-element wrapper - one entry, keyed by the empty string, which
     * is how vanilla's binary form writes each element of a list whose elements differ in type.
     *
     * @param compound the compound to test
     * @return whether the compound wraps one list element
     */
    public static boolean isWrapper(@NotNull CompoundTag compound) {
        return compound.size() == 1 && compound.containsKey("");
    }

    /** Reduces one value of this component to its key, read as a stack's value where {@code stack} is set and as a converted case value otherwise. */
    private @NotNull String reduce(@NotNull Tag<?> value, boolean stack) {
        return switch (this) {
            case DYED_COLOR -> Integer.toString(rgb(value, stack));
            case CUSTOM_NAME -> text(value, stack).toString();
            case LORE -> lore(value, stack);
            case ITEM_MODEL -> identifier(value);
        };
    }

    /** Decodes an identifier as vanilla's {@code Identifier.CODEC} reads one: a string, qualified to {@code minecraft:} when bare. */
    private static @NotNull String identifier(@NotNull Tag<?> value) {
        if (!(value instanceof StringTag string)) throw undecodable("An identifier is a string, not '%s'", value);
        return ResourceId.parse(string.getValue()).id();
    }

    /** Decodes a dyed colour as vanilla's {@code RGB_COLOR_CODEC} does: any number's int value, else three floats. */
    private static int rgb(@NotNull Tag<?> value, boolean stack) {
        if (isNumber(value, stack)) return ((NumericalTag<?>) value).intValue();
        Optional<List<Tag<?>>> channels = elements(value);
        if (channels.isPresent() && channels.get().size() == 3 && channels.get().stream().allMatch(channel -> isNumber(channel, stack))) {
            List<Tag<?>> rgb = channels.get();
            return 0xFF << 24
                | (channel(((NumericalTag<?>) rgb.getFirst()).floatValue()) & 0xFF) << 16
                | (channel(((NumericalTag<?>) rgb.get(1)).floatValue()) & 0xFF) << 8
                | channel(((NumericalTag<?>) rgb.getLast()).floatValue()) & 0xFF;
        }
        throw undecodable("A dyed colour is a number or three floats, not '%s'", value);
    }

    /** One float channel as vanilla's {@code ARGB.as8BitChannel} scales it - {@code Mth.floor(value * 255)}. */
    private static int channel(float value) {
        float scaled = value * 255f;
        int truncated = (int) scaled;
        return scaled < truncated ? truncated - 1 : truncated;
    }

    /** Decodes a lore - a list of at most {@value #MAX_LORE_LINES} text components - to its canonical form. */
    private static @NotNull String lore(@NotNull Tag<?> value, boolean stack) {
        List<Tag<?>> lines = elements(value).orElseThrow(() -> undecodable("A lore is a list of lines, not '%s'", value));
        if (lines.size() > MAX_LORE_LINES)
            throw undecodable("A lore holds at most %d lines, not %d", MAX_LORE_LINES, lines.size());
        return lines.stream()
            .map(line -> text(line, stack).toString())
            .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Decodes a text component to its canonical form, {@code [contents, style, siblings]}: the
     * contents a literal's text or another kind's members, the style an object of the members it
     * sets in a fixed order, and the siblings the canonical forms of the components it appends.
     */
    private static @NotNull Text text(@NotNull Tag<?> value, boolean stack) {
        if (value instanceof StringTag string) return new Text(quote(string.getValue()), "{}", List.of());
        Optional<List<Tag<?>>> list = elements(value);
        if (list.isPresent()) {
            if (list.get().isEmpty()) throw undecodable("A text component list is empty");
            Text first = text(list.get().getFirst(), stack);
            List<String> siblings = new ArrayList<>(first.siblings());
            list.get().stream().skip(1).forEach(sibling -> siblings.add(text(sibling, stack).toString()));
            return new Text(first.contents(), first.style(), siblings);
        }
        if (!(value instanceof CompoundTag component))
            throw undecodable("A text component is a string, a list or a compound, not '%s'", value);

        List<String> siblings = new ArrayList<>();
        Optional<Tag<?>> extra = member(component, "extra");
        if (extra.isPresent()) {
            List<Tag<?>> appended = elements(extra.get())
                .filter(elements -> !elements.isEmpty())
                .orElseThrow(() -> undecodable("A text component's extra is a non-empty list, not '%s'", extra.get()));
            appended.forEach(sibling -> siblings.add(text(sibling, stack).toString()));
        }
        return new Text(contents(component), style(component, stack), siblings);
    }

    /** Decodes a compound component's contents: a literal's text as a string, any other kind as its kind and canonical members. */
    private static @NotNull String contents(@NotNull CompoundTag component) {
        Optional<Tag<?>> type = member(component, "type");
        String kind;
        if (type.isPresent()) {
            if (!(type.get() instanceof StringTag named) || !CONTENT_KINDS.contains(named.getValue()))
                throw undecodable("Unknown text component type '%s'", type.get());
            kind = named.getValue();
        } else {
            kind = inferredKind(component).orElseThrow(() -> undecodable("A text component names no contents: '%s'", component));
        }

        if (kind.equals("text")) {
            return member(component, "text")
                .filter(StringTag.class::isInstance)
                .map(text -> quote(((StringTag) text).getValue()))
                .orElseThrow(() -> undecodable("A text component has no string 'text': '%s'", component));
        }
        String members = object(component.entrySet()
            .stream()
            .filter(entry -> !entry.getKey().equals("type") && !entry.getKey().equals("extra"))
            .filter(entry -> !entry.getKey().equals("color") && !entry.getKey().equals("shadow_color"))
            .filter(entry -> !FLAGS.contains(entry.getKey()) && !STYLE_MEMBERS.contains(entry.getKey())));
        return "{" + quote("kind") + ":" + quote(kind) + "," + quote("members") + ":" + members + "}";
    }

    /** The contents kind vanilla's fuzzy match picks for a compound with no {@code type}: the first whose member it carries. */
    private static @NotNull Optional<String> inferredKind(@NotNull CompoundTag component) {
        if (member(component, "text").filter(StringTag.class::isInstance).isPresent()) return Optional.of("text");
        if (member(component, "translate").isPresent()) return Optional.of("translatable");
        if (member(component, "keybind").isPresent()) return Optional.of("keybind");
        if (member(component, "score").isPresent()) return Optional.of("score");
        if (member(component, "selector").isPresent()) return Optional.of("selector");
        if (member(component, "nbt").isPresent()) return Optional.of("nbt");
        if (Stream.of("object", "sprite", "player").anyMatch(key -> member(component, key).isPresent())) return Optional.of("object");
        return Optional.empty();
    }

    /** Decodes a compound component's style: the members it sets, in vanilla's field order, each in canonical form. */
    private static @NotNull String style(@NotNull CompoundTag component, boolean stack) {
        List<String> style = new ArrayList<>();
        member(component, "color").ifPresent(color -> style.add(quote("color") + ":" + quote(colour(color))));
        member(component, "shadow_color").ifPresent(shadow -> style.add(quote("shadow_color") + ":" + canonical(shadow)));
        for (String name : FLAGS)
            member(component, name).ifPresent(value -> style.add(quote(name) + ":" + flag(value, stack)));
        for (String key : STYLE_MEMBERS)
            member(component, key).ifPresent(value -> style.add(quote(key) + ":" + canonical(value)));
        return style.stream().collect(Collectors.joining(",", "{", "}"));
    }

    /** Decodes a text colour to its spelling: a named colour by name, a hex one as {@code #RRGGBB}. */
    private static @NotNull String colour(@NotNull Tag<?> value) {
        if (!(value instanceof StringTag string)) throw undecodable("A text colour is a string, not '%s'", value);
        String colour = string.getValue();
        if (!colour.startsWith("#")) {
            if (NAMED_COLOURS.contains(colour)) return colour;
            throw undecodable("Unknown text colour '%s'", colour);
        }
        try {
            int rgb = Integer.parseInt(colour.substring(1), 16);
            if (rgb >= 0 && rgb <= 0xFFFFFF) return String.format("#%06X", rgb);
        } catch (NumberFormatException ignored) {
            // Falls through to the refusal below, as an out-of-range value does.
        }
        throw undecodable("Invalid text colour '%s'", colour);
    }

    /** Decodes a style flag: on the case side the byte a JSON boolean converts to, on the stack side any numeric tag, non-zero meaning set. */
    private static boolean flag(@NotNull Tag<?> value, boolean stack) {
        if (stack && value instanceof NumericalTag<?> number) return number.byteValue() != 0;
        if (!stack && value instanceof ByteTag bool) return bool.byteValue() != 0;
        throw undecodable("A text style flag is a boolean, not '%s'", value);
    }

    /**
     * Reduces a member this renderer compares without decoding to one spelling shared by both sides:
     * compounds with their keys sorted and their null members dropped, a number by its value, so a
     * JSON boolean is {@code 1} or {@code 0} as both decode to the same number wherever vanilla reads
     * one, and a null kept where a list holds it.
     */
    private static @NotNull String canonical(@NotNull Tag<?> value) {
        if (value instanceof CompoundTag compound) return object(compound.entrySet().stream());
        Optional<List<Tag<?>>> elements = elements(value);
        if (elements.isPresent()) {
            return elements.get()
                .stream()
                .map(DecodedComponent::canonical)
                .collect(Collectors.joining(",", "[", "]"));
        }
        return switch (value) {
            case StringTag string -> quote(string.getValue());
            case NumericalTag<?> number -> number(number.getValue());
            default -> "null";
        };
    }

    /** Writes the canonical object of a compound's entries: the null members dropped, the rest sorted by key, each value canonical. */
    private static @NotNull String object(@NotNull Stream<Map.Entry<String, Tag<?>>> entries) {
        return entries.filter(entry -> !(entry.getValue() instanceof EndTag))
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> quote(entry.getKey()) + ":" + canonical(entry.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    }

    /** Writes a number by its value, trailing zeros stripped, or as a string where it is not finite. */
    private static @NotNull String number(@NotNull Number value) {
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toString();
        } catch (NumberFormatException notFinite) {
            return quote(value.toString());
        }
    }

    /**
     * Writes a string as a JSON string literal: quoted, with a quote, a backslash and every control
     * character escaped, and the line and paragraph separators too.
     */
    private static @NotNull String quote(@NotNull String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (character < 0x20 || character == LINE_SEPARATOR || character == PARAGRAPH_SEPARATOR)
                        quoted.append(String.format("\\u%04x", (int) character));
                    else
                        quoted.append(character);
                }
            }
        }
        return quoted.append('"').toString();
    }

    /** The elements of a list or array tag, each list element vanilla wrapped under the empty key read as the element it wraps, or empty for any other tag. */
    private static @NotNull Optional<List<Tag<?>>> elements(@NotNull Tag<?> value) {
        return switch (value) {
            case ListTag<?> list -> Optional.of(list.stream()
                .<Tag<?>>map(element -> element instanceof CompoundTag wrapper && isWrapper(wrapper) ? wrapper.get("") : element)
                .toList());
            case ByteArrayTag bytes -> {
                byte[] values = bytes.getValue();
                List<Tag<?>> elements = new ArrayList<>(values.length);
                for (byte element : values) elements.add(new ByteTag(element));
                yield Optional.of(elements);
            }
            case IntArrayTag ints -> Optional.of(Arrays.stream(ints.getValue()).<Tag<?>>mapToObj(IntTag::new).toList());
            case LongArrayTag longs -> Optional.of(Arrays.stream(longs.getValue()).<Tag<?>>mapToObj(LongTag::new).toList());
            default -> Optional.empty();
        };
    }

    /** A member of a compound, absent when missing or the end tag, as vanilla's map read treats a null. */
    private static @NotNull Optional<Tag<?>> member(@NotNull CompoundTag compound, @NotNull String key) {
        return Optional.<Tag<?>>ofNullable(compound.get(key)).filter(value -> !(value instanceof EndTag));
    }

    /** Whether a value is a number: any numeric tag on the stack side, and on the case side any but the byte a JSON boolean converts to. */
    private static boolean isNumber(@NotNull Tag<?> value, boolean stack) {
        return value instanceof NumericalTag<?> && (stack || !(value instanceof ByteTag));
    }

    /** The refusal of a value that does not decode, its message formatted from the arguments. */
    private static @NotNull IllegalArgumentException undecodable(@NotNull @PrintFormat String message, @Nullable Object... args) {
        return new IllegalArgumentException(String.format(message, args));
    }

    /**
     * One text component in canonical form, written as {@code [contents, style, siblings]}.
     *
     * @param contents the contents, already written - a literal's text as a string, any other kind as its kind and members
     * @param style the style, already written as an object of the members it sets
     * @param siblings the canonical form of each component appended to it, in order
     */
    private record Text(@NotNull String contents, @NotNull String style, @NotNull List<String> siblings) {

        /** {@inheritDoc} */
        @Override
        public @NotNull String toString() {
            return "[" + this.contents + "," + this.style + "," + this.siblings.stream().collect(Collectors.joining(",", "[", "]")) + "]";
        }

    }

}
