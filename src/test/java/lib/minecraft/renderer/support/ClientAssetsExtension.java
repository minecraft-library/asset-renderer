package lib.minecraft.renderer.support;

import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;

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
 * {@link #VERSION} is the one place the rendered Minecraft version is written down, so a version bump
 * is a one-line edit here rather than a sweep over every acquiring test.
 */
public final class ClientAssetsExtension implements BeforeAllCallback {

    /** the Minecraft version every acquiring test renders against */
    public static final @NotNull String VERSION = "26.1";

    /** what the assets are resolved through, at the cache root production's own default names */
    private static final @NotNull ClientOptions OPTIONS = ClientOptions.builder().version(VERSION).build();

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
