package lib.minecraft.renderer.asset.rule;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.rule.filter.IntRanges;
import lib.minecraft.renderer.asset.rule.filter.NbtRule;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.ItemContext;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A parsed OptiFine / MCPatcher Custom Item Texture rule.
 *
 * <p>Every condition is ANDed: {@link ItemContext#matches(CitRule)} returns {@code true} only when the
 * item id, damage, stack size, enchantments, hand, and every {@link NbtRule} all hold. The parser is
 * fail-closed - an unparseable condition rejects the whole rule rather than silently dropping a filter,
 * so a rule that loads is a rule whose every condition is known.
 *
 * @param id the pack-relative {@code .properties} path - identity and the weight-tie sort key
 * @param pack the owning pack
 * @param type the retexture subject; only {@link CitType#ITEM} reaches icon resolution
 * @param items the item ids the rule accepts, {@code minecraft:}-defaulted
 * @param damage the optional damage filter
 * @param stackSize the optional stack-size filter
 * @param enchantments the optional enchantment filter
 * @param hand the hand constraint; GUI rendering counts as {@link Hand#MAIN}
 * @param nbtRules the {@code nbt.<path>} / {@code components.<name>} conditions, all of which must hold
 * @param output the texture / model replacements a match applies
 * @param weight the sort weight; higher wins, ties broken by filename then pack priority
 */
@Parity(claim = "cit-grammar", mode = Mode.DEMOTE)
public record CitRule(
    @NotNull ResourceId id,
    @NotNull PackId pack,
    @NotNull CitType type,
    @NotNull ConcurrentList<ResourceId> items,
    @NotNull Optional<DamageSpec> damage,
    @NotNull Optional<IntRanges> stackSize,
    @NotNull Optional<EnchantmentSpec> enchantments,
    @NotNull Hand hand,
    @NotNull ConcurrentList<NbtRule> nbtRules,
    @NotNull CitOutput output,
    int weight
) {

    /**
     * The source {@code .properties} filename - the last path segment of {@link #id} - used as the
     * deterministic weight-tie sort key.
     *
     * @return the source filename
     */
    public @NotNull String filename() {
        String path = this.id.name();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

}
