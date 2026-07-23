package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.WeatheringCopper;

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
        void persist(String value, CompoundTag payload) {
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
        void persist(String value, CompoundTag payload) {
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
        void persist(String value, CompoundTag payload) {
            villagerData(payload).putString("type", Identifier.withDefaultNamespace(value).toString());
        }
    },

    /** A villager's profession, which rides in the same compound as its type. */
    VILLAGER_PROFESSION("villager_profession") {
        @Override
        void persist(String value, CompoundTag payload) {
            villagerData(payload).putString("profession", Identifier.withDefaultNamespace(value).toString());
        }
    };

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
     * @param value the option selected
     * @param payload the payload being assembled, already carrying any coat
     */
    void persist(String value, CompoundTag payload) {}

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
