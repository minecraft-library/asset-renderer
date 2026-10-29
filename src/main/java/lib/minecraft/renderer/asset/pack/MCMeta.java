package lib.minecraft.renderer.asset.pack;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.JsonTree;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed {@code .mcmeta} document - either a pack root {@code pack.mcmeta} or a per-asset
 * {@code <file>.png.mcmeta} sidecar. One umbrella holds every render-relevant section as an optional:
 * {@link Pack} for the root, and the four sidecar sections ({@link Animation}, {@link TextureSampling},
 * {@link GuiScaling}, {@link Villager}) that any one sidecar may combine. The pack and villager sections
 * parse to {@link Optional#empty()} where the document has none; each of the animation, texture and
 * {@code gui} sections answers a {@link Possible} that is also empty where the document carries one the
 * client's codec refuses, since each of the client's reads parses only the sections it asks for.
 *
 * @param id the asset this document annotates; a pack root uses {@code <packId>:pack}
 * @param pack the {@code pack} section, present only for a {@code pack.mcmeta}
 * @param animation the {@code animation} flipbook section: present where the client loads it, empty where
 *     the document carries one the client's codec refuses, and absent where it carries none
 * @param texture the {@code texture} sampling section: present where the client loads it, empty where the
 *     document carries one the client's codec refuses, and absent where it carries none
 * @param gui the {@code gui} section: present where the client loads it, empty where the document
 *     carries one the client's codec refuses, and absent where it carries none
 * @param villager the {@code villager} hat-overlay section, when present
 */
public record MCMeta(
    @NotNull ResourceId id,
    @NotNull Optional<Pack> pack,
    @NotNull Possible<Animation> animation,
    @NotNull Possible<TextureSampling> texture,
    @NotNull Possible<GuiScaling> gui,
    @NotNull Optional<Villager> villager
) {

    /** The empty document, carrying no sections; the default when a pack or asset ships no mcmeta. */
    public static final @NotNull MCMeta EMPTY = new MCMeta(
        new ResourceId(ResourceId.DEFAULT_NAMESPACE, ""),
        Optional.empty(), Possible.absent(), Possible.absent(), Possible.absent(), Optional.empty());

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
     * A flipbook {@code animation} sidecar section, as the client's codec reads it. Its constructor refuses
     * what the codec refuses, so a section the client would not load cannot be built.
     *
     * @param frametime the ticks each frame lasts where it declares none, at least 1
     * @param interpolate whether adjacent frames blend
     * @param width the frame width in pixels, at least 1, where the section declares one
     * @param height the frame height in pixels, at least 1, where the section declares one
     * @param frames the playback sequence, where the section declares one - every strip frame in order
     *     where it does not
     */
    public record Animation(
        int frametime,
        boolean interpolate,
        @NotNull Optional<Integer> width,
        @NotNull Optional<Integer> height,
        @NotNull Optional<ConcurrentList<Frame>> frames
    ) {

        /**
         * Constructs a new {@code Animation}, refusing what the client's codec refuses.
         *
         * @throws IllegalArgumentException if the frametime, or a declared width or height, is below 1
         */
        public Animation {
            positive(frametime);
            width.ifPresent(MCMeta::positive);
            height.ifPresent(MCMeta::positive);
        }

    }

    /**
     * One entry of an {@link Animation}'s declared sequence.
     *
     * @param index the strip frame it draws, never negative
     * @param time the ticks it lasts, at least 1, where it declares them - the section's frametime where it
     *     does not
     */
    public record Frame(int index, @NotNull Optional<Integer> time) {

        /**
         * Constructs a new {@code Frame}, refusing what the client's codec refuses.
         *
         * @throws IllegalArgumentException if the index is below 0 or a declared time below 1
         */
        public Frame {
            nonNegative(index);
            time.ifPresent(MCMeta::positive);
        }

    }

    /**
     * A {@code texture} sidecar section: how the texture is sampled and its mip levels built, as the
     * client's codec reads it.
     *
     * @param blur whether the sampler blurs on magnification
     * @param clamp whether the sampler clamps at the texture's edges
     * @param mipmapStrategy how each mip level is built from the one above it
     * @param alphaCutoffBias the bias added to the alpha cutoff a mip level keeps coverage against
     */
    public record TextureSampling(boolean blur, boolean clamp, @NotNull MipmapStrategy mipmapStrategy, float alphaCutoffBias) {

        /** The mipmap strategies a texture section names, each spelled as its name in lower case. */
        public enum MipmapStrategy {
            AUTO, MEAN, CUTOUT, STRICT_CUTOUT, DARK_CUTOUT;

            /**
             * Looks up a strategy by the name a sidecar spells it in, exactly as the client's codec does.
             *
             * @param name the name the sidecar spells
             * @return the strategy, or empty for any other text
             */
            public static @NotNull Optional<MipmapStrategy> parse(@NotNull String name) {
                for (MipmapStrategy strategy : values())
                    if (strategy.name().toLowerCase(Locale.ROOT).equals(name)) return Optional.of(strategy);

                return Optional.empty();
            }
        }

    }

    /**
     * A GUI sprite's scaling, as the client's {@code gui.scaling} codec reads it: a stretch, a tile or a
     * nine-slice, each holding only what its mode reads. Each refuses in its constructor what the codec
     * refuses, so a scaling the client would not load cannot be built.
     */
    public sealed interface GuiScaling permits GuiScaling.Stretch, GuiScaling.Tile, GuiScaling.NineSlice {

        /**
         * The scaling of a section that declares none and of a sprite that carries no section - stretch,
         * as the client's default is.
         */
        @NotNull GuiScaling DEFAULT = new Stretch();

        /**
         * Answers the mode this scaling draws in, the one a sidecar names it by.
         *
         * @return the scaling mode
         */
        @NotNull Type type();

        /** The GUI sprite scaling modes, each spelled in a sidecar as its name in lower case. */
        enum Type {
            STRETCH, TILE, NINE_SLICE;

            /**
             * Looks up a scaling mode by the name a sidecar spells it in, exactly as the client's codec
             * does.
             *
             * @param name the name the sidecar spells
             * @return the mode, or empty for any other text
             */
            public static @NotNull Optional<Type> parse(@NotNull String name) {
                for (Type type : values())
                    if (type.name().toLowerCase(Locale.ROOT).equals(name)) return Optional.of(type);

                return Optional.empty();
            }
        }

        /**
         * A sprite stretched over the whole rect it is drawn into. It reads nothing beside its type, so a
         * size declared beside it is never read.
         */
        record Stretch() implements GuiScaling {

            /** {@inheritDoc} */
            @Override
            public @NotNull Type type() {
                return Type.STRETCH;
            }

        }

        /**
         * A sprite repeated across the rect from its top-left corner, cut at the size it declares.
         *
         * @param width the width the tile is cut at, in GUI pixels, at least 1
         * @param height the height the tile is cut at, in GUI pixels, at least 1
         */
        record Tile(int width, int height) implements GuiScaling {

            /**
             * Constructs a new {@code Tile}, refusing a size the client's codec refuses.
             *
             * @throws IllegalArgumentException if the width or the height is below 1
             */
            public Tile {
                positive(width);
                positive(height);
            }

            /** {@inheritDoc} */
            @Override
            public @NotNull Type type() {
                return Type.TILE;
            }

        }

        /**
         * A sprite cut into four corners, four edges and a centre at the size it declares, the corners
         * drawn as they are and the rest stretched or tiled over the rect. Its two opposing border sides
         * always leave a centre slice, as the client's validation requires.
         *
         * @param width the width the sprite is sliced at, in GUI pixels, at least 1
         * @param height the height the sprite is sliced at, in GUI pixels, at least 1
         * @param border the depth of each border, in GUI pixels
         * @param stretchInner whether the edges and the centre stretch rather than tile
         */
        record NineSlice(int width, int height, @NotNull Border border, boolean stretchInner) implements GuiScaling {

            /**
             * Constructs a new {@code NineSlice}, refusing what the client's codec refuses.
             *
             * @throws IllegalArgumentException if the width or the height is below 1, or the border's left
             *     and right sides, or its top and bottom, leave no centre slice between them
             */
            public NineSlice {
                positive(width);
                positive(height);
                if (border.left() + border.right() >= width) {
                    throw new IllegalArgumentException(String.format(
                        "Nine-sliced texture has no horizontal center slice: '%s' + '%s' >= '%s'",
                        border.left(), border.right(), width));
                }
                if (border.top() + border.bottom() >= height) {
                    throw new IllegalArgumentException(String.format(
                        "Nine-sliced texture has no vertical center slice: '%s' + '%s' >= '%s'",
                        border.top(), border.bottom(), height));
                }
            }

            /** {@inheritDoc} */
            @Override
            public @NotNull Type type() {
                return Type.NINE_SLICE;
            }

            /**
             * A nine-slice's border depths, one a side; the bare-integer form sets all four sides alike.
             *
             * @param left the left border's depth, in GUI pixels, never negative
             * @param top the top border's depth, in GUI pixels, never negative
             * @param right the right border's depth, in GUI pixels, never negative
             * @param bottom the bottom border's depth, in GUI pixels, never negative
             */
            public record Border(int left, int top, int right, int bottom) {

                /**
                 * Constructs a new {@code Border}, refusing a side the client's codec refuses.
                 *
                 * @throws IllegalArgumentException if a side is below 0
                 */
                public Border {
                    nonNegative(left);
                    nonNegative(top);
                    nonNegative(right);
                    nonNegative(bottom);
                }

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

    /**
     * Refuses a value below 1, as the client's {@code POSITIVE_INT} codec does.
     *
     * @param value the value read
     * @throws IllegalArgumentException if the value is below 1
     */
    private static void positive(int value) {
        if (value < 1)
            throw new IllegalArgumentException(String.format("Value must be positive: '%s'", value));
    }

    /**
     * Refuses a value below 0, as the client's {@code NON_NEGATIVE_INT} codec does.
     *
     * @param value the value read
     * @throws IllegalArgumentException if the value is below 0
     */
    private static void nonNegative(int value) {
        if (value < 0)
            throw new IllegalArgumentException(String.format("Value must be non-negative: '%s'", value));
    }

}
