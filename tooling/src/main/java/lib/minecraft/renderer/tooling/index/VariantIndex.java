package lib.minecraft.renderer.tooling.index;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.gson.JsonTree;
import dev.simplified.util.StringUtil;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.interp.Cells;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walk-scoped index of the data-driven variant machinery, built once per run: every
 * {@code data/minecraft/<stem>_variant/*.json} table read through the cache resource API
 * (avoiding an O(entities x zip-entries) re-scan) plus the {@code <X>Variants} holder-class
 * {@code DEFAULT} map.
 *
 * <p>{@code spawn_conditions} subtrees are retained verbatim as parsed nodes - the
 * variant axis resolver copies them into the emitted resource without reserialisation drift. Sound-variant
 * directories ({@code _sound_variant}) are runtime audio metadata, not rendering data, and
 * are skipped. Enum-map variants (horse coats) are the variant axis resolver's
 * per-subject detection, not table data - they carry no {@code data/} directory.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class VariantIndex {

    /** {@code <X>Variants.createKey("id")} - the holder-class key factory (vanilla member name). */
    private static final @NotNull String CREATE_KEY = SourceClasses.Methods.CREATE_KEY;

    /**
     * The holder-class file-name suffix ({@code WolfVariants.class}) - the holder stem
     * convention plus the class-file extension.
     */
    private static final @NotNull String VARIANTS_CLASS_SUFFIX =
        SourceClasses.Types.VARIANT_HOLDER_STEM + ".class";

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
     * @param spawnConditions the vanilla {@code spawn_conditions} subtree verbatim, or
     *     {@code null} when the file declares none
     */
    public record Variant(
        @NotNull String variantId,
        @NotNull Map<String, String> textures,
        @NotNull Map<String, String> babyTextures,
        @Nullable String model,
        @Nullable JsonTree spawnConditions
    ) {}

    private final @NotNull Map<String, List<Variant>> tables;
    private final @NotNull Map<String, String> holderDefaults;

    /**
     * Reads every variant table and holder default out of the jar, once.
     *
     * @param run the live run
     * @param defaultField the static field name a variant holder class binds its canonical default
     *     under ({@code WolfVariants.DEFAULT}), supplied by the flow that declares the convention
     * @return the built index
     */
    public static @NotNull VariantIndex build(@NotNull ToolingRun run, @NotNull String defaultField) {
        Diagnostics diagnostics = run.diagnostics().child("variants");
        Map<String, List<Variant>> tables = loadTables(run.cache(), diagnostics);
        Map<String, String> holderDefaults = loadHolderDefaults(run.cache(), defaultField);
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
    public boolean hasTable(@NotNull String stem) {
        return this.tables.containsKey(stem);
    }

    /**
     * The ordered variant list for a stem (zip directory order), or {@code null} when the
     * jar ships no such table.
     *
     * @param stem the variant directory stem
     * @return the variants, or {@code null}
     */
    public @Nullable List<Variant> table(@NotNull String stem) {
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
    public @Nullable String holderDefault(@NotNull String stem) {
        return this.holderDefaults.get(stem);
    }

    // ------------------------------------------------------------------------------------
    // table walk
    // ------------------------------------------------------------------------------------

    private static @NotNull Map<String, List<Variant>> loadTables(@NotNull ClassNodeCache cache, @NotNull Diagnostics diagnostics) {
        Map<String, List<Variant>> out = new LinkedHashMap<>();
        for (String entryPath : cache.list(SourceClasses.Paths.DATA_ROOT, ".json")) {
            String afterPrefix = entryPath.substring(SourceClasses.Paths.DATA_ROOT.length());
            int slash = afterPrefix.indexOf('/');
            if (slash <= 0) continue;
            String dirName = afterPrefix.substring(0, slash);
            if (!dirName.endsWith(SourceClasses.Paths.VARIANT_DIR_SUFFIX)
                || dirName.endsWith("_sound" + SourceClasses.Paths.VARIANT_DIR_SUFFIX)) continue;
            String stem = dirName.substring(0, dirName.length() - SourceClasses.Paths.VARIANT_DIR_SUFFIX.length());
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
        JsonTree root = cache.readJson(entryPath);
        if (root == null) return null;

        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, String> babyTextures = new LinkedHashMap<>();

        String singleAsset = root.findString(SourceClasses.DataKeys.ASSET_ID).orElse(null);
        if (singleAsset != null) textures.put("primary", texturePath(singleAsset));
        String singleBabyAsset = root.findString(SourceClasses.DataKeys.BABY_ASSET_ID).orElse(null);
        if (singleBabyAsset != null) babyTextures.put("primary", texturePath(singleBabyAsset));

        collectAssetMap(root, SourceClasses.DataKeys.ASSETS, textures);
        collectAssetMap(root, SourceClasses.DataKeys.BABY_ASSETS, babyTextures);

        if (textures.isEmpty()) {
            diagnostics.warn("variant '%s' has no asset_id / assets - skipped", entryPath);
            return null;
        }
        return new Variant(variantId, textures, babyTextures,
            root.findString(SourceClasses.DataKeys.MODEL).orElse(null),
            root.find(SourceClasses.DataKeys.SPAWN_CONDITIONS).orElse(null));
    }

    /** Folds {@code root.<field>}'s string members into {@code out} as texture paths. */
    private static void collectAssetMap(@NotNull JsonTree root, @NotNull String field, @NotNull Map<String, String> out) {
        JsonTree map = root.find(field).orElse(null);
        if (map == null) return;
        for (Map.Entry<String, JsonTree> member : map.members().toList()) {
            String assetId = map.findString(member.getKey()).orElse(null);
            if (assetId != null) out.put(member.getKey(), texturePath(assetId));
        }
    }

    /** Converts a variant {@code asset_id} resource location into a {@code textures/.../X.png} path. */
    private static @NotNull String texturePath(@NotNull String assetId) {
        return "textures/" + SourceClasses.Paths.stripNamespace(assetId) + ".png";
    }

    // ------------------------------------------------------------------------------------
    // holder DEFAULT walk
    // ------------------------------------------------------------------------------------

    /**
     * Walks every {@code <X>Variants.class} holder for its {@code DEFAULT} initialiser and
     * returns the {@code stem -> default id} map ({@code wolf -> pale}). Holders without a
     * {@code DEFAULT} field ({@code CatVariants}) don't appear, which is what narrows the
     * listing's over-match ({@code WolfSoundVariants}, {@code PaintingVariants}) to the
     * entity holders.
     *
     * <p>A listing answers empty rather than failing, so an empty one throws: the holder-stem
     * grammar matching nothing is a renamed convention, and every variant default silently
     * going missing is the shape a run must stop on rather than emit.
     *
     * @param cache the per-run class cache
     * @param defaultField the static field name a holder binds its canonical default under
     * @return the {@code stem -> default id} map
     */
    private static @NotNull Map<String, String> loadHolderDefaults(
        @NotNull ClassNodeCache cache, @NotNull String defaultField) {
        Map<String, String> out = new LinkedHashMap<>();
        String holderStem = SourceClasses.Types.VARIANT_HOLDER_STEM;
        List<String> holders = cache.list(SourceClasses.Types.MINECRAFT_ROOT, VARIANTS_CLASS_SUFFIX);
        if (holders.isEmpty())
            throw new ToolingException("Client jar lists no '%s' class under '%s' - the variant holder-class grammar matches nothing",
                VARIANTS_CLASS_SUFFIX, SourceClasses.Types.MINECRAFT_ROOT);
        for (String entryPath : holders) {
            String simple = entryPath.substring(entryPath.lastIndexOf('/') + 1, entryPath.length() - ".class".length());
            String stem = StringUtil.toSnakeCase(simple.substring(0, simple.length() - holderStem.length()));
            String holderInternal = entryPath.substring(0, entryPath.length() - ".class".length());
            String defaultId = findHolderDefaultId(cache, holderInternal, defaultField);
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
    private static @Nullable String findHolderDefaultId(
        @NotNull ClassNodeCache cache, @NotNull String holderInternal, @NotNull String defaultField) {
        ClassNode cn = cache.load(holderInternal);
        if (cn == null) return null;
        MethodNode clinit = ClassKit.findMethod(cn, ClassKit.CLINIT);
        if (clinit == null) return null;

        // First pass: (FIELD -> id) from the LDC + createKey + PUTSTATIC chain. The literal
        // filter rejects namespaced / path-bearing strings so registry-root LDCs don't bind, and
        // the createKey match is owner-agnostic - any class can host the key factory. The commit
        // hook manages its own arming and reset: an unarmed PUTSTATIC leaves both cells intact.
        Map<String, String> fieldToId = new LinkedHashMap<>();
        Cells.Latch<String> pendingId = Cells.latch();
        Cells.Flag pendingCreateKey = Cells.flag();
        AsmWalker.over(clinit)
            .on(Insn.of(AbstractInsnNode.class, in -> {
                String literal = AsmWalker.stringLiteral(in);
                return literal != null && !literal.contains(":") && !literal.contains("/");
            }), in -> {
                String literal = AsmWalker.stringLiteral(in);
                if (literal == null) return;
                pendingId.set(literal);
                pendingCreateKey.clear();
            })
            .on(Insn.of(MethodInsnNode.class, mi -> mi.getOpcode() == Opcodes.INVOKESTATIC
                && CREATE_KEY.equals(mi.name)), mi -> pendingCreateKey.set())
            .on(Insn.putStatic(holderInternal), put -> {
                String id = pendingId.get();
                if (id == null || !pendingCreateKey.get()) return;
                fieldToId.put(put.name, id);
                pendingId.clear();
                pendingCreateKey.clear();
            })
            .run();

        // Second pass: which FIELD is bound to DEFAULT.
        String boundField = AsmWalker.over(clinit)
            .latch(in -> AsmWalker.isGetStatic(in, holderInternal) ? ((FieldInsnNode) in).name : null)
            .commitAt(FieldInsnNode.class, put -> AsmWalker.isPutStatic(put, holderInternal, defaultField))
            .firstNotNull(commit -> commit.value());
        return boundField == null ? null : fieldToId.get(boundField);
    }

}
