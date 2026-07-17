package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.StringUtil;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.StringTag;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The per-render item context the item renderer passes down so the pack rule layer can decide whether
 * any CIT rule applies to the current invocation.
 *
 * <p>The NBT is carried as a real {@link CompoundTag} tree, which preserves list indices, wildcards,
 * {@code count}, and tag types. Scalar conveniences ({@link #itemId}, {@link #damage}, {@link #maxDamage},
 * {@link #stackCount}, {@link #displayName}, {@link #enchantments}, {@link #potionEffects}) stay for
 * the non-NBT conditions. When a caller supplies no NBT the {@link Builder} synthesises a minimal
 * compound from the display name (under {@code components.minecraft:custom_name}), so display-name
 * rules keep matching scalar-only callers.
 *
 * @param itemId the namespaced item id, e.g. {@code minecraft:diamond_sword}
 * @param damage the current damage value, {@code 0} for items with no durability
 * @param maxDamage the item's max durability, for the CIT {@code damage=%} percentage mode
 * @param stackCount the stack size for the item slot
 * @param displayName the item display name, if any
 * @param nbt the item's NBT tree, or empty when the caller supplied neither NBT nor a synthesisable scalar
 * @param enchantments the item enchantments keyed by namespaced id (e.g. {@code minecraft:sharpness} to {@code 5})
 * @param potionEffects the potion effects on this item, in application order; the first drives the liquid tint
 */
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
     * A mutable builder for {@link ItemContext}. Supports eager binary-NBT ingestion
     * ({@link #nbtBase64} / {@link #nbt(byte[])} - Hypixel item payloads are base64 gzipped
     * binary NBT) and, when no NBT is supplied, synthesises a minimal compound from the scalars at
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
         * Parses a base64-wrapped (gzip auto-detected) binary NBT payload - the Hypixel item shape -
         * into a lazily-decoded borrow tree: the base64 is decoded to bytes, then handed to
         * {@link NbtFactory#borrowFromByteArray}. The rule layer reads most fields once, so the borrow
         * tree skips the per-value materialization the eager path pays up front.
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
