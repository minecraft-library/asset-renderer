package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The declared split-id facts a bytecode walk cannot see. Vanilla has ONE {@code BlockEntityType.BED},
 * {@code SKULL}, {@code BANNER}, etc.; the head/foot, 4-way skull, standing/wall and flag-submodel
 * splits and their id names are OUR output convention - undetectable by bytecode inspection alone, so
 * declared here with mandatory provenance. Never fetches ({@code PolicyPurityTest}).
 *
 * <p>The split MECHANICS stay bytecode ({@link BlockGeometrySourceResolver} runs generic
 * ModelLayers x {@code LayerDefinitionIndex} detection first); this enum only supplies the
 * split-id vocabulary and the branch-parameter values (banner / sign {@code withStick}, hanging-sign
 * attachment enum) the manifest requests carry.
 *
 * <p>A row whose fact sits at a walkable member declares that {@link Navigation} coordinate in
 * place of a value; the consuming resolver re-enters the engine there and reads it.
 */
enum BlockFamilyPolicies implements NavigationPolicy {

    /**
     * The factory-method-to-split-id map. A block-entity renderer that references
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
        "our split ids for the multi-mesh families (bed head/foot, decorated_pot base/sides, bell_body,"
            + " 4-way skull); vanilla registers one BlockEntityType each. Skull dims stay derived from each"
            + " wrapper's LayerDefinition.create tail - mob 64x32, humanoid 64x64"),

    /**
     * The sign factory-parameter table, keyed on the factory method the renderer's own
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
        "sign withStick + hanging-sign attachment split ids; the factory branch structure is bytecode, the"
            + " id<->constant naming is ours"),

    /**
     * The banner {@code ModelLayers} field to (split id, {@code withStick})
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
        "banner ModelLayers field -> (split id, withStick); the ICONST split is baked in createRoots, and the"
            + " flag/pole split is the _FLAG field suffix, matched against a BannerFlagModel endsWith test"),

    /**
     * The {@code SkullBlock$Types} to catalog split-id map. Vanilla has ONE
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
        "the 4-way skull split grouping the seven SkullBlock$Types by shared mesh + texture dims (mob 64x32 /"
            + " humanoid 64x64 / dragon mesh / piglin ears); vanilla registers one BlockEntityType.SKULL, so the"
            + " split is ours"),

    /**
     * The catalog family-dispatch roster: which subjects emit a {@code blocks[]} catalog and
     * which family builder each rides. The family SET is derivable from discovery; the
     * split/texture-source divergence per family is the declared fact. Subjects absent here
     * (enchanting_table / lectern) carry no catalog; the part-only pseudo families ride
     * {@link BlockTransformPolicies#PART_COMPOSITION} instead.
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
        "the family-dispatch roster: the family set is derivable from discovery, so only the per-family"
            + " split / texture-source divergence is declared; part-only pseudo families ride PART_COMPOSITION"),

    /**
     * The chest block-to-{@code ChestSpecialRenderer}-texture-field binding: the three fixed
     * classes plus the copper composition rule ({@code COPPER_ + <WeatherState>}, {@code UNAFFECTED}
     * for the bare base, waxed sharing the unwaxed sheet). The binding is spread across vanilla's
     * special-renderer dispatch - not one walkable site.
     */
    CHEST_VARIANT(
        new ChestVariants(
            Map.of("chest", "REGULAR", "trapped_chest", "TRAPPED", "ender_chest", "ENDER_CHEST"),
            "COPPER_"),
        "chest class->texture-field binding + COPPER_<weather> composition with the UNAFFECTED fallback; the"
            + " class<->field binding is spread across vanilla's special-renderer dispatch rather than sitting at"
            + " one walkable site"),

    /**
     * The coordinate of the mesh a tint-bearing renderer dyes. The banner renderer's submit method
     * hands a model to three submits; only the one whose callee reads
     * {@code DyeColor.getTextureDiffuseColor} routes the dye, and the model that submit receives is
     * the dye-taking mesh. The pole and the flag base submit untinted.
     */
    BANNER_DYE_TARGET(
        new Navigation.At("net/minecraft/client/renderer/blockentity/BannerRenderer", "submitBanner", null),
        "the dye-taking mesh is the model argument of the submit whose callee reads"
            + " DyeColor.getTextureDiffuseColor; BlockTintFlagResolver re-enters the coordinate and reads it"),

    /**
     * The sheet field each catalog family's texture base is bound to. Two halves meet here. The
     * ROUTING - which family reads which field - is authored, because no naming rule derives it:
     * six families name a mapper of their own, the bell names the generic block-entity mapper, and
     * "the first sprite this mapper composes" would hand the chest the ender-chest stem rather than
     * the bare prefix the resolver appends its own discriminator to. The BASE STRINGS are read at
     * the fields: a sprite-mapper field carries its base as the prefix its composition prepends, a
     * sprite-id field carries the mapper's own base followed by the stem it was composed with.
     * Conduit is absent - its base derives from {@code ConduitRenderer.<clinit>}.
     */
    SHEET_TEXTURE_BASES(
        Map.ofEntries(
            sheetEntry(CatalogFamily.SHULKER_BOX, "DEFAULT_SHULKER_TEXTURE_LOCATION"),
            sheetEntry(CatalogFamily.CHEST, "CHEST_MAPPER"),
            sheetEntry(CatalogFamily.BED, "BED_MAPPER"),
            sheetEntry(CatalogFamily.SIGN, "SIGN_MAPPER"),
            sheetEntry(CatalogFamily.HANGING_SIGN, "HANGING_SIGN_MAPPER"),
            sheetEntry(CatalogFamily.BELL, "BLOCK_ENTITIES_MAPPER"),
            sheetEntry(CatalogFamily.DECORATED_POT, "DECORATED_POT_BASE"),
            sheetEntry(CatalogFamily.BANNER, "BANNER_BASE")),
        "the field each family's base is bound to, authored because no naming rule derives it - six"
            + " families name a mapper of their own, the bell names the generic block-entity mapper, and"
            + " the shulker / decorated_pot / banner name a sprite id composed from a mapper plus a stem;"
            + " every base string is read at the field the family names"),

    /**
     * The coordinate of the PLAYER skull skin stem. The default-skin accessor indexes one element
     * of the class's skin array, and the element built at that index carries the stem; every other
     * skull type reads its skin from the {@code SkullBlockRenderer} populate lambda instead.
     */
    PLAYER_SKULL_SKIN(
        new Navigation.At("net/minecraft/client/resources/DefaultPlayerSkin", "getDefaultSkin", null),
        "the PLAYER skull skin is the default-skin array element getDefaultSkin indexes;"
            + " BlockCatalogResolver re-enters the coordinate, reads the index and the stem bound at it");

    /**
     * Pairs a catalog family with the sheet coordinate its texture base is read at, so a row names
     * the sheet class once.
     *
     * @param family the catalog family
     * @param field the sheet field the base is bound to - a sprite mapper or a sprite id
     * @return the roster row
     */
    private static @NotNull Map.Entry<CatalogFamily, Navigation.At> sheetEntry(
        @NotNull CatalogFamily family, @NotNull String field) {
        return Map.entry(family, new Navigation.At("net/minecraft/client/renderer/Sheets", field, null));
    }

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

    /** A catalog-bearing family of the family-dispatch roster - the dispatch token its builder rides. */
    enum CatalogFamily {
        SHULKER_BOX, CHEST, BED, SIGN, HANGING_SIGN, CONDUIT, BELL,
        DECORATED_POT, COPPER_GOLEM_STATUE, SKULL, BANNER
    }

    /**
     * The chest binding: the fixed block-to-field entries plus the copper field-name prefix
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
        if (this.value instanceof Navigation coordinate) return coordinate;
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
     * The catalog family a subject dispatches to, or {@code null} when the subject emits no
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

    /** The coordinate the dye-taking mesh is recovered at. */
    static Navigation.@NotNull At dyeTargetCoordinate() {
        return (Navigation.At) BANNER_DYE_TARGET.value;
    }

    /**
     * The coordinate a catalog family's sheet texture base is read at.
     *
     * @param family the catalog family token
     * @return the sheet-field coordinate
     * @throws IllegalArgumentException if the family names no sheet field
     */
    @SuppressWarnings("unchecked")
    static Navigation.@NotNull At sheetCoordinate(@NotNull CatalogFamily family) {
        Navigation.At coordinate = ((Map<CatalogFamily, Navigation.At>) SHEET_TEXTURE_BASES.value).get(family);
        if (coordinate == null) throw new IllegalArgumentException("No sheet coordinate for family " + family);
        return coordinate;
    }

    /** The coordinate the PLAYER skull skin stem is recovered at. */
    static Navigation.@NotNull At playerSkullCoordinate() {
        return (Navigation.At) PLAYER_SKULL_SKIN.value;
    }

}
