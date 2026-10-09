package lib.minecraft.renderer.support;

import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.content.index.RendererContext;
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
 * {@link ClientAssets}, the same vanilla pack and the same {@link RendererContext} loaded over them.
 * <p>
 * The assets are read from the client jar cached at the root {@link ClientOptions} itself defaults to,
 * which is what a pipeline run, a tooling flow and a render driver all write, so one cached jar serves
 * every one of them and a test charges nothing for a jar that is already there. The vanilla pack is the
 * jar's asset tree read into memory once, so every test that renders against vanilla reads the one
 * snapshot. An absent jar is acquired on demand, which pulls ~25MB from Mojang once and then never
 * again.
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

    /** the acquired assets, {@code null} until the first acquisition completes */
    private static volatile ClientAssets assets = null;

    /** the context built over {@link #assets}, {@code null} until the first caller asks for one */
    private static volatile RendererContext context = null;

    /**
     * Resolves the client assets before the first annotated test class runs, so the work is charged
     * to the extension rather than to whichever class happened to go first.
     *
     * <p>Installing this is what makes a class safe for the fast suite: where nothing has cached the
     * client jar yet, the class is ABANDONED rather than acquired for, so no run of the fast suite can
     * open a socket. A test that means to exercise the acquisition itself reaches {@link #assets()}
     * directly instead, which still acquires on demand.
     *
     * @param extensionContext the JUnit extension context, unused because the acquisition is JVM-global
     */
    @Override
    public void beforeAll(@NotNull ExtensionContext extensionContext) {
        assumeTrue(isCached(), () -> "no cached client jar at '" + jar()
            + "' - run './gradlew slowTest --tests \"*ClientAcquisitionIntegrationTest\"' to cache one");
        assets();
    }

    /**
     * Answers whether the client jar is already cached.
     *
     * <p>A presence question rather than a correctness one: what it decides is whether a class can
     * run at all, and a jar that is present but wrong is a matter for
     * {@code ClientAcquisitionIntegrationTest}, which asserts the shape of what is read out of one.
     *
     * @return {@code true} when a test can read the client assets without acquiring them
     */
    public static boolean isCached() {
        return Files.isRegularFile(jar());
    }

    /**
     * The client jar the assets are read out of, at production's own cache root.
     *
     * @return the cached client jar's path
     */
    public static @NotNull Path jar() {
        return OPTIONS.vanillaRoot().resolve("client.jar");
    }

    /**
     * Answers the client assets, acquiring them if this is the first call in the JVM.
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
     * Answers the vanilla pack the shared assets hold - the client jar's asset tree, read into memory
     * once for the JVM - so a test reads vanilla files through the same container production does.
     *
     * @return the shared vanilla pack
     */
    public static @NotNull PackContainer vanilla() {
        return assets().vanilla();
    }

    /**
     * Answers the production {@link RendererContext} over those assets, building its indexes if this is
     * the first call in the JVM. The context holds only unmodifiable indexes and the pack stack's own
     * decoded-pixel cache, so one instance serves every test class.
     *
     * @return the shared context
     */
    public static @NotNull RendererContext context() {
        RendererContext built = context;
        if (built != null) return built;

        synchronized (LOCK) {
            if (context == null) context = RendererContext.load(assets());
            return context;
        }
    }

}
