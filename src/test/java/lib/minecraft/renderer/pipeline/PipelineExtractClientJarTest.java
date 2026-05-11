package lib.minecraft.renderer.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.pipeline.pack.PackMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link Pipeline#extractClientJar(Path, Path)} - in particular the
 * {@code pack.mcmeta} synthesis fallback that kicks in when the source jar does not ship
 * a root mcmeta (the modern Mojang client-jar shape, verified across 1.21.4 and 26.1
 * during the 999.8 backlog investigation).
 * <p>
 * These tests construct synthetic ZIPs in-process - no Minecraft assets are touched and
 * the {@code @Tag("slow")} integration path is untouched.
 */
@DisplayName("Pipeline.extractClientJar pack.mcmeta synthesis")
class PipelineExtractClientJarTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("Synthesises pack.mcmeta when jar ships only version.json + assets")
    void synthesisesFromVersionJsonWhenRootMcmetaMissing(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("id", "test-version");
                o.addProperty("name", "Test Pack");
                JsonObject pv = new JsonObject();
                pv.addProperty("resource_major", 84);
                pv.addProperty("resource_minor", 0);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        Pipeline.extractClientJar(jarPath, packRoot);

        Path mcmeta = packRoot.resolve("pack.mcmeta");
        assertThat(Files.isRegularFile(mcmeta), is(true));
        PackMeta parsed = PackMeta.parse(mcmeta, "vanilla");
        assertThat(parsed.packFormat(), is(84));
        assertThat(parsed.description(), containsString("Test Pack"));
        assertThat(Files.isRegularFile(packRoot.resolve("assets/minecraft/textures/block/stone.png")), is(true));
    }

    @Test
    @DisplayName("Preserves real pack.mcmeta when jar ships one (no synthesis)")
    void preservesRealMcmetaWhenJarShipsOne(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");
        String realMcmeta = json(o -> {
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", 42);
            pack.addProperty("description", "Original mcmeta from jar");
            o.add("pack", pack);
        });

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write(realMcmeta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // Include a version.json that would synthesise a DIFFERENT format - the real
            // mcmeta must take precedence over the synthetic fallback.
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("name", "Should Not Win");
                JsonObject pv = new JsonObject();
                pv.addProperty("resource_major", 99);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        });

        Pipeline.extractClientJar(jarPath, packRoot);

        PackMeta parsed = PackMeta.parse(packRoot.resolve("pack.mcmeta"), "vanilla");
        assertThat(parsed.packFormat(), is(42));
        assertThat(parsed.description(), is("Original mcmeta from jar"));
    }

    @Test
    @DisplayName("Skips synthesis when version.json is missing (preserves original failure mode)")
    void skipsSynthesisWhenVersionJsonAbsent(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        Pipeline.extractClientJar(jarPath, packRoot);

        assertThat(Files.exists(packRoot.resolve("pack.mcmeta")), is(false));
    }

    @Test
    @DisplayName("Skips synthesis when version.json lacks pack_version.resource_major")
    void skipsSynthesisWhenResourceMajorAbsent(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("id", "no-pack-version");
                o.addProperty("name", "Has Name But No pack_version");
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        Pipeline.extractClientJar(jarPath, packRoot);

        assertThat(Files.exists(packRoot.resolve("pack.mcmeta")), is(false));
    }

    @Test
    @DisplayName("Synthesis tolerates malformed version.json - skips silently")
    void skipsSynthesisWhenVersionJsonMalformed(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write("{ not valid json".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        Pipeline.extractClientJar(jarPath, packRoot);

        assertThat(Files.exists(packRoot.resolve("pack.mcmeta")), is(false));
    }

    @FunctionalInterface
    private interface ZipBuilder {
        void build(ZipOutputStream zip) throws IOException;
    }

    private static void writeZip(@org.jetbrains.annotations.NotNull Path jarPath, ZipBuilder builder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            builder.build(zip);
        }
        Files.write(jarPath, bytes.toByteArray());
    }

    private static String json(JsonBuilder builder) {
        JsonObject obj = new JsonObject();
        builder.build(obj);
        return GSON.toJson(obj);
    }

    @FunctionalInterface
    private interface JsonBuilder {
        void build(JsonObject obj);
    }
}
