package lib.minecraft.renderer.content.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * The sprite ids the client's items atlas holds over a pack stack - what decides, when an item model
 * bakes, whether a texture it draws is an items-atlas sprite or a blocks-atlas one.
 * <p>
 * The client stitches the atlas from every pack's {@code minecraft:atlases/items.json}, their
 * {@code sources} lists concatenated lowest priority first, and runs each source in order over the
 * sprite ids gathered so far: {@code directory} adds every texture under its source directory, renamed
 * under its prefix; {@code single} adds one texture that exists, under its own id or the one it names;
 * {@code filter} removes every id its pattern matches, each half a search rather than a whole match;
 * {@code unstitch} adds the region sprites it cuts from a texture that exists; and
 * {@code paletted_permutations} adds one sprite per texture per permutation, the permutation's name
 * joined on by the separator. The missing sprite is in every atlas.
 * <p>
 * A model's texture is looked up in this atlas first and the blocks atlas second, and one in neither is
 * the missing sprite the blocks atlas holds - so every sprite id this set does not hold is a
 * blocks-atlas one as far as a bake is concerned.
 */
public final class ItemsAtlas {

    /** The id every atlas holds its missing sprite under. */
    private static final @NotNull String MISSING_SPRITE = "minecraft:missingno";

    /** The namespace and stem the items atlas's definition files sit at. */
    private static final @NotNull String ITEMS_ATLAS = "minecraft:items";

    /** Every atlas definition file of every pack, of which the items atlas's are kept. */
    private static final @NotNull PackSubtree.Subtree ATLASES = PackSubtree.Subtree.of(VanillaPaths.ATLASES_SUBDIR, ".json");

    /** The sprite ids the atlas holds, as {@code <namespace>:<path>}. */
    private final @NotNull Set<String> sprites;

    private ItemsAtlas(@NotNull Set<String> sprites) {
        this.sprites = Set.copyOf(sprites);
    }

    /**
     * Stitches the items atlas's sprite ids over a stack whose textures are indexed.
     *
     * @param stack the resolved, texture-indexed pack stack
     * @return the atlas
     */
    public static @NotNull ItemsAtlas load(@NotNull PackStack stack) {
        Set<String> textures = stack.textureIndex().keySet().stream().map(ResourceId::id).collect(Collectors.toSet());
        Set<String> sprites = new HashSet<>();
        sprites.add(MISSING_SPRITE);

        for (PackSubtree.Entry entry : PackSubtree.walk(stack.ascending(), ATLASES)) {
            if (!(entry.namespace() + ":" + entry.stem()).equals(ITEMS_ATLAS)) continue;
            for (JsonElement source : sources(entry))
                if (source.isJsonObject()) run(source.getAsJsonObject(), textures, sprites);
        }
        return new ItemsAtlas(sprites);
    }

    /**
     * Whether the atlas holds a sprite.
     *
     * @param spriteId the sprite id, namespaced or bare
     * @return whether a model drawing it draws an items-atlas sprite
     */
    public boolean contains(@NotNull String spriteId) {
        return this.sprites.contains(ResourceId.parse(spriteId).id());
    }

    /** The {@code sources} one atlas file lists, none where it is not a JSON object carrying them. */
    private static @NotNull Collection<JsonElement> sources(@NotNull PackSubtree.Entry entry) {
        try {
            JsonElement root = JsonParser.parseString(new String(entry.container().bytes(entry.entryPath()).orElseThrow(), StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("sources") && root.getAsJsonObject().get("sources").isJsonArray())
                return root.getAsJsonObject().getAsJsonArray("sources").asList();
        } catch (JsonParseException ex) {
            System.err.printf("Skipping malformed atlas '%s' from pack '%s': %s%n", entry.entryPath(), entry.pack().id(), ex.getMessage());
        }
        return List.of();
    }

    /** Runs one source over the sprite ids gathered so far. */
    private static void run(@NotNull JsonObject source, @NotNull Set<String> textures, @NotNull Set<String> sprites) {
        switch (ResourceId.vanillaPath(string(source, "type")).orElse("")) {
            case "directory" -> {
                String directory = string(source, "source");
                String prefix = string(source, "prefix");
                String under = directory.isEmpty() ? "" : directory + "/";
                for (String texture : textures) {
                    ResourceId id = ResourceId.parse(texture);
                    if (id.name().startsWith(under))
                        sprites.add(new ResourceId(id.namespace(), prefix + id.name().substring(under.length())).id());
                }
            }
            case "single" -> {
                String resource = ResourceId.parse(string(source, "resource")).id();
                if (textures.contains(resource))
                    sprites.add(source.has("sprite") ? ResourceId.parse(string(source, "sprite")).id() : resource);
            }
            case "filter" -> {
                JsonObject pattern = source.has("pattern") && source.get("pattern").isJsonObject() ? source.getAsJsonObject("pattern") : new JsonObject();
                Optional<Pattern> namespace;
                Optional<Pattern> path;
                try {
                    namespace = compile(pattern, "namespace");
                    path = compile(pattern, "path");
                } catch (PatternSyntaxException ex) {
                    // The client refuses a filter it cannot compile, so it removes nothing.
                    return;
                }
                sprites.removeIf(sprite -> {
                    ResourceId id = ResourceId.parse(sprite);
                    return namespace.map(p -> p.matcher(id.namespace()).find()).orElse(true)
                        && path.map(p -> p.matcher(id.name()).find()).orElse(true);
                });
            }
            case "unstitch" -> {
                if (!textures.contains(ResourceId.parse(string(source, "resource")).id()) || !source.has("regions")) return;
                for (JsonElement region : source.getAsJsonArray("regions"))
                    if (region.isJsonObject()) sprites.add(ResourceId.parse(string(region.getAsJsonObject(), "sprite")).id());
            }
            case "paletted_permutations" -> {
                if (!source.has("textures") || !source.has("permutations")) return;
                String separator = source.has("separator") ? string(source, "separator") : "_";
                for (JsonElement texture : source.getAsJsonArray("textures"))
                    for (String permutation : source.getAsJsonObject("permutations").keySet())
                        sprites.add(ResourceId.parse(texture.getAsString() + separator + permutation).id());
            }
            default -> { }
        }
    }

    /**
     * Compiles one half of a filter pattern, empty where it is absent, which matches every id.
     *
     * @throws PatternSyntaxException if the half is no valid regex
     */
    private static @NotNull Optional<Pattern> compile(@NotNull JsonObject pattern, @NotNull String key) {
        return pattern.has(key) ? Optional.of(Pattern.compile(string(pattern, key))) : Optional.empty();
    }

    /** A string member, {@code ""} where it is absent or not a primitive. */
    private static @NotNull String string(@NotNull JsonObject node, @NotNull String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

}
