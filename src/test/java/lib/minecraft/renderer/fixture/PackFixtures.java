package lib.minecraft.renderer.fixture;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.content.pack.MCMetaParser;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pack inputs for tests filed below the pack reader - a directory's container, a rule-bearing pack over
 * one, and a parsed {@code pack.mcmeta}.
 *
 * <p>A subject that walks or scans packs takes each as a {@link ResourcePack} over a
 * {@link PackContainer}. The only reader of the metadata is {@link MCMetaParser}, a tier above such a
 * test, so the test builds its input here, where the harness sits outside the tier order. A test filed
 * above the pack reader takes its rule-bearing pack here too, so a rule the lookup resolves end to end
 * is scanned out of the pack the scanner's own tests read.
 */
public final class PackFixtures {

    private PackFixtures() {}

    /**
     * Opens an exploded pack directory as the container a {@link ResourcePack} carries.
     *
     * @param root the pack root directory
     * @return the directory's container
     */
    public static @NotNull PackContainer directory(@NotNull Path root) {
        return new PackContainer.Directory(root);
    }

    /**
     * Opens an exploded pack directory as a pack the rule scanner reads - vanilla's core and the
     * OptiFine rules as its capabilities, the base root, the {@code minecraft} namespace and an empty
     * sidecar - creating the directory first when it does not exist.
     *
     * @param id the pack's id
     * @param root the pack root directory
     * @return the pack over the directory
     * @throws IOException if the directory cannot be created
     */
    public static @NotNull ResourcePack rulePack(@NotNull PackId id, @NotNull Path root) throws IOException {
        Files.createDirectories(root);
        return new ResourcePack(id, directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE, PackCapability.OPTIFINE_RULES));
    }

    /**
     * Parses a {@code .mcmeta} document from its JSON text.
     *
     * @param json the raw JSON text
     * @param id the asset id the document annotates
     * @return the parsed document
     */
    public static @NotNull MCMeta mcmeta(@NotNull String json, @NotNull ResourceId id) {
        return MCMetaParser.parse(json, id);
    }

}
