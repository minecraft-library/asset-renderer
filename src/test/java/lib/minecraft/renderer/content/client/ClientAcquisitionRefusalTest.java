package lib.minecraft.renderer.content.client;

import lib.minecraft.renderer.exception.ClientException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the acquisition refuses rather than acts on: a client jar entry whose name does not stay under
 * the pack root, a version that is not a version id, and a download that is not the size and SHA-1
 * declared for it.
 * <p>
 * Every jar here is a small zip written under a temporary directory, every download a byte array, and
 * every call either reads a file already on disk, resolves a path or verifies a stream it is handed,
 * so nothing in the class opens a socket and it runs in the fast suite. The two members that can
 * download are held by {@code ClientAcquisitionIntegrationTest}.
 */
@DisplayName("ClientAcquisition keeps entries under the pack root, a version under the cache root and a download to its hash")
class ClientAcquisitionRefusalTest {

    /** An ordinary entry every stand-in jar carries, so a refusal is not an empty jar's doing. */
    private static final @NotNull String STONE = "assets/minecraft/textures/block/stone.png";

    /**
     * Entry names under a vanilla tree that carry a parent segment - one leaving the pack root from
     * each tree, one that stays inside it, and one spelled with the other separator.
     */
    private static final @NotNull List<String> PARENT_SEGMENT_NAMES = List.of(
        "assets/minecraft/../../../stray.txt",
        "data/minecraft/../../../stray.txt",
        "assets/minecraft/textures/../models/block/stone.json",
        "assets/minecraft/..\\..\\..\\stray.txt");

    @Test
    @DisplayName("extraction refuses an entry name with a parent segment and writes nothing outside the pack root")
    void extractionKeepsEveryEntryUnderThePackRoot(@TempDir Path tempDir) throws IOException {
        for (String name : PARENT_SEGMENT_NAMES) {
            Path work = Files.createTempDirectory(tempDir, "extract");
            Path jar = writeJar(work.resolve("client.jar"), name);
            Path packRoot = work.resolve("vanilla").resolve("pack");

            ClientException refused = assertThrows(ClientException.class,
                () -> ClientAcquisition.extractClientJar(jar, packRoot), name);

            assertThat("the refusal names the entry", refused.getMessage(), containsString(name));
            assertThat(name + " wrote nothing outside the pack root", strays(work, packRoot), is(empty()));
        }
    }

    @Test
    @DisplayName("the in-memory read refuses the same entry names the extraction does")
    void inMemoryReadRefusesWhatExtractionRefuses(@TempDir Path tempDir) throws IOException {
        for (String name : PARENT_SEGMENT_NAMES) {
            Path jar = writeJar(Files.createTempDirectory(tempDir, "read").resolve("client.jar"), name);

            ClientException refused = assertThrows(ClientException.class, () -> ClientAcquisition.readVanillaPack(jar), name);

            assertThat("the refusal names the entry", refused.getMessage(), containsString(name));
        }
    }

    @Test
    @DisplayName("an entry name the file system cannot spell is refused as a ClientException")
    void extractionRefusesANameNoPathCanSpell(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir.resolve("client.jar"), "assets/minecraft/textures/block/sto\0ne.png");
        Path packRoot = tempDir.resolve("pack");

        assertThrows(ClientException.class, () -> ClientAcquisition.extractClientJar(jar, packRoot));
    }

    @Test
    @DisplayName("a name whose dots are not a parent segment is an ordinary entry")
    void dotsThatAreNotAParentSegmentAreKept(@TempDir Path tempDir) throws IOException {
        String dotted = "assets/minecraft/textures/block/..stone..v2.png";
        Path jar = writeJar(tempDir.resolve("client.jar"), dotted);
        Path packRoot = tempDir.resolve("pack");

        ClientAcquisition.extractClientJar(jar, packRoot);

        assertThat(Files.isRegularFile(packRoot.resolve(dotted)), is(true));
        assertThat(ClientAcquisition.readVanillaPack(jar).exists(dotted), is(true));
    }

    @Test
    @DisplayName("every shape of version id Mojang has published resolves under the cache root")
    void publishedVersionIdsResolve(@TempDir Path tempDir) {
        // One of each shape the Piston manifest carries: releases old and new, weekly and named
        // snapshots, pre-releases and release candidates in each spelling they have had - the 1.14 ones
        // with spaces - the alpha, beta, classic and pre-classic prefixes, and the April Fools builds.
        for (String version : List.of("26.1", "1.21.4", "1.0", "24w14a", "26.4-snapshot-10", "26.3-rc-3", "1.21.9-pre1",
            "1.21.9-rc1", "1.14 Pre-Release 5", "1.14.2 Pre-Release 4", "3D Shareware v1.34", "23w13a_or_b", "1.RV-Pre1",
            "22w13oneblockatatime", "b1.8.1", "a1.2.6", "c0.30_01c", "inf-20100618", "rd-132211")) {
            ClientOptions options = ClientOptions.builder().cacheRoot(tempDir.toFile()).version(version).build();

            assertThat(version, options.vanillaRoot(), equalTo(tempDir.resolve("vanilla").resolve(version)));
            assertThat(version, options.vanillaPackRoot(), equalTo(tempDir.resolve("vanilla").resolve(version).resolve("pack")));
        }
    }

    @Test
    @DisplayName("a version that is not a version id resolves to no cache directory")
    void malformedVersionsAreRefused(@TempDir Path tempDir) {
        File cacheRoot = tempDir.resolve("cache").toFile();

        for (String version : List.of("", ".", "..", "../26.1", "26.1/..", "/26.1", "26.1/pack", "26.1\\..", "C:26.1",
            "26.1.", ".26.1", " 26.1", "26.1 ", "26.1\0", "26\n1", "26.1*", "1".repeat(65))) {
            ClientOptions options = ClientOptions.builder().cacheRoot(cacheRoot).version(version).build();

            assertThrows(ClientException.class, options::vanillaRoot, "vanillaRoot of '" + version + "'");
            assertThrows(ClientException.class, options::vanillaPackRoot, "vanillaPackRoot of '" + version + "'");
        }

        assertThat("refusing a version creates nothing", cacheRoot.exists(), is(false));
    }

    @Test
    @DisplayName("a download of the declared size and SHA-1 becomes the cached jar, with nothing left beside it")
    void aMatchingDownloadIsCached(@TempDir Path tempDir) throws IOException {
        byte[] download = "a client jar".getBytes(StandardCharsets.UTF_8);
        Path target = tempDir.resolve("client.jar");
        Files.writeString(target, "the jar cached before");

        // The metadata's hex is lower case; the comparison does not depend on it.
        ClientAcquisition.writeVerified(new ByteArrayInputStream(download), target, sha1(download).toUpperCase(Locale.ROOT), download.length);

        assertThat(Files.readAllBytes(target), equalTo(download));
        assertThat("only the jar is left", filesIn(tempDir), contains("client.jar"));
    }

    @Test
    @DisplayName("a download that is short, long or altered is refused, leaving the cached jar as it was")
    void aMismatchedDownloadLeavesTheCacheAsItWas(@TempDir Path tempDir) throws IOException {
        byte[] declared = "a client jar".getBytes(StandardCharsets.UTF_8);
        byte[] altered = declared.clone();
        altered[0] ^= 1;
        Map<String, byte[]> downloads = Map.of(
            "short", Arrays.copyOf(declared, declared.length - 1),
            "long", Arrays.copyOf(declared, declared.length + 1),
            "far longer than a read buffer", new byte[64 * 1024],
            "altered", altered,
            "empty", new byte[0]);

        for (Map.Entry<String, byte[]> download : downloads.entrySet()) {
            Path cache = Files.createTempDirectory(tempDir, "warm");
            Path target = cache.resolve("client.jar");
            Files.writeString(target, "the jar cached before");

            assertThrows(ClientException.class, () -> ClientAcquisition.writeVerified(
                new ByteArrayInputStream(download.getValue()), target, sha1(declared), declared.length), download.getKey());

            assertThat(download.getKey() + " left the cached jar alone", Files.readString(target), equalTo("the jar cached before"));
            assertThat(download.getKey() + " left nothing beside it", filesIn(cache), contains("client.jar"));
        }
    }

    @Test
    @DisplayName("a refused download caches nothing where nothing was cached")
    void aMismatchedDownloadCachesNothing(@TempDir Path tempDir) {
        byte[] download = "a client jar".getBytes(StandardCharsets.UTF_8);
        Path target = tempDir.resolve("client.jar");

        assertThrows(ClientException.class, () -> ClientAcquisition.writeVerified(
            new ByteArrayInputStream(download), target, sha1("another jar".getBytes(StandardCharsets.UTF_8)), download.length));

        assertThat(tempDir.toFile().list(), is(new String[0]));
    }

    /**
     * Hashes bytes the way the Piston metadata declares a download's hash.
     *
     * @param bytes the bytes to hash
     * @return their SHA-1, in lower-case hex
     */
    private static @NotNull String sha1(byte @NotNull [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Lists the files directly inside a directory.
     *
     * @param directory the directory to list
     * @return its file names, sorted
     * @throws IOException if the directory cannot be listed
     */
    private static @NotNull List<String> filesIn(@NotNull Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * Writes a stand-in client jar holding the {@code version.json} a pack format is synthesised from,
     * one ordinary texture and one entry under the given name.
     *
     * @param jar where the jar is written
     * @param name the extra entry's name, written exactly as given
     * @return {@code jar}
     * @throws IOException if the jar cannot be written
     */
    private static @NotNull Path writeJar(@NotNull Path jar, @NotNull String name) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write("{\"name\":\"stand-in\",\"pack_version\":{\"resource_major\":84,\"resource_minor\":0}}"
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            for (String entry : List.of(STONE, name)) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(entry.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Lists every file under a work directory that is neither the stand-in jar nor inside the pack
     * root, which is everything an extraction had no business writing.
     *
     * @param work the directory the jar and the pack root both sit under
     * @param packRoot the root the extraction was pointed at
     * @return the stray files, relative to {@code work}
     * @throws IOException if the tree cannot be walked
     */
    private static @NotNull List<String> strays(@NotNull Path work, @NotNull Path packRoot) throws IOException {
        try (Stream<Path> tree = Files.walk(work)) {
            return tree.filter(Files::isRegularFile)
                .filter(path -> !path.startsWith(packRoot) && !path.equals(work.resolve("client.jar")))
                .map(path -> work.relativize(path).toString())
                .sorted()
                .toList();
        }
    }

}
