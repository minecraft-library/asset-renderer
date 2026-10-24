package lib.minecraft.refharness.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic pack-source probe. Writes which packs supply every client resource under the asset
 * directories asset-renderer reads, and the bytes each one supplies - the client's own answer to
 * which pack wins, for asset-renderer's {@code packSourceParityVanilla} to hold its pack stack to.
 *
 * <p>Nothing here is a reference, and nothing is drawn. The one subject writes
 * {@code pack-sources/sources.json} into a directory beside the reference tree, so the manifest that
 * gates the tree never acquires it.
 *
 * <p>The answer is {@code ResourceManager.listResourceStacks}, which is the resolution the client's
 * loaders read: a pack's {@code filter.block} has already erased what it hides from the packs beneath
 * it, an active overlay's file already stands in for its base one, and each stack runs lowest priority
 * first. A {@code .mcmeta} sidecar is never a key of its own there - the client pairs one with the file
 * it annotates - so none is one here.
 *
 * <p>The packs are the ones {@link HarnessConfig#PACKS} names, selected over the client's own
 * selection at boot. A requested pack the client did not select - one its folder source never
 * discovered, or refused - is written under {@code missing} rather than ending the run, because a pack
 * vanilla will not load is itself an answer to the question this probe asks.
 */
@Parity(claim = "harness-probe-sweep", mode = Mode.DEMOTE)
public final class PackSourceSweep implements Sweep<String> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The asset directories asset-renderer reads out of a pack, each listed whole. */
    private static final List<String> DIRECTORIES =
        List.of("atlases", "blockstates", "equipment", "items", "models", "textures");

    /** The one subject, which is also the written file's stem. */
    private static final String SUBJECT = "sources";

    /** The schema version of the written document. */
    private static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Returns a directory outside the reference tree - the dump is a diagnostic, and the manifest
     * that gates the tree must not acquire it.
     */
    @Override
    public String outputDir() {
        return "pack-sources";
    }

    @Override
    public List<String> enumerate(SweepContext ctx) {
        return List.of(SUBJECT);
    }

    @Override
    public RefKey key(String subject) {
        return RefKey.named(subject);
    }

    /** Returns a placeholder canvas - the probe draws nothing, so nothing reads it. */
    @Override
    public Canvas canvas(SweepContext ctx, String subject) {
        return Canvas.square(1);
    }

    /**
     * Writes the dump beside where the subject's PNG would land, as {@code <subject>.json}.
     *
     * @param ctx the sweep context
     * @param subject the subject descriptor
     * @param canvas unused
     * @param out the PNG path the runner resolved, whose directory the dump is written into
     * @return {@code true} once the dump is written
     * @throws IOException if a resource cannot be read or the dump cannot be written
     */
    @Override
    public boolean render(SweepContext ctx, String subject, Canvas canvas, Path out) throws IOException {
        Path target = out.resolveSibling(subject + ".json");
        Files.createDirectories(target.getParent());
        JsonObject dump = dump(ctx.client());
        Files.writeString(target, GSON.toJson(dump));
        LOG.info("PackSourceSweep: {} resources from {} selected packs -> {}",
            dump.getAsJsonObject("resources").size(), dump.getAsJsonArray("selected").size(), target);
        return true;
    }

    /**
     * Returns {@code false} - the one subject has no registry id for the allowlist to match against,
     * so honouring the filter would make any scoped run write nothing.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }

    /** Builds the whole document off the running client's pack repository and resource manager. */
    private static JsonObject dump(Minecraft client) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        selection(client, root);
        JsonArray missing = root.getAsJsonArray("missing");
        if (!missing.isEmpty())
            LOG.warn("PackSourceSweep: the client did not select {} of the requested packs", missing);
        root.add("directories", strings(DIRECTORIES));

        ResourceManager manager = client.getResourceManager();
        Map<String, JsonArray> stacks = new TreeMap<>();
        for (String directory : DIRECTORIES) {
            for (Map.Entry<Identifier, List<Resource>> listed : manager.listResourceStacks(directory, id -> true).entrySet())
                stacks.put(listed.getKey().toString(), stack(listed.getValue()));
        }
        JsonObject resources = new JsonObject();
        stacks.forEach(resources::add);
        root.add("resources", resources);
        return root;
    }

    /**
     * Appends the run's pack selection to a document - the client's resource format, the folder its
     * folder source discovers packs in, the packs {@link HarnessConfig#PACKS} requested, every pack the
     * client selected with the compatibility it graded the pack at, and the requested packs it did not
     * select.
     *
     * @param client the running client
     * @param root the document the five members are appended to, in that order
     */
    static void selection(Minecraft client, JsonObject root) {
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        root.addProperty("resource_format", format.major() + "." + format.minor());
        root.addProperty("pack_directory", client.getResourcePackDirectory().toAbsolutePath().normalize().toString());

        List<String> requested = HarnessConfig.packIds();
        Collection<String> selectedIds = client.getResourcePackRepository().getSelectedIds();
        root.add("requested", strings(requested));

        JsonArray selected = new JsonArray();
        for (Pack pack : client.getResourcePackRepository().getSelectedPacks()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", pack.getId());
            entry.addProperty("compatibility", pack.getCompatibility().name());
            selected.add(entry);
        }
        root.add("selected", selected);
        root.add("missing", strings(requested.stream().filter(id -> !selectedIds.contains(id)).toList()));
    }

    /**
     * Builds one resource's stack as the documents write it - each pack supplying the resource, lowest
     * priority first as the resource manager answers them, with the SHA-256 of what it supplies.
     *
     * @param stack the resource's stack, lowest priority first
     * @return the stack's entries, in the same order
     * @throws IOException if a stacked resource cannot be read
     */
    static JsonArray stack(List<Resource> stack) throws IOException {
        JsonArray entries = new JsonArray();
        for (Resource resource : stack) {
            JsonObject entry = new JsonObject();
            entry.addProperty("pack", resource.sourcePackId());
            entry.addProperty("sha256", sha256(resource));
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Answers the lowercase hex SHA-256 of one stacked resource's bytes.
     *
     * @param resource the resource
     * @return its digest
     * @throws IOException if the resource cannot be read
     */
    static String sha256(Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", ex);
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
