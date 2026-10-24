package lib.minecraft.renderer.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.sweep.ParityPaths;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The renderer's pack resolution held to the client's, resource by resource - which packs supply each
 * file under the asset directories the renderer reads, in what order, and with what bytes.
 * <p>
 * The client's side is the harness's pack-source probe, {@code renderVanillaPackSources}, which writes
 * {@code pack-sources/sources.json} beside the reference tree off the client's own resource manager.
 * This side loads the same packs from the same folder in the same order and walks {@link PackSubtree} -
 * the one walk every pack loader reads through - over the same directories. Each side's answer is a
 * stack per resource, lowest priority first, of the pack supplying it and the SHA-256 of what it
 * supplies.
 * <p>
 * Two stacks disagree in one of four ways, each reported with its keys: a resource only the client
 * lists, one only the renderer lists, one both list from a different sequence of packs, and one both
 * list from the same packs with different bytes. The last is where an overlay the two sides activate
 * differently shows, an active overlay's file standing in for its base one under the pack's own id.
 * <p>
 * A diagnostic report rather than a gate: it writes
 * {@code cache/visual/pack-source-parity-vanilla/report.json} and prints the counts, and it exits
 * non-zero only when there is no dump to read.
 */
@UtilityClass
public final class PackSourceParity {

    /** The client's id for its built-in vanilla pack, which the renderer holds at priority 0. */
    static final @NotNull String VANILLA = "vanilla";

    /** The prefix the client's folder source gives the id of every pack it discovers. */
    private static final @NotNull String FILE_PREFIX = "file/";

    /** The sidecar extension, which the client pairs with the file it annotates rather than listing. */
    private static final @NotNull String MCMETA = ".mcmeta";

    /** The dump the probe writes, beside the reference tree. */
    private static final @NotNull Path SOURCES = ParityPaths.REFERENCES.resolveSibling("pack-sources").resolve("sources.json");

    /** Where the report is written. */
    private static final @NotNull Path REPORT = Path.of("cache", "visual", "pack-source-parity-vanilla", "report.json");

    /** How many keys the console lists per category. */
    private static final int EXAMPLES = 15;

    private static final @NotNull Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * One pack's contribution to one resource.
     *
     * @param pack the supplying pack, by the client's id for it
     * @param sha256 the lowercase hex SHA-256 of the bytes it supplies
     */
    record Supplied(@NotNull String pack, @NotNull String sha256) {}

    /**
     * Both sides' stacks for one resource they disagree on.
     *
     * @param client the client's stack, lowest priority first
     * @param renderer the renderer's stack, lowest priority first
     */
    record Disagreement(@NotNull List<Supplied> client, @NotNull List<Supplied> renderer) {}

    /**
     * How the two sides' stacks disagree, each category keyed by resource in sorted order.
     *
     * @param onlyClient resources the client lists and the renderer does not, with the client's stack
     * @param onlyRenderer resources the renderer lists and the client does not, with the renderer's stack
     * @param order resources both list from a different sequence of packs
     * @param bytes resources both list from the same packs, with different bytes from at least one
     */
    record Comparison(
        @NotNull SortedMap<String, List<Supplied>> onlyClient,
        @NotNull SortedMap<String, List<Supplied>> onlyRenderer,
        @NotNull SortedMap<String, Disagreement> order,
        @NotNull SortedMap<String, Disagreement> bytes
    ) {

        /** Whether the two sides agree on every resource. */
        boolean agrees() {
            return this.onlyClient.isEmpty() && this.onlyRenderer.isEmpty() && this.order.isEmpty() && this.bytes.isEmpty();
        }

    }

    /**
     * Loads the probe's dump and the same packs on this side, compares the two, and writes the report.
     *
     * @param args unused
     * @throws IOException if the dump cannot be read or the report cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (!Files.isRegularFile(SOURCES)) {
            System.err.printf("No pack-source dump at %s%n  Run renderVanillaPackSources first.%n", SOURCES.toAbsolutePath());
            System.exit(1);
        }

        JsonObject dump = JsonParser.parseString(Files.readString(SOURCES)).getAsJsonObject();
        List<String> requested = strings(dump.getAsJsonArray("requested"));
        List<String> directories = strings(dump.getAsJsonArray("directories"));
        PackStack stack = PackAcquisition.acquire(ClientAcquisition.acquire(
            ClientOptions.builder().texturePacks(Concurrent.adoptList(packFiles(dump))).build()));

        List<String> labels = labels(dump);
        Map<String, Integer> foreign = new TreeMap<>();
        Map<String, List<Supplied>> client = clientStacks(dump.getAsJsonObject("resources"), Set.copyOf(labels), foreign);
        Map<String, List<Supplied>> renderer = rendererStacks(stack, labels, directories);
        Comparison comparison = compare(client, renderer);

        JsonObject report = new JsonObject();
        report.addProperty("dump", SOURCES.toAbsolutePath().normalize().toString());
        report.add("resource_format", dump.get("resource_format"));
        report.add("requested", dump.get("requested"));
        report.add("selected", dump.get("selected"));
        report.add("missing", dump.get("missing"));
        report.add("foreign_entries", GSON.toJsonTree(foreign));
        JsonObject counts = new JsonObject();
        counts.addProperty("client", client.size());
        counts.addProperty("renderer", renderer.size());
        counts.addProperty("only_client", comparison.onlyClient().size());
        counts.addProperty("only_renderer", comparison.onlyRenderer().size());
        counts.addProperty("order", comparison.order().size());
        counts.addProperty("bytes", comparison.bytes().size());
        report.add("counts", counts);
        report.add("only_client", GSON.toJsonTree(comparison.onlyClient()));
        report.add("only_renderer", GSON.toJsonTree(comparison.onlyRenderer()));
        report.add("order", GSON.toJsonTree(comparison.order()));
        report.add("bytes", GSON.toJsonTree(comparison.bytes()));
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, GSON.toJson(report));

        System.out.printf("Pack-source parity, client format %s, packs %s%n", dump.get("resource_format").getAsString(), requested);
        if (!dump.getAsJsonArray("missing").isEmpty())
            System.out.printf("  the client did NOT select %s - every resource of those is the renderer's alone%n", dump.get("missing"));
        if (!foreign.isEmpty())
            System.out.printf("  dropped client stack entries from packs outside the comparison: %s%n", foreign);
        System.out.printf("  resources: client %d, renderer %d%n", client.size(), renderer.size());
        print("only the client lists", comparison.onlyClient(), PackSourceParity::top);
        print("only the renderer lists", comparison.onlyRenderer(), PackSourceParity::top);
        print("different pack order", comparison.order(), disagreement -> top(disagreement.client()));
        print("same packs, different bytes", comparison.bytes(), disagreement -> top(disagreement.client()));
        System.out.printf("%s -> %s%n", comparison.agrees() ? "AGREE" : "DISAGREE", REPORT.toAbsolutePath().normalize());
    }

    /**
     * Maps the packs a pack run's dump names as requested to the files the client's folder source
     * discovered them as, in request order. The pack-source probe's {@code sources.json} and the
     * pack-tooltip run's {@code stack.json} each carry the {@code pack_directory} and {@code requested}
     * members it reads.
     *
     * @param dump the pack run's dump
     * @return the pack files, lowest priority first
     * @throws IllegalStateException if a requested pack is not a folder-source id
     */
    public static @NotNull List<File> packFiles(@NotNull JsonObject dump) {
        Path directory = Path.of(dump.get("pack_directory").getAsString());
        List<File> packs = new ArrayList<>();
        for (String id : strings(dump.getAsJsonArray("requested"))) {
            if (!id.startsWith(FILE_PREFIX))
                throw new IllegalStateException(String.format("Requested pack '%s' is not a folder-source id", id));
            packs.add(directory.resolve(id.substring(FILE_PREFIX.length())).toFile());
        }
        return packs;
    }

    /**
     * Answers the client's id for each pack of the stack the renderer loads from a pack run's dump,
     * lowest priority first - the client's built-in vanilla pack, then each pack the dump names as
     * requested.
     *
     * @param dump the pack run's dump
     * @return the ids, one per pack of the stack {@link #packFiles} stacks over vanilla
     */
    public static @NotNull List<String> labels(@NotNull JsonObject dump) {
        List<String> labels = new ArrayList<>();
        labels.add(VANILLA);
        labels.addAll(strings(dump.getAsJsonArray("requested")));
        return labels;
    }

    /**
     * Compares the two sides' stacks resource by resource.
     *
     * @param client the client's stacks, keyed by {@code <namespace>:<path>}
     * @param renderer the renderer's stacks, under the same keys
     * @return every disagreement, by category
     */
    static @NotNull Comparison compare(@NotNull Map<String, List<Supplied>> client, @NotNull Map<String, List<Supplied>> renderer) {
        SortedMap<String, List<Supplied>> onlyClient = new TreeMap<>();
        SortedMap<String, List<Supplied>> onlyRenderer = new TreeMap<>();
        SortedMap<String, Disagreement> order = new TreeMap<>();
        SortedMap<String, Disagreement> bytes = new TreeMap<>();

        client.forEach((key, theirs) -> {
            List<Supplied> ours = renderer.get(key);
            if (ours == null)
                onlyClient.put(key, theirs);
            else if (!packs(theirs).equals(packs(ours)))
                order.put(key, new Disagreement(theirs, ours));
            else if (!theirs.equals(ours))
                bytes.put(key, new Disagreement(theirs, ours));
        });
        renderer.forEach((key, ours) -> {
            if (!client.containsKey(key)) onlyRenderer.put(key, ours);
        });

        return new Comparison(onlyClient, onlyRenderer, order, bytes);
    }

    /**
     * Collapses one resource's files, in resolution order, to one entry per pack - the last of each
     * pack's, which is its active overlay's where one stands in for the base file.
     *
     * @param resolutionOrder every surviving file for one resource, packs ascending and roots in order
     * @return the stack, one entry per supplying pack, lowest priority first
     */
    static @NotNull List<Supplied> collapse(@NotNull List<Supplied> resolutionOrder) {
        List<Supplied> stack = new ArrayList<>();
        for (Supplied supplied : resolutionOrder) {
            if (!stack.isEmpty() && stack.getLast().pack().equals(supplied.pack())) stack.removeLast();
            stack.add(supplied);
        }
        return stack;
    }

    /**
     * Reads the client's stacks, dropping each entry from a pack outside the comparison and counting it
     * under that pack's id - a mod's own resource pack, which the renderer never loads.
     */
    private static @NotNull Map<String, List<Supplied>> clientStacks(
        @NotNull JsonObject resources,
        @NotNull Set<String> known,
        @NotNull Map<String, Integer> foreign
    ) {
        Map<String, List<Supplied>> stacks = new TreeMap<>();
        for (Map.Entry<String, JsonElement> resource : resources.entrySet()) {
            List<Supplied> stack = new ArrayList<>();
            for (JsonElement element : resource.getValue().getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                String pack = entry.get("pack").getAsString();
                if (known.contains(pack)) stack.add(new Supplied(pack, entry.get("sha256").getAsString()));
                else foreign.merge(pack, 1, Integer::sum);
            }
            if (!stack.isEmpty()) stacks.put(resource.getKey(), stack);
        }
        return stacks;
    }

    /** Walks the renderer's stack over the same directories and collapses each resource's files. */
    private static @NotNull Map<String, List<Supplied>> rendererStacks(
        @NotNull PackStack stack,
        @NotNull List<String> labels,
        @NotNull List<String> directories
    ) {
        List<ResourcePack> ascending = stack.ascending();
        if (ascending.size() != labels.size())
            throw new IllegalStateException(String.format("The renderer stacked %d packs for '%s'", ascending.size(), labels));
        Map<PackId, String> labelOf = new HashMap<>();
        for (int i = 0; i < ascending.size(); i++) labelOf.put(ascending.get(i).id(), labels.get(i));

        Map<String, List<Supplied>> resolutionOrder = new TreeMap<>();
        for (String directory : directories) {
            for (PackSubtree.Entry entry : PackSubtree.walk(ascending, PackSubtree.Subtree.of(directory, ""))) {
                if (entry.resourcePath().endsWith(MCMETA)) continue;
                byte[] bytes = entry.container().bytes(entry.entryPath()).orElseThrow(() -> new IllegalStateException(
                    String.format("Listed entry '%s' reads no bytes", entry.entryPath())));
                resolutionOrder.computeIfAbsent(entry.namespace() + ":" + entry.resourcePath(), key -> new ArrayList<>())
                    .add(new Supplied(labelOf.get(entry.pack().id()), sha256(bytes)));
            }
        }

        Map<String, List<Supplied>> stacks = new TreeMap<>();
        resolutionOrder.forEach((key, files) -> stacks.put(key, collapse(files)));
        return stacks;
    }

    /** Prints one category's count, its largest groups by directory and top pack, and its first keys. */
    private static <V> void print(@NotNull String title, @NotNull SortedMap<String, V> category, @NotNull Function<V, String> topPack) {
        System.out.printf("  %s: %d%n", title, category.size());
        if (category.isEmpty()) return;

        Map<String, Integer> groups = new TreeMap<>();
        category.forEach((key, value) -> groups.merge(directoryOf(key) + " @ " + topPack.apply(value), 1, Integer::sum));
        groups.entrySet()
            .stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(EXAMPLES)
            .forEach(group -> System.out.printf("      %6d  %s%n", group.getValue(), group.getKey()));
        category.keySet().stream().limit(EXAMPLES).forEach(key -> System.out.printf("        %s%n", key));
    }

    /** The highest-priority pack of a stack. */
    private static @NotNull String top(@NotNull List<Supplied> stack) {
        return stack.getLast().pack();
    }

    /** The top-level asset directory of a {@code <namespace>:<path>} key. */
    private static @NotNull String directoryOf(@NotNull String key) {
        String path = key.substring(key.indexOf(':') + 1);
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private static @NotNull List<String> packs(@NotNull List<Supplied> stack) {
        return stack.stream().map(Supplied::pack).toList();
    }

    private static @NotNull List<String> strings(@NotNull JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private static @NotNull String sha256(byte @NotNull [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", ex);
        }
    }

}
