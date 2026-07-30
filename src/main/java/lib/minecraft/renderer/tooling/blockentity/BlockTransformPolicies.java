package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declared GUI / inventory-transform facts a bytecode walk cannot settle - which renderer
 * member builds each GUI {@code Transformation}, the per-model camera-facing yaw convention, the
 * bell no-flip exception, and the bed icon rotation. Declared here with mandatory provenance;
 * never fetches ({@code PolicyPurityTest}).
 *
 * <p>The mechanics stay bytecode: {@code TransformWalker} re-enters at the declared entry
 * coordinate and decomposes the {@code PoseStack} chain, and {@link BlockGuiResolver} reads the
 * item-model {@code display.gui} roll for the non-transform families. This enum only supplies the
 * entry coordinates and the handful of facts that a bytecode walk genuinely cannot see (why the
 * same gui-yaw maps to different inventory rotations; why the bell hangs un-flipped).
 */
enum BlockTransformPolicies implements NavigationPolicy {

    /**
     * The 8-renderer table naming which member builds each renderer's GUI
     * {@code Transformation}. A bare method name means "walk that method"; a {@code FIELD:<name>}
     * value means "walk {@code <clinit>} for the {@code PUTSTATIC} of that static
     * {@code Transformation} field". The three renderers absent here (chest, bell,
     * copper_golem_statue) draw with the raw block pose and get no {@code inventory.transform} -
     * their flip is resolved from the item icon instead.
     */
    RENDERER_ENTRY_METHODS(
        Map.ofEntries(
            Map.entry("net/minecraft/client/renderer/blockentity/BedRenderer", "createModelTransform"),
            Map.entry("net/minecraft/client/renderer/blockentity/ShulkerBoxRenderer", "createModelTransform"),
            Map.entry("net/minecraft/client/renderer/blockentity/SkullBlockRenderer", "createGroundTransformation"),
            Map.entry("net/minecraft/client/renderer/blockentity/DecoratedPotRenderer", "createModelTransformation"),
            Map.entry("net/minecraft/client/renderer/blockentity/ConduitRenderer", "FIELD:DEFAULT_TRANSFORMATION"),
            Map.entry("net/minecraft/client/renderer/blockentity/StandingSignRenderer", "bodyTransformation"),
            Map.entry("net/minecraft/client/renderer/blockentity/HangingSignRenderer", "bodyTransformation"),
            Map.entry("net/minecraft/client/renderer/blockentity/BannerRenderer", "modelTransformation")),
        "the GUI-Transformation entry coordinate per renderer: each renderer has >= 2 Transformation-returning"
            + " members, so a return-type scan is ambiguous and this stays the sole hand-curated map. FIELD: means"
            + " walk <clinit> for the static Transformation field's PUTSTATIC"),

    /**
     * The nine split ids whose inventory icon faces the GUI camera at yaw 180. NOT derivable
     * from {@code display.gui} yaw - skull and conduit both carry gui-yaw 45 yet map to 180 / 0,
     * so it is a per-model facing convention, not something a bytecode walk alone can determine.
     */
    INVENTORY_Y_ROTATION(
        Set.of(
            "minecraft:chest",
            "minecraft:banner", "minecraft:wall_banner", "minecraft:banner_flag", "minecraft:wall_banner_flag",
            "minecraft:skull_head", "minecraft:skull_humanoid_head", "minecraft:skull_dragon_head", "minecraft:skull_piglin_head"),
        "the split ids whose GUI icon renders at inventory yaw 180. NOT a display.gui derivation: skull and"
            + " conduit share gui-yaw 45 yet map to 180 against 0 - a camera-facing convention, not the raw gui"
            + " yaw. Every other split defaults to 0"),

    /**
     * The split ids the entity flip is suppressed on. Only {@code bell_body}:
     * {@code BellRenderer.submit} draws with the raw block {@code PoseStack} (no
     * {@code scale(-1, -1, 1)}), and the bell icon is a flat {@code item/generated} sprite with
     * no {@code display.gui} for the roll derivation to read - so the default-flip would be wrong.
     */
    FLIP_SUPPRESSED(
        Set.of("minecraft:bell_body"),
        "the bell hangs un-flipped - BellRenderer.submit applies no scale(-1,-1,1), and the block-space body"
            + " (pivot (8,12,8), cube x=5..11) would leave the block under the cx=-cx flip; its flat item/generated"
            + " icon carries no display.gui, so the roll derivation cannot see it. Every other unresolved icon"
            + " defaults to flip=true"),

    /**
     * The standing-sign attachment constant seeded into {@code bodyTransformation}'s enum param
     * so the {@code baseTransformation} branch resolves per split ({@code sign} = floor icon,
     * {@code wall_sign} = the wall offset). The enum-gate STRUCTURE is bytecode; only which split
     * takes which attachment is declared.
     */
    STANDING_SIGN_ATTACHMENT(
        Map.of("minecraft:sign", "FLOOR", "minecraft:wall_sign", "WALL"),
        "the standing-sign icon attachment per split - sign renders floor-attached, wall_sign takes"
            + " the PlainSignBlock$Attachment.WALL branch offset in StandingSignRenderer.baseTransformation"),

    /**
     * The split ids whose inventory icon renders rotated a quarter turn, and the rotation.
     * Only the bed head (90 degrees) - a GUI presentation policy, not bytecode.
     */
    ICON_ROTATION(
        Map.of("minecraft:bed_head", 90),
        "the bed inventory icon renders rotated 90 degrees - a GUI presentation policy, not bytecode"),

    /**
     * The split ids whose icon draws additively. Only {@code bell_body}: the bell body is drawn
     * over the block's real (non {@code builtin/entity}) blockstate model, so it composites
     * additively.
     */
    ICON_ADDITIVE(
        Set.of("minecraft:bell_body"),
        "the bell body draws additively over the block's real (non builtin/entity) blockstate model"),

    /**
     * The sub-model composition roster: which base split renders a sub-model part, its
     * split id, the render offset, and any per-part texture override. Vanilla composes these in
     * the renderer's {@code submit}; the offsets and the pot-side texture are declared constants
     * rather than walked. The four part-only splits (bed_foot,
     * decorated_pot_sides, banner_flag, wall_banner_flag) exist only as reference targets and
     * carry no parts of their own.
     */
    PART_COMPOSITION(
        Map.of(
            "minecraft:bed_head", List.of(new PartSpec("minecraft:bed_foot", new int[]{0, 0, 16}, null)),
            "minecraft:banner", List.of(new PartSpec("minecraft:banner_flag", null, null)),
            "minecraft:wall_banner", List.of(new PartSpec("minecraft:wall_banner_flag", null, null)),
            "minecraft:decorated_pot", List.of(new PartSpec("minecraft:decorated_pot_sides", new int[]{0, 0, 0},
                "minecraft:textures/entity/decorated_pot/decorated_pot_side.png"))),
        "the sub-model parts roster, an icon-composition policy: BedRenderer at 26.1 has no submit translate, so"
            + " the bed_foot {0,0,16} matches BedBlock's own one-block foot placement; decorated_pot's {0,0,0}"
            + " reflects no PoseStack .translate between the base and sides submits, and its side texture is"
            + " Sheets.DECORATED_POT_SIDE; banner / wall_banner compose their flag sub-model with no offset"),

    /**
     * The flip default for splits whose icon roll is unreadable (a flat sprite with no
     * {@code display.gui}): flip TRUE. Consulted only when the display.gui derivation misses and
     * {@link #FLIP_SUPPRESSED} does not name the split.
     */
    ENTITY_FLIP_DEFAULT(
        Boolean.TRUE,
        "unresolved flat-sprite icons default to flip=true; consulted only when the item model carries no"
            + " display.gui roll and the split is not FLIP_SUPPRESSED"),

    /**
     * The pivot-Y half-block band {@code [8, 16)} marking a block-space-authored factory
     * ({@code UP}): ChestModel pivots {@code (0, 9, 1)}, BellModel {@code (8, 12, 8)}. Outside the
     * band stays {@code DOWN}: ShulkerModel's {@code y = 24} mob root, banner-flag / dragon-head
     * pure-entity meshes, and {@code offsetAndRotation} users (decorated pot).
     */
    Y_AXIS_BAND(
        new float[]{8f, 16f},
        "the [8,16) half-block pivot-Y band, applied as a band rather than a bare >= 8; counter-examples:"
            + " ChestModel (0,9,1) / BellModel (8,12,8) block-space UP, ShulkerModel y=24 mob-root DOWN, and"
            + " offsetAndRotation users (decorated pot) DOWN"),

    /**
     * The canonicalisation tolerance {@code UNIT_EPS = 1e-3}: the band that snaps the
     * shulker's {@code 0.9995} z-fight fudge to unit scale, gates the near-unity translation fold,
     * and bounds the sign-fold / rotateAround comparisons.
     */
    CANONICALISE(
        1e-3f,
        "UNIT_EPS 1e-3 - the shulker 0.9995 z-fight-fudge snap band, the sign-fold axis preference"
            + " (scale(1,-1,-1)=Rx180, and (-s,-s,+s)->Rx180 by Y-symmetry), and the block-centred"
            + " rotateAround-180 = the camera-facing flip already carried by the y_rotation");

    /** The {@code FIELD:} prefix on an entry marking a static {@code Transformation} field. */
    static final @NotNull String FIELD_ENTRY_PREFIX = "FIELD:";

    /**
     * One composed sub-model part: the part's split id, its render offset (in model pixels, or
     * {@code null} to omit), and a per-part texture override (or {@code null} to inherit).
     *
     * @param model the part's models key
     * @param offset the {@code [x, y, z]} render offset, or {@code null} to omit the key
     * @param texture the per-part texture id, or {@code null} to omit the key
     */
    record PartSpec(@NotNull String model, int @Nullable [] offset, @Nullable String texture) {}

    private final @NotNull Object value;
    private final @NotNull String provenance;

    BlockTransformPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The GUI-Transformation entry (method name or {@code FIELD:<field>}) for a renderer, or
     * {@code null} when the renderer draws with the raw block pose (no inventory transform).
     *
     * @param rendererClass the renderer's JVM internal name
     * @return the entry coordinate, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String rendererEntry(@NotNull String rendererClass) {
        return ((Map<String, String>) RENDERER_ENTRY_METHODS.value).get(rendererClass);
    }

    /**
     * Whether a renderer builds a GUI {@code Transformation} (and so its splits carry an
     * {@code inventory.transform}).
     *
     * @param rendererClass the renderer's JVM internal name
     * @return {@code true} when the renderer is a transform target
     */
    static boolean isTransformTarget(@NotNull String rendererClass) {
        return rendererEntry(rendererClass) != null;
    }

    /**
     * The standing-sign attachment constant to seed for a split, or {@code null} when the split is
     * not a standing sign.
     *
     * @param splitId the models key
     * @return the attachment enum constant name, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String signAttachment(@NotNull String splitId) {
        return ((Map<String, String>) STANDING_SIGN_ATTACHMENT.value).get(splitId);
    }

    /**
     * The inventory yaw a split's icon renders at - 180 for the camera-facing families, else 0.
     *
     * @param splitId the models key
     * @return {@code 180f} or {@code 0f}
     */
    @SuppressWarnings("unchecked")
    static float inventoryYRotation(@NotNull String splitId) {
        return ((Set<String>) INVENTORY_Y_ROTATION.value).contains(splitId) ? 180f : 0f;
    }

    /**
     * Whether the entity flip is suppressed on a split (the bell hangs un-flipped).
     *
     * @param splitId the models key
     * @return {@code true} when the split never flips
     */
    @SuppressWarnings("unchecked")
    static boolean isFlipSuppressed(@NotNull String splitId) {
        return ((Set<String>) FLIP_SUPPRESSED.value).contains(splitId);
    }

    /**
     * The inventory-icon rotation for a split in degrees, or {@code null} when the icon is not
     * rotated.
     *
     * @param splitId the models key
     * @return the rotation, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable Integer iconRotation(@NotNull String splitId) {
        return ((Map<String, Integer>) ICON_ROTATION.value).get(splitId);
    }

    /**
     * Whether a split's inventory icon draws additively.
     *
     * @param splitId the models key
     * @return {@code true} when the icon composites additively
     */
    @SuppressWarnings("unchecked")
    static boolean isIconAdditive(@NotNull String splitId) {
        return ((Set<String>) ICON_ADDITIVE.value).contains(splitId);
    }

    /** The flip for splits whose icon roll is unreadable ({@code true}). */
    static boolean entityFlipDefault() {
        return (Boolean) ENTITY_FLIP_DEFAULT.value;
    }

    /** The inclusive lower bound of the block-space pivot-Y band. */
    static float yAxisBandMin() {
        return ((float[]) Y_AXIS_BAND.value)[0];
    }

    /** The exclusive upper bound of the block-space pivot-Y band. */
    static float yAxisBandMax() {
        return ((float[]) Y_AXIS_BAND.value)[1];
    }

    /** The canonicalisation tolerance ({@code UNIT_EPS}). */
    static float canonicalUnitEps() {
        return (Float) CANONICALISE.value;
    }

    /**
     * The sub-model parts a base split composes, or {@code null} when the split renders no
     * sub-models.
     *
     * @param splitId the models key
     * @return the ordered part specs, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable List<PartSpec> partsOf(@NotNull String splitId) {
        return ((Map<String, List<PartSpec>>) PART_COMPOSITION.value).get(splitId);
    }

    /**
     * Whether a split id is a sub-model part (a value in the {@link #PART_COMPOSITION} roster) -
     * the part-only splits carry no {@code blocks[]} of their own.
     *
     * @param splitId the models key
     * @return {@code true} when the split is some base's composed sub-model
     */
    @SuppressWarnings("unchecked")
    static boolean isPartModel(@NotNull String splitId) {
        for (List<PartSpec> specs : ((Map<String, List<PartSpec>>) PART_COMPOSITION.value).values())
            for (PartSpec spec : specs)
                if (spec.model().equals(splitId)) return true;
        return false;
    }

}
