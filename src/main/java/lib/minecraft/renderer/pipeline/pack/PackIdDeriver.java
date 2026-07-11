package lib.minecraft.renderer.pipeline.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.exception.PipelineException;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

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
 * then a short ({@code <= 5}-word) mcmeta description, then the synthetic constant {@code pack}. Across
 * the supply order, the reserved ids {@link PackId#VANILLA} and {@link PackId#MINECRAFT} are pre-taken,
 * the first pack claiming an id keeps it bare, and every later collider gets a letter-ordinal suffix
 * ({@code -b}, {@code -c}, ...) with a loud warning naming both sources - never a silent shadow.
 * Supplying the identical source path twice is an error, not a collision.
 */
@UtilityClass
public final class PackIdDeriver {

    /**
     * The id-heuristic version, bumped whenever the derivation changes so cached pack directories
     * re-key predictably.
     */
    public static final int HEURISTIC_VERSION = 1;

    private static final @NotNull PackId SYNTHETIC = new PackId("pack");
    private static final @NotNull String[] EXTENSIONS = { ".zip", ".cats" };
    private static final @NotNull Pattern SECTION_CODE = Pattern.compile("§.", Pattern.DOTALL);
    private static final @NotNull Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_DESCRIPTION_WORDS = 5;

    /**
     * Picks the preferred (pre-collision) id for one pack via the source ladder.
     *
     * @param sources the pack's naming inputs
     * @return the preferred id, the winning rung, and the raw string that produced it
     */
    public static @NotNull Preferred preferred(@NotNull PackNameSources sources) {
        String stem = stripExtensions(sources.fileName());
        Optional<PackId> byFile = PackId.normalize(stem);
        if (byFile.isPresent()) return new Preferred(byFile.get(), Rung.FILENAME, stem);

        if (sources.licenseTitleLine().isPresent()) {
            String line = dropLicenseTokens(sources.licenseTitleLine().get());
            Optional<PackId> byLicense = PackId.normalize(line);
            if (byLicense.isPresent()) return new Preferred(byLicense.get(), Rung.LICENSE, line);
        }

        if (sources.description().isPresent()) {
            String desc = sources.description().get();
            if (isUsableDescription(desc)) {
                Optional<PackId> byDescription = PackId.normalize(desc);
                if (byDescription.isPresent()) return new Preferred(byDescription.get(), Rung.DESCRIPTION, desc);
            }
        }

        return new Preferred(SYNTHETIC, Rung.SYNTHETIC, SYNTHETIC.value());
    }

    /**
     * Assigns final ids across a supply-ordered set of packs, resolving collisions with letter-ordinal
     * suffixes and pre-seeding the reserved ids as taken.
     *
     * @param supplyOrder the pack sources in supply (priority) order
     * @return one assignment per source, in the same order
     * @throws PipelineException if the identical source path is supplied more than once
     */
    public static @NotNull ConcurrentList<Assignment> assign(@NotNull List<PackNameSources> supplyOrder) {
        Set<String> taken = new HashSet<>();
        taken.add(PackId.VANILLA.value());
        taken.add(PackId.MINECRAFT.value());
        Map<String, Path> takenBy = new HashMap<>();
        Set<Path> seenSources = new HashSet<>();
        List<Assignment> out = new ArrayList<>();

        for (PackNameSources sources : supplyOrder) {
            if (!seenSources.add(sources.source()))
                throw new PipelineException("Pack source '%s' supplied more than once", sources.source());

            Preferred preferred = preferred(sources);
            String base = preferred.id().value();
            String candidate = base;
            int ordinal = 1;
            while (taken.contains(candidate)) {
                ordinal++;
                candidate = base + "-" + columnName(ordinal);
            }

            boolean collided = !candidate.equals(base);
            if (collided) {
                Object earlier = takenBy.getOrDefault(base, Path.of("(reserved id)"));
                System.err.printf("Pack id collision: '%s' already claimed by source '%s'; source '%s' assigned '%s'%n",
                    base, earlier, sources.source(), candidate);
            }

            taken.add(candidate);
            takenBy.put(candidate, sources.source());
            out.add(new Assignment(new PackId(candidate), preferred.rung(), preferred.rawName(),
                collided ? Optional.of(new PackId(base)) : Optional.empty()));
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

    /** Which naming source a preferred id came from, coarsest (most reliable) first. */
    public enum Rung { FILENAME, LICENSE, DESCRIPTION, SYNTHETIC }

    /**
     * A pack's preferred id before collision resolution.
     *
     * @param id the normalized preferred id
     * @param rung the ladder rung that produced it
     * @param rawName the raw string the rung normalized
     */
    public record Preferred(@NotNull PackId id, @NotNull Rung rung, @NotNull String rawName) {}

    /**
     * A pack's final assigned id after collision resolution.
     *
     * @param id the final id, suffixed when it collided
     * @param rung the ladder rung that produced the preferred id
     * @param rawName the raw string the rung normalized
     * @param collisionBase the un-suffixed base id, present only when a collision suffix was applied
     */
    public record Assignment(
        @NotNull PackId id,
        @NotNull Rung rung,
        @NotNull String rawName,
        @NotNull Optional<PackId> collisionBase
    ) {}

}
