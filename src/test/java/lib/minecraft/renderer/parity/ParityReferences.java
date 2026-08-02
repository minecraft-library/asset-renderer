package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the {@code parity-gate} skill's two generated reference files from the JSON that is their
 * only source of truth.
 *
 * <p>The skill loads five reference files and two of them are generated: {@code artifacts.md} from
 * the artifact roster joined to {@code index.json}, and {@code blindness.md} from
 * {@code blindness.json}. The other three hold prose recipes with no JSON source and are the files a
 * human edits.
 *
 * <p><b>This is a sibling of {@link ParityViews}, not a duplicate of it, and the line between them is
 * the destination.</b> That class renders what the store publishes about itself, into the store;
 * this one renders what the skill needs in order to choose a gate, into the skill. They project the
 * same JSON differently because they answer different questions - a store README asks "what values do
 * I hold", a gate reference asks "what can see my change, what produces it, and what will it cost".
 *
 * <p><b>There is deliberately no second spelling of this in the toolkit.</b> The skill design names a
 * {@code python scripts/parity report render --references} that would write the same two files;
 * shipping both would be two implementations of one rendering, which is the class of duplication this
 * store exists to remove. Java owns it because the gate that catches a stale reference is
 * {@code ./gradlew test}, which must not need an interpreter.
 *
 * <p><b>No count is ever printed as a literal.</b> Every number below is read from the JSON at render
 * time, so a reference file cannot claim a population the store does not have - which is exactly what
 * went stale three times in the design documents before a line of code was written.
 */
public final class ParityReferences {

    /** Where the skill's reference files live, relative to the project directory. */
    public static final @NotNull Path HOME = Path.of(".claude/skills/parity-gate/references");

    /** The reference files this class renders, and therefore the ones that must stay regenerable. */
    public static final @NotNull List<String> GENERATED = List.of("artifacts.md", "blindness.md");

    /** How to rewrite a stale reference from the JSON already in the store. Measures nothing. */
    public static final @NotNull String REGEN_COMMAND =
        "./gradlew test --tests \"*ParityReferencesTest\" -Dasset.parity.regenerateViews=true --rerun";

    private ParityReferences() {}

    /**
     * Renders one generated reference file.
     *
     * @param reference the file's name
     * @return its full text, ending in exactly one newline
     * @throws ParityStoreException if the name is not one this class renders
     */
    public static @NotNull String render(@NotNull String reference) {
        return switch (reference) {
            case "artifacts.md" -> renderArtifacts(ParityStore.read("report.oracle-index"));
            case "blindness.md" -> renderBlindness(ParityStore.read("roster.blindness-rules"));
            default -> throw new ParityStoreException(
                "'%s' is not a generated parity reference; the generated set is %s",
                reference, String.join(", ", GENERATED));
        };
    }

    /**
     * Renders {@code artifacts.md} - every registered artifact, what produces it and what it costs.
     *
     * <p>The roster drives the row order rather than the index, because the roster is grouped by kind
     * and the index is one alphabetical map. A reader choosing a gate wants the sweeps together.
     *
     * @param index the parsed {@code index.json}
     * @return the reference's text
     */
    private static @NotNull String renderArtifacts(@NotNull JsonObject index) {
        List<String> out = new ArrayList<>();
        out.add("# Parity artifacts");
        out.add("");
        out.add("Generated from `ParityArtifacts` and `index.json`. **Do not edit** - regenerate with:");
        out.add("");
        out.add("```");
        out.add(REGEN_COMMAND);
        out.add("```");
        out.add("");
        out.add("Every artifact the store knows about, in roster order: sweep-table,");
        out.add("render-manifest, file-digest-set, value-pin, roster, report, probe. `home` is where");
        out.add("the value lives - `STORE` has a file, `POINTER` is a field of another artifact's");
        out.add("file, `SOURCE` is a deliberate second copy held in Java, and `EXTERNAL` is not held");
        out.add("at all. An artifact that is not `baselined` has no last known value, so a comparison");
        out.add("against it answers `MISSING_BASELINE` rather than passing.");
        out.add("");
        out.add("`floor` is how many runs a **first** promotion performs. `runs` is how many actually");
        out.add("agreed, read back from the promoted file - the two are different numbers on purpose,");
        out.add("because a floor that doubled as the record would let a declaration pass for evidence.");
        out.add("");
        out.add("| artifact | kind | home | producer | floor | runs | entries | cost | baselined |");
        out.add("|---|---|---|---|---:|---:|---:|---:|---|");

        JsonObject artifacts = index.getAsJsonObject("artifacts");
        for (ParityArtifacts.Registration registration : ParityArtifacts.ALL) {
            JsonObject entry = artifacts != null && artifacts.has(registration.id())
                ? artifacts.getAsJsonObject(registration.id())
                : new JsonObject();
            out.add("| " + String.join(" | ",
                "`" + registration.id() + "`",
                text(entry, "kind"),
                registration.home().name(),
                registration.producer().isEmpty() ? "-" : "`" + registration.producer() + "`",
                registration.determinismFloor() == 0 ? "-" : String.valueOf(registration.determinismFloor()),
                determinismRuns(registration),
                text(entry, "entries"),
                cost(entry),
                baselined(entry)
            ) + " |");
        }

        out.add("");
        out.add("## Tasks that carry no artifact id");
        out.add("");
        out.add("Recorded so a reader does not conclude they were forgotten. None of them can be");
        out.add("selected by `parityPlan`, and none is a gate.");
        out.add("");
        out.add("| task | why it has no id |");
        out.add("|---|---|");
        out.add("| `atlas`, `diagnoseAtlas`, `diagnoseAtlasTask10` | `AtlasRenderer` dispatches its tiles on `parallelStream` by design, so two runs place the same sprites at different offsets and the output can never be hashed (blindness rule B15). A must-not-crash smoke check. |");
        out.add("| `javadoc` | RED at HEAD with about twenty pre-existing errors, seventeen of them Lombok-generated builders an annotation processor produces and javadoc cannot see. Its exit code carries no information. |");
        out.add("| `jmh` | Benchmark scores, not rendered bytes. `jmh-regression-gate` is the separate skill that compares them. |");
        out.add("| `bedParity`, `packOverlay`, `redstoneTints`, `stackCountBadge`, `loreTooltip` cells | Authoring and version-bump tools. What they write either has no oracle or is already covered by `manifest.visual`. |");
        out.add("| `renderVanillaReferences` and the three narrow harness runs | Preconditions rather than gates: they produce the ground truth every sweep is measured against. Only `renderVanillaAllReferences` refreshes the whole tree, which is why it is the one `manifest.references` names. |");

        return String.join("\n", out) + "\n";
    }

    /**
     * Renders {@code blindness.md} - the reach map in prose.
     *
     * <p>Loaded only when a rule is being <b>questioned</b>. The decision always comes from
     * {@code blindness.json} through {@code parityPlan}; a model that reasons from this prose has
     * re-introduced the judged reach resolution the map exists to replace.
     *
     * @param map the parsed {@code blindness.json}
     * @return the reference's text
     */
    private static @NotNull String renderBlindness(@NotNull JsonObject map) {
        List<String> out = new ArrayList<>();
        out.add("# Blindness map");
        out.add("");
        out.add("Generated from `blindness.json`. **Do not edit** - regenerate with:");
        out.add("");
        out.add("```");
        out.add(REGEN_COMMAND);
        out.add("```");
        out.add("");
        out.add("**Decide from the JSON, explain from this file.** `parityPlan` resolves reach from");
        out.add("`blindness.json` directly; this rendering is for explaining a verdict to a human and");
        out.add("for checking a rule that is being questioned. Reasoning from the prose instead is the");
        out.add("judged reach resolution the map replaced, and it looks correct while being wrong.");
        out.add("");
        out.add("Three modes, and the order they apply in is the whole of the arithmetic. `select`");
        out.add("contributes its `sees` to the union. `demote` contributes too and then removes its");
        out.add("`blind` set **after** the union is taken, which is the only way a rule can speak");
        out.add("about artifacts it does not itself select. `suppress` marks an artifact inadmissible");
        out.add("outright, whatever selected it.");
        out.add("");

        for (JsonElement element : map.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            out.add("## " + rule.get("id").getAsString() + " - " + rule.get("claim").getAsString());
            out.add("");
            out.add("- **mode** " + rule.get("mode").getAsString());
            out.add("- **triggers** " + codeList(rule.getAsJsonArray("trigger_paths")));
            out.add("- **sees** " + codeList(rule.getAsJsonArray("sees")));
            out.add("- **blind** " + codeList(rule.getAsJsonArray("blind")));
            out.add("- **source** " + rule.get("source").getAsString());
            out.add("");
            out.add(rule.get("reason").getAsString());
            out.add("");
            out.add("*Probe:* " + rule.get("probe").getAsString());
            out.add("");
        }

        out.add("## Paths that reach nothing");
        out.add("");
        out.add("Covered and reaching nothing is a different answer from \"I do not know\". A changed");
        out.add("path matching neither a rule nor one of these is `UNKNOWN`, and refusal R1 stops the");
        out.add("plan rather than guessing.");
        out.add("");
        out.add(codeList(map.getAsJsonArray("no_reach")));

        return String.join("\n", out) + "\n";
    }

    /**
     * Returns how many runs actually agreed for an artifact, read back from its promoted file.
     *
     * @param registration the artifact's registration
     * @return the recorded count, or a dash when it has no file or records none
     */
    private static @NotNull String determinismRuns(@NotNull ParityArtifacts.Registration registration) {
        if (registration.home() != ParityArtifacts.Home.STORE) return "-";
        if (ParityStore.isRootFile(registration.id()) || !ParityStore.exists(registration.id())) return "-";
        JsonObject provenance = ParityStore.read(registration.id()).getAsJsonObject("provenance");
        if (provenance == null || !provenance.has("determinism_runs")) return "-";
        return provenance.get("determinism_runs").getAsString();
    }

    /**
     * Returns an artifact's recorded wall time.
     *
     * @param entry the index entry
     * @return the duration in milliseconds, or a dash when none has been recorded
     */
    private static @NotNull String cost(@NotNull JsonObject entry) {
        return entry.has("last_duration_ms") ? entry.get("last_duration_ms").getAsString() + " ms" : "-";
    }

    /**
     * Returns whether the index calls an artifact baselined.
     *
     * @param entry the index entry
     * @return the rendered cell
     */
    private static @NotNull String baselined(@NotNull JsonObject entry) {
        if (!entry.has("baselined")) return "-";
        return entry.get("baselined").getAsBoolean() ? "yes" : "**no**";
    }

    /**
     * Returns a field as plain text, or a dash when absent.
     *
     * @param entry the index entry
     * @param field the field to read
     * @return the rendered cell
     */
    private static @NotNull String text(@NotNull JsonObject entry, @NotNull String field) {
        return entry.has(field) ? entry.get(field).getAsString() : "-";
    }

    /**
     * Renders a JSON string array as a comma-separated list of inline code spans.
     *
     * @param array the array, which may be absent
     * @return the rendered list, or a dash when empty
     */
    private static @NotNull String codeList(JsonArray array) {
        if (array == null || array.isEmpty()) return "-";
        List<String> parts = new ArrayList<>(array.size());
        for (JsonElement element : array) parts.add("`" + element.getAsString() + "`");
        return String.join(", ", parts);
    }

    /**
     * Returns every generated reference's rendered text, keyed by file name.
     *
     * @return the rendered set, in {@link #GENERATED} order
     */
    public static @NotNull Map<String, String> renderAll() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String reference : GENERATED) out.put(reference, render(reference));
        return out;
    }

}
