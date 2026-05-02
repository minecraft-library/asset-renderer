package lib.minecraft.renderer.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.ImageData;
import dev.simplified.image.codec.png.PngImageWriter;
import dev.simplified.image.codec.tga.TgaImageReader;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.exception.ToolingException;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import lib.minecraft.renderer.tooling.entity.BedrockEntityManifest;
import lib.minecraft.renderer.tooling.entity.MobRegistryDiscovery;
import lib.minecraft.renderer.tooling.entity.VariantReconciler;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>
 * TODO: deferred entity-pose / texture issues are tracked in
 * {@code ~/.claude/plans/entity-pipeline-deferred.md}. Currently open: ender_dragon
 * wing/wingtip orientation (strip-layout UV symmetry + unsolved wing1 mirror puzzle)
 * and zombie_horse texture coverage (Bedrock pack ships a 64x64 Java-layout PNG against
 * a 128x128 Bedrock-layout geometry, leaving most UVs sampling empty pixels).
 */
@UtilityClass
public final class ToolingEntityModels {

    /** Mojang's {@code bedrock-samples} branch archive - the sole data source for entities. */
    private static final @NotNull String BEDROCK_PACK_URL =
        "https://github.com/Mojang/bedrock-samples/archive/refs/heads/main.zip";

    /**
     * Downloads {@code url} as a byte array via the JDK {@link HttpClient}. Inlined here because
     * the only call site is the Bedrock pack zip on GitHub, which sits outside any
     * {@link api.simplified.mojang.MojangContract} route.
     */
    private static byte @NotNull [] downloadGithubArchive(@NotNull String url) throws IOException {
        try {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() / 100 != 2)
                throw new ToolingException("GET '%s' returned HTTP '%d'", url, response.statusCode());
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ToolingException(ex, "Interrupted while downloading '%s'", url);
        }
    }

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
        // Java client jar: the mob-id filter only. No textures, no models - just the set of
        // Java-registered living-mob EntityType ids so Bedrock-only experimentals drop out.
        System.out.println("Downloading Minecraft client jar for mob-registry filter...");
        Path clientJar = Pipeline.downloadJarToCache(PipelineOptions.defaults());
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
        // Direct GitHub-archive download stays inline since the URL is outside MojangContract's
        // routed domains and only this generator ever hits it.
        System.out.println("Downloading Bedrock vanilla resource pack...");
        byte[] zipBytes = downloadGithubArchive(BEDROCK_PACK_URL);
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
            if (!manifest.fanOutByGeometry().getOrDefault(geometryId, List.of()).isEmpty()) continue;

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

        // Pull in overlay-only textures (creeper_armor, etc.) that no entity.json references.
        // The per-entity copy loop above is gated on manifest entries, so without this pass these
        // refs would never land under entity_textures/ even though the override layer needs them.
        for (String forcedRef : FORCED_EXTRA_TEXTURE_REFS) {
            if (copiedTextures.contains(forcedRef)) continue;
            byte[] png = texturePngs.get(forcedRef);
            if (png == null) {
                System.err.printf("  Warning: forced extra texture '%s' not found in the Bedrock pack%n", forcedRef);
                continue;
            }
            copyTexture(forcedRef, png);
            copiedTextures.add(forcedRef);
        }

        // Java-jar emissive overlay extraction: walk the cached client jar for
        // {@code entity/**/*_eyes.png} files, copy each into entity_textures/, and append a
        // matching `overlays` entry to every entity in EMISSIVE_PNG_FANOUT. The runtime layers
        // these on top of the base body with emissive: true so the rasterizer renders them
        // additive + full-bright (vanilla Java's RenderType.eyes pattern).
        int emissiveCopied = extractJavaEmissiveOverlays(clientJar, copiedTextures, emittedEntities);
        System.out.println("Extracted " + emissiveCopied + " emissive overlay PNGs from the Java client jar");

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
    private static final @NotNull TgaImageReader TGA_READER =
        new TgaImageReader();

    /** PNG writer used to re-encode Bedrock's TGA sources into bundled PNGs. */
    private static final @NotNull PngImageWriter PNG_WRITER =
        new PngImageWriter();

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
                        ImageData decoded = TGA_READER.read(zis.readAllBytes(), null);
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
     * Walks the cached Java client jar for {@code assets/minecraft/textures/entity/**} PNGs that
     * appear in {@link #EMISSIVE_PNG_FANOUT}, extracts each into the bundled
     * {@link #TEXTURES_OUTPUT_DIR}, and appends an {@code overlays} entry to every fan-out
     * entity already present in {@code emittedEntities}. The overlay reuses each entity's own
     * {@code geometry_ref} so eye UVs land on the head face; non-eye regions sample transparent
     * pixels from the eye-only PNG and render nothing. Logs a warning for any {@code _eyes.png}
     * file in the jar that isn't in the fan-out (so new emissive layers in a future Minecraft
     * version surface during regen) and for any fan-out entry whose target entity isn't in the
     * emitted set (so pruned mobs don't silently lose their overlay).
     *
     * @param clientJar the cached Minecraft client jar path
     * @param copiedTextures set of already-copied texture refs, mutated to include each new
     *     emissive PNG so duplicates are avoided
     * @param emittedEntities the entity-id keyed map of generated JSON rows; mutated in place to
     *     append {@code overlays} entries
     * @return the number of emissive PNGs successfully copied into {@link #TEXTURES_OUTPUT_DIR}
     */
    private static int extractJavaEmissiveOverlays(
        @NotNull Path clientJar,
        @NotNull Set<String> copiedTextures,
        @NotNull Map<String, JsonObject> emittedEntities
    ) throws IOException {
        Set<String> wanted = EMISSIVE_PNG_FANOUT.keySet();
        Map<String, byte[]> found = new LinkedHashMap<>();
        Set<String> seenButUnmapped = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(JAVA_TEX_PREFIX) || !name.endsWith("_eyes.png")) continue;
                String subPath = name.substring(JAVA_TEX_PREFIX.length(), name.length() - ".png".length());
                if (!wanted.contains(subPath)) {
                    seenButUnmapped.add(subPath);
                    continue;
                }
                try (var in = zip.getInputStream(entry)) {
                    found.put(subPath, in.readAllBytes());
                }
            }
        }
        for (String unmapped : seenButUnmapped)
            System.err.printf("  Note: client jar emissive PNG '%s' has no EMISSIVE_PNG_FANOUT entry; skipped%n", unmapped);

        int copied = 0;
        for (Map.Entry<String, List<String>> e : EMISSIVE_PNG_FANOUT.entrySet()) {
            String emissiveRef = e.getKey();
            byte[] png = found.get(emissiveRef);
            if (png == null) {
                System.err.printf("  Warning: emissive overlay '%s' not found in the Java client jar%n", emissiveRef);
                continue;
            }
            if (!copiedTextures.contains(emissiveRef)) {
                copyTexture(emissiveRef, png);
                copiedTextures.add(emissiveRef);
                copied++;
            }
            for (String entityId : e.getValue()) {
                JsonObject entityJson = emittedEntities.get(entityId);
                if (entityJson == null) {
                    System.err.printf("  Note: emissive fan-out target '%s' for '%s' is not in the emitted entity set; skipped%n",
                        entityId, emissiveRef);
                    continue;
                }
                JsonArray overlays = entityJson.has("overlays") && entityJson.get("overlays").isJsonArray()
                    ? entityJson.getAsJsonArray("overlays")
                    : new JsonArray();
                JsonObject overlay = new JsonObject();
                overlay.addProperty("geometry_ref", entityJson.get("geometry_ref").getAsString());
                overlay.addProperty("texture_ref", emissiveRef);
                overlay.addProperty("emissive", true);
                overlays.add(overlay);
                entityJson.add("overlays", overlays);
            }
        }
        return copied;
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
     * Texture refs that no {@code .entity.json} references in the Bedrock pack but the override
     * layer needs for layered rendering. Vanilla Java composes some entities from a base mesh
     * plus a hardcoded overlay layer ({@code CreeperPowerLayer} draws
     * {@code creeper/creeper_armor.png} on top of charged creepers) - those overlay textures
     * never appear in any client-entity manifest, so the per-entity copy loop misses them. We
     * extract them here as an explicit allowlist so the runtime override can find them under
     * {@code entity_textures/}.
     */
    private static final @NotNull Set<String> FORCED_EXTRA_TEXTURE_REFS = Set.of(
        "creeper/creeper_armor",
        "guardian_elder"
    );

    /** In-jar prefix where vanilla Java stores entity texture PNGs. Used by the emissive-overlay
     * extraction pass that mirrors {@link #TEX_PREFIX}'s role for the Bedrock pack. */
    private static final @NotNull String JAVA_TEX_PREFIX =
        "assets/minecraft/textures/entity/";

    /**
     * Vanilla Java's per-entity emissive overlay PNGs, mapped to the entity ids they should be
     * layered on top of. Vanilla composes these via {@code RenderType.eyes} - full-bright +
     * additive ({@code glBlendFunc(SRC_ALPHA, ONE)}) - on the same geometry as the base body so
     * the eye pixels land on the head face and the rest of the overlay samples transparent.
     * <p>
     * Sourced by inspecting {@code RenderType.eyes} callers in the Java client jar; one
     * texture-ref entry per emissive PNG (the sub-path under
     * {@code assets/minecraft/textures/entity/}, without the {@code .png} extension), value is
     * the entity ids that share that emissive overlay (cave spider reuses spider's eyes; the
     * cyan body tint is in the body texture, not the eyes). The mapping changes rarely - one
     * line per new vanilla mob with an emissive layer. Unknown emissive PNGs in the jar log a
     * warning during extraction so they surface for review.
     */
    private static final @NotNull Map<String, List<String>> EMISSIVE_PNG_FANOUT = Map.ofEntries(
        Map.entry("spider/spider_eyes",      List.of("minecraft:spider", "minecraft:cave_spider")),
        Map.entry("enderdragon/dragon_eyes", List.of("minecraft:ender_dragon")),
        Map.entry("enderman/enderman_eyes",  List.of("minecraft:enderman")),
        Map.entry("phantom/phantom_eyes",    List.of("minecraft:phantom")),
        Map.entry("breeze/breeze_eyes",      List.of("minecraft:breeze")),
        Map.entry("creaking/creaking_eyes",  List.of("minecraft:creaking")),
        Map.entry("copper_golem/copper_golem_eyes",
            List.of(
                "minecraft:copper_golem",
                "minecraft:copper_golem_running",
                "minecraft:copper_golem_sitting",
                "minecraft:copper_golem_star"
                // copper_golem_flower is NOT here: its overrides JSON re-roots geometry_ref
                // from geometry.copper_golem.flower (the flower-only mesh) to the full
                // geometry.copper_golem and adds the flower as an overlay. The auto-extractor
                // works against the un-overridden entity_models.json geometry, so an auto entry
                // here would emit an overlay using geometry.copper_golem.flower - which is the
                // flower, not the body, and the eye UVs would miss the head entirely. The
                // flower variant's emissive overlay is added manually in
                // entity_models_overrides.json instead.
            ))
        // Oxidation-stage variants (copper_golem_eyes_exposed, _weathered, _oxidized) exist as
        // separate PNG files in the Java jar but are sourced via per-state texture binding
        // rather than emissive overlays on distinct entity ids. Add them here only when the
        // pipeline emits matching minecraft:copper_golem_exposed (etc.) entity ids.
    );

    /**
     * Copies one PNG from the in-memory pack index into the bundled resources tree, creating
     * any missing parent directories. The bundled file is written verbatim - any per-entity
     * alpha-bumping for entities whose Bedrock texture is authored at low alpha for additive
     * blending now happens at runtime via the {@code force_opaque} entity-override flag, not
     * at extraction time, so the bundled PNG stays exactly as Bedrock ships it.
     */
    private static void copyTexture(@NotNull String textureRef, byte @NotNull [] pngBytes) throws IOException {
        Path target = TEXTURES_OUTPUT_DIR.resolve(textureRef + ".png");
        Files.createDirectories(target.getParent());
        Files.write(target, pngBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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

                Vector3f pivot = readVector3f(boneJson, "pivot", Vector3f.ZERO);
                EulerRotation rotation = readEulerRotation(boneJson, "rotation");
                // rotation propagates through the ancestor chain (children inherit); bind_pose_rotation
                // is a static per-bone pose that applies ONLY to this bone's own cubes. Vanilla v1.8
                // sheep/cow/pig bodies use bind_pose_rotation=[90,0,0] to lay a vertically-authored
                // torso horizontal without also rotating their child leg bones.
                EulerRotation bindPoseRotation = readEulerRotation(boneJson, "bind_pose_rotation");

                JsonArray cubesArray = boneJson.has("cubes") ? boneJson.getAsJsonArray("cubes") : new JsonArray();
                ConcurrentList<EntityModelData.Cube> cubes = Concurrent.newList();

                for (JsonElement cubeElement : cubesArray) {
                    JsonObject cubeJson = cubeElement.getAsJsonObject();
                    Vector3f origin = readVector3f(cubeJson, "origin", Vector3f.ZERO);
                    Vector3f size = readVector3f(cubeJson, "size", new Vector3f(1, 1, 1));
                    float inflate = cubeJson.has("inflate") ? cubeJson.get("inflate").getAsFloat() : 0f;
                    boolean mirror = cubeJson.has("mirror")
                        ? cubeJson.get("mirror").getAsBoolean()
                        : boneMirror;

                    Vector2f uv = Vector2f.ZERO;
                    ConcurrentMap<String, EntityModelData.FaceUv> faceUv = Concurrent.newMap();
                    if (cubeJson.has("uv")) {
                        JsonElement uvEl = cubeJson.get("uv");
                        if (uvEl.isJsonArray()) {
                            JsonArray arr = uvEl.getAsJsonArray();
                            uv = new Vector2f((int) arr.get(0).getAsFloat(), (int) arr.get(1).getAsFloat());
                        } else if (uvEl.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> fe : uvEl.getAsJsonObject().entrySet()) {
                                if (!fe.getValue().isJsonObject()) continue;
                                JsonObject faceObj = fe.getValue().getAsJsonObject();
                                Vector2f faceUvOrigin = readVector2f(faceObj, "uv", Vector2f.ZERO);
                                Vector2f faceUvSize = readVector2f(faceObj, "uv_size", Vector2f.ZERO);
                                faceUv.put(fe.getKey(), new EntityModelData.FaceUv(faceUvOrigin, faceUvSize));
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
                    EulerRotation cubeRotation = readEulerRotation(cubeJson, "rotation");
                    boolean cubeHasRotation = cubeRotation.pitch() != 0f
                        || cubeRotation.yaw() != 0f
                        || cubeRotation.roll() != 0f;
                    Vector3f cubePivot;
                    if (cubeJson.has("pivot")) {
                        cubePivot = readVector3f(cubeJson, "pivot", pivot);
                    } else if (cubeHasRotation) {
                        cubePivot = origin.add(size.multiply(0.5f));
                    } else {
                        cubePivot = pivot;
                    }

                    cubes.add(new EntityModelData.Cube(origin, size, uv, inflate, mirror, cubePivot, cubeRotation, faceUv));
                }

                bones.put(name, new EntityModelData.Bone(pivot, rotation, bindPoseRotation, cubes, boneParent));
            }

            return new EntityModelData(textureWidth, textureHeight, 0f, bones);
        }

        /** Classifies armor wearability from bone names alone. */
        private static @NotNull String inferArmorType(@NotNull Set<String> boneNames) {
            boolean hasHead = boneNames.contains("head");
            boolean hasBody = boneNames.contains("body");
            boolean hasArms = boneNames.contains("rightArm") || boneNames.contains("right_arm");
            boolean hasLegs = boneNames.contains("rightLeg") || boneNames.contains("right_leg");
            return (hasHead && hasBody && hasArms && hasLegs) ? "humanoid" : "none";
        }

        private static @NotNull Vector3f readVector3f(@NotNull JsonObject obj, @NotNull String key, @NotNull Vector3f fallback) {
            if (!obj.has(key)) return fallback;
            JsonElement el = obj.get(key);
            if (!el.isJsonArray()) return fallback;
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() < 3) return fallback;
            return new Vector3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
        }

        private static @NotNull Vector2f readVector2f(@NotNull JsonObject obj, @NotNull String key, @NotNull Vector2f fallback) {
            if (!obj.has(key)) return fallback;
            JsonElement el = obj.get(key);
            if (!el.isJsonArray()) return fallback;
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() < 2) return fallback;
            return new Vector2f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat());
        }

        private static @NotNull EulerRotation readEulerRotation(@NotNull JsonObject obj, @NotNull String key) {
            Vector3f v = readVector3f(obj, key, Vector3f.ZERO);
            return new EulerRotation(v.x(), v.y(), v.z());
        }

    }

}
