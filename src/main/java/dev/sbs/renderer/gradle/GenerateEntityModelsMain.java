package dev.sbs.renderer.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.sbs.renderer.pipeline.loader.EntityModelLoader;
import dev.sbs.renderer.pipeline.parser.EntityModelParser;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Entry point invoked by the {@code generateEntityModels} Gradle task.
 * <p>
 * Downloads the Bedrock Edition vanilla resource pack from GitHub, parses every
 * {@code .geo.json} entity model file via {@link EntityModelParser}, maps each to a Java
 * Edition entity id and default texture, and writes the result to
 * {@code src/main/resources/renderer/entity_models.json}. The runtime pipeline reads the JSON
 * via {@link EntityModelLoader}.
 * <p>
 * Bedrock entity models are geometrically identical to their Java Edition counterparts for all
 * vanilla entities - both editions share the same design specifications. The Bedrock format is
 * used as the source because it ships structured JSON while Java Edition hardcodes entity
 * geometry in compiled class files.
 */
@UtilityClass
public final class GenerateEntityModelsMain {

    private static final @NotNull String BEDROCK_PACK_URL =
        "https://github.com/ZtechNetwork/MCBVanillaResourcePack/archive/refs/heads/master.zip";
    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/renderer/entity_models.json");
    private static final @NotNull String MODELS_PREFIX = "models/entity/";

    /**
     * Geometry identifiers to skip - these are non-entity models, armor layers, or duplicates
     * that don't correspond to renderable entities.
     */
    private static final @NotNull Set<String> SKIP_IDENTIFIERS = Set.of(
        "geometry.player.armor.base", "geometry.player.armor1", "geometry.player.armor2",
        "geometry.player.armor.helmet", "geometry.player.armor.chestplate",
        "geometry.player.armor.leggings", "geometry.player.armor.boots",
        "geometry.item_sprite", "geometry.trident", "geometry.bow", "geometry.crossbow",
        "geometry.shield", "geometry.spyglass", "geometry.minecart",
        "geometry.leash_knot", "geometry.fishing_hook", "geometry.fireworks_rocket",
        "geometry.fireball", "geometry.experience_orb", "geometry.arrow",
        "geometry.ender_crystal", "geometry.wither_skull", "geometry.shulker_bullet",
        "geometry.tripod_camera"
    );

    /**
     * Maps Bedrock geometry identifier stems to Java Edition entity texture paths. Entries
     * not in this map fall back to the convention {@code minecraft:entity/{name}/{name}}.
     */
    private static final @NotNull ConcurrentMap<String, String> TEXTURE_OVERRIDES = buildTextureOverrides();

    public static void main(String @NotNull [] args) throws IOException {
        System.out.println("Downloading Bedrock vanilla resource pack...");
        HttpFetcher fetcher = new HttpFetcher();
        byte[] zipBytes = fetcher.get(BEDROCK_PACK_URL);
        System.out.println("Downloaded " + zipBytes.length + " bytes");

        ConcurrentMap<String, JsonObject> entities = Concurrent.newMap();
        int fileCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // Find .geo.json and .json files under models/entity/ inside the zip
                int modelsIdx = name.indexOf(MODELS_PREFIX);
                if (modelsIdx < 0) continue;
                String fileName = name.substring(modelsIdx + MODELS_PREFIX.length());
                if (fileName.contains("/") || fileName.isEmpty()) continue;
                if (!fileName.endsWith(".json")) continue;

                String json = new String(zis.readAllBytes());
                fileCount++;

                ConcurrentList<EntityModelParser.ParsedEntity> parsed = EntityModelParser.parse(json);
                for (EntityModelParser.ParsedEntity pe : parsed) {
                    String identifier = pe.identifier();
                    if (SKIP_IDENTIFIERS.contains(identifier)) continue;

                    String entityId = identifierToEntityId(identifier);
                    if (entityId == null) continue;
                    // Skip duplicates - prefer the first occurrence (non-versioned)
                    if (entities.containsKey(entityId)) continue;

                    String textureId = resolveTextureId(entityId, identifier);
                    JsonObject entityJson = serializeEntity(pe, textureId);
                    entities.put(entityId, entityJson);
                }
            }
        }

        System.out.println("Parsed " + fileCount + " .geo.json files, produced " + entities.size() + " entity definitions");

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH, buildJson(entities));
        System.out.println("Wrote " + OUTPUT_PATH.toAbsolutePath());
    }

    /**
     * Converts a Bedrock geometry identifier to a namespaced Java Edition entity id.
     * Returns null for identifiers that don't map to a known entity.
     */
    private static String identifierToEntityId(@NotNull String identifier) {
        // Strip "geometry." prefix
        String stem = identifier.startsWith("geometry.") ? identifier.substring(9) : identifier;
        // Strip version suffixes like ".v1.8", ".v1.0"
        stem = stem.replaceAll("\\.v\\d+(\\.\\d+)?$", "");
        // Skip armor variants, baby variants, and other non-base models
        if (stem.contains("armor") || stem.contains("baby") || stem.contains("_v1")
            || stem.contains("_v2") || stem.contains("_v3") || stem.contains("saddle"))
            return null;
        // Normalize separators
        stem = stem.replace(".", "_");
        return "minecraft:" + stem;
    }

    /**
     * Resolves the default Java Edition texture path for an entity.
     */
    private static @NotNull String resolveTextureId(@NotNull String entityId, @NotNull String identifier) {
        String name = entityId.substring("minecraft:".length());
        String override = TEXTURE_OVERRIDES.get(name);
        if (override != null) return override;
        return "minecraft:entity/" + name + "/" + name;
    }

    private static @NotNull JsonObject serializeEntity(
        @NotNull EntityModelParser.ParsedEntity pe,
        @NotNull String textureId
    ) {
        JsonObject entityJson = GsonSettings.defaults().create().toJsonTree(pe.model()).getAsJsonObject();
        entityJson.addProperty("texture_id", textureId);
        entityJson.addProperty("armor_type", pe.armorType());
        return entityJson;
    }

    private static @NotNull String buildJson(@NotNull ConcurrentMap<String, JsonObject> entities) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by GenerateEntityModelsMain from Bedrock Edition vanilla resource pack. Run the \\'generateEntityModels\\' Gradle task to refresh.");

        JsonObject entitiesObj = new JsonObject();
        entities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> entitiesObj.add(entry.getKey(), entry.getValue()));
        root.add("entities", entitiesObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    private static @NotNull ConcurrentMap<String, String> buildTextureOverrides() {
        ConcurrentMap<String, String> map = Concurrent.newMap();
        map.put("zombie", "minecraft:entity/zombie/zombie");
        map.put("skeleton", "minecraft:entity/skeleton/skeleton");
        map.put("creeper", "minecraft:entity/creeper/creeper");
        map.put("spider", "minecraft:entity/spider/spider");
        map.put("enderman", "minecraft:entity/enderman/enderman");
        map.put("zombie_pigman", "minecraft:entity/piglin/zombified_piglin");
        map.put("zombie_villager", "minecraft:entity/zombie_villager/zombie_villager");
        map.put("wither_skeleton", "minecraft:entity/skeleton/wither_skeleton");
        map.put("stray", "minecraft:entity/skeleton/stray");
        map.put("husk", "minecraft:entity/zombie/husk");
        map.put("drowned", "minecraft:entity/zombie/drowned");
        map.put("snow_golem", "minecraft:entity/snow_golem/snow_golem");
        map.put("iron_golem", "minecraft:entity/iron_golem/iron_golem");
        map.put("wolf", "minecraft:entity/wolf/wolf");
        map.put("cat", "minecraft:entity/cat/siamese");
        map.put("ocelot", "minecraft:entity/cat/ocelot");
        map.put("horse", "minecraft:entity/horse/horse_brown");
        map.put("pig", "minecraft:entity/pig/pig");
        map.put("cow", "minecraft:entity/cow/cow");
        map.put("sheep", "minecraft:entity/sheep/sheep");
        map.put("chicken", "minecraft:entity/chicken/chicken");
        map.put("squid", "minecraft:entity/squid/squid");
        map.put("bat", "minecraft:entity/bat/bat");
        map.put("villager", "minecraft:entity/villager/villager");
        map.put("witch", "minecraft:entity/witch/witch");
        map.put("ghast", "minecraft:entity/ghast/ghast");
        map.put("blaze", "minecraft:entity/blaze/blaze");
        map.put("slime", "minecraft:entity/slime/slime");
        map.put("magma_cube", "minecraft:entity/slime/magmacube");
        map.put("silverfish", "minecraft:entity/silverfish/silverfish");
        map.put("endermite", "minecraft:entity/endermite/endermite");
        map.put("guardian", "minecraft:entity/guardian/guardian");
        map.put("shulker", "minecraft:entity/shulker/shulker");
        map.put("phantom", "minecraft:entity/phantom/phantom");
        map.put("ravager", "minecraft:entity/illager/ravager");
        map.put("pillager", "minecraft:entity/illager/pillager");
        map.put("vindicator", "minecraft:entity/illager/vindicator");
        map.put("evoker", "minecraft:entity/illager/evoker");
        map.put("vex", "minecraft:entity/vex/vex");
        map.put("dolphin", "minecraft:entity/dolphin/dolphin");
        map.put("turtle", "minecraft:entity/turtle/big_sea_turtle");
        map.put("panda", "minecraft:entity/panda/panda");
        map.put("fox", "minecraft:entity/fox/fox");
        map.put("bee", "minecraft:entity/bee/bee");
        map.put("hoglin", "minecraft:entity/hoglin/hoglin");
        map.put("piglin", "minecraft:entity/piglin/piglin");
        map.put("strider", "minecraft:entity/strider/strider");
        map.put("axolotl", "minecraft:entity/axolotl/axolotl_lucy");
        map.put("glow_squid", "minecraft:entity/squid/glow_squid");
        map.put("goat", "minecraft:entity/goat/goat");
        map.put("frog", "minecraft:entity/frog/temperate_frog");
        map.put("tadpole", "minecraft:entity/tadpole/tadpole");
        map.put("allay", "minecraft:entity/allay/allay");
        map.put("warden", "minecraft:entity/warden/warden");
        map.put("camel", "minecraft:entity/camel/camel");
        map.put("sniffer", "minecraft:entity/sniffer/sniffer");
        map.put("armadillo", "minecraft:entity/armadillo/armadillo");
        map.put("breeze", "minecraft:entity/breeze/breeze");
        map.put("bogged", "minecraft:entity/skeleton/bogged");
        map.put("creaking", "minecraft:entity/creaking/creaking");
        map.put("armor_stand", "minecraft:entity/armorstand/armorstand");
        map.put("rabbit", "minecraft:entity/rabbit/brown");
        map.put("polar_bear", "minecraft:entity/bear/polarbear");
        map.put("llama", "minecraft:entity/llama/llama");
        map.put("parrot", "minecraft:entity/parrot/parrot_red_blue");
        map.put("cod", "minecraft:entity/fish/cod");
        map.put("salmon", "minecraft:entity/fish/salmon");
        map.put("pufferfish", "minecraft:entity/fish/pufferfish");
        map.put("tropical_fish", "minecraft:entity/fish/tropical");
        map.put("ender_dragon", "minecraft:entity/dragon/dragon");
        map.put("wither_boss", "minecraft:entity/wither_boss/wither");
        return map;
    }

}
