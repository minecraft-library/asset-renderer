package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.ArgbHex;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.tooling.ToolingPotionColors;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

/**
 * A loader that reads the bundled vanilla potion effect colour table from the
 * {@code v2/potion_colors.json} classpath resource and produces a lookup map of effect id to ARGB.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.effect.MobEffects} static initializer as parsed by
 * {@link ToolingPotionColors} {@code .Parser}. To refresh it on a Minecraft version bump, run the
 * {@code potionColors2} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Colours are stored as {@code 0x}-prefixed hex strings in the JSON because Gson cannot round-trip
 * {@code 0xFF000000}-class signed integers literally; the native read decodes them through
 * {@link ArgbHex}.
 */
@UtilityClass
public class PotionColorLoader {

    /**
     * Bundled effect colour snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "potion_colors.json";

    /**
     * Loads the bundled effect colour table natively from v2.
     *
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentMap<String, Integer> load() {
        return loadNative(Diagnostics.root("potionColors", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the effect colour table from {@code v2/potion_colors.json} natively through the shared
     * read layer, decoding each row's {@code color} through {@link ArgbHex}. Exposed for tests.
     *
     * @param diagnostics the scope envelope and colour warnings are recorded to
     * @return a map of namespaced effect id to ARGB colour
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull ConcurrentMap<String, Integer> loadNative(@NotNull Diagnostics diagnostics) {
        V2Document document = V2Resources.read(RESOURCE_NAME, V2Resources.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        HashMap<String, Integer> colors = new HashMap<>();
        for (V2Effect effect : document.as(V2PotionColors.class).effects())
            colors.put(effect.effect(), ArgbHex.parse(effect.color(), diagnostics));
        return Concurrent.adoptMap(colors).toUnmodifiable();
    }

    /** The v2 {@code potion_colors.json} payload: the ordered effect-colour rows. */
    private record V2PotionColors(@NotNull List<V2Effect> effects) {}

    /**
     * One v2 effect-colour row.
     *
     * @param effect the namespaced effect id
     * @param color the {@code 0xAARRGGBB} hex colour
     */
    private record V2Effect(@NotNull String effect, @NotNull String color) {}

}
