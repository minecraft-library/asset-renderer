package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.pipeline.util.ResourceDocument;
import lib.minecraft.renderer.pipeline.util.BundledResource;
import lib.minecraft.renderer.tooling.ToolingGlintItems;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A loader that reads the bundled "always glinted" vanilla item set from the
 * {@code glint_items.json} classpath resource and produces a set of namespaced item ids.
 * <p>
 * The JSON resource is a checked-in snapshot of MC 26.1's
 * {@code net.minecraft.world.item.Items} static initializer as parsed by
 * {@link ToolingGlintItems} {@code .Parser} - the items registered with the default data component
 * {@code minecraft:enchantment_glint_override = true}. To refresh it on a Minecraft version bump,
 * run the {@code glintItems} Gradle task; the runtime pipeline never invokes the ASM walker directly.
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
     * Loads the bundled always-glinted item set natively.
     *
     * @return the set of namespaced item ids that carry an always-on glint override
     * @throws PipelineException if the classpath resource is missing or malformed
     */
    public static @NotNull Set<String> load() {
        return loadNative(Diagnostics.root("glintItems", Diagnostics.Output.CONSOLE, null));
    }

    /**
     * Reads the always-glinted item set from {@code glint_items.json} natively through the shared
     * read layer. Exposed for tests.
     *
     * @param diagnostics the scope envelope warnings are recorded to
     * @return the set of namespaced item ids
     * @throws PipelineException if the resource is missing or malformed
     */
    static @NotNull Set<String> loadNative(@NotNull Diagnostics diagnostics) {
        ResourceDocument document = BundledResource.read(RESOURCE_NAME, BundledResource.MissingPolicy.REQUIRED, diagnostics).orElseThrow();
        return document.as(GlintItemTable.class).items();
    }

    /** The {@code glint_items.json} payload: the sorted always-glinted item ids. */
    private record GlintItemTable(@NotNull Set<String> items) {}

}
