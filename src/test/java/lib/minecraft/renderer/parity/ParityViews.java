package lib.minecraft.renderer.parity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the store's tracked markdown views from the JSON that is their only source of truth.
 *
 * <p>JSON is authoritative and markdown is a generated human view, never the other way round. One
 * view is tracked - {@code README.md} - because a store nobody can read at a glance gets read by
 * re-running a sweep, and it is the page a human actually opens. Everything else is generated into
 * the working root on request and never committed.
 *
 * <p><b>The renderer takes no input but the store.</b> No timestamp, no {@code git rev-parse}, no
 * locale-dependent formatting: staleness is computed against the {@code promoted_at} shas already
 * stored rather than against a live {@code HEAD}, so the view does not go red because time passed.
 * That is what makes byte-identical regeneration a test rather than a rule.
 *
 * <p><b>This is the one renderer, and a promotion must call it rather than reimplement it.</b> The
 * design that scheduled this class also names a Python spelling of the same regeneration; two
 * implementations of one rendering is the class of duplication the whole store exists to remove, so
 * whichever side ends up driving a promotion drives this code. Until a promotion exists, the
 * regeneration path is {@link #REGEN_COMMAND}, which re-renders from the JSON already in the store
 * and measures nothing - naming a promote command here would tell a reader to run a re-baseline to
 * fix a stale rendering, which is the implicit re-baseline the store forbids.
 */
public final class ParityViews {

    /** The views that are tracked in the production store, and therefore have to be regenerable. */
    public static final @NotNull List<String> TRACKED = List.of("README.md");

    /** How to rewrite a stale view from the JSON already in the store. Measures nothing. */
    public static final @NotNull String REGEN_COMMAND =
        "./gradlew test --tests \"*ParityViewsTest\" -Dasset.parity.regenerateViews=true --rerun";

    private ParityViews() {}

    /**
     * Renders one tracked view.
     *
     * @param view the view's file name
     * @return its full text, ending in exactly one newline
     * @throws ParityStoreException if the view is not one this class renders
     */
    public static @NotNull String render(@NotNull String view) {
        if (!TRACKED.contains(view))
            throw new ParityStoreException("'%s' is not a tracked parity view; the tracked set is %s",
                view, String.join(", ", TRACKED));
        return renderReadme(ParityStore.read("report.oracle-index"));
    }

    /**
     * Renders {@code README.md} from the index.
     *
     * @param index the parsed {@code index.json}
     * @return the view's text
     */
    private static @NotNull String renderReadme(@NotNull JsonObject index) {
        List<String> out = new ArrayList<>();
        out.add("# Parity store");
        out.add("");
        out.add("Generated from `index.json`. **Do not edit** - regenerate with:");
        out.add("");
        out.add("```");
        out.add(REGEN_COMMAND);
        out.add("```");
        out.add("");
        out.add("Every artifact this store knows about is below. An artifact that is not `baselined`");
        out.add("has no last known value yet, so a comparison against it answers `MISSING_BASELINE`");
        out.add("rather than passing.");
        out.add("");

        out.add("## Artifacts");
        out.add("");
        out.add("Values this store holds, one file each.");
        out.add("");
        out.add("| artifact | file | entries | headline | promoted at | baselined |");
        out.add("|---|---|---:|---|---|---|");
        forEachSorted(index, "artifacts", (id, entry) -> out.add("| " + String.join(" | ",
            "`" + id + "`",
            code(entry, "file"),
            text(entry, "entries"),
            headline(entry),
            code(entry, "promoted_at"),
            entry.has("baselined") && entry.get("baselined").getAsBoolean() ? "yes" : "**no**"
        ) + " |"));
        out.add("");

        out.add("## Pointers");
        out.add("");
        out.add("Artifacts that are a field of another artifact's file. A sum that is the sum of the");
        out.add("column beside it is one answer, so it is stored once and pointed at.");
        out.add("");
        out.add("| artifact | pointer |");
        out.add("|---|---|");
        forEachSorted(index, "pointers", (id, entry) -> out.add(
            "| `" + id + "` | " + code(entry, "pointer") + " |"));
        out.add("");

        out.add("## External");
        out.add("");
        out.add("Artifacts this store does not hold, and where each one does live. Recorded so a");
        out.add("citation by path is never the only record that something exists.");
        out.add("");
        out.add("| artifact | home | reason |");
        out.add("|---|---|---|");
        forEachSorted(index, "external", (id, entry) -> out.add(
            "| `" + id + "` | " + code(entry, "home") + " | " + text(entry, "reason") + " |"));
        out.add("");

        out.add("## Sources");
        out.add("");
        out.add("Rosters and pins held as a deliberate second copy in Java, so a change to the first");
        out.add("copy fails loudly. Externalising one would defeat exactly that, so the store records");
        out.add("where it lives and how to re-derive it, and carries no value.");
        out.add("");
        out.add("| artifact | home | re-derive |");
        out.add("|---|---|---|");
        forEachSorted(index, "sources", (id, entry) -> out.add(
            "| `" + id + "` | " + code(entry, "test_class") + " | " + text(entry, "re_derive") + " |"));

        return String.join("\n", out) + "\n";
    }

    /**
     * Applies an action to every entry of one index map, in sorted key order.
     *
     * @param index the parsed index
     * @param map the map's key
     * @param action the per-entry action
     */
    private static void forEachSorted(@NotNull JsonObject index, @NotNull String map,
                                      @NotNull java.util.function.BiConsumer<String, JsonObject> action) {
        JsonElement raw = index.get(map);
        if (raw == null || !raw.isJsonObject()) return;
        raw.getAsJsonObject()
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> action.accept(entry.getKey(), entry.getValue().getAsJsonObject()));
    }

    /**
     * Returns an artifact's headline value - the one number a reader wants first.
     *
     * @param entry the index entry
     * @return the sum for a sweep, the file count for a manifest, or a dash
     */
    private static @NotNull String headline(@NotNull JsonObject entry) {
        if (entry.has("sum")) return "sum " + entry.get("sum").getAsString();
        if (entry.has("entries")) return entry.get("entries").getAsString() + " entries";
        return "-";
    }

    /**
     * Returns a field as inline code, or a dash when absent.
     *
     * @param entry the index entry
     * @param field the field to read
     * @return the rendered cell
     */
    private static @NotNull String code(@NotNull JsonObject entry, @NotNull String field) {
        return entry.has(field) ? "`" + entry.get(field).getAsString() + "`" : "-";
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

}
