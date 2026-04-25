package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.linked.ConcurrentLinkedMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.client.ClientJarDownloader;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tooling.entity.BedrockEntityManifest;
import lib.minecraft.renderer.tooling.entity.MobRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.VariantReconciler;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Entry point invoked by the {@code entityModels} Gradle task.
 * <p>
 * Produces a self-contained Bedrock-native entity bundle from a single source-of-truth zip
 * ({@link #BEDROCK_PACK_URL Mojang's {@code bedrock-samples}}). In one pass the tooling:
 * <ol>
 *   <li>Scans the Java client jar for living-mob {@code EntityType} ids - the <b>only</b> use of
 *       Java data in this pipeline. Used as an inclusion filter so Bedrock-only experimental
 *       entities (agent, npc, villager_v2) drop out.</li>
 *   <li>Parses every {@code resource_pack/entity/*.entity.json} into a
 *       {@link BedrockEntityManifest.Manifest manifest} of (identifier -&gt; geometry + texture
 *       reference).</li>
 *   <li>Parses every {@code resource_pack/models/entity/*.geo.json} into bone/cube trees,
 *       resolves geometry inheritance, and stores the result verbatim in Bedrock's authored
 *       Y-up absolute-coord frame.</li>
 *   <li>For each surviving entity: copies its referenced PNG out of the same zip into
 *       {@code src/main/resources/lib/minecraft/renderer/entity_textures/&lt;ref&gt;.png} so the
 *       runtime ships with its own texture set independent of Java's client atlas.</li>
 *   <li>Writes the paired metadata JSONs to {@code src/main/resources/lib/minecraft/renderer/}.</li>
 * </ol>
 * <p>
 * Outputs:
 * <ul>
 *   <li>{@code entity_models.json} - per-entity row with {@code geometry_ref}, {@code texture_ref}
 *       (path under {@code entity_textures/}), {@code armor_type}, optional {@code variant_of}.</li>
 *   <li>{@code entity_geometry.json} - deduplicated bone/cube trees keyed by Bedrock geometry id.</li>
 *   <li>{@code entity_textures/&lt;ref&gt;.png} - Bedrock entity PNGs, copied verbatim.</li>
 * </ul>
 */
@UtilityClass
public final class ToolingEntityModels {

    /** Mojang's {@code bedrock-samples} branch archive - the sole data source for entities. */
    private static final @NotNull String BEDROCK_PACK_URL =
        "https://github.com/Mojang/bedrock-samples/archive/refs/heads/main.zip";

    /** Bundled output - per-entity metadata rows. */
    private static final @NotNull Path MODELS_OUTPUT_PATH =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_models.json");

    /** Bundled output - deduplicated geometry trees keyed by Bedrock geometry id. */
    private static final @NotNull Path GEOMETRY_OUTPUT_PATH =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_geometry.json");

    /** Bundled output - directory under which each entity's PNG lives at {@code <texture_ref>.png}. */
    private static final @NotNull Path TEXTURES_OUTPUT_DIR =
        Path.of("src/main/resources/lib/minecraft/renderer/entity_textures");

    /** In-zip prefix where Bedrock stores entity geometry files. */
    private static final @NotNull String GEO_PREFIX = "resource_pack/models/entity/";

    /** In-zip prefix for entity texture PNGs. Every emitted {@code texture_ref} is appended to this to locate the source PNG. */
    private static final @NotNull String TEX_PREFIX = "resource_pack/textures/entity/";

    /** Namespace emitted on every entity identifier. */
    private static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    /** Bedrock geometry identifier prefix; stripped for fallback name-based classification. */
    private static final @NotNull String GEOMETRY_PREFIX = "geometry.";

    /**
     * Aliases from a declared {@code description.parent} id to the geometry the pack actually
     * ships. {@code geometry.humanoid} is Bedrock's engine-built-in humanoid rig; this pack ships
     * the equivalent body as {@code geometry.humanoid.custom}, so legacy children that name
     * {@code geometry.humanoid} rewire onto the custom variant.
     */
    private static final @NotNull Map<String, String> PARENT_ALIASES = Map.ofEntries(
        Map.entry("geometry.humanoid", "geometry.humanoid.custom")
    );

    /**
     * Runs the generator.
     *
     * @param args ignored - all output paths are fixed
     * @throws IOException if the pack cannot be downloaded or an output file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        HttpFetcher fetcher = new HttpFetcher();

        // Java client jar: the mob-id filter only. No textures, no models - just the set of
        // Java-registered living-mob EntityType ids so Bedrock-only experimentals drop out.
        System.out.println("Downloading Minecraft client jar for mob-registry filter...");
        Path clientJar = ClientJarDownloader.download(AssetPipelineOptions.defaults(), fetcher);
        Set<String> knownMobIds = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            Diagnostics diag = new Diagnostics();
            for (MobRegistryDiscovery.MobEntry entry : MobRegistryDiscovery.discover(zip, diag))
                knownMobIds.add(entry.entityId());
            System.out.println("Discovered " + knownMobIds.size() + " living-mob entity ids");
            diag.entries().forEach(System.out::println);
        }

        // Bedrock pack: the sole entity data source. Buffered in memory so every pass - manifest,
        // geometry, texture copy - reads from the same snapshot without re-downloading.
        System.out.println("Downloading Bedrock vanilla resource pack...");
        byte[] zipBytes = fetcher.get(BEDROCK_PACK_URL);
        System.out.println("Downloaded " + zipBytes.length + " bytes");

        // Pass 1: client-entity manifest (identifier -> {geometryId, textureRef}).
        Diagnostics manifestDiag = new Diagnostics();
        BedrockEntityManifest.Manifest manifest = BedrockEntityManifest.parse(zipBytes, manifestDiag);
        manifestDiag.entries().forEach(System.out::println);
        System.out.println("Parsed " + manifest.byIdentifier().size() + " entity.json entries");

        // Pass 2: every .geo.json file. First-occurrence-wins dedup on geometry id.
        Map<String, Parser.ParsedEntity> geometriesById = new LinkedHashMap<>();
        int geoFileCount = 0;
        try (var zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                int idx = name.indexOf(GEO_PREFIX);
                if (idx < 0) continue;
                String suffix = name.substring(idx + GEO_PREFIX.length());
                if (suffix.contains("/") || !suffix.endsWith(".json")) continue;

                geoFileCount++;
                ConcurrentList<Parser.ParsedEntity> parsed = Parser.parse(new String(zis.readAllBytes()));
                for (Parser.ParsedEntity pe : parsed)
                    geometriesById.putIfAbsent(pe.identifier(), pe);
            }
        }
        System.out.println("Parsed " + geoFileCount + " .geo.json files, "
            + geometriesById.size() + " unique geometries");

        // Pass 2b: fold parent bones into each child (legacy "A:B" + modern description.parent).
        int resolved = resolveInheritance(geometriesById);
        System.out.println("Resolved inheritance for " + resolved + " geometries");

        // Emit entities. The manifest is authoritative for identifier->{geometry, texture}; the
        // geometry-stem fallback catches Bedrock-only climate variants whose .entity.json file
        // the pack does not ship (cow_cold, pig_warm, etc.).
        Map<String, JsonObject> emittedEntities = new LinkedHashMap<>();
        Map<String, Parser.ParsedEntity> emittedGeometries = new LinkedHashMap<>();
        // Snapshot of copies already performed so identical textures referenced by multiple
        // entity ids (horse/donkey/mule reusing the horse texture) copy exactly once.
        Set<String> copiedTextures = new LinkedHashSet<>();
        int droppedNonMob = 0;
        int keptBase = 0;
        int keptVariant = 0;

        // Build a one-shot zip index so the texture-copy pass doesn't rescan for every PNG.
        Map<String, byte[]> texturePngs = loadTexturePngs(zipBytes);
        System.out.println("Indexed " + texturePngs.size() + " entity texture PNGs from the pack");

        for (Map.Entry<String, BedrockEntityManifest.Entry> e : manifest.byIdentifier().entrySet()) {
            BedrockEntityManifest.Entry manifestEntry = e.getValue();
            Parser.ParsedEntity pe = geometriesById.get(manifestEntry.geometryId());
            if (pe == null) continue;

            VariantReconciler.Classification c = VariantReconciler.classify(e.getKey(), knownMobIds);
            if (!c.isMob()) { droppedNonMob++; continue; }

            boolean added = addEmission(
                emittedEntities, emittedGeometries, copiedTextures, texturePngs,
                c, pe, manifestEntry.geometryId(), manifestEntry.textureRef()
            );
            if (added) {
                if (c.baseMobId() != null) keptVariant++; else keptBase++;
            }
        }

        for (Map.Entry<String, Parser.ParsedEntity> g : geometriesById.entrySet()) {
            String geometryId = g.getKey();
            if (!manifest.fanOutByGeometry().getOrDefault(geometryId, java.util.List.of()).isEmpty()) continue;

            String stem = bedrockStem(geometryId);
            if (stem == null) continue;
            VariantReconciler.Classification c = VariantReconciler.classify(stem, knownMobIds);
            if (!c.isMob()) { droppedNonMob++; continue; }

            // Climate variants like cow_cold/chicken_cold/sheep_sheared have no entity.json of
            // their own; derive a texture_ref from Bedrock's <base>/<canonical> pack convention
            // and only keep it when the PNG actually exists. Base mobs (rare in this path)
            // try <canonical>/<canonical> first.
            String fallbackRef = null;
            String canonical = c.canonicalId();
            String base = c.baseMobId();
            if (canonical != null) {
                String guess = (base != null ? base : canonical) + "/" + canonical;
                if (texturePngs.containsKey(guess)) fallbackRef = guess;
                else if (base != null && texturePngs.containsKey(base + "/" + base)) fallbackRef = base + "/" + base;
            }

            boolean added = addEmission(
                emittedEntities, emittedGeometries, copiedTextures, texturePngs,
                c, g.getValue(), geometryId, fallbackRef
            );
            if (added) {
                if (c.baseMobId() != null) keptVariant++; else keptBase++;
            }
        }

        System.out.printf(
            "Emitted %d entities (%d base + %d variant) across %d geometries, dropped %d non-mob, copied %d textures%n",
            emittedEntities.size(), keptBase, keptVariant, emittedGeometries.size(), droppedNonMob, copiedTextures.size()
        );

        Files.createDirectories(MODELS_OUTPUT_PATH.getParent());
        Files.writeString(MODELS_OUTPUT_PATH, buildModelsJson(emittedEntities));
        Files.writeString(GEOMETRY_OUTPUT_PATH, buildGeometryJson(emittedGeometries));
        System.out.println("Wrote " + MODELS_OUTPUT_PATH.toAbsolutePath());
        System.out.println("Wrote " + GEOMETRY_OUTPUT_PATH.toAbsolutePath());
        System.out.println("Wrote " + copiedTextures.size() + " PNGs under " + TEXTURES_OUTPUT_DIR.toAbsolutePath());
    }

    /** TGA reader - used directly because Bedrock ships TGA 1.0 files that {@code ImageFactory}'s
     * magic-byte auto-detect (which keys on the TGA 2.0 footer) would reject. */
    private static final @NotNull dev.simplified.image.codec.tga.TgaImageReader TGA_READER =
        new dev.simplified.image.codec.tga.TgaImageReader();

    /** PNG writer used to re-encode Bedrock's TGA sources into bundled PNGs. */
    private static final @NotNull dev.simplified.image.codec.png.PngImageWriter PNG_WRITER =
        new dev.simplified.image.codec.png.PngImageWriter();

    /**
     * Indexes every {@code resource_pack/textures/entity/**} PNG and TGA in the pack by its
     * sub-path below {@code textures/entity/} (without extension). TGA sources are decoded via
     * {@link #TGA_READER} and re-encoded through {@link #PNG_WRITER} so the bundled classpath
     * layout stays pure PNG and the runtime loader only needs Java's built-in PNG codec. When
     * both formats exist for the same ref, PNG wins.
     */
    private static @NotNull Map<String, byte[]> loadTexturePngs(byte @NotNull [] zipBytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                int idx = name.indexOf(TEX_PREFIX);
                if (idx < 0) continue;
                String subPath = name.substring(idx + TEX_PREFIX.length());

                String key;
                byte[] png;
                if (subPath.endsWith(".png")) {
                    key = subPath.substring(0, subPath.length() - ".png".length());
                    png = zis.readAllBytes();
                } else if (subPath.endsWith(".tga")) {
                    key = subPath.substring(0, subPath.length() - ".tga".length());
                    if (out.containsKey(key)) continue; // PNG already indexed for this ref.
                    try {
                        dev.simplified.image.ImageData decoded = TGA_READER.read(zis.readAllBytes(), null);
                        png = PNG_WRITER.write(decoded, null);
                    } catch (RuntimeException ex) {
                        System.err.printf("  Warning: failed to decode TGA '%s': %s%n", name, ex.getMessage());
                        continue;
                    }
                } else {
                    continue;
                }
                out.put(key, png);
            }
        }
        return out;
    }

    /**
     * Emits one entity row and, on first sight of its texture_ref, copies the Bedrock PNG out
     * of the in-memory pack index into {@link #TEXTURES_OUTPUT_DIR}. Returns {@code true} when a
     * new entity row was added; returns {@code false} when the canonical id is already emitted.
     */
    private static boolean addEmission(
        @NotNull Map<String, JsonObject> emittedEntities,
        @NotNull Map<String, Parser.ParsedEntity> emittedGeometries,
        @NotNull Set<String> copiedTextures,
        @NotNull Map<String, byte[]> texturePngs,
        @NotNull VariantReconciler.Classification classification,
        @NotNull Parser.ParsedEntity geometry,
        @NotNull String geometryId,
        @Nullable String textureRef
    ) throws IOException {
        String canonicalEntityId = MINECRAFT_NAMESPACE + classification.canonicalId();
        if (emittedEntities.containsKey(canonicalEntityId)) return false;

        JsonObject entityJson = new JsonObject();
        entityJson.addProperty("geometry_ref", geometryId);
        if (textureRef != null) {
            entityJson.addProperty("texture_ref", textureRef);
            if (!copiedTextures.contains(textureRef)) {
                byte[] png = texturePngs.get(textureRef);
                if (png != null) {
                    copyTexture(textureRef, png);
                    copiedTextures.add(textureRef);
                } else {
                    System.err.printf("  Warning: entity '%s' references texture '%s' which is not in the Bedrock pack%n",
                        canonicalEntityId, textureRef);
                }
            }
        }
        entityJson.addProperty("armor_type", geometry.armorType());
        if (classification.baseMobId() != null)
            entityJson.addProperty("variant_of", MINECRAFT_NAMESPACE + classification.baseMobId());

        emittedEntities.put(canonicalEntityId, entityJson);
        emittedGeometries.putIfAbsent(geometryId, geometry);
        return true;
    }

    /**
     * Texture refs that ship from Bedrock with uniform partial alpha for additive blending in
     * Bedrock's render engine, but should appear fully opaque under this static iso renderer.
     * Blaze ships every non-transparent texel at alpha=90 because Bedrock's animation engine
     * does additive blending to produce the rod glow; without that pass the rods come out at
     * 35% opacity. We bump every non-zero alpha to 255 at extraction time so the bundled PNG
     * matches Java's authored opacity.
     */
    private static final @NotNull Set<String> OPAQUE_ALPHA_TEXTURE_REFS = Set.of("blaze");

    /**
     * Copies one PNG from the in-memory pack index into the bundled resources tree, creating
     * any missing parent directories. Uses {@link StandardCopyOption#REPLACE_EXISTING} so a
     * re-run overwrites stale files. When the ref is in {@link #OPAQUE_ALPHA_TEXTURE_REFS} the
     * PNG is decoded, every non-zero alpha is bumped to 255, and the result re-encoded.
     */
    private static void copyTexture(@NotNull String textureRef, byte @NotNull [] pngBytes) throws IOException {
        Path target = TEXTURES_OUTPUT_DIR.resolve(textureRef + ".png");
        Files.createDirectories(target.getParent());
        byte[] outBytes = OPAQUE_ALPHA_TEXTURE_REFS.contains(textureRef)
            ? bumpAlphaToOpaque(pngBytes, textureRef)
            : pngBytes;
        Files.write(target, outBytes, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Decodes a PNG, replaces every {@code alpha > 0 && alpha < 255} with {@code 255}, and
     * re-encodes. Preserves fully-transparent texels (alpha=0). Returns the original bytes
     * unchanged if decoding fails.
     */
    private static byte @NotNull [] bumpAlphaToOpaque(byte @NotNull [] pngBytes, @NotNull String textureRef) throws IOException {
        java.awt.image.BufferedImage src;
        try {
            src = javax.imageio.ImageIO.read(new ByteArrayInputStream(pngBytes));
        } catch (IOException ex) {
            System.err.printf("  Warning: opaque-alpha bump failed to decode '%s': %s%n", textureRef, ex.getMessage());
            return pngBytes;
        }
        if (src == null) return pngBytes;
        int w = src.getWidth();
        int h = src.getHeight();
        java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a > 0 && a < 255) argb = (argb & 0x00FFFFFF) | 0xFF000000;
                dst.setRGB(x, y, argb);
            }
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(dst, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Strips the {@code "geometry."} prefix and collapses dotted segments into underscores so
     * the result is comparable to a Java registry id. Returns {@code null} when the identifier
     * looks like an armor/baby/saddle/versioned variant we know to skip at the fallback level.
     */
    private static @Nullable String bedrockStem(@NotNull String geometryId) {
        String stem = geometryId.startsWith(GEOMETRY_PREFIX) ? geometryId.substring(GEOMETRY_PREFIX.length()) : geometryId;
        stem = stem.replaceAll("\\.v\\d+(\\.\\d+)?$", "");
        if (stem.contains("armor") || stem.contains("baby") || stem.contains("_v1")
            || stem.contains("_v2") || stem.contains("_v3") || stem.contains("saddle"))
            return null;
        return stem.replace(".", "_");
    }

    /**
     * Serialises the entities map into {@code entity_models.json} with stable alphabetical key
     * ordering so re-runs produce byte-identical output.
     */
    private static @NotNull String buildModelsJson(@NotNull Map<String, JsonObject> entities) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. One entry per Java EntityType living mob; geometry_ref points into entity_geometry.json and texture_ref into entity_textures/. Run ./gradlew :asset-renderer:entityModels to refresh.");

        JsonObject entitiesObj = new JsonObject();
        entities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> entitiesObj.add(e.getKey(), e.getValue()));
        root.add("entities", entitiesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    /**
     * Serialises the deduplicated geometries map into {@code entity_geometry.json}.
     */
    private static @NotNull String buildGeometryJson(@NotNull Map<String, Parser.ParsedEntity> geometries) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingEntityModels. Deduplicated bone/cube trees keyed by Bedrock geometry id; referenced from entity_models.json.");

        JsonObject geometriesObj = new JsonObject();
        Gson gson = GsonSettings.defaults().create();
        geometries.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> geometriesObj.add(e.getKey(), gson.toJsonTree(e.getValue().model())));
        root.add("geometries", geometriesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    /**
     * Folds every geometry's parent chain into its bone tree. Bedrock declares inheritance via
     * legacy {@code "A:B"} key form or modern {@code description.parent}; both are recorded as
     * {@link Parser.ParsedEntity#parentIdentifier} by the parser and resolved here. Cycles and
     * unresolved parents fall back to the child's own bones so the generation run never aborts.
     */
    private static int resolveInheritance(@NotNull Map<String, Parser.ParsedEntity> geometriesById) {
        Map<String, EntityModelData> resolvedCache = new LinkedHashMap<>();
        int changes = 0;

        for (String id : new ArrayList<>(geometriesById.keySet())) {
            Parser.ParsedEntity original = geometriesById.get(id);
            EntityModelData resolvedModel = resolveOne(original, geometriesById, resolvedCache, new LinkedHashSet<>());
            if (resolvedModel == original.model()) continue;

            String newArmor = resolvedModel.getBones().isEmpty()
                ? original.armorType()
                : inferArmorTypeFromKeys(resolvedModel.getBones().keySet());
            geometriesById.put(id, new Parser.ParsedEntity(id, resolvedModel, newArmor, original.parentIdentifier()));
            changes++;
        }
        return changes;
    }

    private static @NotNull EntityModelData resolveOne(
        @NotNull Parser.ParsedEntity entity,
        @NotNull Map<String, Parser.ParsedEntity> geometriesById,
        @NotNull Map<String, EntityModelData> cache,
        @NotNull Set<String> stack
    ) {
        String id = entity.identifier();
        EntityModelData cached = cache.get(id);
        if (cached != null) return cached;

        String parentRef = entity.parentIdentifier();
        if (parentRef == null) { cache.put(id, entity.model()); return entity.model(); }

        String resolvedParentId = PARENT_ALIASES.getOrDefault(parentRef, parentRef);
        Parser.ParsedEntity parent = geometriesById.get(resolvedParentId);
        if (parent == null || stack.contains(resolvedParentId)) {
            cache.put(id, entity.model());
            return entity.model();
        }

        stack.add(id);
        EntityModelData parentModel = resolveOne(parent, geometriesById, cache, stack);
        stack.remove(id);

        EntityModelData merged = mergeBones(parentModel, entity.model());
        cache.put(id, merged);
        return merged;
    }

    /**
     * Merges a child's bone tree on top of its parent's. Parent bones are copied first so their
     * insertion order wins at runtime (render-priority ordering); child bones with matching
     * names replace the parent entry in place, and child-only bones are appended. Texture
     * dimensions come from whichever side declared them non-default.
     */
    private static @NotNull EntityModelData mergeBones(
        @NotNull EntityModelData parent,
        @NotNull EntityModelData child
    ) {
        ConcurrentLinkedMap<String, EntityModelData.Bone> merged = Concurrent.newLinkedMap();
        merged.putAll(parent.getBones());
        for (Map.Entry<String, EntityModelData.Bone> e : child.getBones().entrySet())
            merged.put(e.getKey(), e.getValue());

        int texW = child.getTextureWidth() != 64 || parent.getTextureWidth() == 64
            ? child.getTextureWidth()
            : parent.getTextureWidth();
        int texH = child.getTextureHeight() != 64 || parent.getTextureHeight() == 64
            ? child.getTextureHeight()
            : parent.getTextureHeight();

        return new EntityModelData(texW, texH, child.getInventoryYRotation(), merged);
    }

    /** Re-runs armor classification after a merge changes the bone set. */
    private static @NotNull String inferArmorTypeFromKeys(@NotNull Set<String> boneNames) {
        boolean hasHead = boneNames.contains("head");
        boolean hasBody = boneNames.contains("body");
        boolean hasArms = boneNames.contains("rightArm") || boneNames.contains("right_arm");
        boolean hasLegs = boneNames.contains("rightLeg") || boneNames.contains("right_leg");
        return (hasHead && hasBody && hasArms && hasLegs) ? "humanoid" : "none";
    }

    /**
     * Parser for Bedrock {@code .geo.json} entity model files.
     * <p>
     * Handles both legacy {@code 1.8.0} (top-level {@code "geometry.<id>"} keys, with optional
     * {@code "A:B"} inheritance syntax) and modern {@code 1.12.0+} ({@code "minecraft:geometry"}
     * array with a {@code "description"} block). All coordinates - bone pivots, cube origins,
     * cube pivots, sizes, rotations - are stored verbatim in the authored Bedrock-native Y-up
     * absolute-coord frame. Per-cube UVs are parsed from both the legacy {@code [u, v]} array
     * form and the {@code 1.12+} {@code {face: {uv, uv_size}}} object form.
     *
     * @see EntityModelLoader
     * @see EntityModelData
     */
    @UtilityClass
    static class Parser {

        private static final @NotNull Gson GSON = GsonSettings.defaults().create();

        /**
         * Result of parsing one geometry entry.
         *
         * @param identifier the Bedrock geometry identifier
         * @param model the parsed bone/cube tree (Bedrock-native coords)
         * @param armorType inferred armor wearability ({@code "humanoid"} or {@code "none"})
         * @param parentIdentifier the parent geometry id this one inherits from, or {@code null}
         */
        public record ParsedEntity(
            @NotNull String identifier,
            @NotNull EntityModelData model,
            @NotNull String armorType,
            @Nullable String parentIdentifier
        ) {}

        /**
         * Parses all geometry definitions from a single {@code .geo.json} file. A single file
         * may contain multiple geometries (adult/baby variants, etc.).
         */
        public static @NotNull ConcurrentList<ParsedEntity> parse(@NotNull String json) {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return Concurrent.newList();

            ConcurrentList<ParsedEntity> results = Concurrent.newList();
            if (root.has("minecraft:geometry"))
                parseModern(root.getAsJsonArray("minecraft:geometry"), results);
            else
                parseLegacy(root, results);
            return results;
        }

        /** Modern 1.12+ format: {@code "minecraft:geometry": [{ "description": {...}, "bones": [...] }]}. */
        private static void parseModern(@NotNull JsonArray geometries, @NotNull ConcurrentList<ParsedEntity> results) {
            for (JsonElement el : geometries) {
                JsonObject geo = el.getAsJsonObject();
                JsonObject description = geo.getAsJsonObject("description");
                if (description == null) continue;

                String identifier = description.has("identifier")
                    ? description.get("identifier").getAsString() : "unknown";
                int texW = description.has("texture_width") ? description.get("texture_width").getAsInt() : 64;
                int texH = description.has("texture_height") ? description.get("texture_height").getAsInt() : 64;
                String parent = description.has("parent") ? description.get("parent").getAsString() : null;

                JsonArray bonesArray = geo.has("bones") ? geo.getAsJsonArray("bones") : new JsonArray();
                EntityModelData model = buildModel(bonesArray, texW, texH);
                if (model.getBones().isEmpty() && parent == null) continue;

                results.add(new ParsedEntity(identifier, model, inferArmorType(model.getBones().keySet()), parent));
            }
        }

        /** Legacy 1.8.0 format: top-level {@code "geometry.<id>[:<parent_id>]"} keys. */
        private static void parseLegacy(@NotNull JsonObject root, @NotNull ConcurrentList<ParsedEntity> results) {
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();
                if (key.equals("format_version")) continue;
                if (!entry.getValue().isJsonObject()) continue;

                int colon = key.indexOf(':');
                String identifier = colon >= 0 ? key.substring(0, colon) : key;
                String parent = colon >= 0 ? key.substring(colon + 1) : null;

                JsonObject geo = entry.getValue().getAsJsonObject();
                int texW = geo.has("texturewidth") ? geo.get("texturewidth").getAsInt() : 64;
                int texH = geo.has("textureheight") ? geo.get("textureheight").getAsInt() : 64;

                JsonArray bonesArray = geo.has("bones") ? geo.getAsJsonArray("bones") : new JsonArray();
                EntityModelData model = buildModel(bonesArray, texW, texH);
                if (model.getBones().isEmpty() && parent == null) continue;

                results.add(new ParsedEntity(identifier, model, inferArmorType(model.getBones().keySet()), parent));
            }
        }

        /**
         * Converts a Bedrock bones array into an {@link EntityModelData} with a flat bone map in
         * Bedrock-native absolute coords. {@code rotation} and {@code bind_pose_rotation} are
         * summed into the bone's static orientation; per-cube UV supports both array and object
         * forms.
         */
        private static @NotNull EntityModelData buildModel(
            @NotNull JsonArray bonesArray,
            int textureWidth,
            int textureHeight
        ) {
            ConcurrentLinkedMap<String, EntityModelData.Bone> bones = Concurrent.newLinkedMap();

            for (JsonElement boneElement : bonesArray) {
                JsonObject boneJson = boneElement.getAsJsonObject();
                String name = boneJson.has("name") ? boneJson.get("name").getAsString() : "unnamed";
                if (boneJson.has("neverRender") && boneJson.get("neverRender").getAsBoolean()) continue;

                String boneParent = boneJson.has("parent") && !boneJson.get("parent").isJsonNull()
                    ? boneJson.get("parent").getAsString() : null;
                boolean boneMirror = boneJson.has("mirror") && boneJson.get("mirror").getAsBoolean();

                float[] pivot = readFloatArray(boneJson, "pivot", new float[]{ 0, 0, 0 });
                float[] r = readFloatArray(boneJson, "rotation", new float[]{ 0, 0, 0 });
                float[] bp = readFloatArray(boneJson, "bind_pose_rotation", new float[]{ 0, 0, 0 });
                // rotation propagates through the ancestor chain (children inherit); bind_pose_rotation
                // is a static per-bone pose that applies ONLY to this bone's own cubes. Vanilla v1.8
                // sheep/cow/pig bodies use bind_pose_rotation=[90,0,0] to lay a vertically-authored
                // torso horizontal without also rotating their child leg bones.
                EulerRotation rotation = new EulerRotation(r[0], r[1], r[2]);
                EulerRotation bindPoseRotation = new EulerRotation(bp[0], bp[1], bp[2]);

                JsonArray cubesArray = boneJson.has("cubes") ? boneJson.getAsJsonArray("cubes") : new JsonArray();
                ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();

                for (JsonElement cubeElement : cubesArray) {
                    JsonObject cubeJson = cubeElement.getAsJsonObject();
                    float[] origin = readFloatArray(cubeJson, "origin", new float[]{ 0, 0, 0 });
                    float[] size = readFloatArray(cubeJson, "size", new float[]{ 1, 1, 1 });
                    float inflate = cubeJson.has("inflate") ? cubeJson.get("inflate").getAsFloat() : 0f;
                    boolean mirror = cubeJson.has("mirror")
                        ? cubeJson.get("mirror").getAsBoolean()
                        : boneMirror;

                    int[] uv = new int[]{ 0, 0 };
                    ConcurrentMap<String, EntityModelData.FaceUv> faceUv = Concurrent.newMap();
                    if (cubeJson.has("uv")) {
                        JsonElement uvEl = cubeJson.get("uv");
                        if (uvEl.isJsonArray()) {
                            JsonArray arr = uvEl.getAsJsonArray();
                            uv = new int[]{ (int) arr.get(0).getAsFloat(), (int) arr.get(1).getAsFloat() };
                        } else if (uvEl.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> fe : uvEl.getAsJsonObject().entrySet()) {
                                if (!fe.getValue().isJsonObject()) continue;
                                JsonObject faceObj = fe.getValue().getAsJsonObject();
                                EntityModelData.FaceUv fu = new EntityModelData.FaceUv();
                                if (faceObj.has("uv")) copyFloat2(readFloatArray(faceObj, "uv", new float[]{0,0}), fu.getUv());
                                if (faceObj.has("uv_size")) copyFloat2(readFloatArray(faceObj, "uv_size", new float[]{0,0}), fu.getUvSize());
                                faceUv.put(fe.getKey(), fu);
                            }
                        }
                    }

                    // Bedrock 1.12+ cube rotation semantics: when a cube declares `rotation` without
                    // an explicit `pivot`, the rotation anchor defaults to the cube's geometric
                    // centre (origin + size/2), NOT the owning bone's pivot. Pig.v3 and friends
                    // rely on this - their body cube rotates around its own centre to lay flat.
                    // When the cube has no rotation the pivot is irrelevant (fast path in
                    // EntityGeometryKit.composeCubeTransform); we still populate it from the bone
                    // pivot as a safe placeholder so downstream equals/hashCode stays meaningful.
                    float[] cr = readFloatArray(cubeJson, "rotation", new float[]{ 0, 0, 0 });
                    boolean cubeHasRotation = cr[0] != 0f || cr[1] != 0f || cr[2] != 0f;
                    float[] cubePivot;
                    if (cubeJson.has("pivot")) {
                        cubePivot = readFloatArray(cubeJson, "pivot", pivot);
                    } else if (cubeHasRotation) {
                        cubePivot = new float[]{
                            origin[0] + size[0] * 0.5f,
                            origin[1] + size[1] * 0.5f,
                            origin[2] + size[2] * 0.5f
                        };
                    } else {
                        cubePivot = pivot;
                    }
                    EulerRotation cubeRotation = new EulerRotation(cr[0], cr[1], cr[2]);

                    cubes.add(new EntityModelData.Cube(origin, size, uv, inflate, mirror, cubePivot, cubeRotation, faceUv));
                }

                bones.put(name, new EntityModelData.Bone(pivot, rotation, bindPoseRotation, cubes, boneParent));
            }

            return new EntityModelData(textureWidth, textureHeight, 0f, bones);
        }

        private static void copyFloat2(float @NotNull [] src, float @NotNull [] dst) {
            if (src.length >= 2 && dst.length >= 2) { dst[0] = src[0]; dst[1] = src[1]; }
        }

        /** Classifies armor wearability from bone names alone. */
        private static @NotNull String inferArmorType(@NotNull Set<String> boneNames) {
            boolean hasHead = boneNames.contains("head");
            boolean hasBody = boneNames.contains("body");
            boolean hasArms = boneNames.contains("rightArm") || boneNames.contains("right_arm");
            boolean hasLegs = boneNames.contains("rightLeg") || boneNames.contains("right_leg");
            return (hasHead && hasBody && hasArms && hasLegs) ? "humanoid" : "none";
        }

        private static float @NotNull [] readFloatArray(@NotNull JsonObject obj, @NotNull String key, float @NotNull [] fallback) {
            if (!obj.has(key)) return fallback;
            JsonElement el = obj.get(key);
            if (!el.isJsonArray()) return fallback;
            JsonArray arr = el.getAsJsonArray();
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsFloat();
            return out;
        }

    }

}
