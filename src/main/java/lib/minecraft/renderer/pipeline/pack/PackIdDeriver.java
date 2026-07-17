package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.exception.PipelineException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives stable {@link PackId}s from a pack's naming inputs, then resolves collisions across a
 * supply-ordered set of packs.
 *
 * <p>A four-rung ladder picks each pack's preferred id, taking the first rung whose normalized output
 * is non-empty (a clean filename is never second-guessed by a fancier description): the filename stem
 * with its {@code .zip}/{@code .cats} extension chain stripped, then the {@code LICENSE} title line,
 * then a short ({@code <= 5}-word) mcmeta description, then the synthetic constant {@code pack}. Each
 * rung reads its own naming input off the {@link Naming} handle, and the reads are lazy - the
 * {@code LICENSE} slurp and the mcmeta probe run only when an earlier rung fails. Across the supply
 * order, the reserved ids {@link PackId#VANILLA} and {@link PackId#MINECRAFT} are pre-taken, the first
 * pack claiming an id keeps it bare, and every later collider gets a letter-ordinal suffix
 * ({@code -b}, {@code -c}, ...) with a loud warning naming both sources - never a silent shadow.
 * Supplying the identical source path twice is an error, not a collision.
 */
@UtilityClass
final class PackIdDeriver {

    private static final @NotNull PackId SYNTHETIC = new PackId("pack");
    private static final @NotNull String[] EXTENSIONS = { ".zip", ".cats" };
    private static final @NotNull Pattern SECTION_CODE = Pattern.compile("§.", Pattern.DOTALL);
    private static final @NotNull Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_DESCRIPTION_WORDS = 5;

    /**
     * Picks the preferred (pre-collision) id for one pack, walking the ladder and taking the first rung
     * that yields a usable id (the synthetic rung always does, so the ladder never falls through).
     *
     * @param naming the pack's naming handle
     * @return the preferred id, the winning rung, and the raw string that produced it
     */
    static @NotNull Candidate preferred(@NotNull Naming naming) {
        for (Rung rung : Rung.values()) {
            Optional<Candidate> candidate = rung.derive(naming);
            if (candidate.isPresent()) return candidate.get();
        }
        throw new IllegalStateException("Pack id ladder exhausted without the synthetic fallback");
    }

    /**
     * Assigns final ids across a supply-ordered set of packs, resolving collisions with letter-ordinal
     * suffixes and pre-seeding the reserved ids as taken.
     *
     * @param supplyOrder the pack naming handles in supply (priority) order
     * @return one id per source, in the same order
     * @throws PipelineException if the identical source path is supplied more than once
     */
    static @NotNull ConcurrentList<PackId> assign(@NotNull List<Naming> supplyOrder) {
        Set<String> taken = new HashSet<>();
        taken.add(PackId.VANILLA.value());
        taken.add(PackId.MINECRAFT.value());
        Map<String, Path> takenBy = new HashMap<>();
        Set<Path> seenSources = new HashSet<>();
        List<PackId> out = new ArrayList<>();

        for (Naming naming : supplyOrder) {
            if (!seenSources.add(naming.source()))
                throw new PipelineException("Pack source '%s' supplied more than once", naming.source());

            String base = preferred(naming).id().value();
            String candidate = base;
            int ordinal = 1;
            while (taken.contains(candidate)) {
                ordinal++;
                candidate = base + "-" + columnName(ordinal);
            }

            if (!candidate.equals(base)) {
                Object earlier = takenBy.getOrDefault(base, Path.of("(reserved id)"));
                System.err.printf("Pack id collision: '%s' already claimed by source '%s'; source '%s' assigned '%s'%n",
                    base, earlier, naming.source(), candidate);
            }

            taken.add(candidate);
            takenBy.put(candidate, naming.source());
            out.add(new PackId(candidate));
        }
        return Concurrent.adoptList(out).toUnmodifiable();
    }

    /** Strips the trailing {@code .zip}/{@code .cats} extension chain, case-insensitively and iteratively. */
    private static @NotNull String stripExtensions(@NotNull String name) {
        String s = name;
        boolean changed = true;
        while (changed) {
            changed = false;
            String lower = s.toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS)
                if (lower.endsWith(ext)) {
                    s = s.substring(0, s.length() - ext.length());
                    changed = true;
                    break;
                }
        }
        return s;
    }

    /** Drops a trailing {@code RESOURCE PACK LICENSE} or {@code LICENSE} token from a license title line. */
    private static @NotNull String dropLicenseTokens(@NotNull String line) {
        String s = line.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("resource pack license")) s = s.substring(0, s.length() - "resource pack license".length());
        else if (lower.endsWith("license")) s = s.substring(0, s.length() - "license".length());
        return s.strip();
    }

    /** A description is usable as a name only when, with formatting codes stripped, it is non-empty and short. */
    private static boolean isUsableDescription(@NotNull String description) {
        String stripped = SECTION_CODE.matcher(description).replaceAll("").replace("§", "").strip();
        if (stripped.isEmpty()) return false;
        return WHITESPACE.split(stripped).length <= MAX_DESCRIPTION_WORDS;
    }

    /** The first non-blank line of a root {@code LICENSE}, if the container ships one. */
    private static @NotNull Optional<String> licenseTitleLine(@NotNull PackContainer container) {
        return container.bytes("LICENSE")
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .flatMap(text -> text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst());
    }

    /** The flattened, non-blank {@code pack.mcmeta} description, if any. */
    private static @NotNull Optional<String> description(@NotNull PackContainer container) {
        return container.bytes("pack.mcmeta")
            .map(bytes -> MCMeta.parse(new String(bytes, StandardCharsets.UTF_8), new ResourceId("probe", "pack")))
            .flatMap(MCMeta::pack)
            .map(pack -> pack.description().plain())
            .filter(plain -> !plain.isBlank());
    }

    /** Excel-style bijective base-26 column name: {@code 1->a}, {@code 2->b}, ..., {@code 26->z}, {@code 27->aa}. */
    private static @NotNull String columnName(int ordinal) {
        StringBuilder sb = new StringBuilder();
        int n = ordinal;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('a' + (n % 26)));
            n /= 26;
        }
        return sb.toString();
    }

    /**
     * A lazy handle over one pack's naming inputs - the source path (always available) and its detected
     * container, whose bytes are read only when a rung asks for them.
     *
     * @param source the pack source as supplied - a directory or a {@code .zip} / {@code .cats} /
     *     {@code .cats.zip} archive; its filename drives ladder rung 1
     * @param container the detected container the {@code LICENSE} and {@code pack.mcmeta} reads go through
     */
    record Naming(@NotNull Path source, @NotNull PackContainer container) {

        /** The pack source's filename, as it appears on disk (extension chain intact). */
        @NotNull String fileName() {
            Path name = this.source.getFileName();
            return name == null ? "" : name.toString();
        }
    }

    /**
     * The four naming sources, coarsest (most reliable) first. Each constant reads its own raw naming
     * string off the {@link Naming} handle, and {@link #derive} normalizes it into a {@link Candidate}
     * when the read yields a usable id.
     */
    enum Rung {

        /** The filename stem, with its {@code .zip}/{@code .cats} extension chain stripped. */
        FILENAME {
            @Override
            @NotNull Optional<String> raw(@NotNull Naming naming) {
                return Optional.of(stripExtensions(naming.fileName()));
            }
        },

        /** The first non-blank {@code LICENSE} line, its trailing license tokens dropped. */
        LICENSE {
            @Override
            @NotNull Optional<String> raw(@NotNull Naming naming) {
                return licenseTitleLine(naming.container()).map(PackIdDeriver::dropLicenseTokens);
            }
        },

        /** A short ({@code <= 5}-word) {@code pack.mcmeta} description. */
        DESCRIPTION {
            @Override
            @NotNull Optional<String> raw(@NotNull Naming naming) {
                return description(naming.container()).filter(PackIdDeriver::isUsableDescription);
            }
        },

        /** The synthetic constant {@code pack} - the always-present fallback. */
        SYNTHETIC {
            @Override
            @NotNull Optional<String> raw(@NotNull Naming naming) {
                return Optional.of(PackIdDeriver.SYNTHETIC.value());
            }
        };

        /** This rung's raw naming string, empty when the rung's source is absent. */
        abstract @NotNull Optional<String> raw(@NotNull Naming naming);

        /**
         * Normalizes this rung's raw string into a candidate id, empty when the source is absent or
         * normalizes to nothing.
         *
         * @param naming the pack's naming handle
         * @return the candidate this rung yields, or empty
         */
        final @NotNull Optional<Candidate> derive(@NotNull Naming naming) {
            return raw(naming).flatMap(raw -> PackId.normalize(raw).map(id -> new Candidate(id, this, raw)));
        }
    }

    /**
     * A pack's preferred id before collision resolution.
     *
     * @param id the normalized preferred id
     * @param rung the ladder rung that produced it
     * @param rawName the raw string the rung normalized
     */
    record Candidate(@NotNull PackId id, @NotNull Rung rung, @NotNull String rawName) {}

}
