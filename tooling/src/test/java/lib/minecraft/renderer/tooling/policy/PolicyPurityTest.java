package lib.minecraft.renderer.tooling.policy;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical no-fetch contract of the policy SPI: every {@link NavigationPolicy}
 * implementor - the flow-local {@code *Policies} enums - may declare facts and coordinates
 * but never fetch, so their sources may not import {@code ClassKit}, {@code ClassNodeCache} or
 * any {@code org.objectweb.asm} type. The implementors are discovered by walking the tooling
 * main tree for {@code *Policies.java}, so a newly added enum is covered without being listed
 * anywhere.
 *
 * <p>Two more mechanical scans ride the same walk: {@code java.util.zip} is the jar reader's
 * alone, and a {@code "minecraft:"} / {@code "net/minecraft"} string literal outside
 * {@code VanillaSourceClasses} and the policy enums is a violation. The reflective scan is the
 * third - every policy constant's {@code provenance} field must be non-blank.
 */
@DisplayName("policy purity: *Policies sources never import the fetch surface, zip stays in the jar reader")
class PolicyPurityTest {

    private static final @NotNull Path TOOLING_MAIN = Path.of("tooling/src/main/java/lib/minecraft/renderer/tooling");

    /** Filename suffix selecting the flow-local policy enums. */
    private static final @NotNull String POLICY_SOURCE = "Policies.java";

    /** Filename suffix selecting every tooling main source. */
    private static final @NotNull String ANY_SOURCE = ".java";

    /** Every banned import, the sources it is banned in, and the one filename exempt from it. */
    private static final @NotNull List<BannedImport> BANNED_IMPORTS = List.of(
        new BannedImport("import org.objectweb.asm", POLICY_SOURCE, Optional.empty()),
        new BannedImport("import lib.minecraft.renderer.tooling.kernel.ClassKit", POLICY_SOURCE, Optional.empty()),
        new BannedImport("import lib.minecraft.renderer.tooling.kernel.ClassNodeCache", POLICY_SOURCE, Optional.empty()),
        new BannedImport("import java.util.zip", ANY_SOURCE, Optional.of("ClassNodeCache.java")));

    /** String-literal fragments legal only inside the two sanctioned hard-coding homes. */
    private static final @NotNull List<String> VANILLA_LITERAL_MARKS = List.of("minecraft:", "net/minecraft");

    /** A double-quoted Java string literal (no escaped quotes exist in tooling sources). */
    private static final @NotNull Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\"");

    @Test
    @DisplayName("scan mechanics: clean and dirty dummy sources classify correctly")
    void mechanicsProvenOnDummies() {
        String clean = """
            package lib.minecraft.renderer.tooling.entity;
            import lib.minecraft.renderer.tooling.policy.Navigation;
            import lib.minecraft.renderer.tooling.policy.NavigationPolicy;
            enum DummyPolicies implements NavigationPolicy { ROW; }
            """;
        String dirty = """
            package lib.minecraft.renderer.tooling.entity;
            import lib.minecraft.renderer.tooling.kernel.ClassKit;
            import org.objectweb.asm.tree.ClassNode;
            enum DummyPolicies implements NavigationPolicy { ROW; }
            """;
        assertEquals(List.of(), bannedImportsIn(clean, bansFor(POLICY_SOURCE)));
        assertEquals(2, bannedImportsIn(dirty, bansFor(POLICY_SOURCE)).size());
    }

    @Test
    @DisplayName("every tooling *Policies source is pure")
    void everyPolicySourceIsPure() throws IOException {
        assertNoBannedImports(POLICY_SOURCE, "policy purity violations");
    }

    @Test
    @DisplayName("java.util.zip is imported by the jar reader alone")
    void zipStaysInTheJarReader() throws IOException {
        assertNoBannedImports(ANY_SOURCE, "java.util.zip outside the jar reader");
    }

    @Test
    @DisplayName("vanilla literals live only in VanillaSourceClasses and the policy enums")
    void vanillaLiteralsOnlyInSanctionedHomes() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING_MAIN)) {
            sources.filter(path -> path.getFileName().toString().endsWith(ANY_SOURCE))
                .filter(path -> !path.getFileName().toString().equals("VanillaSourceClasses.java"))
                .filter(path -> !path.getFileName().toString().endsWith(POLICY_SOURCE))
                .forEach(path -> {
                    try {
                        int lineNumber = 0;
                        for (String line : Files.readString(path).lines().toList()) {
                            lineNumber++;
                            String trimmed = line.trim();
                            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
                            for (String mark : VANILLA_LITERAL_MARKS)
                                if (STRING_LITERAL.matcher(line).results()
                                    .anyMatch(match -> match.group().contains(mark)))
                                    violations.add(path.getFileName() + ":" + lineNumber + ": " + trimmed);
                        }
                    } catch (IOException ex) {
                        violations.add(path + ": unreadable (" + ex + ")");
                    }
                });
        }
        assertTrue(violations.isEmpty(), "vanilla literals outside sanctioned homes:\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("every policy constant carries non-blank provenance")
    void everyPolicyConstantCarriesProvenance() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING_MAIN)) {
            for (Path path : sources.filter(source -> source.getFileName().toString().endsWith(POLICY_SOURCE)).toList()) {
                Class<?> policy = classOf(path);
                Object[] constants = policy.getEnumConstants();
                if (constants == null || constants.length == 0) {
                    violations.add(policy.getName() + ": not an enum / no constants");
                    continue;
                }
                try {
                    Field provenance = policy.getDeclaredField("provenance");
                    provenance.setAccessible(true);
                    for (Object constant : constants) {
                        Object value = provenance.get(constant);
                        if (!(value instanceof String text) || text.isBlank())
                            violations.add(policy.getSimpleName() + "." + constant + ": blank provenance");
                    }
                } catch (ReflectiveOperationException ex) {
                    violations.add(policy.getName() + ": no readable provenance field (" + ex + ")");
                }
            }
        }
        assertTrue(violations.isEmpty(), "provenance violations:\n" + String.join("\n", violations));
    }

    /**
     * Fails when a tooling main source the scope selects declares an import the table bans in it.
     *
     * @param scope filename suffix selecting both the sources walked and the bans applied
     * @param what what the collected violations are, for the failure message
     * @throws IOException if the tooling main tree cannot be walked
     */
    private static void assertNoBannedImports(@NotNull String scope, @NotNull String what) throws IOException {
        List<BannedImport> bans = bansFor(scope);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING_MAIN)) {
            sources.filter(path -> path.getFileName().toString().endsWith(scope))
                .forEach(path -> {
                    List<BannedImport> covering = bans.stream().filter(ban -> ban.covers(path)).toList();
                    try {
                        for (String banned : bannedImportsIn(Files.readString(path), covering))
                            violations.add(path.getFileName() + ": " + banned);
                    } catch (IOException ex) {
                        violations.add(path + ": unreadable (" + ex + ")");
                    }
                });
        }
        assertTrue(violations.isEmpty(), what + ":\n" + String.join("\n", violations));
    }

    /**
     * Selects the bans the table declares for one scope.
     *
     * @param scope the filename suffix a ban declares
     * @return the bans carrying that scope
     */
    private static @NotNull List<BannedImport> bansFor(@NotNull String scope) {
        return BANNED_IMPORTS.stream().filter(ban -> ban.scope().equals(scope)).toList();
    }

    /**
     * Collects the import lines the given bans reject in one source's text.
     *
     * @param source the whole text of one Java source file
     * @param bans the bans covering that file
     * @return the offending import lines, trimmed, in encounter order
     */
    private static @NotNull List<String> bannedImportsIn(@NotNull String source, @NotNull List<BannedImport> bans) {
        List<String> found = new ArrayList<>();
        for (String line : source.lines().toList()) {
            String trimmed = line.trim();
            for (BannedImport ban : bans)
                if (trimmed.startsWith(ban.prefix())) found.add(trimmed);
        }
        return found;
    }

    /**
     * Loads the class a tooling main source file compiles to.
     *
     * @param source the source file's path under the tooling main tree
     * @return the loaded class
     */
    private static @NotNull Class<?> classOf(@NotNull Path source) {
        String relative = TOOLING_MAIN.relativize(source).toString()
            .replace('\\', '/')
            .replace(".java", "")
            .replace('/', '.');
        try {
            return Class.forName("lib.minecraft.renderer.tooling." + relative);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Policy source without a loadable class: " + source, ex);
        }
    }

    /**
     * One banned import prefix and where it is banned.
     *
     * @param prefix the import-statement prefix an in-scope source may not declare
     * @param scope filename suffix selecting the sources the ban covers
     * @param exempt the one filename sanctioned to declare it, empty when none is
     */
    private record BannedImport(@NotNull String prefix, @NotNull String scope, @NotNull Optional<String> exempt) {

        /**
         * Tests whether this ban covers the source file at the given path.
         *
         * @param source the source file's path
         * @return whether the ban applies to it
         */
        boolean covers(@NotNull Path source) {
            String name = source.getFileName().toString();
            return name.endsWith(scope) && exempt.filter(name::equals).isEmpty();
        }
    }

}
