package lib.minecraft.renderer.content.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import dev.simplified.gson.JsonTree;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.FormatRange;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads a {@code .mcmeta} document - a pack root {@code pack.mcmeta} or a per-asset
 * {@code <file>.png.mcmeta} sidecar - into an {@link MCMeta}. One pass reads whichever sections are
 * present; a section absent from the document lands as {@link Optional#empty()}, and the animation,
 * texture and {@code gui} sections as {@link Possible#absent()}.
 * <p>
 * A document is malformed where it is not JSON or holds nothing, and a pack root's where its pack section
 * carries a malformed encoding or a value of the wrong type; each is raised as a {@link ContentException}
 * naming the document. A sidecar section the client's codec refuses - a value of the wrong JSON type, a
 * member it requires and the document lacks, a number out of its range, a name it does not know - is held
 * as there and refused, its {@link Possible} empty, and the rest of the document stands, because each of
 * the client's reads parses only the sections it asks for.
 */
@UtilityClass
@Parity(claim = "asset-layer")
public class MCMetaParser {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Parses a {@code .mcmeta} document from its JSON text.
     *
     * @param json the raw JSON text
     * @param id the asset id this document annotates
     * @return the parsed document
     * @throws ContentException if the JSON is unreadable or empty, or its pack section carries a malformed
     *     encoding or a value of the wrong type
     */
    public static @NotNull MCMeta parse(@NotNull String json, @NotNull ResourceId id) {
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException ex) {
            throw new ContentException(ex, "Malformed mcmeta for '%s'", id);
        }
        if (root == null)
            throw new ContentException("Empty mcmeta for '%s'", id);
        return parse(JsonTree.wrap(root), id);
    }

    /**
     * Parses a {@code .mcmeta} document from an already-decoded JSON object, reading every section
     * present.
     *
     * @param root the decoded document root
     * @param id the asset id this document annotates
     * @return the parsed document
     * @throws ContentException if its pack section carries a malformed encoding or a value of the wrong
     *     type
     */
    public static @NotNull MCMeta parse(@NotNull JsonTree root, @NotNull ResourceId id) {
        try {
            return new MCMeta(
                id,
                readPack(root, id),
                readAnimation(root),
                readTexture(root),
                readGui(root),
                readVillager(root));
        } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException ex) {
            // The pack section's format range reads its array elements as integers, so text or an object
            // there is the JSON reader's own refusal of a value of the wrong type.
            throw new ContentException(ex, "Malformed value in mcmeta for '%s'", id);
        }
    }

    private static @NotNull Optional<MCMeta.Pack> readPack(@NotNull JsonTree root, @NotNull ResourceId id) {
        Optional<JsonTree> packNode = root.findObject("pack");
        if (packNode.isEmpty()) return Optional.empty();
        JsonTree pack = packNode.get();
        String packId = id.namespace();
        FormatRange formats = FormatRange.fromPackObject(pack, packId);
        MCMeta.Description description = pack.find("description").map(MCMeta.Description::of).orElse(MCMeta.Description.EMPTY);
        return Optional.of(new MCMeta.Pack(formats, description, readOverlays(root, packId), readFilters(root, packId)));
    }

    private static @NotNull ConcurrentList<MCMeta.Overlay> readOverlays(@NotNull JsonTree root, @NotNull String packId) {
        Optional<JsonTree> overlays = root.findObject("overlays");
        if (overlays.isEmpty()) return Concurrent.newList();
        Optional<JsonTree> entries = overlays.get().findArray("entries");
        if (entries.isEmpty()) return Concurrent.newList();

        ArrayList<MCMeta.Overlay> parsed = new ArrayList<>();
        for (JsonTree entry : entries.get().elements().toList()) {
            if (!entry.isObject()) continue;
            Optional<FormatRange> formats = entry.has("directory") ? FormatRange.fromOverlayEntry(entry, packId) : Optional.empty();
            if (formats.isEmpty()) {
                System.err.printf("Pack '%s': skipping overlay entry missing a directory or a format range: %s%n", packId, entry.toGson());
                continue;
            }
            parsed.add(new MCMeta.Overlay(entry.findString("directory").orElse(null), formats.get()));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    private static @NotNull ConcurrentList<MCMeta.Filter> readFilters(@NotNull JsonTree root, @NotNull String packId) {
        Optional<JsonTree> filter = root.findObject("filter");
        if (filter.isEmpty()) return Concurrent.newList();
        Optional<JsonTree> block = filter.get().findArray("block");
        if (block.isEmpty()) return Concurrent.newList();

        return block.get()
            .elements()
            .filter(JsonTree::isObject)
            .map(entry -> new MCMeta.Filter(compile(entry, "namespace", packId), compile(entry, "path", packId)))
            .collect(Concurrent.toUnmodifiableList());
    }

    private static @NotNull Optional<Pattern> compile(@NotNull JsonTree obj, @NotNull String key, @NotNull String packId) {
        String regex = obj.findString(key).orElse(null);
        if (regex == null) return Optional.empty();
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException ex) {
            throw new ContentException(ex, "Pack '%s' has a malformed filter.block %s regex '%s'", packId, key, regex);
        }
    }

    private static @NotNull Possible<MCMeta.Animation> readAnimation(@NotNull JsonTree root) {
        Optional<JsonTree> section = root.find("animation");
        if (section.isEmpty()) return Possible.absent();

        try {
            JsonTree animation = section.get();
            if (!animation.isObject())
                throw new IllegalArgumentException(String.format("Not a JSON object: '%s'", animation.toGson()));

            // A member holding JSON null is missing to the client's codec.
            Optional<ConcurrentList<MCMeta.Frame>> frames = animation.find("frames")
                .filter(value -> !value.isNull())
                .map(MCMetaParser::parseFrames);
            return Possible.of(new MCMeta.Animation(
                optionalNumber(animation, "frametime").orElse(1),
                flag(animation, "interpolate"),
                optionalNumber(animation, "width"),
                optionalNumber(animation, "height"),
                frames));
        } catch (IllegalArgumentException ex) {
            // Each of the client's reads parses only the sections it asks for, so a refusal costs the reads
            // that ask for this one and the document's other sections stand.
            return Possible.empty();
        }
    }

    /**
     * Reads an animation's {@code frames} list as the client's codec reads it: each entry a strip index
     * alone, or an object naming its {@code index} and, optionally, its {@code time}.
     *
     * @param frames the {@code frames} member
     * @return the declared sequence
     * @throws IllegalArgumentException if the member is not an array, or the client's codec refuses an entry
     */
    private static @NotNull ConcurrentList<MCMeta.Frame> parseFrames(@NotNull JsonTree frames) {
        if (!frames.isArray())
            throw new IllegalArgumentException(String.format("Not a JSON array: '%s'", frames.toGson()));

        return frames.elements()
            .map(frame -> {
                Optional<Integer> index = frame.asInt();
                if (index.isPresent()) return new MCMeta.Frame(index.get(), Optional.empty());
                if (!frame.isObject())
                    throw new IllegalArgumentException(String.format("Not a number or a JSON object: '%s'", frame.toGson()));

                return new MCMeta.Frame(number(frame, "index"), optionalNumber(frame, "time"));
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    private static @NotNull Possible<MCMeta.TextureSampling> readTexture(@NotNull JsonTree root) {
        Optional<JsonTree> section = root.find("texture");
        if (section.isEmpty()) return Possible.absent();

        try {
            JsonTree texture = section.get();
            if (!texture.isObject())
                throw new IllegalArgumentException(String.format("Not a JSON object: '%s'", texture.toGson()));

            // A member holding JSON null is missing to the client's codec.
            MCMeta.TextureSampling.MipmapStrategy mipmapStrategy = MCMeta.TextureSampling.MipmapStrategy.AUTO;
            if (texture.find("mipmap_strategy").filter(value -> !value.isNull()).isPresent()) {
                String name = text(texture, "mipmap_strategy");
                mipmapStrategy = MCMeta.TextureSampling.MipmapStrategy.parse(name)
                    .orElseThrow(() -> new IllegalArgumentException(String.format("Unknown element name: '%s'", name)));
            }
            float alphaCutoffBias = texture.find("alpha_cutoff_bias")
                .filter(value -> !value.isNull())
                .map(value -> value.asFloat().orElseThrow(() ->
                    new IllegalArgumentException(String.format("Not a number: '%s'", value.toGson()))))
                .orElse(0f);
            return Possible.of(new MCMeta.TextureSampling(
                flag(texture, "blur"), flag(texture, "clamp"), mipmapStrategy, alphaCutoffBias));
        } catch (IllegalArgumentException ex) {
            // Each of the client's reads parses only the sections it asks for, so a refusal costs the reads
            // that ask for this one and the document's other sections stand.
            return Possible.empty();
        }
    }

    private static @NotNull Possible<MCMeta.GuiScaling> readGui(@NotNull JsonTree root) {
        Optional<JsonTree> gui = root.find("gui");
        if (gui.isEmpty()) return Possible.absent();

        try {
            return Possible.of(readScaling(gui.get()));
        } catch (IllegalArgumentException ex) {
            // Only the client's GUI atlas reads the section, so a refusal costs the GUI sprite read alone
            // and the document's other sections stand.
            return Possible.empty();
        }
    }

    /**
     * Reads a {@code gui} section as the client's codec reads it - the members its mode reads and no
     * other, each of the JSON type the codec wants.
     *
     * @param gui the {@code gui} member
     * @return the scaling the section declares
     * @throws IllegalArgumentException if the client's codec refuses the section
     */
    private static @NotNull MCMeta.GuiScaling readScaling(@NotNull JsonTree gui) {
        if (!gui.isObject())
            throw new IllegalArgumentException(String.format("Not a JSON object: '%s'", gui.toGson()));

        // A member holding JSON null is missing to the client's codec.
        Optional<JsonTree> declared = gui.find("scaling").filter(value -> !value.isNull());
        if (declared.isEmpty()) return MCMeta.GuiScaling.DEFAULT;

        JsonTree scaling = declared.get();
        if (!scaling.isObject())
            throw new IllegalArgumentException(String.format("Not a JSON object: '%s'", scaling.toGson()));

        String name = text(scaling, "type");
        MCMeta.GuiScaling.Type type = MCMeta.GuiScaling.Type.parse(name)
            .orElseThrow(() -> new IllegalArgumentException(String.format("Unknown element name: '%s'", name)));
        return switch (type) {
            case STRETCH -> new MCMeta.GuiScaling.Stretch();
            case TILE -> new MCMeta.GuiScaling.Tile(number(scaling, "width"), number(scaling, "height"));
            case NINE_SLICE -> new MCMeta.GuiScaling.NineSlice(
                number(scaling, "width"), number(scaling, "height"),
                border(required(scaling, "border")), flag(scaling, "stretch_inner"));
        };
    }

    /**
     * Reads a nine-slice's {@code border}: a number of at least 1 setting all four sides, or an object
     * naming each side.
     *
     * @param border the {@code border} member
     * @return the border it declares
     * @throws IllegalArgumentException if the value is neither form, or the number is below 1
     */
    private static @NotNull MCMeta.GuiScaling.NineSlice.Border border(@NotNull JsonTree border) {
        Optional<Integer> all = border.asInt();
        if (all.isPresent()) {
            if (all.get() < 1)
                throw new IllegalArgumentException(String.format("Value must be positive: '%s'", all.get()));

            return new MCMeta.GuiScaling.NineSlice.Border(all.get(), all.get(), all.get(), all.get());
        }
        if (!border.isObject())
            throw new IllegalArgumentException(String.format("Not a JSON object: '%s'", border.toGson()));

        return new MCMeta.GuiScaling.NineSlice.Border(
            number(border, "left"), number(border, "top"), number(border, "right"), number(border, "bottom"));
    }

    /**
     * Reads a member the client's codec requires, a member holding JSON {@code null} counting as
     * missing, as the codec counts it.
     *
     * @param object the section
     * @param key the member's name
     * @return the member
     * @throws IllegalArgumentException if the member is missing or {@code null}
     */
    private static @NotNull JsonTree required(@NotNull JsonTree object, @NotNull String key) {
        return object.find(key).filter(value -> !value.isNull()).orElseThrow(() ->
            new IllegalArgumentException(String.format("No key '%s' in '%s'", key, object.toGson())));
    }

    /**
     * Reads a required member as a JSON number, its integer value as the client's codec takes it.
     *
     * @param object the section
     * @param key the member's name
     * @return the member's integer value
     * @throws IllegalArgumentException if the member is missing, {@code null} or not a JSON number
     */
    private static int number(@NotNull JsonTree object, @NotNull String key) {
        return object.findInt(key).orElseThrow(() ->
            new IllegalArgumentException(String.format("No number under '%s' in '%s'", key, object.toGson())));
    }

    /**
     * Reads an optional member as a JSON number, its integer value as the client's codec takes it, a member
     * holding JSON {@code null} counting as missing.
     *
     * @param object the section
     * @param key the member's name
     * @return the member's integer value, or empty where it is missing or {@code null}
     * @throws IllegalArgumentException if the member is present and not a JSON number
     */
    private static @NotNull Optional<Integer> optionalNumber(@NotNull JsonTree object, @NotNull String key) {
        Optional<JsonTree> value = object.find(key).filter(member -> !member.isNull());
        if (value.isEmpty()) return Optional.empty();

        return Optional.of(value.get().asInt().orElseThrow(() ->
            new IllegalArgumentException(String.format("Not a number: '%s'", value.get().toGson()))));
    }

    /**
     * Reads a required member as JSON text.
     *
     * @param object the section
     * @param key the member's name
     * @return the member's text
     * @throws IllegalArgumentException if the member is missing or not JSON text
     */
    private static @NotNull String text(@NotNull JsonTree object, @NotNull String key) {
        JsonElement value = required(object, key).toGson();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(String.format("Not a string: '%s'", value));

        return value.getAsString();
    }

    /**
     * Reads an optional member as a JSON boolean, {@code false} where it is missing or {@code null}.
     *
     * @param object the section
     * @param key the member's name
     * @return the member's value, or {@code false}
     * @throws IllegalArgumentException if the member is present and not a JSON boolean
     */
    private static boolean flag(@NotNull JsonTree object, @NotNull String key) {
        Optional<JsonTree> value = object.find(key).filter(member -> !member.isNull());
        if (value.isEmpty()) return false;

        return value.get().asBool().orElseThrow(() ->
            new IllegalArgumentException(String.format("Not a boolean: '%s'", value.get().toGson())));
    }

    private static @NotNull Optional<MCMeta.Villager> readVillager(@NotNull JsonTree root) {
        Optional<JsonTree> villager = root.findObject("villager");
        if (villager.isEmpty()) return Optional.empty();
        JsonTree v = villager.get();
        // A `hat` member that is not a readable string (an object, an array, an explicit null) falls to
        // NONE rather than reaching the parser, which takes a non-null name.
        MCMeta.Villager.Hat hat = v.findString("hat").map(MCMeta.Villager.Hat::parse).orElse(MCMeta.Villager.Hat.NONE);
        return Optional.of(new MCMeta.Villager(hat));
    }

}
