package lib.minecraft.renderer.content.pack;

import com.google.gson.Gson;
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
     * @throws ContentException if the JSON is unreadable or a present section carries a malformed
     *     encoding
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
     * @throws ContentException if a present section carries a malformed encoding
     */
    public static @NotNull MCMeta parse(@NotNull JsonTree root, @NotNull ResourceId id) {
        return new MCMeta(
            id,
            readPack(root, id),
            readAnimation(root),
            readTexture(root),
            readGui(root),
            readVillager(root));
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

    private static @NotNull Optional<MCMeta.Animation> readAnimation(@NotNull JsonTree root) {
        Optional<JsonTree> animation = root.findObject("animation");
        if (animation.isEmpty()) return Optional.empty();
        JsonTree a = animation.get();
        int frametime = a.getInt("frametime", 1);
        boolean interpolate = a.getBoolean("interpolate", false);
        int width = a.getInt("width", -1);
        int height = a.getInt("height", -1);
        ConcurrentList<MCMeta.Frame> frames = a.findArray("frames").map(MCMetaParser::parseFrames).orElseGet(Concurrent::newList);
        return Optional.of(new MCMeta.Animation(frametime, interpolate, width, height, frames));
    }

    private static @NotNull ConcurrentList<MCMeta.Frame> parseFrames(@NotNull JsonTree elements) {
        return elements.elements()
            .filter(element -> element.asInt().isPresent() || element.isObject())
            .map(element -> element.asInt()
                .map(index -> new MCMeta.Frame(index, -1))
                .orElseGet(() -> new MCMeta.Frame(element.getInt("index", 0), element.getInt("time", -1))))
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

    private static @NotNull Optional<MCMeta.GuiScaling> readGui(@NotNull JsonTree root) {
        Optional<JsonTree> gui = root.findObject("gui");
        if (gui.isEmpty()) return Optional.empty();
        JsonTree scaling = gui.get().findObject("scaling").orElseGet(JsonTree::object);

        MCMeta.GuiScaling.Type type = MCMeta.GuiScaling.Type.STRETCH;
        if (scaling.has("type"))
            type = MCMeta.GuiScaling.Type.parse(scaling.findString("type").orElse(null));
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
