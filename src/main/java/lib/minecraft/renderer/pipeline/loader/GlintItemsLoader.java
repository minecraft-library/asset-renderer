package lib.minecraft.renderer.pipeline.loader;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentSet;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.load.V2Document;
import lib.minecraft.renderer.pipeline.load.V2Resources;
import lib.minecraft.renderer.tooling.ToolingGlintItems;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;

/**
 * A loader that reads the bundled "always glinted" vanilla item set from the
 * {@code v2/glint_items.json} classpath resource and produces a set of namespaced item ids.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.item.Items} static initializer as parsed by
 * {@link ToolingGlintItems} {@code .Parser} - the items registered with the default data component
 * {@code minecraft:enchantment_glint_override = true}. To refresh it on a Minecraft version bump,
 * run the {@code glintItems2} Gradle task; the runtime pipeline never invokes the ASM walker directly.
 * <p>
 * Membership gates the automatic foil sheen at render time: an item in this set renders with the
 * enchantment glint even when its render options carry no explicit enchantment flag.
 */
@UtilityClass
public class GlintItemsLoader {

    /**
     * Bundled always-glinted item snapshot's resource file name.
     */
    private static final @NotNull String RESOURCE_NAME = "glint_items.json";

    /**
     * Loads the bundled always-glinted item set natively from v2.
     *
     * @return the set of namespaced item ids that carry an always-on glint override, unmodifiable
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull ConcurrentSet<String> load() {
        return loadNative(Diagnostics.root("glintItems", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the always-glinted item set from {@code v2/glint_items.json} natively through the shared
     * read layer. Exposed for tests.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return the set of namespaced item ids, unmodifiable
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull ConcurrentSet<String> loadNative(@NotNull Diagnostics diagnostics) {
        V2Document document = V2Resources.read(RESOURCE_NAME, V2Resources.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        HashSet<String> ids = new HashSet<>(document.as(V2GlintItems.class).items());
        return Concurrent.adoptSet(ids).toUnmodifiable();
    }

    /** The v2 {@code glint_items.json} payload: the sorted always-glinted item ids. */
    private record V2GlintItems(@NotNull List<String> items) {}

}
