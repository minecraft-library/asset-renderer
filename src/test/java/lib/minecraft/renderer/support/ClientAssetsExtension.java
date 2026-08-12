package lib.minecraft.renderer.support;

import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;

/**
 * JUnit 5 {@link Extension} that downloads and extracts the Minecraft client jar exactly once per test
 * JVM, before the first annotated test class runs, and hands every later caller the same
 * {@link ClientAssets} and the same {@link PipelineRendererContext} built over them.
 * <p>
 * The first uncached call pulls ~25MB from Mojang; every later one reuses {@link #CACHE_ROOT}, which is
 * a stable directory rather than a temporary one so the extracted jar survives across sessions and
 * offline vanilla-source lookups keep working. Both accessors acquire on demand, so a caller that
 * cannot declare {@code @ExtendWith} - a bootstrap that has to sit inside the one test method that is
 * tagged slow - reaches the same single acquisition.
 * <p>
 * {@link #VERSION} is the one place the rendered Minecraft version is written down, so a version bump
 * is a one-line edit here rather than a sweep over every acquiring test.
 */
public final class ClientAssetsExtension implements BeforeAllCallback {

    /** the Minecraft version every acquiring test renders against */
    public static final @NotNull String VERSION = "26.1";

    /** stable, non-temporary cache root, so the extracted client jar survives across sessions */
    public static final @NotNull File CACHE_ROOT = new File("cache/it");

    /** monitor guarding the double-checked-locking acquisition across test classes */
    private static final @NotNull Object LOCK = new Object();

    /** the extracted assets, {@code null} until the first acquisition completes */
    private static volatile ClientAssets assets = null;

    /** the context built over {@link #assets}, {@code null} until the first caller asks for one */
    private static volatile PipelineRendererContext context = null;

    /**
     * Acquires the client assets before the first annotated test class runs, so the download and
     * extraction are charged to the extension rather than to whichever class happened to go first.
     *
     * @param extensionContext the JUnit extension context, unused because the acquisition is JVM-global
     */
    @Override
    public void beforeAll(@NotNull ExtensionContext extensionContext) {
        assets();
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
            if (assets == null)
                assets = ClientAcquisition.acquire(ClientOptions.builder()
                    .version(VERSION)
                    .cacheRoot(CACHE_ROOT)
                    .build());
            return assets;
        }
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
