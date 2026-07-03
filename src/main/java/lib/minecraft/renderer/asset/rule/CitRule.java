package lib.minecraft.renderer.asset.rule;

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
 * @param source the source file path, retained for debug and diagnostics only
 * @param weight the rule weight for sort priority; higher values win
 * @param matchedItems the item ids the rule applies to; the context item must be one of them
 * @param damageRange the optional damage-value filter; absent means any damage matches
 * @param stackSizeRange the optional stack-size filter; absent means any stack size matches
 * @param nbtConditions dot-separated NBT paths to conditions, all of which must hold
 * @param enchantmentIds enchantment ids that must all be present, level unconstrained
 * @param enchantmentLevels enchantment id to required level range; the enchantment must be
 *     present and its level within range
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
