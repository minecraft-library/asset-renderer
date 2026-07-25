package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.WeatheringCopper;

import java.util.List;
import java.util.Locale;

/**
 * How each appearance axis reaches a vanilla entity.
 *
 * <p>Two mechanisms, and which one an axis uses is vanilla's choice rather than this harness's.
 * {@link #persist} writes into the payload the deserialiser reads, for the axes whose setters vanilla
 * declares private; {@link #apply} calls the setter, for the axes vanilla exposes. Every axis
 * implements exactly one of the two and inherits the other as a no-op.
 *
 * <p>The payload is assembled before the entity is built rather than written a tag at a time, because
 * vanilla packs more than one axis into a single tag - a horse's coat and its markings share an
 * integer, and a tropical fish's pattern shares one with both of its colours.
 */
enum TraitAxis {

    /**
     * A wolf's behavioural state, which vanilla derives from whether it is tamed and whether it is
     * still angry rather than storing as a state of its own.
     *
     * <p>Anger is read as an end time compared against the world clock, so a time far enough ahead is
     * angry without anything ever ticking. Taming is left off for the angry state deliberately:
     * vanilla picks the tame texture ahead of the angry one, so a wolf that is both renders as tame.
     */
    STATE("state") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            switch (value) {
                case TAME -> {
                    if (entity instanceof TamableAnimal tamable) tamable.setTame(true, false);
                }
                case ANGRY -> {
                    if (entity instanceof NeutralMob neutral) neutral.setPersistentAngerEndTime(Long.MAX_VALUE);
                }
                default -> throw new IllegalArgumentException("No vanilla state named '" + value + "'");
            }
        }
    },

    /**
     * An iron golem's crack stage, which vanilla derives from how hurt it is rather than storing.
     *
     * <p>The stage is read off the health fraction, so the reference is produced by wounding the
     * golem to the middle of the band each stage occupies - far enough from either edge that a
     * rounding difference cannot move it into a neighbouring stage.
     */
    CRACKINESS("crackiness") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (!(entity instanceof LivingEntity living)) return;
            float fraction = switch (value) {
                case "low" -> 0.6f;
                case "medium" -> 0.35f;
                case "high" -> 0.1f;
                default -> throw new IllegalArgumentException("No vanilla crack stage named '" + value + "'");
            };
            living.setHealth(living.getMaxHealth() * fraction);
        }
    },

    /** A copper golem's oxidation stage, which vanilla exposes as a setter. */
    WEATHERING("weathering") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (!(entity instanceof CopperGolem golem)) return;
            for (WeatheringCopper.WeatherState state : WeatheringCopper.WeatherState.values())
                if (state.getSerializedName().equals(value)) {
                    golem.setWeatherState(state);
                    return;
                }
            throw new IllegalArgumentException("No vanilla weather state named '" + value + "'");
        }
    },

    /**
     * A horse's face and leg markings, which vanilla packs into the high byte of the same integer its
     * coat occupies - so the selection is merged into whatever the coat already wrote rather than
     * replacing it.
     */
    MARKINGS("markings") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            for (Markings markings : Markings.values())
                if (markings.name().toLowerCase(Locale.ROOT).equals(value)) {
                    int coat = payload.getIntOr(HORSE_VARIANT, 0) & 0xFF;
                    payload.putInt(HORSE_VARIANT, coat | ((markings.getId() << 8) & 0xFF00));
                    return;
                }
            throw new IllegalArgumentException("No vanilla horse marking named '" + value + "'");
        }
    },

    /**
     * A tropical fish's pattern, which also chooses its body: six of the twelve are drawn on a small
     * mesh and six on a large one.
     *
     * <p>Vanilla packs the pattern into the low half of one integer and the two dye colours into the
     * two bytes above it, so the selection is merged into the low half and leaves the colours where
     * they are.
     */
    PATTERN("pattern") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            for (TropicalFish.Pattern pattern : TropicalFish.Pattern.values())
                if (pattern.getSerializedName().equals(value)) {
                    int colours = payload.getIntOr(FISH_VARIANT, 0) & 0xFFFF0000;
                    payload.putInt(FISH_VARIANT, colours | (pattern.getPackedId() & 0xFFFF));
                    return;
                }
            throw new IllegalArgumentException("No vanilla fish pattern named '" + value + "'");
        }
    },

    /** A villager's biome type, which rides in the compound vanilla persists its trade data under. */
    VILLAGER_TYPE("villager_type") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            villagerData(payload).putString("type", Identifier.withDefaultNamespace(value).toString());
        }
    },

    /** A villager's profession, which rides in the same compound as its type. */
    VILLAGER_PROFESSION("villager_profession") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            villagerData(payload).putString("profession", Identifier.withDefaultNamespace(value).toString());
        }
    },

    /**
     * A villager's trade level, which decides which badge is drawn over its work clothes.
     *
     * <p>Vanilla numbers the levels from one and names the badges; an unnamed level deserialises to
     * one and a level below it clamps back up, so the lowest badge is what every job villager wears
     * whether or not anything selects it.
     */
    VILLAGER_LEVEL("villager_level") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            int level = BADGES.indexOf(value) + 1;
            if (level == 0) throw new IllegalArgumentException("No vanilla trade badge named '" + value + "'");
            villagerData(payload).putInt("level", level);
        }
    },

    /**
     * Whether the subject has been sheared, which two models answer to and answer differently: a sheep
     * loses a whole overlay layer and the bogged loses the mushrooms growing out of its skull. Only
     * the sheep's half is an entity setter - the mushrooms are bones, and bones are pinned.
     */
    SHEARED("sheared") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof Sheep sheep) sheep.setSheared(true);
            else if (entity instanceof Bogged bogged) bogged.setSheared(true);
        }
    },

    /**
     * Whether the subject is charged, which vanilla stores for a creeper and derives for a wither.
     *
     * <p>A creeper has no setter at all - being struck by lightning is the only thing that sets it, so
     * the flag is persisted instead. A wither counts as charged below half health, so the reference is
     * produced by wounding it there.
     */
    CHARGED("charged") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            payload.putBoolean("powered", true);
        }

        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof WitherBoss wither) wither.setHealth(wither.getMaxHealth() / 2.0f);
        }
    },

    /** A bone the mesh carries but the default render does not show, or shows and this one does not. */
    TOGGLE("toggle") {},

    /**
     * How big the subject is, which vanilla splits four ways: a slime carries a size, a salmon
     * carries a named one, a pufferfish carries how far it has puffed up, and an armour stand
     * carries a flag.
     *
     * <p>The stand's is persisted rather than set because its setter is private, and it is the
     * reason this axis is asked which type it is applying to - {@code small} means a different tag
     * on a stand than on a salmon, and writing both would put a key on each that nothing reads.
     */
    SIZE("size") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            if (type == EntityType.ARMOR_STAND) {
                if (!SMALL.equals(value))
                    throw new IllegalArgumentException("An armour stand has no size named '" + value + "'");
                payload.putBoolean("Small", true);
                return;
            }
            SALMON_SIZES.stream().filter(value::equals).findFirst()
                .ifPresent(size -> payload.putString("type", size));
        }

        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof Slime slime) slime.setSize(switch (value) {
                case "small" -> 1;
                case "medium" -> 2;
                case "large" -> 4;
                default -> throw new IllegalArgumentException("No vanilla slime size named '" + value + "'");
            }, false);
            else if (entity instanceof Pufferfish pufferfish) pufferfish.setPuffState(switch (value) {
                case "small" -> 0;
                case "medium" -> 1;
                case "large" -> Pufferfish.STATE_FULL;
                default -> throw new IllegalArgumentException("No vanilla puff state named '" + value + "'");
            });
        }
    },

    /**
     * The dye on a wolf's or a cat's collar.
     *
     * <p>Vanilla will not draw a collar on an animal that is not tamed - the render state carries a
     * colour only for a tame one - so the selection tames the subject as well as dyeing it. That is
     * invisible on a cat and is not on a wolf, whose texture changes with taming, which is why a
     * wolf's collar references name the tame state too.
     */
    COLLAR_COLOR("collar_color") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            payload.putByte("CollarColor", (byte) dye(value).getId());
        }

        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof TamableAnimal tamable) tamable.setTame(true, false);
        }
    },

    /** The dye on a sheep's wool, which vanilla exposes as a setter. */
    WOOL_COLOR("wool_color") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof Sheep sheep) sheep.setColor(dye(value));
        }
    },

    /** A tropical fish's body dye, which vanilla keeps in the third byte of its packed variant. */
    BASE_COLOR("base_color") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            packFishColour(payload, dye(value).getId(), 16);
        }
    },

    /** A tropical fish's pattern dye, which vanilla keeps in the fourth byte of the same integer. */
    PATTERN_COLOR("pattern_color") {
        @Override
        void persist(EntityType<?> type, String value, CompoundTag payload) {
            packFishColour(payload, dye(value).getId(), 24);
        }
    },

    /**
     * A worn saddle or body covering, put on through the same setter a server uses when a mob spawns
     * with gear.
     *
     * <p>The slot names the axis carries are the asset side's, and the item behind each is vanilla's
     * and differs per model - a llama wears a carpet, a wolf wears armour named for the scute it is
     * crafted from, a nautilus wears copper. The roster holds the pairing, because nothing about the
     * slot says which item fills it.
     */
    EQUIP("equip") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (!(entity instanceof LivingEntity living)) return;
            String slot = value.contains(".") ? value.substring(0, value.indexOf('.')) : value;
            EntityRoster.equipment(entity.getType(), slot)
                .ifPresent(worn -> living.setItemSlot(worn.slot(), new ItemStack(worn.item())));
        }
    },

    /** A full set of humanoid armour, one material across all four worn slots. */
    ARMOR("armor") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (!(entity instanceof LivingEntity living)) return;
            if (!"iron".equals(value))
                throw new IllegalArgumentException("No armour material named '" + value + "'");
            living.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            living.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            living.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            living.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        }
    },

    /**
     * The dye on what the subject is wearing, applied to the stack the equipment selection already
     * put on it - which is why it is spelled after that selection and sorts after it.
     */
    EQUIPMENT_COLOR("equipment_color") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (!(entity instanceof LivingEntity living)) return;
            ItemStack worn = living.getItemBySlot(EquipmentSlot.BODY).copy();
            if (worn.isEmpty()) return;
            worn.set(DataComponents.DYED_COLOR, new DyedItemColor(dye(value).getTextureDiffuseColor()));
            living.setItemSlot(EquipmentSlot.BODY, worn);
        }
    },

    /**
     * The block a subject carries, or the sentinel that drops the one it carries by default.
     *
     * <p>Three shapes, and the difference is vanilla's: an enderman holds whatever block it picked up,
     * an iron golem holds a poppy on a timer no world tick will start here, and a snow golem wears a
     * pumpkin it can be sheared out of.
     */
    CARRIED("carried") {
        @Override
        void apply(SweepContext ctx, String value, Entity entity) {
            if (entity instanceof EnderMan enderman) {
                // The name spells a namespace separator the way a file name can carry it.
                Identifier block = Identifier.parse(value.replace("__", ":"));
                enderman.setCarriedBlock(BuiltInRegistries.BLOCK.getValue(block).defaultBlockState());
            } else if (entity instanceof IronGolem golem) {
                // The public offer broadcasts a world event; this is the same handler that event
                // reaches, and it is the only part a never-ticked subject needs.
                golem.handleEntityEvent(OFFER_FLOWER_EVENT);
            } else if (entity instanceof SnowGolem golem) {
                golem.setPumpkin(!CARRIED_NONE.equals(value));
            }
        }
    };

    /** The value that drops a subject's default decoration rather than naming one to carry. */
    static final String CARRIED_NONE = "none";

    /** The entity event vanilla sends to start an iron golem's flower offer. */
    private static final byte OFFER_FLOWER_EVENT = 11;

    /** The sizes a salmon is persisted under, which are its only mechanism - its setter is private. */
    private static final List<String> SALMON_SIZES = List.of("small", "medium", "large");

    /** The one size an armour stand has besides its full one. */
    private static final String SMALL = "small";

    /** The trade badges, in the order vanilla numbers them from one. */
    static final List<String> BADGES = List.of("stone", "iron", "gold", "emerald", "diamond");

    /** Resolves a dye by the name vanilla serialises it under. */
    private static DyeColor dye(String value) {
        for (DyeColor dye : DyeColor.values())
            if (dye.getSerializedName().equals(value)) return dye;
        throw new IllegalArgumentException("No vanilla dye named '" + value + "'");
    }

    /** Writes one dye into its byte of the fish's packed variant, leaving the other three alone. */
    private static void packFishColour(CompoundTag payload, int id, int shift) {
        int packed = payload.getIntOr(FISH_VARIANT, 0) & ~(0xFF << shift);
        payload.putInt(FISH_VARIANT, packed | ((id & 0xFF) << shift));
    }

    /** The tag a horse's coat and markings share. */
    static final String HORSE_VARIANT = "Variant";

    /** The tag a tropical fish's pattern and its two dye colours share. */
    static final String FISH_VARIANT = "Variant";

    /** The compound a villager's type, profession and level are persisted in together. */
    private static final String VILLAGER_DATA = "VillagerData";

    /**
     * Returns the villager compound inside a payload, creating it on first use.
     *
     * <p>The three villager axes share one compound, so each has to extend what the others wrote
     * rather than replace it - and the compound has to carry a level whether or not one was selected,
     * because vanilla's codec reads all three together.
     */
    private static CompoundTag villagerData(CompoundTag payload) {
        return payload.getCompound(VILLAGER_DATA).orElseGet(() -> {
            CompoundTag data = new CompoundTag();
            payload.put(VILLAGER_DATA, data);
            return data;
        });
    }

    /** The state a tamed subject is in - the one vanilla draws a collar on. */
    static final String TAME = "tame";

    /** The state an angered subject is in. */
    static final String ANGRY = "angry";

    private final String axis;

    TraitAxis(String axis) {
        this.axis = axis;
    }

    /** The token name this axis is spelled with in a reference name. */
    String token() {
        return this.axis;
    }

    /**
     * Writes one selection into the payload vanilla's deserialiser reads.
     *
     * @param type the entity type being built, for the axes vanilla persists under a different key
     *     per subject
     * @param value the option selected
     * @param payload the payload being assembled, already carrying any coat
     */
    void persist(EntityType<?> type, String value, CompoundTag payload) {}

    /**
     * Applies one selection to the built entity.
     *
     * @param ctx the sweep context, for the registries some selections resolve against
     * @param value the option selected
     * @param entity the entity to apply it to
     */
    void apply(SweepContext ctx, String value, Entity entity) {}

    /**
     * Resolves the axis one trait selects.
     *
     * <p>An axis this harness cannot apply throws rather than being ignored. A silently dropped
     * selection renders the default under a name claiming otherwise, which is a reference that passes
     * its comparison while measuring nothing.
     *
     * @param trait the selection to resolve
     * @return the axis that knows how to apply it
     */
    static TraitAxis of(Appearance.Trait trait) {
        for (TraitAxis axis : values())
            if (axis.axis.equals(trait.axis())) return axis;
        throw new IllegalArgumentException("No vanilla mechanism for axis '" + trait.axis() + "'");
    }
}
