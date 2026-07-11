package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A parsed {@code .mcmeta} document - either a pack root {@code pack.mcmeta} or a per-asset
 * {@code <file>.png.mcmeta} sidecar. One umbrella holds every render-relevant section as an optional:
 * {@link Pack} for the root, and the four sidecar sections ({@link Animation}, {@link TextureFlags},
 * {@link GuiScaling}, {@link Villager}) that any one sidecar may combine. Sections absent from the
 * document parse to {@link Optional#empty()}; a single {@link #parse} pass reads whichever are present.
 *
 * @param id the asset this document annotates; a pack root uses {@code <packId>:pack}
 * @param pack the {@code pack} section, present only for a {@code pack.mcmeta}
 * @param animation the {@code animation} flipbook section, when present
 * @param texture the {@code texture} sampler-flags section, when present
 * @param gui the {@code gui.scaling} sprite-scaling section, when present
 * @param villager the {@code villager} hat-overlay section, when present
 */
public record MCMeta(
    @NotNull ResourceId id,
    @NotNull Optional<Pack> pack,
    @NotNull Optional<Animation> animation,
    @NotNull Optional<TextureFlags> texture,
    @NotNull Optional<GuiScaling> gui,
    @NotNull Optional<Villager> villager
) {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The empty document, carrying no sections; the default when a pack or asset ships no mcmeta. */
    public static final @NotNull MCMeta EMPTY = new MCMeta(
        new ResourceId(ResourceId.DEFAULT_NAMESPACE, ""),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * Parses a {@code .mcmeta} document from its JSON text.
     *
     * @param json the raw JSON text
     * @param id the asset id this document annotates
     * @return the parsed document
     * @throws PipelineException if the JSON is unreadable or a present section carries a malformed
     *     encoding
     */
    public static @NotNull MCMeta parse(@NotNull String json, @NotNull ResourceId id) {
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException ex) {
            throw new PipelineException(ex, "Malformed mcmeta for '%s'", id);
        }
        if (root == null)
            throw new PipelineException("Empty mcmeta for '%s'", id);
        return parse(root, id);
    }

    /**
     * Parses a {@code .mcmeta} document from an already-decoded JSON object, reading every section
     * present.
     *
     * @param root the decoded document root
     * @param id the asset id this document annotates
     * @return the parsed document
     * @throws PipelineException if a present section carries a malformed encoding
     */
    public static @NotNull MCMeta parse(@NotNull JsonObject root, @NotNull ResourceId id) {
        return new MCMeta(
            id,
            readPack(root, id),
            readAnimation(root),
            readTexture(root),
            readGui(root),
            readVillager(root));
    }

    private static @NotNull Optional<Pack> readPack(@NotNull JsonObject root, @NotNull ResourceId id) {
        if (!root.has("pack") || !root.get("pack").isJsonObject()) return Optional.empty();
        JsonObject pack = root.getAsJsonObject("pack");
        String packId = id.namespace();
        FormatRange formats = FormatRange.fromPackObject(pack, packId);
        Description description = pack.has("description")
            ? Description.of(pack.get("description"))
            : Description.EMPTY;
        return Optional.of(new Pack(formats, description, readOverlays(root, packId), readFilters(root, packId)));
    }

    private static @NotNull ConcurrentList<Overlay> readOverlays(@NotNull JsonObject root, @NotNull String packId) {
        if (!root.has("overlays") || !root.get("overlays").isJsonObject()) return Concurrent.newList();
        JsonObject overlays = root.getAsJsonObject("overlays");
        if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) return Concurrent.newList();

        ArrayList<Overlay> parsed = new ArrayList<>();
        for (JsonElement entry : overlays.getAsJsonArray("entries")) {
            if (!entry.isJsonObject()) continue;
            JsonObject obj = entry.getAsJsonObject();
            if (!obj.has("directory") || !obj.has("formats")) {
                System.err.printf("Pack '%s': skipping overlay entry missing directory/formats: %s%n", packId, obj);
                continue;
            }
            parsed.add(new Overlay(obj.get("directory").getAsString(), FormatRange.fromFormatsValue(obj.get("formats"), packId)));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    private static @NotNull ConcurrentList<Filter> readFilters(@NotNull JsonObject root, @NotNull String packId) {
        if (!root.has("filter") || !root.get("filter").isJsonObject()) return Concurrent.newList();
        JsonObject filter = root.getAsJsonObject("filter");
        if (!filter.has("block") || !filter.get("block").isJsonArray()) return Concurrent.newList();

        ArrayList<Filter> parsed = new ArrayList<>();
        for (JsonElement entry : filter.getAsJsonArray("block")) {
            if (!entry.isJsonObject()) continue;
            JsonObject obj = entry.getAsJsonObject();
            parsed.add(new Filter(compile(obj, "namespace", packId), compile(obj, "path", packId)));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    private static @NotNull Optional<Pattern> compile(@NotNull JsonObject obj, @NotNull String key, @NotNull String packId) {
        if (!obj.has(key)) return Optional.empty();
        String regex = obj.get(key).getAsString();
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException ex) {
            throw new PipelineException(ex, "Pack '%s' has a malformed filter.block %s regex '%s'", packId, key, regex);
        }
    }

    private static @NotNull Optional<Animation> readAnimation(@NotNull JsonObject root) {
        if (!root.has("animation") || !root.get("animation").isJsonObject()) return Optional.empty();
        JsonObject a = root.getAsJsonObject("animation");
        int frametime = a.has("frametime") ? a.get("frametime").getAsInt() : 1;
        boolean interpolate = a.has("interpolate") && a.get("interpolate").getAsBoolean();
        int width = a.has("width") ? a.get("width").getAsInt() : -1;
        int height = a.has("height") ? a.get("height").getAsInt() : -1;
        ConcurrentList<Frame> frames = a.has("frames") && a.get("frames").isJsonArray()
            ? parseFrames(a.getAsJsonArray("frames"))
            : Concurrent.newList();
        return Optional.of(new Animation(frametime, interpolate, width, height, frames));
    }

    private static @NotNull ConcurrentList<Frame> parseFrames(@NotNull JsonArray elements) {
        ArrayList<Frame> frames = new ArrayList<>(elements.size());
        for (JsonElement element : elements) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
                frames.add(new Frame(element.getAsInt(), -1));
            else if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                int index = entry.has("index") ? entry.get("index").getAsInt() : 0;
                int time = entry.has("time") ? entry.get("time").getAsInt() : -1;
                frames.add(new Frame(index, time));
            }
        }
        return Concurrent.adoptList(frames).toUnmodifiable();
    }

    private static @NotNull Optional<TextureFlags> readTexture(@NotNull JsonObject root) {
        if (!root.has("texture") || !root.get("texture").isJsonObject()) return Optional.empty();
        JsonObject t = root.getAsJsonObject("texture");
        boolean blur = t.has("blur") && t.get("blur").getAsBoolean();
        boolean clamp = t.has("clamp") && t.get("clamp").getAsBoolean();
        return Optional.of(new TextureFlags(blur, clamp));
    }

    private static @NotNull Optional<GuiScaling> readGui(@NotNull JsonObject root) {
        if (!root.has("gui") || !root.get("gui").isJsonObject()) return Optional.empty();
        JsonObject gui = root.getAsJsonObject("gui");
        JsonObject scaling = gui.has("scaling") && gui.get("scaling").isJsonObject()
            ? gui.getAsJsonObject("scaling")
            : new JsonObject();

        GuiScaling.Type type = GuiScaling.Type.STRETCH;
        if (scaling.has("type"))
            type = GuiScaling.Type.parse(scaling.get("type").getAsString());
        int width = scaling.has("width") ? scaling.get("width").getAsInt() : -1;
        int height = scaling.has("height") ? scaling.get("height").getAsInt() : -1;
        GuiScaling.Border border = scaling.has("border")
            ? GuiScaling.Border.of(scaling.get("border"))
            : new GuiScaling.Border(0, 0, 0, 0);
        boolean stretchInner = scaling.has("stretch_inner") && scaling.get("stretch_inner").getAsBoolean();
        return Optional.of(new GuiScaling(type, width, height, border, stretchInner));
    }

    private static @NotNull Optional<Villager> readVillager(@NotNull JsonObject root) {
        if (!root.has("villager") || !root.get("villager").isJsonObject()) return Optional.empty();
        JsonObject v = root.getAsJsonObject("villager");
        Villager.Hat hat = v.has("hat") ? Villager.Hat.parse(v.get("hat").getAsString()) : Villager.Hat.NONE;
        return Optional.of(new Villager(hat));
    }

    /**
     * The {@code pack} section of a root {@code pack.mcmeta}.
     *
     * @param formats the normalized declared format range
     * @param description the pack description
     * @param overlays the declared overlay entries, in declaration order (later wins)
     * @param filters the {@code filter.block} patterns hiding matching files in lower packs
     */
    public record Pack(
        @NotNull FormatRange formats,
        @NotNull Description description,
        @NotNull ConcurrentList<Overlay> overlays,
        @NotNull ConcurrentList<Filter> filters
    ) {}

    /**
     * One {@code overlays.entries[*]} entry.
     *
     * @param directory the overlay subdirectory name, relative to the pack root
     * @param formats the format range gating whether this overlay contributes a root
     */
    public record Overlay(@NotNull String directory, @NotNull FormatRange formats) {}

    /**
     * One {@code filter.block[*]} pattern; a file is hidden when both present patterns match.
     *
     * @param namespace the namespace regex, if declared
     * @param path the path regex, if declared
     */
    public record Filter(@NotNull Optional<Pattern> namespace, @NotNull Optional<Pattern> path) {}

    /**
     * A pack description, normalizing the string and text-component encodings.
     *
     * @param plain the depth-first {@code text} concatenation with {@code §} codes preserved
     * @param raw the original JSON element, round-tripping the structured form
     */
    public record Description(@NotNull String plain, @NotNull JsonElement raw) {

        /** The empty description. */
        public static final @NotNull Description EMPTY = new Description("", com.google.gson.JsonNull.INSTANCE);

        /**
         * Normalizes a raw description element - a bare string, a text-component object, or an array
         * of either - into a plain flattened string plus the untouched raw element.
         *
         * @param raw the raw {@code description} JSON element
         * @return the normalized description
         */
        public static @NotNull Description of(@NotNull JsonElement raw) {
            return new Description(flatten(raw), raw);
        }

        private static @NotNull String flatten(@NotNull JsonElement element) {
            if (element.isJsonPrimitive()) return element.getAsString();
            if (element.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement child : element.getAsJsonArray()) sb.append(flatten(child));
                return sb.toString();
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                StringBuilder sb = new StringBuilder();
                if (obj.has("text") && obj.get("text").isJsonPrimitive()) sb.append(obj.get("text").getAsString());
                if (obj.has("extra") && obj.get("extra").isJsonArray())
                    for (JsonElement child : obj.getAsJsonArray("extra")) sb.append(flatten(child));
                return sb.toString();
            }
            return "";
        }

    }

    /**
     * A flipbook {@code animation} sidecar section.
     *
     * @param frametime the default per-frame duration in ticks
     * @param interpolate whether adjacent frames blend
     * @param width the explicit frame width override, or {@code -1} to inherit
     * @param height the explicit frame height override, or {@code -1} to inherit
     * @param frames the normalized playback sequence; bare strip indices carry the {@code -1}
     *     duration marker deferring to {@link #frametime}
     */
    public record Animation(
        int frametime,
        boolean interpolate,
        int width,
        int height,
        @NotNull ConcurrentList<Frame> frames
    ) {}

    /**
     * One entry in an {@link Animation#frames} sequence.
     *
     * @param index the zero-based frame index into the strip
     * @param time the per-frame duration in ticks, or {@code -1} to defer to {@link Animation#frametime}
     */
    public record Frame(int index, int time) {}

    /**
     * A {@code texture} sidecar section carrying sampler flags.
     *
     * @param blur whether the sampler blurs on magnification
     * @param clamp whether the sampler clamps at texture edges
     */
    public record TextureFlags(boolean blur, boolean clamp) {}

    /**
     * A {@code gui.scaling} sidecar section describing how a GUI sprite scales.
     *
     * @param type the scaling mode
     * @param width the base sprite width, or {@code -1} when unspecified
     * @param height the base sprite height, or {@code -1} when unspecified
     * @param border the nine-slice border insets
     * @param stretchInner whether the nine-slice center stretches rather than tiles
     */
    public record GuiScaling(@NotNull Type type, int width, int height, @NotNull Border border, boolean stretchInner) {

        /** The GUI sprite scaling mode; defaults to {@link #STRETCH}. */
        public enum Type {
            STRETCH, TILE, NINE_SLICE;

            /**
             * Parses a scaling type name case-insensitively, defaulting to {@link #STRETCH} on an
             * unrecognised value.
             *
             * @param name the type name
             * @return the parsed type, or {@link #STRETCH}
             */
            public static @NotNull Type parse(@NotNull String name) {
                return switch (name.toLowerCase(Locale.ROOT)) {
                    case "tile" -> TILE;
                    case "nine_slice" -> NINE_SLICE;
                    default -> STRETCH;
                };
            }
        }

        /**
         * Nine-slice border insets; the bare-integer form applies the same inset to all four sides.
         *
         * @param left the left inset
         * @param top the top inset
         * @param right the right inset
         * @param bottom the bottom inset
         */
        public record Border(int left, int top, int right, int bottom) {

            /**
             * Reads a {@code border} value - a bare int applied to all four sides, or a
             * {@code {left,top,right,bottom}} object (missing keys default to {@code 0}).
             *
             * @param value the border value
             * @return the parsed border
             */
            public static @NotNull Border of(@NotNull JsonElement value) {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    int all = value.getAsInt();
                    return new Border(all, all, all, all);
                }
                JsonObject obj = value.getAsJsonObject();
                return new Border(side(obj, "left"), side(obj, "top"), side(obj, "right"), side(obj, "bottom"));
            }

            private static int side(@NotNull JsonObject obj, @NotNull String key) {
                return obj.has(key) ? obj.get(key).getAsInt() : 0;
            }
        }
    }

    /**
     * A {@code villager} sidecar section carrying the hat-overlay flag.
     *
     * @param hat how the villager profession hat overlays this texture
     */
    public record Villager(@NotNull Hat hat) {

        /** The villager hat overlay flag; defaults to {@link #NONE}. */
        public enum Hat {
            NONE, PARTIAL, FULL;

            /**
             * Parses a hat name case-insensitively, defaulting to {@link #NONE} on an unrecognised value.
             *
             * @param name the hat name
             * @return the parsed hat, or {@link #NONE}
             */
            public static @NotNull Hat parse(@NotNull String name) {
                return switch (name.toLowerCase(Locale.ROOT)) {
                    case "partial" -> PARTIAL;
                    case "full" -> FULL;
                    default -> NONE;
                };
            }
        }
    }

}
