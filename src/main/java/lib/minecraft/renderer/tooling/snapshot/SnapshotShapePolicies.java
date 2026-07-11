package lib.minecraft.renderer.tooling.snapshot;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The tint / potion / glint walks' complete policy roster (SPINE 2.1: P45 + P46) - the
 * renderer-capability drops recorded in {@code dropped[]} (never silent, decision 24) and the
 * bytecode-shape judgments the three walks pattern-match on. Never fetches
 * ({@code PolicyPurityTest}): the walks do the fetching; these constants home the declared
 * facts + their hard-won provenance so every hard-coded judgment lives in one place.
 *
 * <p>Everything the audits marked DERIVABLE - tint colormap targets (from the source body's
 * {@code BiomeColors.getAverage*Color} call), stem colour (symbolic eval at age 0, D60), block
 * ids ({@code BlockRegistryIndex}) - ships as derivation with NO policy fallback and is absent
 * from this roster.
 */
enum SnapshotShapePolicies implements NavigationPolicy {

    // P45 - renderer-capability drops (recorded in dropped[], never silent - decision 24)

    /**
     * P45 - the tint sources the static GUI/parity render cannot sample: their colour is
     * per-state / biome-dynamic, so the icon would show the default state anyway. Recorded as
     * {@code dropped[]} rows with reason {@code dynamic_source} rather than emitted.
     */
    TINT_DYNAMIC_SOURCE_DROPS(
        Set.of("water", "waterParticles", "redstone"),
        "renderer cannot sample per-state / biome-dynamic tints at static render time; the GUI"
            + " icon shows the default state (ToolingBlockTints.java:155-158). water / waterParticles"
            + " resolve BiomeColors.getAverageWaterColor - no WATER colormap target exists; redstone"
            + " resolves RedStoneWireBlock.getColorForPower - power-driven, no colormap"),

    /**
     * P45 - a {@code List.of(a, b, ...)} tint registration composes multiple sources
     * ({@code pink_petals} = {@code [BLANK_LAYER, grass()]}); the renderer tints one source per
     * block, so the registration is recorded as {@code dropped[]} with reason
     * {@code multi_source}. The value IS the reason string.
     */
    TINT_MULTI_SOURCE_DROP(
        "multi_source",
        "composed List.of registrations are unsupported: the renderer tints one source per block"
            + " (pink_petals [BLANK_LAYER, grass()]) - ToolingBlockTints.java:254-256"),

    /**
     * P45 - {@code constant(colorInHand, colorInWorld)}: the GUI block icon uses vanilla's
     * no-context in-hand colour ({@code BlockTintSource.color(state)}), which is the FIRST arg.
     * The value is the picked argument index.
     */
    TINT_CONSTANT_IN_HAND(
        0,
        "constant(colorInHand, colorInWorld): the GUI icon uses vanilla's no-context in-hand"
            + " colour = the first arg (ToolingBlockTints.java:244-248)"),

    // P46 - bytecode-shape knowledge (declared shapes with their hard-won comments)

    /**
     * P46 - the first LDC string since the last {@code register} is the effect id; later strings
     * are attribute-modifier ids ({@code "effect.speed"}). Pure shape marker.
     */
    POTION_EFFECT_ID_FIRST_LDC(
        Boolean.TRUE,
        "the first LDC string since the last register is the effect id; later strings are"
            + " attribute-modifier ids ('effect.speed') - ToolingPotionColors.java:168-171"),

    /**
     * P46 - only int literals between {@code NEW net/minecraft/world/effect/*} and its
     * {@code <init>} are colour candidates; a fresh {@code NEW} resets the int stack. Pure shape
     * marker.
     */
    POTION_NEW_RESETS_STACK(
        Boolean.TRUE,
        "only int literals between NEW net/minecraft/world/effect/* and its <init> are colour"
            + " candidates; a fresh NEW resets the int stack - ToolingPotionColors.java:176-181"),

    /**
     * P46 - the colour-carrying effect constructor is {@code (MobEffectCategory, int)V}; the
     * trailing int literal is the ARGB colour. Prefix-owner match handles {@code MobEffect}
     * subclasses. Pure shape marker (the descriptor is composed from VSC).
     */
    POTION_COLOR_CTOR(
        Boolean.TRUE,
        "the colour-carrying MobEffect ctor is (MobEffectCategory, int)V; the trailing int is the"
            + " ARGB colour, prefix-owner matched for subclasses - ToolingPotionColors.java:185-195"),

    /**
     * P46 - {@code GETSTATIC DataComponents.ENCHANTMENT_GLINT_OVERRIDE} immediately followed by
     * {@code iconst_1} means {@code component(..., true)} - the item is always-foil. Pure shape
     * marker.
     */
    GLINT_TRUE_ADJACENCY(
        Boolean.TRUE,
        "GETSTATIC ENCHANTMENT_GLINT_OVERRIDE followed by iconst_1 == component(.., true)"
            + " - ToolingGlintItems.java:161-166"),

    /**
     * P46 - {@code PUTSTATIC Items.<field>:LItem;} terminates one registration and resets the
     * pending glint flag so a glint set on one item never bleeds into the next. Pure shape
     * marker.
     */
    GLINT_ITEM_FIELD_RESET(
        Boolean.TRUE,
        "PUTSTATIC Items.<field>:LItem; terminates a registration; the reset prevents glint"
            + " bleeding across registrations - ToolingGlintItems.java:171-177, :102-105");

    /** The reason recorded for {@link #TINT_DYNAMIC_SOURCE_DROPS} rows. */
    static final @NotNull String REASON_DYNAMIC_SOURCE = "dynamic_source";

    private final @NotNull Object value;
    private final @NotNull String provenance;

    SnapshotShapePolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /** The tint-source method names the renderer cannot sample (dropped as {@code dynamic_source}). */
    @SuppressWarnings("unchecked")
    static @NotNull Set<String> dynamicSourceDrops() {
        return (Set<String>) TINT_DYNAMIC_SOURCE_DROPS.value;
    }

    /** The reason recorded for a composed multi-source registration. */
    static @NotNull String multiSourceReason() {
        return (String) TINT_MULTI_SOURCE_DROP.value;
    }

    /** The {@code constant(colorInHand, colorInWorld)} argument index the GUI icon uses (the first). */
    static int constantInHandArg() {
        return (int) TINT_CONSTANT_IN_HAND.value;
    }

}
