package lib.minecraft.renderer.content.pack;

import com.google.gson.JsonObject;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.store.ParityJson;
import lib.minecraft.renderer.store.Pins;
import lib.minecraft.renderer.store.SelfCapture;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * End-to-end acquisition test that downloads the client jar for the Minecraft version
 * {@link ClientAssetsExtension} acquires, reads its vanilla pack into memory and asserts the asset
 * tree is there, and holds the stack-resolved colormaps to the digests pinned in the parity store. The
 * opt-in extraction is held to the in-memory read of the same jar: a small stand-in client jar, cached
 * under a temporary root, extracted to disk writes its vanilla pack and nothing beside it.
 * <p>
 * This test is tagged {@code slow} and is skipped by the default {@code test} task. Run it
 * explicitly with {@code ./gradlew slowTest}. The first run downloads ~25MB
 * from {@code piston-data.mojang.com}; subsequent runs reuse the cached copy.
 * <p>
 * The cache root is deliberately stable (not a temporary directory) so the cached client jar survives
 * across sessions - offline vanilla-source lookups depend on having it on disk after the test runs.
 * {@code .gitignore} already excludes {@code asset-renderer/cache/}, so nothing leaks into commits.
 * <p>
 * Neither the version nor the cache root is written down here: both are
 * {@link ClientAssetsExtension}'s, which is what makes the acquisition shared with every other test
 * that needs it and a version bump a one-line edit.
 * <p>
 * It reaches {@link ClientAssetsExtension#assets()} directly rather than installing the extension,
 * because installing it ABANDONS a class where nothing has cached the client - which is what keeps
 * every other caller out of the network, and is the one thing this test exists to do. So the cold
 * path runs here or nowhere.
 */
@Tag("slow")
@DisplayName("ClientAcquisition end-to-end integration")
class ClientAcquisitionIntegrationTest {

    /** The digest-set the colormap byte-parity assertion both writes and reads. */
    private static final String COLORMAP_ARTIFACT = "digest.colormap-lut";

    /** The canonical form those digests are taken over: the raw big-endian ARGB buffer. */
    private static final String COLORMAP_FORM = "raw-argb-bytes";

    /** The stack-resolved biome colormaps, probed by the colormap byte-parity assertion. */
    private static ConcurrentMap<TintSource, ColorMap> colorMaps;

    /** The vanilla pack read into memory off the cached client jar, probed by the asset-tree assertions. */
    private static PackContainer vanilla;

    /**
     * Acquires the extension's client assets once for the class and publishes the shared
     * {@link #vanilla} pack and the {@link #colorMaps} the pack stack over it resolves.
     */
    @BeforeAll
    static void downloadAndRead() {
        ClientAssets result = ClientAssetsExtension.assets();
        vanilla = result.vanilla();
        colorMaps = ColorMapLoader.load(PackAcquisition.acquire(result));
    }

    @Test
    @DisplayName("reads the client jar's asset tree into memory")
    void readsClientJarAssets() {
        for (String directory : List.of("assets/minecraft", "assets/minecraft/models/block",
            "assets/minecraft/models/item", "assets/minecraft/textures/block", "data/minecraft"))
            assertThat(directory + " carries entries", vanilla.entries(directory).findAny().isPresent(), is(true));
        assertThat("the synthesised pack.mcmeta", vanilla.exists("pack.mcmeta"), is(true));
    }

    @Test
    @DisplayName("extracting a cached jar writes its vanilla pack, and nothing beside it, under the pack root")
    void extractsWhenAsked(@TempDir Path cache) throws IOException {
        ClientOptions options = ClientOptions.builder().cacheRoot(cache.toFile()).extractAssets(true).build();
        Path jar = options.vanillaRoot().resolve("client.jar");
        writeClientJar(jar);

        ClientAssets extracted = ClientAcquisition.acquire(options);

        assertThat(extracted.vanilla(), instanceOf(PackContainer.Directory.class));
        Path packRoot = ((PackContainer.Directory) extracted.vanilla()).root();
        assertThat(packRoot, equalTo(options.vanillaPackRoot()));
        assertThat("pack root exists", Files.isDirectory(packRoot), is(true));
        assertThat("assets/minecraft/textures/block exists",
            Files.isDirectory(packRoot.resolve("assets/minecraft/textures/block")), is(true));
        assertThat("the extraction holds what the in-memory read holds",
            extracted.vanilla().entries("").sorted().toList(), equalTo(ClientAcquisition.readVanillaPack(jar).entries("").toList()));
        assertThat("the jar sits beside the pack, not in it", extracted.vanilla().exists("client.jar"), is(false));
    }

    @Test
    @DisplayName("stack-resolved colormaps byte-match the digests pinned in the parity store")
    void colormapsByteMatchBundledLut() {
        // The byte-parity probe: sha256 of the raw big-endian ARGB bytes of each bundled colormap.
        // The re-point resolves vanilla's own extracted PNG through the stack and must decode to
        // the identical bytes. The form is `raw-argb-bytes`, which - unlike the shipped tables'
        // `table-canonical` - encodes no library's formatting, so no dependency version rides with
        // it.
        Map<String, JsonObject> entries = new TreeMap<>();
        Map<String, String> observed = new TreeMap<>();
        for (TintSource type : List.of(TintSource.GRASS, TintSource.FOLIAGE,
            TintSource.DRY_FOLIAGE)) {
            String digest = ParityJson.sha256(colorMaps.get(type).pixels());
            observed.put(type.name(), digest);
            JsonObject entry = new JsonObject();
            entry.addProperty("form", COLORMAP_FORM);
            entry.addProperty("sha256", digest);
            entries.put(type.name(), entry);
        }
        SelfCapture.write(COLORMAP_ARTIFACT, entries);

        SelfCapture.requireBaseline(COLORMAP_ARTIFACT);

        assertThat("the pinned LUT set and the LUTs actually resolved must be the same names",
            Pins.keys(COLORMAP_ARTIFACT), equalTo(List.copyOf(observed.keySet())));
        observed.forEach((name, digest) -> assertThat(name + " colormap LUT bytes; if intentional, "
                + "re-baseline it: " + Pins.rebaselineCommand(COLORMAP_ARTIFACT),
            digest, equalTo(Pins.digest(COLORMAP_ARTIFACT, name))));
    }

    /**
     * Writes a stand-in client jar at {@code jar} - the vanilla trees, the {@code version.json} the
     * pack's {@code pack.mcmeta} is synthesised from, and the second-namespace, class and manifest
     * entries a client jar carries beside the pack.
     *
     * @param jar where the jar is cached
     * @throws IOException if the jar cannot be written
     */
    private static void writeClientJar(@NotNull Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write("{\"name\":\"stand-in\",\"pack_version\":{\"resource_major\":84,\"resource_minor\":0}}"
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            for (String name : List.of("assets/minecraft/textures/block/stone.png", "assets/minecraft/models/block/stone.json",
                "data/minecraft/tags/block/logs.json", "assets/realms/lang/en_us.json", "net/minecraft/client/main/Main.class",
                "META-INF/MANIFEST.MF")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

}
