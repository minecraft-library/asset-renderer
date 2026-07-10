package lib.minecraft.renderer.pipeline.load;

import org.jetbrains.annotations.Nullable;

/**
 * The parsed header of a {@code v2/*.json} asset resource - the {@code //} provenance comment, the
 * {@code format} discriminator, and the {@code source_version} stamp.
 *
 * <p>Read off every v2 envelope by {@link V2Document}; never re-emitted, since the consumer side
 * only reads.
 *
 * @param header the {@code //} provenance line, or {@code null} when the resource omits it
 * @param format the {@code format} discriminator ({@code 2} for every shipped v2 resource)
 * @param sourceVersion the {@code source_version} stamp, or {@code null} when the resource omits it
 */
record V2Envelope(@Nullable String header, int format, @Nullable String sourceVersion) {}
