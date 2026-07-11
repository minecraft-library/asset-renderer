package lib.minecraft.renderer.tooling2.policy;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical no-fetch contract of the policy SPI (SPINE decision 9): every
 * {@code NavigationPolicy} implementor - the flow-local {@code *Policies} enums - may declare
 * facts and coordinates but never fetch, so their sources may not import AsmKit,
 * ClassNodeCache, or any {@code org.objectweb.asm} type.
 *
 * <p>Scaffold session (S2): the scan mechanics are proven on dummy sources; the roster grows
 * one enum per flow session and anything hard-coded outside the SPINE 2.1 roster becomes a
 * failure here from phase 5 on. The doc-12 K9 extensions folded in with the entity policy
 * enums (S6): the vanilla-literal source scan (a {@code "minecraft:"} /
 * {@code "net/minecraft"} string literal outside {@code VanillaSourceClasses} and the policy
 * enums is a roster violation - the C2 stray-accretion regression gate) and the non-blank
 * provenance assert (reflective, over every policy constant's {@code provenance} field).
 */
@DisplayName("policy purity: *Policies sources never import the fetch surface")
class PolicyPurityTest {

    private static final @NotNull Path TOOLING2_MAIN = Path.of("src/main/java/lib/minecraft/renderer/tooling2");

    /** Import prefixes a policy source may never reference (the fetch surface). */
    private static final @NotNull List<String> BANNED_IMPORTS = List.of(
        "import org.objectweb.asm",
        "import lib.minecraft.renderer.tooling2.kernel.AsmKit",
        "import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache");

    /** String-literal fragments legal only inside the two sanctioned hard-coding homes (doc 05 SS7). */
    private static final @NotNull List<String> VANILLA_LITERAL_MARKS = List.of("minecraft:", "net/minecraft");

    /** A double-quoted Java string literal (no escaped quotes exist in tooling2 sources). */
    private static final @NotNull Pattern STRING_LITERAL = Pattern.compile("\"[^\"]*\"");

    @Test
    @DisplayName("scan mechanics: clean and dirty dummy sources classify correctly")
    void mechanicsProvenOnDummies() {
        String clean = """
            package lib.minecraft.renderer.tooling2.entity;
            import lib.minecraft.renderer.tooling2.policy.Navigation;
            import lib.minecraft.renderer.tooling2.policy.NavigationPolicy;
            enum DummyPolicies implements NavigationPolicy { ROW; }
            """;
        String dirty = """
            package lib.minecraft.renderer.tooling2.entity;
            import lib.minecraft.renderer.tooling2.kernel.AsmKit;
            import org.objectweb.asm.tree.ClassNode;
            enum DummyPolicies implements NavigationPolicy { ROW; }
            """;
        assertEquals(List.of(), bannedImportsIn(clean));
        assertEquals(2, bannedImportsIn(dirty).size());
    }

    @Test
    @DisplayName("every tooling2 *Policies source is pure (roster grows per flow session)")
    void everyPolicySourceIsPure() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING2_MAIN)) {
            sources.filter(path -> path.getFileName().toString().endsWith("Policies.java"))
                .forEach(path -> {
                    try {
                        for (String banned : bannedImportsIn(Files.readString(path)))
                            violations.add(path.getFileName() + ": " + banned);
                    } catch (IOException ex) {
                        violations.add(path + ": unreadable (" + ex + ")");
                    }
                });
        }
        assertTrue(violations.isEmpty(), "policy purity violations:\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("K9: vanilla literals live only in VanillaSourceClasses and the policy enums")
    void vanillaLiteralsOnlyInSanctionedHomes() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING2_MAIN)) {
            sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().equals("VanillaSourceClasses.java"))
                .filter(path -> !path.getFileName().toString().endsWith("Policies.java"))
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
    @DisplayName("K9: every policy constant carries non-blank provenance")
    void everyPolicyConstantCarriesProvenance() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TOOLING2_MAIN)) {
            for (Path path : sources.filter(source -> source.getFileName().toString().endsWith("Policies.java")).toList()) {
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

    /** Loads the class a tooling2 main source file compiles to. */
    private static @NotNull Class<?> classOf(@NotNull Path source) {
        String relative = TOOLING2_MAIN.relativize(source).toString()
            .replace('\\', '/')
            .replace(".java", "")
            .replace('/', '.');
        try {
            return Class.forName("lib.minecraft.renderer.tooling2." + relative);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Policy source without a loadable class: " + source, ex);
        }
    }

    private static @NotNull List<String> bannedImportsIn(@NotNull String source) {
        List<String> found = new ArrayList<>();
        for (String line : source.lines().toList()) {
            String trimmed = line.trim();
            for (String banned : BANNED_IMPORTS)
                if (trimmed.startsWith(banned)) found.add(trimmed);
        }
        return found;
    }

}
