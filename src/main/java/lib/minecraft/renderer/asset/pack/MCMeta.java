package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed {@code .mcmeta} document - either a pack root {@code pack.mcmeta} or a per-asset
 * {@code <file>.png.mcmeta} sidecar. One umbrella holds every render-relevant section as an optional:
 * {@link Pack} for the root, and the four sidecar sections ({@link Animation}, {@link TextureFlags},
 * {@link GuiScaling}, {@link Villager}) that any one sidecar may combine. Sections absent from the
 * document parse to {@link Optional#empty()}.
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

    /** The empty document, carrying no sections; the default when a pack or asset ships no mcmeta. */
    public static final @NotNull MCMeta EMPTY = new MCMeta(
        new ResourceId(ResourceId.DEFAULT_NAMESPACE, ""),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

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
         * Whether this pack's {@code filter.block} section hides a file in a lower pack - the shared
         * predicate every pack-stack subtree walk consults before merging its own entries. The
         * vanilla synthesised mcmeta declares no filters, so a vanilla-only stack hides nothing.
         * <p>
         * The two halves are asked <b>separately</b>, each across the whole list, because that is
         * what the client does: {@code ResourceFilterSection} exposes {@code isNamespaceFiltered} and
         * {@code isPathFiltered} as two independent {@code anyMatch} passes, each testing only its
         * own half of each entry, and an absent pattern compiles to a constant-true predicate. A
         * per-entry conjunction exists in the client ({@code IdentifierPattern.locationPredicate})
         * but pack filtering never calls it - its only reader is the {@code minecraft:filter} atlas
         * sprite source. The difference is not academic: a section whose entries are
         * {@code [{"namespace": "x"}, {"path": "y"}]} has a path-less entry, so the path half is
         * constant-true and <b>every</b> file below is hidden.
         *
         * @param namespace the namespace the file lives in
         * @param resourcePath the file's path relative to {@code assets/<namespace>/}, subdirectory
         *     and extension included ({@code blockstates/stone.json})
         * @return {@code true} when this pack's filters hide that file
         */
        public boolean hidesFile(@NotNull String namespace, @NotNull String resourcePath) {
            return hidesNamespace(namespace) && hidesPath(resourcePath);
        }

        /**
         * Whether any filter's namespace half matches - the client's {@code isNamespaceFiltered},
         * which decides whether the filter is installed for a namespace at all rather than which
         * files it takes.
         *
         * @param namespace the namespace to test
         * @return {@code true} when a filter covers that namespace
         */
        public boolean hidesNamespace(@NotNull String namespace) {
            return this.filters.stream().anyMatch(filter -> filter.matchesNamespace(namespace));
        }

        /**
         * Whether any filter's path half matches - the client's {@code isPathFiltered}, which selects
         * files once the namespace is covered.
         *
         * @param resourcePath the file's path relative to {@code assets/<namespace>/}
         * @return {@code true} when a filter takes that path
         */
        public boolean hidesPath(@NotNull String resourcePath) {
            return this.filters.stream().anyMatch(filter -> filter.matchesPath(resourcePath));
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
         * Whether this entry's namespace half matches. An entry declaring no namespace pattern
         * matches every namespace - the client compiles an absent pattern to a constant-true
         * predicate rather than to a no-match.
         *
         * @param namespace the namespace to test
         * @return {@code true} when this entry's namespace half matches
         */
        public boolean matchesNamespace(@NotNull String namespace) {
            return this.namespace.map(pattern -> pattern.matcher(namespace).find()).orElse(true);
        }

        /**
         * Whether this entry's path half matches. An entry declaring no path pattern matches every
         * path, which is what makes a namespace-only entry hide a whole namespace.
         * <p>
         * Matching is unanchored substring ({@link java.util.regex.Matcher#find}), mirroring the
         * client's {@code IdentifierPattern}, which builds its predicates through
         * {@link java.util.regex.Pattern#asPredicate} (defined as {@code s -> matcher(s).find()}).
         * An anchored full match would hide fewer resources than vanilla for the same pattern.
         * <p>
         * The string tested is the file's path relative to {@code assets/<namespace>/}, subdirectory
         * and extension included, because that is what the client tests: its filter predicate is
         * {@code id -> section.isPathFiltered(id.getPath())} and the identifier it receives is built
         * by relativising the file against the namespace root, so a blockstate is
         * {@code blockstates/stone.json} rather than {@code stone}.
         *
         * @param resourcePath the file path relative to the namespace root
         * @return {@code true} when this entry's path half matches
         */
        public boolean matchesPath(@NotNull String resourcePath) {
            return this.path.map(pattern -> pattern.matcher(resourcePath).find()).orElse(true);
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
            if (element.isPrimitive()) return element.asString().orElseThrow();
            if (element.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonTree child : element.elements().toList()) sb.append(flatten(child));
                return sb.toString();
            }
            if (element.isObject()) {
                StringBuilder sb = new StringBuilder();
                if (element.has("text") && element.find("text").orElse(null).isPrimitive()) sb.append(element.findString("text").orElse(null));
                Optional<JsonTree> extra = element.findArray("extra");
                if (extra.isPresent())
                    for (JsonTree child : extra.get().elements().toList()) sb.append(flatten(child));
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
                if (value.asInt().isPresent()) {
                    int all = value.asInt().get();
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
