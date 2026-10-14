package lib.minecraft.renderer.sweep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import lib.minecraft.nbt.NbtFactory;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.call.request.ItemModelContext;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.PackAcquisition;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.sweep.LeafStacks.Reach;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The roster of item stacks the pack-icon sweep draws on both sides - the block items a resource pack
 * gives an item definition of its own, each with no stack and with every stack its definition's
 * component tests choose a branch for.
 * <p>
 * A block item here is one whose vanilla definition draws a block model in a slot, and the pack shadows
 * it where its own {@code items/<name>.json} replaces vanilla's. Each such item is a subject with no
 * stack - the branch the definition takes for a stack choosing nothing, drawn with the pack's textures -
 * and one subject per distinct stack {@link LeafStacks} solves a leaf of the pack's definition to at
 * the slot's display context. A definition the loader refuses keeps its plain subject and walks no
 * leaf, the client drawing its missing item model.
 * <p>
 * Both sides read this one file: the harness's pack-icon sweep decodes each stack's components with the
 * client's own codec and draws it through the client's slot path, and {@link PackIconParity} draws
 * the same stack through the renderer's inventory icon. The roster is written beside the reference tree,
 * where the harness writes the drawings it answers with.
 * <p>
 * Usage: {@code PackIconRoster <pack directory> <comma-separated pack file names, lowest priority first>}.
 */
@UtilityClass
public final class PackIconRoster {

    /** The roster and the harness drawings beside it, a sibling of the reference tree. */
    static final @NotNull Path HOME = ParityPaths.REFERENCES.resolveSibling("pack-icons");

    /** The roster file itself. */
    static final @NotNull Path ROSTER = HOME.resolve("roster.json");

    /** The display context a slot draws at, the one a subject's stack is solved for. */
    private static final @NotNull String SLOT = ItemModelContext.DISPLAY_CONTEXT_GUI;

    /** How many hex digits of a stack's SNBT digest name its subject. */
    private static final int DIGEST_DIGITS = 10;

    private static final @NotNull Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * One subject: an item and the stack it is drawn with.
     *
     * @param key the subject's name, which is also the stem of each side's drawing
     * @param item the qualified item id
     * @param components the stack's component patch as SNBT, {@code {}} for a stack choosing nothing
     * @param models the leaf models the stack was solved for, empty for a stack choosing nothing
     */
    record Subject(@NotNull String key, @NotNull String item, @NotNull String components, @NotNull List<String> models) {

        /** Whether the stack carries no component, the subject drawing the item as no stack. */
        boolean plain() {
            return this.models.isEmpty();
        }

    }

    /**
     * Writes the roster for the packs named.
     *
     * @param args the folder holding the packs, then their file names, comma-separated and lowest
     *     priority first
     * @throws IOException if a definition or the roster cannot be read or written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (args.length < 2 || args[1].isBlank())
            throw new IllegalArgumentException("Usage: PackIconRoster <pack directory> <pack file names>");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        List<String> names = List.of(args[1].split(","));
        List<File> files = names.stream().map(name -> directory.resolve(name.trim()).toFile()).toList();

        ClientAssets vanillaAssets = ClientAcquisition.acquire(ClientOptions.defaults());
        ClientAssets stackedAssets = new ClientAssets(
            ClientOptions.builder().texturePacks(Concurrent.adoptList(new ArrayList<>(files))).build(), vanillaAssets.vanilla());
        RendererContext vanilla = RendererContext.load(vanillaAssets);
        RendererContext stacked = RendererContext.load(stackedAssets);
        PackStack stack = PackAcquisition.acquire(stackedAssets);

        List<Subject> subjects = new ArrayList<>();
        definitions(stack).forEach((id, model) -> {
            boolean blockIcon = vanilla.findItemTree(id).toOptional()
                .flatMap(tree -> ItemModelContext.gui().resolve(tree).blockModel())
                .isPresent();
            if (!blockIcon) return;
            subjects.addAll(subjectsOf(id, model, stacked));
        });

        JsonObject roster = new JsonObject();
        roster.addProperty("format", 1);
        roster.addProperty("pack_directory", directory.toString());
        JsonArray packs = new JsonArray();
        names.forEach(name -> packs.add(name.trim()));
        roster.add("packs", packs);
        roster.add("subjects", GSON.toJsonTree(subjects));
        Files.createDirectories(HOME);
        Files.writeString(ROSTER, GSON.toJson(roster));

        long plain = subjects.stream().filter(Subject::plain).count();
        System.out.printf("Pack-icon roster for %s: %d block items, %d stacks -> %s%n",
            names, plain, subjects.size() - plain, ROSTER.toAbsolutePath().normalize());
    }

    /**
     * The subjects of one shadowed block item: the item with no stack, then every distinct stack a leaf
     * of its definition solves to at the slot, in the definition's own order.
     */
    private static @NotNull List<Subject> subjectsOf(@NotNull String id, @NotNull JsonElement model, @NotNull RendererContext stacked) {
        String stem = stem(id);
        Map<String, Subject> subjects = new LinkedHashMap<>();
        subjects.put(stem, new Subject(stem, id, "{}", List.of()));
        if (stacked.findItemTree(id).filter(ItemModelTree::isRejected).isPresent())
            return List.copyOf(subjects.values());

        for (LeafStacks.Leaf leaf : LeafStacks.leaves(id, model)) {
            Reach reach = Reach.of(leaf);
            if (!reach.walkable() || !reach.displayContext().equals(SLOT) || reach.components().isEmpty()) continue;
            String components = NbtFactory.toSnbt(reach.components());
            String key = stem + "~stack=" + digest(components);
            Subject held = subjects.get(key);
            List<String> models = new ArrayList<>(held == null ? List.of() : held.models());
            if (!models.contains(leaf.model())) models.add(leaf.model());
            subjects.put(key, new Subject(key, id, components, List.copyOf(models)));
        }
        return List.copyOf(subjects.values());
    }

    /**
     * The {@code model} member of the item definition each id resolves to above vanilla - the
     * highest-priority pack's file for it, read through the walk every pack loader reads through - keyed
     * by qualified item id in sorted order. A definition that is not JSON is left out, as the loader
     * refuses it.
     */
    private static @NotNull Map<String, JsonElement> definitions(@NotNull PackStack stack) throws IOException {
        List<ResourcePack> packs = stack.ascending().subList(1, stack.ascending().size());
        Map<String, PackSubtree.Entry> winners = new TreeMap<>();
        for (PackSubtree.Entry entry : PackSubtree.walk(packs, PackSubtree.Subtree.of("items", ".json")))
            if (entry.isDirectChild()) winners.put(entry.namespace() + ":" + entry.stem(), entry);

        Map<String, JsonElement> definitions = new TreeMap<>();
        for (Map.Entry<String, PackSubtree.Entry> winner : winners.entrySet()) {
            byte[] bytes = winner.getValue().container().bytes(winner.getValue().entryPath())
                .orElseThrow(() -> new IOException("Listed definition '" + winner.getKey() + "' reads no bytes"));
            try {
                JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (root.isJsonObject() && root.getAsJsonObject().has("model"))
                    definitions.put(winner.getKey(), root.getAsJsonObject().get("model"));
            } catch (JsonParseException unreadable) {
                // The loader refuses it too, and the client draws its missing item model.
            }
        }
        return definitions;
    }

    /** The subject stem of an item: {@code <namespace>__<path>}, as the harness names a reference. */
    private static @NotNull String stem(@NotNull String id) {
        return id.replace(":", "__");
    }

    /** The leading hex digits of a stack's SNBT digest. */
    private static @NotNull String digest(@NotNull String snbt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(snbt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_DIGITS);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", ex);
        }
    }

}
