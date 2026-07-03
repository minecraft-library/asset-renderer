package lib.minecraft.renderer.support;

import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.tooling.ToolingFonts;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * JUnit 5 {@link Extension} that pre-warms the Minecraft OTF fonts before any annotated test
 * class touches {@link MinecraftFont}. Invokes {@link ToolingFonts}
 * exactly once per test JVM and materialises the produced {@code .otf} files into
 * {@code build/resources/test/fonts/}, which is already on the test classpath - the classloader
 * picks them up on the next {@code getResourceAsStream} call, so {@code MinecraftFont.<clinit>}
 * (which runs lazily when a test method first references the enum) finds them via its standard
 * classpath lookup.
 * <p>
 * Needed because the cached JitPack build of {@code com.github.minecraft-library:text} predates
 * the in-source {@code MinecraftFont} runtime bootstrap that would otherwise call
 * {@code ToolingFonts.generate} automatically on a classpath miss. Until JitPack rebuilds, we
 * pre-warm the cache from the test side by invoking {@link ToolingFonts#main} - the only public
 * entry point in the cached JAR - which writes the generator's output to
 * {@code cache/fonts/*.otf} at the module root.
 * <p>
 * Host requirements match {@link ToolingFonts}: {@code git} and a Python 3.10+ interpreter on
 * {@code PATH} for the first bootstrap. Subsequent runs reuse the cloned repo and venv under
 * {@code cache/font-generator/}.
 *
 * @see ToolingFonts
 */
public final class MinecraftFontsExtension implements BeforeAllCallback {

    /**
     * Minecraft version to generate. Hardcoded because {@code ToolingFonts.DEFAULT_VERSION} is
     * package-private in the cached JitPack JAR; keeping this in sync with the minecraft-text
     * source on a version bump is a one-line change.
     */
    private static final @NotNull String VERSION = "26.1";

    /** Where {@link ToolingFonts#main} writes the generator's OTF output. */
    private static final @NotNull Path CACHE_FONTS_DIR = Path.of("cache", "fonts");

    /** Final home of the OTFs, on the test classpath via Gradle's {@code build/resources/test} mapping. */
    private static final @NotNull Path CLASSPATH_FONTS_DIR = Path.of("build", "resources", "test", "fonts");

    /** Sentinel file whose presence short-circuits a re-run of the generator. */
    private static final @NotNull String SENTINEL = "Minecraft-Regular.otf";

    /** Monitor guarding the {@link #bootstrapped} double-checked-locking flow across parallel test classes. */
    private static final @NotNull Object LOCK = new Object();

    /** {@code true} once the fonts are on the classpath for this JVM; short-circuits every later {@code beforeAll}. */
    private static volatile boolean bootstrapped = false;

    /**
     * Provisions the OTFs onto the test classpath exactly once per JVM, before the first annotated
     * test class runs. Uses double-checked locking on {@link #bootstrapped} so concurrent test
     * classes don't re-run the generator. On the first uncached call: if the classpath sentinel is
     * already present nothing is done; otherwise the generator is invoked (unless its
     * {@code cache/fonts} output already exists) and the OTFs are copied onto the classpath.
     *
     * @param context the JUnit extension context (unused; the provisioning is JVM-global)
     * @throws Exception if the generator or the classpath copy fails
     */
    @Override
    public void beforeAll(@NotNull ExtensionContext context) throws Exception {
        if (bootstrapped) return;

        synchronized (LOCK) {
            if (bootstrapped) return;

            if (Files.isRegularFile(CLASSPATH_FONTS_DIR.resolve(SENTINEL))) {
                bootstrapped = true;
                return;
            }

            if (!Files.isRegularFile(CACHE_FONTS_DIR.resolve(SENTINEL)))
                ToolingFonts.main(new String[] { VERSION });

            copyOtfsToClasspath(CACHE_FONTS_DIR);
            bootstrapped = true;
        }
    }

    /**
     * Copies every {@code .otf} in {@code source} into {@link #CLASSPATH_FONTS_DIR}, overwriting any
     * existing file, so the classloader resolves them on the next {@code getResourceAsStream}.
     *
     * @param source the directory holding the generator's OTF output
     * @throws IOException if the target directory cannot be created or a source directory listing fails
     */
    private static void copyOtfsToClasspath(@NotNull Path source) throws IOException {
        Files.createDirectories(CLASSPATH_FONTS_DIR);
        try (Stream<Path> files = Files.list(source)) {
            files.filter(p -> p.getFileName().toString().endsWith(".otf"))
                .forEach(otf -> {
                    try {
                        Files.copy(otf, CLASSPATH_FONTS_DIR.resolve(otf.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        throw new RuntimeException("Failed to copy " + otf + " onto test classpath", ex);
                    }
                });
        }
    }

}
