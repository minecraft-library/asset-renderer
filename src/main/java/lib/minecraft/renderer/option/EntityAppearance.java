package lib.minecraft.renderer.option;

import lib.minecraft.renderer.option.spec.DyeColor;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The entity-specific axis selections for a single {@code EntityRenderer} invocation, held as one
 * cohesive value on {@link EntityOptions#getAppearance()} so {@code EntityOptions} does not accrete a
 * loose field per axis. Each selection maps onto the {@code entity_models.json} model form: the
 * typed {@link #getAge() age} axis, the option-sourced dyed {@link #tint(TintAxis) collar} tint, and the
 * id-encoded / option-encoded string axes ({@link #getState() state}, {@link #getCarried() carried})
 * whose valid values are declared per-entity in the model JSON rather than a hard-coded enum.
 *
 * <p>Every axis is empty / default unless explicitly set, so the default appearance has no effect on
 * the render. An axis a given entity does not support is simply ignored at render (an unknown
 * {@code state} falls back to the default texture).
 */
@Getter
@Builder(toBuilder = true)
public class EntityAppearance {

    /** The {@link #getState() state} token a tamed subject selects - the wolf's tame coat. */
    private static final @NotNull String TAME_STATE = "tame";

    /**
     * The collar dye an untouched tamed subject wears, matching the {@code DEFAULT_COLLAR_COLOR}
     * both collar-bearing entities declare.
     */
    private static final @NotNull DyeColor DEFAULT_COLLAR_COLOR = DyeColor.Vanilla.RED;

    /**
     * Age selector. {@link Age#BABY} renders the entity's distinct baby mesh when it has one;
     * {@link Age#ADULT} (default) renders the adult mesh. Only affects entities with a dedicated
     * baby model.
     */
    @lombok.Builder.Default
    private final @NotNull Age age = Age.ADULT;

    /**
     * Behavioural-state selector for entities that ship per-state textures (wolf {@code "tame"} /
     * {@code "angry"}; the default {@code "wild"} is equivalent to empty). Swaps the base texture to
     * the matching state entry when the resolved entity carries one, else the default texture is
     * used.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> state = Optional.empty();

    /**
     * Variant selector for entities whose coat / colour {@code variant} axis is option-encoded (cow
     * temperate / cold / warm, wolf coats, cat breeds, ...). Selects that option's baked mesh + coat
     * texture in place of the model's default coat; empty (default) renders the default coat. Ignored
     * by entities with no variant axis, or while the axis is id-encoded (each coat a first-class render id).
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> variant = Optional.empty();

    /**
     * Carried-block selector. Two roles depending on the entity's block overlays:
     *
     * <ul>
     *   <li>For always-present body decorations (snow golem's carved pumpkin, mooshroom's
     *       mushrooms): empty (default) renders the authored blocks; {@code "none"} drops them (a
     *       sheared snow golem), removing both their geometry and their canvas-bounds contribution.</li>
     *   <li>For caller-selected held blocks (enderman carried block, iron golem flower): a block id
     *       ({@code "minecraft:poppy"}) renders that block in the entity's selectable overlay slot;
     *       empty (default) and {@code "none"} draw no held block, matching vanilla's empty-handed
     *       default. See {@link #selectedCarriedBlock()}.</li>
     * </ul>
     */
    @lombok.Builder.Default
    private final @NotNull Optional<String> carried = Optional.empty();

    /**
     * Body-size selector for entities with a {@code size} axis (pufferfish deflated / medium /
     * fully-puffed meshes). Selects one of the entity's distinct baked size meshes; empty (default)
     * keeps the entity's canonical mesh (pufferfish {@link Size#LARGE}, the fully-puffed silhouette
     * vanilla's renderer shows for the settled reference). Ignored by entities without a size axis.
     */
    @lombok.Builder.Default
    private final @NotNull Optional<Size> size = Optional.empty();

    /**
     * The selected dye per {@link TintAxis tint axis} - the body base tint ({@link TintAxis#BASE},
     * tropical fish) and each named overlay tint ({@link TintAxis#WOOL} sheep wool,
     * {@link TintAxis#PATTERN} tropical fish pattern, {@link TintAxis#COLLAR} wolf / cat collar).
     * An axis absent from the map uses its target's baked default (the model {@code base_tint} or
     * the overlay's {@code tint_color}), so the default appearance is unchanged; a
     * present axis multiplies its target by the dye's {@link DyeColor#argb() ARGB}. One map rather
     * than a loose {@link Optional} field per dye axis - see {@link TintAxis}.
     */
    @lombok.Builder.Default
    private final @NotNull Map<TintAxis, DyeColor> tints = Map.of();

    /**
     * Tropical-fish pattern selector. When present and the resolved entity carries a
     * {@code texture_by: pattern} overlay (the tropical fish pattern), that overlay draws the
     * selected pattern's texture instead of its baked default; empty (default) renders the baked
     * pattern ({@code KOB}).
     */
    @lombok.Builder.Default
    private final @NotNull Optional<TropicalFishPattern> pattern = Optional.empty();

    /**
     * Horse-marking selector - the white socks / blaze / patches drawn over the coat colour. When set
     * to a non-{@link HorseMarking#NONE} value and the resolved entity supports markings (the horse),
     * a same-geometry translucent overlay draws that marking's texture over the coat;
     * {@link HorseMarking#NONE} (default) draws no marking. Ignored by entities without a marking layer.
     */
    @lombok.Builder.Default
    private final @NotNull HorseMarking markings = HorseMarking.NONE;

    /**
     * Iron-golem crackiness (damage) selector. When set to a non-{@link IronGolemCrackiness#NONE}
     * level and the resolved entity carries a {@code texture_by: crackiness} overlay (the iron
     * golem), that overlay draws the level's crack texture over the body; {@link
     * IronGolemCrackiness#NONE} (default) draws no cracks. Ignored by entities without a crackiness overlay.
     */
    @lombok.Builder.Default
    private final @NotNull IronGolemCrackiness crackiness = IronGolemCrackiness.NONE;

    /**
     * Copper-golem weathering selector. When the resolved entity supports weathering (the copper
     * golem), this swaps both the body base texture and its emissive eye overlay to the selected
     * oxidation state's textures; {@link CopperWeathering#UNAFFECTED} (default) renders the
     * freshly-placed copper textures. Ignored by entities without weathering.
     */
    @lombok.Builder.Default
    private final @NotNull CopperWeathering weathering = CopperWeathering.UNAFFECTED;

    /**
     * Villager / zombie-villager biome type - the robe texture forming the base clothing pass. When
     * the resolved entity carries a {@code texture_by: type} overlay (the villager profession layer),
     * that overlay draws the selected type's {@code <prefix>/type/<biome>} robe, or its
     * {@code <prefix>/baby/<biome>} robe once {@link #getAge() age} selects {@link Age#BABY};
     * {@link VillagerType#PLAINS} (default) resolves to the baked {@code type/plains} robe. Ignored by
     * entities without a villager profession layer.
     */
    @lombok.Builder.Default
    private final @NotNull VillagerType villagerType = VillagerType.PLAINS;

    /**
     * Villager / zombie-villager profession - the clothes + hat pass over the biome robe. When set to
     * a non-{@link VillagerProfession#NONE} value and the resolved entity carries a
     * {@code texture_by: profession} overlay, that overlay draws the profession's
     * {@code <prefix>/profession/<name>} texture; {@link VillagerProfession#NONE} (default) draws no
     * profession pass. Ignored by entities without a villager profession layer.
     */
    @lombok.Builder.Default
    private final @NotNull VillagerProfession villagerProfession = VillagerProfession.NONE;

    /**
     * Villager / zombie-villager trade level badge - the small emblem over the profession clothes.
     * When set to a non-{@link VillagerLevel#NONE} tier and the selected
     * {@link #villagerProfession profession} {@link VillagerProfession#drawsBadge() draws a badge}
     * (a real job), the resolved entity's {@code texture_by: profession_level} overlay draws that
     * tier's {@code <prefix>/profession_level/<badge>} texture; {@link VillagerLevel#NONE} (default)
     * or a {@code NONE} / {@code NITWIT} profession draws no badge. Ignored by entities without a
     * villager profession layer.
     */
    @lombok.Builder.Default
    private final @NotNull VillagerLevel villagerLevel = VillagerLevel.NONE;

    /**
     * Whether the entity renders sheared. When {@code true} the resolved definition drops its
     * shearable overlays (the sheep wool) - both the rendered geometry and its canvas-bounds
     * contribution; {@code false} (default) renders the entity's wool.
     */
    @lombok.Builder.Default
    private final boolean sheared = false;

    /**
     * Whether the entity renders charged (lightning-struck). When {@code true} the resolved definition
     * keeps its charged-only overlay (the creeper energy swirl); {@code false} (default) drops it. Only
     * affects entities with a charged overlay (the creeper).
     */
    @lombok.Builder.Default
    private final boolean charged = false;

    /**
     * The set of bone-toggle names to un-hide for entities with toggleable bones (donkey / mule /
     * llama {@code chest}). Each name matches a model-form {@code bone_toggles} key; a toggle a
     * given entity does not declare is ignored. Empty (default) leaves every toggleable bone hidden.
     */
    @lombok.Builder.Default
    private final @NotNull Set<String> toggles = Set.of();

    /**
     * Equipment selection keyed by slot ({@code saddle} / {@code body}) for entities with equipment
     * overlays (pig/horse/camel/strider/happy_ghast/nautilus saddle; horse/nautilus/wolf armor). The
     * value is the material/asset ({@code leather}, {@code iron}, {@code diamond}; {@code saddle} for
     * the single saddle item), or an empty string to use the layer's default material (leather armor,
     * the saddle). A slot a given entity does not offer is ignored; empty (default) renders no
     * equipment. See {@link #equipmentMaterial(String)}.
     */
    @lombok.Builder.Default
    private final @NotNull java.util.Map<String, String> equipment = java.util.Map.of();

    /**
     * Whether the entity wears an elytra. When {@code true} the two elytra wings render on the back as
     * a model overlay; {@code false} (default) draws no wings. Only meaningful for the humanoid roster
     * that can equip a chest item; a headless render draws the static {@code minecraft:elytra} wing
     * texture (there is no wearer cape / elytra skin source).
     */
    @lombok.Builder.Default
    private final boolean elytra = false;

    /**
     * The dye selected for a {@link TintAxis tint axis}, or empty when the axis uses its baked
     * default.
     *
     * @param axis the tint axis to look up
     * @return the selected dye for {@code axis}, or empty
     */
    public @NotNull Optional<DyeColor> tint(@NotNull TintAxis axis) {
        return Optional.ofNullable(this.tints.get(axis));
    }

    /**
     * Whether this appearance selects the baby mesh.
     *
     * @return {@code true} when {@link #getAge() age} is {@link Age#BABY}
     */
    public boolean isBaby() {
        return this.age == Age.BABY;
    }

    /**
     * Whether this appearance selects the tamed state.
     *
     * @return {@code true} when {@link #getState() state} selects {@code tame}
     */
    public boolean isTamed() {
        return this.state.filter(TAME_STATE::equals).isPresent();
    }

    /**
     * The dye the collar draws with, or empty when no collar is worn. Vanilla ties the collar to
     * tameness rather than to dyeing - a tamed subject always wears one, in the untouched default red
     * until it is dyed - so a tamed appearance resolves a colour with {@link TintAxis#COLLAR} left
     * unselected. Selecting that axis without the state resolves one too: vanilla cannot dye an
     * untamed subject's collar, so naming a collar dye is itself a statement that the subject is tamed.
     *
     * @return the collar dye, or empty when the subject is neither tamed nor collar-dyed
     */
    public @NotNull Optional<DyeColor> collarTint() {
        if (this.isTamed()) return Optional.of(this.tints.getOrDefault(TintAxis.COLLAR, DEFAULT_COLLAR_COLOR));
        return this.tint(TintAxis.COLLAR);
    }

    /**
     * Whether the carried block overlays should be dropped (a sheared snow golem, an empty-handed
     * enderman).
     *
     * @return {@code true} when {@link #getCarried() carried} is {@code "none"}
     */
    public boolean dropsCarried() {
        return this.carried.filter("none"::equals).isPresent();
    }

    /**
     * The block id to render in a {@code selectable} block overlay (enderman carried block, iron
     * golem flower), or empty when no held block is selected. A selectable overlay renders only when
     * this is present; the default (empty) and {@code "none"} both leave the entity empty-handed.
     *
     * @return the selected carried block id, or empty for the default / dropped state
     */
    public @NotNull Optional<String> selectedCarriedBlock() {
        return this.carried.filter(id -> !"none".equals(id));
    }

    /**
     * The selected material for an equipment {@code slot}, or empty when the slot is not equipped.
     * A present-but-blank value means "use the layer's default material" (leather armor, the saddle).
     *
     * @param slot the equipment slot ({@code saddle} / {@code body})
     * @return the selected material (possibly blank for "default"), or empty when the slot is unequipped
     */
    public @NotNull Optional<String> equipmentMaterial(@NotNull String slot) {
        return Optional.ofNullable(this.equipment.get(slot));
    }

    /**
     * Builds an appearance with every axis at its default (adult, no state / carried / collar).
     *
     * @return the default appearance
     */
    public static @NotNull EntityAppearance defaults() {
        return builder().build();
    }

    /**
     * Opens a builder seeded from this instance's current values, for deriving a variant with a few
     * axes changed.
     *
     * @return a builder pre-populated from this instance
     */
    public @NotNull EntityAppearanceBuilder mutate() {
        return this.toBuilder();
    }

}
