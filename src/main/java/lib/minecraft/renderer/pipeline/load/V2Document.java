package lib.minecraft.renderer.pipeline.load;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.ToolingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Envelope-aware reader for a {@code v2/*.json} asset resource.
 *
 * <p>{@link #open(byte[], Diagnostics)} parses the payload, asserts the {@code format == 2}
 * discriminator, and surfaces a {@code source_version} mismatch against the expected
 * {@value #EXPECTED_SOURCE_VERSION} to the supplied {@link Diagnostics} as a warning rather than a
 * silent proceed. The parsed node is exposed through {@link #payload()} for structural reads and
 * {@link #as(Class)} for whole-document deserialisation into a typed DTO.
 *
 * <p>Reading reuses the tooling2 {@link JsonNode} read surface rather than a bespoke navigator; the
 * {@code pipeline -> tooling2.kernel} edge is sanctioned pending relocation of the shared JSON core
 * to a neutral package.
 */
public final class V2Document {

    /** The version stamp every 26.1 v2 resource carries; mirrors {@code PipelineOptions.version}. */
    static final @NotNull String EXPECTED_SOURCE_VERSION = "26.1";

    /** The {@code format} discriminator every v2 resource carries. */
    private static final int EXPECTED_FORMAT = 2;

    /** Sentinel returned by {@link JsonNode#getInt} when the {@code format} member is absent. */
    private static final int NO_FORMAT = -1;

    /** Shared Gson configured with the project defaults, used by {@link #as(Class)}. */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private final @NotNull JsonNode payload;
    private final @NotNull V2Envelope envelope;

    private V2Document(@NotNull JsonNode payload, @NotNull V2Envelope envelope) {
        this.payload = payload;
        this.envelope = envelope;
    }

    /**
     * Parses and envelope-validates a v2 resource's UTF-8 bytes.
     *
     * @param utf8 the raw resource bytes
     * @param diagnostics the scope a {@code source_version} mismatch is warned to
     * @return the validated document
     * @throws PipelineException if the bytes are not parseable JSON, or carry a {@code format} other
     *     than {@value #EXPECTED_FORMAT}
     */
    public static @NotNull V2Document open(byte @NotNull [] utf8, @NotNull Diagnostics diagnostics) {
        JsonNode payload;
        try {
            payload = JsonNode.parse(utf8);
        } catch (ToolingException ex) {
            throw new PipelineException(ex, "Malformed v2 JSON resource (%d bytes)", utf8.length);
        }

        int format = payload.getInt("format", NO_FORMAT);
        if (format != EXPECTED_FORMAT)
            throw new PipelineException("v2 resource declares format '%d', expected '%d'", format, EXPECTED_FORMAT);

        @Nullable String sourceVersion = payload.getString("source_version");
        if (!EXPECTED_SOURCE_VERSION.equals(sourceVersion))
            diagnostics.warn("v2 resource source_version '%s' does not match expected '%s'", sourceVersion, EXPECTED_SOURCE_VERSION);

        return new V2Document(payload, new V2Envelope(payload.getString("//"), format, sourceVersion));
    }

    /**
     * Deserialises the whole document into a typed DTO via the project Gson. Envelope members
     * ({@code //} / {@code format} / {@code source_version}) the DTO does not declare are ignored.
     *
     * @param type the DTO class to deserialise into
     * @param <T> the DTO type
     * @return the deserialised DTO
     */
    public <T> @NotNull T as(@NotNull Class<T> type) {
        return GSON.fromJson(payload.toGson(), type);
    }

    /** The validated payload node, carrying the tooling2 {@link JsonNode} read surface. */
    public @NotNull JsonNode payload() {
        return this.payload;
    }

    /** The {@code format} discriminator this resource declared. */
    public int format() {
        return this.envelope.format();
    }

    /** The {@code source_version} stamp this resource declared, or {@code null} when absent. */
    public @Nullable String sourceVersion() {
        return this.envelope.sourceVersion();
    }

    /** The {@code //} provenance header this resource declared, or {@code null} when absent. */
    public @Nullable String header() {
        return this.envelope.header();
    }
}
