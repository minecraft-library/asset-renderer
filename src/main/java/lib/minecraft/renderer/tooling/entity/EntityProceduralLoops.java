package lib.minecraft.renderer.tooling.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.minecraft.renderer.tooling.ToolingBlockEntities;
import lib.minecraft.renderer.tooling.ToolingEntityModels;
import lib.minecraft.renderer.tooling.util.FastTrig;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-class supplemental bone tables for procedural-loop model factories. Vanilla's
 * {@code SquidModel}, {@code BlazeModel}, {@code GhastModel}, {@code SilverfishModel},
 * {@code EndermiteModel}, {@code SlimeModel} etc. emit their tentacle / rod / segment / shell
 * bones inside a {@code for (int i = 0; i &lt; N; i++)} loop in {@code createBodyLayer}, with
 * each iteration's pivot computed from {@code Math.cos / Math.sin} or simple
 * {@code i * stride} arithmetic. The shared block-entity {@code Parser} (which the Java entity
 * pipeline reuses) treats {@code ILOAD i} as a non-literal, collapses the entire loop into a
 * single {@code i = 0} iteration, and emits no bones for the procedural body.
 *
 * <p>Rather than extending the {@code Parser} with double-precision arithmetic, loop
 * unrolling, and {@code Math} intrinsics - a deep refactor that would touch the block-entity
 * side too - this class hand-encodes the bones each known procedural loop would emit. Each
 * template entry mirrors the corresponding vanilla {@code createBodyLayer} bytecode so that
 * future MC version bumps surface as a parity-test failure (cube dimensions / pivot
 * formulas / loop counts will break first), allowing the maintainer to update the template
 * directly from the model class's source.
 *
 * <p>Phase E.3 ships the squid template as a proof-of-concept; blaze / ghast / silverfish /
 * endermite / slime / breeze / ender_dragon are documented as follow-up work in the research
 * plan at {@code ~/.claude/plans/java-derived-entity-models-research.md}. Each new template
 * is ~10-30 lines; no Parser change is needed to add one.
 *
 * <p>Templates are keyed by {@code "<factoryClassInternalName>#<factoryMethod>"} so multiple
 * entities sharing one {@code createBodyLayer} (squid + glow_squid both use
 * {@code SquidModel.createBodyLayer}) all benefit from a single template.
 */
@UtilityClass
public final class EntityProceduralLoops {

    /**
     * Returns {@code true} when a procedural-loop template is registered for the given
     * resolution. Used by {@link ToolingEntityModels} to
     * detect entities that need a stub geometry injected when the {@code Parser} produces no
     * bones (silverfish / endermite both fail the {@code Parser}'s linear walk because their
     * factory methods loop over static {@code int[][]} arrays the parser can't decode; without
     * a stub the {@code Parser} returns {@code null} and the entity drops out of
     * {@code entity_models.json}).
     */
    public static boolean hasTemplate(@NotNull EntityLayerDefinitionResolver.Resolution resolution) {
        return hasTemplate(resolution.targetClass() + "#" + resolution.targetMethod());
    }

    /**
     * String-keyed variant of {@link #hasTemplate(EntityLayerDefinitionResolver.Resolution)}.
     * Used by {@code ToolingBlockEntities.Parser} to gate its for-loop unrolling + indy /
     * helper bone-name resolution off for factories the applier still owns - parser-emitted
     * bone names ({@code partN}) would otherwise duplicate the applier's hand-coded names
     * ({@code part_N}) in the same JSON. As each entity migrates, its case here is removed
     * (alongside the corresponding {@code apply*} dispatch and template) and the parser
     * automatically picks up that factory.
     *
     * @param factoryKey the {@code <classInternalName>#<methodName>} key
     * @return {@code true} when an applier template is registered for the factory
     */
    public static boolean hasTemplate(@NotNull String factoryKey) {
        return switch (factoryKey) {
            case "net/minecraft/client/model/monster/ghast/GhastModel#createBodyLayer" -> true;
            default -> false;
        };
    }

    /**
     * Augments the parsed geometry JSON with bones that the procedural-loop body in the
     * matching model class would have emitted. No-op when no template matches the
     * {@code (targetClass, targetMethod)} pair - the geometry is returned with whatever
     * bones the {@code Parser} extracted from the linear bytecode walk.
     *
     * @param geometry the parsed geometry JSON ({@code bones / textureWidth / textureHeight});
     *     bones are added in place
     * @param resolution the layer resolution that produced this geometry, used to dispatch on
     *     {@code (targetClass, targetMethod)}
     * @return the same {@code geometry} reference, mutated when a template applied
     */
    public static @NotNull JsonObject augment(
        @NotNull JsonObject geometry,
        @NotNull EntityLayerDefinitionResolver.Resolution resolution
    ) {
        // Snapshot bone refs before dispatching so the post-pass can identify which bones the
        // applier emitted (new key OR same key but different JsonObject reference, i.e. replaced -
        // applyGhastTentacles overwrites the partial {@code tentacle0} the parser's linear walk
        // emitted with garbage values).
        Map<String, JsonObject> preExisting = snapshotBones(geometry);

        String key = resolution.targetClass() + "#" + resolution.targetMethod();
        switch (key) {
            case "net/minecraft/client/model/monster/ghast/GhastModel#createBodyLayer" ->
                applyGhastTentacles(geometry);
            default -> { /* no template registered yet */ }
        }

        // Post-pass: scale every applier-emitted bone to match the parsed body bone's scale.
        // Without this each per-entity applier would need to know its factory's
        // {@code MeshTransformer.scaling(F)} factor and bake the {@code pose.scaled(F)
        // .translated(0, 24.016*(1-F), 0)} formula into every pivot - ghast lived with the
        // hardcoded version for a release (Task #39) and elder_guardian carried a
        // {@code geometry.guardian_elder} hand-edit in {@code entity_geometry_handedits.json}
        // re-baking all 17 bones. Centralising it here keeps individual appliers as raw
        // {@code addBox} bytecode-literal mirrors and matches the Parser's
        // {@code applyMeshTransformerScaling} formula exactly.
        scaleAugmentedBones(geometry, preExisting);
        return geometry;
    }

    /**
     * Captures a name-to-reference snapshot of the geometry's bones so the post-pass in
     * {@link #augment} can tell which bones the applier inserted or replaced. Reference identity
     * is what matters: an applier that does {@code bones.add(name, freshObject)} swaps the
     * reference even if the name was already present, and the post-pass treats the new object as
     * "applier-emitted".
     */
    private static @NotNull Map<String, JsonObject> snapshotBones(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return Map.of();
        Map<String, JsonObject> snapshot = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
            if (entry.getValue().isJsonObject())
                snapshot.put(entry.getKey(), entry.getValue().getAsJsonObject());
        }
        return snapshot;
    }

    /**
     * Applies the vanilla {@code MeshTransformer.scaling(F)} formula to every bone the applier
     * emitted, where F is read from the post-parse {@code body} bone's {@code scale} field. The
     * Parser already baked F into pre-existing bones (via
     * {@link ToolingBlockEntities ToolingBlockEntities}'s
     * {@code applyMeshTransformerScaling}); this completes the job for applier-emitted bones so
     * pre-existing and procedurally-emitted bones share the same coordinate frame.
     *
     * <p>Transform per bone: {@code pivot' = (F*x, F*y + 24.016*(1-F), F*z)} and
     * {@code scale' = F}. Cubes ({@code origin / size / inflate}) stay in bone-local space; the
     * kit multiplies them by {@code bone.scale} at vertex time, so leaving them untouched gives
     * the right effective render size.
     *
     * <p>No-op when F is 1f (no scaling to apply) or when the body bone is missing. Bones
     * present in {@code preExisting} with the same {@link JsonObject} reference are skipped -
     * the Parser already scaled them. Bones present under the same name with a different
     * reference (replaced by the applier) are scaled here.
     */
    private static void scaleAugmentedBones(@NotNull JsonObject geometry, @NotNull Map<String, JsonObject> preExisting) {
        // Scan pre-existing bones for the Parser-baked MT scale - the Parser scales every
        // surviving bone uniformly so any one of them carries the right F. Don't anchor on
        // {@code body} specifically: GuardianModel's elder layer has no body bone (just
        // head / eye / tail), and grabbing the scale off the first such bone works there too.
        float f = 1f;
        for (JsonObject bone : preExisting.values()) {
            if (bone.has("scale")) {
                float candidate = bone.get("scale").getAsFloat();
                if (candidate != 1f) { f = candidate; break; }
            }
        }
        if (f == 1f) return;
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        float dy = 24.016f * (1f - f);
        for (Map.Entry<String, JsonElement> entry : bones.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject bone = entry.getValue().getAsJsonObject();
            if (preExisting.get(entry.getKey()) == bone) continue;
            JsonArray pivot = bone.getAsJsonArray("pivot");
            if (pivot != null && pivot.size() == 3) {
                float px = pivot.get(0).getAsFloat();
                float py = pivot.get(1).getAsFloat();
                float pz = pivot.get(2).getAsFloat();
                JsonArray scaled = new JsonArray();
                scaled.add(f * px);
                scaled.add(f * py + dy);
                scaled.add(f * pz);
                bone.add("pivot", scaled);
            }
            if (!bone.has("scale")) bone.addProperty("scale", f);
        }
    }

    /**
     * Adds the 9 tentacle bones that {@code GhastModel.createBodyLayer}'s loop emits.
     * Mirrors the bytecode at offsets 50-166: a deterministic-seeded {@code RandomSource}
     * (seed {@code 1660L}) drives the per-iteration tentacle height; the per-tentacle X/Z
     * pivot is laid out as a 3x3 grid centered on the body's pivot:
     * <ul>
     * <li>{@code px = ((i % 3) - 0.5f * ((i / 3) % 2) - 0.75f) * 5f}</li>
     * <li>{@code pz = ((i / 3) - 1) * 5f}</li>
     * <li>{@code height = new Random(1660L).nextInt(7) + 8} (consumed in source order)</li>
     * </ul>
     *
     * <p>Tentacles are children of the {@code root} (NOT {@code body} - the bytecode reuses
     * the root local from {@code astore_1} at offset 12 inside the loop), so the world pivot
     * equals the local pivot directly: {@code (px, 24.6, pz)}. Cube:
     * {@code addBox(-1, 0, -1, 2, height, 2)} at {@code texOffs(0, 0)}.
     *
     * <p>The factory's final step wraps the LayerDefinition with
     * {@code MeshTransformer.scaling(4.5f)} (inline, not via a class-level static MT field);
     * this applier emits the raw addBox-literal values and the shared
     * {@link #scaleAugmentedBones} post-pass in {@link #augment} bakes the scaling into the
     * pivot + {@code scale} field uniformly across every appender, matching what the Parser
     * does for the body bone via
     * {@code lib.minecraft.renderer.tooling.ToolingBlockEntities}'s
     * {@code applyMeshTransformerScaling}.
     */
    private static void applyGhastTentacles(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        java.util.Random rng = new java.util.Random(1660L);
        for (int i = 0; i < 9; i++) {
            float px = (float) (((i % 3) - 0.5 * ((i / 3) % 2) - 0.75) * 5.0);
            float pz = (float) (((i / 3) - 1) * 5.0);
            int height = rng.nextInt(7) + 8;
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(px, 24.6f, pz));
            bone.add("rotation", floatArray(0f, 0f, 0f));
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(-1f, 0f, -1f));
            cube.add("size", floatArray(2f, height, 2f));
            JsonArray uv = new JsonArray();
            uv.add(0);
            uv.add(0);
            cube.add("uv", uv);
            cube.addProperty("inflate", 0.0);
            cube.addProperty("mirror", false);
            cube.add("face_uv", new JsonObject());
            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            // Use the same name format as {@code PartNames.tentacle(int)} produces
            // ("tentacle" + i, no underscore) so this bone overwrites the partial bone the
            // parser's linear walk emits for the loop's first iteration with garbage values.
            bones.add("tentacle" + i, bone);
        }
    }

    /**
     * Builds a 3-element {@link JsonArray} from float values - matches the parser's serialisation shape.
     */
    private static @NotNull JsonArray floatArray(float a, float b, float c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

}
