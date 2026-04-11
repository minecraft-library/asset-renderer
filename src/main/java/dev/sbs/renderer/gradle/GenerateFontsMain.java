package dev.sbs.renderer.gradle;

import dev.simplified.collection.Concurrent;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point invoked by the {@code generateFonts} Gradle JavaExec task.
 * <p>
 * Produces the Minecraft {@code .otf} font files consumed by
 * {@link dev.sbs.renderer.text.MinecraftFont MinecraftFont} by cloning the
 * {@code minecraft-library/font-generator} Python tool into {@code cache/font-generator/},
 * setting up a virtual environment, installing the package, and invoking the generator with
 * {@code --version} and {@code --output} arguments. The resulting {@code .otf} files land in
 * {@code cache/fonts/} at the module root - never in the source tree, so the
 * {@code src/main/resources/fonts/} classpath location stays empty at rest and the generator
 * never has to write into a resource directory.
 * <p>
 * The module's {@code processResources} task copies {@code cache/fonts/} into
 * {@code build/resources/main/fonts/} during the normal Gradle build, so
 * {@code MinecraftFont}'s internal {@code initFont("fonts/Minecraft-Regular.otf", size)} call
 * keeps resolving via the standard classpath lookup at runtime without any code changes.
 * <p>
 * Idempotent between runs: the clone and venv setup are skipped when their sentinel paths
 * already exist, only the generator invocation itself runs unconditionally. Pip install is
 * re-run each time with {@code --quiet} so an updated {@code pyproject.toml} in the cloned
 * repo is picked up automatically.
 *
 * <h2>Command-line arguments</h2>
 * <ul>
 * <li>{@code args[0]} - Minecraft version id to compile, e.g. {@code 26.1}. Defaults to
 *     {@code 26.1} when omitted (matches the bundled {@code vanilla_tints.json}).</li>
 * </ul>
 *
 * <h2>Requirements on the host</h2>
 * <ul>
 * <li>{@code git} on {@code PATH}</li>
 * <li>A Python 3.10+ interpreter on {@code PATH} (tried as {@code python3}, {@code python},
 *     then {@code py} in that order)</li>
 * </ul>
 */
@UtilityClass
public final class GenerateFontsMain {

    private static final @NotNull String REPO_URL = "https://github.com/minecraft-library/font-generator.git";
    private static final @NotNull String DEFAULT_VERSION = "26.1";
    private static final @NotNull String CLONE_DIR_REL = "cache/font-generator";
    private static final @NotNull String FONTS_DIR_REL = "cache/fonts";
    private static final @NotNull String MODULE_NAME = "minecraft_fontgen";

    /**
     * Runs the font generator.
     *
     * @param args {@code args[0]} is the Minecraft version id; defaults to {@code 26.1}
     * @throws IOException if git / venv / pip / the generator fails, or any file I/O in the
     *     cache directory errors out
     * @throws InterruptedException if a child process is interrupted while waiting
     */
    public static void main(String @NotNull [] args) throws IOException, InterruptedException {
        String version = args.length > 0 ? args[0] : DEFAULT_VERSION;
        Path moduleRoot = Path.of(".").toAbsolutePath().normalize();
        Path cloneDir = moduleRoot.resolve(CLONE_DIR_REL);
        Path fontsDir = moduleRoot.resolve(FONTS_DIR_REL);

        ensureRepoCloned(moduleRoot, cloneDir);
        Path venvPython = ensureVenv(cloneDir);
        installPackage(cloneDir, venvPython);
        runGenerator(cloneDir, venvPython, version, fontsDir);

        System.out.println();
        System.out.println("Fonts generated at " + fontsDir);
        System.out.println("Run ./gradlew :asset-renderer:processResources to copy them onto the classpath.");
    }

    /**
     * Clones the font-generator repo into {@code cache/font-generator} if the target directory
     * does not already exist. Assumes {@code git} is on {@code PATH}.
     */
    private static void ensureRepoCloned(@NotNull Path moduleRoot, @NotNull Path cloneDir) throws IOException, InterruptedException {
        if (Files.isDirectory(cloneDir)) {
            System.out.println("Using cached font-generator clone at " + cloneDir);
            return;
        }
        Files.createDirectories(cloneDir.getParent());
        System.out.println("Cloning " + REPO_URL + " -> " + cloneDir);
        run(moduleRoot, "git", "clone", "--depth", "1", REPO_URL, cloneDir.toString());
    }

    /**
     * Creates a Python 3 virtual environment inside the cloned repo if not already present, and
     * returns the path to the venv's Python interpreter. Picks the correct interpreter name per
     * platform ({@code python3} / {@code python} / {@code py}) for the initial {@code venv}
     * call; subsequent calls use the venv's own interpreter directly.
     */
    private static @NotNull Path ensureVenv(@NotNull Path cloneDir) throws IOException, InterruptedException {
        Path venvDir = cloneDir.resolve(".venv");
        Path venvPython = resolveVenvPython(venvDir);
        if (Files.isRegularFile(venvPython)) {
            System.out.println("Using cached venv at " + venvDir);
            return venvPython;
        }
        String hostPython = detectHostPython();
        System.out.println("Creating virtual environment with " + hostPython + " -m venv .venv");
        run(cloneDir, hostPython, "-m", "venv", ".venv");
        if (!Files.isRegularFile(venvPython))
            throw new IOException("venv creation did not produce the expected interpreter at " + venvPython);
        return venvPython;
    }

    /**
     * Installs the font-generator package into the venv in editable mode. Re-running this is
     * cheap when nothing changed (pip short-circuits), and picks up upstream
     * {@code pyproject.toml} changes automatically.
     */
    private static void installPackage(@NotNull Path cloneDir, @NotNull Path venvPython) throws IOException, InterruptedException {
        System.out.println("Installing font-generator package via pip");
        run(cloneDir, venvPython.toString(), "-m", "pip", "install", "-e", ".", "--quiet");
    }

    /**
     * Invokes the generator itself. The output directory is passed as an absolute path so the
     * generator lands files in the module's {@code cache/fonts/} regardless of its own
     * working-directory assumptions.
     */
    private static void runGenerator(
        @NotNull Path cloneDir,
        @NotNull Path venvPython,
        @NotNull String version,
        @NotNull Path fontsDir
    ) throws IOException, InterruptedException {
        Files.createDirectories(fontsDir);
        System.out.println("Running " + MODULE_NAME + " --version " + version + " --output " + fontsDir);
        run(
            cloneDir,
            venvPython.toString(),
            "-m", MODULE_NAME,
            "--version", version,
            "--output", fontsDir.toAbsolutePath().toString(),
            "--silent"
        );
    }

    /**
     * Returns the path to the venv's Python interpreter for the current platform. Windows venvs
     * place it under {@code Scripts/python.exe}; POSIX venvs under {@code bin/python}.
     */
    private static @NotNull Path resolveVenvPython(@NotNull Path venvDir) {
        return isWindows()
            ? venvDir.resolve("Scripts").resolve("python.exe")
            : venvDir.resolve("bin").resolve("python");
    }

    /**
     * Probes common Python interpreter names and returns the first one that responds to
     * {@code --version} successfully. Throws with a clear error message if none is found.
     */
    private static @NotNull String detectHostPython() {
        for (String candidate : new String[] { "python3", "python", "py" }) {
            try {
                Process probe = new ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
                if (probe.waitFor() == 0)
                    return candidate;
            } catch (IOException | InterruptedException ignored) {
                // keep probing
            }
        }
        throw new IllegalStateException("No Python 3 interpreter found on PATH - install Python 3.10+ and retry");
    }

    /**
     * Runs a child process with the given command line in the given working directory,
     * inheriting stdin / stdout / stderr so generator output streams live to Gradle's console.
     * Throws on non-zero exit.
     */
    private static void run(@NotNull Path workingDir, @NotNull String @NotNull ... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(Concurrent.newList(command))
            .directory(workingDir.toFile())
            .inheritIO()
            .start();
        int exit = process.waitFor();
        if (exit != 0)
            throw new IOException("Command failed with exit code " + exit + ": " + String.join(" ", command));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

}
