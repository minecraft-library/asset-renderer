package lib.minecraft.renderer.tooling.entity;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * One-shot generator that writes the {@code living_mobs.json} baseline from the real
 * {@link MobRegistryDiscovery} output. Not run by the parity-sweep task - invoke explicitly on
 * a Minecraft version bump with
 * {@code ./gradlew :asset-renderer:slowTest --tests '*MobRegistryDiscoveryBaselineGen'}.
 */
@DisplayName("MobRegistryDiscovery baseline regenerator")
@Tag("slow")
class MobRegistryDiscoveryBaselineGen {

    private static final Path JAR = Path.of("cache/asset-renderer/vanilla/26.1/client.jar");
    private static final Path BASELINE = Path.of("src/test/resources/lib/minecraft/renderer/baseline/living_mobs.json");

    @Test
    @DisplayName("regenerate living_mobs.json from the real 26.1 jar")
    void regenerate() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            ConcurrentList<MobRegistryDiscovery.MobEntry> mobs =
                MobRegistryDiscovery.discover(zip, new Diagnostics());

            JsonObject root = new JsonObject();
            root.addProperty("//", "Captured from MobRegistryDiscovery on the 26.1 client jar. Regenerate with MobRegistryDiscoveryBaselineGen -Dbaseline.regen=true.");
            JsonArray arr = new JsonArray();
            mobs.stream()
                .sorted((a, b) -> a.entityId().compareTo(b.entityId()))
                .forEach(entry -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("entityId", entry.entityId());
                    e.addProperty("fieldName", entry.fieldName());
                    e.addProperty("entityClassInternalName", entry.entityClassInternalName());
                    e.addProperty("mobCategory", entry.mobCategory());
                    arr.add(e);
                });
            root.add("mobs", arr);

            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
            Files.writeString(BASELINE, json);
            System.out.println("Wrote " + mobs.size() + " living mobs to " + BASELINE.toAbsolutePath());
        }
    }

}
