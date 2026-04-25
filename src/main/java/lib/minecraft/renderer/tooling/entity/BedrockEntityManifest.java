package lib.minecraft.renderer.tooling.entity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses Bedrock Edition's {@code entity/*.entity.json} client-entity definitions into a pair of
 * lookup tables the entity-models pipeline consumes.
 *
 * <p>Bedrock ships one {@code .entity.json} per canonical entity. The file binds a stable
 * identifier (which usually - but not always - matches the Java {@code EntityType} registry id)
 * to geometry and texture references:
 *
 * <pre>
 * // entity/wither.entity.json
 * {
 *   "minecraft:client_entity": {
 *     "description": {
 *       "identifier": "minecraft:wither",
 *       "geometry": { "default": "geometry.witherBoss", ... },
 *       "textures": { "default": "textures/entity/wither_boss/wither", ... }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>The manifest surfaces two views of that data:
 *
 * <ul>
 *   <li>{@link Manifest#byIdentifier()} - per-entity metadata keyed by the Bedrock identifier
 *       (e.g. {@code "wither"}, {@code "iron_golem"}, {@code "zombie_pigman"}).</li>
 *   <li>{@link Manifest#fanOutByGeometry()} - reverse index keyed by the Bedrock geometry id
 *       (e.g. {@code "geometry.witherBoss"} -&gt; {@code ["wither"]};
 *       {@code "geometry.horse"} -&gt; {@code ["donkey", "horse", "mule", "skeleton_horse",
 *       "zombie_horse"]}). Bedrock reuses geometry across related entities; the reverse index
 *       feeds the fan-out in {@code ToolingEntityModels} so every entity sharing a geometry
 *       ends up with its own entry.</li>
 * </ul>
 *
 * <p>Only the {@code "default"} geometry and texture keys are read. Bedrock may ship extras
 * ({@code "armor"}, {@code "baby"}, {@code "cracked_high"}, etc.) but the asset-renderer's
 * atlas path is strictly base-geometry-with-base-texture.
 */
@UtilityClass
public final class BedrockEntityManifest {

    /** In-zip prefix for client-entity definition files. Complements {@code resource_pack/models/entity/}. */
    private static final @NotNull String ENTITY_PREFIX = "resource_pack/entity/";

    /** File-name suffix for Bedrock client-entity definitions. */
    private static final @NotNull String ENTITY_SUFFIX = ".entity.json";

    private static final @NotNull String CLIENT_ENTITY_KEY = "minecraft:client_entity";
    private static final @NotNull String DESCRIPTION_KEY = "description";
    private static final @NotNull String IDENTIFIER_KEY = "identifier";
    private static final @NotNull String GEOMETRY_KEY = "geometry";
    private static final @NotNull String TEXTURES_KEY = "textures";
    private static final @NotNull String DEFAULT_KEY = "default";

    /** Prefix stripped from identifiers so {@code "minecraft:wither"} becomes {@code "wither"}. */
    private static final @NotNull String MINECRAFT_NAMESPACE = "minecraft:";

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Per-entity metadata extracted from a single {@code entity/<name>.entity.json} file.
     *
     * @param identifier the Bedrock-canonical entity id (namespace stripped), e.g. {@code "wither"}
     * @param geometryId the referenced default geometry id, e.g. {@code "geometry.witherBoss"}
     * @param textureRef the Bedrock texture sub-path under {@code textures/entity/}, with the
     *     {@code textures/entity/} prefix stripped (e.g. {@code "wither_boss/wither"}). The
     *     tooling locates the PNG in-zip at {@code resource_pack/textures/entity/<textureRef>.png}
     *     and bundles it under {@code /lib/minecraft/renderer/entity_textures/<textureRef>.png}.
     *     {@code null} when the client_entity.json has no default texture binding.
     */
    public record Entry(
        @NotNull String identifier,
        @NotNull String geometryId,
        @Nullable String textureRef
    ) {}

    /**
     * The two lookup tables produced by {@link #parse}.
     *
     * @param byIdentifier entity metadata keyed by Bedrock-canonical identifier (namespace-free)
     * @param fanOutByGeometry the identifiers that reference each geometry, insertion-ordered
     *     so fan-out emission is deterministic across regenerations
     */
    public record Manifest(
        @NotNull Map<String, Entry> byIdentifier,
        @NotNull Map<String, List<String>> fanOutByGeometry
    ) {}

    /**
     * Walks every {@code entity/<name>.entity.json} file inside the Bedrock resource-pack zip
     * stream and returns the assembled manifest. Files without a usable identifier or geometry
     * are silently skipped; malformed JSON records a WARN on {@code diagnostics}.
     *
     * @param packZipBytes the in-memory Bedrock resource pack archive
     * @param diagnostics the diagnostic sink
     * @return the parsed manifest
     */
    public static @NotNull Manifest parse(
        byte @NotNull [] packZipBytes,
        @NotNull Diagnostics diagnostics
    ) {
        Map<String, Entry> byIdentifier = new LinkedHashMap<>();
        Map<String, List<String>> fanOut = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(packZipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                String name = zipEntry.getName();
                int idx = name.indexOf(ENTITY_PREFIX);
                if (idx < 0) continue;
                String suffix = name.substring(idx + ENTITY_PREFIX.length());
                if (suffix.contains("/") || !suffix.endsWith(ENTITY_SUFFIX)) continue;

                String json = new String(zis.readAllBytes());
                Entry entry;
                try {
                    entry = parseEntry(json);
                } catch (Exception ex) {
                    diagnostics.warn("Failed to parse %s: %s", name, ex.getMessage());
                    continue;
                }
                if (entry == null) continue;

                // First occurrence wins - later .entity.json files with the same id get skipped.
                // Matches the existing dedup convention from the geometry walk.
                if (byIdentifier.containsKey(entry.identifier())) continue;

                byIdentifier.put(entry.identifier(), entry);
                fanOut.computeIfAbsent(entry.geometryId(), k -> new ArrayList<>())
                    .add(entry.identifier());
            }
        } catch (Exception ex) {
            diagnostics.error("Failed to read Bedrock pack zip stream: %s", ex.getMessage());
        }

        return new Manifest(byIdentifier, fanOut);
    }

    /**
     * Parses one {@code .entity.json} into an {@link Entry}. Returns {@code null} when the file
     * has no identifier or no default geometry reference.
     */
    private static @Nullable Entry parseEntry(@NotNull String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root == null || !root.has(CLIENT_ENTITY_KEY)) return null;

        JsonObject clientEntity = root.getAsJsonObject(CLIENT_ENTITY_KEY);
        if (!clientEntity.has(DESCRIPTION_KEY)) return null;

        JsonObject description = clientEntity.getAsJsonObject(DESCRIPTION_KEY);
        if (!description.has(IDENTIFIER_KEY) || !description.has(GEOMETRY_KEY)) return null;

        String rawIdentifier = description.get(IDENTIFIER_KEY).getAsString();
        if (!rawIdentifier.startsWith(MINECRAFT_NAMESPACE)) return null;
        String identifier = rawIdentifier.substring(MINECRAFT_NAMESPACE.length());

        JsonObject geometryBlock = description.getAsJsonObject(GEOMETRY_KEY);
        if (!geometryBlock.has(DEFAULT_KEY)) return null;
        String geometryId = geometryBlock.get(DEFAULT_KEY).getAsString();

        // Texture resolution precedence:
        //   1. `textures.default` when present (most entities).
        //   2. `textures.<identifier>` keyed by the entity's own stripped id - donkey.entity.json
        //      declares `{ "donkey": "...", "baby_donkey": "..." }` with no `default`, and the
        //      primary variant key is always the identifier.
        //   3. The first key in insertion order - axolotl/cat/frog/parrot/villager all ship with
        //      variant-only texture maps whose first key is a reasonable "primary" variant.
        // A null return means the entity truly declares no textures.
        String textureRef = null;
        if (description.has(TEXTURES_KEY)) {
            JsonObject textures = description.getAsJsonObject(TEXTURES_KEY);
            String rawPath = null;
            if (textures.has(DEFAULT_KEY)) {
                rawPath = textures.get(DEFAULT_KEY).getAsString();
            } else if (textures.has(identifier)) {
                rawPath = textures.get(identifier).getAsString();
            } else {
                for (Map.Entry<String, JsonElement> e : textures.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        rawPath = e.getValue().getAsString();
                        break;
                    }
                }
            }
            if (rawPath != null) textureRef = stripTextureEntityPrefix(rawPath);
        }

        return new Entry(identifier, geometryId, textureRef);
    }

    /**
     * Strips the {@code "textures/entity/"} prefix from a Bedrock texture path so the result is
     * a stable relative sub-path (e.g. {@code "wither_boss/wither"}) usable as both the in-zip
     * PNG lookup suffix and the bundled classpath-resource key under
     * {@code /lib/minecraft/renderer/entity_textures/}. Paths not under {@code textures/entity/}
     * are returned with only the {@code textures/} prefix stripped so classpath layout falls back
     * gracefully on pack quirks (e.g. {@code map/map}).
     */
    private static @NotNull String stripTextureEntityPrefix(@NotNull String rawPath) {
        String stripped = rawPath;
        if (stripped.startsWith("textures/")) stripped = stripped.substring("textures/".length());
        if (stripped.startsWith("entity/")) stripped = stripped.substring("entity/".length());
        return stripped;
    }

}
