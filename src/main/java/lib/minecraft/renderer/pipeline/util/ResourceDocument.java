package lib.minecraft.renderer.pipeline.util;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.PipelineException;
import dev.simplified.gson.exception.JsonException;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Envelope-aware reader for a bundled {@code *.json} asset resource.
 *
 * <p>{@link #open(byte[], Diagnostics)} parses the payload, asserts the {@code format == 2}
 * discriminator, and surfaces a {@code source_version} mismatch against the expected
 * {@value #EXPECTED_SOURCE_VERSION} to the supplied {@link Diagnostics} as a warning rather than a
 * silent proceed. The parsed node is exposed through {@link #payload()} for structural reads and
 * {@link #as(Class)} for whole-document deserialisation into a typed DTO.
 *
 * <p>Reading reuses the {@link JsonTree} read surface rather than a bespoke navigator.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceDocument {

    /** The version stamp every 26.1 resource carries; mirrors {@code ClientOptions.version}. */
    static final @NotNull String EXPECTED_SOURCE_VERSION = "26.1";

    /** The {@code format} discriminator every resource carries. */
    private static final int EXPECTED_FORMAT = 2;

    /** Sentinel returned by {@link JsonTree#getInt} when the {@code format} member is absent. */
    private static final int NO_FORMAT = -1;

    /** Shared Gson configured with the project defaults, used by {@link #as(Class)}. */
    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    private final @NotNull JsonTree payload;
    private final @NotNull ResourceEnvelope envelope;

    /**
     * Parses and envelope-validates a resource's UTF-8 bytes.
     *
     * @param utf8 the raw resource bytes
     * @param diagnostics the scope a {@code source_version} mismatch is warned to
     * @return the validated document
     * @throws PipelineException if the bytes are not parseable JSON, or carry a {@code format} other
     *     than {@value #EXPECTED_FORMAT}
     */
    public static @NotNull ResourceDocument open(byte @NotNull [] utf8, @NotNull Diagnostics diagnostics) {
        JsonTree payload;
        try {
            payload = JsonTree.parse(utf8);
        } catch (JsonException ex) {
            throw new PipelineException(ex, "Malformed JSON resource (%d bytes)", utf8.length);
        }

        int format = payload.getInt("format", NO_FORMAT);
        if (format != EXPECTED_FORMAT)
            throw new PipelineException("Resource declares format '%d', expected '%d'", format, EXPECTED_FORMAT);

        @Nullable String sourceVersion = payload.findString("source_version").orElse(null);
        if (!EXPECTED_SOURCE_VERSION.equals(sourceVersion))
            diagnostics.warn("Resource source_version '%s' does not match expected '%s'", sourceVersion, EXPECTED_SOURCE_VERSION);

        return new ResourceDocument(payload,
            new ResourceEnvelope(payload.findString("//"), format, Optional.ofNullable(sourceVersion)));
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

    /** The validated payload node, carrying the tooling {@link JsonTree} read surface. */
    public @NotNull JsonTree payload() {
        return this.payload;
    }

    /** The parsed envelope - the {@code //} header, {@code format} discriminator, and {@code source_version} stamp. */
    public @NotNull ResourceEnvelope envelope() {
        return this.envelope;
    }
}
