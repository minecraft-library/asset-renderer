package lib.minecraft.renderer.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.FormatRange;
import lib.minecraft.renderer.asset.pack.MCMeta;
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
 * Unit coverage for {@link ClientAcquisition#extractClientJar(Path, Path)} - in particular the
 * {@code pack.mcmeta} synthesis fallback that kicks in when the source jar does not ship
 * a root mcmeta (the modern Mojang client-jar shape, verified across 1.21.4 and 26.1
 * during the 999.8 backlog investigation).
 * <p>
 * The synthesis reads {@code version.json} (captured in memory during ZIP iteration, never
 * written to disk) and derives the pack format from its {@code pack_version} object. Format
 * resolution prefers the modern {@code resource_major} key, falls back to the legacy flat
 * {@code resource} key (paired with {@code data} in 1.21.4-era jars), and bails silently when
 * neither key is present, {@code version.json} is absent, or its JSON is malformed. Synthesis
 * only runs when the jar shipped no root {@code pack.mcmeta} - a real mcmeta always wins.
 * <p>
 * These tests construct synthetic ZIPs in-process - no Minecraft assets are touched and
 * the {@code @Tag("slow")} integration path is untouched.
 */
@DisplayName("ClientAcquisition.extractClientJar pack.mcmeta synthesis")
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

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        Path mcmeta = packRoot.resolve("pack.mcmeta");
        assertThat(Files.isRegularFile(mcmeta), is(true));
        MCMeta parsed = MCMeta.parse(Files.readString(mcmeta), new ResourceId("vanilla", "pack"));
        assertThat(parsed.pack().orElseThrow().formats().min().major(), is(84));
        assertThat(parsed.pack().orElseThrow().formats().min().minor(), is(0));
        assertThat(parsed.pack().orElseThrow().description().plain(), containsString("Test Pack"));
        assertThat(Files.isRegularFile(packRoot.resolve("assets/minecraft/textures/block/stone.png")), is(true));

        // version.json is captured in memory for synthesis - it must NOT be extracted to disk.
        assertThat(Files.exists(packRoot.resolve("version.json")), is(false));
    }

    @Test
    @DisplayName("Captures resource_minor into min_format; max_format widens to the major's minorRange ceiling")
    void capturesResourceMinorIntoFormatRange(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("name", "future");
                JsonObject pv = new JsonObject();
                pv.addProperty("resource_major", 84);
                pv.addProperty("resource_minor", 3);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        // The resolved renderer target is formats().min(); it must carry the full major.minor (matching
        // vanilla's getPackVersion), while max widens to the major's highest minor - vanilla's builtin
        // pack range PackFormat.minorRange = [(major, minor), (major, MAX)]. A bare pack_format int would
        // have floored the minor to 0.
        FormatRange formats = MCMeta.parse(Files.readString(packRoot.resolve("pack.mcmeta")), new ResourceId("vanilla", "pack"))
            .pack().orElseThrow().formats();
        assertThat(formats.min().major(), is(84));
        assertThat(formats.min().minor(), is(3));
        assertThat(formats.max().major(), is(84));
        assertThat(formats.max().minor(), is(FormatRange.FormatVersion.MAX_MINOR));
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

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        MCMeta parsed = MCMeta.parse(Files.readString(packRoot.resolve("pack.mcmeta")), new ResourceId("vanilla", "pack"));
        assertThat(parsed.pack().orElseThrow().formats().min().major(), is(42));
        assertThat(parsed.pack().orElseThrow().description().plain(), is("Original mcmeta from jar"));
    }

    @Test
    @DisplayName("Synthesises pack.mcmeta from legacy {resource, data} schema (1.21.4-era jars)")
    void synthesisesFromLegacyResourceFieldShape(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("id", "1.21.4");
                o.addProperty("name", "1.21.4");
                JsonObject pv = new JsonObject();
                // Legacy flat shape - verified on the real 1.21.4 client jar shipped by Mojang.
                pv.addProperty("resource", 46);
                pv.addProperty("data", 61);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        MCMeta parsed = MCMeta.parse(Files.readString(packRoot.resolve("pack.mcmeta")), new ResourceId("vanilla", "pack"));
        assertThat(parsed.pack().orElseThrow().formats().min().major(), is(46));
        assertThat(parsed.pack().orElseThrow().description().plain(), containsString("1.21.4"));
    }

    @Test
    @DisplayName("Prefers resource_major over resource when both shapes are present")
    void prefersResourceMajorWhenBothShapesPresent(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                JsonObject pv = new JsonObject();
                pv.addProperty("resource_major", 84);
                pv.addProperty("resource", 46);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        MCMeta parsed = MCMeta.parse(Files.readString(packRoot.resolve("pack.mcmeta")), new ResourceId("vanilla", "pack"));
        assertThat(parsed.pack().orElseThrow().formats().min().major(), is(84));
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

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        assertThat(Files.exists(packRoot.resolve("pack.mcmeta")), is(false));
    }

    @Test
    @DisplayName("Skips synthesis when version.json has neither pack_version.resource_major nor pack_version.resource")
    void skipsSynthesisWhenBothFormatKeysAbsent(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("client.jar");
        Path packRoot = tempDir.resolve("pack");

        writeZip(jarPath, zip -> {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(json(o -> {
                o.addProperty("id", "neither-key");
                o.addProperty("name", "Has Name But No format keys");
                JsonObject pv = new JsonObject();
                // Neither resource_major nor resource - synthesis must bail.
                pv.addProperty("data", 61);
                o.add("pack_version", pv);
            }).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/block/stone.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        });

        ClientAcquisition.extractClientJar(jarPath, packRoot);

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

        ClientAcquisition.extractClientJar(jarPath, packRoot);

        assertThat(Files.exists(packRoot.resolve("pack.mcmeta")), is(false));
    }

    /** Callback that populates the entries of the synthetic client jar under construction. */
    @FunctionalInterface
    private interface ZipBuilder {
        void build(ZipOutputStream zip) throws IOException;
    }

    /**
     * Builds an in-memory ZIP from {@code builder} and writes it to {@code jarPath}, standing in for
     * a real client jar without touching any Minecraft assets.
     *
     * @param jarPath destination path for the synthetic jar
     * @param builder callback that adds the jar entries
     * @throws IOException if writing the jar bytes fails
     */
    private static void writeZip(@org.jetbrains.annotations.NotNull Path jarPath, ZipBuilder builder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            builder.build(zip);
        }
        Files.write(jarPath, bytes.toByteArray());
    }

    /**
     * Serialises a {@link JsonObject} populated by {@code builder} to a compact JSON string for use
     * as a jar entry body (a {@code version.json} or {@code pack.mcmeta} fixture).
     *
     * @param builder callback that populates the object
     * @return the serialised JSON
     */
    private static String json(JsonBuilder builder) {
        JsonObject obj = new JsonObject();
        builder.build(obj);
        return GSON.toJson(obj);
    }

    /** Callback that populates the {@link JsonObject} backing a fixture JSON document. */
    @FunctionalInterface
    private interface JsonBuilder {
        void build(JsonObject obj);
    }
}
