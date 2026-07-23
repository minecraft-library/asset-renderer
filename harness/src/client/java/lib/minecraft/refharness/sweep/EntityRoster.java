package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.rabbit.Rabbit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
     * The coats of the families whose variant axis is a plain Java enum rather than a data-driven
     * registry, so the registry walk cannot reach them.
     *
     * <p>Every one is persisted, because vanilla declares each of these setters private - the NBT
     * round-trip a world load runs is the only public route to them. The tag name and its type are
     * vanilla's: a coat filed under an integer id keeps that id, and the panda's gene pair is written
     * as two strings because the renderer reads the visible gene only where the hidden one agrees.
     *
     * @param type the entity type
     * @return its coats, or empty for a type whose coats come from a registry or from nowhere
     */
    public static List<Appearance.Coat> enumCoats(EntityType<?> type) {
        List<Appearance.Coat> coats = new ArrayList<>();
        if (type == EntityType.AXOLOTL) {
            for (Axolotl.Variant coat : Axolotl.Variant.values())
                coats.add(Appearance.Coat.ofInt("Variant", coat.getId(), coat.getSerializedName()));
        } else if (type == EntityType.LLAMA || type == EntityType.TRADER_LLAMA) {
            for (Llama.Variant coat : Llama.Variant.values())
                coats.add(Appearance.Coat.ofInt("Variant", coat.getId(), coat.getSerializedName()));
        } else if (type == EntityType.RABBIT) {
            for (Rabbit.Variant coat : Rabbit.Variant.values())
                coats.add(Appearance.Coat.ofInt("RabbitType", coat.id(), coat.getSerializedName()));
        } else if (type == EntityType.PANDA) {
            for (Panda.Gene gene : Panda.Gene.values()) {
                String name = gene.getSerializedName();
                coats.add(Appearance.Coat.ofString("MainGene", name, name).and("HiddenGene", name));
            }
        }
        return coats;
    }

    /**
     * The one-axis appearance selections a type is swept at, beyond its coats and its baby.
     *
     * <p>One entry is one reference. An axis names only the models that answer to it: applying it to
     * a model that ignores it renders the default under a name claiming otherwise, which inflates the
     * coverage number with references that cannot fail.
     *
     * @param ctx the sweep context, for the registries some option lists come from
     * @param type the entity type
     * @return its selections, empty for a type no axis reaches
     */
    public static List<Appearance.Trait> selections(SweepContext ctx, EntityType<?> type) {
        List<Appearance.Trait> selections = new ArrayList<>();
        // Wolf is the only model whose data declares a behavioural state, and the wild state is its
        // default, so only the other two are references.
        if (type == EntityType.WOLF) {
            selections.add(new Appearance.Trait(TraitAxis.STATE.token(), TraitAxis.ANGRY));
            selections.add(new Appearance.Trait(TraitAxis.STATE.token(), TraitAxis.TAME));
        }
        // The stage axes each reach one model, and each carries a default the reference set already
        // covers - an uncracked golem, an unweathered one, an unmarked horse.
        if (type == EntityType.IRON_GOLEM) select(selections, TraitAxis.CRACKINESS, "low", "medium", "high");
        if (type == EntityType.COPPER_GOLEM)
            select(selections, TraitAxis.WEATHERING, "exposed", "weathered", "oxidized");
        if (type == EntityType.HORSE)
            select(selections, TraitAxis.MARKINGS, "white", "white_field", "white_dots", "black_dots");
        // The fish's pattern is also its body shape - six of the twelve are drawn on the large mesh -
        // so the shape axis needs no references of its own.
        if (type == EntityType.TROPICAL_FISH) {
            for (TropicalFish.Pattern pattern : TropicalFish.Pattern.values())
                if (pattern != TropicalFish.Pattern.KOB)
                    selections.add(new Appearance.Trait(TraitAxis.PATTERN.token(), pattern.getSerializedName()));
        }
        // The two villagers share one overlay set. The zombie villager takes the professions and not
        // the biome types: vanilla's texture corpus ships it no per-type sidecar, so each of those six
        // would be a byte-identical copy of its default under a name claiming otherwise.
        if (type == EntityType.VILLAGER)
            select(selections, TraitAxis.VILLAGER_TYPE,
                "desert", "jungle", "savanna", "snow", "swamp", "taiga");
        if (type == EntityType.VILLAGER || type == EntityType.ZOMBIE_VILLAGER)
            select(selections, TraitAxis.VILLAGER_PROFESSION,
                "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher",
                "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");
        // Two booleans that reach two models each and are a silent no-op on the other eighty-eight.
        if (type == EntityType.SHEEP || type == EntityType.BOGGED) select(selections, TraitAxis.SHEARED, "true");
        if (type == EntityType.CREEPER || type == EntityType.WITHER) select(selections, TraitAxis.CHARGED, "true");
        for (String toggle : BONE_TOGGLES.getOrDefault(type, List.of()))
            selections.add(new Appearance.Trait(TraitAxis.TOGGLE.token(), toggle));
        // The size options exclude the model's own default, unlike the coat options, so each model
        // contributes only the sizes it is not already rendered at.
        if (type == EntityType.SLIME || type == EntityType.MAGMA_CUBE)
            select(selections, TraitAxis.SIZE, "medium", "large");
        if (type == EntityType.SALMON) select(selections, TraitAxis.SIZE, "small", "large");
        if (type == EntityType.PUFFERFISH) select(selections, TraitAxis.SIZE, "small", "medium");
        return selections;
    }

    /**
     * The bone toggles each model carries, named as the appearance names them rather than as the mesh
     * names the bones they reach.
     *
     * <p>The bogged's shear toggle is not here: it is driven by the sheared flag rather than selected
     * on its own, so counting it twice would be counting one reference twice.
     */
    private static final Map<EntityType<?>, List<String>> BONE_TOGGLES = Map.of(
        EntityType.ARMOR_STAND, List.of("show_arms", "show_base_plate"),
        EntityType.BEE, List.of("stinger"),
        EntityType.DONKEY, List.of("chest"),
        EntityType.MULE, List.of("chest"),
        EntityType.LLAMA, List.of("chest"),
        EntityType.TRADER_LLAMA, List.of("chest"),
        EntityType.GOAT, List.of("horn"),
        EntityType.TURTLE, List.of("egg"));

    /**
     * The bones a subject's selections force, mapped to the visibility they force them to.
     *
     * <p>Vanilla drives every one of these from {@code setupAnim}, which the harness does not run, so
     * the flag has to be written onto the part. Two of them read backwards from the rest: a goat is
     * horned and a bogged is mushroomed until something says otherwise.
     *
     * @param type the entity type, which decides what a shared selection name reaches
     * @param appearance the selections being rendered
     * @return the bones to force, empty when nothing selected reaches one
     */
    public static Map<String, Boolean> bonePins(EntityType<?> type, Appearance appearance) {
        Map<String, Boolean> pins = new LinkedHashMap<>();
        for (Appearance.Trait trait : appearance.traits()) {
            if (trait.axis().equals(TraitAxis.TOGGLE.token())) pins.putAll(toggleBones(trait.value()));
            else if (trait.axis().equals(TraitAxis.SHEARED.token()) && type == EntityType.BOGGED)
                pins.put("mushrooms", false);
        }
        return pins;
    }

    /** The bones one toggle name reaches, and what selecting it does to them. */
    private static Map<String, Boolean> toggleBones(String toggle) {
        return switch (toggle) {
            case "show_arms" -> Map.of("left_arm", true, "right_arm", true);
            case "show_base_plate" -> Map.of("base_plate", true);
            case "stinger" -> Map.of("stinger", true);
            case "chest" -> Map.of("left_chest", true, "right_chest", true);
            case "horn" -> Map.of("left_horn", false, "right_horn", false);
            case "egg" -> Map.of("egg_belly", true);
            default -> throw new IllegalArgumentException("No bone toggle named '" + toggle + "'");
        };
    }

    /** Appends one selection per option of a single axis. */
    private static void select(List<Appearance.Trait> selections, TraitAxis axis, String... options) {
        for (String option : options) selections.add(new Appearance.Trait(axis.token(), option));
    }

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
     * Cross-entity groups - types the asset-renderer measures a shared silhouette across because they
     * share a mesh.
     *
     * <p>Distinct from {@link #FAMILY_OVERRIDES}, which decides what an adult canvas is unioned over.
     * This decides only whether a subject's <em>baby</em> canvas is measured against its adults as
     * well as itself, and the two answers are genuinely different: a stray shares a canvas with a
     * skeleton, but a wandering trader does not share one with a villager while still sharing the
     * villager's baby framing.
     */
    public static final Map<EntityType<?>, EntityType<?>> GROUP_OF = Map.of(
        EntityType.CAMEL_HUSK, EntityType.CAMEL,
        EntityType.MOOSHROOM, EntityType.COW,
        EntityType.ZOGLIN, EntityType.HOGLIN,
        EntityType.TRADER_LLAMA, EntityType.LLAMA,
        EntityType.PIGLIN_BRUTE, EntityType.PIGLIN,
        EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN,
        EntityType.STRAY, EntityType.SKELETON,
        EntityType.GLOW_SQUID, EntityType.SQUID,
        EntityType.WANDERING_TRADER, EntityType.VILLAGER
    );

    /**
     * Whether a type's selected-appearance canvases are measured against its plain subjects as well
     * as against the selection.
     *
     * <p>Not a stylistic choice - it mirrors what the asset-renderer measures, and getting it wrong
     * produces a reference of different dimensions whose comparison then reports framing rather than
     * the render. The asset side unions the appearance it is rendering with every adult coat of its
     * own model and with every member of its group; a model that is neither coated nor grouped is
     * measured against the selected appearance alone, and its plain silhouette never enters the box.
     *
     * @param type the entity type
     * @return whether the plain silhouette belongs in this type's derived canvases
     */
    public static boolean sharesDefaultCanvas(EntityType<?> type) {
        return DEFAULT_COAT.containsKey(type)
            || GROUP_OF.containsKey(type)
            || GROUP_OF.containsValue(type);
    }

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
