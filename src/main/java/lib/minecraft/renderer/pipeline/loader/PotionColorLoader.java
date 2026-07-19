package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Map;

/**
 * A loader that reads the bundled vanilla potion effect colour table from the
 * {@code potion_colors.json} classpath resource and produces a lookup map of effect id to ARGB.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.effect.MobEffects} static initializer as parsed by
 * {@link ToolingPotionColors} {@code .Parser}. To refresh it on a Minecraft version bump, run the
 * {@code potionColors} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Colours are stored as {@code 0x}-prefixed hex strings in an effect-keyed object because Gson cannot
 * round-trip {@code 0xFF000000}-class signed integers literally; each value reflects straight into a
 * {@link Color} through the shared codec.
 */
@UtilityClass
public class PotionColorLoader {

    /**
     * Bundled effect colour snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "potion_colors.json";

    /**
     * Loads the bundled effect colour table natively.
     *
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull Map<String, Color> load() {
        return loadNative(Diagnostics.root("potionColors", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the effect colour table from {@code potion_colors.json} natively through the shared
     * read layer, decoding each value through the {@link Color} codec. Exposed for tests.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull Map<String, Color> loadNative(@NotNull Diagnostics diagnostics) {
        ResourceDocument document = BundledResource.read(RESOURCE_NAME, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        return document.as(PotionColorTable.class).effects();
    }

    /** The {@code potion_colors.json} payload: the effect-keyed colour map. */
    private record PotionColorTable(@NotNull Map<String, Color> effects) {}

}
