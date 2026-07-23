package lib.minecraft.refharness.sweep;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;

import java.util.Map;
import java.util.Set;

/** The hand-maintained tables that decide which entities are swept and how they are grouped. */
public final class EntityRoster {

    private EntityRoster() {}

    /**
     * Allowlist of {@link MobCategory#MISC} entity types that should still be rendered despite the
     * MISC-filter exclusion.
     *
     * <p>Everything in MISC is non-renderable for these purposes - items, lightning, projectiles,
     * vehicles, paintings - <em>except</em> a few {@link LivingEntity} subclasses vanilla
     * deliberately categorises as MISC because they do not behave like mobs (no AI, no spawn rules)
     * but still have a model and a renderer that produces a useful portrait.
     *
     * <p>Add ids here when a new such entity ships. Each entry must be the key of an entity type
     * whose creation returns a {@link LivingEntity}, or the rest of the sweep's render path - which
     * assumes one for things like body-rotation zeroing - will throw at runtime.
     */
    public static final Set<Identifier> MISC_ALLOWLIST = Set.of(
        Identifier.withDefaultNamespace("armor_stand"),
        // Every id below registers as MobCategory.MISC in vanilla yet is a LivingEntity the
        // asset-renderer emits and renders, so each needs an entry here to get a reference PNG.
        // The golems are MISC because they have no spawn rules; the villager because its spawning
        // is structure-driven rather than category-driven.
        Identifier.withDefaultNamespace("copper_golem"),
        Identifier.withDefaultNamespace("iron_golem"),
        Identifier.withDefaultNamespace("snow_golem"),
        Identifier.withDefaultNamespace("villager")
    );

    /**
     * Entity types whose vanilla renderer dispatches to a different texture or model based on a
     * registry-driven variant. For each type listed here the sweep enumerates every variant in the
     * corresponding registry and writes one PNG per pair instead of just the type's default.
     *
     * <p>The variant is set through NBT - {@code variant} is the field vanilla's deserialiser keys
     * off - and the entity is reconstructed so that deserialiser runs its lookup and applies the
     * variant before render-state extraction. Plain creation does not accept NBT and would hand back
     * the default variant.
     *
     * <p>Adding a new variant-bearing entity is one line here plus its variant-registry import.
     */
    public static final Map<EntityType<?>, ResourceKey<? extends Registry<?>>> VARIANT_REGISTRIES = Map.of(
        EntityType.COW, Registries.COW_VARIANT,
        EntityType.PIG, Registries.PIG_VARIANT,
        EntityType.CHICKEN, Registries.CHICKEN_VARIANT,
        EntityType.FROG, Registries.FROG_VARIANT,
        EntityType.WOLF, Registries.WOLF_VARIANT,
        // cat + zombie_nautilus also render per-variant in vanilla; enumerate them so their
        // per-variant reference PNGs exist to match the asset-renderer's per-variant rows.
        EntityType.CAT, Registries.CAT_VARIANT,
        EntityType.ZOMBIE_NAUTILUS, Registries.ZOMBIE_NAUTILUS_VARIANT
    );

    /**
     * The coat each variant family is at when nothing selects one.
     *
     * <p>Two jobs. Five of these families carry a variant axis whose options are a plain Java enum
     * rather than a data-driven registry, so the sweep does not walk them and each ships exactly one
     * reference - naming it after the coat it is keeps a family from having two spellings, a bare one
     * and a coated one, for the same appearance. For all fourteen it also says which coat an axis
     * that is not the coat axis should be rendered at, so a baby is one reference per model rather
     * than one per coat.
     *
     * <p>These names are vanilla's own defaults, and they are what the asset-renderer's model form
     * declares as each axis's default. A wrong entry here would not produce an unreadable name - it
     * would produce a readable name for a different subject, so it is worth checking against the
     * model form rather than against intuition.
     */
    public static final Map<EntityType<?>, String> DEFAULT_COAT = Map.ofEntries(
        // Enum-driven, and the only reference these families ship today.
        Map.entry(EntityType.AXOLOTL, "lucy"),
        Map.entry(EntityType.LLAMA, "creamy"),
        Map.entry(EntityType.PANDA, "normal"),
        Map.entry(EntityType.RABBIT, "brown"),
        Map.entry(EntityType.TRADER_LLAMA, "creamy"),
        // Registry-driven, walked in full by the sweep.
        Map.entry(EntityType.CAT, "black"),
        Map.entry(EntityType.CHICKEN, "temperate"),
        Map.entry(EntityType.COW, "temperate"),
        Map.entry(EntityType.FROG, "temperate"),
        Map.entry(EntityType.PIG, "temperate"),
        Map.entry(EntityType.WOLF, "pale"),
        Map.entry(EntityType.ZOMBIE_NAUTILUS, "temperate"),
        // Enum-driven and walked in full, each by its own arm.
        Map.entry(EntityType.HORSE, "white"),
        Map.entry(EntityType.MOOSHROOM, "red")
    );

    /**
     * Cross-type family overrides for the canvas-sizing pre-pass, mapping a secondary entity type to
     * the primary type whose family it shares - and thus whose canvas, scale and anchor it renders
     * with.
     *
     * <p>Variants of one type group automatically, since they all key on the same type; an entry is
     * needed only when two distinct types should group.
     *
     * <p>Stray joins skeleton because they share a mesh: the Java pipeline canvas-fits skeleton with
     * stray's inflated clothing-layer overlay, so the harness has to apply the same union to keep
     * skeleton's reference at the same canvas dimensions as stray's.
     *
     * <p>Mooshroom is deliberately <b>not</b> grouped into cow. The asset-renderer sizes cow to the
     * cow body alone and mooshroom to its own body plus mushrooms, so grouping them here would push
     * the cow reference down by the mushroom height and diverge from the Java render.
     */
    public static final Map<EntityType<?>, EntityType<?>> FAMILY_OVERRIDES = Map.of(
        EntityType.STRAY, EntityType.SKELETON
    );

    /**
     * Returns the type whose family a type belongs to.
     *
     * @param type the entity type
     * @return its family root, which is the type itself unless it is overridden
     */
    public static EntityType<?> familyRoot(EntityType<?> type) {
        return FAMILY_OVERRIDES.getOrDefault(type, type);
    }
}
