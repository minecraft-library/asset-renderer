package lib.minecraft.refharness.sweep;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The hand-maintained tables that decide which entities are swept and how they are grouped. */
@UtilityClass
public final class EntityRoster {

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
     * <p>One entry is one reference, and an entry is a list because a reference sometimes has to
     * select more than one thing to select anything at all - vanilla will not draw a collar on an
     * animal that is not also tamed. An axis names only the models that answer to it: applying it to
     * a model that ignores it renders the default under a name claiming otherwise, which inflates the
     * coverage number with references that cannot fail.
     *
     * @param ctx the sweep context, for the registries some option lists come from
     * @param type the entity type
     * @return its selections, empty for a type no axis reaches
     */
    public static List<List<Appearance.Trait>> selections(SweepContext ctx, EntityType<?> type) {
        List<List<Appearance.Trait>> selections = new ArrayList<>();
        // Wolf is the only model whose data declares a behavioural state, and the wild state is its
        // default, so only the other two are references.
        if (type == EntityType.WOLF) select(selections, TraitAxis.STATE, TraitAxis.ANGRY, TraitAxis.TAME);
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
                    select(selections, TraitAxis.PATTERN, pattern.getSerializedName());
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
            select(selections, TraitAxis.TOGGLE, toggle);
        // The size options exclude the model's own default, unlike the coat options, so each model
        // contributes only the sizes it is not already rendered at.
        if (type == EntityType.SLIME || type == EntityType.MAGMA_CUBE)
            select(selections, TraitAxis.SIZE, "medium", "large");
        if (type == EntityType.SALMON) select(selections, TraitAxis.SIZE, "small", "large");
        if (type == EntityType.PUFFERFISH) select(selections, TraitAxis.SIZE, "small", "medium");
        // An armour stand's small form is a size for the same reason a salmon's is: a second mesh
        // vanilla bakes and picks between at submit. Vanilla routes the flag through isBaby to reach
        // the armour layer, then carves the stand back out of the aged-down sheets - so the stand
        // wears its full-size sheets and draws its trim, which is what the asset side models.
        if (type == EntityType.ARMOR_STAND) select(selections, TraitAxis.SIZE, "small");
        // Every dye, not one representative: sixteen renders of an ARGB multiply cost seconds, and a
        // sampled axis cannot say which of the sixteen a regression moved.
        if (type == EntityType.SHEEP) selectDyes(selections, TraitAxis.WOOL_COLOR);
        if (type == EntityType.TROPICAL_FISH) {
            selectDyes(selections, TraitAxis.BASE_COLOR);
            selectDyes(selections, TraitAxis.PATTERN_COLOR);
        }
        // A collar needs a tamed subject before vanilla draws it at all. That is invisible on a cat
        // and not on a wolf, whose texture changes with taming - so the wolf's collar references name
        // the tame state as well, and describe what they are rather than under-describing it.
        if (type == EntityType.CAT) selectDyes(selections, TraitAxis.COLLAR_COLOR);
        if (type == EntityType.WOLF)
            for (DyeColor dye : DyeColor.values())
                selections.add(List.of(
                    new Appearance.Trait(TraitAxis.COLLAR_COLOR.token(), dye.getSerializedName()),
                    new Appearance.Trait(TraitAxis.STATE.token(), TraitAxis.TAME)));
        // Equipment is named by the slot and left at the layer's own default material, which is what
        // the model form declares and what a reference with no material after the slot means.
        for (Worn worn : EQUIPMENT.getOrDefault(type, List.of()))
            select(selections, TraitAxis.EQUIP, worn.name());
        if (HUMANOID_ARMOR.contains(type)) select(selections, TraitAxis.ARMOR, "iron");
        CARRIED.getOrDefault(type, List.of()).forEach(block ->
            select(selections, TraitAxis.CARRIED, block));
        // The two dye samples, each with the undyed reference beside it as its control. A wolf's
        // dyed layer is the interesting one: vanilla ships it with no colour to fall back on, so it
        // draws nothing at all until a dye is set.
        if (type == EntityType.WOLF || type == EntityType.HORSE)
            selections.add(List.of(
                new Appearance.Trait(TraitAxis.EQUIP.token(), "body"),
                new Appearance.Trait(TraitAxis.EQUIPMENT_COLOR.token(),
                    type == EntityType.WOLF ? "red" : "blue")));
        selections.addAll(pairs(type));
        return selections;
    }

    /**
     * One wearable, as the appearance names it and as vanilla fills it.
     *
     * @param name the slot the reference names
     * @param slot the vanilla slot the item goes in
     * @param item the item that produces the layer's own default material
     */
    record Worn(String name, EquipmentSlot slot, Item item) {}

    /**
     * What each model is swept wearing.
     *
     * <p>Nothing about a slot says which item fills it: a llama wears a carpet, a wolf wears armour
     * named for the scute it is crafted from, a nautilus wears copper, and a happy ghast wears a
     * harness. Every entry is the item behind that layer's declared default material, so a reference
     * that names the slot and no material renders what the model form says it should.
     */
    private static final Map<EntityType<?>, List<Worn>> EQUIPMENT = equipment();

    private static Map<EntityType<?>, List<Worn>> equipment() {
        Map<EntityType<?>, List<Worn>> worn = new LinkedHashMap<>();
        Worn saddle = new Worn("saddle", EquipmentSlot.SADDLE, Items.SADDLE);
        for (EntityType<?> type : List.of(EntityType.CAMEL, EntityType.CAMEL_HUSK, EntityType.DONKEY,
            EntityType.HORSE, EntityType.MULE, EntityType.NAUTILUS, EntityType.PIG,
            EntityType.SKELETON_HORSE, EntityType.STRIDER, EntityType.ZOMBIE_HORSE,
            EntityType.ZOMBIE_NAUTILUS))
            worn.computeIfAbsent(type, key -> new ArrayList<>()).add(saddle);
        body(worn, Items.LEATHER_HORSE_ARMOR, EntityType.HORSE, EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);
        body(worn, Items.COPPER_NAUTILUS_ARMOR, EntityType.NAUTILUS, EntityType.ZOMBIE_NAUTILUS);
        body(worn, Items.WHITE_CARPET, EntityType.LLAMA, EntityType.TRADER_LLAMA);
        body(worn, Items.WHITE_HARNESS, EntityType.HAPPY_GHAST);
        body(worn, Items.WOLF_ARMOR, EntityType.WOLF);
        return Map.copyOf(worn);
    }

    private static void body(Map<EntityType<?>, List<Worn>> worn, Item item, EntityType<?>... types) {
        for (EntityType<?> type : types)
            worn.computeIfAbsent(type, key -> new ArrayList<>())
                .add(new Worn("body", EquipmentSlot.BODY, item));
    }

    /**
     * Returns what a model wears in one named slot.
     *
     * @param type the entity type
     * @param slot the slot the reference names
     * @return the wearable, empty for a model that wears nothing there
     */
    static Optional<Worn> equipment(EntityType<?> type, String slot) {
        return EQUIPMENT.getOrDefault(type, List.of()).stream()
            .filter(worn -> worn.name().equals(slot))
            .findFirst();
    }

    /** The models whose data classifies their armour layer as humanoid. */
    private static final Set<EntityType<?>> HUMANOID_ARMOR = Set.of(
        EntityType.ARMOR_STAND, EntityType.BOGGED, EntityType.DROWNED, EntityType.GIANT,
        EntityType.HUSK, EntityType.PARCHED, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
        EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.ZOMBIE,
        EntityType.ZOMBIE_VILLAGER, EntityType.ZOMBIFIED_PIGLIN);

    /**
     * What each model carries, or the sentinel that drops what it carries by default.
     *
     * <p>The mooshroom is deliberately absent. Its census row asks for a mooshroom without mushrooms,
     * and vanilla has no such adult: the renderer fills the mushroom model unconditionally from the
     * variant, shearing one replaces the entity with a cow rather than clearing a flag, and the only
     * suppression is being a baby. A reference for it would be a copy of the plain mooshroom under a
     * name claiming otherwise.
     *
     * <p>The enderman's block is the one arbitrary value in the whole set - its overlay declares no
     * block at all - and a full cube was chosen over a cross model so the reference exercises the
     * block-overlay transform rather than a flat plane.
     */
    private static final Map<EntityType<?>, List<String>> CARRIED = Map.of(
        EntityType.ENDERMAN, List.of("minecraft__grass_block"),
        EntityType.IRON_GOLEM, List.of("minecraft__poppy"),
        EntityType.SNOW_GOLEM, List.of(TraitAxis.CARRIED_NONE));

    /**
     * The selections that only mean something together, which is why they are named as pairs rather
     * than left to a cross product.
     *
     * <p>Two arms, and each is a rule one axis cannot reach on its own. A villager's hat is decided by
     * its biome hat <em>and</em> its profession hat together, so the branch that hides the head needs
     * one of each. Its trade badge is drawn only for a profession that earns one, so the badge axis
     * says nothing until a job is selected alongside it.
     */
    private static List<List<Appearance.Trait>> pairs(EntityType<?> type) {
        List<List<Appearance.Trait>> pairs = new ArrayList<>();
        if (type == EntityType.VILLAGER)
            for (String profession : List.of("butcher", "farmer"))
                pairs.add(List.of(
                    new Appearance.Trait(TraitAxis.VILLAGER_TYPE.token(), "desert"),
                    new Appearance.Trait(TraitAxis.VILLAGER_PROFESSION.token(), profession)));
        if (type == EntityType.VILLAGER || type == EntityType.ZOMBIE_VILLAGER)
            for (String badge : TraitAxis.BADGES)
                pairs.add(List.of(
                    new Appearance.Trait(TraitAxis.VILLAGER_PROFESSION.token(), "farmer"),
                    new Appearance.Trait(TraitAxis.VILLAGER_LEVEL.token(), badge)));
        return pairs;
    }

    /**
     * The selections rendered on a type's baby rather than on its adult.
     *
     * <p>Kept apart from the adult selections because age is not a selection like the others - it
     * chooses the mesh, and everything else is chosen on top of whichever mesh that leaves. These four
     * are the only references that reach the baby villager's own arms: the robe its type pass draws,
     * the head its profession pass clears, and the hat lookup that reads an adult's sidecar for a
     * subject that has none - the one arm neither a single axis nor an adult pair can discriminate.
     *
     * @param ctx the sweep context, for the registries some option lists come from
     * @param type the entity type
     * @return its baby selections, empty for a type whose baby no axis reaches
     */
    public static List<List<Appearance.Trait>> babySelections(SweepContext ctx, EntityType<?> type) {
        if (type != EntityType.VILLAGER) return List.of();
        Appearance.Trait desert = new Appearance.Trait(TraitAxis.VILLAGER_TYPE.token(), "desert");
        Appearance.Trait butcher = new Appearance.Trait(TraitAxis.VILLAGER_PROFESSION.token(), "butcher");
        return List.of(
            List.of(desert),
            List.of(new Appearance.Trait(TraitAxis.VILLAGER_PROFESSION.token(), "farmer")),
            List.of(butcher),
            List.of(desert, butcher));
    }

    /**
     * The bone toggles each model carries, named as the appearance names them rather than as the mesh
     * names the bones they reach. A toggle names the thing rather than a state of it, so these read as
     * bare nouns and the direction lives in the model's derived default - a toggle reference renders
     * the flag's <em>other</em> state, which no name asserting one could stay true to. {@code chest}
     * reaching {@code left_chest} / {@code right_chest} is the shape; {@code base_plate} matching its
     * one bone exactly is a coincidence rather than the rule.
     *
     * <p>The bogged's shear toggle is not here: it is driven by the sheared flag rather than selected
     * on its own, so counting it twice would be counting one reference twice.
     */
    private static final Map<EntityType<?>, List<String>> BONE_TOGGLES = Map.of(
        EntityType.ARMOR_STAND, List.of("arms", "base_plate"),
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
     * the flag has to be written onto the part. Three of them read backwards from the rest: a goat is
     * horned, a bogged is mushroomed, and an armor stand keeps its base plate until something says
     * otherwise - the last because the bit vanilla stores means "no base plate" rather than "show" it.
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

    /**
     * The bones one toggle name reaches, and what selecting it does to them.
     *
     * <p><b>A toggle names the state its subject is NOT resting in</b>, so every entry here is the
     * opposite of what the subject draws with nothing selected. A stand rests without arms and keeps
     * its base plate, a goat rests horned, a donkey rests without its chest - so selecting reads
     * true, false, false, true in that order, and a pin that answered a subject's own resting side
     * would render a second copy of the reference beside it rather than the other appearance.
     */
    private static Map<String, Boolean> toggleBones(String toggle) {
        return switch (toggle) {
            case "arms" -> Map.of("left_arm", true, "right_arm", true);
            case "base_plate" -> Map.of("base_plate", false);
            // A bee rests WITH its sting: BeeRenderState builds hasStinger at one, nothing pins the
            // bone, and the cancelled setupAnim leaves ModelPart's constructed visible standing. So
            // the toggle is the bee that has stung, and pinning it true drew the resting bee twice.
            case "stinger" -> Map.of("stinger", false);
            case "chest" -> Map.of("left_chest", true, "right_chest", true);
            case "horn" -> Map.of("left_horn", false, "right_horn", false);
            case "egg" -> Map.of("egg_belly", true);
            default -> throw new IllegalArgumentException("No bone toggle named '" + toggle + "'");
        };
    }

    /** Appends one reference per option of a single axis. */
    private static void select(List<List<Appearance.Trait>> selections, TraitAxis axis, String... options) {
        for (String option : options) selections.add(List.of(new Appearance.Trait(axis.token(), option)));
    }

    /** Appends one reference per vanilla dye, for an axis that takes one. */
    private static void selectDyes(List<List<Appearance.Trait>> selections, TraitAxis axis) {
        for (DyeColor dye : DyeColor.values())
            selections.add(List.of(new Appearance.Trait(axis.token(), dye.getSerializedName())));
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
