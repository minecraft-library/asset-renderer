package lib.minecraft.renderer.tooling2.policy;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * failure here from phase 5 on. The doc-12 K9 extensions (vanilla-literal source scan,
 * non-blank-provenance assert) fold into this class when the first real policy lands.
 */
@DisplayName("policy purity: *Policies sources never import the fetch surface")
class PolicyPurityTest {

    private static final @NotNull Path TOOLING2_MAIN = Path.of("src/main/java/lib/minecraft/renderer/tooling2");

    /** Import prefixes a policy source may never reference (the fetch surface). */
    private static final @NotNull List<String> BANNED_IMPORTS = List.of(
        "import org.objectweb.asm",
        "import lib.minecraft.renderer.tooling2.kernel.AsmKit",
        "import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache");

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
