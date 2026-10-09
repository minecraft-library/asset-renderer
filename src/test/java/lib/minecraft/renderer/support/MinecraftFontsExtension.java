package lib.minecraft.renderer.support;

import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.tooling.ToolingFonts;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit 5 {@link Extension} that puts the Minecraft OTF fonts where {@link MinecraftFont} finds them,
 * before the first annotated test class reads one, or abandons the class where no cache holds them.
 * <p>
 * {@code MinecraftFont.Vanilla} resolves each font from the classpath, then from a per-user cache,
 * and failing both runs {@link ToolingFonts#generate} - which clones {@code font-generator} over the
 * network and builds it with the host's Python. Installing this is what keeps that last step out of
 * the fast suite: it reads the fonts from {@code build/resources/test/fonts}, which is on the test
 * classpath, from the project's {@code cache/fonts}, which it copies onto the classpath, or from the
 * library's own per-user cache, which the library reads itself - and where none of the three holds
 * all six, it ABANDONS the class rather than let a render reach the generator.
 * <p>
 * {@code FontGenerationIntegrationTest} is the one place the generator runs: it writes
 * {@code cache/fonts}, and it carries the slow tag because it can open a socket.
 */
public final class MinecraftFontsExtension implements BeforeAllCallback {

    /** the Minecraft version the fonts are generated for, which is the one the library resolves */
    public static final @NotNull String VERSION = ToolingFonts.DEFAULT_VERSION;

    /** where {@link ToolingFonts#main} writes the generator's OTF output, relative to the project */
    public static final @NotNull Path CACHE_FONTS_DIR = Path.of("cache", "fonts");

    /** the test classpath's copy, which {@code MinecraftFont} reads first */
    private static final @NotNull Path CLASSPATH_FONTS_DIR = Path.of("build", "resources", "test", "fonts");

    /** every file {@code MinecraftFont.Vanilla} loads, one per constant */
    public static final @NotNull List<String> FONT_FILES = List.of(
        "Minecraft-Regular.otf", "Minecraft-Bold.otf", "Minecraft-Italic.otf", "Minecraft-BoldItalic.otf",
        "Minecraft-Galactic.otf", "Minecraft-Illageralt.otf");

    /** monitor guarding the one copy onto the classpath across test classes */
    private static final @NotNull Object LOCK = new Object();

    /** {@code true} once the fonts are readable without the generator for this JVM */
    private static volatile boolean provisioned = false;

    /**
     * Makes the fonts readable before the first annotated test class runs, or abandons the class.
     *
     * @param context the JUnit extension context, unused because the provisioning is JVM-global
     * @throws IOException if the copy onto the classpath cannot list or create a directory
     */
    @Override
    public void beforeAll(@NotNull ExtensionContext context) throws IOException {
        if (provisioned) return;

        synchronized (LOCK) {
            if (provisioned) return;
            Optional<Path> source = source();
            assumeTrue(source.isPresent(), () -> "no Minecraft fonts at '" + CLASSPATH_FONTS_DIR + "', '"
                + CACHE_FONTS_DIR + "' or '" + perUserFontsDir() + "' - run './gradlew slowTest --tests"
                + " \"*FontGenerationIntegrationTest\"' to write them");
            if (source.get().equals(CACHE_FONTS_DIR)) copyToClasspath(CACHE_FONTS_DIR);
            provisioned = true;
        }
    }

    /**
     * Answers whether the fonts can be read without running the generator.
     *
     * @return {@code true} when one of the three caches holds every font file
     */
    public static boolean isPresent() {
        return source().isPresent();
    }

    /**
     * Returns the first cache holding every font file, in the order the fonts are best read from.
     *
     * @return the directory, or empty when none holds all of them
     */
    private static @NotNull Optional<Path> source() {
        return List.of(CLASSPATH_FONTS_DIR, CACHE_FONTS_DIR, perUserFontsDir()).stream()
            .filter(dir -> FONT_FILES.stream().allMatch(file -> Files.isRegularFile(dir.resolve(file))))
            .findFirst();
    }

    /**
     * The library's per-user font cache, where its second tier reads.
     *
     * @return the directory under {@link MinecraftFont#defaultCacheRoot()}
     */
    private static @NotNull Path perUserFontsDir() {
        return MinecraftFont.defaultCacheRoot().resolve("fonts").resolve(VERSION);
    }

    /**
     * Copies every font file in {@code source} onto the test classpath, overwriting any existing
     * file, so the classloader resolves them on the next {@code getResourceAsStream}.
     *
     * @param source the directory holding the generator's OTF output
     * @throws IOException if the target directory cannot be created
     * @throws ContentException if one of the fonts cannot be copied
     */
    private static void copyToClasspath(@NotNull Path source) throws IOException {
        Files.createDirectories(CLASSPATH_FONTS_DIR);
        for (String file : FONT_FILES) {
            try {
                Files.copy(source.resolve(file), CLASSPATH_FONTS_DIR.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new ContentException(ex, "Failed to copy '%s' onto the test classpath", file);
            }
        }
    }

}
