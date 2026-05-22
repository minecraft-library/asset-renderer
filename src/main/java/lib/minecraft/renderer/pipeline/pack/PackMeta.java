package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.pack.TexturePack;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * The parsed contents of a pack's root {@code pack.mcmeta} file, normalised to the fields the
 * pipeline actually consumes: declared format, description, and the optional overlay-entries
 * list. Single canonical parser shared by the vanilla pack (extracted out of the client jar) and
 * every user-supplied texture pack.
 *
 * @see FormatSpec
 * @param packFormat the pack's declared resource pack format. Reads {@code pack.pack_format}
 *     first then falls back to the legacy {@code pack.format} alias; defaults to {@code 0} when
 *     neither key is present (which means the pack declares no overlay format and any
 *     {@code overlays.entries[].formats} ranges that contain {@code 0} would still match - in
 *     practice that's no real overlay)
 * @param description the pack's human-readable description, or empty when the value is missing
 *     or carries a structured text component the pipeline does not interpret
 * @param overlays the optional overlay entries declared under {@code overlays.entries}, in
 *     declaration order; loaders walk these later-wins
 */
public record PackMeta(
    int packFormat,
    @NotNull String description,
    @NotNull ConcurrentList<Overlay> overlays
) {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * The empty mcmeta carrying no format, no description, and no overlays. Used as the default
     * for {@link TexturePack TexturePack}'s no-args
     * constructor and as a safe stub for in-memory test contexts that synthesise a pack without
     * a real {@code pack.mcmeta} on disk.
     */
    public static final @NotNull PackMeta EMPTY = new PackMeta(0, "", Concurrent.newList());

    /**
     * A single {@code overlays.entries[*]} entry: the directory name relative to the pack root
     * and the {@link FormatSpec} predicate gating when this overlay applies.
     *
     * @param directory the overlay subdirectory name, relative to the pack root
     * @param formats the format predicate that gates whether this overlay's directory contributes
     *     a root to the resolved pack
     */
    public record Overlay(@NotNull String directory, @NotNull FormatSpec formats) {}

    /**
     * Parses {@code pack.mcmeta} from the given file path. The file must exist and parse as a
     * JSON object; missing or malformed mcmeta is a loud failure on the assumption that the
     * caller expected the pack to load.
     *
     * @param mcmetaFile the path to the {@code pack.mcmeta} file
     * @param packId the pack identifier, used in error messages
     * @return the parsed metadata
     * @throws PipelineException if the file is missing, unreadable, or the JSON is malformed
     */
    public static @NotNull PackMeta parse(@NotNull Path mcmetaFile, @NotNull String packId) {
        if (!Files.isRegularFile(mcmetaFile))
            throw new PipelineException("Pack '%s' is missing its pack.mcmeta at '%s'", packId, mcmetaFile);

        JsonObject root;
        try {
            root = GSON.fromJson(Files.readString(mcmetaFile), JsonObject.class);
        } catch (IOException | JsonSyntaxException ex) {
            throw new PipelineException(ex, "Pack '%s' has malformed pack.mcmeta at '%s'", packId, mcmetaFile);
        }
        if (root == null)
            throw new PipelineException("Pack '%s' has empty pack.mcmeta at '%s'", packId, mcmetaFile);

        return new PackMeta(readPackFormat(root), readDescription(root), readOverlays(root, packId));
    }

    /**
     * Walks {@code root.pack.pack_format} (preferred) then {@code root.pack.format} (legacy
     * alias). Returns {@code 0} when neither key is present.
     */
    private static int readPackFormat(@NotNull JsonObject root) {
        if (!root.has("pack") || !root.get("pack").isJsonObject()) return 0;
        JsonObject pack = root.getAsJsonObject("pack");
        if (pack.has("pack_format")) return pack.get("pack_format").getAsInt();
        if (pack.has("format")) return pack.get("format").getAsInt();
        return 0;
    }

    /**
     * Reads the pack description as a plain string, defaulting to empty when the value is a
     * structured text component the loader does not interpret.
     */
    private static @NotNull String readDescription(@NotNull JsonObject root) {
        if (!root.has("pack") || !root.get("pack").isJsonObject()) return "";
        JsonObject pack = root.getAsJsonObject("pack");
        if (!pack.has("description")) return "";
        JsonElement desc = pack.get("description");
        try {
            return desc.isJsonPrimitive() ? desc.getAsString() : "";
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            return "";
        }
    }

    /**
     * Walks {@code root.overlays.entries} (when present) and parses each entry into an
     * {@link Overlay}. Entries missing {@code directory} or {@code formats} are silently skipped;
     * malformed format specs throw via {@link #parseFormats}.
     */
    private static @NotNull ConcurrentList<Overlay> readOverlays(@NotNull JsonObject root, @NotNull String packId) {
        if (!root.has("overlays") || !root.get("overlays").isJsonObject()) return Concurrent.newList();
        JsonObject overlays = root.getAsJsonObject("overlays");
        if (!overlays.has("entries") || !overlays.get("entries").isJsonArray()) return Concurrent.newList();

        JsonArray entries = overlays.getAsJsonArray("entries");
        ArrayList<Overlay> parsed = new ArrayList<>();
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) continue;
            JsonObject entryObj = entry.getAsJsonObject();
            if (!entryObj.has("directory") || !entryObj.has("formats")) continue;
            String directory = entryObj.get("directory").getAsString();
            FormatSpec spec = parseFormats(entryObj.get("formats"), packId);
            parsed.add(new Overlay(directory, spec));
        }
        return Concurrent.adoptList(parsed).toUnmodifiable();
    }

    /**
     * Parses a single {@code formats} entry. Accepts the bare-integer, length-2-array, and
     * {@code {min_inclusive, max_inclusive}} object encodings; rejects malformed entries with a
     * pack-id-bearing exception so bad packs fail loudly rather than silently dropping their
     * overlays.
     */
    private static @NotNull FormatSpec parseFormats(@NotNull JsonElement formats, @NotNull String packId) {
        if (formats.isJsonPrimitive() && formats.getAsJsonPrimitive().isNumber())
            return new FormatSpec.IntSpec(formats.getAsInt());

        if (formats.isJsonArray()) {
            JsonArray arr = formats.getAsJsonArray();
            if (arr.size() != 2)
                throw new PipelineException("Pack '%s' has overlay 'formats' array of size %d (expected 2)", packId, arr.size());
            return new FormatSpec.RangeSpec(arr.get(0).getAsInt(), arr.get(1).getAsInt());
        }

        if (formats.isJsonObject()) {
            JsonObject obj = formats.getAsJsonObject();
            if (!obj.has("min_inclusive") || !obj.has("max_inclusive"))
                throw new PipelineException("Pack '%s' overlay 'formats' object missing min_inclusive/max_inclusive", packId);
            return new FormatSpec.RangeSpec(obj.get("min_inclusive").getAsInt(), obj.get("max_inclusive").getAsInt());
        }

        throw new PipelineException("Pack '%s' has unrecognised overlay 'formats' encoding", packId);
    }

}
