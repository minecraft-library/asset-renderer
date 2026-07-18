package lib.minecraft.renderer.pipeline.load;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The parsed header of a bundled {@code *.json} asset resource - the {@code //} provenance comment, the
 * {@code format} discriminator, and the {@code source_version} stamp.
 *
 * <p>Read off every resource envelope by {@link ResourceDocument}; never re-emitted, since the consumer side
 * only reads.
 *
 * @param header the {@code //} provenance line, empty when the resource omits it
 * @param format the {@code format} discriminator ({@code 2} for every shipped resource)
 * @param sourceVersion the {@code source_version} stamp, empty when the resource omits it
 */
public record ResourceEnvelope(@NotNull Optional<String> header, int format, @NotNull Optional<String> sourceVersion) {}
