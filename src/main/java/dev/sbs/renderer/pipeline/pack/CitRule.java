package dev.sbs.renderer.pipeline.pack;

import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A parsed Custom Item Texture rule from an Optifine or MCPatcher {@code .properties} file.
 * <p>
 * The rule carries every condition needed to decide whether the item renderer should swap in a
 * custom texture for a particular render call, plus the output texture identifier and a sort
 * weight for priority resolution when multiple rules match.
 *
 * @param source the source file path (debug only)
 * @param weight the rule weight for sort priority; higher values win
 * @param matchedItems the list of item ids the rule applies to
 * @param damageRange the optional damage-value filter
 * @param stackSizeRange the optional stack-size filter
 * @param nbtConditions a map of dot-separated NBT paths to conditions that must match
 * @param enchantmentIds a list of enchantment ids that must all be present
 * @param enchantmentLevels a map of enchantment id to level range; both must match
 * @param outputTextureId the namespaced texture id to use when the rule matches
 */
public record CitRule(
    @NotNull String source,
    int weight,
    @NotNull ConcurrentList<String> matchedItems,
    @NotNull Optional<IntRange> damageRange,
    @NotNull Optional<IntRange> stackSizeRange,
    @NotNull ConcurrentMap<String, NbtCondition> nbtConditions,
    @NotNull ConcurrentList<String> enchantmentIds,
    @NotNull ConcurrentMap<String, IntRange> enchantmentLevels,
    @NotNull String outputTextureId
) {}
