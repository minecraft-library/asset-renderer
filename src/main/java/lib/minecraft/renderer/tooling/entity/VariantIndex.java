package lib.minecraft.renderer.tooling.entity;

import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walk-scoped index of the data-driven variant machinery, built ONCE per session (SPINE 3.1
 * stage 2): every {@code data/minecraft/<stem>_variant/*.json} table read through the cache
 * resource API (killing the legacy O(entities x zip-entries) re-scan) plus the
 * {@code <X>Variants} holder-class {@code DEFAULT} map.
 *
 * <p>{@code spawn_conditions} subtrees are retained VERBATIM as parsed nodes [D64] - the
 * variant axis resolver copies them into the emitted resource without reserialisation drift. Sound-variant
 * directories ({@code _sound_variant}) are runtime audio metadata, not rendering data, and
 * are skipped. Enum-map variants (horse coats [D1]) are the variant axis resolver's
 * per-subject detection, not table data - they carry no {@code data/} directory.
 */
final class VariantIndex {

    /**
     * The variant holder classes' canonical-default static field name
     * ({@code WolfVariants.DEFAULT}) - P15, {@link EntityNamingPolicies#ENUM_DEFAULT_FIELD}.
     */
    private static final @NotNull String DEFAULT_FIELD = EntityNamingPolicies.ENUM_DEFAULT_FIELD.stringValue();

    /** {@code <X>Variants.createKey("id")} - the holder-class key factory (vanilla member name). */
    private static final @NotNull String CREATE_KEY = VanillaSourceClasses.Methods.CREATE_KEY;

    /**
     * The holder-class file-name suffix ({@code WolfVariants.class}) - the P20 holder stem
     * convention ({@link EntityNamingPolicies#VARIANT_ENUM_CONVENTIONS}, first member).
     */
    private static final @NotNull String VARIANTS_CLASS_SUFFIX =
        EntityNamingPolicies.VARIANT_ENUM_CONVENTIONS.strings().getFirst() + ".class";

    /**
     * One variant entry parsed from a variant JSON file. Carries either a single
     * {@code asset_id} (cow / pig / chicken / frog / cat shape, under the {@code primary}
     * subkey) or a per-state {@code assets} map (wolf - {@code angry} / {@code tame} /
     * {@code wild} sub-textures).
     *
     * @param variantId the variant's resource id (file basename minus {@code .json})
     * @param textures ordered subkey-to-texture-path map ({@code primary} for the
     *     single-asset shape; explicit state names for the multi-asset shape)
     * @param babyTextures ordered subkey-to-baby-texture-path map, empty when none declared
     * @param model the {@code model} discriminator selecting a non-default model class, or
     *     {@code null} when the default model applies
     * @param spawnConditions the vanilla {@code spawn_conditions} subtree VERBATIM [D64], or
     *     {@code null} when the file declares none
     */
    record Variant(
        @NotNull String variantId,
        @NotNull Map<String, String> textures,
        @NotNull Map<String, String> babyTextures,
        @Nullable String model,
        @Nullable JsonNode spawnConditions
    ) {}

    private final @NotNull Map<String, List<Variant>> tables;
    private final @NotNull Map<String, String> holderDefaults;

    private VariantIndex(@NotNull Map<String, List<Variant>> tables, @NotNull Map<String, String> holderDefaults) {
        this.tables = tables;
        this.holderDefaults = holderDefaults;
    }

    /**
     * Reads every variant table and holder default out of the jar, once.
     *
     * @param session the live session
     * @return the built index
     */
    static @NotNull VariantIndex build(@NotNull ToolingSession session) {
        Diagnostics diagnostics = session.diagnostics().child("variants");
        Map<String, List<Variant>> tables = loadTables(session.cache(), diagnostics);
        Map<String, String> holderDefaults = loadHolderDefaults(session.cache());
        diagnostics.info("indexed variant tables for %d stems %s; holder defaults %s",
            tables.size(), tables.keySet(), holderDefaults);
        return new VariantIndex(tables, holderDefaults);
    }

    /**
     * Reports whether a variant table exists for the stem - the state-field-driven variant
     * signal (wolf / cat renderers read {@code state.texture} populated upstream from a
     * variant lookup that {@code getTextureLocation} alone cannot trace).
     *
     * @param stem the variant directory stem ({@code wolf})
     * @return {@code true} when a table exists
     */
    boolean hasTable(@NotNull String stem) {
        return this.tables.containsKey(stem);
    }

    /**
     * The ordered variant list for a stem (zip directory order), or {@code null} when the
     * jar ships no such table.
     *
     * @param stem the variant directory stem
     * @return the variants, or {@code null}
     */
    @Nullable List<Variant> table(@NotNull String stem) {
        return this.tables.get(stem);
    }

    /**
     * The canonical default variant id from the stem's {@code <X>Variants.DEFAULT} holder
     * field ({@code wolf} to {@code pale}), or {@code null} when the holder has no
     * {@code DEFAULT} ({@code CatVariants}).
     *
     * @param stem the variant directory stem
     * @return the default variant id, or {@code null}
     */
    @Nullable String holderDefault(@NotNull String stem) {
        return this.holderDefaults.get(stem);
    }

    /** The indexed stems, in zip directory order (unmodifiable). */
    @NotNull Map<String, List<Variant>> tables() {
        return Collections.unmodifiableMap(this.tables);
    }

    // ------------------------------------------------------------------------------------
    // table walk
    // ------------------------------------------------------------------------------------

    private static @NotNull Map<String, List<Variant>> loadTables(@NotNull ClassNodeCache cache, @NotNull Diagnostics diagnostics) {
        Map<String, List<Variant>> out = new LinkedHashMap<>();
        for (String entryPath : cache.list(VanillaSourceClasses.Paths.DATA_ROOT, ".json")) {
            String afterPrefix = entryPath.substring(VanillaSourceClasses.Paths.DATA_ROOT.length());
            int slash = afterPrefix.indexOf('/');
            if (slash <= 0) continue;
            String dirName = afterPrefix.substring(0, slash);
            if (!dirName.endsWith(VanillaSourceClasses.Paths.VARIANT_DIR_SUFFIX)
                || dirName.endsWith("_sound" + VanillaSourceClasses.Paths.VARIANT_DIR_SUFFIX)) continue;
            String stem = dirName.substring(0, dirName.length() - VanillaSourceClasses.Paths.VARIANT_DIR_SUFFIX.length());
            String fileName = afterPrefix.substring(slash + 1);
            if (fileName.contains("/")) continue;
            String variantId = fileName.substring(0, fileName.length() - ".json".length());

            Variant parsed = parseVariant(cache, entryPath, variantId, diagnostics);
            if (parsed == null) continue;
            out.computeIfAbsent(stem, key -> new java.util.ArrayList<>()).add(parsed);
        }
        return out;
    }

    /**
     * Parses one variant JSON. Two vanilla shapes: single asset ({@code asset_id} +
     * optional {@code baby_asset_id}) and multi-asset ({@code assets} / {@code baby_assets}
     * per-state maps, wolf). Returns {@code null} (with a WARN) when neither shape's
     * required field is present.
     */
    private static @Nullable Variant parseVariant(
        @NotNull ClassNodeCache cache,
        @NotNull String entryPath,
        @NotNull String variantId,
        @NotNull Diagnostics diagnostics
    ) {
        JsonNode root = cache.readJson(entryPath);
        if (root == null) return null;

        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, String> babyTextures = new LinkedHashMap<>();

        String singleAsset = root.getString(VanillaSourceClasses.DataKeys.ASSET_ID);
        if (singleAsset != null) textures.put("primary", texturePath(singleAsset));
        String singleBabyAsset = root.getString(VanillaSourceClasses.DataKeys.BABY_ASSET_ID);
        if (singleBabyAsset != null) babyTextures.put("primary", texturePath(singleBabyAsset));

        collectAssetMap(root, VanillaSourceClasses.DataKeys.ASSETS, textures);
        collectAssetMap(root, VanillaSourceClasses.DataKeys.BABY_ASSETS, babyTextures);

        if (textures.isEmpty()) {
            diagnostics.warn("variant '%s' has no asset_id / assets - skipped", entryPath);
            return null;
        }
        return new Variant(variantId, textures, babyTextures,
            root.getString(VanillaSourceClasses.DataKeys.MODEL),
            root.get(VanillaSourceClasses.DataKeys.SPAWN_CONDITIONS));
    }

    /** Folds {@code root.<field>}'s string members into {@code out} as texture paths. */
    private static void collectAssetMap(@NotNull JsonNode root, @NotNull String field, @NotNull Map<String, String> out) {
        JsonNode map = root.get(field);
        if (map == null) return;
        for (Map.Entry<String, JsonNode> member : map.members()) {
            String assetId = map.getString(member.getKey());
            if (assetId != null) out.put(member.getKey(), texturePath(assetId));
        }
    }

    /** Converts a variant {@code asset_id} resource location into a {@code textures/.../X.png} path. */
    private static @NotNull String texturePath(@NotNull String assetId) {
        String stripped = assetId.startsWith(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE)
            ? assetId.substring(VanillaSourceClasses.Paths.MINECRAFT_NAMESPACE.length())
            : assetId;
        return "textures/" + stripped + ".png";
    }

    // ------------------------------------------------------------------------------------
    // holder DEFAULT walk
    // ------------------------------------------------------------------------------------

    /**
     * Walks every {@code <X>Variants.class} holder for its {@code DEFAULT} initialiser and
     * returns the {@code stem -> default id} map ({@code wolf -> pale}). Holders without a
     * {@code DEFAULT} field ({@code CatVariants}) don't appear.
     */
    private static @NotNull Map<String, String> loadHolderDefaults(@NotNull ClassNodeCache cache) {
        Map<String, String> out = new LinkedHashMap<>();
        String holderStem = EntityNamingPolicies.VARIANT_ENUM_CONVENTIONS.strings().getFirst();
        for (String entryPath : cache.list(VanillaSourceClasses.Types.MINECRAFT_ROOT, VARIANTS_CLASS_SUFFIX)) {
            String simple = entryPath.substring(entryPath.lastIndexOf('/') + 1, entryPath.length() - ".class".length());
            String stem = StringUtil.toSnakeCase(simple.substring(0, simple.length() - holderStem.length()));
            String holderInternal = entryPath.substring(0, entryPath.length() - ".class".length());
            String defaultId = findHolderDefaultId(cache, holderInternal);
            if (defaultId != null) out.put(stem, defaultId);
        }
        return out;
    }

    /**
     * Walks the holder's {@code <clinit>} for the {@code LDC "<id>"; INVOKESTATIC createKey;
     * PUTSTATIC <FIELD>} chain (one per variant) and the closing {@code GETSTATIC <FIELD>;
     * PUTSTATIC DEFAULT} that selects the canonical default. Returns the id bound to the
     * field {@code DEFAULT} references, or {@code null} on any pattern miss.
     */
    private static @Nullable String findHolderDefaultId(@NotNull ClassNodeCache cache, @NotNull String holderInternal) {
        ClassNode cn = cache.load(holderInternal);
        if (cn == null) return null;
        MethodNode clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;

        // First pass: (FIELD -> id) from the LDC + createKey + PUTSTATIC chain. The literal
        // filter rejects namespaced / path-bearing strings so registry-root LDCs don't bind.
        Map<String, String> fieldToId = new LinkedHashMap<>();
        String pendingId = null;
        boolean pendingCreateKey = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && !literal.contains(":") && !literal.contains("/")) {
                pendingId = literal;
                pendingCreateKey = false;
                continue;
            }
            // Owner-agnostic createKey match - any class can host the key factory.
            if (in.getOpcode() == Opcodes.INVOKESTATIC
                && in instanceof MethodInsnNode mi
                && CREATE_KEY.equals(mi.name)) {
                pendingCreateKey = true;
                continue;
            }
            if (AsmKit.isPutStatic(in, holderInternal) && pendingId != null && pendingCreateKey) {
                fieldToId.put(((FieldInsnNode) in).name, pendingId);
                pendingId = null;
                pendingCreateKey = false;
            }
        }

        // Second pass: which FIELD is bound to DEFAULT.
        String pendingField = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, holderInternal)) {
                pendingField = ((FieldInsnNode) in).name;
                continue;
            }
            if (AsmKit.isPutStatic(in, holderInternal, DEFAULT_FIELD) && pendingField != null)
                return fieldToId.get(pendingField);
        }
        return null;
    }

}
