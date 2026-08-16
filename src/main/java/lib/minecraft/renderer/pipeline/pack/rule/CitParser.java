package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.rule.CitOutput;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.CitType;
import lib.minecraft.renderer.asset.pack.rule.DamageSpec;
import lib.minecraft.renderer.asset.pack.rule.EnchantmentSpec;
import lib.minecraft.renderer.asset.pack.rule.Hand;
import lib.minecraft.renderer.asset.pack.rule.IntRanges;
import lib.minecraft.renderer.asset.pack.rule.NbtPath;
import lib.minecraft.renderer.asset.pack.rule.NbtPredicate;
import lib.minecraft.renderer.asset.pack.rule.NbtRule;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Parses one OptiFine / MCPatcher CIT {@code .properties} file into a {@link CitRule}. Fail-closed:
 * any condition that fails to parse throws a {@link RuleRejection} which rejects the whole rule with
 * a diagnostic, so a swallowed number format can never delete a filter and over-match. Resolves all
 * four texture / model path forms (the filename default, bare names, {@code ./}-relative, and
 * {@code assets/minecraft/} relative) at parse so the matcher never re-derives paths.
 */
@Parity(claim = "pack-rule-layer")
@UtilityClass
public class CitParser {

    /** The minecraft namespace every unqualified id defaults to. */
    private static final @NotNull String MINECRAFT = "minecraft";

    /**
     * Parses one CIT file, returning empty (with a diagnostic) when any condition is unparseable or
     * the rule produces no usable output.
     *
     * @param props the loaded properties
     * @param ruleId the pack-relative {@code .properties} id
     * @param pack the owning pack
     * @param propsDir the file's directory, relative to {@code assets/minecraft/}
     * @param citRoot the CIT root the file was found under ({@code optifine/cit} or {@code mcpatcher/cit})
     * @return the parsed rule, or empty when rejected
     */
    public static @NotNull Optional<CitRule> parse(
        @NotNull Properties props, @NotNull ResourceId ruleId, @NotNull PackId pack,
        @NotNull String propsDir, @NotNull String citRoot
    ) {
        try {
            return Optional.of(parseOrThrow(props, ruleId, pack, propsDir, citRoot));
        } catch (RuleRejection rejection) {
            RuleDiagnostics.reject(pack, ruleId, rejection);
            return Optional.empty();
        }
    }

    private static @NotNull CitRule parseOrThrow(
        @NotNull Properties props, @NotNull ResourceId ruleId, @NotNull PackId pack,
        @NotNull String propsDir, @NotNull String citRoot
    ) {
        CitType type = CitType.parse(props.getProperty("type"));
        // A concrete-retexture subject (item / armor / elytra) must name its items and produce an output;
        // only type=enchantment legitimately carries neither - it feeds the glint policy, not a texture.
        boolean retextures = type == CitType.ITEM || type == CitType.ARMOR || type == CitType.ELYTRA;

        List<ResourceId> items = parseIds(props.getProperty("items", props.getProperty("matchItems", "")));
        if (retextures && items.isEmpty())
            throw new RuleRejection("items", "", "rule matches no items");

        int weight = parseWeight(props.getProperty("weight"));
        Optional<DamageSpec> damage = parseDamage(props);
        Optional<IntRanges> stackSize = parseRanges(props, "stackSize");
        Optional<EnchantmentSpec> enchantments = parseEnchantments(props);
        Hand hand = Hand.parse(props.getProperty("hand"));
        ConcurrentList<NbtRule> nbtRules = parseNbtRules(props);
        CitOutput output = parseOutput(props, propsDir, citRoot, basename(ruleId));

        if (retextures && output.isEmpty())
            throw new RuleRejection("texture", "", "rule produces no texture or model output");

        return new CitRule(ruleId, pack, type, Concurrent.adoptList(items).toUnmodifiable(),
            damage, stackSize, enchantments, hand, nbtRules, output, weight);
    }

    /**
     * Synthesises a CIT rule for an {@code optifine/cit/potion/} filename-convention texture -
     * one rule per discovered PNG, matching the potion item whose {@code potion_contents} names the
     * effect, so the single matcher path covers the shortcut.
     *
     * @param texture the resolved potion-overlay texture id
     * @param ruleId the synthetic rule id (the PNG's pack-relative path)
     * @param pack the owning pack
     * @param potionItem the potion item id the variant maps to
     * @param effect the effect id from the filename
     * @return the synthesised rule
     */
    public static @NotNull CitRule synthesisePotion(
        @NotNull ResourceId texture, @NotNull ResourceId ruleId, @NotNull PackId pack,
        @NotNull ResourceId potionItem, @NotNull String effect
    ) {
        NbtPath path = new NbtPath(Concurrent.adoptList(new ArrayList<NbtPath.Step>(List.of(
            new NbtPath.Key("components"), new NbtPath.Key("minecraft:potion_contents"), new NbtPath.Key("potion")))).toUnmodifiable());
        NbtRule nbt = new NbtRule(path, NbtPredicate.exact(MINECRAFT + ":" + effect), false);
        CitOutput output = new CitOutput(Optional.of(texture), Concurrent.newMap(), Optional.empty(), Concurrent.newMap());
        return new CitRule(ruleId, pack, CitType.ITEM, Concurrent.newList(potionItem),
            Optional.empty(), Optional.empty(), Optional.empty(), Hand.ANY, Concurrent.newList(nbt), output, 0);
    }

    // --- conditions ---------------------------------------------------------------------------

    private static @NotNull List<ResourceId> parseIds(String value) {
        List<ResourceId> ids = new ArrayList<>();
        if (value == null) return ids;
        for (String token : value.trim().split("\\s+"))
            if (!token.isBlank()) ids.add(token.contains(":") ? ResourceId.parse(token) : new ResourceId(MINECRAFT, token));
        return ids;
    }

    private static int parseWeight(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new RuleRejection("weight", raw, "not an integer");
        }
    }

    private static @NotNull Optional<DamageSpec> parseDamage(@NotNull Properties props) {
        String raw = props.getProperty("damage");
        if (raw == null || raw.isBlank()) return Optional.empty();
        boolean percent = raw.contains("%");
        IntRanges ranges;
        try {
            ranges = IntRanges.parse(raw.replace("%", ""));
        } catch (NumberFormatException ex) {
            throw new RuleRejection("damage", raw, "invalid damage range");
        }
        int mask = 0;
        String maskRaw = props.getProperty("damageMask");
        if (maskRaw != null && !maskRaw.isBlank()) {
            try {
                mask = Integer.decode(maskRaw.trim());
            } catch (NumberFormatException ex) {
                throw new RuleRejection("damageMask", maskRaw, "invalid mask");
            }
        }
        return Optional.of(new DamageSpec(ranges, percent, mask));
    }

    private static @NotNull Optional<IntRanges> parseRanges(@NotNull Properties props, @NotNull String key) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(IntRanges.parse(raw));
        } catch (NumberFormatException ex) {
            throw new RuleRejection(key, raw, "invalid range");
        }
    }

    private static @NotNull Optional<EnchantmentSpec> parseEnchantments(@NotNull Properties props) {
        String modern = props.getProperty("enchantments");
        String legacy = props.getProperty("enchantmentIDs");
        String ids = modern != null ? modern : legacy;
        String levelsRaw = props.getProperty("enchantmentLevels");
        boolean hasIds = ids != null && !ids.isBlank();
        boolean hasLevels = levelsRaw != null && !levelsRaw.isBlank();
        boolean idKeyPresent = modern != null || legacy != null;

        if (!hasIds && !hasLevels && !idKeyPresent) return Optional.empty();

        List<ResourceId> idList = hasIds ? parseIds(ids) : new ArrayList<>();
        Optional<IntRanges> levels = Optional.empty();
        if (hasLevels) {
            try {
                levels = Optional.of(IntRanges.parse(levelsRaw));
            } catch (NumberFormatException ex) {
                throw new RuleRejection("enchantmentLevels", levelsRaw, "invalid level range");
            }
        }
        return Optional.of(new EnchantmentSpec(Concurrent.adoptList(idList).toUnmodifiable(), levels));
    }

    private static @NotNull ConcurrentList<NbtRule> parseNbtRules(@NotNull Properties props) {
        List<NbtRule> rules = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("nbt.")) rules.add(buildNbtRule(nbtPath(key.substring("nbt.".length())), props.getProperty(key)));
            else if (key.startsWith("components.")) rules.add(buildNbtRule(componentsPath(key.substring("components.".length())), props.getProperty(key)));
        }
        return Concurrent.adoptList(rules).toUnmodifiable();
    }

    /** Builds the path for a legacy {@code nbt.<path>} key, rewriting {@code nbt.display.Name} to the modern component path. */
    private static @NotNull NbtPath nbtPath(@NotNull String pathPart) {
        if (pathPart.equals("display.Name")) return componentsPath("minecraft:custom_name");
        return NbtPath.parse(pathPart);
    }

    /** Builds a two-step {@code components.<name>} path, applying the {@code ~} to {@code minecraft:} shortcut. */
    private static @NotNull NbtPath componentsPath(@NotNull String name) {
        String resolved = name.startsWith("~") ? MINECRAFT + ":" + name.substring(1) : name;
        List<NbtPath.Step> steps = new ArrayList<>(List.of(new NbtPath.Key("components"), new NbtPath.Key(resolved)));
        return new NbtPath(Concurrent.adoptList(steps).toUnmodifiable());
    }

    private static @NotNull NbtRule buildNbtRule(@NotNull NbtPath path, @NotNull String value) {
        boolean negated = value.startsWith("!");
        String body = negated ? value.substring(1) : value;
        return new NbtRule(path, parsePredicate(body), negated);
    }

    private static @NotNull NbtPredicate parsePredicate(@NotNull String value) {
        if (value.startsWith("pattern:")) return NbtPredicate.glob(value.substring("pattern:".length()), false);
        if (value.startsWith("ipattern:")) return NbtPredicate.glob(value.substring("ipattern:".length()), true);
        if (value.startsWith("regex:")) return NbtPredicate.regex(value.substring("regex:".length()), false);
        if (value.startsWith("iregex:")) return NbtPredicate.regex(value.substring("iregex:".length()), true);
        if (value.startsWith("range:")) {
            try {
                return NbtPredicate.range(IntRanges.parse(value.substring("range:".length())));
            } catch (NumberFormatException ex) {
                throw new RuleRejection("nbt", value, "invalid range predicate");
            }
        }
        if (value.startsWith("exists:")) return NbtPredicate.exists(value.substring("exists:".length()).trim().equalsIgnoreCase("true"));
        if (value.startsWith("raw:")) return NbtPredicate.raw(parsePredicate(value.substring("raw:".length())));
        return NbtPredicate.exact(value);
    }

    // --- output -------------------------------------------------------------------------------

    private static @NotNull CitOutput parseOutput(@NotNull Properties props, @NotNull String propsDir, @NotNull String citRoot, @NotNull String basename) {
        String texRaw = props.getProperty("texture");
        String modelRaw = props.getProperty("model");
        ConcurrentMap<String, ResourceId> subTextures = parseSubEntries(props, "texture.", propsDir, citRoot, true);
        ConcurrentMap<String, ResourceId> subModels = parseSubEntries(props, "model.", propsDir, citRoot, false);
        Optional<ResourceId> model = modelRaw != null && !modelRaw.isBlank()
            ? Optional.of(resolveModelPath(modelRaw, propsDir, citRoot))
            : Optional.empty();

        Optional<ResourceId> texture;
        if (texRaw != null && !texRaw.isBlank()) texture = Optional.of(resolveTexturePath(texRaw, propsDir, citRoot, basename));
        else if (model.isPresent() || !subTextures.isEmpty() || !subModels.isEmpty()) texture = Optional.empty();
        else texture = Optional.of(resolveTexturePath(null, propsDir, citRoot, basename));

        return new CitOutput(texture, subTextures, model, subModels);
    }

    private static @NotNull ConcurrentMap<String, ResourceId> parseSubEntries(
        @NotNull Properties props, @NotNull String prefix, @NotNull String propsDir, @NotNull String citRoot, boolean texture
    ) {
        ConcurrentMap<String, ResourceId> entries = Concurrent.newMap();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String name = key.substring(prefix.length());
            String value = props.getProperty(key);
            entries.put(name, texture ? resolveTexturePath(value, propsDir, citRoot, name) : resolveModelPath(value, propsDir, citRoot));
        }
        return entries.toUnmodifiable();
    }

    /**
     * Resolves a CIT {@code texture} value across the four OptiFine forms. Package
     * private so the four-form fixture pins each branch directly.
     *
     * @param written the raw texture value, or {@code null} for the filename-default form
     * @param propsDir the file's directory, relative to {@code assets/minecraft/}
     * @param citRoot the CIT root ({@code optifine/cit} / {@code mcpatcher/cit})
     * @param basename the {@code .properties} basename, for the filename default
     * @return the resolved texture id
     */
    static @NotNull ResourceId resolveTexturePath(String written, @NotNull String propsDir, @NotNull String citRoot, @NotNull String basename) {
        if (written == null || written.isBlank()) return toTextureId(join(propsDir, basename));
        String w = stripSuffix(written.trim(), ".png");
        if (w.contains(":")) return ResourceId.parse(w);
        String path;
        if (w.startsWith("./")) path = join(propsDir, w.substring(2));
        else if (w.contains("/")) path = w;
        else path = join(citRoot, w);
        return toTextureId(path);
    }

    /** Resolves a CIT {@code model} value across the same forms as {@link #resolveTexturePath}, minus the filename default. */
    private static @NotNull ResourceId resolveModelPath(String written, @NotNull String propsDir, @NotNull String citRoot) {
        String w = stripSuffix((written == null ? "" : written).trim(), ".json");
        if (w.contains(":")) return ResourceId.parse(w);
        String path;
        if (w.startsWith("./")) path = join(propsDir, w.substring(2));
        else if (w.contains("/")) path = w;
        else path = join(citRoot, w);
        return toModelId(path);
    }

    /** Maps an {@code assets/minecraft/}-relative path to a texture id, folding a {@code textures/} prefix into the id scheme. */
    private static @NotNull ResourceId toTextureId(@NotNull String path) {
        if (path.startsWith("textures/")) return new ResourceId(MINECRAFT, path.substring("textures/".length()));
        return new ResourceId(MINECRAFT, path);
    }

    /** Maps an {@code assets/minecraft/}-relative path to a model id, folding a {@code models/} prefix into the id scheme. */
    private static @NotNull ResourceId toModelId(@NotNull String path) {
        if (path.startsWith("models/")) return new ResourceId(MINECRAFT, path.substring("models/".length()));
        return new ResourceId(MINECRAFT, path);
    }

    private static @NotNull String basename(@NotNull ResourceId ruleId) {
        String name = ruleId.name();
        int slash = name.lastIndexOf('/');
        String file = slash >= 0 ? name.substring(slash + 1) : name;
        return stripSuffix(file, ".properties");
    }

    private static @NotNull String join(@NotNull String dir, @NotNull String tail) {
        return dir.isEmpty() ? tail : dir + "/" + tail;
    }

    private static @NotNull String stripSuffix(@NotNull String value, @NotNull String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

}
