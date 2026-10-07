package lib.minecraft.renderer.request;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.StringUtil;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.NumericalTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.asset.rule.CitRule;
import lib.minecraft.renderer.asset.rule.CitType;
import lib.minecraft.renderer.asset.rule.Hand;
import lib.minecraft.renderer.asset.rule.filter.NbtRule;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The item stack one item render draws - the input the pack's CIT rules, the item definition's
 * dispatch walk, the tooltip and the dye tint all read.
 *
 * <p>The stack is a Minecraft 26.1 item stack, the root the game writes for one:
 * {@code {id, count, components}}, where {@code components} is the stack's patch - each set component
 * keyed by its qualified id ({@code minecraft:custom_data}, {@code minecraft:custom_name},
 * {@code minecraft:dyed_color}, ...) in its 26.1 NBT form, and a removed one keyed
 * {@code !minecraft:<id>}. {@link #ofStack(CompoundTag)} reads one. A component an item definition tests
 * is read from that {@code components} compound ({@link #components()}), and {@code minecraft:item_model},
 * which every 26.1 item holds by default as its own id, from {@link #itemId} where the compound neither
 * sets nor removes it. So a stack written in any other shape - an item stack from before 1.20.5, whose
 * data sits under {@code tag} - carries none of the components a definition tests but that one. The
 * CIT rules walk the whole tree, so their root-relative {@code nbt.*} paths read whatever shape they
 * are written against.
 *
 * <p>The NBT is carried as a real {@link CompoundTag} tree, which preserves list indices, wildcards,
 * {@code count}, and tag types. Scalar conveniences ({@link #itemId}, {@link #damage}, {@link #maxDamage},
 * {@link #stackCount}, {@link #displayName}, {@link #enchantments}, {@link #potionEffects}) stay for
 * the non-NBT conditions. When a caller supplies no NBT the {@link Builder} synthesises a minimal
 * compound from the display name, a plain-literal {@code minecraft:custom_name} under
 * {@code components}, which is the 26.1 form of an unstyled name, so display-name rules and
 * definitions keep matching scalar-only callers.
 *
 * @param itemId the namespaced item id, e.g. {@code minecraft:diamond_sword}
 * @param damage the current damage value, {@code 0} for items with no durability
 * @param maxDamage the item's max durability, for the CIT {@code damage=%} percentage mode
 * @param stackCount the stack size for the item slot
 * @param displayName the item display name, if any
 * @param nbt the item's NBT tree - a 26.1 stack, or any compound the CIT rules walk - or empty when the caller supplied neither NBT nor a synthesisable scalar
 * @param enchantments the item enchantments keyed by namespaced id (e.g. {@code minecraft:sharpness} to {@code 5})
 * @param potionEffects the potion effects on this item, in application order; the first drives the liquid tint
 */
@Parity(claim = "asset-layer")
@Parity(claim = "pack-rule-layer")
public record ItemContext(
    @NotNull String itemId,
    int damage,
    int maxDamage,
    int stackCount,
    @NotNull Optional<String> displayName,
    @NotNull Optional<CompoundTag> nbt,
    @NotNull ConcurrentMap<String, Integer> enchantments,
    @NotNull ConcurrentList<String> potionEffects
) {

    /** The empty context - no item, no metadata. CIT rules never match against this, and the item render seam short-circuits it. */
    public static final @NotNull ItemContext EMPTY = new ItemContext(
        "", 0, 0, 1, Optional.empty(), Optional.empty(), Concurrent.newMap(), Concurrent.newList());

    /**
     * A minimal context describing only the item id, for render calls that do not care about CIT
     * matching beyond the item.
     *
     * @param itemId the namespaced item id
     * @return the item-only context
     */
    public static @NotNull ItemContext ofItem(@NotNull String itemId) {
        return builder().itemId(itemId).build();
    }

    /**
     * Builds the context a Minecraft 26.1 item stack describes, as {@link Builder#stack(CompoundTag)}
     * reads one. The stack is read while a render runs, so it must not be mutated meanwhile.
     *
     * @param stack the item stack, {@code {id, count, components}}
     * @return the stack's context
     */
    public static @NotNull ItemContext ofStack(@NotNull CompoundTag stack) {
        return builder().stack(stack).build();
    }

    /**
     * Opens a fresh builder.
     *
     * @return a new builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * The item's NBT tree, materialising an empty compound when the context carries none - so the CIT
     * matcher can walk uniformly whether or not the caller supplied NBT.
     *
     * @return the NBT tree, or a fresh empty compound when absent
     */
    public @NotNull CompoundTag effectiveNbt() {
        return this.nbt.orElseGet(CompoundTag::new);
    }

    /**
     * The stack's component patch - the {@code components} compound at the root of the NBT, keyed by
     * qualified component id, a removed component keyed {@code !minecraft:<id>} - which an item
     * definition's component tests read.
     *
     * @return the component patch, empty where the NBT holds no {@code components} compound
     */
    public @NotNull Optional<CompoundTag> components() {
        return this.nbt.map(root -> root.get("components"))
            .filter(CompoundTag.class::isInstance)
            .map(CompoundTag.class::cast);
    }

    /**
     * Answers whether a CIT rule applies to this item. Checks run cheapest-first (id, then the scalar
     * range filters, then the NBT walks) so the common no-match path exits early. A {@link Hand#OFF}
     * rule never matches because GUI rendering is always the main hand. A rule with an empty
     * {@link CitRule#items() items} list matches any item - the parser rejects that for
     * {@link CitType#ITEM} rules, but a {@code type=enchantment} glint rule may legitimately carry no
     * item filter and then applies to every item bearing the matched enchantment.
     *
     * @param rule the parsed CIT rule
     * @return {@code true} when every condition of the rule holds
     */
    public boolean matches(@NotNull CitRule rule) {
        if (rule.hand() == Hand.OFF) return false;

        if (!rule.items().isEmpty() && rule.items().stream().noneMatch(item -> item.id().equals(this.itemId))) return false;

        if (rule.damage().isPresent() && !rule.damage().get().matches(this.damage, this.maxDamage)) return false;

        if (rule.stackSize().isPresent() && !rule.stackSize().get().contains(this.stackCount)) return false;

        if (rule.enchantments().isPresent() && !rule.enchantments().get().matches(this.enchantments)) return false;

        if (!rule.nbtRules().isEmpty()) {
            CompoundTag nbt = effectiveNbt();
            for (NbtRule nbtRule : rule.nbtRules())
                if (!nbtRule.matches(nbt)) return false;
        }

        return true;
    }

    /**
     * Synthesises a minimal NBT compound from the scalar conveniences - currently the display name at
     * {@code components.minecraft:custom_name}, the canonical path the legacy {@code nbt.display.Name}
     * alias rewrites to - so display-name CIT rules match a scalar-only caller.
     */
    private static @NotNull Optional<CompoundTag> synthesise(@NotNull Optional<String> displayName) {
        if (displayName.isEmpty()) return Optional.empty();
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_name", new StringTag(displayName.get()));
        CompoundTag root = new CompoundTag();
        root.put("components", components);
        return Optional.of(root);
    }

    /**
     * A mutable builder for {@link ItemContext}. Reads a 26.1 item stack's scalars out of the stack
     * itself ({@link #stack}), takes binary NBT ({@link #nbtBase64} / {@link #nbt(byte[])}) as the tree
     * it is, and, when no NBT is supplied, synthesises a minimal compound from the scalars at
     * {@link #build}.
     */
    public static final class Builder {

        private @NotNull String itemId = "";
        private int damage = 0;
        private int maxDamage = 0;
        private int stackCount = 1;
        private @NotNull Optional<String> displayName = Optional.empty();
        private @NotNull Optional<CompoundTag> nbt = Optional.empty();
        private final @NotNull ConcurrentMap<String, Integer> enchantments = Concurrent.newMap();
        private final @NotNull ConcurrentList<String> potionEffects = Concurrent.newList();

        private Builder() {}

        /**
         * Sets the namespaced item id.
         *
         * @param itemId the item id
         * @return this builder
         */
        public @NotNull Builder itemId(@NotNull String itemId) {
            this.itemId = itemId;
            return this;
        }

        /**
         * Sets the current damage value.
         *
         * @param damage the damage value
         * @return this builder
         */
        public @NotNull Builder damage(int damage) {
            this.damage = damage;
            return this;
        }

        /**
         * Sets the item's max durability.
         *
         * @param maxDamage the max durability
         * @return this builder
         */
        public @NotNull Builder maxDamage(int maxDamage) {
            this.maxDamage = maxDamage;
            return this;
        }

        /**
         * Sets the stack size.
         *
         * @param stackCount the stack size
         * @return this builder
         */
        public @NotNull Builder stackCount(int stackCount) {
            this.stackCount = stackCount;
            return this;
        }

        /**
         * Sets the item display name.
         *
         * @param displayName the display name
         * @return this builder
         */
        public @NotNull Builder displayName(@NotNull String displayName) {
            this.displayName = Optional.of(displayName);
            return this;
        }

        /**
         * Sets the NBT tree directly.
         *
         * @param nbt the NBT compound
         * @return this builder
         */
        public @NotNull Builder nbt(@NotNull CompoundTag nbt) {
            this.nbt = Optional.of(nbt);
            return this;
        }

        /**
         * Reads a Minecraft 26.1 item stack, {@code {id, count, components}}: the item id from
         * {@code id}, the stack size from {@code count}, the damage from the {@code minecraft:damage}
         * component, and the stack itself as the NBT tree, whose {@code components} compound the item
         * definition's component tests read.
         * <p>
         * An absent {@code id} keeps the id set so far, and an absent {@code count} reads as one, as the
         * game reads it. The max durability is not read: {@code minecraft:max_damage} is a component an
         * item holds by default, which a stack's patch does not normally set, so a caller whose CIT rules
         * test a damage percentage sets {@link #maxDamage(int)}.
         *
         * @param stack the item stack
         * @return this builder
         */
        public @NotNull Builder stack(@NotNull CompoundTag stack) {
            if (stack.get("id") instanceof StringTag id)
                this.itemId = id.getValue();

            this.stackCount = stack.get("count") instanceof NumericalTag<?> count ? count.intValue() : 1;

            if (stack.get("components") instanceof CompoundTag components
                && components.get("minecraft:damage") instanceof NumericalTag<?> damage)
                this.damage = damage.intValue();

            this.nbt = Optional.of(stack);
            return this;
        }

        /**
         * Parses a base64-wrapped (gzip auto-detected) binary NBT payload into a lazily-decoded borrow
         * tree: the base64 is decoded to bytes, then handed to {@link NbtFactory#borrowFromByteArray}.
         * The tree is read as it decodes - a binary 26.1 item stack reaches the item definition's
         * component tests through its {@code components} compound, and any other compound reaches the
         * CIT rules that walk it. The rule layer reads most fields once, so the borrow tree skips the
         * per-value materialization the eager path pays up front.
         *
         * <p>The decoded array is freshly allocated per call, so the borrow tree's buffer-retention
         * contract carries no caller-mutation hazard here.
         *
         * @param base64 the base64 NBT payload
         * @return this builder
         */
        public @NotNull Builder nbtBase64(@NotNull String base64) {
            this.nbt = Optional.of(NbtFactory.borrowFromByteArray(StringUtil.decodeBase64(base64)));
            return this;
        }

        /**
         * Parses a binary NBT byte array into a lazily-decoded borrow tree via
         * {@link NbtFactory#borrowFromByteArray}. The rule layer reads most fields once, so the
         * borrow tree skips the per-value materialization the eager path pays up front.
         *
         * <p>The returned tree retains the (decompressed) input bytes, so callers must not mutate
         * {@code bytes} after this call.
         *
         * @param bytes the binary NBT bytes
         * @return this builder
         */
        public @NotNull Builder nbt(byte @NotNull [] bytes) {
            this.nbt = Optional.of(NbtFactory.borrowFromByteArray(bytes));
            return this;
        }

        /**
         * Adds one enchantment.
         *
         * @param id the namespaced enchantment id
         * @param level the enchantment level
         * @return this builder
         */
        public @NotNull Builder enchantment(@NotNull String id, int level) {
            this.enchantments.put(id, level);
            return this;
        }

        /**
         * Adds one potion effect id.
         *
         * @param effectId the namespaced effect id
         * @return this builder
         */
        public @NotNull Builder potionEffect(@NotNull String effectId) {
            this.potionEffects.add(effectId);
            return this;
        }

        /**
         * Builds the context, synthesising a minimal NBT compound from the scalars when the caller
         * supplied none.
         *
         * @return the built context
         */
        public @NotNull ItemContext build() {
            Optional<CompoundTag> resolved = this.nbt.isPresent() ? this.nbt : synthesise(this.displayName);
            return new ItemContext(
                this.itemId, this.damage, this.maxDamage, this.stackCount,
                this.displayName, resolved, this.enchantments, this.potionEffects);
        }

    }

}
