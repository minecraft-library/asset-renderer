package lib.minecraft.renderer.tooling.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Formats a packed ARGB int as the {@code 0xAARRGGBB} uppercase-hex string the generated
     * resources carry for packed colours ({@code tint_color}, {@code base_tint}). One place for the
     * format so every writer's colour serialisation stays byte-identical.
     *
     * @param argb the packed ARGB colour
     * @return the {@code 0x}-prefixed 8-digit uppercase hex string
     */
    public static @NotNull String hex8(int argb) {
        return String.format("0x%08X", argb);
    }

    /**
     * Starts a fluent {@link Node} over a fresh {@link JsonObject}. The shared JSON-assembly
     * vocabulary for the tooling writers - collapses the {@code new JsonObject(); if (cond)
     * obj.addProperty(...)} shape that recurs through the generators into one legible chain.
     *
     * @return a {@code Node} wrapping a new empty object
     */
    public static @NotNull Node object() {
        return new Node(new JsonObject());
    }

    /**
     * Starts a fluent {@link Node} over an existing {@link JsonObject}, appending to it in place.
     *
     * @param obj the object to append to
     * @return a {@code Node} wrapping {@code obj}
     */
    public static @NotNull Node wrap(@NotNull JsonObject obj) {
        return new Node(obj);
    }

    /**
     * A fluent builder over a {@link JsonObject}, one-to-one with Gson's {@code addProperty}
     * overloads plus conditional (@code putIf}/{@code putIfNotNull}) and packed-colour ({@code
     * putHex}) variants. Every method routes through the identical {@code addProperty} overload the
     * hand-written writers use and appends in call order, so a chain is byte-identical to the
     * equivalent sequence of {@code obj.addProperty(...)} statements ({@link JsonObject} preserves
     * insertion order). Deliberately carries no {@code double}/{@code Number} overload - a {@code
     * Float} and a {@code Double} serialise differently, so numeric values stay {@code float}.
     */
    public static final class Node {

        private final @NotNull JsonObject obj;

        private Node(@NotNull JsonObject obj) {
            this.obj = obj;
        }

        /**
         * Adds a string member.
         *
         * @param key the member name
         * @param value the string value
         * @return this node
         */
        public @NotNull Node put(@NotNull String key, @NotNull String value) {
            obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds an int member.
         *
         * @param key the member name
         * @param value the int value
         * @return this node
         */
        public @NotNull Node put(@NotNull String key, int value) {
            obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a float member.
         *
         * @param key the member name
         * @param value the float value
         * @return this node
         */
        public @NotNull Node put(@NotNull String key, float value) {
            obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a boolean member.
         *
         * @param key the member name
         * @param value the boolean value
         * @return this node
         */
        public @NotNull Node put(@NotNull String key, boolean value) {
            obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds an arbitrary element member (nested object / array).
         *
         * @param key the member name
         * @param value the element to add
         * @return this node
         */
        public @NotNull Node add(@NotNull String key, @NotNull JsonElement value) {
            obj.add(key, value);
            return this;
        }

        /**
         * Adds a string member only when {@code condition} holds.
         *
         * @param condition whether to add the member
         * @param key the member name
         * @param value the string value
         * @return this node
         */
        public @NotNull Node putIf(boolean condition, @NotNull String key, @NotNull String value) {
            if (condition) obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a float member only when {@code condition} holds.
         *
         * @param condition whether to add the member
         * @param key the member name
         * @param value the float value
         * @return this node
         */
        public @NotNull Node putIf(boolean condition, @NotNull String key, float value) {
            if (condition) obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a boolean member only when {@code condition} holds.
         *
         * @param condition whether to add the member
         * @param key the member name
         * @param value the boolean value
         * @return this node
         */
        public @NotNull Node putIf(boolean condition, @NotNull String key, boolean value) {
            if (condition) obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a string member only when {@code value} is non-null.
         *
         * @param key the member name
         * @param value the string value, or {@code null} to skip
         * @return this node
         */
        public @NotNull Node putIfNotNull(@NotNull String key, @Nullable String value) {
            if (value != null) obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a float member only when {@code value} is non-null, preserving the boxed {@code Float}
         * so serialisation matches a plain {@code addProperty(float)}.
         *
         * @param key the member name
         * @param value the float value, or {@code null} to skip
         * @return this node
         */
        public @NotNull Node putIfNotNull(@NotNull String key, @Nullable Float value) {
            if (value != null) obj.addProperty(key, value);
            return this;
        }

        /**
         * Adds a packed ARGB colour member as the {@code 0xAARRGGBB} string (see {@link #hex8}).
         *
         * @param key the member name
         * @param argb the packed ARGB colour
         * @return this node
         */
        public @NotNull Node putHex(@NotNull String key, int argb) {
            obj.addProperty(key, hex8(argb));
            return this;
        }

        /**
         * Adds a packed ARGB colour member as the {@code 0xAARRGGBB} string only when {@code
         * condition} holds.
         *
         * @param condition whether to add the member
         * @param key the member name
         * @param argb the packed ARGB colour
         * @return this node
         */
        public @NotNull Node putHexIf(boolean condition, @NotNull String key, int argb) {
            if (condition) obj.addProperty(key, hex8(argb));
            return this;
        }

        /**
         * The built object (the same instance appended to throughout the chain).
         *
         * @return the underlying object
         */
        public @NotNull JsonObject build() {
            return obj;
        }
    }

}
