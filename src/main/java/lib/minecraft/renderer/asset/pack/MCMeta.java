package lib.minecraft.renderer.asset.pack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.node.JsonTree;
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
        return parse(JsonTree.wrap(root), id);
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
    public static @NotNull MCMeta parse(@NotNull JsonTree root, @NotNull ResourceId id) {
        return new MCMeta(
            id,
            readPack(root, id),
            readAnimation(root),
            readTexture(root),
            readGui(root),
            readVillager(root));
    }

    private static @NotNull Optional<Pack> readPack(@NotNull JsonTree root, @NotNull ResourceId id) {
        Optional<JsonTree> packNode = root.findObject("pack");
        if (packNode.isEmpty()) return Optional.empty();
        JsonTree pack = packNode.get();
        String packId = id.namespace();
        FormatRange formats = FormatRange.fromPackObject(pack, packId);
        Description description = pack.has("description")
            ? Description.of(pack.get("description"))
            : Description.EMPTY;
        return Optional.of(new Pack(formats, description, readOverlays(root, packId), readFilters(root, packId)));
    }

    private static @NotNull ConcurrentList<Overlay> readOverlays(@NotNull JsonTree root, @NotNull String packId) {
        Optional<JsonTree> overlays = root.findObject("overlays");
        if (overlays.isEmpty()) return Concurrent.newList();
        Optional<JsonTree> entries = overlays.get().findArray("entries");
        if (entries.isEmpty()) return Concurrent.newList();

        ArrayList<Overlay> parsed = new ArrayList<>();
        for (JsonTree entry : entries.get().elements()) {
            if (!entry.isObject()) continue;
            if (!entry.has("directory") || !entry.has("formats")) {
                System.err.printf("Pack '%s': skipping overlay entry missing directory/formats: %s%n", packId, entry.toGson());
                continue;
            }
            parsed.add(new Overlay(entry.getString("directory"), FormatRange.fromFormatsValue(entry.get("formats"), packId)));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    private static @NotNull ConcurrentList<Filter> readFilters(@NotNull JsonTree root, @NotNull String packId) {
        Optional<JsonTree> filter = root.findObject("filter");
        if (filter.isEmpty()) return Concurrent.newList();
        Optional<JsonTree> block = filter.get().findArray("block");
        if (block.isEmpty()) return Concurrent.newList();

        ArrayList<Filter> parsed = new ArrayList<>();
        for (JsonTree entry : block.get().elements()) {
            if (!entry.isObject()) continue;
            parsed.add(new Filter(compile(entry, "namespace", packId), compile(entry, "path", packId)));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    private static @NotNull Optional<Pattern> compile(@NotNull JsonTree obj, @NotNull String key, @NotNull String packId) {
        String regex = obj.getString(key);
        if (regex == null) return Optional.empty();
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException ex) {
            throw new PipelineException(ex, "Pack '%s' has a malformed filter.block %s regex '%s'", packId, key, regex);
        }
    }

    private static @NotNull Optional<Animation> readAnimation(@NotNull JsonTree root) {
        Optional<JsonTree> animation = root.findObject("animation");
        if (animation.isEmpty()) return Optional.empty();
        JsonTree a = animation.get();
        int frametime = a.getInt("frametime", 1);
        boolean interpolate = a.getBool("interpolate", false);
        int width = a.getInt("width", -1);
        int height = a.getInt("height", -1);
        ConcurrentList<Frame> frames = a.findArray("frames").map(MCMeta::parseFrames).orElseGet(Concurrent::newList);
        return Optional.of(new Animation(frametime, interpolate, width, height, frames));
    }

    private static @NotNull ConcurrentList<Frame> parseFrames(@NotNull JsonTree elements) {
        ArrayList<Frame> frames = new ArrayList<>(elements.size());
        for (JsonTree element : elements.elements()) {
            if (element.intValue().isPresent())
                frames.add(new Frame(element.intValue().get(), -1));
            else if (element.isObject()) {
                int index = element.getInt("index", 0);
                int time = element.getInt("time", -1);
                frames.add(new Frame(index, time));
            }
        }
        return Concurrent.adoptList(frames).toUnmodifiable();
    }

    private static @NotNull Optional<TextureFlags> readTexture(@NotNull JsonTree root) {
        Optional<JsonTree> texture = root.findObject("texture");
        if (texture.isEmpty()) return Optional.empty();
        JsonTree t = texture.get();
        boolean blur = t.getBool("blur", false);
        boolean clamp = t.getBool("clamp", false);
        return Optional.of(new TextureFlags(blur, clamp));
    }

    private static @NotNull Optional<GuiScaling> readGui(@NotNull JsonTree root) {
        Optional<JsonTree> gui = root.findObject("gui");
        if (gui.isEmpty()) return Optional.empty();
        JsonTree scaling = gui.get().findObject("scaling").orElseGet(JsonTree::object);

        GuiScaling.Type type = GuiScaling.Type.STRETCH;
        if (scaling.has("type"))
            type = GuiScaling.Type.parse(scaling.getString("type"));
        int width = scaling.getInt("width", -1);
        int height = scaling.getInt("height", -1);
        GuiScaling.Border border = scaling.has("border")
            ? GuiScaling.Border.of(scaling.get("border"))
            : new GuiScaling.Border(0, 0, 0, 0);
        boolean stretchInner = scaling.getBool("stretch_inner", false);
        return Optional.of(new GuiScaling(type, width, height, border, stretchInner));
    }

    private static @NotNull Optional<Villager> readVillager(@NotNull JsonTree root) {
        Optional<JsonTree> villager = root.findObject("villager");
        if (villager.isEmpty()) return Optional.empty();
        JsonTree v = villager.get();
        Villager.Hat hat = v.has("hat") ? Villager.Hat.parse(v.getString("hat")) : Villager.Hat.NONE;
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
    ) {

        /**
         * Whether any of this pack's {@code filter.block} patterns hides a resource id - the shared
         * predicate every pack-stack loader consults to erase lower-pack rows before merging its own.
         * The vanilla synthesised mcmeta declares no filters, so a vanilla-only stack hides nothing.
         *
         * @param id the resource id to test
         * @return {@code true} when a filter hides the id
         */
        public boolean hides(@NotNull ResourceId id) {
            return this.filters.stream().anyMatch(filter -> filter.matches(id));
        }

    }

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
    public record Filter(@NotNull Optional<Pattern> namespace, @NotNull Optional<Pattern> path) {

        /**
         * Whether this filter hides a resource id - every declared pattern matches (namespace regex
         * against the id namespace, path regex against the id name). A filter that declares neither
         * pattern hides nothing.
         * <p>
         * Matching is unanchored substring ({@link java.util.regex.Matcher#find}), mirroring the
         * vanilla client's {@code ResourceFilterSection}, whose {@code IdentifierPattern} builds its
         * predicates through {@link java.util.regex.Pattern#asPredicate} (defined as
         * {@code s -> matcher(s).find()}). An anchored full match would hide fewer resources than
         * vanilla for the same authored pattern.
         *
         * @param id the resource id to test
         * @return {@code true} when this filter hides the id
         */
        public boolean matches(@NotNull ResourceId id) {
            if (this.namespace.isEmpty() && this.path.isEmpty()) return false;
            boolean namespaceOk = this.namespace.map(pattern -> pattern.matcher(id.namespace()).find()).orElse(true);
            boolean pathOk = this.path.map(pattern -> pattern.matcher(id.name()).find()).orElse(true);
            return namespaceOk && pathOk;
        }

    }

    /**
     * A pack description, normalizing the string and text-component encodings.
     *
     * @param plain the depth-first {@code text} concatenation with {@code §} codes preserved
     */
    public record Description(@NotNull String plain) {

        /** The empty description. */
        public static final @NotNull Description EMPTY = new Description("");

        /**
         * Normalizes a raw description node - a bare string, a text-component object, or an array
         * of either - into a plain flattened string.
         *
         * @param raw the raw {@code description} JSON node
         * @return the normalized description
         */
        public static @NotNull Description of(@NotNull JsonTree raw) {
            return new Description(flatten(raw));
        }

        private static @NotNull String flatten(@NotNull JsonTree element) {
            if (element.isPrimitive()) return element.stringValue().orElseThrow();
            if (element.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonTree child : element.elements()) sb.append(flatten(child));
                return sb.toString();
            }
            if (element.isObject()) {
                StringBuilder sb = new StringBuilder();
                if (element.has("text") && element.get("text").isPrimitive()) sb.append(element.getString("text"));
                Optional<JsonTree> extra = element.findArray("extra");
                if (extra.isPresent())
                    for (JsonTree child : extra.get().elements()) sb.append(flatten(child));
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
            public static @NotNull Border of(@NotNull JsonTree value) {
                if (value.intValue().isPresent()) {
                    int all = value.intValue().get();
                    return new Border(all, all, all, all);
                }
                return new Border(side(value, "left"), side(value, "top"), side(value, "right"), side(value, "bottom"));
            }

            private static int side(@NotNull JsonTree obj, @NotNull String key) {
                return obj.getInt(key, 0);
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
