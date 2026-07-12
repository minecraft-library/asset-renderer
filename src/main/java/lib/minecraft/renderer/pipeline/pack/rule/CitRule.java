package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.PackId;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A parsed OptiFine / MCPatcher Custom Item Texture rule (03-rules §3.1) - the successor to HEAD's
 * {@code asset.rule.CitRule}, rebuilt against the 07-optifine defect table.
 *
 * <p>Every condition is ANDed (07-optifine §2.1): {@link #matches} returns {@code true} only when the
 * item id, damage, stack size, enchantments, hand, and every {@link NbtRule} all hold. The parser is
 * fail-closed (D3.3) - an unparseable condition rejects the whole rule rather than silently dropping
 * a filter, so a rule that loads is a rule whose every condition is known.
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
     * Whether this rule applies to a render-time item context. Checks run cheapest-first (id, then
     * the scalar range filters, then the NBT walks) so the common no-match path exits early. A
     * {@link Hand#OFF} rule never matches because GUI rendering is always the main hand
     * (07-optifine §7.1).
     *
     * @param context the per-render item context
     * @return {@code true} when every condition holds
     */
    public boolean matches(@NotNull ItemContext context) {
        if (this.hand == Hand.OFF) return false;

        if (this.items.stream().noneMatch(item -> item.id().equals(context.itemId()))) return false;

        if (this.damage.isPresent() && !this.damage.get().matches(context.damage(), context.maxDamage())) return false;

        if (this.stackSize.isPresent() && !this.stackSize.get().contains(context.stackCount())) return false;

        if (this.enchantments.isPresent() && !this.enchantments.get().matches(context.enchantments())) return false;

        if (!this.nbtRules.isEmpty()) {
            CompoundTag nbt = context.effectiveNbt();
            for (NbtRule rule : this.nbtRules)
                if (!rule.matches(nbt)) return false;
        }

        return true;
    }

    /**
     * The source {@code .properties} filename - the last path segment of {@link #id} - used as the
     * deterministic weight-tie sort key (D3.2, replacing HEAD's walk-order tie-break).
     *
     * @return the source filename
     */
    public @NotNull String filename() {
        String path = this.id.name();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

}
