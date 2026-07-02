package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.ClassNodeCache;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ASM-derives per-entity variant sets from renderer bytecode plus the client jar's data-driven
 * variant tables and variant enums.
 *
 * <p>Two vanilla variant mechanisms are resolved here:
 *
 * <ul>
 *   <li><b>Data-driven tables</b> ({@link #loadAll}) - the {@code data/minecraft/<X>_variant/*.json}
 *       files introduced in MC 1.21+ for cow / pig / chicken / frog / wolf / cat
 *       (climate / breed variants). Each JSON file declares an {@code asset_id} (the texture
 *       resource location, a vanilla {@code net.minecraft.resources.Identifier}) plus optional
 *       {@code baby_asset_id} and {@code model} fields:
 *       <pre>{@code
 * {
 *   "asset_id": "minecraft:entity/cow/cow_cold",
 *   "baby_asset_id": "minecraft:entity/cow/cow_cold_baby",
 *   "model": "cold",
 *   "spawn_conditions": [...]
 * }
 *       }</pre></li>
 *   <li><b>Variant enums</b> ({@link #resolveEnumDefault}) - the pure-enum pattern
 *       (axolotl / rabbit) where the renderer's {@code getTextureLocation} reads an enum-typed
 *       {@code state.variant} field and the enum exposes a {@code DEFAULT} initialiser.</li>
 * </ul>
 *
 * <p>The {@code asset_id} value is a resource location that maps to a real PNG via the
 * {@code textures/} prefix - {@code "minecraft:entity/cow/cow_cold"} resolves to
 * {@code textures/entity/cow/cow_cold.png}. Spawn conditions describe runtime selection logic
 * (biome filters, priority); the renderer here only cares about which textures exist for which
 * variants.
 *
 * <p>Variant directory names are bytecode-stable - the {@code XVariant} class name returned by
 * {@link EntityTextureResolver.Result#variantSourceClass()} ends in
 * {@code "Variant"} and corresponds 1:1 to a {@code data/minecraft/<X>_variant/} directory.
 * The mapping is computed via {@link #directoryFor(String)} so future variant types pick up
 * automatically.
 */
@UtilityClass
public final class EntityVariantResolver {

    /**
     * Prefix every variant data directory shares.
     */
    private static final @NotNull String DATA_PREFIX = "data/minecraft/";

    /**
     * Suffix every variant data directory shares.
     */
    private static final @NotNull String VARIANT_SUFFIX = "_variant/";

    /**
     * One variant entry parsed from a variant JSON file. Carries either a single
     * {@code asset_id} (cow / pig / chicken / frog / cat shape) or a per-key {@code assets}
     * map (wolf shape, where one variant declares {@code angry} / {@code tame} / {@code wild}
     * sub-textures). {@link EntityRuntimeJsonWriter} reads {@link #primaryTexturePath()} for the
     * emitted {@code texture_ref}, picking whichever set is non-empty.
     *
     * @param variantId the variant's resource id (e.g. {@code "cold"}, {@code "ashen"});
     *     the file basename minus {@code .json}
     * @param textures ordered map of {@code subkey -&gt; texture path} ({@code "primary"} key
     *     used for the single-asset shape; explicit subkey names like {@code "angry"} /
     *     {@code "tame"} / {@code "wild"} for the multi-asset shape)
     * @param babyTextures ordered map of {@code subkey -&gt; baby texture path} or empty when
     *     no baby variants are declared
     * @param model optional {@code model} field discriminating which model class renders this
     *     variant; {@code null} when the default model applies
     */
    public record Result(
        @NotNull String variantId,
        @NotNull java.util.LinkedHashMap<String, String> textures,
        @NotNull java.util.LinkedHashMap<String, String> babyTextures,
        @Nullable String model
    ) {
        /**
         * Selects the default texture path - the {@code "wild"} entry from the {@code assets} map
         * (vanilla wolf default) when present, else the single {@code asset_id} (under
         * {@code "primary"}), else the first {@link #textures} entry.
         *
         * @return the default texture path, or {@code null} when {@link #textures} is empty
         */
        public @Nullable String primaryTexturePath() {
            String wild = this.textures.get("wild");
            if (wild != null) return wild;
            String primary = this.textures.get("primary");
            if (primary != null) return primary;
            java.util.Iterator<String> it = this.textures.values().iterator();
            return it.hasNext() ? it.next() : null;
        }

        /**
         * Selects the default baby texture path using the same {@code "wild"} &rarr;
         * {@code "primary"} &rarr; first-entry precedence as {@link #primaryTexturePath()}.
         *
         * @return the matching baby texture path, or {@code null} when no baby variants are declared
         */
        public @Nullable String primaryBabyTexturePath() {
            String wild = this.babyTextures.get("wild");
            if (wild != null) return wild;
            String primary = this.babyTextures.get("primary");
            if (primary != null) return primary;
            java.util.Iterator<String> it = this.babyTextures.values().iterator();
            return it.hasNext() ? it.next() : null;
        }
    }

    /**
     * Converts a variant {@code asset_id} resource location into a {@code textures/.../X.png} path.
     */
    private static @NotNull String texturePath(@NotNull String assetId) {
        String stripped = assetId.startsWith("minecraft:") ? assetId.substring("minecraft:".length()) : assetId;
        return "textures/" + stripped + ".png";
    }

    /**
     * Variant id treated as the base entity (no separate {@code variant_of} row emitted) when
     * walking data-driven variant tables. Vanilla 1.21+ uses {@code "temperate"} for cow / pig /
     * chicken / frog as the climate-default; the base entity ({@code minecraft:cow}) takes that
     * variant's texture when no spawn-condition match overrides it.
     */
    private static final @NotNull String DEFAULT_VARIANT_ID = "temperate";

    /**
     * Picks the default variant from a variant list, preferring the canonical default detected
     * from the entity's {@code <X>Variants.DEFAULT} static field (e.g.
     * {@code WolfVariants.DEFAULT = PALE} resolves to {@code "pale"}). {@code CatVariants} has
     * no {@code DEFAULT} so {@code canonicalDefaultId} is {@code null} and the fallback chain
     * runs - prefer {@code "temperate"} (cow / pig / chicken / frog climate-default), then
     * the first entry.
     *
     * @param variantList the variant list as loaded by {@link #loadAll}
     * @param canonicalDefaultId the canonical default variant id from the entity's variant
     *     enum's {@code DEFAULT} field, or {@code null} when no canonical default exists
     * @return the selected default variant
     */
    public static @NotNull Result pickDefault(
        @NotNull ConcurrentList<Result> variantList,
        @Nullable String canonicalDefaultId
    ) {
        if (canonicalDefaultId != null) {
            for (Result v : variantList)
                if (canonicalDefaultId.equals(v.variantId())) return v;
        }
        for (Result v : variantList)
            if (DEFAULT_VARIANT_ID.equals(v.variantId())) return v;
        return variantList.get(0);
    }

    /**
     * Walks every {@code data/minecraft/<X>_variant/*.json} in the client jar and returns a
     * map keyed by the variant directory's stem ({@code "cow"}, {@code "pig"}, ...) to the
     * ordered list of variant entries. Empty when the jar ships no variant directories
     * (pre-1.21).
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return variant stem -&gt; ordered variant list
     */
    public static @NotNull ConcurrentMap<String, ConcurrentList<Result>> loadAll(
        @NotNull EntityToolingContext context,
        @NotNull Diagnostics diagnostics
    ) {
        ZipFile zip = context.zip();
        ConcurrentMap<String, ConcurrentList<Result>> out = Concurrent.newMap();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (!name.startsWith(DATA_PREFIX)) continue;
            String afterPrefix = name.substring(DATA_PREFIX.length());
            int slashIdx = afterPrefix.indexOf('/');
            if (slashIdx <= 0) continue;
            String dirName = afterPrefix.substring(0, slashIdx);
            // Skip non-texture variant tables (sound variants are runtime audio metadata,
            // not rendering data; they share the _variant suffix but ship under
            // _sound_variant directories).
            if (!dirName.endsWith("_variant") || dirName.endsWith("_sound_variant")) continue;
            String variantStem = dirName.substring(0, dirName.length() - "_variant".length());
            String fileName = afterPrefix.substring(slashIdx + 1);
            if (!fileName.endsWith(".json") || fileName.contains("/")) continue;
            String variantId = fileName.substring(0, fileName.length() - ".json".length());

            Result parsed = parseVariant(zip, entry, variantId, diagnostics);
            if (parsed == null) continue;

            out.computeIfAbsent(variantStem, k -> Concurrent.newList()).add(parsed);
        }
        return out;
    }

    /**
     * Walks every {@code <X>Variants.class} (the data-driven holder class - {@code WolfVariants},
     * {@code CatVariants}, etc.) for its {@code DEFAULT} static field initialiser and returns
     * a {@code variantStem -> defaultVariantId} map (e.g. {@code wolf -> "pale"},
     * {@code chicken -> "warm"} - the canonical defaults vanilla picks at runtime when no
     * spawn condition matches). The {@code <clinit>} pattern walked:
     * <pre>{@code
     *   LDC "pale"; INVOKESTATIC createKey; PUTSTATIC PALE
     *   ...
     *   GETSTATIC PALE; PUTSTATIC DEFAULT
     * }</pre>
     * The holder class lookup uses the convention {@code <CamelStem>Variants.class}; missing
     * holders or holders without a {@code DEFAULT} field don't appear in the result. Empty
     * when the jar ships no holder classes.
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return variant stem -&gt; canonical default variant id
     */
    public static @NotNull ConcurrentMap<String, String> loadDataDrivenDefaults(
        @NotNull EntityToolingContext context,
        @NotNull Diagnostics diagnostics
    ) {
        ZipFile zip = context.zip();
        ConcurrentMap<String, String> out = Concurrent.newMap();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith("Variants.class")) continue;
            String simple = name.substring(name.lastIndexOf('/') + 1, name.length() - ".class".length());
            // simple ends with "Variants"; strip and snake-case to get the variant stem
            String stem = camelToSnake(simple.substring(0, simple.length() - "Variants".length()));
            String holderInternal = name.substring(0, name.length() - ".class".length());
            String defaultId = findDataDrivenDefaultId(context.classNodes(), holderInternal);
            if (defaultId != null) out.put(stem, defaultId);
        }
        return out;
    }

    /**
     * Walks a renderer's model-map construction bytecode (the {@code bakeModels} static factory
     * on variant renderers that keep a {@code Map<XVariant$ModelType, Model>}) and returns a
     * {@code (ModelType-constant-name -> ModelLayers field name)} map for the <b>adult</b> model
     * layers. Used to resolve a variant's {@code model} discriminator to its actual
     * {@code ModelLayers.X} field when the vanilla naming doesn't follow the
     * {@code <MODEL>_<STEM>} convention.
     *
     * <p>Cow's {@code bakeModels} pairs {@code ModelType.WARM -> ModelLayers.WARM_COW} (matches
     * the convention), but zombie_nautilus pairs {@code ModelType.WARM ->
     * ModelLayers.ZOMBIE_NAUTILUS_CORAL} (does not). Deriving the pairing from bytecode covers
     * both. Each {@code AdultAndBabyModelPair} entry lists the adult layer first, then the baby
     * layer; only the first {@code ModelLayers.X} after each {@code ModelType} GETSTATIC is
     * captured so the baby layer ({@code WARM_COW_BABY}) is skipped.
     *
     * <p>Walks every method (the pattern lives in {@code bakeModels} for cow / zombie_nautilus,
     * but scanning all methods keeps it robust to renderers that build the map inline in
     * {@code <init>}); {@code putIfAbsent} keeps the first pairing found. Returns an empty map
     * when the renderer has no such construction.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer class's JVM internal name
     * @return {@code ModelType} constant name (uppercase, e.g. {@code "WARM"}) -&gt; adult
     *     {@code ModelLayers} field name (e.g. {@code "ZOMBIE_NAUTILUS_CORAL"}); empty when no
     *     model-map construction is present
     */
    public static @NotNull Map<String, String> modelTypeToModelLayerField(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName
    ) {
        ClassNode cn = classNodes.load(rendererInternalName);
        if (cn == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (MethodNode method : cn.methods) {
            String pendingModelType = null;
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != Opcodes.GETSTATIC || !(in instanceof FieldInsnNode fi)) continue;
                if (fi.owner.endsWith("$ModelType")) {
                    pendingModelType = fi.name;
                } else if (VanillaSourceClasses.MODEL_LAYERS.equals(fi.owner) && pendingModelType != null) {
                    // First ModelLayers after the ModelType GETSTATIC is the adult layer; consume
                    // the pending key so the following BABY layer (e.g. WARM_COW_BABY) is skipped.
                    out.putIfAbsent(pendingModelType, fi.name);
                    pendingModelType = null;
                }
            }
        }
        return out;
    }

    /**
     * Fallback for variant tables whose holder class lacks a {@code DEFAULT} field
     * ({@code CatVariants} is the canonical case in vanilla 26.1). Walks every
     * {@code data/minecraft/<variantStem>_variant/*.json} and returns the alphabetically-first
     * variant id whose {@code spawn_conditions} entries are all unconditional (each entry
     * carries only {@code priority} - no {@code condition} sub-object). This mirrors vanilla's
     * runtime selection at a fresh-spawn zero state, where structure / moon / biome / time
     * gated variants drop out and the remaining unconditional set is iterated alphabetically.
     *
     * <p>For cats: {@code all_black.json} carries structure + moon conditions and is filtered
     * out; the remaining 10 unconditional variants are ordered alphabetically and {@code black}
     * wins -&gt; cat texture resolves to {@code entity/cat/cat_black}.
     *
     * @param context the tooling context (jar + diagnostics + ClassNode cache)
     * @param variantStem the variant directory stem (e.g. {@code "cat"}) whose
     *     {@code data/minecraft/<stem>_variant/} directory is scanned
     * @param diagnostics the diagnostic sink shared with sibling discovery walks
     * @return the variant id (e.g. {@code "black"}) or {@code null} when no unconditional
     *     variant exists in the directory
     */
    public static @Nullable String findAlphaFirstUnconditionalVariantId(
        @NotNull EntityToolingContext context,
        @NotNull String variantStem,
        @NotNull Diagnostics diagnostics
    ) {
        ZipFile zip = context.zip();
        String dirPrefix = DATA_PREFIX + variantStem + "_variant/";
        java.util.TreeSet<String> unconditional = new java.util.TreeSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (!name.startsWith(dirPrefix)) continue;
            String tail = name.substring(dirPrefix.length());
            if (!tail.endsWith(".json") || tail.contains("/")) continue;
            String variantId = tail.substring(0, tail.length() - ".json".length());
            if (isUnconditionalVariant(zip, entry, diagnostics))
                unconditional.add(variantId);
        }
        return unconditional.isEmpty() ? null : unconditional.first();
    }

    /**
     * Returns {@code true} when every entry in the variant JSON's {@code spawn_conditions} array
     * lacks a {@code condition} sub-object - i.e., the variant is selectable regardless of
     * structure / biome / moon / time gates. Variants with an empty / absent
     * {@code spawn_conditions} array also return {@code true} (the harness defaults are
     * always considered selectable).
     */
    private static boolean isUnconditionalVariant(
        @NotNull ZipFile zip,
        @NotNull ZipEntry entry,
        @NotNull Diagnostics diagnostics
    ) {
        try (InputStream in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return false;
            JsonElement conditions = parsed.getAsJsonObject().get("spawn_conditions");
            if (conditions == null || !conditions.isJsonArray()) return true;
            for (JsonElement element : conditions.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                if (element.getAsJsonObject().has("condition")) return false;
            }
            return true;
        } catch (IOException ex) {
            diagnostics.warn("failed to read variant '%s': %s", entry.getName(), ex.getMessage());
            return false;
        } catch (JsonSyntaxException ex) {
            diagnostics.warn("variant '%s' has malformed JSON: %s", entry.getName(), ex.getMessage());
            return false;
        }
    }

    /**
     * Walks the data-variant holder class's {@code <clinit>} for the
     * {@code LDC + createKey + PUTSTATIC} chain (one per variant) and the closing
     * {@code GETSTATIC <FIELD>; PUTSTATIC DEFAULT} that selects the canonical default. Returns
     * the resource id passed to {@code createKey} for the field referenced by {@code DEFAULT},
     * or {@code null} when the class doesn't have a {@code DEFAULT} field or the pattern
     * doesn't match.
     */
    private static @Nullable String findDataDrivenDefaultId(
        @NotNull ClassNodeCache classNodes,
        @NotNull String holderInternalName
    ) {
        var cn = classNodes.load(holderInternalName);
        if (cn == null) return null;
        var clinit = AsmKit.findMethod(cn, AsmKit.CLINIT);
        if (clinit == null) return null;
        // First pass: build (FIELD -> id-string) map from the LDC + createKey + PUTSTATIC chain.
        java.util.Map<String, String> fieldToId = new java.util.LinkedHashMap<>();
        String pendingId = null;
        boolean pendingCreateKey = false;
        for (var in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String literal = AsmKit.readStringLiteral(in);
            if (literal != null && !literal.contains(":") && !literal.contains("/")) {
                pendingId = literal;
                pendingCreateKey = false;
                continue;
            }
            // Owner-agnostic createKey match (any class can host a createKey factory).
            if (in.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                && in instanceof org.objectweb.asm.tree.MethodInsnNode mi
                && "createKey".equals(mi.name)) {
                pendingCreateKey = true;
                continue;
            }
            if (AsmKit.isPutStatic(in, holderInternalName) && pendingId != null && pendingCreateKey) {
                fieldToId.put(((org.objectweb.asm.tree.FieldInsnNode) in).name, pendingId);
                pendingId = null;
                pendingCreateKey = false;
            }
        }
        // Second pass: find which FIELD is bound to DEFAULT.
        String pendingField = null;
        for (var in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isGetStatic(in, holderInternalName)) {
                pendingField = ((org.objectweb.asm.tree.FieldInsnNode) in).name;
                continue;
            }
            if (AsmKit.isPutStatic(in, holderInternalName, "DEFAULT") && pendingField != null)
                return fieldToId.get(pendingField);
        }
        return null;
    }

    /**
     * Returns the variant directory stem for the given variant class name. Strips the trailing
     * {@code "Variant"} (and inner-class qualifier suffixes), then snake-cases the result -
     * {@code "CowVariant"} -&gt; {@code "cow"}, {@code "PigVariant"} -&gt; {@code "pig"},
     * {@code "WolfVariants"} -&gt; {@code "wolf"} (defensive trailing-s).
     *
     * @param variantClassInternalName the JVM internal name from
     *     {@link EntityTextureResolver.Result#variantSourceClass()}
     * @return the variant directory stem, or {@code null} when the class doesn't conform to the
     *     {@code XVariant} naming convention
     */
    public static @Nullable String directoryFor(@NotNull String variantClassInternalName) {
        int lastSlash = variantClassInternalName.lastIndexOf('/');
        String simple = lastSlash >= 0 ? variantClassInternalName.substring(lastSlash + 1) : variantClassInternalName;
        // Strip trailing $Whatever inner-class qualifier (e.g. "CowVariant$ModelType").
        int dollar = simple.indexOf('$');
        if (dollar >= 0) simple = simple.substring(0, dollar);
        if (simple.endsWith("Variants")) simple = simple.substring(0, simple.length() - "Variants".length());
        else if (simple.endsWith("Variant")) simple = simple.substring(0, simple.length() - "Variant".length());
        else return null;
        return camelToSnake(simple);
    }

    /**
     * Parses one variant JSON file. Handles two shapes vanilla ships:
     * <ul>
     *   <li><b>Single asset</b> ({@code asset_id} string, optional {@code baby_asset_id}) -
     *       cow / pig / chicken / frog / cat.</li>
     *   <li><b>Multi-asset</b> ({@code assets} object, optional {@code baby_assets} object) -
     *       wolf, where each variant declares per-state textures
     *       ({@code "angry"} / {@code "tame"} / {@code "wild"}).</li>
     * </ul>
     * Returns {@code null} on syntax errors or when neither shape's required field is present.
     */
    private static @Nullable Result parseVariant(
        @NotNull ZipFile zip,
        @NotNull ZipEntry entry,
        @NotNull String variantId,
        @NotNull Diagnostics diagnostics
    ) {
        try (InputStream in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                diagnostics.warn("variant '%s' is not a JSON object - skipped", entry.getName());
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            java.util.LinkedHashMap<String, String> textures = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, String> babyTextures = new java.util.LinkedHashMap<>();

            String singleAsset = optionalString(root, "asset_id");
            if (singleAsset != null) textures.put("primary", texturePath(singleAsset));
            String singleBabyAsset = optionalString(root, "baby_asset_id");
            if (singleBabyAsset != null) babyTextures.put("primary", texturePath(singleBabyAsset));

            collectAssetMap(root, "assets", textures);
            collectAssetMap(root, "baby_assets", babyTextures);

            if (textures.isEmpty()) {
                diagnostics.warn("variant '%s' has no asset_id / assets - skipped", entry.getName());
                return null;
            }
            return new Result(variantId, textures, babyTextures, optionalString(root, "model"));
        } catch (IOException ex) {
            diagnostics.warn("failed to read variant '%s': %s", entry.getName(), ex.getMessage());
            return null;
        } catch (JsonSyntaxException ex) {
            diagnostics.warn("variant '%s' has malformed JSON: %s", entry.getName(), ex.getMessage());
            return null;
        }
    }

    /**
     * Reads {@code root.<field>} as a string-keyed asset-id map and folds entries into {@code out}.
     */
    private static void collectAssetMap(@NotNull JsonObject root, @NotNull String field, @NotNull java.util.LinkedHashMap<String, String> out) {
        if (!root.has(field)) return;
        JsonElement element = root.get(field);
        if (element == null || !element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            out.put(entry.getKey(), texturePath(value.getAsString()));
        }
    }

    /**
     * Returns the named field as a string, or {@code null} when absent / non-string.
     */
    private static @Nullable String optionalString(@NotNull JsonObject object, @NotNull String field) {
        if (!object.has(field)) return null;
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }

    /**
     * {@code "ColdCow"} -&gt; {@code "cold_cow"}, {@code "Cow"} -&gt; {@code "cow"}.
     */
    private static @NotNull String camelToSnake(@NotNull String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------
    // Enum-DEFAULT variant detection (axolotl / rabbit pattern)
    // ----------------------------------------------------------------------------------------
    //
    // Two vanilla 26.1 entities use a pure-enum variant pattern distinct from the data-driven
    // tables above: the renderer's getTextureLocation reads `state.variant : L<EnumClass>;`
    // and the enum exposes a `public static final DEFAULT` initializer. Combined with the
    // per-entity texture-naming convention `<entity>/<entity>_<default_lowercase>`, this lets
    // the tooling pick the canonical default texture stem without a hardcoded entry.
    //
    // - AxolotlRenderer.getTextureLocation reads state.variant : LAxolotl$Variant;;
    //   Axolotl$Variant.DEFAULT = LUCY -> axolotl/axolotl_lucy
    // - RabbitRenderer.getTextureLocation reads state.variant : LRabbit$Variant;;
    //   Rabbit$Variant.DEFAULT = BROWN -> rabbit/rabbit_brown
    //
    // Data-driven entities (cat / cow / pig / chicken / frog / wolf) DON'T match - their
    // state field is a Holder<XVariant> that resolves through the data/minecraft/X_variant/
    // registry walked by loadAll above.

    private static final @NotNull String GET_TEXTURE_LOCATION = "getTextureLocation";
    private static final @NotNull String VARIANT_FIELD = "variant";

    /**
     * Outcome of {@link #resolveEnumDefault}: the variant enum's internal name plus the
     * lowercase name of the {@code DEFAULT} enum value. The texture stem is computed by the
     * caller from the entity id (e.g. {@code minecraft:axolotl} + {@code lucy} -&gt;
     * {@code axolotl/axolotl_lucy}).
     *
     * @param variantClass JVM internal name of the variant enum
     * @param defaultName lowercase name of the {@code DEFAULT} enum value
     */
    public record EnumDefault(@NotNull String variantClass, @NotNull String defaultName) {}

    /**
     * Returns the enum-default binding for the renderer's {@code getTextureLocation} when the
     * method reads {@code state.variant} from an enum-typed field AND the variant enum has a
     * {@code public static final DEFAULT} initializer in its {@code <clinit>}. Returns
     * {@code null} for any other shape - data-driven Holder variants, no-DEFAULT enums,
     * non-overridden getTextureLocation, etc.
     *
     * @param classNodes the ClassNode cache (shared with sibling resolver walks)
     * @param rendererInternalName the renderer class's JVM internal name
     * @param diag the diagnostic sink (one INFO line per resolved enum default)
     * @return the resolved binding, or {@code null} when no match
     */
    public static @Nullable EnumDefault resolveEnumDefault(
        @NotNull ClassNodeCache classNodes,
        @NotNull String rendererInternalName,
        @NotNull Diagnostics diag
    ) {
        ClassNode rendererCn = classNodes.load(rendererInternalName);
        if (rendererCn == null) return null;
        MethodNode getTex = findOwnGetTextureLocation(rendererCn);
        if (getTex == null) return null;

        String variantClass = findVariantFieldClass(getTex);
        if (variantClass == null) return null;

        String defaultName = AsmKit.findEnumDefaultName(classNodes, variantClass);
        if (defaultName == null) return null;

        diag.info("variant-default: '%s' -> %s.%s", rendererInternalName, variantClass, defaultName);
        return new EnumDefault(variantClass, defaultName.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the renderer's own (non-bridge) {@code getTextureLocation} method. The bridge
     * variant that takes {@code LivingEntityRenderState} delegates back to the typed version
     * via checkcast + invokevirtual - we skip it because the body has no
     * {@code GETFIELD variant} instruction.
     */
    private static @Nullable MethodNode findOwnGetTextureLocation(@NotNull ClassNode cn) {
        String livingState = "(L" + VanillaSourceClasses.LIVING_ENTITY_RENDER_STATE + ";)L" + VanillaSourceClasses.IDENTIFIER + ";";
        String identifierReturn = ")L" + VanillaSourceClasses.IDENTIFIER + ";";
        MethodNode bridge = null;
        for (MethodNode m : cn.methods) {
            if (!GET_TEXTURE_LOCATION.equals(m.name)) continue;
            if (m.desc == null) continue;
            if (livingState.equals(m.desc)) {
                bridge = m;
                continue;
            }
            if (m.desc.endsWith(identifierReturn)) return m;
        }
        return bridge;
    }

    /**
     * Scans the method body for a {@code GETFIELD <state>.variant : L<EnumClass>;}
     * instruction and returns the enum class's JVM internal name. The variant field is named
     * exactly {@code "variant"} in vanilla 26.1 RenderState classes
     * ({@code AxolotlRenderState.variant}, {@code RabbitRenderState.variant}). Returns
     * {@code null} when no such GETFIELD appears.
     */
    private static @Nullable String findVariantFieldClass(@NotNull MethodNode method) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != Opcodes.GETFIELD) continue;
            if (!(in instanceof FieldInsnNode fi)) continue;
            if (!VARIANT_FIELD.equals(fi.name)) continue;
            if (fi.desc == null || !fi.desc.startsWith("L") || !fi.desc.endsWith(";")) continue;
            return fi.desc.substring(1, fi.desc.length() - 1);
        }
        return null;
    }

}
