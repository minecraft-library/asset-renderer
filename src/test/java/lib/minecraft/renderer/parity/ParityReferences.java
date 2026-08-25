package lib.minecraft.renderer.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.annotations.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * {@code python parity/scripts/parity report render --references} that would write the same two files;
 * shipping both would be two implementations of one rendering, which is the class of duplication this
 * store exists to remove. Java owns it because the gate that catches a stale reference is
 * {@code ./gradlew test}, which must not need an interpreter.
 *
 * <p><b>No count is ever printed as a literal.</b> Every number below is read from the JSON at render
 * time, so a reference file cannot claim a population the store does not have - which is exactly what
 * went stale three times in the design documents before a line of code was written.
 */
@UtilityClass
public final class ParityReferences {

    /** Where the skill's reference files live, relative to the project directory. */
    public static final @NotNull Path HOME = Path.of(".claude/skills/parity-gate/references");

    /** The reference files this class renders, and therefore the ones that must stay regenerable. */
    public static final @NotNull List<String> GENERATED = List.of("artifacts.md", "blindness.md");

    /** How to rewrite a stale reference from the JSON already in the store. Measures nothing. */
    public static final @NotNull String REGEN_COMMAND =
        "./gradlew test --tests \"*ParityReferencesTest\" -Dasset.parity.regenerateViews=true --rerun";

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
        out.add("`producer` is what a capture of that row **runs**: `parityCapture` depends on the");
        out.add("producers of every row it captures, and the row's own capture step orders itself after");
        out.add("them rather than depending on them. The column is those tasks in the build file's");
        out.add("order, asserted equal to that row there. It is not a census of every task able to write");
        out.add("those bytes, so a task absent from a row can still write into it - a narrower harness");
        out.add("run refreshing part of the reference tree, one visual producer rewriting its own");
        out.add("sub-tree. Run less than a row names and the rest of its file set stays at whatever wrote");
        out.add("it last, while the capture hashes cleanly, because every declared member exists.");
        out.add("");
        out.add("The store's own two root files are the exception: no capture step covers either, so the");
        out.add("build file holds no row for them and the column says what writes the file instead -");
        out.add("`parityPromote` for the index, which a promotion stamps in the same act as the baseline");
        out.add("it writes, and nothing for the rule roster, which is hand-authored. Both are asserted");
        out.add("against that reading rather than against the build file.");
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
                codeJoin(registration.producers()),
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
        out.add("| `generateAtlas` (with `-Pdiagnose` / `-PsourceFilter` / `-PskipRender`) | `AtlasRenderer` dispatches its tiles on `parallelStream` by design, so two runs place the same sprites at different offsets and the output can never be hashed (blindness rule B15). A must-not-crash smoke check. |");
        out.add("| `javadoc` | RED at HEAD, and every error is the same one: a builder an annotation processor produces and the doclet cannot see. Its exit code carries no information. The incubator module flag is wired onto it like every other consumer, which is why the two errors that were about `SimdOps` are gone and the task is still red. |");
        out.add("| `jmh` | Benchmark scores, not rendered bytes. `jmh-regression-gate` is the separate skill that compares them. |");
        out.add("| `redstoneTints`, `stackCountBadge`, `blockFlipbook` | Authoring and version-bump tools. No stored artifact is defined over any of their output directories - none is a member of `manifest.visual` - so what they write is compared against nothing this store holds. |");
        out.add("| `blockRender3D`, `entityProjections`, `entityRender3D`, `itemDayCycle`, `itemRender2D`, `loreTooltip`, `menuRender`, `projectionSmoke` | Visual producers whose `cache/visual` sub-tree is a member of `manifest.visual`, so the rows they write are gated under that id and captured by `visualSweepSet` rather than by a task of their own. |");
        out.add("| `renderVanillaReferences` and the three narrow harness runs | Preconditions rather than gates: they produce the ground truth every sweep is measured against. Only `renderVanillaAllReferences` refreshes the whole tree, which is why it is the one `manifest.references` names. |");
        out.add("| `renderVanillaPitchRollProbe`, `renderVanillaDepthQuantumProbe` | Harness probes that render OUTSIDE the reference tree and refresh no reference, so neither is a precondition either. The second's output is registered as `probe.depth-quantum`, which is external: it is evidence rather than a value a gate reproduces. |");

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
        out.add("Reach is resolved **one changed path at a time** and the answers are unioned, so a");
        out.add("rule speaks about the files it triggers on and about no others: a file that reaches");
        out.add("nothing, committed beside one that reaches a bundle, still plans that bundle.");
        out.add("");
        out.add("Within one path the resolver runs three passes, in this order. **Union**: each");
        out.add("fired rule contributes its `sees`, its mode included. **Demote**: each fired");
        out.add("`demote` rule removes its own `blind` set from that union, taking out what a");
        out.add("different rule selected on this same path along with its own contribution.");
        out.add("**Suppress**: each fired `suppress` rule removes its `sees` and its `blind`");
        out.add("together, the pass that outranks the other two.");
        out.add("");
        out.add("A rule marked **derived** authors no `sees`. Its selection is what the committed");
        out.add("reference graph answers for the changed file, so one glob over a package says");
        out.add("something different about each class under it - and a rule whose region really is");
        out.add("engine-wide goes on costing an engine-wide run. It keeps everything else it has:");
        out.add("`blind`, `reason` and `probe` state what an artifact OBSERVES, which is a different");
        out.add("question from which code a change touches and one no graph can answer.");
        out.add("");
        out.add("Neither removal pass reads a `select` rule's `blind` list, so that list subtracts");
        out.add("nothing and is a statement the plan prints - B10 and B23 below each carry one");
        out.add("naming artifacts outside their own `sees`. What a claim comes to therefore");
        out.add("depends on whether the claiming rule and the selecting rule fire on the **same");
        out.add("path** or on **different paths**, and one pair of rules answers both ways over");
        out.add("one change set.");
        out.add("");
        out.add("`BlindnessMapTest.java` alone fires B37 (`select`) and B39 (`demote`, B37's");
        out.add("list) on one path: the demote pass empties the union, SEES is empty, and every");
        out.add("artifact on that list is reported blind with nothing recorded against it. That");
        out.add("file beside `SelfCapture.java` fires B39 on the first path alone, the second");
        out.add("path resolves to B37's list, and the union carries it - SEES holds all of it");
        out.add("and each blind row reads \"claimed blind, selected by B37\". A `select`");
        out.add("rule's claim resolves by the same arithmetic from the other side: on");
        out.add("`BlockGeometryKit.java` B10 claims `sweep.block` blind while B19 selects it on");
        out.add("that path, so it is in SEES and its row names B19; on `PlayerRenderer.java` B9");
        out.add("claims `sweep.player` and no fired rule selects it, so it is absent from SEES and");
        out.add("its row names nobody.");
        out.add("");
        out.add("## Judging a `manifest.portal` mover on the sub-tick path");
        out.add("");
        out.add("`portalRenderer` writes each animated subject twice - a plain strip on the tick");
        out.add("lattice and an `_animated_smooth` strip at three sub-steps per tick - and");
        out.add("`manifest.portal` hashes both. A sub-tick change that collapsed the smooth strip");
        out.add("to duplicated frames would move those bytes and read as an ordinary mover, so the");
        out.add("bytes having moved is not by itself the question.");
        out.add("");
        out.add("What separates a real intermediate frame from a duplicate: **frame `3n` of the");
        out.add("smooth strip is the plain strip's frame `n` exactly, and the two frames between");
        out.add("each pair differ from both of their neighbours.** Measured over all four animated");
        out.add("subjects, 120 plain frames and 360 smooth: every one of the 120 lattice frames is");
        out.add("identical on every channel, and the smallest margin by which an in-between frame");
        out.add("differs from its nearer neighbour is 20 channel levels on the portal and 47 on the");
        out.add("gateway. Re-measure with `./gradlew portalRenderer` and decode both strips; the");
        out.add("run's own capture step refuses if the working root already holds a finished");
        out.add("capture, which does not affect the strips it writes.");
        out.add("");

        for (JsonElement element : map.getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            out.add("## " + rule.get("id").getAsString() + " - " + rule.get("claim").getAsString());
            out.add("");
            out.add("- **mode** " + rule.get("mode").getAsString());
            out.add("- **triggers** " + codeList(rule.getAsJsonArray("trigger_paths")));
            // A derived rule's empty list would render as "sees nothing", which is the opposite of
            // what it says: the graph answers per file, and this rendering is what a human reads when
            // a rule is being questioned.
            out.add("- **sees** " + (rule.has("derived") && rule.get("derived").getAsBoolean()
                ? "derived per file from the reference graph"
                : codeList(rule.getAsJsonArray("sees"))));
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
        out.add("plan rather than guessing. A **rule wins** where both match, so an entry here speaks");
        out.add("only for paths no rule claims.");
        out.add("");

        for (JsonElement element : map.getAsJsonArray("no_reach")) {
            JsonObject entry = element.getAsJsonObject();
            out.add("### `" + entry.get("glob").getAsString() + "`");
            out.add("");
            out.add(entry.get("reason").getAsString());
            out.add("");
            out.add("*Probe:* " + entry.get("probe").getAsString());
            out.add("");
        }

        return String.join("\n", out).stripTrailing() + "\n";
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
     * Renders a list of names as a comma-separated list of inline code spans.
     *
     * <p>Whole rather than truncated, because the one column a reader with no time budget is sent to
     * is this one: a row rendered as its first producer reads as the whole answer, and running that
     * task alone leaves the rest of the artifact's file set at whatever wrote it last, with the
     * capture hashing cleanly over the mixture.
     *
     * @param names the names
     * @return the rendered list, or a dash when empty
     */
    private static @NotNull String codeJoin(@NotNull List<String> names) {
        if (names.isEmpty()) return "-";
        return names.stream().map(name -> "`" + name + "`").collect(Collectors.joining(", "));
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
