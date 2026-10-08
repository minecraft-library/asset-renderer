package lib.minecraft.renderer.content.rule;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackFiles;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.ColorProperties;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.asset.rule.RuleSet;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.VanillaPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Scans one {@link ResourcePack} into its per-pack {@link RuleSet}, folding the pack's
 * OptiFine / MCPatcher CIT and CTM trees and its {@code optifine/color.properties} into one payload.
 * PackCapability-gated: a pack without {@link PackCapability#OPTIFINE_RULES} returns
 * {@link RuleSet#empty(PackId)} without touching disk, so a vanilla-only stack scans to nothing and
 * the whole rule layer stays inert. Walks the pack's active roots (base first, overlays after) so an
 * overlay's rules win within the pack.
 */
@Parity(claim = "pack-rule-layer")
@UtilityClass
@Parity(claim = "pack-resolution")
public class RuleScanner {

    private static final @NotNull String ASSETS = "assets/minecraft/";
    private static final @NotNull String[] CIT_ROOTS = {"optifine/cit", "mcpatcher/cit"};
    private static final @NotNull String[] CTM_ROOTS = {"optifine/ctm", "mcpatcher/ctm"};
    private static final @NotNull String COLOR_FILE = "optifine/color.properties";
    private static final @NotNull String[] GLINT_FILES = {"mcpatcher/cit.properties", "optifine/cit.properties"};
    private static final @NotNull String POTION_DIR = "/potion/";

    /**
     * The six rule subtrees, at the fixed {@code minecraft} namespace the OptiFine convention always
     * uses. A CIT root needs two of them because it holds two kinds of file - the {@code .properties}
     * rules, and the {@code potion/} shortcut textures a rule is synthesised from - and a subtree
     * matches one extension.
     */
    private static final @NotNull PackSubtree.Subtree[] RULE_SUBTREES = ruleSubtrees();

    /**
     * Builds {@link #RULE_SUBTREES} in the order the hand-rolled scan visited them: each CIT root's
     * rules then its potion textures, then the CTM roots.
     *
     * @return the subtrees to walk per pack
     */
    private static @NotNull PackSubtree.Subtree[] ruleSubtrees() {
        List<PackSubtree.Subtree> subtrees = new ArrayList<>();
        for (String citRoot : CIT_ROOTS) {
            subtrees.add(PackSubtree.Subtree.assets(VanillaPaths.MINECRAFT_NAMESPACE_DIR, citRoot, ".properties"));
            subtrees.add(PackSubtree.Subtree.assets(VanillaPaths.MINECRAFT_NAMESPACE_DIR, citRoot, ".png"));
        }
        for (String ctmRoot : CTM_ROOTS)
            subtrees.add(PackSubtree.Subtree.assets(VanillaPaths.MINECRAFT_NAMESPACE_DIR, ctmRoot, ".properties"));
        return subtrees.toArray(new PackSubtree.Subtree[0]);
    }

    /**
     * Scans a pack into its rule payload.
     * <p>
     * The pack's colour overrides are the one {@code optifine/color.properties} the game's resource
     * lookup answers for it: the copy in the last of its roots that ships one, so a later overlay wins
     * over an earlier one and any overlay over the base, read whole with nothing taken from the copies
     * it hides. A copy whose body cannot be loaded leaves the pack shipping none, so the packs below it
     * are read.
     *
     * @param pack the pack to scan
     * @return the pack's rules, or {@link RuleSet#empty(PackId)} when it carries no OptiFine tree
     */
    public static @NotNull RuleSet scan(@NotNull ResourcePack pack) {
        if (!pack.has(PackCapability.OPTIFINE_RULES)) return RuleSet.empty(pack.id());

        PackFiles container = pack.container();
        List<CitRule> citRules = new ArrayList<>();
        List<CtmRule> ctmRules = new ArrayList<>();
        Optional<String> colorPath = Optional.empty();
        Optional<Boolean> useGlint = Optional.empty();

        for (PackSubtree.Entry entry : PackSubtree.walk(pack, RULE_SUBTREES))
            scanRuleFile(entry, pack.id(), citRules, ctmRules);

        // The colour and glint overrides are single named files rather than a subtree, so they stay a
        // point read across the pack's roots - the walk enumerates, and there is nothing to enumerate.
        for (PackRoot root : pack.roots()) {
            String base = root.prefix() + ASSETS;
            if (container.exists(base + COLOR_FILE)) colorPath = Optional.of(base + COLOR_FILE);
            for (String glintFile : GLINT_FILES) {
                Optional<Boolean> value = readProperties(container, base + glintFile).flatMap(RuleScanner::readUseGlint);
                if (value.isPresent()) useGlint = value;
            }
        }

        Optional<ColorProperties> colors = colorPath.flatMap(container::bytes).flatMap(bytes ->
            ColorPropertiesParser.parse(new String(bytes, StandardCharsets.ISO_8859_1), new ResourceId("minecraft", COLOR_FILE), pack.id()));
        return new RuleSet(pack.id(), Concurrent.adoptList(citRules).toUnmodifiable(), Concurrent.adoptList(ctmRules).toUnmodifiable(), colors, useGlint);
    }

    /**
     * Builds the merged view over a pack stack - scans every pack and folds their rules into one
     * deterministically-ordered payload.
     *
     * <p>Merge order: CIT rules by weight DESC, then FILENAME (a platform-deterministic tie-break), then
     * higher-priority pack; CTM rules partitioned tile-target before block-target then the same key;
     * {@code color.properties} taken whole from the highest-priority pack that ships one, as the game
     * reads it - no key of a lower pack's file shows through, even where the file read holds no usable
     * key - and carried under the nominal {@code color.properties} id and {@link PackId#VANILLA} the
     * merged view names; {@code useGlint} taken from the highest pack shipping it.
     *
     * @param ascending the stack's packs, vanilla first and the highest-priority pack last
     * @return the merged rule set
     */
    public static @NotNull RuleSet mergeAll(@NotNull List<ResourcePack> ascending) {
        Map<PackId, Integer> priority = new HashMap<>();
        List<RuleSet> perPack = new ArrayList<>();
        int index = 0;
        for (ResourcePack pack : ascending) {
            priority.put(pack.id(), index++);
            perPack.add(scan(pack));
        }

        ConcurrentList<CitRule> cit = perPack.stream()
            .flatMap(rules -> rules.citRules().stream())
            .sorted(citComparator(priority))
            .collect(Concurrent.toUnmodifiableList());

        // Tile targets sort and land before block targets, so the two partitions are sorted apart and
        // concatenated rather than sorted together under a partition-first comparator.
        Comparator<CtmRule> ctmComparator = ctmComparator(priority);
        ConcurrentList<CtmRule> ctm = Stream.concat(
            perPack.stream()
                .flatMap(rules -> rules.ctmRules().stream())
                .filter(CtmRule::isTileTarget)
                .sorted(ctmComparator),
            perPack.stream()
                .flatMap(rules -> rules.ctmRules().stream())
                .filter(rule -> !rule.isTileTarget())
                .sorted(ctmComparator))
            .collect(Concurrent.toUnmodifiableList());

        Optional<ColorProperties> colors = Optional.empty();
        Optional<Boolean> useGlint = Optional.empty();
        for (RuleSet rules : perPack) {
            if (rules.colors().isPresent()) colors = rules.colors();
            if (rules.useGlint().isPresent()) useGlint = rules.useGlint();
        }

        Optional<ColorProperties> mergedColors = colors.map(read ->
            new ColorProperties(new ResourceId("minecraft", "color.properties"), PackId.VANILLA, read.overrides()));
        return new RuleSet(PackId.VANILLA, cit, ctm, mergedColors, useGlint);
    }

    /**
     * Weight DESC, then filename ASC, then higher-priority pack, then full id - a total, deterministic order.
     */
    private static @NotNull Comparator<CitRule> citComparator(@NotNull Map<PackId, Integer> priority) {
        return Comparator.comparingInt(CitRule::weight).reversed()
            .thenComparing(CitRule::filename)
            .thenComparing(rule -> priority.getOrDefault(rule.pack(), 0), Comparator.<Integer>reverseOrder())
            .thenComparing(rule -> rule.id().name());
    }

    /**
     * Weight DESC, then filename ASC, then higher-priority pack, then full id (partition is applied before this).
     */
    private static @NotNull Comparator<CtmRule> ctmComparator(@NotNull Map<PackId, Integer> priority) {
        return Comparator.comparingInt(CtmRule::weight).reversed()
            .thenComparing(CtmRule::filename)
            .thenComparing(rule -> priority.getOrDefault(rule.pack(), 0), Comparator.<Integer>reverseOrder())
            .thenComparing(rule -> rule.id().name());
    }

    /**
     * Parses one walked rule file into its rule list. A {@code .properties} file under a CIT root is a
     * CIT rule and under a CTM root a CTM rule; a {@code .png} is only ever the {@code potion/}
     * shortcut a rule is synthesised from, and any other texture in the tree is ignored.
     *
     * @param entry the rule file the subtree walk resolved
     * @param pack the owning pack, carried onto each parsed rule
     * @param citRules the running CIT rule list
     * @param ctmRules the running CTM rule list
     */
    private static void scanRuleFile(
        @NotNull PackSubtree.Entry entry,
        @NotNull PackId pack,
        @NotNull List<CitRule> citRules,
        @NotNull List<CtmRule> ctmRules
    ) {
        String rel = entry.resourcePath();
        String ruleRoot = entry.subtree().subdir();

        if (rel.endsWith(".png")) {
            if (rel.contains(ruleRoot + POTION_DIR)) synthesisePotion(rel, pack).ifPresent(citRules::add);
            return;
        }

        readProperties(entry.container(), entry.entryPath()).ifPresent(props -> {
            if (ruleRoot.endsWith("/cit"))
                CitParser.parse(props, new ResourceId("minecraft", rel), pack, parentDir(rel), ruleRoot).ifPresent(citRules::add);
            else
                CtmParser.parse(props, new ResourceId("minecraft", rel), pack, parentDir(rel), basename(rel)).ifPresent(ctmRules::add);
        });
    }

    /**
     * Synthesises a rule for an {@code optifine/cit/potion/<variant>/<effect>.png} shortcut texture.
     */
    private static @NotNull Optional<CitRule> synthesisePotion(@NotNull String rel, @NotNull PackId pack) {
        int potionIndex = rel.indexOf(POTION_DIR);
        if (potionIndex < 0) return Optional.empty();
        String tail = rel.substring(potionIndex + POTION_DIR.length());
        String[] parts = tail.split("/");
        String variant = parts.length >= 2 ? parts[0] : "normal";
        String effect = basenamePng(parts[parts.length - 1]);
        ResourceId texture = new ResourceId("minecraft", rel.endsWith(".png") ? rel.substring(0, rel.length() - 4) : rel);
        return Optional.of(CitParser.synthesisePotion(texture, new ResourceId("minecraft", rel), pack, potionItem(variant), effect));
    }

    private static @NotNull ResourceId potionItem(@NotNull String variant) {
        return switch (variant) {
            case "splash" -> new ResourceId("minecraft", "splash_potion");
            case "linger", "lingering" -> new ResourceId("minecraft", "lingering_potion");
            default -> new ResourceId("minecraft", "potion");
        };
    }

    private static @NotNull Optional<Boolean> readUseGlint(@NotNull Properties props) {
        String value = props.getProperty("useGlint");
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(Boolean.parseBoolean(value.trim()));
    }

    /**
     * Reads a pack's {@code .properties} rule file, empty when the container lacks it or its body
     * cannot be read - including a Unicode escape whose four digits are not hex, which
     * {@link Properties#load(java.io.InputStream)} refuses outright. Such a file is skipped with one
     * log line, so one bad rule file never fails the context load.
     *
     * @param container the pack files the rule is read from
     * @param path the container-relative path of the rule file
     * @return the loaded properties, or empty when the file is absent or unreadable
     */
    private static @NotNull Optional<Properties> readProperties(@NotNull PackFiles container, @NotNull String path) {
        return container.bytes(path).flatMap(bytes -> {
            Properties props = new Properties();
            try {
                props.load(new ByteArrayInputStream(bytes));
                return Optional.of(props);
            } catch (IOException ex) {
                return Optional.empty();
            } catch (IllegalArgumentException ex) {
                System.err.printf("Pack '%s': skipping malformed rule file '%s': %s%n", container, path, ex.getMessage());
                return Optional.empty();
            }
        });
    }

    private static @NotNull String strip(@NotNull String entry, @NotNull String base) {
        return entry.startsWith(base) ? entry.substring(base.length()) : entry;
    }

    private static @NotNull String parentDir(@NotNull String rel) {
        int slash = rel.lastIndexOf('/');
        return slash >= 0 ? rel.substring(0, slash) : "";
    }

    private static @NotNull String basename(@NotNull String rel) {
        int slash = rel.lastIndexOf('/');
        String file = slash >= 0 ? rel.substring(slash + 1) : rel;
        return file.endsWith(".properties") ? file.substring(0, file.length() - ".properties".length()) : file;
    }

    private static @NotNull String basenamePng(@NotNull String file) {
        return file.endsWith(".png") ? file.substring(0, file.length() - 4) : file;
    }

}
