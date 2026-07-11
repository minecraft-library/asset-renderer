package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
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
            + " SourceDiscovery.PARAM_INT_SUFFIX:125-128 + BannerFlagModel endsWith heuristic"),

    /**
     * P31 - the {@code SkullBlock$Types} to catalog split-id map. Vanilla has ONE
     * {@code BlockEntityType.SKULL}; our 4-way split groups the seven skull types by shared mesh
     * + texture dims (skeleton/wither/creeper = mob head; zombie/player = humanoid; dragon;
     * piglin ears). Keyed by the block-id type prefix ({@code skeleton_skull} -> {@code skeleton}).
     */
    SKULL_TYPE_SPLIT(
        Map.ofEntries(
            Map.entry("skeleton", "minecraft:skull_head"),
            Map.entry("wither_skeleton", "minecraft:skull_head"),
            Map.entry("creeper", "minecraft:skull_head"),
            Map.entry("zombie", "minecraft:skull_humanoid_head"),
            Map.entry("player", "minecraft:skull_humanoid_head"),
            Map.entry("dragon", "minecraft:skull_dragon_head"),
            Map.entry("piglin", "minecraft:skull_piglin_head")),
        "P31: the 4-way skull split grouping the seven SkullBlock$Types by shared mesh + texture dims -"
            + " legacy BlockListDiscovery.SKULL_TYPE_TO_ENTITY_ID:149-157 (mob 64x32 / humanoid 64x64 /"
            + " dragon mesh / piglin ears); vanilla registers one BlockEntityType.SKULL, the split is ours"),

    /**
     * P33 - the catalog family-dispatch roster: which subjects emit a {@code blocks[]} catalog and
     * which family builder each rides. The family SET is derivable from discovery; the
     * split/texture-source divergence per family is the declared fact. Subjects absent here
     * (enchanting_table / lectern) carry no catalog; the part-only pseudo families ride
     * {@link BlockTransformPolicies#PART_COMPOSITION} [P32] instead.
     */
    FAMILY_ROSTER(
        Map.ofEntries(
            Map.entry("shulker_box", CatalogFamily.SHULKER_BOX),
            Map.entry("chest", CatalogFamily.CHEST),
            Map.entry("bed", CatalogFamily.BED),
            Map.entry("sign", CatalogFamily.SIGN),
            Map.entry("hanging_sign", CatalogFamily.HANGING_SIGN),
            Map.entry("conduit", CatalogFamily.CONDUIT),
            Map.entry("bell", CatalogFamily.BELL),
            Map.entry("decorated_pot", CatalogFamily.DECORATED_POT),
            Map.entry("copper_golem_statue", CatalogFamily.COPPER_GOLEM_STATUE),
            Map.entry("skull", CatalogFamily.SKULL),
            Map.entry("banner", CatalogFamily.BANNER)),
        "P33: the family-dispatch roster - legacy BlockListDiscovery.FAMILY_DISPATCH:191/:203-222;"
            + " the family set is derivable from discovery, only the per-family split/texture-source"
            + " divergence is declared (07 3 row 18); part-only pseudo families ride PART_COMPOSITION [P32]"),

    /**
     * P35 - the chest block-to-{@code ChestSpecialRenderer}-texture-field binding: the three fixed
     * classes plus the copper composition rule ({@code COPPER_ + <WeatherState>}, {@code UNAFFECTED}
     * for the bare base, waxed sharing the unwaxed sheet). The binding is spread across vanilla's
     * special-renderer dispatch - not one walkable site.
     */
    CHEST_VARIANT(
        new ChestVariants(
            Map.of("chest", "REGULAR", "trapped_chest", "TRAPPED", "ender_chest", "ENDER_CHEST"),
            "COPPER_"),
        "P35: chest class->texture-field binding + COPPER_<weather> composition with the UNAFFECTED"
            + " fallback - legacy BlockListDiscovery switch :1199-1212 + weather default :1914; 'the"
            + " class<->field binding is spread across vanilla's special-renderer dispatch; not one"
            + " walkable site' (07 3 row 22)"),

    /**
     * P43 - which of a tint-bearing renderer's meshes takes the dye: the {@code *FlagModel} factory
     * (the banner flag); the wood-brown pole / bar never tints. An escape hatch - the honest
     * derivation would need data-flow analysis of which submitted buffer receives the DyeColor.
     */
    BANNER_DYE_TARGET(
        "FlagModel",
        "P43: the dye-taking mesh is the *FlagModel factory - legacy TintDiscovery.java:84 (classEntry"
            + " .contains(\"Flag\") && endsWith(\"Model.class\")), rationale :81-85; 'LIKELY-hard - would"
            + " need renderer data-flow analysis of which submitted buffer receives the DyeColor'"
            + " (08 3 row 34); consulted by BlockTintFlagResolver"),

    /**
     * D54 - the fixed sheet texture stems ({@code = Sheets.<X>} sprite prefixes) per catalog
     * family. The legacy flow hard-codes the same values; deriving them from {@code Sheets.<clinit>}
     * stays a post-bridge option (08 A5). Conduit is absent - its base derives from
     * {@code ConduitRenderer.<clinit>}.
     */
    SHEET_TEXTURE_BASES(
        Map.ofEntries(
            Map.entry(CatalogFamily.SHULKER_BOX, "entity/shulker/shulker"),
            Map.entry(CatalogFamily.CHEST, "entity/chest/"),
            Map.entry(CatalogFamily.BED, "entity/bed/"),
            Map.entry(CatalogFamily.SIGN, "entity/signs/"),
            Map.entry(CatalogFamily.HANGING_SIGN, "entity/signs/hanging/"),
            Map.entry(CatalogFamily.BELL, "entity/"),
            Map.entry(CatalogFamily.DECORATED_POT, "entity/decorated_pot/decorated_pot_base"),
            Map.entry(CatalogFamily.BANNER, "entity/banner/banner_base")),
        "D54: the Sheets.<X> sprite stems the legacy flow hard-codes (BlockListDiscovery constants;"
            + " shulker colorToShulkerSprite concat base, bed/sign/hanging/banner/pot sheet prefixes,"
            + " chest special-renderer sheet dir, bell BLOCK_ENTITIES_MAPPER entity/ base - 08 A5 rows"
            + " 21-28); Sheets.<clinit> derivation deferred post-bridge"),

    /**
     * The PLAYER skull skin stem - legacy chases {@code DefaultPlayerSkin.getDefaultSkin} through
     * its array-index shape (SourceDiscovery :969-1046) to this stable value; declared since the
     * chase bottoms out in a constant.
     */
    PLAYER_SKULL_SKIN(
        "entity/player/slim/steve",
        "the DefaultPlayerSkin.getDefaultSkin chase result (legacy SourceDiscovery:969-1046) - the"
            + " stable default skin the PLAYER SkullBlock$Types entry renders; every other type reads"
            + " SKIN_BY_TYPE from the SkullBlockRenderer populate lambda");

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

    /** A catalog-bearing family of the P33 roster - the dispatch token its builder rides. */
    enum CatalogFamily {
        SHULKER_BOX, CHEST, BED, SIGN, HANGING_SIGN, CONDUIT, BELL,
        DECORATED_POT, COPPER_GOLEM_STATUE, SKULL, BANNER
    }

    /**
     * The P35 chest binding: the fixed block-to-field entries plus the copper field-name prefix
     * the weather composition prepends.
     *
     * @param fixedFields block local id -> {@code ChestSpecialRenderer} texture-field name
     * @param copperFieldPrefix the {@code COPPER_} prefix before the {@code WeatherState} name
     */
    record ChestVariants(@NotNull Map<String, String> fixedFields, @NotNull String copperFieldPrefix) {}

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

    /**
     * The catalog split id for a skull block's type prefix, or {@code null} when the prefix is
     * not a known skull type.
     *
     * @param typePrefix the block-id type prefix ({@code skeleton}, {@code wither_skeleton})
     * @return the declared split id, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable String skullTypeSplit(@NotNull String typePrefix) {
        return ((Map<String, String>) SKULL_TYPE_SPLIT.value).get(typePrefix);
    }

    /**
     * The P33 catalog family a subject dispatches to, or {@code null} when the subject emits no
     * block catalog (enchanting_table / lectern).
     *
     * @param subjectLocalId the namespace-less subject id
     * @return the family token, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static @Nullable CatalogFamily catalogFamily(@NotNull String subjectLocalId) {
        return ((Map<String, CatalogFamily>) FAMILY_ROSTER.value).get(subjectLocalId);
    }

    /**
     * The fixed {@code ChestSpecialRenderer} texture field for a chest block, or {@code null}
     * when the block is a copper chest (composed as {@code COPPER_<weather>}).
     *
     * @param blockLocal the block's namespace-less id
     * @return the field name, or {@code null} for the copper composition
     */
    static @Nullable String chestVariantField(@NotNull String blockLocal) {
        return ((ChestVariants) CHEST_VARIANT.value).fixedFields().get(blockLocal);
    }

    /** The {@code COPPER_} field-name prefix the chest weather composition prepends. */
    static @NotNull String chestCopperFieldPrefix() {
        return ((ChestVariants) CHEST_VARIANT.value).copperFieldPrefix();
    }

    /** The factory-class suffix marking the dye-taking mesh (the banner {@code *FlagModel}) [P43]. */
    static @NotNull String dyeTargetModelSuffix() {
        return (String) BANNER_DYE_TARGET.value;
    }

    /**
     * The declared sheet texture stem for a catalog family [D54].
     *
     * @param family the P33 family token
     * @return the {@code Sheets.<X>} stem
     */
    @SuppressWarnings("unchecked")
    static @NotNull String sheetTextureBase(@NotNull CatalogFamily family) {
        String base = ((Map<CatalogFamily, String>) SHEET_TEXTURE_BASES.value).get(family);
        if (base == null) throw new IllegalArgumentException("No declared sheet base for family " + family);
        return base;
    }

    /** The declared PLAYER skull skin stem (the DefaultPlayerSkin chase result). */
    static @NotNull String playerSkullSkin() {
        return (String) PLAYER_SKULL_SKIN.value;
    }

}
