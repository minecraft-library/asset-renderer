package lib.minecraft.renderer.support;

import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit 5 {@link Extension} that resolves the Minecraft client assets exactly once per test JVM,
 * before the first annotated test class runs, and hands every later caller the same
 * {@link ClientAssets} and the same {@link PipelineRendererContext} built over them.
 * <p>
 * The assets are read from the cache root {@link ClientOptions} itself defaults to, which is what a
 * pipeline run, a tooling flow and a render driver all write, so one extraction on disk serves every
 * one of them and a test charges nothing for a tree that is already there. An absent extraction is
 * acquired on demand, which pulls ~25MB from Mojang once and then never again.
 * <p>
 * {@link #VERSION} is production's own version read back rather than a second copy of it. It was a
 * literal here while this class owned its own cache root, where two spellings only meant two trees;
 * pointed at production's root, two spellings would mean one directory resolved two ways and nothing
 * would have said so.
 */
public final class ClientAssetsExtension implements BeforeAllCallback {

    /** what the assets are resolved through - production's own defaults, root and version alike */
    private static final @NotNull ClientOptions OPTIONS = ClientOptions.defaults();

    /** the Minecraft version every acquiring test renders against, which is the one production names */
    public static final @NotNull String VERSION = OPTIONS.getVersion();

    /** monitor guarding the double-checked-locking acquisition across test classes */
    private static final @NotNull Object LOCK = new Object();

    /** the extracted assets, {@code null} until the first acquisition completes */
    private static volatile ClientAssets assets = null;

    /** the context built over {@link #assets}, {@code null} until the first caller asks for one */
    private static volatile PipelineRendererContext context = null;

    /**
     * Resolves the client assets before the first annotated test class runs, so the work is charged
     * to the extension rather than to whichever class happened to go first.
     *
     * <p>Installing this is what makes a class safe for the fast suite: where nothing has extracted
     * the client yet, the class is ABANDONED rather than acquired for, so no run of the fast suite
     * can open a socket. A test that means to exercise the acquisition itself reaches
     * {@link #assets()} directly instead, which still acquires on demand.
     *
     * @param extensionContext the JUnit extension context, unused because the acquisition is JVM-global
     */
    @Override
    public void beforeAll(@NotNull ExtensionContext extensionContext) {
        assumeTrue(isExtracted(), () -> "no client extraction at '" + vanillaRoot()
            + "' - run './gradlew slowTest --tests \"*ClientAcquisitionIntegrationTest\"' to write one");
        assets();
    }

    /**
     * Answers whether the extraction is already on disk.
     *
     * <p>A presence question rather than a correctness one: what it decides is whether a class can
     * run at all, and an extraction that is present but wrong is a matter for
     * {@code ClientAcquisitionIntegrationTest}, which asserts the shape of one. So it asks after the
     * jar, the pack metadata and the two subtrees the extraction writes, and nothing further.
     *
     * @return {@code true} when a test can read the client assets without acquiring them
     */
    public static boolean isExtracted() {
        Path root = vanillaRoot();
        return Files.isRegularFile(root.resolve("client.jar"))
            && Files.isRegularFile(root.resolve("pack.mcmeta"))
            && Files.isDirectory(root.resolve(VanillaSourcePaths.VANILLA_ASSET_ROOT))
            && Files.isDirectory(root.resolve(VanillaSourcePaths.VANILLA_DATA_ROOT));
    }

    /**
     * Answers the extracted client assets, acquiring them if this is the first call in the JVM.
     *
     * @return the shared assets
     */
    public static @NotNull ClientAssets assets() {
        ClientAssets acquired = assets;
        if (acquired != null) return acquired;

        synchronized (LOCK) {
            if (assets == null) assets = ClientAcquisition.acquire(OPTIONS);
            return assets;
        }
    }

    /**
     * The pack root the assets are read from, which is production's own.
     *
     * @return the vanilla pack root
     */
    public static @NotNull Path vanillaRoot() {
        return OPTIONS.vanillaRoot();
    }

    /**
     * Answers the production {@link RendererContext} over those assets, building its indexes if this is
     * the first call in the JVM. The context holds only unmodifiable indexes and the pack stack's own
     * decoded-pixel cache, so one instance serves every test class.
     *
     * @return the shared context
     */
    public static @NotNull PipelineRendererContext context() {
        PipelineRendererContext built = context;
        if (built != null) return built;

        synchronized (LOCK) {
            if (context == null) context = PipelineRendererContext.of(assets());
            return context;
        }
    }

}
