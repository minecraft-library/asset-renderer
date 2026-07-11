package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.exception.PipelineException;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code .pack.provenance.json} sidecar recording how a materialized pack's id was derived, so
 * re-extraction and collision handling stay auditable.
 *
 * <p>It captures the source path and its last-modified time (the re-extract trigger), the id-heuristic
 * version (a change re-keys cache directories predictably), the raw string the winning rung normalized,
 * the {@link PackIdDeriver.Rung} that produced it, and the collision trail - the un-suffixed base id
 * when a suffix was applied.
 *
 * @param id the final assigned pack id
 * @param sourcePath the pack source path as supplied
 * @param sourceModifiedMillis the source's last-modified epoch millis
 * @param heuristicVersion the id-heuristic version that produced this id
 * @param rawName the raw string the winning rung normalized
 * @param chosenRung the ladder rung that produced the preferred id
 * @param collisionBase the un-suffixed base id, present only when a collision suffix was applied
 */
public record PackProvenance(
    @NotNull PackId id,
    @NotNull String sourcePath,
    long sourceModifiedMillis,
    int heuristicVersion,
    @NotNull String rawName,
    @NotNull PackIdDeriver.Rung chosenRung,
    @NotNull Optional<PackId> collisionBase
) {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /**
     * Builds provenance from a derivation assignment and the source's disk facts, stamping the current
     * {@link PackIdDeriver#HEURISTIC_VERSION}.
     *
     * @param assignment the resolved id assignment
     * @param source the pack source path
     * @param sourceModifiedMillis the source's last-modified epoch millis
     * @return the provenance record
     */
    public static @NotNull PackProvenance of(@NotNull PackIdDeriver.Assignment assignment, @NotNull Path source, long sourceModifiedMillis) {
        return new PackProvenance(assignment.id(), source.toString(), sourceModifiedMillis,
            PackIdDeriver.HEURISTIC_VERSION, assignment.rawName(), assignment.rung(), assignment.collisionBase());
    }

    /**
     * Serializes this provenance to its sidecar JSON text.
     *
     * @return the JSON text
     */
    public @NotNull String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", this.id.value());
        obj.addProperty("source_path", this.sourcePath);
        obj.addProperty("source_modified_millis", this.sourceModifiedMillis);
        obj.addProperty("heuristic_version", this.heuristicVersion);
        obj.addProperty("raw_name", this.rawName);
        obj.addProperty("chosen_rung", this.chosenRung.name());
        this.collisionBase.ifPresent(base -> obj.addProperty("collision_base", base.value()));
        return GSON.toJson(obj);
    }

    /**
     * Parses provenance from its sidecar JSON text.
     *
     * @param json the JSON text
     * @return the parsed provenance
     * @throws PipelineException if the JSON is malformed or missing a required field
     */
    public static @NotNull PackProvenance parse(@NotNull String json) {
        JsonObject obj;
        try {
            obj = GSON.fromJson(json, JsonObject.class);
        } catch (JsonSyntaxException ex) {
            throw new PipelineException(ex, "Malformed pack provenance JSON");
        }
        if (obj == null)
            throw new PipelineException("Empty pack provenance JSON");

        return new PackProvenance(
            new PackId(require(obj, "id")),
            require(obj, "source_path"),
            requireElement(obj, "source_modified_millis").getAsLong(),
            requireElement(obj, "heuristic_version").getAsInt(),
            require(obj, "raw_name"),
            parseRung(require(obj, "chosen_rung")),
            obj.has("collision_base") ? Optional.of(new PackId(obj.get("collision_base").getAsString())) : Optional.empty());
    }

    private static @NotNull JsonElement requireElement(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key))
            throw new PipelineException("Pack provenance JSON missing required field '%s'", key);
        return obj.get(key);
    }

    private static @NotNull String require(@NotNull JsonObject obj, @NotNull String key) {
        return requireElement(obj, key).getAsString();
    }

    private static @NotNull PackIdDeriver.Rung parseRung(@NotNull String name) {
        try {
            return PackIdDeriver.Rung.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new PipelineException(ex, "Pack provenance JSON has an unknown chosen_rung '%s'", name);
        }
    }

}
