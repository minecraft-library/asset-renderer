package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.policy.AsmContext;
import lib.minecraft.renderer.tooling2.policy.Navigation;
import lib.minecraft.renderer.tooling2.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declared GUI / inventory-transform facts (SPINE 2.1 roster P36 / P41) a bytecode walk
 * cannot settle - which renderer member builds each GUI {@code Transformation}, the per-model
 * camera-facing yaw convention, the bell no-flip exception, and the bed icon rotation. Declared
 * here with mandatory provenance; never fetches ({@code PolicyPurityTest}).
 *
 * <p>Split MECHANICS stay bytecode: {@code TransformWalker} re-enters at the P41 coordinate and
 * decomposes the {@code PoseStack} chain, and {@link BlockGuiResolver} reads the item-model
 * {@code display.gui} roll for the non-transform families. This enum only supplies the entry
 * coordinates and the handful of facts the charter genuinely cannot see (why the same gui-yaw
 * maps to different inventory rotations; why the bell hangs un-flipped).
 */
enum BlockTransformPolicies implements NavigationPolicy {

    /**
     * P41 - the 8-renderer table naming which member builds each renderer's GUI
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
        "P41: the GUI-Transformation entry coordinate per renderer - legacy"
            + " InventoryTransformDecomposer.buildRendererEntryMethods:100-113; each renderer has >=2"
            + " Transformation-returning members so a return-type scan is ambiguous (the sole hand-curated"
            + " map, ITD:82-86). FIELD: = walk <clinit> for the static Transformation field's PUTSTATIC"),

    /**
     * The nine split ids whose inventory icon faces the GUI camera at yaw 180. NOT derivable
     * from {@code display.gui} yaw - skull and conduit both carry gui-yaw 45 yet map to 180 / 0,
     * so it is a per-model facing convention, undetectable by charter [D58 revisited].
     */
    INVENTORY_Y_ROTATION(
        Set.of(
            "minecraft:chest",
            "minecraft:banner", "minecraft:wall_banner", "minecraft:banner_flag", "minecraft:wall_banner_flag",
            "minecraft:skull_head", "minecraft:skull_humanoid_head", "minecraft:skull_dragon_head", "minecraft:skull_piglin_head"),
        "the split ids whose GUI icon renders at inventory yaw 180 - legacy"
            + " SourceDiscovery.ID_TO_INVENTORY_Y_ROTATION:104-114. NOT a display.gui derivation: skull and"
            + " conduit share gui-yaw 45 yet map to 180 vs 0 - a camera-facing convention, not the raw gui"
            + " yaw [D58 hypothesis refuted]. Every other split defaults to 0"),

    /**
     * P36 - the split ids the entity flip is suppressed on. Only {@code bell_body}:
     * {@code BellRenderer.submit} draws with the raw block {@code PoseStack} (no
     * {@code scale(-1, -1, 1)}), and the bell icon is a flat {@code item/generated} sprite with
     * no {@code display.gui} for the roll derivation to read - so the default-flip would be wrong.
     */
    FLIP_SUPPRESSED(
        Set.of("minecraft:bell_body"),
        "P36 / [D48]: the bell hangs un-flipped - BellRenderer.submit applies no scale(-1,-1,1) and the"
            + " block-space body (pivot (8,12,8), cube x=5..11) would leave the block under the cx=-cx flip;"
            + " its flat item/generated icon carries no display.gui so the roll derivation cannot see it -"
            + " legacy ToolingBlockModels:161-167. Every other unresolved icon defaults to flip=true"),

    /**
     * P36 - the split ids whose inventory icon renders rotated a quarter turn, and the rotation.
     * Only the bed head (90 degrees) - a GUI presentation policy, not bytecode.
     */
    ICON_ROTATION(
        Map.of("minecraft:bed_head", 90),
        "P36: the bed inventory icon renders rotated 90 degrees - legacy"
            + " ToolingBlockModels.applyPerBlockFamilyFields:423-425; GUI presentation policy, not bytecode"),

    /**
     * The split ids whose icon draws additively. Only {@code bell_body}: the bell body is drawn
     * over the block's real (non {@code builtin/entity}) blockstate model, so it composites
     * additively [D50].
     */
    ICON_ADDITIVE(
        Set.of("minecraft:bell_body"),
        "[D50]: the bell body draws additively over the block's real (non builtin/entity) blockstate"
            + " model - legacy ToolingBlockModels.applyPerBlockFamilyFields:427-429"),

    /**
     * P32 - the sub-model composition roster: which base split renders a sub-model part, its
     * split id, the render offset, and any per-part texture override. Vanilla composes these in
     * the renderer's {@code submit}; the offsets and the pot-side texture are declared (the
     * legacy walk hard-codes them as constants). The four part-only splits (bed_foot,
     * decorated_pot_sides, banner_flag, wall_banner_flag) exist only as reference targets and
     * carry no parts of their own.
     */
    PART_COMPOSITION(
        Map.of(
            "minecraft:bed_head", List.of(new PartSpec("minecraft:bed_foot", new int[]{0, 0, 16}, null)),
            "minecraft:banner", List.of(new PartSpec("minecraft:banner_flag", null, null)),
            "minecraft:wall_banner", List.of(new PartSpec("minecraft:wall_banner_flag", null, null)),
            "minecraft:decorated_pot", List.of(new PartSpec("minecraft:decorated_pot_sides", new int[]{0, 0, 0},
                "minecraft:entity/decorated_pot/decorated_pot_side"))),
        "P32: the sub-model parts roster - legacy BlockListDiscovery BED_FOOT_OFFSET={0,0,16}:171 (icon-"
            + " composition policy; BedRenderer at 26.1 has no submit translate - the {0,0,16} matches"
            + " BedBlock's one-block foot placement), DECORATED_POT_SIDES_OFFSET={0,0,0}:164 (no PoseStack"
            + " .translate between the base and sides submits) + SIDE_TEXTURE:1794 (= Sheets.DECORATED_POT_SIDE);"
            + " banner/wall_banner compose their flag sub-model with no offset - :1629-1630, :1810-1811");

    /** The {@code FIELD:} prefix on a P41 entry marking a static {@code Transformation} field. */
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
     * @return {@code true} when the renderer is a P41 transform target
     */
    static boolean isTransformTarget(@NotNull String rendererClass) {
        return rendererEntry(rendererClass) != null;
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
