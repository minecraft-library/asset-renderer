package lib.minecraft.renderer.bench;

import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.container.PackContainer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jol.info.GraphLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Compares the pack container kinds on equal content - the CPU each read operation costs, the memory it
 * allocates, and the heap each container keeps.
 * <p>
 * Every kind of one {@link #content} holds the same entries with the same bytes, built once per machine
 * under {@code build/jmh-fixtures/}: the vanilla pack as the production extraction writes it, and the
 * Hypixel+ user pack, each as an exploded directory and as a zip. The setup refuses a fixture whose
 * entry count disagrees with its sibling's, so a drifted fixture fails rather than skewing a row.
 * <p>
 * Allocation per operation comes from the {@code gc} profiler. The heap a container keeps is printed
 * once per trial as a {@code # footprint} line - a JOL deep size over the container's components - and
 * a directory or zip keeps almost none of it on the heap, because its bytes live in the operating
 * system's file cache, which no JVM measurement sees. Every run is warm-cache, and every row reports
 * milliseconds per operation, the unit the build's {@code jmh} block sets for every benchmark. Run:
 * <pre>{@code
 * ./gradlew jmh -PjmhInclude=PackContainerBenchmark -PjmhProfilers=gc
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@Warmup(time = 2)
@Measurement(time = 2)
@State(Scope.Benchmark)
public class PackContainerBenchmark {

    /** The seed the read order is shuffled with, so every run reads in the same order. */
    private static final long SEED = 42L;

    /** Where the fixtures are written, once per machine. */
    private static final Path FIXTURES = Path.of("build", "jmh-fixtures");

    /** The container kind under test. */
    @Param({"DIRECTORY", "ZIP"})
    public String kind;

    /** The pack the containers hold. */
    @Param({"VANILLA", "HYPIXEL_PLUS"})
    public String content;

    /** The container under test, built once per trial. */
    private PackContainer container;

    /** Every entry of the pack, in a seeded shuffle. */
    private String[] paths;

    /** Every PNG entry of the pack, in path order. */
    private String[] pngs;

    /** The directory one subtree listing reads - a texture folder of a size the two packs share. */
    private String subtree;

    /** The next entry the cycling reads take. */
    private int cursor;

    /**
     * Builds the fixtures where they are absent, opens the container, lists the paths the reads cycle
     * through and prints the container's footprint.
     *
     * @throws IOException if a fixture cannot be written
     */
    @Setup(Level.Trial)
    public void prepare() throws IOException {
        Fixture fixture = Fixture.valueOf(this.content).prepare();
        this.subtree = fixture.subtree;
        this.container = open();

        List<String> all = new ArrayList<>(this.container.entries("").sorted().toList());
        long expected = fixture.sibling(this.kind).entries("").count();
        if (all.size() != expected)
            throw new IllegalStateException("Fixture '%s' holds %d entries as %s and %d as its sibling"
                .formatted(this.content, all.size(), this.kind, expected));

        this.pngs = all.stream().filter(path -> path.endsWith(".png")).toArray(String[]::new);
        Collections.shuffle(all, new Random(SEED));
        this.paths = all.toArray(String[]::new);
        this.cursor = 0;

        System.out.printf("# footprint kind=%s content=%s entries=%d retained=%d%n",
            this.kind, this.content, this.paths.length, retained(this.container));
    }

    /**
     * Builds a fresh container of the kind under test.
     *
     * @return the container
     */
    @Benchmark
    public PackContainer open() {
        Fixture fixture = Fixture.valueOf(this.content);
        return switch (this.kind) {
            case "DIRECTORY" -> new PackContainer.Directory(fixture.directory());
            case "ZIP" -> new PackContainer.Zip(fixture.zip());
            default -> throw new IllegalArgumentException("Unknown container kind '%s'".formatted(this.kind));
        };
    }

    /**
     * Lists every entry of the pack.
     *
     * @return the entry count
     */
    @Benchmark
    public long entriesAll() {
        return this.container.entries("").count();
    }

    /**
     * Lists one texture folder, the shape a subtree walk asks for.
     *
     * @return the entry count
     */
    @Benchmark
    public long entriesSubtree() {
        return this.container.entries(this.subtree).count();
    }

    /**
     * Reads the next entry's bytes.
     *
     * @return the bytes
     */
    @Benchmark
    public Optional<byte[]> read() {
        return this.container.bytes(next());
    }

    /**
     * Asks whether the next entry exists, which it does.
     *
     * @return the answer
     */
    @Benchmark
    public boolean existsHit() {
        return this.container.exists(next());
    }

    /**
     * Asks whether an entry beside the next one exists, which it does not.
     *
     * @return the answer
     */
    @Benchmark
    public boolean existsMiss() {
        return this.container.exists(next() + ".absent");
    }

    /**
     * Probes every PNG for its {@code .mcmeta} sidecar, the read the texture index makes per texture.
     *
     * @return how many sidecars were found
     */
    @Benchmark
    public int sidecarScan() {
        int found = 0;
        for (String png : this.pngs)
            if (this.container.bytes(png + ".mcmeta").isPresent()) found++;

        return found;
    }

    /** The entry the next cycling read takes. */
    private String next() {
        String path = this.paths[this.cursor];
        if (++this.cursor == this.paths.length) this.cursor = 0;
        return path;
    }

    /**
     * The heap a container keeps - a JOL deep size over its record components, which is where every
     * kind keeps what it holds.
     */
    private static long retained(PackContainer container) {
        Object[] roots = Stream.of(container.getClass().getRecordComponents())
            .map(component -> value(component, container))
            .toArray();
        return GraphLayout.parseInstance(roots).totalSize();
    }

    private static Object value(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * One pack's content, materialised once per machine as an exploded directory and as a zip holding the
     * same entries.
     */
    private enum Fixture {

        /** The vanilla pack, as the production extraction writes it out of the cached client jar. */
        VANILLA("vanilla-" + ClientOptions.defaults().getVersion(), "assets/minecraft/textures/block") {
            @Override
            void writeDirectory(Path target) {
                Path jar = ClientOptions.defaults().vanillaRoot().resolve("client.jar");
                if (!Files.isRegularFile(jar))
                    throw new IllegalStateException("No cached client jar at '%s' - './gradlew generateTables' caches one".formatted(jar));

                ClientAcquisition.extractClientJar(jar, target);
            }
        },

        /** The Hypixel+ user pack, read from the texture-pack cache. */
        HYPIXEL_PLUS("hypixel-plus", "assets/hplus/textures/entity") {
            @Override
            void writeDirectory(Path target) throws IOException {
                Path zip = Path.of("cache", "texturepacks", "Hypixel+ 0.23.4 for 1.21.8.zip");
                if (!Files.isRegularFile(zip))
                    throw new IllegalStateException("No Hypixel+ pack at '%s'".formatted(zip));

                unzip(zip, target);
            }

            @Override
            Path zip() {
                return Path.of("cache", "texturepacks", "Hypixel+ 0.23.4 for 1.21.8.zip");
            }
        };

        /** The fixture's name under {@code build/jmh-fixtures/}. */
        private final String name;

        /** The texture folder the subtree listing reads. */
        private final String subtree;

        Fixture(String name, String subtree) {
            this.name = name;
            this.subtree = subtree;
        }

        /** Writes the exploded pack into an empty directory. */
        abstract void writeDirectory(Path target) throws IOException;

        /** The exploded pack. */
        Path directory() {
            return FIXTURES.resolve(this.name);
        }

        /** The zipped pack. */
        Path zip() {
            return FIXTURES.resolve(this.name + ".zip");
        }

        /**
         * Writes whichever of the two forms is absent - each into a temporary sibling first, then moved
         * into place, so a run that dies half way leaves nothing a later run would mistake for whole.
         */
        Fixture prepare() throws IOException {
            if (!Files.isDirectory(directory())) {
                Path partial = FIXTURES.resolve(this.name + ".partial");
                deleteTree(partial);
                Files.createDirectories(partial);
                writeDirectory(partial);
                Files.move(partial, directory(), StandardCopyOption.ATOMIC_MOVE);
            }
            if (!Files.isRegularFile(zip())) {
                Path partial = FIXTURES.resolve(this.name + ".zip.partial");
                zipTree(directory(), partial);
                Files.move(partial, zip(), StandardCopyOption.ATOMIC_MOVE);
            }
            return this;
        }

        /** The container the trial's kind is checked against - the other form of the same pack. */
        PackContainer sibling(String kind) {
            return kind.equals("DIRECTORY") ? new PackContainer.Zip(zip()) : new PackContainer.Directory(directory());
        }

    }

    /** Extracts every file entry of a zip under a directory. */
    private static void unzip(Path zip, Path target) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                Path destination = target.resolve(entry.getName());
                Files.createDirectories(destination.getParent());
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Zips every file under a directory, deflated, in path order. */
    private static void zipTree(Path root, Path zip) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            for (Path file : files) {
                archive.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
    }

    /** Deletes a directory tree, if there is one. */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

}
