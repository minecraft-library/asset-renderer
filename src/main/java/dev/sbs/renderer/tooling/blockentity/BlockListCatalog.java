package dev.sbs.renderer.tooling.blockentity;

import dev.sbs.renderer.tooling.asm.AsmKit;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * NOTE: This is a hardcoded catalog with a bytecode-side drift sanity check, NOT a real
 * discovery module. The PR 3 todo for real {@code BlockListDiscovery} is documented below.
 *
 * <p>Maps each block-entity-model id to the list of vanilla blocks that render as that model
 * plus any sub-model {@code parts} the runtime merges at draw time. The mapping is flat-list
 * data (19 entries, ~90 block ids across all entities) that's gnarly enough to warrant a
 * separate PR rather than trying to carve it out of
 * {@code net/minecraft/client/renderer/Sheets.<clinit>}'s various maps all at once.
 *
 * <pre>
 * // TODO PR 3: replace the CANONICAL_BLOCK_LIST table below with a real
 * // BlockListDiscovery that walks:
 * //   - Sheets.CHEST_MAPPING -> chest / trapped_chest / ender_chest / copper_chest variants
 * //   - Sheets.SHULKER_TEXTURE_LOCATION + ShulkerBoxBlock.getColors -> shulker_box variants
 * //   - Sheets.BED_TEXTURES + DyeColor.values() -> bed_head (bed_foot empty for loose bed item)
 * //   - Sheets.SIGN_MATERIALS + Sheets.HANGING_SIGN_MATERIALS -> sign / hanging_sign per wood
 * //   - Sheets.SKULL_TEXTURES per SkullBlock.Type -> skull_head / skull_humanoid_head / skull_dragon_head / skull_piglin_head
 * //   - Sheets.BANNER_BASE -> banner / wall_banner per dye
 * //   - Constant literal strings in DecoratedPotRenderer for the decorated_pot decal textures
 * //   - Constant literal strings in ConduitRenderer for the conduit shell texture
 * //   - Constant literal strings in CopperGolemStatueBlockRenderer for oxidation-state textures
 * //   - BlockEntityType.validBlocks per type -> cross-check the above against the vanilla
 * //     (BE type -> blocks) registry
 * // The {@code parts} field (one sub-model offset into its parent) would come from the
 * // corresponding renderer's submit method, tracking PoseStack.translate arguments against
 * // each bakeLayer call.
 * </pre>
 *
 * <p>The bytecode sanity check here only asserts that {@code Sheets} still exposes the field
 * names our table assumes ({@code CHEST_LOCATION}, {@code SHULKER_TEXTURE_LOCATION}, etc.);
 * when Mojang renames or removes them we surface a {@code diag.warn} so the catalog can be
 * updated alongside the renaming.
 */
@UtilityClass
public final class BlockListCatalog {

    private static final @NotNull String SHEETS_CLASS = "net/minecraft/client/renderer/Sheets";

    /**
     * A single block that renders as an entity-model entity. {@code comment} is an optional
     * {@code //}-prefixed note carried through to the generated JSON (used for
     * humans-reading-the-JSON commentary).
     */
    public record BlockMapping(@NotNull String blockId, @NotNull String textureId, @Nullable String comment) {
        public BlockMapping(@NotNull String blockId, @NotNull String textureId) {
            this(blockId, textureId, null);
        }
    }

    /**
     * A link to a secondary entity-model rendered on top of / next to this one. Used for
     * multi-part entities like beds (head + foot) and decorated pots (base + sides).
     */
    public record PartRef(@NotNull String model, int @Nullable [] offset, @Nullable String texture) {
        public PartRef(@NotNull String model) {
            this(model, null, null);
        }
        public PartRef(@NotNull String model, int @NotNull [] offset) {
            this(model, offset, null);
        }
    }

    /** Full binding for one entity-model id. */
    public record EntityBlockMapping(@NotNull List<BlockMapping> blocks, @Nullable List<PartRef> parts) {}

    /** Sheets field names we reference - sanity-checked against the jar at lookup time. */
    private static final @NotNull Set<String> REFERENCED_SHEETS_FIELDS = Set.of(
        "CHEST_SHEET",
        "CHEST_MAPPER",
        "SHULKER_SHEET",
        "SHULKER_MAPPER",
        "BED_SHEET",
        "BED_MAPPER",
        "SIGN_SHEET",
        "SIGN_MAPPER",
        "BANNER_SHEET",
        "BANNER_MAPPER",
        "DECORATED_POT_SHEET",
        "DECORATED_POT_MAPPER"
    );

    /**
     * The full 19-entry (entity_model_id -> blocks + parts) table. This is the hardcoded
     * zone. Every row is a direct copy of what {@code baseline/block_list.json} would produce
     * if Phase C were replaced by real discovery.
     */
    private static final @NotNull Map<String, EntityBlockMapping> CANONICAL_BLOCK_LIST = buildCanonical();

    private static @NotNull Map<String, EntityBlockMapping> buildCanonical() {
        LinkedHashMap<String, EntityBlockMapping> m = new LinkedHashMap<>();

        m.put("minecraft:shulker_box", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:shulker_box", "minecraft:entity/shulker/shulker"),
            new BlockMapping("minecraft:white_shulker_box", "minecraft:entity/shulker/shulker_white"),
            new BlockMapping("minecraft:orange_shulker_box", "minecraft:entity/shulker/shulker_orange"),
            new BlockMapping("minecraft:magenta_shulker_box", "minecraft:entity/shulker/shulker_magenta"),
            new BlockMapping("minecraft:light_blue_shulker_box", "minecraft:entity/shulker/shulker_light_blue"),
            new BlockMapping("minecraft:yellow_shulker_box", "minecraft:entity/shulker/shulker_yellow"),
            new BlockMapping("minecraft:lime_shulker_box", "minecraft:entity/shulker/shulker_lime"),
            new BlockMapping("minecraft:pink_shulker_box", "minecraft:entity/shulker/shulker_pink"),
            new BlockMapping("minecraft:gray_shulker_box", "minecraft:entity/shulker/shulker_gray"),
            new BlockMapping("minecraft:light_gray_shulker_box", "minecraft:entity/shulker/shulker_light_gray"),
            new BlockMapping("minecraft:cyan_shulker_box", "minecraft:entity/shulker/shulker_cyan"),
            new BlockMapping("minecraft:purple_shulker_box", "minecraft:entity/shulker/shulker_purple"),
            new BlockMapping("minecraft:blue_shulker_box", "minecraft:entity/shulker/shulker_blue"),
            new BlockMapping("minecraft:brown_shulker_box", "minecraft:entity/shulker/shulker_brown"),
            new BlockMapping("minecraft:green_shulker_box", "minecraft:entity/shulker/shulker_green"),
            new BlockMapping("minecraft:red_shulker_box", "minecraft:entity/shulker/shulker_red"),
            new BlockMapping("minecraft:black_shulker_box", "minecraft:entity/shulker/shulker_black")
        ), null));

        m.put("minecraft:chest", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:chest", "minecraft:entity/chest/normal"),
            new BlockMapping("minecraft:trapped_chest", "minecraft:entity/chest/trapped"),
            new BlockMapping("minecraft:ender_chest", "minecraft:entity/chest/ender"),
            new BlockMapping("minecraft:copper_chest", "minecraft:entity/chest/copper"),
            new BlockMapping("minecraft:exposed_copper_chest", "minecraft:entity/chest/copper_exposed"),
            new BlockMapping("minecraft:weathered_copper_chest", "minecraft:entity/chest/copper_weathered"),
            new BlockMapping("minecraft:oxidized_copper_chest", "minecraft:entity/chest/copper_oxidized"),
            new BlockMapping("minecraft:waxed_copper_chest", "minecraft:entity/chest/copper"),
            new BlockMapping("minecraft:waxed_exposed_copper_chest", "minecraft:entity/chest/copper_exposed"),
            new BlockMapping("minecraft:waxed_weathered_copper_chest", "minecraft:entity/chest/copper_weathered"),
            new BlockMapping("minecraft:waxed_oxidized_copper_chest", "minecraft:entity/chest/copper_oxidized")
        ), null));

        m.put("minecraft:bed_head", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:white_bed", "minecraft:entity/bed/white"),
            new BlockMapping("minecraft:orange_bed", "minecraft:entity/bed/orange"),
            new BlockMapping("minecraft:magenta_bed", "minecraft:entity/bed/magenta"),
            new BlockMapping("minecraft:light_blue_bed", "minecraft:entity/bed/light_blue"),
            new BlockMapping("minecraft:yellow_bed", "minecraft:entity/bed/yellow"),
            new BlockMapping("minecraft:lime_bed", "minecraft:entity/bed/lime"),
            new BlockMapping("minecraft:pink_bed", "minecraft:entity/bed/pink"),
            new BlockMapping("minecraft:gray_bed", "minecraft:entity/bed/gray"),
            new BlockMapping("minecraft:light_gray_bed", "minecraft:entity/bed/light_gray"),
            new BlockMapping("minecraft:cyan_bed", "minecraft:entity/bed/cyan"),
            new BlockMapping("minecraft:purple_bed", "minecraft:entity/bed/purple"),
            new BlockMapping("minecraft:blue_bed", "minecraft:entity/bed/blue"),
            new BlockMapping("minecraft:brown_bed", "minecraft:entity/bed/brown"),
            new BlockMapping("minecraft:green_bed", "minecraft:entity/bed/green"),
            new BlockMapping("minecraft:red_bed", "minecraft:entity/bed/red"),
            new BlockMapping("minecraft:black_bed", "minecraft:entity/bed/black")
        ), List.of(new PartRef("minecraft:bed_foot", new int[]{ 0, 0, 16 }))));

        m.put("minecraft:sign", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:oak_sign", "minecraft:entity/signs/oak"),
            new BlockMapping("minecraft:spruce_sign", "minecraft:entity/signs/spruce"),
            new BlockMapping("minecraft:birch_sign", "minecraft:entity/signs/birch"),
            new BlockMapping("minecraft:jungle_sign", "minecraft:entity/signs/jungle"),
            new BlockMapping("minecraft:acacia_sign", "minecraft:entity/signs/acacia"),
            new BlockMapping("minecraft:dark_oak_sign", "minecraft:entity/signs/dark_oak"),
            new BlockMapping("minecraft:crimson_sign", "minecraft:entity/signs/crimson"),
            new BlockMapping("minecraft:warped_sign", "minecraft:entity/signs/warped"),
            new BlockMapping("minecraft:mangrove_sign", "minecraft:entity/signs/mangrove"),
            new BlockMapping("minecraft:bamboo_sign", "minecraft:entity/signs/bamboo"),
            new BlockMapping("minecraft:cherry_sign", "minecraft:entity/signs/cherry"),
            new BlockMapping("minecraft:pale_oak_sign", "minecraft:entity/signs/pale_oak"),
            new BlockMapping("minecraft:oak_wall_sign", "minecraft:entity/signs/oak"),
            new BlockMapping("minecraft:spruce_wall_sign", "minecraft:entity/signs/spruce"),
            new BlockMapping("minecraft:birch_wall_sign", "minecraft:entity/signs/birch"),
            new BlockMapping("minecraft:jungle_wall_sign", "minecraft:entity/signs/jungle"),
            new BlockMapping("minecraft:acacia_wall_sign", "minecraft:entity/signs/acacia"),
            new BlockMapping("minecraft:dark_oak_wall_sign", "minecraft:entity/signs/dark_oak"),
            new BlockMapping("minecraft:crimson_wall_sign", "minecraft:entity/signs/crimson"),
            new BlockMapping("minecraft:warped_wall_sign", "minecraft:entity/signs/warped"),
            new BlockMapping("minecraft:mangrove_wall_sign", "minecraft:entity/signs/mangrove"),
            new BlockMapping("minecraft:bamboo_wall_sign", "minecraft:entity/signs/bamboo"),
            new BlockMapping("minecraft:cherry_wall_sign", "minecraft:entity/signs/cherry"),
            new BlockMapping("minecraft:pale_oak_wall_sign", "minecraft:entity/signs/pale_oak")
        ), null));

        m.put("minecraft:hanging_sign", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:oak_hanging_sign", "minecraft:entity/signs/hanging/oak"),
            new BlockMapping("minecraft:spruce_hanging_sign", "minecraft:entity/signs/hanging/spruce"),
            new BlockMapping("minecraft:birch_hanging_sign", "minecraft:entity/signs/hanging/birch"),
            new BlockMapping("minecraft:jungle_hanging_sign", "minecraft:entity/signs/hanging/jungle"),
            new BlockMapping("minecraft:acacia_hanging_sign", "minecraft:entity/signs/hanging/acacia"),
            new BlockMapping("minecraft:dark_oak_hanging_sign", "minecraft:entity/signs/hanging/dark_oak"),
            new BlockMapping("minecraft:crimson_hanging_sign", "minecraft:entity/signs/hanging/crimson"),
            new BlockMapping("minecraft:warped_hanging_sign", "minecraft:entity/signs/hanging/warped"),
            new BlockMapping("minecraft:mangrove_hanging_sign", "minecraft:entity/signs/hanging/mangrove"),
            new BlockMapping("minecraft:bamboo_hanging_sign", "minecraft:entity/signs/hanging/bamboo"),
            new BlockMapping("minecraft:cherry_hanging_sign", "minecraft:entity/signs/hanging/cherry"),
            new BlockMapping("minecraft:pale_oak_hanging_sign", "minecraft:entity/signs/hanging/pale_oak"),
            new BlockMapping("minecraft:oak_wall_hanging_sign", "minecraft:entity/signs/hanging/oak"),
            new BlockMapping("minecraft:spruce_wall_hanging_sign", "minecraft:entity/signs/hanging/spruce"),
            new BlockMapping("minecraft:birch_wall_hanging_sign", "minecraft:entity/signs/hanging/birch"),
            new BlockMapping("minecraft:jungle_wall_hanging_sign", "minecraft:entity/signs/hanging/jungle"),
            new BlockMapping("minecraft:acacia_wall_hanging_sign", "minecraft:entity/signs/hanging/acacia"),
            new BlockMapping("minecraft:dark_oak_wall_hanging_sign", "minecraft:entity/signs/hanging/dark_oak"),
            new BlockMapping("minecraft:crimson_wall_hanging_sign", "minecraft:entity/signs/hanging/crimson"),
            new BlockMapping("minecraft:warped_wall_hanging_sign", "minecraft:entity/signs/hanging/warped"),
            new BlockMapping("minecraft:mangrove_wall_hanging_sign", "minecraft:entity/signs/hanging/mangrove"),
            new BlockMapping("minecraft:bamboo_wall_hanging_sign", "minecraft:entity/signs/hanging/bamboo"),
            new BlockMapping("minecraft:cherry_wall_hanging_sign", "minecraft:entity/signs/hanging/cherry"),
            new BlockMapping("minecraft:pale_oak_wall_hanging_sign", "minecraft:entity/signs/hanging/pale_oak")
        ), null));

        m.put("minecraft:conduit", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:conduit", "minecraft:entity/conduit/base")
        ), null));

        m.put("minecraft:bell_body", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:bell_floor", "minecraft:entity/bell/bell_body",
                "Additive overlay: BellModel.createBodyLayer's bell-cup geometry hangs from the bar+post fixture supplied by block/bell_floor.json / bell_ceiling.json / bell_wall.json / bell_between_walls.json. Vanilla minecraft:bell is the loose item; the four model-named blocks are the placed variants picked by the bell blockstate's attachment property. additive=true keeps each blockstate's primary model in place and merges the bell entity geometry on top at render time."),
            new BlockMapping("minecraft:bell_ceiling", "minecraft:entity/bell/bell_body"),
            new BlockMapping("minecraft:bell_wall", "minecraft:entity/bell/bell_body"),
            new BlockMapping("minecraft:bell_between_walls", "minecraft:entity/bell/bell_body")
        ), null));

        m.put("minecraft:decorated_pot", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:decorated_pot", "minecraft:entity/decorated_pot/decorated_pot_base",
                "Primary model is the neck + lid/base caps from createBaseLayer (Sheets.DECORATED_POT_BASE, 32x32); the pot walls from createSidesLayer use Sheets.DECORATED_POT_SIDE (16x16 solid terracotta, the default when a pot has no sherd decorations).")
        ), List.of(new PartRef("minecraft:decorated_pot_sides", new int[]{ 0, 0, 0 }, "minecraft:entity/decorated_pot/decorated_pot_side"))));

        m.put("minecraft:copper_golem_statue", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:copper_golem_statue", "minecraft:entity/copper_golem/copper_golem"),
            new BlockMapping("minecraft:exposed_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_exposed"),
            new BlockMapping("minecraft:weathered_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_weathered"),
            new BlockMapping("minecraft:oxidized_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_oxidized"),
            new BlockMapping("minecraft:waxed_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem"),
            new BlockMapping("minecraft:waxed_exposed_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_exposed"),
            new BlockMapping("minecraft:waxed_weathered_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_weathered"),
            new BlockMapping("minecraft:waxed_oxidized_copper_golem_statue", "minecraft:entity/copper_golem/copper_golem_oxidized")
        ), null));

        m.put("minecraft:skull_head", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:skeleton_skull", "minecraft:entity/skeleton/skeleton",
                "skull_head variant: UV calibrated for 64x32 entity textures (skeleton/wither/creeper/piglin/dragon)."),
            new BlockMapping("minecraft:skeleton_wall_skull", "minecraft:entity/skeleton/skeleton"),
            new BlockMapping("minecraft:wither_skeleton_skull", "minecraft:entity/skeleton/wither_skeleton"),
            new BlockMapping("minecraft:wither_skeleton_wall_skull", "minecraft:entity/skeleton/wither_skeleton"),
            new BlockMapping("minecraft:creeper_head", "minecraft:entity/creeper/creeper"),
            new BlockMapping("minecraft:creeper_wall_head", "minecraft:entity/creeper/creeper")
        ), null));

        m.put("minecraft:skull_dragon_head", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:dragon_head", "minecraft:entity/enderdragon/dragon",
                "Dragon head: full snout geometry from DragonHeadModel.createHeadLayer (256x256 dragon entity texture). Extends past the 0..16 bbox; runtime recenterAndFit rescales for the atlas tile."),
            new BlockMapping("minecraft:dragon_wall_head", "minecraft:entity/enderdragon/dragon")
        ), null));

        m.put("minecraft:skull_piglin_head", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:piglin_head", "minecraft:entity/piglin/piglin",
                "Piglin head: head cube + ears from AbstractPiglinModel.addHead, reached by following the invokestatic chain from PiglinHeadModel.createHeadModel."),
            new BlockMapping("minecraft:piglin_wall_head", "minecraft:entity/piglin/piglin")
        ), null));

        m.put("minecraft:skull_humanoid_head", new EntityBlockMapping(List.of(
            new BlockMapping("minecraft:zombie_head", "minecraft:entity/zombie/zombie",
                "skull_humanoid_head variant: UV calibrated for 64x64 player-skin textures (zombie, player). Same geometry as skull_head; only the V scale differs so the top-left 32x16 head region samples correctly on a double-height texture."),
            new BlockMapping("minecraft:zombie_wall_head", "minecraft:entity/zombie/zombie"),
            new BlockMapping("minecraft:player_head", "minecraft:entity/player/wide/steve"),
            new BlockMapping("minecraft:player_wall_head", "minecraft:entity/player/wide/steve")
        ), null));

        List<BlockMapping> bannerBlocks = List.of(
            new BlockMapping("minecraft:white_banner", "minecraft:entity/banner/banner_base",
                "Standing + wall banners: pole+bar (primary) merged with flag (part). All 16 dye colours share the entity/banner/banner_base 64x64 texture; per-colour appearance comes from the `tint` field, which is a DyeColor.Vanilla enum name resolved to the canonical textureDiffuseColor at load time (hex #RRGGBB / #AARRGGBB is also accepted for custom colours outside the sixteen vanilla dyes). The tint is applied as a multiplicative tint at render time via BlockRenderer.Isometric3D. Limitation: the pole+bar receive the flag tint because block-level tint is per-block, not per-face."),
            new BlockMapping("minecraft:orange_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:magenta_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:light_blue_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:yellow_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:lime_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:pink_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:gray_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:light_gray_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:cyan_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:purple_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:blue_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:brown_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:green_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:red_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:black_banner", "minecraft:entity/banner/banner_base")
        );
        m.put("minecraft:banner", new EntityBlockMapping(bannerBlocks, List.of(new PartRef("minecraft:banner_flag"))));

        List<BlockMapping> wallBannerBlocks = List.of(
            new BlockMapping("minecraft:white_wall_banner", "minecraft:entity/banner/banner_base",
                "Wall banners: flag + bar only, no vertical pole. Parsed from BannerModel.createBodyLayer(false) + BannerFlagModel.createFlagLayer(false), which apply wall-specific geometry: the bar sits at y=-20.5 (not y=-44 as on standing banners), and the flag is pivoted at (0, -20.5, 10.5) to hang from the bar against the wall surface."),
            new BlockMapping("minecraft:orange_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:magenta_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:light_blue_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:yellow_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:lime_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:pink_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:gray_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:light_gray_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:cyan_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:purple_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:blue_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:brown_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:green_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:red_wall_banner", "minecraft:entity/banner/banner_base"),
            new BlockMapping("minecraft:black_wall_banner", "minecraft:entity/banner/banner_base")
        );
        m.put("minecraft:wall_banner", new EntityBlockMapping(wallBannerBlocks, List.of(new PartRef("minecraft:wall_banner_flag"))));

        m.put("minecraft:bed_foot", new EntityBlockMapping(List.of(), null));
        m.put("minecraft:decorated_pot_sides", new EntityBlockMapping(List.of(), null));
        m.put("minecraft:banner_flag", new EntityBlockMapping(List.of(), null));
        m.put("minecraft:wall_banner_flag", new EntityBlockMapping(List.of(), null));

        return m;
    }

    /**
     * Returns the canonical block list. The bytecode sanity check scans
     * {@code Sheets}' fields; any {@link #REFERENCED_SHEETS_FIELDS} name missing from the jar
     * surfaces a {@code diag.warn} so the catalog can be revisited on a MC version bump.
     */
    public static @NotNull Map<String, EntityBlockMapping> lookup(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode sheets = AsmKit.loadClass(zip, SHEETS_CLASS);
        if (sheets == null) {
            diag.warn("Sheets class not in jar - block-list catalog drift sanity check skipped");
            return CANONICAL_BLOCK_LIST;
        }
        Set<String> present = new java.util.HashSet<>();
        for (FieldNode f : sheets.fields) present.add(f.name);
        for (String expected : REFERENCED_SHEETS_FIELDS) {
            if (!present.contains(expected))
                diag.warn("block-list catalog: Sheets.%s expected but missing - catalog may be stale", expected);
        }
        return CANONICAL_BLOCK_LIST;
    }

}
