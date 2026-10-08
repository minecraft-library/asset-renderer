package lib.minecraft.renderer.request;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.Getter;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.appearance.AppearanceGate;
import lib.minecraft.renderer.vanilla.appearance.Axis;
import lib.minecraft.renderer.vanilla.appearance.CopperWeathering;
import lib.minecraft.renderer.vanilla.appearance.Flag;
import lib.minecraft.renderer.vanilla.appearance.HorseMarking;
import lib.minecraft.renderer.vanilla.appearance.IronGolemCrackiness;
import lib.minecraft.renderer.vanilla.appearance.Size;
import lib.minecraft.renderer.vanilla.appearance.TextureAxis;
import lib.minecraft.renderer.vanilla.appearance.TintAxis;
import lib.minecraft.renderer.vanilla.appearance.TropicalFishPattern;
import lib.minecraft.renderer.vanilla.appearance.villager.VillagerLevel;
import lib.minecraft.renderer.vanilla.appearance.villager.VillagerProfession;
import lib.minecraft.renderer.vanilla.appearance.villager.VillagerType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
@ClassBuilder
@Parity(claim = "asset-layer")
public class AppearanceOptions {

    /** The {@link #getState() state} token a tamed subject selects - the wolf's tame coat. */
    private static final @NotNull String TAME_STATE = "tame";

    /**
     * The collar dye an untouched tamed subject wears, matching the {@code DEFAULT_COLLAR_COLOR}
     * both collar-bearing entities declare.
     */
    private static final @NotNull DyeColor DEFAULT_COLLAR_COLOR = DyeColor.Vanilla.RED;

    /**
     * The path segment marking a baby robe directory, which a {@link TextureAxis#TYPE} pass' baked ref
     * carries.
     */
    private static final @NotNull String BABY_ROBE_SEGMENT = "/baby/";

    /**
     * Age selector. {@link Age#BABY} renders the entity's distinct baby mesh when it has one;
     * {@link Age#ADULT} (default) renders the adult mesh. Only affects entities with a dedicated
     * baby model.
     */
    private final @NotNull Age age = Age.ADULT;

    /**
     * Behavioural-state selector for entities that ship per-state textures (wolf {@code "tame"} /
     * {@code "angry"}; the default {@code "wild"} is equivalent to empty). Swaps the base texture to
     * the matching state entry when the resolved entity carries one, else the default texture is
     * used.
     */
    private final @NotNull Optional<String> state = Optional.empty();

    /**
     * Variant selector for entities whose coat / colour {@code variant} axis is option-encoded (cow
     * temperate / cold / warm, wolf coats, cat breeds, ...). Selects that option's baked mesh + coat
     * texture in place of the model's default coat; empty (default) renders the default coat. Ignored
     * by entities with no variant axis, or while the axis is id-encoded (each coat a first-class render id).
     */
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
    private final @NotNull Optional<String> carried = Optional.empty();

    /**
     * Body-size selector for entities with a {@code size} axis (pufferfish deflated / medium /
     * fully-puffed meshes). Selects one of the entity's distinct baked size meshes; empty (default)
     * keeps the entity's canonical mesh (pufferfish {@link Size#LARGE}, the fully-puffed silhouette
     * vanilla's renderer shows for the settled reference). Ignored by entities without a size axis.
     */
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
    private final @NotNull Map<TintAxis, DyeColor> tints = Map.of();

    /**
     * Tropical-fish pattern selector. When present and the resolved entity carries a
     * {@code texture_by: pattern} overlay (the tropical fish pattern), that overlay draws the
     * selected pattern's texture instead of its baked default; empty (default) renders the baked
     * pattern ({@code KOB}).
     */
    private final @NotNull Optional<TropicalFishPattern> pattern = Optional.empty();

    /**
     * Horse-marking selector - the white socks / blaze / patches drawn over the coat colour. When set
     * to a non-{@link HorseMarking#NONE} value and the resolved entity carries a
     * {@code texture_by: markings} overlay (the horse), that overlay draws the marking's texture over
     * the coat; {@link HorseMarking#NONE} (default) draws no marking. Ignored by entities without a
     * marking overlay.
     */
    private final @NotNull HorseMarking markings = HorseMarking.NONE;

    /**
     * Iron-golem crackiness (damage) selector. When set to a non-{@link IronGolemCrackiness#NONE}
     * level and the resolved entity carries a {@code texture_by: crackiness} overlay (the iron
     * golem), that overlay draws the level's crack texture over the body; {@link
     * IronGolemCrackiness#NONE} (default) draws no cracks. Ignored by entities without a crackiness overlay.
     */
    private final @NotNull IronGolemCrackiness crackiness = IronGolemCrackiness.NONE;

    /**
     * Copper-golem weathering selector. When the resolved entity supports weathering (the copper
     * golem), this swaps both the body base texture and its emissive eye overlay to the selected
     * oxidation state's textures; {@link CopperWeathering#UNAFFECTED} (default) renders the
     * freshly-placed copper textures. Ignored by entities without weathering.
     */
    private final @NotNull CopperWeathering weathering = CopperWeathering.UNAFFECTED;

    /**
     * Villager / zombie-villager biome type - the robe texture forming the base clothing pass. When
     * the resolved entity carries a {@code texture_by: type} overlay (the villager profession layer),
     * that overlay draws the selected type's {@code <prefix>/type/<biome>} robe, or its
     * {@code <prefix>/baby/<biome>} robe once {@link #getAge() age} selects {@link Age#BABY};
     * {@link VillagerType#PLAINS} (default) resolves to the baked {@code type/plains} robe. Ignored by
     * entities without a villager profession layer.
     */
    private final @NotNull VillagerType villagerType = VillagerType.PLAINS;

    /**
     * Villager / zombie-villager profession - the clothes + hat pass over the biome robe. When set to
     * a non-{@link VillagerProfession#NONE} value and the resolved entity carries a
     * {@code texture_by: profession} overlay, that overlay draws the profession's
     * {@code <prefix>/profession/<name>} texture; {@link VillagerProfession#NONE} (default) draws no
     * profession pass. Ignored by entities without a villager profession layer.
     */
    private final @NotNull VillagerProfession villagerProfession = VillagerProfession.NONE;

    /**
     * Villager / zombie-villager trade level badge - the small emblem over the profession clothes.
     * Whenever the selected {@link #villagerProfession profession}
     * {@link VillagerProfession#drawsBadge() draws a badge} (a real job), the resolved entity's
     * {@code texture_by: profession_level} overlay draws a tier's
     * {@code <prefix>/profession_level/<badge>} texture: the tier named here, or
     * {@link VillagerLevel#minimum() the first} when empty (default), which is what vanilla clamps
     * an unspecified level up to. A {@code NONE} or {@code NITWIT} profession draws no badge whatever
     * is named here, and neither does a baby. Ignored by entities without a villager profession
     * layer.
     */
    private final @NotNull Optional<VillagerLevel> villagerLevel = Optional.empty();

    /**
     * Whether the entity renders sheared. When {@code true} the resolved definition drops its
     * shearable overlays (the sheep wool) - both the rendered geometry and its canvas-bounds
     * contribution; {@code false} (default) renders the entity's wool.
     */
    private final boolean sheared = false;

    /**
     * Whether the entity renders charged (lightning-struck). When {@code true} the resolved definition
     * keeps its charged-only overlay (the creeper energy swirl); {@code false} (default) drops it. Only
     * affects entities with a charged overlay (the creeper).
     */
    private final boolean charged = false;

    /**
     * The selected bone toggles, by name - each flips the bones naming it from how they rest, drawing a donkey's
     * {@code chest} and hiding a goat's {@code horn}, on whichever mesh the age, shape and size axes draw. A name
     * no bone of that mesh carries is ignored; empty (default) selects none, the sheared axis adding its own
     * and a filled {@link #equipment} slot adding the one its layer names for its wearer.
     */
    private final @NotNull Set<String> toggles = Set.of();

    /**
     * Equipment selection keyed by slot ({@code saddle} / {@code body}) for entities with equipment
     * overlays (pig/horse/camel/strider/happy_ghast/nautilus saddle; horse/nautilus/wolf armor). The
     * value is the material/asset ({@code leather}, {@code iron}, {@code diamond}; {@code saddle} for
     * the single saddle item), or an empty string to use the layer's default material (leather armor,
     * the saddle). A slot a given entity does not offer is ignored; empty (default) renders no
     * equipment. See {@link #equipmentMaterial(String)}.
     */
    private final @NotNull Map<String, String> equipment = Map.of();

    /**
     * Whether the entity wears an elytra. When {@code true} the two elytra wings render on the back as
     * a model overlay; {@code false} (default) draws no wings. The pair is the half-scale one wherever
     * {@link #rendersBaby(Entity)} holds - on a baby, and on a small armour stand. The wings draw only
     * on an entity whose vanilla renderer builds the wings layer - the armour stand, the player, and
     * the skeletons, zombies and piglins; on any other entity the selection draws nothing and leaves
     * the canvas as it is. A headless render draws the static {@code minecraft:elytra} wing texture
     * (there is no wearer cape / elytra skin source).
     */
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
     * Whether the subject renders at the age vanilla calls a baby - the one flag vanilla's worn-armour
     * and wing layers both read off the wearer. A subject this appearance draws as its baby form is
     * one. So is a wearer this appearance dresses in its second armour shell, since vanilla hands that
     * shell over on the same flag: the shell's gate is the selection that reaches it - the
     * {@link #getAge() age} axis for the wearers that age, the {@link #getSize() size} axis for the
     * armour stand, whose {@code isBaby} is its {@code isSmall}. Any other subject renders at full
     * age, whatever the age axis names.
     *
     * @param definition the subject's indexed definition, before this appearance resolves it - the
     *     resolved one wears the shell the gate already picked, which names no second shell of its own
     * @return whether the subject renders as a baby
     */
    public boolean rendersBaby(@NotNull Entity definition) {
        if (this.isBaby() && definition.axes().baby().isPresent()) return true;
        return definition.humanoidArmor()
            .flatMap(Shell::alternate)
            .filter(alternate -> this.passes(alternate.when()))
            .isPresent();
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
     * The dye this appearance selects for a {@link TintAxis tint axis}, or empty when the axis' target
     * keeps its baked default. Reads the {@link #getTints() selection map}, except for the one axis
     * whose selection is derived rather than stored: {@link TintAxis#COLLAR} answers
     * {@link #collarTint()}.
     *
     * @param axis the tint axis
     * @return the selected dye, or empty
     */
    public @NotNull Optional<DyeColor> selection(@NotNull TintAxis axis) {
        return axis == TintAxis.COLLAR ? this.collarTint() : this.tint(axis);
    }

    /**
     * Whether this appearance selects one option of an appearance axis - the side of a {@code when}
     * comparison a gated row names. The {@link #getAge() age} axis rests at {@link Age#ADULT}, so an
     * untouched appearance selects that option; an unset {@link #getSize() size} selects no size
     * option, the resting mesh being a per-entity fact the option cannot see.
     *
     * @param option the axis option
     * @return {@code true} when the option is the one selected
     */
    public boolean selects(@NotNull Axis option) {
        return switch (option) {
            case Age selected -> this.age == selected;
            case Size selected -> this.size.filter(selected::equals).isPresent();
            case Flag flag -> switch (flag) {
                case SHEARED -> this.sheared;
                case CHARGED -> this.charged;
                case COLLARED -> this.collarTint().isPresent();
            };
        };
    }

    /**
     * Whether a gated row renders for this appearance. A {@link AppearanceGate.Selected} gate renders
     * when whether its option is {@link #selects selected} matches the gate's polarity; a
     * {@link AppearanceGate.TintedGate} renders once its axis selects a dye whose colour differs from
     * the row's baked tint.
     *
     * @param gate the row's gate
     * @return {@code true} when the row renders
     */
    public boolean passes(@NotNull AppearanceGate gate) {
        return switch (gate) {
            case AppearanceGate.Selected selected -> this.selects(selected.option()) == selected.expected();
            case AppearanceGate.TintedGate tinted -> tinted.axis()
                .flatMap(held -> this.tint(held).map(held::resolve))
                .filter(argb -> argb != tinted.defaultArgb())
                .isPresent();
        };
    }

    /**
     * The shell a wearer is dressed in for this appearance - its second one when this appearance passes
     * the gate that shell is reached by, else the one it is handed.
     *
     * @param shell the wearer's shell
     * @return the shell to dress the wearer in
     */
    public @NotNull Shell shell(@NotNull Shell shell) {
        return shell.alternate()
            .filter(alternate -> this.passes(alternate.when()))
            .map(Shell.Alternate::shell)
            .orElse(shell);
    }

    /**
     * Whether a pose-style row applies to this appearance - the row's {@link PoseStyle#age() age}
     * against this appearance's, a row with no age applying to both. Catalog membership is the entity
     * filter, so this is the applicability fact left to ask per request.
     *
     * @param style the style row
     * @return whether the row applies
     */
    public boolean applies(@NotNull PoseStyle style) {
        return style.age().map(this::selects).orElse(true);
    }

    /**
     * Whether this appearance admits the pass a style gate token names - the token is the spelling
     * the gated pass's {@code when} key uses, so the two filters read one vocabulary. An unknown
     * token admits nothing.
     *
     * @param gateToken the gate token a style source entry carries
     * @return whether this appearance keeps the gated pass
     */
    public boolean admits(@NotNull String gateToken) {
        for (Flag flag : Flag.values())
            if (flag.name().equalsIgnoreCase(gateToken)) return this.selects(flag);
        return false;
    }

    /**
     * The texture ref an overlay pass on a {@link TextureAxis texture axis} draws for this appearance,
     * or empty when the selection draws nothing so the pass is skipped. What each axis draws is
     * written on its constant; the {@link TextureAxis#TYPE} robe directory is read off the pass' own
     * baked ref, so the robe can never bind over the wrong mesh.
     *
     * @param axis the pass' texture axis
     * @param texturePrefix the entity texture prefix ({@code villager} / {@code zombie_villager})
     *     the villager axes' prefix-relative sub-paths are qualified with
     * @param rowTexture the row's own baked texture ref, which an axis with a baked default falls
     *     back to and the robe directory is read from
     * @return the effective texture ref, or empty when the selection resolves to nothing
     */
    public @NotNull Optional<String> texture(@NotNull TextureAxis axis, @NotNull String texturePrefix,
                                             @NotNull Optional<String> rowTexture) {
        return switch (axis) {
            case PATTERN -> this.pattern.map(TropicalFishPattern::overlayTexture).or(() -> rowTexture);
            case CRACKINESS -> this.crackiness.overlayTexture();
            case MARKINGS -> this.isBaby() ? this.markings.babyOverlayTexture() : this.markings.overlayTexture();
            case WEATHERING -> Optional.of(this.weathering.eyeTexture());
            case TYPE -> {
                boolean babyRobe = rowTexture.filter(ref -> ref.contains(BABY_ROBE_SEGMENT)).isPresent();
                yield Optional.of(texturePrefix + "/"
                    + (babyRobe ? this.villagerType.babyOverlaySubPath() : this.villagerType.overlaySubPath()));
            }
            case PROFESSION -> this.villagerProfession.textureRef(texturePrefix);
            case PROFESSION_LEVEL -> this.villagerProfession.drawsBadge()
                ? Optional.of(texturePrefix + "/"
                    + this.villagerLevel.orElseGet(VillagerLevel::minimum).overlaySubPath())
                : Optional.empty();
        };
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
     * Folds a definition's render-axis selections for this appearance into a single resolved
     * {@link Entity} the renderer iterates unconditionally, with no scattered {@code !baby} gates - the
     * render-time policy the reader deliberately leaves off the loaded data.
     *
     * <p>The worn-armor shell resolves ahead of them all and outside the fork, against whichever
     * selection the wearer's own second shell names. So does the bone-toggle selection the flip (8)
     * reads, the sheared toggle (3) included, which is how it reaches the mesh a baby swaps in.
     *
     * <p>The nine axis semantics apply in a fixed short-circuit order: (1) a baby swaps in the
     * {@link Entity.Axes#baby() baby form} - its mesh, its pose and its overlay passes - and so DROPS block
     * overlays / equipment, which the form does not carry - each carries adult geometry that would render
     * adult-sized around the smaller baby body, which is exactly why the overlay passes are the form's own
     * rather than the adult ones, and the form carries only the passes that declare a baby form, so a pass
     * with none drops out structurally - and the whole non-baby branch is skipped bar three steps: the
     * overlay gate filter (2), which runs over whichever list is in play, the sheared selection (3) and
     * the flip (8); else (2) sheared drops the wool overlay, charged gates the swirl and an unworn collar
     * drops its row; (3) the sheared axis adds a {@code "sheared"} bone toggle to the selection (bogged);
     * (4) block overlays resolve against the carried selection; (5) the shape axis swaps to the
     * tropical-fish large body; (6) the size axis swaps to the selected size's mesh and its pose (armor
     * stand, pufferfish, salmon); (7) the size axis multiplies the render scale (slime / magma_cube),
     * and where no size form swapped a pose of its own in, the first selected equipment slot whose
     * layer names a pose for its wearer swaps that pose in for the body's, re-pointing any pass
     * sharing the body's pose (the harnessed happy ghast's smaller body); (8)
     * selected bone toggles flip their bones' visibility once, on the mesh the baby, shape and size swaps
     * leave selected (donkey / mule / llama chest reveal, the goat's horns and the bee's sting hide on a
     * baby as on an adult, the armor stand's arms and plate at either size), each filled slot whose
     * layer names a toggle for its wearer adding that toggle to the selection (the armoured warm zombie
     * nautilus's corals hide); (9) the base-color axis
     * overrides the baked base tint (tropical-fish dye), applied OUTSIDE the baby fork so it affects both.
     * A non-baby, non-carried appearance returns an equivalent definition unchanged.
     *
     * <p>The style catalog narrows to the in-force view - a row whose age refuses this appearance
     * drops, and a gated source entry survives iff this appearance {@link #admits admits} its gate.
     *
     * @param entity the definition to fold
     * @return the age / carried / sheared / shape / size / tint-resolved definition
     */
    public @NotNull Entity resolve(@NotNull Entity entity) {
        // Variant fold (option-encoded coat / colour): a selected variant resolves against that option's
        // fully-built sub-definition, so every later axis (baby / size / tint) folds on top of the coat.
        // An absent or unknown option, and a non-variant model (empty variants map), keep the model
        // default coat.
        Entity definition = this.getVariant()
            .flatMap(coat -> entity.axes().variant().select(coat))
            .orElse(entity);
        Entity.Builder builder = definition.mutate();
        builder.styles(definition.styles().inForce(this.isBaby(), this::admits));
        // The worn shell resolves ahead of the age fork and outside it, because the axis that
        // selects a wearer's second shell is the wearer's own - six swap on age and the armor stand
        // on size - and vanilla picks the set off the flag alone rather than off the body mesh.
        Optional<Shell> armor = definition.layers()
            .humanoidArmor()
            .map(this::shell);
        // Selected bone toggles flip their bones' visibility (donkey/mule/llama chest reveal, the goat's
        // horns and the bee's sting hide on a baby as on an adult, the armor stand's arms and plate) on
        // whichever mesh the age, shape and size swaps below leave selected. The sheared axis
        // additionally activates the "sheared" toggle for entities that declare one (bogged drops its
        // mushrooms); entities whose sheared handling is overlay-only (sheep wool) declare no such
        // toggle and are left unchanged.
        Set<String> selectedToggles = this.getToggles();
        // Named unconditionally rather than gated on the subject declaring one: a mesh whose
        // bones name no "sheared" selection is left alone by the flip anyway, so asking first
        // would be a second roster of which subjects have the toggle.
        if (this.isSheared()) {
            selectedToggles = new LinkedHashSet<>(selectedToggles);
            selectedToggles.add("sheared");
        }
        EntityMesh selected = definition.model();
        ConcurrentList<Entity.EquipmentOverlay> equipment;
        Optional<Entity> baby = this.isBaby() ? definition.axes().baby() : Optional.empty();
        if (baby.isPresent()) {
            // The pose swaps WITH the mesh and never without it. A baby is a different model class,
            // so it is a different pose, and two of the families that pose at all are posed through
            // the baby class alone - carrying the adult's pose onto a baby mesh would animate bones
            // by the names the adult happens to share. The form draws no block overlay and no
            // equipment, so reading both off it drops the adult's.
            Entity form = baby.get();
            selected = form.model();
            builder.pose(form.pose())
                .overlays(this.gatedOverlays(form.overlays()))
                .blockOverlays(form.blockOverlays());
            equipment = form.layers().equipment();
        } else {
            ConcurrentList<Entity.OverlayLayer> passes = this.gatedOverlays(definition.overlays());
            builder.overlays(passes);
            builder.blockOverlays(this.resolveBlockOverlays(definition));
            // The shape axis (tropical fish) swaps to the large body when the selected pattern's Shape
            // is large - the large mesh, its tropical_b base texture and the pattern overlays cloned
            // onto the large geometry, all of it ONE already-built form rather than three members
            // lifted onto this builder. The pattern axis still picks the concrete overlay texture via
            // texture_by. A small / default pattern leaves the small body untouched.
            Optional<Entity> large = this.getPattern()
                .filter(pattern -> pattern.shape() == TropicalFishPattern.Shape.LARGE)
                .flatMap(pattern -> definition.axes().shape().select(Entity.SHAPE_LARGE));
            if (large.isPresent()) {
                Entity form = large.get();
                selected = form.model();
                passes = form.overlays();
                builder.overlays(passes).axes(form.axes());
            }
            // The size axis swaps to the selected size's form, which carries whichever of the two
            // vanilla mechanisms its subject uses: a distinct baked mesh (armor stand, pufferfish,
            // salmon) or the base mesh at a multiplied render scale (slime, magma_cube). The pose
            // swaps with the mesh for the reason the baby's does - a baked size mesh is posed by its
            // own model class, and the small pufferfish's fins are bones the row's pose never names.
            // All three are read off the form because it already holds the resolved values - the
            // selected size's own mesh and pose, and its own already-multiplied scale. Selecting the
            // declared size resolves to a form equal to the base, so it changes nothing.
            //
            // The orthographic VANILLA_ISO parity path reads the scale off the resolved definition and
            // sizes a native pixels-per-block canvas from it, so a 2x size renders a 2x canvas and
            // entity rather than resolving self-similar to the default.
            Optional<Entity> sized = this.getSize().flatMap(definition.axes().size()::select);
            EntityPose bodyPose = definition.pose();
            if (sized.isPresent()) {
                Entity form = sized.get();
                selected = form.model();
                bodyPose = form.pose();
                builder.pose(bodyPose);
                builder.rendererScale(form.rendererScale());
            }
            equipment = definition.layers().equipment();
            // A filled slot whose layer names a pose for its wearer swaps that pose in for the body's
            // - the harnessed happy ghast's smaller body - while the pose in force is still the
            // row's own. Vanilla's body asks only whether the stack is empty, so a selected slot
            // swaps whether or not its material names an asset the layer can draw. A pass sharing
            // the body's pose follows it onto the swapped one.
            if (bodyPose == definition.pose()) {
                Optional<EntityPose> wearer = equipment.stream()
                    .filter(overlay -> this.equipmentMaterial(overlay.slot()).isPresent())
                    .flatMap(overlay -> overlay.wearerPose().stream())
                    .findFirst();
                if (wearer.isPresent()) {
                    builder.pose(wearer.get());
                    builder.overlays(repointed(passes, bodyPose, wearer.get()));
                }
            }
        }
        // A filled slot whose layer names a toggle for its wearer selects it - the warm zombie
        // nautilus's corals, which its body draws only while the body armour slot is empty. Read
        // off the equipment in force, so a baby, which wears none, selects none. Like the pose swap
        // it asks only whether the slot is filled, not whether the material names a drawable asset.
        selectedToggles = this.wearerToggles(selectedToggles, equipment);
        // The flip lands once, after the fork, on the mesh the age, shape and size swaps leave
        // selected: a swap puts in its form's own mesh as built, so a selection flipped before it
        // would leave with the mesh it replaced. A mesh no selection reaches comes back as itself, so
        // a swapped form the selection misses - a baby selecting nothing among them - keeps its
        // instance, and an unswapped one keeps the row's. The flipped mesh is held against the row's
        // rather than the selected one, because a swap's own mesh is a change the builder has not
        // taken yet.
        EntityMesh flipped = selected.withToggled(selectedToggles);
        if (flipped != definition.model()) builder.model(flipped);
        // A layer's own toggles ride the same selection the wearer's do, so an equipped saddle
        // draws its reins for a ridden subject and its chest panniers for a chested one.
        builder.layers(new Entity.Layers(toggledEquipment(equipment, selectedToggles), armor,
            definition.layers().wings()));
        // The base_color axis (tropical fish) overrides the model base_tint with the selected dye; absent
        // (default) keeps the baked base_tint.
        this.tint(TintAxis.BASE).ifPresent(color -> builder.baseTintArgb(color.argb()));
        return builder.build();
    }

    /**
     * The toggle selection with every toggle a filled slot names for its wearer added, or the given
     * selection itself when no filled slot names one.
     *
     * @param toggles the toggles selected so far
     * @param equipment the equipment overlays in force
     * @return the selection the filled slots leave
     */
    private @NotNull Set<String> wearerToggles(
        @NotNull Set<String> toggles, @NotNull ConcurrentList<Entity.EquipmentOverlay> equipment) {

        Set<String> out = toggles;
        for (Entity.EquipmentOverlay overlay : equipment) {
            if (overlay.wearerToggle().isEmpty() || this.equipmentMaterial(overlay.slot()).isEmpty()) continue;
            if (out == toggles) out = new LinkedHashSet<>(toggles);
            out.add(overlay.wearerToggle().get());
        }
        return out;
    }

    /**
     * The passes with every one sharing the body's pose re-pointed at the pose swapped in for it,
     * or the given list itself when none shares it.
     *
     * @param passes the overlay passes in force
     * @param body the body pose being replaced
     * @param swapped the pose replacing it
     * @return the passes following the swapped pose
     */
    private static @NotNull ConcurrentList<Entity.OverlayLayer> repointed(
        @NotNull ConcurrentList<Entity.OverlayLayer> passes, @NotNull EntityPose body, @NotNull EntityPose swapped) {

        if (passes.stream().noneMatch(pass -> pass.pose() == body)) return passes;
        return passes.stream()
            .map(pass -> pass.pose() != body ? pass : new Entity.OverlayLayer(pass.model(), pass.textureRef(),
                pass.pass(), pass.tintArgb(), pass.skipBounds(), pass.tintBy(), pass.textureBy(), pass.gate(),
                pass.noHatModel(), swapped, pass.textureScroll()))
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Drops the overlays this appearance does not activate - the sheep wool once sheared, the creeper
     * swirl unless charged, the collar while none is worn - both the rendered geometry and its
     * canvas-bounds contribution. The list is only rebuilt when a resolve-stage gate is present, so
     * a list carrying none is returned as-is. Applied to the adult and the baby list alike, so a
     * gated pass that gains a baby form is gated on a baby too rather than drawing unconditionally.
     *
     * @param overlays the overlay list to gate
     * @return the surviving overlays, or the given list itself when nothing drops
     */
    private @NotNull ConcurrentList<Entity.OverlayLayer> gatedOverlays(@NotNull ConcurrentList<Entity.OverlayLayer> overlays) {
        boolean gated = overlays.stream()
            .anyMatch(overlay -> overlay.gate()
                .filter(gate -> !(gate instanceof AppearanceGate.TintedGate))
                .isPresent());
        if (!gated) return overlays;
        return overlays.stream()
            .filter(this::rendersAtResolve)
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * Whether an overlay survives the resolve-stage gate filter: an unconditional or tint-gated overlay
     * is kept here (a {@link AppearanceGate.TintedGate} is instead evaluated at render), while a flag /
     * charged gate that fails for this appearance drops the overlay (the sheared wool, the uncharged
     * creeper swirl).
     *
     * @param overlay the overlay to test
     * @return whether the overlay survives the resolve stage
     */
    private boolean rendersAtResolve(@NotNull Entity.OverlayLayer overlay) {
        return overlay.gate()
            .filter(gate -> !(gate instanceof AppearanceGate.TintedGate))
            .map(this::passes)
            .orElse(true);
    }

    /**
     * Resolves the definition's block overlays against this appearance's carried selection. A
     * <b>fixed</b> overlay (mooshroom mushrooms, snow golem pumpkin) is kept unless {@code carried ==
     * "none"} drops it; a <b>selectable</b> overlay (enderman carried block, iron golem flower) is kept
     * only when a block is selected, with its block id replaced by that selection. The default (empty)
     * appearance therefore renders the fixed decorations and no selectable held block.
     *
     * @param definition the definition whose block overlays resolve
     * @return the block overlays this appearance draws
     */
    private @NotNull ConcurrentList<Entity.BlockOverlayLayer> resolveBlockOverlays(@NotNull Entity definition) {
        if (definition.blockOverlays().isEmpty()) return definition.blockOverlays();
        Optional<String> selected = this.selectedCarriedBlock();
        boolean dropsFixed = this.dropsCarried();
        return definition.blockOverlays()
            .stream()
            .filter(overlay -> overlay.selectable() ? selected.isPresent() : !dropsFixed)
            .map(overlay -> overlay.selectable() ? overlay.withBlockId(selected.orElseThrow()) : overlay)
            .collect(Concurrent.toUnmodifiableList());
    }

    /**
     * The equipment overlays with their selected toggles flipped, or the given list when nothing
     * moves.
     *
     * @param equipment the resolved definition's equipment overlays
     * @param toggles the appearance's selected toggle names
     * @return the overlays drawing what the selection asks for
     */
    private static @NotNull ConcurrentList<Entity.EquipmentOverlay> toggledEquipment(
        @NotNull ConcurrentList<Entity.EquipmentOverlay> equipment, @NotNull Set<String> toggles) {

        if (toggles.isEmpty() || equipment.isEmpty()) return equipment;
        List<Entity.EquipmentOverlay> out = new ArrayList<>(equipment.size());
        boolean moved = false;
        for (Entity.EquipmentOverlay overlay : equipment) {
            Entity.EquipmentOverlay flipped = overlay.withToggles(toggles);
            moved |= flipped != overlay;
            out.add(flipped);
        }
        return moved ? Concurrent.newUnmodifiableList(out) : equipment;
    }

    /**
     * Builds an appearance with every axis at its default (adult, no state / carried / collar).
     *
     * @return the default appearance
     */
    public static @NotNull AppearanceOptions defaults() {
        return builder().build();
    }

}
