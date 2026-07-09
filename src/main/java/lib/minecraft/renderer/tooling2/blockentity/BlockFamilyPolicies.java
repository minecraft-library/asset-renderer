package lib.minecraft.renderer.tooling2.blockentity;

import lib.minecraft.renderer.tooling2.policy.AsmContext;
import lib.minecraft.renderer.tooling2.policy.Navigation;
import lib.minecraft.renderer.tooling2.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * THE {@code navigate()} prototype (SPINE 2.1 roster P31 / P33 / P34 / P39): the declared
 * split-id facts a bytecode walk cannot see. Vanilla has ONE {@code BlockEntityType.BED},
 * {@code SKULL}, {@code BANNER}, etc.; the head/foot, 4-way skull, standing/wall and flag-submodel
 * splits and their id names are OUR output convention - undetectable by charter, so declared here
 * with mandatory provenance. Never fetches ({@code PolicyPurityTest}).
 *
 * <p>The split MECHANICS stay bytecode ({@link BlockGeometrySourceResolver} runs generic
 * ModelLayers x {@code LayerDefinitionIndex} detection first); this enum only supplies the
 * split-id vocabulary and the branch-parameter values (banner / sign {@code withStick}, hanging-sign
 * attachment enum) the manifest requests carry.
 */
enum BlockFamilyPolicies implements NavigationPolicy {

    /**
     * P34 / P31 - the factory-method-to-split-id map. A block-entity renderer that references
     * several primary layers (bed head + foot, decorated pot base + sides, the four skull
     * meshes) splits into one model per layer; the split id is keyed on
     * {@code <baseLocalId>#<factoryMethod>}. Bases without an entry keep the subject id
     * unchanged (the single-mesh families - shulker, chest, conduit, copper_golem_statue).
     */
    METHOD_SPLIT_NAMING(
        Map.ofEntries(
            Map.entry("bed#createHeadLayer", "minecraft:bed_head"),
            Map.entry("bed#createFootLayer", "minecraft:bed_foot"),
            Map.entry("decorated_pot#createBaseLayer", "minecraft:decorated_pot"),
            Map.entry("decorated_pot#createSidesLayer", "minecraft:decorated_pot_sides"),
            Map.entry("bell#createBodyLayer", "minecraft:bell_body"),
            Map.entry("skull#createMobHeadLayer", "minecraft:skull_head"),
            Map.entry("skull#createHumanoidHeadLayer", "minecraft:skull_humanoid_head"),
            Map.entry("skull#createHeadLayer", "minecraft:skull_dragon_head"),
            Map.entry("skull#createHeadModel", "minecraft:skull_piglin_head")),
        "P34/P31: our split ids for the multi-mesh families (bed head/foot, decorated_pot base/sides,"
            + " bell_body, 4-way skull); vanilla uses one BlockEntityType each - legacy"
            + " SourceDiscovery.applyMethodSuffix:738-757 + SKULL_VARIANT_POLICY:141-144 (skull dims stay"
            + " derived from each wrapper's LayerDefinition.create tail: mob 64x32, humanoid 64x64)"),

    /**
     * P39 - the sign factory-parameter table, keyed on the factory method the renderer's own
     * static primary reach resolves to. Standing signs split on the {@code withStick} boolean
     * ({@code sign} withStick=1 / {@code wall_sign} withStick=0); hanging signs split on the
     * {@code HangingSignBlock$Attachment} enum the factory branches on ({@code CEILING} board +
     * V-chains, {@code CEILING_MIDDLE} the {@code attached=true} straight chains, {@code WALL}).
     * The enum-gate STRUCTURE is bytecode; the id-to-constant naming is ours.
     */
    SIGN_VARIANTS(
        Map.of(
            "createSignLayer", List.of(
                new SignVariant("minecraft:sign", 1, null),
                new SignVariant("minecraft:wall_sign", 0, null)),
            "createHangingSignLayer", List.of(
                new SignVariant("minecraft:hanging_sign", null, "CEILING"),
                new SignVariant("minecraft:hanging_sign_attached", null, "CEILING_MIDDLE"),
                new SignVariant("minecraft:wall_hanging_sign", null, "WALL"))),
        "P39: sign withStick + hanging-sign attachment split ids; the factory branch structure is"
            + " bytecode, the id<->constant naming is ours - legacy SourceDiscovery.emitSignSources:693-716"),

    /**
     * P34 (banner) - the banner {@code ModelLayers} field to (split id, {@code withStick})
     * table. {@code BannerRenderer} references four fields, each resolving to
     * {@code BannerModel.createBodyLayer(Z)} / {@code BannerFlagModel.createFlagLayer(Z)} with a
     * compile-time {@code ICONST_0/1}; the field-name prefix ({@code STANDING_} / {@code WALL_})
     * carries the standing/wall {@code withStick} and the {@code _FLAG} suffix carries the
     * pole-vs-flag submodel split - derived here so the same factory method splits four ways.
     */
    BANNER_FIELDS(
        Map.of(
            "STANDING_BANNER", new BannerVariant("minecraft:banner", 1),
            "WALL_BANNER", new BannerVariant("minecraft:wall_banner", 0),
            "STANDING_BANNER_FLAG", new BannerVariant("minecraft:banner_flag", 1),
            "WALL_BANNER_FLAG", new BannerVariant("minecraft:wall_banner_flag", 0)),
        "P34: banner ModelLayers field -> (split id, withStick); the ICONST split is baked in"
            + " createRoots, the flag/pole split is the _FLAG field suffix - legacy"
            + " SourceDiscovery.PARAM_INT_SUFFIX:125-128 + BannerFlagModel endsWith heuristic");

    /**
     * One sign / hanging-sign variant: the split id plus the branch parameter selecting it -
     * either an int {@code withStick} value or a {@code HangingSignBlock$Attachment} enum
     * constant name (exactly one of the two is set).
     *
     * @param splitId the models key this variant emits
     * @param withStick the {@code createSignLayer(boolean)} value, or {@code null} for hanging signs
     * @param attachment the attachment enum constant name, or {@code null} for standing signs
     */
    record SignVariant(@NotNull String splitId, @Nullable Integer withStick, @Nullable String attachment) {}

    /**
     * One banner variant: the split id plus the {@code withStick} the field name implies.
     *
     * @param splitId the models key this variant emits
     * @param withStick the {@code createBodyLayer(boolean)} / {@code createFlagLayer(boolean)} value
     */
    record BannerVariant(@NotNull String splitId, int withStick) {}

    private final @NotNull Object value;
    private final @NotNull String provenance;

    BlockFamilyPolicies(@NotNull Object value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    @Override
    public @NotNull Navigation navigate(@NotNull AsmContext context) {
        return new Navigation.Value<>(this.value, this.provenance);
    }

    /**
     * The declared split id for a {@code <baseLocalId>#<factoryMethod>} coordinate, or
     * {@code null} when the base keeps its subject id (single-mesh families).
     *
     * @param baseLocalId the namespace-less subject id ({@code bed}, {@code skull})
     * @param factoryMethod the primary layer factory method
     * @return the declared split id, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String methodSplitId(@NotNull String baseLocalId, @NotNull String factoryMethod) {
        return ((Map<String, String>) METHOD_SPLIT_NAMING.value).get(baseLocalId + "#" + factoryMethod);
    }

    /**
     * The declared sign variants for a factory method, or {@code null} when the method is not a
     * sign factory.
     *
     * @param factoryMethod {@code createSignLayer} / {@code createHangingSignLayer}
     * @return the ordered variants, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable List<SignVariant> signVariants(@NotNull String factoryMethod) {
        return ((Map<String, List<SignVariant>>) SIGN_VARIANTS.value).get(factoryMethod);
    }

    /**
     * The declared banner variant for a {@code ModelLayers} field, or {@code null} when the
     * field is not a banner layer.
     *
     * @param modelLayersField the {@code ModelLayers} field name
     * @return the banner variant, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable BannerVariant bannerVariant(@NotNull String modelLayersField) {
        return ((Map<String, BannerVariant>) BANNER_FIELDS.value).get(modelLayersField);
    }

}
