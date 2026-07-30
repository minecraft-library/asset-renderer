package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code blocks[]} node: the ordered {@code {block, texture, variant?, tint?}} rows
 * every split renders. Built once per subject; each split queries its rows by id.
 *
 * <p>Block ids come from {@link BlockRegistryIndex}; the split partition follows the block id
 * family (standing vs wall, skull type). Texture bases the vanilla bytecode carries are DERIVED
 * from the owning {@code <clinit>} (chest variants, copper oxidation, skull skins, conduit / bell);
 * the fixed sheet prefixes (shulker / bed / signs / banner / decorated_pot) are declared in
 * {@link BlockFamilyPolicies} ({@code SHEET_TEXTURE_BASES}). The colour / wood / weather / type
 * discriminators ride the block id directly, without walking constructor enum arguments.
 */
final class BlockCatalogResolver {

    /** The one variant gate any block row carries: the ceiling hanging sign's straight-chain mesh. */
    private static final @NotNull String ATTACHED_VARIANT = "attached=true";

    private static final @NotNull String SHULKER_BASE_LOCAL = "shulker_box";
    private static final @NotNull String HASHMAP_CONSUMER_DESC = "(Ljava/util/HashMap;)V";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull BlockRegistryIndex blockRegistry;
    private final @NotNull BlockEntitySubject subject;
    private final @NotNull List<String> splitIds;
    private final @NotNull Diagnostics diagnostics;

    private @Nullable Map<String, JsonTree> bySplitId;

    BlockCatalogResolver(
        @NotNull ToolingSession session,
        @NotNull BlockRegistryIndex blockRegistry,
        @NotNull BlockEntitySubject subject,
        @NotNull List<String> splitIds
    ) {
        this.cache = session.cache();
        this.blockRegistry = blockRegistry;
        this.subject = subject;
        this.splitIds = splitIds;
        this.diagnostics = session.diagnostics().child(subject.beTypeId());
    }

    /**
     * The split's {@code blocks} array, or {@code null} when it renders no blocks (the part-only
     * splits and the enchanting_table / lectern).
     *
     * @param splitId the models key
     * @return the {@code blocks} array node, or {@code null}
     */
    @Nullable JsonTree blocks(@NotNull String splitId) {
        if (this.bySplitId == null) this.bySplitId = build();
        return this.bySplitId.get(splitId);
    }

    /** Dispatches the subject to its family builder, producing split id -> block rows. */
    private @NotNull Map<String, JsonTree> build() {
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        BlockFamilyPolicies.CatalogFamily family = BlockFamilyPolicies.catalogFamily(this.subject.localId());
        if (family != null)                 // no catalog family (enchanting_table / lectern) = no catalog
            switch (family) {
                case SHULKER_BOX -> shulker(rows);
                case CHEST -> chest(rows);
                case BED -> bed(rows);
                case SIGN -> signs(rows, BlockFamilyPolicies.sheetTextureBase(family));
                case HANGING_SIGN -> hangingSigns(rows);
                case CONDUIT -> single(rows, conduitTexture());
                case BELL -> single(rows, bellTexture());
                case DECORATED_POT -> single(rows, BlockFamilyPolicies.sheetTextureBase(family));
                case COPPER_GOLEM_STATUE -> copperGolem(rows);
                case SKULL -> skull(rows);
                case BANNER -> banner(rows);
            }

        Map<String, JsonTree> out = new LinkedHashMap<>();
        rows.forEach((splitId, list) -> out.put(splitId, toArray(list)));
        return out;
    }

    // ------------------------------------------------------------------------------------
    // single-list families (all blocks under the sole non-part split)
    // ------------------------------------------------------------------------------------

    /** Shulker: the uncolored box first, then the 16 dyed boxes in DyeColor declaration order. */
    private void shulker(@NotNull Map<String, List<Row>> rows) {
        Map<String, Row> byColour = new LinkedHashMap<>();
        Row uncolored = null;
        for (String field : this.subject.blockFields()) {
            String local = blockLocal(field);
            Row row = new Row(blockId(field), shulkerTextureStem(local), null, null);
            if (local.equals(SHULKER_BASE_LOCAL)) uncolored = row;
            else byColour.put(stripSuffix(local, SHULKER_BASE_LOCAL), row);
        }
        List<Row> list = new ArrayList<>();
        if (uncolored != null) list.add(uncolored);
        orderByDye(byColour, list);
        rows.put(primarySplit(), list);
    }

    /** The shulker texture stem: the bare sheet for the uncolored box, {@code shulker_<color>} for the dyed. */
    private static @NotNull String shulkerTextureStem(@NotNull String blockLocal) {
        String sheet = BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.SHULKER_BOX);
        if (blockLocal.equals(SHULKER_BASE_LOCAL)) return sheet;
        return sheet + "_" + stripSuffix(blockLocal, SHULKER_BASE_LOCAL);
    }

    /** Bed: the 16 dyed beds under bed_head in DyeColor declaration order, texture entity/bed/<color>. */
    private void bed(@NotNull Map<String, List<Row>> rows) {
        Map<String, Row> byColour = new LinkedHashMap<>();
        for (String field : this.subject.blockFields()) {
            String colour = stripSuffix(blockLocal(field), "bed");
            byColour.put(colour,
                new Row(blockId(field), BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.BED) + colour, null, null));
        }
        List<Row> list = new ArrayList<>();
        orderByDye(byColour, list);
        rows.put(primarySplit(), list);
    }

    /** Appends {@code byColour}'s rows into {@code out} in DyeColor declaration order. */
    private void orderByDye(@NotNull Map<String, Row> byColour, @NotNull List<Row> out) {
        for (String colour : dyeColorOrder()) {
            Row row = byColour.get(colour);
            if (row != null) out.add(row);
        }
    }

    /**
     * Chest: 11 blocks under the sole split; texture base from ChestSpecialRenderer. The regular +
     * copper chests keep their validBlocks order, then trapped, then ender - the
     * {@code CHEST + TRAPPED_CHEST + ENDER_CHEST} concatenation, which is emitted row order and is
     * not the order the shared-renderer union arrives in.
     */
    private void chest(@NotNull Map<String, List<Row>> rows) {
        Map<String, String> bases = chestVariantBases();
        List<Row> main = new ArrayList<>();
        Row trapped = null;
        Row ender = null;
        for (String field : this.subject.blockFields()) {
            String local = blockLocal(field);
            String variantField = chestVariantField(local);
            String base = bases.get(variantField);
            if (base == null) {
                this.diagnostics.warn("no ChestSpecialRenderer texture base bound for field '%s' (block '%s') - empty base", variantField, field);
                base = "";
            }
            Row row = new Row(blockId(field),
                BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.CHEST) + base, null, null);
            switch (local) {
                case "trapped_chest" -> trapped = row;
                case "ender_chest" -> ender = row;
                default -> main.add(row);
            }
        }
        if (trapped != null) main.add(trapped);
        if (ender != null) main.add(ender);
        rows.put(primarySplit(), main);
    }

    /** Copper golem statue: 8 blocks (4 weathers x waxed/unwaxed) under the sole split. */
    private void copperGolem(@NotNull Map<String, List<Row>> rows) {
        Map<String, String> textures = copperGolemTextures();
        List<Row> list = new ArrayList<>();
        for (String field : this.subject.blockFields()) {
            String weather = copperWeatherField(blockLocal(field), "copper_golem_statue");
            String texture = textures.get(weather);
            if (texture == null) {
                this.diagnostics.warn("no CopperGolemOxidationLevels texture bound for weather '%s' (block '%s') - empty stem", weather, field);
                texture = "";
            }
            list.add(new Row(blockId(field), texture, null, null));
        }
        rows.put(primarySplit(), list);
    }

    /** A one-texture-for-every-block family (conduit / bell / decorated_pot). */
    private void single(@NotNull Map<String, List<Row>> rows, @NotNull String texture) {
        List<Row> list = new ArrayList<>();
        for (String field : this.subject.blockFields())
            list.add(new Row(blockId(field), texture, null, null));
        rows.put(primarySplit(), list);
    }

    // ------------------------------------------------------------------------------------
    // multi-split families
    // ------------------------------------------------------------------------------------

    /** Signs: split standing vs wall by id suffix; texture entity/signs/<wood>. */
    private void signs(@NotNull Map<String, List<Row>> rows, @NotNull String texturePrefix) {
        for (String field : this.subject.blockFields()) {
            String blockLocal = blockLocal(field);
            String split = assignBySuffix(blockLocal);
            if (split == null) continue;
            String wood = stripSuffix(blockLocal, localId(split));
            rows.computeIfAbsent(split, key -> new ArrayList<>())
                .add(new Row(blockId(field), texturePrefix + wood, null, null));
        }
    }

    /** Hanging signs: standing / wall by suffix, plus the hanging_sign_attached alternate (same blocks + variant). */
    private void hangingSigns(@NotNull Map<String, List<Row>> rows) {
        signs(rows, BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.HANGING_SIGN));
        // hanging_sign_attached re-lists the ceiling hanging sign's rows under variant attached=true.
        String source = splitEndingWith("hanging_sign");
        String attached = splitEndingWith("hanging_sign_attached");
        if (source == null || attached == null) return;
        List<Row> base = rows.get(source);
        if (base == null) return;
        List<Row> alternate = new ArrayList<>();
        for (Row row : base) alternate.add(new Row(row.block(), row.texture(), ATTACHED_VARIANT, null));
        rows.put(attached, alternate);
    }

    /** Skull: partition the 14 skull blocks across the 4 splits by SkullBlock$Types (from the id prefix). */
    private void skull(@NotNull Map<String, List<Row>> rows) {
        Map<String, String> skins = skullSkins();
        for (String field : this.subject.blockFields()) {
            String type = skullType(blockLocal(field));
            String split = BlockFamilyPolicies.skullTypeSplit(type);
            if (split == null) {
                this.diagnostics.warn("skull block '%s' has unknown type prefix '%s' - dropped", field, type);
                continue;
            }
            String skin = "player".equals(type) ? BlockFamilyPolicies.playerSkullSkin() : skins.get(type.toUpperCase(Locale.ROOT));
            if (skin == null) {
                this.diagnostics.warn("no skull skin for type '%s' (block '%s') - dropped", type, field);
                continue;
            }
            rows.computeIfAbsent(split, key -> new ArrayList<>())
                .add(new Row(blockId(field), skin, null, null));
        }
    }

    /** Banner: split standing vs wall by id suffix; shared banner_base texture; per-block dye tint. */
    private void banner(@NotNull Map<String, List<Row>> rows) {
        for (String field : this.subject.blockFields()) {
            String blockLocal = blockLocal(field);
            String split = assignBySuffix(blockLocal);
            if (split == null) continue;
            String tint = stripSuffix(blockLocal, localId(split)).toUpperCase(Locale.ROOT);
            rows.computeIfAbsent(split, key -> new ArrayList<>())
                .add(new Row(blockId(field), BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.BANNER), null, tint));
        }
    }

    // ------------------------------------------------------------------------------------
    // texture readers (renderer / enum <clinit>)
    // ------------------------------------------------------------------------------------

    /**
     * ChestSpecialRenderer field name -> texture base ({@code REGULAR->normal},
     * {@code COPPER_EXPOSED->copper_exposed}). Each field binds {@code LDC <base>; INVOKESTATIC
     * <sprite factory>; PUTSTATIC <field>}, so the base is the last string LDC before the store.
     */
    private @NotNull Map<String, String> chestVariantBases() {
        Map<String, String> out = new LinkedHashMap<>();
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.CHEST_SPECIAL_RENDERER);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return out;
        String pending = null;
        for (AbstractInsnNode in : clinit.instructions) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pending = literal;
                continue;
            }
            if (pending != null && AsmKit.isPutStatic(in, VanillaSourceClasses.Types.CHEST_SPECIAL_RENDERER)) {
                out.put(((FieldInsnNode) in).name, pending);
                pending = null;
            }
        }
        return out;
    }

    /** CopperGolemOxidationLevels field name -> stripped texture path (first path LDC after each NEW). */
    private @NotNull Map<String, String> copperGolemTextures() {
        Map<String, String> out = new LinkedHashMap<>();
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.COPPER_GOLEM_OXIDATION_LEVELS);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return out;
        String pending = null;
        for (AbstractInsnNode in : clinit.instructions) {
            if (in.getOpcode() == Opcodes.NEW) {
                pending = null;
                continue;
            }
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && pending == null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURE_DIR)) {
                pending = stripTexturePath(literal);
                continue;
            }
            if (AsmKit.isPutStatic(in, VanillaSourceClasses.Types.COPPER_GOLEM_OXIDATION_LEVELS) && pending != null) {
                out.put(((FieldInsnNode) in).name, pending);
                pending = null;
            }
        }
        return out;
    }

    /** SkullBlock$Types field name -> stripped skin path, from the SKIN_BY_TYPE populate lambda (PLAYER excluded). */
    private @NotNull Map<String, String> skullSkins() {
        Map<String, String> out = new LinkedHashMap<>();
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.SKULL_BLOCK_RENDERER);
        if (cn == null) return out;
        MethodNode lambda = null;
        for (MethodNode method : cn.methods)
            if (method.desc.equals(HASHMAP_CONSUMER_DESC) && (method.access & Opcodes.ACC_STATIC) != 0) {
                lambda = method;
                break;
            }
        if (lambda == null) return out;
        String pendingType = null;
        for (AbstractInsnNode in : lambda.instructions) {
            if (AsmKit.isGetStatic(in, VanillaSourceClasses.Types.SKULL_BLOCK_TYPES)) {
                pendingType = ((FieldInsnNode) in).name;
                continue;
            }
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && pendingType != null && literal.startsWith(VanillaSourceClasses.Paths.TEXTURE_DIR)) {
                out.putIfAbsent(pendingType, stripTexturePath(literal));
                pendingType = null;
            }
        }
        return out;
    }

    /**
     * ConduitRenderer: the mapper base path ({@code entity/conduit}, the first path-like LDC) plus
     * the {@code SHELL_TEXTURE} stem ({@code base}) -> {@code entity/conduit/base}.
     */
    private @NotNull String conduitTexture() {
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.CONDUIT_RENDERER);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return "";
        String base = null;
        String pendingStem = null;
        for (AbstractInsnNode in : clinit.instructions) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                if (literal.contains("/")) base = literal; else pendingStem = literal;
                continue;
            }
            if (base != null && pendingStem != null && AsmKit.isPutStatic(in, VanillaSourceClasses.Types.CONDUIT_RENDERER, "SHELL_TEXTURE"))
                return base + "/" + pendingStem;
        }
        return "";
    }

    /** BellRenderer: the {@code BELL_TEXTURE} stem ({@code bell/bell_body}) under the block-entities {@code entity/} prefix. */
    private @NotNull String bellTexture() {
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.BELL_RENDERER);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return "";
        String pendingStem = null;
        for (AbstractInsnNode in : clinit.instructions) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                pendingStem = literal;
                continue;
            }
            if (pendingStem != null && AsmKit.isPutStatic(in, VanillaSourceClasses.Types.BELL_RENDERER, "BELL_TEXTURE"))
                return BlockFamilyPolicies.sheetTextureBase(BlockFamilyPolicies.CatalogFamily.BELL) + pendingStem;
        }
        return "";
    }

    /**
     * The DyeColor serialized names in declaration order ({@code white, orange, magenta, ...}) -
     * the shulker / bed / banner colour ordering. Each enum constant binds its serialized name as
     * the SECOND string LDC between its {@code NEW} and closing {@code PUTSTATIC} (the first is the
     * constant name).
     */
    private @NotNull List<String> dyeColorOrder() {
        List<String> order = new ArrayList<>();
        ClassNode cn = this.cache.load(VanillaSourceClasses.Types.DYE_COLOR);
        MethodNode clinit = cn == null ? null : AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return order;
        String first = null;
        String second = null;
        for (AbstractInsnNode in : clinit.instructions) {
            if (in.getOpcode() == Opcodes.NEW && in instanceof TypeInsnNode type
                && type.desc.equals(VanillaSourceClasses.Types.DYE_COLOR)) {
                first = null;
                second = null;
                continue;
            }
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null) {
                if (first == null) first = literal;
                else if (second == null) second = literal;
                continue;
            }
            if (AsmKit.isPutStatic(in, VanillaSourceClasses.Types.DYE_COLOR) && second != null) {
                order.add(second);
                first = null;
                second = null;
            }
        }
        return order;
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    /** The subject's sole non-part split (the single-list families' block target). */
    private @NotNull String primarySplit() {
        for (String split : this.splitIds)
            if (!BlockTransformPolicies.isPartModel(split)) return split;
        return this.subject.beTypeId();
    }

    /** The non-part split whose local id is the longest suffix of {@code blockLocal}, or null when none matches. */
    private @Nullable String assignBySuffix(@NotNull String blockLocal) {
        String best = null;
        int bestLength = -1;
        for (String split : this.splitIds) {
            if (BlockTransformPolicies.isPartModel(split)) continue;
            String splitLocal = localId(split);
            if (blockLocal.endsWith(splitLocal) && splitLocal.length() > bestLength) {
                best = split;
                bestLength = splitLocal.length();
            }
        }
        return best;
    }

    /** The subject split whose local id ends with {@code suffix}, or null. */
    private @Nullable String splitEndingWith(@NotNull String suffix) {
        for (String split : this.splitIds)
            if (localId(split).equals(suffix)) return split;
        return null;
    }

    /** The block's namespace-less id ({@code chest} for {@code Blocks.CHEST}) - the family discriminant. */
    private @NotNull String blockLocal(@NotNull String field) {
        return localId(blockId(field));
    }

    /** The block's namespaced id via the registry index, falling back to the field-name derivation. */
    private @NotNull String blockId(@NotNull String field) {
        BlockRegistryIndex.Entry entry = this.blockRegistry.byField(field);
        if (entry != null) return entry.id();
        this.diagnostics.warn("block field '%s' not in the registry index - id derived from field name", field);
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + field.toLowerCase(Locale.ROOT);
    }

    /** The chest ChestSpecialRenderer variant field a block maps to (fixed binding + copper weather). */
    private static @NotNull String chestVariantField(@NotNull String blockLocal) {
        String fixed = BlockFamilyPolicies.chestVariantField(blockLocal);
        if (fixed != null) return fixed;
        return BlockFamilyPolicies.chestCopperFieldPrefix() + copperWeatherField(blockLocal, "copper_chest");
    }

    /**
     * The weather-state enum name a copper block maps to: {@code UNAFFECTED} for the bare base,
     * else the weather prefix before {@code _<base>} (waxed stripped, same texture as unwaxed).
     */
    private static @NotNull String copperWeatherField(@NotNull String blockLocal, @NotNull String base) {
        String local = blockLocal.startsWith("waxed_") ? blockLocal.substring("waxed_".length()) : blockLocal;
        if (local.equals(base)) return "UNAFFECTED";
        return local.substring(0, local.length() - base.length() - 1).toUpperCase(Locale.ROOT);
    }

    /** The skull type prefix ({@code skeleton_skull} / {@code creeper_wall_head} -> {@code skeleton} / {@code creeper}). */
    private static @NotNull String skullType(@NotNull String blockLocal) {
        for (String suffix : List.of("_wall_skull", "_wall_head", "_skull", "_head"))
            if (blockLocal.endsWith(suffix)) return blockLocal.substring(0, blockLocal.length() - suffix.length());
        return blockLocal;
    }

    /** {@code <prefix>_<value>} -> {@code value} (the family stem stripped from a block local id). */
    private static @NotNull String stripSuffix(@NotNull String blockLocal, @NotNull String suffix) {
        int cut = blockLocal.length() - suffix.length() - 1;
        return cut > 0 ? blockLocal.substring(0, cut) : blockLocal;
    }

    /** Strips the {@code textures/} prefix + {@code .png} suffix from a raw texture LDC (the stem currency). */
    private static @NotNull String stripTexturePath(@NotNull String raw) {
        String path = raw.startsWith(VanillaSourceClasses.Paths.TEXTURE_DIR)
            ? raw.substring(VanillaSourceClasses.Paths.TEXTURE_DIR.length()) : raw;
        return path.endsWith(VanillaSourceClasses.Paths.PNG_SUFFIX)
            ? path.substring(0, path.length() - VanillaSourceClasses.Paths.PNG_SUFFIX.length()) : path;
    }

    /** A block id / split id stripped of its {@code minecraft:} namespace. */
    private static @NotNull String localId(@NotNull String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Materialises a row list into the {@code blocks} array node, wrapping stems in the full asset grammar. */
    private static @NotNull JsonTree toArray(@NotNull List<Row> rows) {
        JsonTree array = JsonTree.array();
        for (Row row : rows)
            array.add(JsonTree.object()
                .put("block", row.block())
                .put("texture", assetPath(row.texture()))
                .putIf("variant", row.variant())
                .putIf("tint", row.tint()));
        return array;
    }

    /** A texture stem in the full asset grammar ({@code minecraft:textures/<stem>.png}). */
    private static @NotNull String assetPath(@NotNull String stem) {
        return VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE + VanillaSourceClasses.Paths.TEXTURE_DIR
            + stem + VanillaSourceClasses.Paths.PNG_SUFFIX;
    }

    /** One catalog row: the block id, its entity-texture stem, an optional blockstate gate, an optional dye tint. */
    private record Row(@NotNull String block, @NotNull String texture, @Nullable String variant, @Nullable String tint) {}

}
