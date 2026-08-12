package lib.minecraft.renderer.tooling.kernel;

import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.exception.ClientException;
import lib.minecraft.renderer.client.ClientOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared client-jar acquisition + session factory, giving every tooling main a single call
 * that resolves the jar, opens the class-node cache over it, and mints the run's diagnostics
 * root.
 */
@UtilityClass
public class ToolingPipeline {

    /** The {@code -Dasset.tooling.diag} override for the diagnostics output mode. */
    private static final @NotNull String DIAG_PROPERTY = "asset.tooling.diag";

    /** The FILE-mode log directory. */
    private static final @NotNull Path DIAGNOSTICS_DIR = Path.of("cache", "asset-renderer", "diagnostics");

    private static final @NotNull DateTimeFormatter LOG_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /**
     * Locates or downloads the client jar, opens the session cache over it, and mints the
     * flow's diagnostics root - the whole run context in one call.
     *
     * <p>Output resolution: the caller's {@code mode} stands unless
     * {@code -Dasset.tooling.diag=NONE|CONSOLE|FILE} overrides it; the FILE target is
     * {@code cache/asset-renderer/diagnostics/<flow>-<timestamp>.log}, resolved
     * here so {@link Diagnostics} stays mode-blind.
     *
     * @param flow the flow name (diagnostics root segment + envelope generator stamp)
     * @param mode the requested output mode (mains pass {@code CONSOLE}; tests {@code NONE})
     * @return the live session
     * @throws ToolingException if the jar cannot be acquired or opened
     */
    public static @NotNull ToolingSession openSession(@NotNull String flow, @NotNull Diagnostics.Output mode) {
        ClientOptions options = ClientOptions.defaults();
        Path jar;
        try {
            jar = ClientAcquisition.downloadJarToCache(options);
        } catch (ClientException ex) {
            throw new ToolingException(ex, "Failed to acquire client jar for flow '%s'", flow);
        }
        Diagnostics.Output resolved = resolveOutput(mode);
        Path fileTarget = resolved == Diagnostics.Output.FILE
            ? DIAGNOSTICS_DIR.resolve(flow + "-" + LOG_STAMP.format(LocalDateTime.now()) + ".log")
            : null;
        return new ToolingSession(options, ClassNodeCache.open(jar), Diagnostics.root(flow, resolved, fileTarget));
    }

    private static @NotNull Diagnostics.Output resolveOutput(@NotNull Diagnostics.Output requested) {
        @Nullable String override = System.getProperty(DIAG_PROPERTY);
        if (override == null || override.isBlank()) return requested;
        try {
            return Diagnostics.Output.valueOf(override.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolingException(ex, "Unknown -D%s value '%s' (expected NONE, CONSOLE, or FILE)", DIAG_PROPERTY, override);
        }
    }

}
