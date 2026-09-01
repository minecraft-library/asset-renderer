package lib.minecraft.renderer.pipeline.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.MotionSource;
import lib.minecraft.renderer.engine.kit.PoseKit;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares an emitted style inventory against what {@link PoseKit#motionOf} measures over the
 * shipped tables, per subject, reduced to the two bits both vocabularies answer: whether anything
 * moves the subject, and which gait reaches the movement.
 *
 * <p>The live side resolves each subject the way a render does and asks {@code motionOf} at the
 * default excursions - a subject answers {@code walk} exactly where the stride alone moves it, and
 * moves at rest under any other non-{@code none} drive. The emitted side is parsed directly from
 * the models table named by {@code asset.migration.new}: a row is in force when its age admits the
 * appearance, and a gated source entry when the appearance keeps the pass its gate token names (the
 * {@code charged} swirl is kept only for a charged subject). A subject whose in-force {@code idle}
 * row carries a source moves at rest; failing that, a stride row carrying one moves at a walk;
 * failing both, nothing moves it - a row whose whole inventory is gated off counts exactly as a
 * subject nothing moves.
 *
 * <p>Subjects are every entity at its default appearance, the baby form of every entity carrying a
 * distinct baby mesh, and the gate-carrying appearances the defaults never select: the charged
 * creeper, the sheared sheep, and the tamed (collared) wolf and cat. Every subject's verdict prints
 * either way; a disagreement is reported with both sides' raw answers and fails the comparison.
 *
 * <p>Skipped when {@code asset.migration.new} is unset or names no models table, so the ordinary
 * suite never depends on files only a migration workspace holds.
 */
@DisplayName("the emitted style inventory against the live motion measurement")
class StyleInventoryVsMotionOf {

    /** Names the directory holding the emitted models table. */
    private static final @NotNull String NEW_TABLES_PROPERTY = "asset.migration.new";

    /** The gait a stride reaches. */
    private static final @NotNull String WALK = "walk";

    /** The gait everything else moves at, and the one a still subject rests in. */
    private static final @NotNull String IDLE = "idle";

    @Test
    @DisplayName("every subject's two bits agree between the inventory and motionOf")
    void inventoryAgreesWithMotionOf() throws IOException {
        Path tables = tables();
        Assumptions.assumeTrue(tables != null,
            "set -D" + NEW_TABLES_PROPERTY + " to the emitted table directory");

        JsonObject models = JsonParser.parseString(
                Files.readString(tables.resolve("entity_models.json"), StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("models");
        Map<String, Entity> index = new TreeMap<>(EntityModelLoader.load());

        List<String> verdicts = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();
        for (Map.Entry<String, Entity> entry : index.entrySet()) {
            compare(entry.getKey(), entry.getValue(), AppearanceOptions.defaults(), "default",
                models, verdicts, disagreements);
            if (entry.getValue().axes().babyModel().isPresent())
                compare(entry.getKey(), entry.getValue(),
                    AppearanceOptions.builder().age(Age.BABY).build(), "baby",
                    models, verdicts, disagreements);
        }
        gated(index, "minecraft:creeper", AppearanceOptions.builder().charged(true).build(),
            "charged", models, verdicts, disagreements);
        gated(index, "minecraft:sheep", AppearanceOptions.builder().sheared(true).build(),
            "sheared", models, verdicts, disagreements);
        gated(index, "minecraft:wolf", AppearanceOptions.builder().state(Optional.of("tame")).build(),
            "tame", models, verdicts, disagreements);
        gated(index, "minecraft:cat", AppearanceOptions.builder().state(Optional.of("tame")).build(),
            "tame", models, verdicts, disagreements);

        verdicts.forEach(System.out::println);
        System.out.println("style inventory vs motionOf: " + verdicts.size() + " subjects, "
            + disagreements.size() + " disagreement(s)");
        assertTrue(disagreements.isEmpty(),
            () -> "the inventory and motionOf disagree:\n" + String.join("\n", disagreements));
    }

    /** The directory the property names, or {@code null} where it is unset or holds no table. */
    private static @Nullable Path tables() {
        String named = System.getProperty(NEW_TABLES_PROPERTY);
        if (named == null || named.isBlank()) return null;
        Path directory = Path.of(named);
        return Files.isRegularFile(directory.resolve("entity_models.json")) ? directory : null;
    }

    /** One gate-carrying extra subject, compared where the index carries its entity. */
    private static void gated(
        @NotNull Map<String, Entity> index, @NotNull String id, @NotNull AppearanceOptions appearance,
        @NotNull String label, @NotNull JsonObject models,
        @NotNull List<String> verdicts, @NotNull List<String> disagreements) {

        Entity entity = index.get(id);
        if (entity != null) compare(id, entity, appearance, label, models, verdicts, disagreements);
    }

    /** One subject measured both ways, its verdict recorded and any disagreement reported. */
    private static void compare(
        @NotNull String id, @NotNull Entity entity, @NotNull AppearanceOptions appearance,
        @NotNull String label, @NotNull JsonObject models,
        @NotNull List<String> verdicts, @NotNull List<String> disagreements) {

        MotionSource motion = PoseKit.motionOf(entity.resolve(appearance), AnimationOptions.defaults());
        boolean liveAnimates = motion != MotionSource.NONE;
        String liveGait = motion == MotionSource.STRIDE ? WALK : IDLE;

        Inventory emitted = inventory(models.getAsJsonObject(id), appearance);
        boolean agree = liveAnimates == emitted.animates() && liveGait.equals(emitted.gait());

        String line = id + "[" + label + "]: live motion=" + motion.name()
            + " -> animates=" + liveAnimates + " gait=" + liveGait
            + " | emitted " + emitted.detail()
            + " -> animates=" + emitted.animates() + " gait=" + emitted.gait()
            + (agree ? " : AGREE" : " : DISAGREE");
        verdicts.add(line);
        if (!agree) disagreements.add(line);
    }

    /** The two bits a subject's in-force inventory answers, and the inventory spelled out. */
    private record Inventory(boolean animates, @NotNull String gait, @NotNull String detail) {}

    /**
     * Reduces one model's spelled rows to the subject's in-force answer: the first applying
     * {@code idle} and {@code stride} rows are found in shipped order, each source entry is kept or
     * dropped by its gate against the appearance, and the two bits follow - rest movement from the
     * idle row, else a walk from the stride row, else stillness.
     */
    private static @NotNull Inventory inventory(
        @Nullable JsonObject model, @NotNull AppearanceOptions appearance) {

        JsonElement styles = model == null ? null : model.get("styles");
        if (styles == null) return new Inventory(false, IDLE, "no styles");

        List<String> idle = null;
        List<String> stride = null;
        for (JsonElement row : styles.getAsJsonArray()) {
            JsonObject held = row.getAsJsonObject();
            if (!applies(held, appearance)) continue;
            String id = held.get("id").getAsString();
            if (idle == null && IDLE.equals(id)) idle = inForceSources(held, appearance);
            if (stride == null && "stride".equals(id)) stride = inForceSources(held, appearance);
        }

        String detail = "idle=" + (idle == null ? "(no row)" : idle)
            + " stride=" + (stride == null ? "(no row)" : stride);
        if (idle != null && !idle.isEmpty()) return new Inventory(true, IDLE, detail);
        if (stride != null && !stride.isEmpty()) return new Inventory(true, WALK, detail);
        return new Inventory(false, IDLE, detail);
    }

    /** Whether a row's age admits the appearance - an unaged row admits both. */
    private static boolean applies(@NotNull JsonObject row, @NotNull AppearanceOptions appearance) {
        if (!row.has("age")) return true;
        return "baby".equals(row.get("age").getAsString()) == appearance.isBaby();
    }

    /**
     * A row's in-force source entries, each spelled {@code token} or {@code token@gate} - a gated
     * entry survives only where the appearance keeps the pass its gate names.
     */
    private static @NotNull List<String> inForceSources(
        @NotNull JsonObject row, @NotNull AppearanceOptions appearance) {

        List<String> out = new ArrayList<>();
        if (!row.has("sources")) return out;
        JsonArray sources = row.getAsJsonArray("sources");
        for (JsonElement source : sources) {
            if (source.isJsonPrimitive()) {
                out.add(source.getAsString());
                continue;
            }
            JsonObject gated = source.getAsJsonObject();
            JsonElement gate = gated.get("gate");
            if (gate == null) {
                out.add(gated.get("source").getAsString());
                continue;
            }
            if (admitted(gate.getAsString(), appearance))
                out.add(gated.get("source").getAsString() + "@" + gate.getAsString());
        }
        return out;
    }

    /**
     * Whether the appearance keeps the pass a gate token names. Only the tokens the corpus spells
     * are answered - an unknown one is a table this comparison has no rule for, and refusing it
     * loudly beats guessing a side.
     */
    private static boolean admitted(@NotNull String gate, @NotNull AppearanceOptions appearance) {
        if ("charged".equals(gate)) return appearance.isCharged();
        throw new IllegalStateException("Style gate token '" + gate + "' has no admittance rule here");
    }

}
