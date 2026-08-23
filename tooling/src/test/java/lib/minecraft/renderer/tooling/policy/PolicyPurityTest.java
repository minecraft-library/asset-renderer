package lib.minecraft.renderer.tooling.policy;

import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanical contract of the policy SPI - what a {@code *Policies} row may declare, how a
 * consumer may reach it, and what its source may never import.
 *
 * <p>Purity is the first half: an implementor declares facts and coordinates but never fetches, so
 * its source may not import {@code ClassKit}, {@code ClassNodeCache} or any {@code org.objectweb.asm}
 * type, and may not reach the cache through the frame it is handed either - an import ban alone
 * leaves {@code context.session().cache()} spelled with no import at all. {@code java.util.zip} is
 * the jar reader's alone; a {@code "minecraft:"} / {@code "net/minecraft"} literal outside
 * {@code VanillaSourceClasses} and the policy enums is a violation; and every constant carries
 * non-blank provenance. The implementors are discovered by walking the tooling main tree for
 * {@code *Policies.java}, so a newly added enum is covered without being listed anywhere.
 *
 * <p>The roster is the second half. {@link #ROSTER} declares the arm every constant answers, so a
 * row carrying a coordinate today and answering {@link Navigation.Value} tomorrow fails here rather
 * than reverting in silence to a hard-coded fact - the regression the table exists to catch. The
 * arms are exactly what the sealed {@link Navigation} permits, each one produced by a live row.
 *
 * <p>A row whose answer carries a coordinate is reached by consulting, which three checks together
 * hold: no member hands a {@link Navigation} back by type, no policy source reads a coordinate row's
 * payload outside the reads the table sanctions, and no policy source destructures a coordinate into
 * the strings it is made of. The table also declares which rows ride a keyless frame and the scan
 * must find exactly those, so a row LEAVING keyless coverage - a consumer gaining a subject-keyed
 * consultation - fails as loudly as one misbehaving inside it.
 *
 * <p>What it does not pin: the scans read source text with comments stripped, so a consultation
 * reached through an indirected receiver, or a fetch spelled through a helper of the policy's own,
 * is outside their reach.
 */
@DisplayName("policy SPI: pure sources, the declared roster of arms, and consultation as the reach")
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

    /** A block comment or javadoc, which the code scans read past. */
    private static final @NotNull Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * The fetch a policy reaches through its own frame. The frame hands over a session for
     * {@code options()}, and the cache hangs off it, so the import bans do not see this one - it is
     * spelled with no import at all.
     */
    private static final @NotNull String CACHE_REACH = ".cache()";

    /**
     * The coordinate arms' own accessors. A policy DECLARES a coordinate and never takes one apart,
     * so a destructured coordinate in a policy source is a payload leaving without the arm around it.
     */
    private static final @NotNull List<String> COORDINATE_ACCESSORS =
        List.of(".owner()", ".member()", ".desc()", ".entryPath()", ".trace()", ".steps()");

    /**
     * Every policy row and the arm its live consultation answers. The roster law asserts this table
     * and the walked tree name the same rows, so a constant added, moved or retired fails until its
     * arm is declared here - the table cannot drift behind the roster it describes.
     *
     * <p>A per-anchor row answers a coordinate keyed on the frame's anchor class, so it is consulted
     * at an anchor its own table names, and answers {@link Navigation.None} at one it does not. A
     * keyless row is one every consultation of it reaches through {@link AsmContext#keyless}, which
     * the source scan re-derives and compares against what is declared here.
     */
    private static final @NotNull List<Row> ROSTER = List.of(
        row("BlockFamilyPolicies", "METHOD_SPLIT_NAMING", Navigation.Value.class),
        row("BlockFamilyPolicies", "SIGN_VARIANTS", Navigation.Value.class),
        row("BlockFamilyPolicies", "BANNER_FIELDS", Navigation.Value.class),
        row("BlockFamilyPolicies", "FAMILY_ROSTER", Navigation.Value.class),
        keylessRow("BlockFamilyPolicies", "BANNER_DYE_TARGET", Navigation.At.class),
        row("BlockFamilyPolicies", "SHEET_TEXTURE_BASES", Navigation.Value.class),
        row("BlockFamilyPolicies", "PLAYER_SKULL_SKIN", Navigation.Dataflow.class),
        row("BlockGeometryPolicies", "PRIMARY_METHOD_NAMES", Navigation.Value.class),
        keylessPerAnchorRow("BlockTransformPolicies", "RENDERER_ENTRY_METHODS", Navigation.At.class),
        row("BlockTransformPolicies", "INVENTORY_Y_ROTATION", Navigation.Value.class),
        row("BlockTransformPolicies", "FLIP_SUPPRESSED", Navigation.Value.class),
        row("BlockTransformPolicies", "STANDING_SIGN_ATTACHMENT", Navigation.Value.class),
        row("BlockTransformPolicies", "ICON_ROTATION", Navigation.Value.class),
        row("BlockTransformPolicies", "ICON_ADDITIVE", Navigation.Value.class),
        row("BlockTransformPolicies", "PART_COMPOSITION", Navigation.Value.class),
        row("BlockTransformPolicies", "ENTITY_FLIP_DEFAULT", Navigation.Value.class),
        row("BlockTransformPolicies", "Y_AXIS_BAND", Navigation.Value.class),
        row("BlockTransformPolicies", "CANONICALISE", Navigation.Value.class),
        keylessRow("ColorMapPolicies", "GRASS", Navigation.AtResource.class),
        keylessRow("ColorMapPolicies", "FOLIAGE", Navigation.AtResource.class),
        keylessRow("ColorMapPolicies", "DRY_FOLIAGE", Navigation.AtResource.class),
        row("BlockStatePolicies", "BOOLEAN_DEFAULT", Navigation.Value.class),
        row("EntityAxisPolicies", "NATURAL_SIZE_SET", Navigation.At.class),
        row("EntityAxisPolicies", "AXIS_NAME_VOCABULARY", Navigation.Value.class),
        row("EntityAxisPolicies", "STATE_PRECEDENCE", Navigation.Value.class),
        row("EntityAxisPolicies", "SIZE_DOMAIN", Navigation.At.class),
        row("EntityAxisPolicies", "SHAPE_SIZE_MEMBERSHIP", Navigation.Value.class),
        row("EntityNamingPolicies", "UNIFORM_SCALE_TOLERANCE", Navigation.At.class),
        row("EntityNamingPolicies", "ENUM_DEFAULT_FIELD", Navigation.Value.class),
        row("EntityNamingPolicies", "DATA_VARIANT_SUFFIXES", Navigation.Value.class),
        row("EntityNamingPolicies", "SUFFIX_MIN_RECURRENCE", Navigation.Value.class),
        row("EntityNamingPolicies", "LEFT_RIGHT_STEMS", Navigation.Value.class),
        row("EntityOverlayPolicies", "FULL_MESH_REUSE_ALPHA_EPSILON", Navigation.Value.class),
        row("EntityOverlayPolicies", "DECOR_SKIP_BOUNDS", Navigation.Value.class),
        row("EntityOverlayPolicies", "FROZEN_FRAME", Navigation.Value.class),
        row("EntityOverlayPolicies", "PRESENCE_GATE_FIXED_WHEN_GUARDED", Navigation.Value.class),
        row("EntityOverlayPolicies", "EQUIPMENT_DEFAULT_MATERIALS", Navigation.Value.class),
        row("EntityRestPolicies", "IN_WATER_FAMILY", Navigation.Value.class),
        row("EntityRestPolicies", "RESTING_PHASE_ANSWER", Navigation.At.class),
        row("SnapshotShapePolicies", "TINT_DYNAMIC_SOURCE_DROPS", Navigation.Value.class),
        row("SnapshotShapePolicies", "TINT_MULTI_SOURCE_DROP", Navigation.Value.class),
        keylessRow("SnapshotShapePolicies", "TINT_CONSTANT_IN_HAND", Navigation.Dataflow.class));

    /** The SPI method names a consumer consults a row through. */
    private static final @NotNull List<String> CONSULTATIONS =
        List.of(".navigate(", ".requireAt(", ".requireDataflow(");

    /** The keyless frame construction - the one sanctioned spelling of a consultation with no subject. */
    private static final @NotNull String KEYLESS_FRAME = "AsmContext.keyless(";

    /** The subject-keyed frame construction. */
    private static final @NotNull String KEYED_FRAME = "new AsmContext(";

    /** The name of the enum member the SPI consultation enters at. */
    private static final @NotNull String CONSULT = "navigate";

    /** The name of the private field a policy holds its declared payload in. */
    private static final @NotNull String PAYLOAD_FIELD = "value";

    /** Two subjects a consultation is taken at, so a row reading the subject cannot answer both alike. */
    private static final @NotNull String SUBJECT_A = "<subject-a>";
    private static final @NotNull String SUBJECT_B = "<subject-b>";

    /** An anchor class no per-anchor row's table names an entry for. */
    private static final @NotNull String UNROSTERED_ANCHOR = "<no-such-anchor>";

    /** The one row a consumer reads without consulting, and the accessor it reads it through. */
    private static final @NotNull String SHEET_ROW = "BlockFamilyPolicies.SHEET_TEXTURE_BASES";
    private static final @NotNull String SHEET_ACCESSOR = "BlockFamilyPolicies.sheetCoordinate";

    /**
     * The coordinate rows a policy source may name the payload of, and the only two: the transform
     * roster, which a private lookup reads so {@code navigate} can select the anchor's own entry, and
     * the sheet table, read by the accessor above.
     */
    private static final @NotNull List<String> PAYLOAD_READS =
        List.of("BlockTransformPolicies.RENDERER_ENTRY_METHODS", SHEET_ROW);

    @TempDir
    static Path tempDir;

    /** The roster the walk discovers, keyed by the declaring enum's simple name. */
    private static Map<String, Class<?>> rosterClasses = Map.of();

    /** Each roster enum's own source file, keyed the same way. */
    private static Map<String, Path> rosterSources = Map.of();

    /** A session the consultations carry; no policy body reads it, and its cache opens no vanilla jar. */
    private static ToolingSession session;

    @BeforeAll
    static void openRoster() throws IOException {
        Map<String, Class<?>> classes = new TreeMap<>();
        Map<String, Path> sources = new TreeMap<>();
        try (Stream<Path> walked = Files.walk(TOOLING_MAIN)) {
            for (Path path : walked.filter(source -> source.getFileName().toString().endsWith(POLICY_SOURCE)).toList()) {
                Class<?> policy = classOf(path);
                classes.put(policy.getSimpleName(), policy);
                sources.put(policy.getSimpleName(), path);
            }
        }
        rosterClasses = classes;
        rosterSources = sources;
        Path jar = tempDir.resolve("policy.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("policy.marker"));
            zip.closeEntry();
        }
        session = new ToolingSession(ClientOptions.defaults(), ClassNodeCache.open(jar),
            Diagnostics.root("policy", Diagnostics.Output.NONE, null));
    }

    @AfterAll
    static void closeSession() {
        session.close();
    }

    // ------------------------------------------------------------------------------------
    // purity: a policy declares, never fetches
    // ------------------------------------------------------------------------------------

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
    @DisplayName("no policy reaches the jar cache through the frame it is handed")
    void noPolicyReachesTheCacheThroughItsFrame() throws IOException {
        assertEquals(List.of(), fragmentsInPolicySources(List.of(CACHE_REACH)),
            "Policy sources fetching through the frame's session, which no import ban can see");
    }

    @Test
    @DisplayName("every roster enum is flow-local - none is public")
    void everyRosterEnumIsFlowLocal() {
        List<String> exported = rosterClasses.values().stream()
            .filter(policy -> Modifier.isPublic(policy.getModifiers()))
            .map(Class::getSimpleName)
            .sorted()
            .toList();
        assertEquals(List.of(), exported, "Policy enums visible outside their own flow");
    }

    @Test
    @DisplayName("every policy constant carries non-blank provenance")
    void everyPolicyConstantCarriesProvenance() {
        List<String> violations = new ArrayList<>();
        for (Class<?> policy : rosterClasses.values()) {
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
        assertTrue(violations.isEmpty(), "provenance violations:\n" + String.join("\n", violations));
    }

    // ------------------------------------------------------------------------------------
    // the roster law: every row answers a live arm from the closed set
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the walked roster and the arm table name the same rows")
    void theRosterIsExactlyTheDeclaredRows() {
        List<String> discovered = new ArrayList<>();
        for (Map.Entry<String, Class<?>> policy : rosterClasses.entrySet())
            for (Object constant : policy.getValue().getEnumConstants())
                discovered.add(policy.getKey() + "." + ((Enum<?>) constant).name());
        assertEquals(ROSTER.stream().map(Row::key).sorted().toList(), discovered.stream().sorted().toList(),
            "The arm table and the walked roster name different rows");
    }

    @Test
    @DisplayName("every row answers the arm it declares - a coordinate row regressing to a declared value fails here")
    void everyRowAnswersItsDeclaredArm() {
        List<String> violations = new ArrayList<>();
        for (Row declared : ROSTER) {
            Class<? extends Navigation> answered = consult(declared, SUBJECT_A).getClass();
            if (!declared.arm().equals(answered))
                violations.add("'%s' answers '%s' where '%s' is declared".formatted(
                    declared.key(), answered.getSimpleName(), declared.arm().getSimpleName()));
        }
        assertTrue(violations.isEmpty(),
            "Rows answering an arm the table does not declare:\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("the arms are the five the sealed interface permits, each with a live producer")
    void everyPermittedArmHasALiveProducer() {
        Set<Class<?>> permitted = Set.of(Navigation.class.getPermittedSubclasses());
        assertEquals(
            Set.of(Navigation.At.class, Navigation.AtResource.class, Navigation.Dataflow.class,
                Navigation.Value.class, Navigation.None.class),
            permitted,
            "The sealed arm set is not the five arms the roster is written against");
        Set<Class<?>> produced = new HashSet<>();
        for (Row declared : ROSTER) {
            produced.add(consult(declared, SUBJECT_A).getClass());
            if (declared.perAnchor()) produced.add(offRoster(declared).getClass());
        }
        assertEquals(permitted, produced, "An arm the interface permits is produced by no live row");
    }

    @Test
    @DisplayName("a per-anchor row answers None for an anchor its own table names no entry for")
    void aPerAnchorRowAnswersNoneOffItsTable() {
        for (Row declared : ROSTER) {
            if (!declared.perAnchor()) continue;
            assertInstanceOf(Navigation.None.class, offRoster(declared),
                "Row '%s' answers a coordinate for an anchor it names no entry for".formatted(declared.key()));
        }
    }

    // ------------------------------------------------------------------------------------
    // the bypass guard: a coordinate is reached by consulting, not by reading
    // ------------------------------------------------------------------------------------

    /**
     * Pins that a row whose answer carries a coordinate is reached through the SPI, with one
     * exemption.
     *
     * <p>{@code SHEET_TEXTURE_BASES} keeps a package-private accessor because its coordinates are
     * keyed by catalog family, which the frame does not carry: the frame the block-entity flow builds
     * stamps the subject's {@code beTypeId()} where the row's key is derived from its
     * {@code localId()}. The resolver holds the family already, so the accessor takes it. That is a
     * cost, not a correctness bar - a subject-keyed consultation would answer the same sheet, the
     * three families that ask for none answering off the table as the transform roster's rows do.
     */
    @Test
    @DisplayName("a coordinate row is reached by consulting the SPI, the family-keyed sheet table excepted")
    void everyCoordinateRowIsReachedThroughTheSpi() throws IOException {
        Map<String, Boolean> reach = spiReach();
        List<String> unreached = new ArrayList<>();
        for (Row declared : ROSTER)
            if (carriesCoordinate(consult(declared, SUBJECT_A)) && !reach.containsKey(declared.key()))
                unreached.add(declared.key());
        assertEquals(List.of(SHEET_ROW), unreached,
            "Coordinate rows reached without a consultation, against the one sanctioned exemption");
    }

    @Test
    @DisplayName("the family-keyed sheet accessor is the only member handing a coordinate out by type")
    void theSheetAccessorIsTheOnlyCoordinateHander() {
        assertEquals(List.of(SHEET_ACCESSOR), coordinateHanders(),
            "Roster members handing a coordinate to a caller, against the one sanctioned exemption");
    }

    @Test
    @DisplayName("a coordinate row's payload is named only where the table sanctions it - a raw or destructured read fails")
    void aCoordinatePayloadIsNamedOnlyWhereSanctioned() throws IOException {
        assertEquals(PAYLOAD_READS.stream().sorted().toList(), coordinatePayloadReads(),
            "Policy sources reading a coordinate row's payload, against the reads the table sanctions");
        assertEquals(List.of(), fragmentsInPolicySources(COORDINATE_ACCESSORS),
            "Policy sources taking a coordinate apart, which is what a consumer does with the arm it was handed");
    }

    // ------------------------------------------------------------------------------------
    // the keyless frame: an answer taken with no subject may not track one
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the rows the sources consult keylessly are the rows the table declares keyless")
    void theKeylessSetIsTheDeclaredSet() throws IOException {
        Map<String, Boolean> reach = spiReach();
        List<String> scanned = reach.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        assertEquals(ROSTER.stream().filter(Row::keyless).map(Row::key).sorted().toList(), scanned,
            "The rows consulted through a keyless frame are not the rows the table declares keyless");
    }

    @Test
    @DisplayName("a row consulted through a keyless frame answers the same whatever the subject")
    void keylessRowsDoNotTrackTheSubject() {
        List<Row> keyless = ROSTER.stream().filter(Row::keyless).toList();
        assertFalse(keyless.isEmpty(), "The table declares no keyless row for the guard to cover");
        for (Row declared : keyless)
            assertEquals(consult(declared, SUBJECT_A), consult(declared, SUBJECT_B),
                "Row '%s' rides a keyless frame and answers per subject".formatted(declared.key()));
    }

    // ------------------------------------------------------------------------------------
    // roster plumbing
    // ------------------------------------------------------------------------------------

    /**
     * Strips comments, so a scan reads what a source DOES rather than what it says about itself. A
     * javadoc naming a frame construction or a payload read is prose, and a guard that counts it is a
     * guard a comment can switch off.
     *
     * @param source the whole text of one Java source file
     * @return the same text with block comments and line comments removed
     */
    private static @NotNull String stripComments(@NotNull String source) {
        StringBuilder code = new StringBuilder();
        for (String line : BLOCK_COMMENT.matcher(source).replaceAll("").lines().toList()) {
            int comment = line.indexOf("//");
            code.append(comment >= 0 && line.lastIndexOf('"', comment) < 0 ? line.substring(0, comment) : line)
                .append('\n');
        }
        return code.toString();
    }

    /**
     * Collects the banned fragments the policy sources spell in code.
     *
     * @param fragments the fragments no policy source may spell
     * @return the offending {@code <enum>: <fragment>} pairs, sorted
     * @throws IOException if a policy source cannot be read
     */
    private static @NotNull List<String> fragmentsInPolicySources(@NotNull List<String> fragments) throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Path> policy : rosterSources.entrySet()) {
            String code = stripComments(Files.readString(policy.getValue()));
            for (String fragment : fragments)
                if (code.contains(fragment)) found.add(policy.getKey() + ": " + fragment);
        }
        return found.stream().sorted().toList();
    }

    /**
     * The coordinate rows whose payload their own source names. A policy builds its answer off
     * {@code this}, so naming a coordinate constant's payload is a lookup handing the coordinate
     * somewhere, and the table says which of those are sanctioned.
     *
     * @return the rows read that way, sorted
     * @throws IOException if a policy source cannot be read
     */
    private static @NotNull List<String> coordinatePayloadReads() throws IOException {
        List<String> reads = new ArrayList<>();
        for (Row declared : ROSTER) {
            if (!carriesCoordinate(consult(declared, SUBJECT_A))) continue;
            String code = stripComments(Files.readString(rosterSources.get(declared.policy())));
            if (code.contains(declared.constant() + ".")) reads.add(declared.key());
        }
        return reads.stream().sorted().toList();
    }

    /**
     * Consults a row exactly as its flow does - at a subject, and at an anchor its own table names
     * when the row dispatches per anchor.
     *
     * @param declared the row to consult
     * @param subject the subject id the frame carries
     * @return the arm the row answers
     */
    private static @NotNull Navigation consult(@NotNull Row declared, @NotNull String subject) {
        return constantOf(declared).navigate(frame(subject, declared.perAnchor() ? anchorOf(declared) : null));
    }

    /**
     * Consults a per-anchor row at an anchor its table names no entry for.
     *
     * @param declared the row to consult
     * @return the arm the row answers off its own table
     */
    private static @NotNull Navigation offRoster(@NotNull Row declared) {
        return constantOf(declared).navigate(frame(SUBJECT_A, UNROSTERED_ANCHOR));
    }

    /**
     * Builds a consultation frame over the test session.
     *
     * @param subject the subject id the frame carries
     * @param anchor the anchor class in play, or {@code null} when the row reads none
     * @return the frame
     */
    private static @NotNull AsmContext frame(@NotNull String subject, @Nullable String anchor) {
        return new AsmContext(session, subject, anchor, session.diagnostics());
    }

    /**
     * Resolves the enum constant a row names.
     *
     * @param declared the row
     * @return the constant, as the SPI sees it
     */
    private static @NotNull NavigationPolicy constantOf(@NotNull Row declared) {
        Class<?> policy = rosterClasses.get(declared.policy());
        if (policy == null)
            throw new IllegalStateException("The roster holds no policy named '%s'".formatted(declared.policy()));
        for (Object constant : policy.getEnumConstants())
            if (((Enum<?>) constant).name().equals(declared.constant())) return (NavigationPolicy) constant;
        throw new IllegalStateException("Policy '%s' declares no constant named '%s'"
            .formatted(declared.policy(), declared.constant()));
    }

    /**
     * The anchor a per-anchor row is consulted at, taken from the row's own table so the test names
     * no vanilla class of its own.
     *
     * @param declared the row
     * @return one anchor the row's table names an entry for
     */
    private static @NotNull String anchorOf(@NotNull Row declared) {
        Field payload;
        try {
            payload = rosterClasses.get(declared.policy()).getDeclaredField(PAYLOAD_FIELD);
            payload.setAccessible(true);
            if (payload.get(constantOf(declared)) instanceof Map<?, ?> table)
                for (Object key : table.keySet())
                    if (key instanceof String anchor) return anchor;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Row '%s' has no readable declared payload".formatted(declared.key()), ex);
        }
        throw new IllegalStateException("Row '%s' declares no anchor-keyed table to consult at".formatted(declared.key()));
    }

    /**
     * Whether an answer hands the caller a bytecode or resource coordinate, either as its own arm or
     * inside a declared value.
     *
     * @param answer the arm a row answered
     * @return whether a coordinate travels in it
     */
    private static boolean carriesCoordinate(@NotNull Navigation answer) {
        if (answer instanceof Navigation.Value<?> declared) return holdsCoordinate(declared.value());
        return !(answer instanceof Navigation.None);
    }

    /**
     * Whether a declared value is, holds or nests a coordinate.
     *
     * @param declared the declared value, an element of one, or {@code null}
     * @return whether a coordinate is reachable from it
     */
    private static boolean holdsCoordinate(@Nullable Object declared) {
        if (declared instanceof Navigation) return true;
        if (declared instanceof Map<?, ?> table)
            return table.values().stream().anyMatch(PolicyPurityTest::holdsCoordinate);
        if (declared instanceof Collection<?> items)
            return items.stream().anyMatch(PolicyPurityTest::holdsCoordinate);
        if (declared != null && declared.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(declared); index++)
                if (holdsCoordinate(Array.get(declared, index))) return true;
            return false;
        }
        if (declared != null && declared.getClass().isRecord()) {
            for (RecordComponent component : declared.getClass().getRecordComponents())
                if (holdsCoordinate(componentOf(declared, component))) return true;
            return false;
        }
        return false;
    }

    /**
     * Reads one component off a payload record, the record types being flow-local like the enums
     * declaring them.
     *
     * @param declared the record holding the component
     * @param component the component to read
     * @return the component's value
     */
    private static @Nullable Object componentOf(@NotNull Object declared, @NotNull RecordComponent component) {
        try {
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(declared);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Payload record '%s' will not answer its component '%s'"
                .formatted(declared.getClass().getSimpleName(), component.getName()), ex);
        }
    }

    /**
     * Scans the consuming sources for consultations: which rows are consulted, and which of those
     * ride a frame carrying no subject.
     *
     * <p>A source building only keyless frames consults keylessly; one building a subject-keyed
     * frame anywhere is read as keyed, so a consumer that gains a second, subject-keyed consultation
     * drops the rows it reaches out of the keyless set, which the declared set is compared against.
     * Comments are stripped first: a frame construction named in a javadoc is prose, and counting it
     * would let a comment erase a row's coverage.
     *
     * @return each consulted row, against whether some consultation of it is keyless
     * @throws IOException if the tooling main tree cannot be walked
     */
    private static @NotNull Map<String, Boolean> spiReach() throws IOException {
        Map<String, Boolean> reach = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(TOOLING_MAIN)) {
            for (Path path : sources.filter(source -> source.getFileName().toString().endsWith(ANY_SOURCE))
                .filter(source -> !source.getFileName().toString().endsWith(POLICY_SOURCE)).toList()) {
                String code = stripComments(Files.readString(path));
                boolean keyless = code.contains(KEYLESS_FRAME) && !code.contains(KEYED_FRAME);
                for (Map.Entry<String, Class<?>> policy : rosterClasses.entrySet()) {
                    boolean wholeRoster = walksWholeRoster(code, policy.getKey());
                    for (Object constant : policy.getValue().getEnumConstants()) {
                        String name = ((Enum<?>) constant).name();
                        if (wholeRoster || namesConsultation(code, policy.getKey() + "." + name))
                            reach.merge(policy.getKey() + "." + name, keyless, Boolean::logicalOr);
                    }
                }
            }
        }
        return reach;
    }

    /**
     * Whether a source walks an enum's whole roster and consults what the walk binds. The loop
     * variable is read off the loop itself, so a source merely naming {@code values()} somewhere
     * counts for nothing.
     *
     * @param code one tooling main source with its comments stripped
     * @param policy the declaring enum's simple name
     * @return whether every row that enum declares is consulted there
     */
    private static boolean walksWholeRoster(@NotNull String code, @NotNull String policy) {
        Matcher loop = Pattern.compile("for\\s*\\(\\s*" + policy + "\\s+(\\w+)\\s*:\\s*" + policy + "\\.values\\(\\)\\s*\\)")
            .matcher(code);
        return loop.find() && namesConsultation(code, loop.group(1));
    }

    /**
     * Whether a source consults through one receiver - a named constant or a loop variable.
     *
     * @param code one tooling main source with its comments stripped
     * @param receiver the receiver the consultation would be spelled on
     * @return whether the source consults through it
     */
    private static boolean namesConsultation(@NotNull String code, @NotNull String receiver) {
        for (String call : CONSULTATIONS)
            if (code.contains(receiver + call)) return true;
        return false;
    }

    /**
     * The roster members handing a {@link Navigation} to a caller - a coordinate reachable without
     * consulting. The consultation itself and the private lookups it selects an answer through are
     * not handers.
     *
     * @return the offending members as {@code <enum>.<member>}, sorted
     */
    private static @NotNull List<String> coordinateHanders() {
        List<String> handers = new ArrayList<>();
        for (Map.Entry<String, Class<?>> policy : rosterClasses.entrySet()) {
            for (Method method : policy.getValue().getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) continue;
                if (method.getName().equals(CONSULT)) continue;
                if (namesNavigation(method.getGenericReturnType().getTypeName()))
                    handers.add(policy.getKey() + "." + method.getName());
            }
            for (Field field : policy.getValue().getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isPrivate(field.getModifiers())) continue;
                if (namesNavigation(field.getGenericType().getTypeName()))
                    handers.add(policy.getKey() + "." + field.getName());
            }
        }
        return handers.stream().sorted().toList();
    }

    /**
     * Whether a declared type names the SPI's answer type.
     *
     * @param typeName a member's generic type name
     * @return whether a coordinate travels in it
     */
    private static boolean namesNavigation(@NotNull String typeName) {
        return typeName.contains(Navigation.class.getSimpleName());
    }

    /**
     * Declares a row answering one arm for every frame, consulted at a subject.
     *
     * @param policy the declaring enum's simple name
     * @param constant the enum constant's name
     * @param arm the arm the row answers
     * @return the table row
     */
    private static @NotNull Row row(
        @NotNull String policy, @NotNull String constant, @NotNull Class<? extends Navigation> arm) {
        return new Row(policy, constant, arm, false, false);
    }

    /**
     * Declares a row every consultation of which rides a keyless frame.
     *
     * @param policy the declaring enum's simple name
     * @param constant the enum constant's name
     * @param arm the arm the row answers
     * @return the table row
     */
    private static @NotNull Row keylessRow(
        @NotNull String policy, @NotNull String constant, @NotNull Class<? extends Navigation> arm) {
        return new Row(policy, constant, arm, false, true);
    }

    /**
     * Declares a keyless row answering a coordinate per anchor class.
     *
     * @param policy the declaring enum's simple name
     * @param constant the enum constant's name
     * @param arm the arm the row answers at an anchor its table names
     * @return the table row
     */
    private static @NotNull Row keylessPerAnchorRow(
        @NotNull String policy, @NotNull String constant, @NotNull Class<? extends Navigation> arm) {
        return new Row(policy, constant, arm, true, true);
    }

    // ------------------------------------------------------------------------------------
    // scan plumbing
    // ------------------------------------------------------------------------------------

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
     * One policy row and what its live consultation answers.
     *
     * @param policy the declaring enum's simple name
     * @param constant the enum constant's name
     * @param arm the arm the consultation answers
     * @param perAnchor whether the row dispatches on the frame's anchor class rather than answering one arm for every frame
     * @param keyless whether every consultation of the row rides a frame carrying no subject
     */
    private record Row(
        @NotNull String policy,
        @NotNull String constant,
        @NotNull Class<? extends Navigation> arm,
        boolean perAnchor,
        boolean keyless
    ) {

        /**
         * The roster key, {@code <enum>.<constant>}.
         *
         * @return the key the walked roster and the table meet under
         */
        @NotNull String key() {
            return this.policy + "." + this.constant;
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
