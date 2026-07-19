package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.FormatRange.FormatVersion;
import lib.minecraft.renderer.asset.pack.PackContainer;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.pack.rule.RuleScanner;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the vanilla pack root plus the user-supplied pack sources into a resolved {@link PackStack}:
 * detect each container by content, derive stable ids across the supply order (collisions resolved
 * loudly), resolve overlay roots against the renderer-target format, detect capabilities, and assemble
 * the stack with vanilla at priority 0.
 *
 * <p>Acquisition is virtual by default: each user pack keeps the {@link PackContainer} it was detected
 * as - a zip or {@code .cats} archive serves its bytes in place, without extraction to disk - so every
 * downstream loader reads through the container SPI. The vanilla base pack is a real
 * {@link PackContainer.Directory} over the tree {@code ClientAcquisition.extractClientJar} produces.
 */
@UtilityClass
public final class PackAcquisition {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();
    private static final @NotNull String CONFIG_CATHARSIS = "config.catharsis.json";

    /**
     * Compiles the full, fully-indexed pack stack for the given client assets: build the vanilla base
     * pack at the configured version, detect each user container by content, derive stable ids across
     * the supply order (collisions resolved loudly), resolve overlay roots, assemble the stack, scan it
     * into the texture index, and merge the pack rule layer - so callers never receive a bare stack.
     *
     * @param assets the extracted client assets (options + vanilla root)
     * @return the resolved, texture-indexed, rule-carrying stack, vanilla first
     * @throws PipelineException if a source is unreadable, a pack's metadata is malformed, or the
     *     extracted root does not match the configured version
     */
    public static @NotNull PackStack acquire(@NotNull ClientAssets assets) {
        ClientOptions options = assets.options();
        Path vanillaRoot = assets.vanillaRoot();
        // The directory-name version derivation is gone; the options version is authoritative.
        String minecraftVersion = options.getVersion();
        ResourcePack vanilla = vanillaPack(vanillaRoot, minecraftVersion);
        FormatVersion target = rendererTarget(vanilla);

        List<Path> userSources = options.getTexturePacks().stream().map(File::toPath).toList();
        List<PackContainer> containers = new ArrayList<>();
        List<PackIdDeriver.Naming> naming = new ArrayList<>();
        for (Path source : userSources) {
            PackContainer container = PackContainer.detect(source);
            containers.add(container);
            naming.add(new PackIdDeriver.Naming(source, container));
        }
        ConcurrentList<PackId> ids = PackIdDeriver.assign(naming);

        List<ResourcePack> packs = new ArrayList<>();
        packs.add(vanilla);
        for (int i = 0; i < userSources.size(); i++)
            packs.add(userPack(containers.get(i), ids.get(i), target, minecraftVersion));

        PackStack base = PackStack.of(Concurrent.adoptList(packs).toUnmodifiable());
        PackStack indexed = base.withTextureIndex(TextureIndexer.index(base));
        return indexed.withRules(RuleScanner.mergeAll(indexed));
    }

    /** Builds the vanilla base pack from its already-extracted tree (in place, no materialization). */
    private static @NotNull ResourcePack vanillaPack(@NotNull Path vanillaPackRoot, @NotNull String minecraftVersion) {
        if (!Files.isDirectory(vanillaPackRoot))
            throw new PipelineException("Vanilla pack root '%s' does not exist or is not a directory", vanillaPackRoot);
        PackContainer container = new PackContainer.Directory(vanillaPackRoot);
        MCMeta meta = readMeta(container, PackId.VANILLA);
        Set<PackCapability> capabilities = detectCapabilities(container, meta);
        ConcurrentList<PackRoot> roots = resolveRoots(container, meta, rendererTargetFrom(meta), minecraftVersion, capabilities);
        return new ResourcePack(PackId.VANILLA, container, meta, roots, namespaces(container, roots), capabilities);
    }

    /**
     * Assembles one user pack's {@link ResourcePack} straight off its detected container - virtual by
     * default: a zip / {@code .cats} pack serves its bytes in place, without extraction to disk.
     */
    private static @NotNull ResourcePack userPack(@NotNull PackContainer container, @NotNull PackId id,
                                                  @NotNull FormatVersion target, @NotNull String minecraftVersion) {
        MCMeta meta = readMeta(container, id);
        Set<PackCapability> capabilities = detectCapabilities(container, meta);
        ConcurrentList<PackRoot> roots = resolveRoots(container, meta, target, minecraftVersion, capabilities);
        return new ResourcePack(id, container, meta, roots, namespaces(container, roots), capabilities);
    }

    /**
     * Resolves the active roots: base first, then every vanilla {@code overlays.entries} overlay whose
     * format range contains the target, then - for a pack carrying {@link PackCapability#CATHARSIS_CONVENTIONS}
     * - every Catharsis {@code fabric:overlays} entry whose condition holds under the pack's config
     * defaults. Each active overlay's directory must exist. Both overlay families stack
     * over the root in list order (later wins), the vanilla {@code CompositePackResources} semantics
     * already applies.
     */
    private static @NotNull ConcurrentList<PackRoot> resolveRoots(@NotNull PackContainer container,
                                                                  @NotNull MCMeta meta, @NotNull FormatVersion target,
                                                                  @NotNull String minecraftVersion, @NotNull Set<PackCapability> capabilities) {
        ArrayList<PackRoot> roots = new ArrayList<>();
        roots.add(PackRoot.BASE);
        meta.pack().ifPresent(pack -> {
            for (MCMeta.Overlay overlay : pack.overlays()) {
                if (!overlay.formats().contains(target)) continue;
                if (containsAny(container, overlay.directory())) roots.add(PackRoot.overlay(overlay.directory()));
            }
        });
        if (capabilities.contains(PackCapability.CATHARSIS_CONVENTIONS))
            addCatharsisOverlays(container, target, minecraftVersion, roots);
        return Concurrent.adoptList(roots).toUnmodifiable();
    }

    /** Whether the container carries any file entry under a directory prefix - the virtual analog of "the overlay dir exists". */
    private static boolean containsAny(@NotNull PackContainer container, @NotNull String directory) {
        return container.entries(directory).findAny().isPresent();
    }

    /**
     * Appends the active Catharsis {@code fabric:overlays} roots. Parses the pack mcmeta for the
     * {@code fabric:overlays} entries, loads the config defaults (root {@code config.catharsis.json}
     * overriding any mcmeta {@code catharsis:pack/v1.config}), and evaluates each entry's condition
     * against them and the renderer target. Any parse failure degrades to no Catharsis overlays rather
     * than failing acquisition.
     */
    private static void addCatharsisOverlays(@NotNull PackContainer container,
                                             @NotNull FormatVersion target, @NotNull String minecraftVersion, @NotNull List<PackRoot> roots) {
        // Catharsis overlay evaluation runs over untrusted pack input during acquisition; any failure
        // (malformed JSON slipping a type guard, an illegal overlay directory string throwing
        // InvalidPathException on resolve, ...) must degrade to no Catharsis overlays, never break the
        // acquisition of this or any other pack (overlays inert, never error).
        try {
            Optional<JsonTree> mcmeta = readJsonObject(container, "pack.mcmeta");
            if (mcmeta.isEmpty()) return;
            CatharsisConfig config = CatharsisOverlays.loadConfig(readJson(container, CONFIG_CATHARSIS), mcmeta.get());
            CatharsisTarget catharsisTarget = new CatharsisTarget(target.major(), minecraftVersion);
            for (String directory : CatharsisOverlays.activeOverlayDirectories(mcmeta.get(), config, catharsisTarget))
                if (containsAny(container, directory)) roots.add(PackRoot.overlay(directory));
        } catch (RuntimeException ex) {
            System.err.printf("Pack '%s': skipping Catharsis overlays after an evaluation error: %s%n", container, ex);
        }
    }

    /** Reads and parses a container entry as a JSON object node, empty when absent or malformed (Catharsis never errors). */
    private static @NotNull Optional<JsonTree> readJsonObject(@NotNull PackContainer container, @NotNull String path) {
        return readJson(container, path).filter(JsonTree::isObject);
    }

    /** Reads and parses a container entry as a JSON node, empty when absent or malformed. */
    private static @NotNull Optional<JsonTree> readJson(@NotNull PackContainer container, @NotNull String path) {
        return container.bytes(path).flatMap(bytes -> {
            try {
                JsonElement parsed = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
                return parsed == null ? Optional.empty() : Optional.of(JsonTree.wrap(parsed));
            } catch (JsonSyntaxException ex) {
                return Optional.empty();
            }
        });
    }

    /**
     * The namespaces (directories under {@code assets/}) across a pack's active roots, derived from
     * the container's file entries so a zip / {@code .cats} pack enumerates identically to an exploded
     * directory. A namespace directory that ships no files contributes nothing to rendering and is not
     * reported (it was inert under the old directory listing too).
     */
    private static @NotNull Set<String> namespaces(@NotNull PackContainer container, @NotNull ConcurrentList<PackRoot> roots) {
        TreeSet<String> namespaces = new TreeSet<>();
        for (PackRoot root : roots) {
            String assetsPrefix = root.prefix() + "assets";
            container.entries(assetsPrefix).forEach(entry -> {
                String rest = entry.substring(assetsPrefix.length() + 1);
                int slash = rest.indexOf('/');
                if (slash > 0) namespaces.add(rest.substring(0, slash));
            });
        }
        return Set.copyOf(namespaces);
    }

    /**
     * Detects the capabilities a container carries. VANILLA_CORE and
     * OPTIFINE_RULES are path signals; CATHARSIS_CONVENTIONS has four independently-sufficient signals -
     * two on-disk paths ({@code config.catharsis.json}, {@code assets/skyblock/items/}) and two mcmeta
     * sections ({@code catharsis:pack/v1}, a {@code fabric:overlays} entry with a {@code catharsis:*}
     * condition). MCMeta's typed parse does not retain the Catharsis / Fabric sections, so the mcmeta
     * signals are read from the raw JSON (degrade-safe - a malformed mcmeta simply contributes no signal).
     */
    private static @NotNull Set<PackCapability> detectCapabilities(@NotNull PackContainer container, @NotNull MCMeta meta) {
        LinkedHashSet<PackCapability> capabilities = new LinkedHashSet<>();
        if (container.entries("").anyMatch(p -> p.startsWith("assets/") || p.contains("/assets/")))
            capabilities.add(PackCapability.VANILLA_CORE);
        if (container.entries("").anyMatch(p -> p.contains("/optifine/") || p.contains("/mcpatcher/")))
            capabilities.add(PackCapability.OPTIFINE_RULES);
        if (container.entries("").anyMatch(PackAcquisition::isCatharsisPathSignal)
            || readJsonObject(container, "pack.mcmeta").filter(PackAcquisition::hasCatharsisMcmetaSignal).isPresent())
            capabilities.add(PackCapability.CATHARSIS_CONVENTIONS);
        return Set.copyOf(capabilities);
    }

    private static boolean isCatharsisPathSignal(@NotNull String path) {
        // The full-segment form avoids matching a pack's own <ns>_skyblock/items tree (e.g. hypixel_skyblock).
        return path.endsWith("config.catharsis.json") || path.contains("assets/skyblock/items/");
    }

    /** Whether the pack mcmeta carries a {@code catharsis:pack/v1} section or a {@code fabric:overlays} entry gated on a {@code catharsis:*} condition. */
    private static boolean hasCatharsisMcmetaSignal(@NotNull JsonTree mcmetaRoot) {
        if (mcmetaRoot.keys().anyMatch(key -> key.startsWith("catharsis:pack"))) return true;
        Optional<JsonTree> overlays = mcmetaRoot.findObject("fabric:overlays");
        if (overlays.isEmpty()) return false;
        Optional<JsonTree> entries = overlays.get().findArray("entries");
        if (entries.isEmpty()) return false;
        for (JsonTree entry : entries.get().elements().toList()) {
            if (!entry.isObject()) continue;
            Optional<JsonTree> conditionObj = entry.findObject("condition");
            if (conditionObj.isEmpty()) continue;
            String condition = conditionObj.get().findString("condition").orElse(null);
            if (condition != null && condition.startsWith("catharsis:")) return true;
        }
        return false;
    }

    /** The renderer-target format for overlay activation: the game's current format, taken from the vanilla pack's declared floor. */
    private static @NotNull FormatVersion rendererTarget(@NotNull ResourcePack vanilla) {
        return rendererTargetFrom(vanilla.meta());
    }

    private static @NotNull FormatVersion rendererTargetFrom(@NotNull MCMeta meta) {
        return meta.pack().map(pack -> pack.formats().min()).orElse(new FormatVersion(0, 0));
    }

    private static @NotNull MCMeta readMeta(@NotNull PackContainer container, @NotNull PackId id) {
        return container.bytes("pack.mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), new ResourceId(id.value(), "pack")))
            .orElse(MCMeta.EMPTY);
    }

}
