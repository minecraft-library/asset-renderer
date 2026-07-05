package lib.minecraft.renderer.tooling.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared Gson instances and the shared write-envelope for the tooling JSON writers - one place to keep
 * the on-disk formatting identical across every generated {@code renderer/*.json} resource.
 */
@UtilityClass
public class ToolingJson {

    /**
     * Pretty-printing Gson with HTML escaping disabled, carrying the renderer's registered type
     * adapters. Every tooling writer that rewrites a generated resource shares this single instance so
     * their formatting (indentation, {@code <}/{@code >}/{@code &} escaping) can never drift apart.
     */
    public static final @NotNull Gson PRETTY =
        GsonSettings.defaults().mutate().isPrettyPrint().isHtmlEscaping(false).build().create();

    /**
     * Pretty-printing Gson leaving Gson's default HTML-safe escaping ON (writes {@code <}/{@code >}/
     * {@code &}/{@code =}/{@code '} as {@code \\uXXXX}). Used by the diagnostic writers, whose output
     * is scratch (not a bundled resource); kept distinct from {@link #PRETTY} so their escaping stays
     * unchanged.
     */
    public static final @NotNull Gson PRETTY_HTML_SAFE =
        GsonSettings.defaults().mutate().isPrettyPrint().build().create();

    /**
     * Writes one bundled snapshot resource in the shared envelope every generator uses: a {@code "//"}
     * header comment, the {@code source_version} of the jar it was walked from, then the payload under
     * its member key, pretty-printed via {@link #PRETTY} and terminated with the platform line
     * separator. Creates the parent directory if missing and logs the written path to stdout.
     * Centralising the envelope keeps the header, property order, formatting, and trailing newline
     * byte-identical across every {@code renderer/*.json}.
     *
     * @param outputPath the resource path to write
     * @param headerComment the {@code "//"} header noting the source and refresh task
     * @param version the source client-jar version, stored under {@code source_version}
     * @param memberKey the top-level key the payload is stored under ({@code tints} / {@code blocks} / ...)
     * @param payload the resource body (a {@code JsonArray} or {@code JsonObject})
     * @throws IOException if creating the directory or writing the file fails
     */
    public static void writeResource(
        @NotNull Path outputPath,
        @NotNull String headerComment,
        @NotNull String version,
        @NotNull String memberKey,
        @NotNull JsonElement payload
    ) throws IOException {
        JsonObject root = header(headerComment, version);
        root.add(memberKey, payload);

        writeJson(outputPath, root, PRETTY);
        System.out.println("Wrote " + outputPath.toAbsolutePath());
    }

    /**
     * Builds a JSON root seeded with the shared header every generated resource carries: the
     * {@code "//"} provenance comment followed by the {@code source_version} of the client jar it was
     * generated from. Single-member snapshot writers go through {@link #writeResource}; richer,
     * multi-member writers (the entity diagnostics) seed their root here, add their own members, and
     * hand it to {@link #writeJson}. Centralising the header keeps its keys, order, and naming
     * identical across every writer.
     *
     * @param comment the {@code "//"} provenance comment noting the source and refresh task
     * @param version the source client-jar version, stored under {@code source_version}
     * @return a fresh root carrying only the {@code "//"} + {@code source_version} header
     */
    public static @NotNull JsonObject header(@NotNull String comment, @NotNull String version) {
        JsonObject root = new JsonObject();
        root.addProperty("//", comment);
        root.addProperty("source_version", version);
        return root;
    }

    /**
     * Writes a JSON root to disk in the low-level shape every tooling generator shares: pretty-printed
     * via the given Gson and terminated with the platform line separator, creating the parent directory
     * if missing. This is the byte-level write beneath both the snapshot {@link #writeResource} envelope
     * (which adds the {@code //} + {@code source_version} header) and the entity writers (whose roots
     * carry no {@code source_version}); centralising it keeps formatting + the trailing newline
     * identical across every generated file.
     *
     * @param outputPath the file path to write
     * @param root the JSON root document to serialise
     * @param gson the Gson to serialise with ({@link #PRETTY} or {@link #PRETTY_HTML_SAFE})
     * @throws IOException if creating the directory or writing the file fails
     */
    public static void writeJson(@NotNull Path outputPath, @NotNull JsonObject root, @NotNull Gson gson) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, gson.toJson(root) + System.lineSeparator());
    }

}
