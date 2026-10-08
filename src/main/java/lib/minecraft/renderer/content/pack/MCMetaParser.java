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
 * present; a section absent from the document lands as {@link Optional#empty()}.
 * <p>
 * A document is malformed where it is not JSON, holds nothing, or carries a value of the wrong type
 * where a section's member is read - text where a number goes, an object or an array where a name goes,
 * a frame entry that is neither a strip index nor an object - as vanilla's metadata codec refuses each.
 * Every one of them is raised as a {@link ContentException} naming the document.
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
     * @throws ContentException if the JSON is unreadable or empty, or a present section carries a
     *     malformed encoding or a value of the wrong type
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
     * @throws ContentException if a present section carries a malformed encoding or a value of the
     *     wrong type
     */
    public static @NotNull MCMeta parse(@NotNull JsonTree root, @NotNull ResourceId id) {
        try {
            return new MCMeta(
                id,
                readPack(root, id),
                readAnimation(root, id),
                readTexture(root),
                readGui(root, id),
                readVillager(root));
        } catch (NumberFormatException | IllegalStateException | UnsupportedOperationException ex) {
            // A number read from text that is not one, or an object or array read as a primitive: the
            // JSON reader's own refusals of a value of the wrong type.
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
            if (!entry.has("directory") || !entry.has("formats")) {
                System.err.printf("Pack '%s': skipping overlay entry missing directory/formats: %s%n", packId, entry.toGson());
                continue;
            }
            parsed.add(new MCMeta.Overlay(entry.findString("directory").orElse(null), FormatRange.fromFormatsValue(entry.find("formats").orElse(null), packId)));
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

    private static @NotNull Optional<MCMeta.Animation> readAnimation(@NotNull JsonTree root, @NotNull ResourceId id) {
        Optional<JsonTree> animation = root.findObject("animation");
        if (animation.isEmpty()) return Optional.empty();
        JsonTree a = animation.get();
        int frametime = a.getInt("frametime", 1);
        boolean interpolate = a.getBoolean("interpolate", false);
        int width = a.getInt("width", -1);
        int height = a.getInt("height", -1);
        ConcurrentList<MCMeta.Frame> frames = a.findArray("frames").map(entries -> parseFrames(entries, id)).orElseGet(Concurrent::newList);
        return Optional.of(new MCMeta.Animation(frametime, interpolate, width, height, frames));
    }

    private static @NotNull ConcurrentList<MCMeta.Frame> parseFrames(@NotNull JsonTree elements, @NotNull ResourceId id) {
        return elements.elements()
            .map(element -> {
                if (element.asInt().isPresent()) return new MCMeta.Frame(element.asInt().get(), -1);
                if (element.isObject()) return new MCMeta.Frame(element.getInt("index", 0), element.getInt("time", -1));
                throw new ContentException("Malformed animation frame in mcmeta for '%s': %s", id, element.toGson());
            })
            .collect(Concurrent.toUnmodifiableList());
    }

    private static @NotNull Optional<MCMeta.TextureFlags> readTexture(@NotNull JsonTree root) {
        Optional<JsonTree> texture = root.findObject("texture");
        if (texture.isEmpty()) return Optional.empty();
        JsonTree t = texture.get();
        boolean blur = t.getBoolean("blur", false);
        boolean clamp = t.getBoolean("clamp", false);
        return Optional.of(new MCMeta.TextureFlags(blur, clamp));
    }

    private static @NotNull Optional<MCMeta.GuiScaling> readGui(@NotNull JsonTree root, @NotNull ResourceId id) {
        Optional<JsonTree> gui = root.findObject("gui");
        if (gui.isEmpty()) return Optional.empty();
        JsonTree scaling = gui.get().findObject("scaling").orElseGet(JsonTree::object);

        MCMeta.GuiScaling.Type type = MCMeta.GuiScaling.Type.STRETCH;
        if (scaling.has("type")) {
            // The type names a scaling mode, so a member that is not text names none.
            JsonElement named = scaling.find("type").orElseThrow().toGson();
            if (!named.isJsonPrimitive() || !named.getAsJsonPrimitive().isString())
                throw new ContentException("Malformed gui.scaling type in mcmeta for '%s': %s", id, named);
            type = MCMeta.GuiScaling.Type.parse(named.getAsString());
        }
        int width = scaling.getInt("width", -1);
        int height = scaling.getInt("height", -1);
        MCMeta.GuiScaling.Border border = scaling.find("border")
            .map(MCMeta.GuiScaling.Border::of)
            .orElse(new MCMeta.GuiScaling.Border(0, 0, 0, 0));
        boolean stretchInner = scaling.getBoolean("stretch_inner", false);
        return Optional.of(new MCMeta.GuiScaling(type, width, height, border, stretchInner));
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
