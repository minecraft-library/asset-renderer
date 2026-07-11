package lib.minecraft.renderer.visual;

import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage assertion for the entity parity sweep's variant-aware {@link ParityRefMapping} (axis-unification
 * #3): the mapping must never silently drop a subject the legacy {@code java_keys ∩ vanilla_keys}
 * enumeration compared, and it must ADD the variant-superset families whose only harness reference is the
 * plain base. Guards the sweep's subject enumeration so option-encoding {@code variant} does not quietly
 * shrink coverage.
 *
 * <p>Reads the harness reference PNGs from the gitignored {@code cache/} sweep output; skips when that
 * cache is not warm (a fresh checkout) - the coverage guarantee only means anything against real refs.
 */
final class ParityRefMappingTest {

    private static final Path VANILLA_DIR = Path.of("cache/asset-renderer/vanilla/26.1/references/entities");
    private static final String VANILLA_PREFIX = "minecraft__";

    @Test
    @DisplayName("the mapping is a superset of the legacy intersection and adds the variant supersets")
    void coversLegacyIntersectionAndSupersets() throws IOException {
        assumeTrue(Files.isDirectory(VANILLA_DIR), "vanilla references not warm - run renderVanillaReferences");
        Set<String> javaKeys = new TreeSet<>(EntityModelLoader.load().keySet());
        Set<String> vanillaKeys = vanillaRefIds();
        assumeTrue(!vanillaKeys.isEmpty(), "no harness reference PNGs present");

        ParityRefMapping mapping = ParityRefMapping.load();
        assertThat("v2 variant families loaded", mapping.hasVariantFamilies(), is(true));

        List<ParityRefMapping.Subject> subjects = mapping.resolve(javaKeys, vanillaKeys);
        Set<String> comparedRefs = subjects.stream().map(ParityRefMapping.Subject::refId).collect(Collectors.toCollection(TreeSet::new));

        // 1. No coverage regression: every legacy-intersection subject stays compared.
        Set<String> legacy = ParityRefMapping.legacyIntersection(javaKeys, vanillaKeys);
        assertThat("no legacy-compared subject silently dropped",
            comparedRefs.containsAll(legacy), is(true));

        // 2. The variant-superset families (plain-ref-only) are now compared via their default coat.
        for (String superset : ParityRefMapping.SUPERSET_FAMILIES)
            if (vanillaKeys.contains(superset))
                assertThat("superset family " + superset + " is compared", comparedRefs, hasItem(superset));

        // 3. No harness ref with a resolvable Java target is left on the floor: every ref either maps to
        //    a subject or is a deduplicated plain family ref (its coat compared under a per-variant ref).
        assertThat("no harness ref with a Java target is dropped",
            mapping.unresolved(javaKeys, vanillaKeys), is(empty()));

        // 4. Every deduplicated plain ref is redundant, not a drop: its default coat is compared under a
        //    per-variant ref (mooshroom's plain ref, whose coat is mooshroom_red).
        for (String deduped : mapping.deduplicated(vanillaKeys))
            assertThat("deduped plain ref " + deduped + " has a per-variant coat compared",
                comparedRefs.stream().anyMatch(r -> r.startsWith(deduped + "_")), is(true));

        // 5. The mapping grows coverage past the legacy intersection (the supersets are net-new).
        assertThat("the mapping adds the variant supersets", comparedRefs.size() >= legacy.size(), is(true));
    }

    @Test
    @DisplayName("every subject drives an entity id the pipeline actually loads")
    void everySubjectHasALoadedEntityId() throws IOException {
        assumeTrue(Files.isDirectory(VANILLA_DIR), "vanilla references not warm");
        Set<String> javaKeys = new TreeSet<>(EntityModelLoader.load().keySet());
        Set<String> vanillaKeys = vanillaRefIds();
        assumeTrue(!vanillaKeys.isEmpty(), "no harness reference PNGs present");

        ParityRefMapping mapping = ParityRefMapping.load();
        List<ParityRefMapping.Subject> subjects = mapping.resolve(javaKeys, vanillaKeys);
        // Every ref becomes exactly one of: a subject, a deduplicated plain ref, or unresolved.
        assertThat("subjects + deduped + unresolved partition the refs",
            subjects.size() + mapping.deduplicated(vanillaKeys).size() + mapping.unresolved(javaKeys, vanillaKeys).size(),
            is(vanillaKeys.size()));
        assertThat("subjects were produced", subjects, hasSize(vanillaKeys.size() - mapping.deduplicated(vanillaKeys).size()));
        assertThat("every subject renders a loaded entity id",
            subjects.stream().allMatch(s -> javaKeys.contains(s.entityId())), is(true));
    }

    private static @org.jetbrains.annotations.NotNull Set<String> vanillaRefIds() throws IOException {
        try (Stream<Path> stream = Files.list(VANILLA_DIR)) {
            TreeSet<String> ids = new TreeSet<>();
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(VANILLA_PREFIX) || !name.endsWith(".png")) return;
                ids.add("minecraft:" + name.substring(VANILLA_PREFIX.length(), name.length() - ".png".length()));
            });
            return ids;
        }
    }
}
