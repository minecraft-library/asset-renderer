package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.rule.CitRule;
import lib.minecraft.renderer.asset.pack.rule.ColorProperties;
import lib.minecraft.renderer.asset.pack.rule.CtmRule;
import lib.minecraft.renderer.asset.pack.rule.RuleSet;
import lib.minecraft.renderer.pipeline.pack.PackSubtree;
import lib.minecraft.renderer.pipeline.pack.VanillaSourcePaths;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Scans one {@link ResourcePack} into its per-pack {@link RuleSet}, folding the pack's
 * OptiFine / MCPatcher CIT and CTM trees and its per-root {@code color.properties} into one payload.
 * PackCapability-gated: a pack without {@link PackCapability#OPTIFINE_RULES} returns
 * {@link RuleSet#empty(PackId)} without touching disk, so a vanilla-only stack scans to nothing and
 * the whole rule layer stays inert. Walks the pack's active roots (base first, overlays after) so an
 * overlay's rules win within the pack.
 */
@UtilityClass
public class RuleScanner {

    private static final @NotNull String ASSETS = "assets/minecraft/";
    private static final @NotNull String[] CIT_ROOTS = {"optifine/cit", "mcpatcher/cit"};
    private static final @NotNull String[] CTM_ROOTS = {"optifine/ctm", "mcpatcher/ctm"};
    private static final @NotNull String[] COLOR_FILES = {"mcpatcher/color.properties", "optifine/color.properties"};
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
            subtrees.add(PackSubtree.Subtree.assets(VanillaSourcePaths.MINECRAFT_NAMESPACE_DIR, citRoot, ".properties"));
            subtrees.add(PackSubtree.Subtree.assets(VanillaSourcePaths.MINECRAFT_NAMESPACE_DIR, citRoot, ".png"));
        }
        for (String ctmRoot : CTM_ROOTS)
            subtrees.add(PackSubtree.Subtree.assets(VanillaSourcePaths.MINECRAFT_NAMESPACE_DIR, ctmRoot, ".properties"));
        return subtrees.toArray(new PackSubtree.Subtree[0]);
    }

    /**
     * Scans a pack into its rule payload.
     *
     * @param pack the pack to scan
     * @return the pack's rules, or {@link RuleSet#empty(PackId)} when it carries no OptiFine tree
     */
    public static @NotNull RuleSet scan(@NotNull ResourcePack pack) {
        if (!pack.has(PackCapability.OPTIFINE_RULES)) return RuleSet.empty(pack.id());

        PackContainer container = pack.container();
        List<CitRule> citRules = new ArrayList<>();
        List<CtmRule> ctmRules = new ArrayList<>();
        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>();
        Optional<Boolean> useGlint = Optional.empty();

        for (PackSubtree.Entry entry : PackSubtree.walk(pack, RULE_SUBTREES))
            scanRuleFile(entry, pack.id(), citRules, ctmRules);

        // The colour and glint overrides are single named files rather than a subtree, so they stay a
        // point read across the pack's roots - the walk enumerates, and there is nothing to enumerate.
        for (PackRoot root : pack.roots()) {
            String base = root.prefix() + ASSETS;
            for (String colorFile : COLOR_FILES) container.bytes(base + colorFile).ifPresent(bytes ->
                colors.putAll(ColorProperties.parse(new String(bytes, StandardCharsets.ISO_8859_1), new ResourceId("minecraft", colorFile), pack.id()).overrides()));
            for (String glintFile : GLINT_FILES) {
                Optional<Boolean> value = readProperties(container, base + glintFile).flatMap(RuleScanner::readUseGlint);
                if (value.isPresent()) useGlint = value;
            }
        }

        ColorProperties merged = new ColorProperties(new ResourceId("minecraft", "color.properties"), pack.id(), Concurrent.adoptMap(colors).toUnmodifiable());
        return new RuleSet(pack.id(), Concurrent.adoptList(citRules).toUnmodifiable(), Concurrent.adoptList(ctmRules).toUnmodifiable(), merged, useGlint);
    }

    /**
     * Builds the merged view over a resolved stack - scans every pack and folds their rules into one
     * deterministically-ordered payload.
     *
     * <p>Merge order: CIT rules by weight DESC, then FILENAME (a platform-deterministic tie-break), then
     * higher-priority pack; CTM rules partitioned tile-target before block-target then the same key;
     * {@code color.properties} merged per-KEY with the highest-priority pack winning each key;
     * {@code useGlint} taken from the highest pack shipping it.
     *
     * @param stack the resolved pack stack
     * @return the merged rule set
     */
    public static @NotNull RuleSet mergeAll(@NotNull PackStack stack) {
        Map<PackId, Integer> priority = new HashMap<>();
        List<RuleSet> perPack = new ArrayList<>();
        int index = 0;
        for (ResourcePack pack : stack.ascending()) {
            priority.put(pack.id(), index++);
            perPack.add(scan(pack));
        }

        List<CitRule> cit = new ArrayList<>();
        for (RuleSet rules : perPack) cit.addAll(rules.citRules());
        cit.sort(citComparator(priority));

        List<CtmRule> tiles = new ArrayList<>();
        List<CtmRule> blocks = new ArrayList<>();
        for (RuleSet rules : perPack)
            for (CtmRule rule : rules.ctmRules()) (rule.isTileTarget() ? tiles : blocks).add(rule);
        Comparator<CtmRule> ctmComparator = ctmComparator(priority);
        tiles.sort(ctmComparator);
        blocks.sort(ctmComparator);
        List<CtmRule> ctm = new ArrayList<>(tiles);
        ctm.addAll(blocks);

        LinkedHashMap<String, Integer> colors = new LinkedHashMap<>();
        Optional<Boolean> useGlint = Optional.empty();
        for (RuleSet rules : perPack) {
            colors.putAll(rules.colors().overrides());
            if (rules.useGlint().isPresent()) useGlint = rules.useGlint();
        }

        ColorProperties mergedColors = new ColorProperties(
            new ResourceId("minecraft", "color.properties"), PackId.VANILLA, Concurrent.adoptMap(colors).toUnmodifiable());
        return new RuleSet(PackId.VANILLA,
            Concurrent.adoptList(cit).toUnmodifiable(), Concurrent.adoptList(ctm).toUnmodifiable(), mergedColors, useGlint);
    }

    /** Weight DESC, then filename ASC, then higher-priority pack, then full id - a total, deterministic order. */
    private static @NotNull Comparator<CitRule> citComparator(@NotNull Map<PackId, Integer> priority) {
        return Comparator.comparingInt(CitRule::weight).reversed()
            .thenComparing(CitRule::filename)
            .thenComparing(rule -> priority.getOrDefault(rule.pack(), 0), Comparator.<Integer>reverseOrder())
            .thenComparing(rule -> rule.id().name());
    }

    /** Weight DESC, then filename ASC, then higher-priority pack, then full id (partition is applied before this). */
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

    /** Synthesises a rule for an {@code optifine/cit/potion/<variant>/<effect>.png} shortcut texture. */
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

    private static @NotNull Optional<Properties> readProperties(@NotNull PackContainer container, @NotNull String path) {
        return container.bytes(path).flatMap(bytes -> {
            Properties props = new Properties();
            try {
                props.load(new ByteArrayInputStream(bytes));
                return Optional.of(props);
            } catch (IOException ex) {
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
