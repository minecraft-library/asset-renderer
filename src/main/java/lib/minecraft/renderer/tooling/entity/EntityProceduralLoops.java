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
     * Source of mathematical constants used by the squid template.
     */
    private static final double TAU = 2.0 * Math.PI;

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
        return switch (resolution.targetClass() + "#" + resolution.targetMethod()) {
            case "net/minecraft/client/model/animal/squid/SquidModel#createBodyLayer",
                 "net/minecraft/client/model/monster/blaze/BlazeModel#createBodyLayer",
                 "net/minecraft/client/model/monster/guardian/GuardianModel#createBodyLayer",
                 "net/minecraft/client/model/monster/guardian/GuardianModel#createElderGuardianLayer",
                 "net/minecraft/client/model/monster/slime/MagmaCubeModel#createBodyLayer",
                 "net/minecraft/client/model/monster/ghast/GhastModel#createBodyLayer",
                 "net/minecraft/client/model/monster/silverfish/SilverfishModel#createBodyLayer",
                 "net/minecraft/client/model/monster/endermite/EndermiteModel#createBodyLayer",
                 "net/minecraft/client/model/monster/dragon/EnderDragonModel#createBodyLayer" -> true;
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
            case "net/minecraft/client/model/animal/squid/SquidModel#createBodyLayer" ->
                applySquidTentacles(geometry);
            case "net/minecraft/client/model/monster/blaze/BlazeModel#createBodyLayer" ->
                applyBlazeRods(geometry);
            case "net/minecraft/client/model/monster/guardian/GuardianModel#createBodyLayer",
                 "net/minecraft/client/model/monster/guardian/GuardianModel#createElderGuardianLayer" ->
                applyGuardianSpikes(geometry);
            case "net/minecraft/client/model/monster/slime/MagmaCubeModel#createBodyLayer" ->
                applyMagmaCubeSegments(geometry);
            case "net/minecraft/client/model/monster/ghast/GhastModel#createBodyLayer" ->
                applyGhastTentacles(geometry);
            case "net/minecraft/client/model/monster/silverfish/SilverfishModel#createBodyLayer" ->
                applySilverfishSegments(geometry);
            case "net/minecraft/client/model/monster/endermite/EndermiteModel#createBodyLayer" ->
                applyEndermiteSegments(geometry);
            case "net/minecraft/client/model/monster/dragon/EnderDragonModel#createBodyLayer" ->
                applyEnderDragonNeckAndTail(geometry);
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
     * Adds the 8 tentacle bones that {@code SquidModel.createBodyLayer}'s loop emits.
     * Mirrors the bytecode at offsets 91-198: for {@code i in 0..8},
     * {@code angle = i * 2π / 8} feeds {@code (cos(angle) * 5, 15, sin(angle) * 5)} as the
     * tentacle pivot and {@code -i * π/4 + π/2} as the Y-rotation. The shared cube is the
     * shape pushed once into local slot 5 before the loop:
     * {@code addBox(-1, 0, -1, 2, 18, 2)} at {@code texOffs(48, 0)}.
     *
     * <p>Names follow the {@code createTentacleName(int)} synthetic which compiles to
     * {@code "tentacle_" + i} via {@code makeConcatWithConstants}.
     */
    private static void applySquidTentacles(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        for (int i = 0; i < 8; i++) {
            double pivotAngle = i * TAU / 8.0;
            float px = (float) (Math.cos(pivotAngle) * 5.0);
            float py = 15f;
            float pz = (float) (Math.sin(pivotAngle) * 5.0);
            float ryRadians = (float) (-i * Math.PI / 4.0 + Math.PI / 2.0);
            float ryDegrees = (float) Math.toDegrees(ryRadians);
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(px, py, pz));
            bone.add("rotation", floatArray(0f, ryDegrees, 0f));
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(-1f, 0f, -1f));
            cube.add("size", floatArray(2f, 18f, 2f));
            JsonArray uv = new JsonArray();
            uv.add(48);
            uv.add(0);
            cube.add("uv", uv);
            cube.addProperty("inflate", 0.0);
            cube.addProperty("mirror", false);
            cube.add("face_uv", new JsonObject());
            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            bones.add("tentacle_" + i, bone);
        }
    }

    /**
     * Adds the 12 rod bones that {@code BlazeModel.createBodyLayer}'s three nested loops emit.
     * Mirrors the bytecode at offsets 71-308: three rings of four rods each, all sharing the
     * cube {@code addBox(0, 0, 0, 2, 8, 2)} at {@code texOffs(0, 16)} that the factory pushes
     * once into local slot 3 before the first loop. Per-ring formulas (Java's Y-down frame, so
     * a Java pivot at {@code y=-1} sits 1 unit ABOVE the head pivot at {@code y=0}):
     * <ul>
     * <li>Outer ring (i=0..3, radius 9, angle starts 0 and steps by π/2):
     *     {@code (cos(angle)*9, -2 + cos(i*0.5), sin(angle)*9)}</li>
     * <li>Middle ring (i=4..7, radius 7, angle starts π/4 and steps by π/2):
     *     {@code (cos(angle)*7, 2 + cos(i*0.5), sin(angle)*7)}</li>
     * <li>Inner ring (i=8..11, radius 5, angle starts 3π/20 ≈ 0.4712 and steps by π/2):
     *     {@code (cos(angle)*5, 11 + cos(i*0.75), sin(angle)*5)}</li>
     * </ul>
     *
     * <p>The {@code getPartName(int)} synthetic compiles to {@code "part_" + i} via
     * {@code makeConcatWithConstants}; vanilla authors the same bones as
     * {@code upperBodyParts0..11}. Since the parity test is image-based the chosen name doesn't
     * affect the comparison; using {@code part_N} matches the Java factory's own convention.
     */
    private static void applyBlazeRods(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        // Outer ring: radius 9, y baseline -2, angle starts 0, step π/2.
        applyBlazeRing(bones, 0, 4, 9f, -2f, 0.5f, 0f);
        // Middle ring: radius 7, y baseline +2, angle starts π/4.
        applyBlazeRing(bones, 4, 8, 7f, 2f, 0.5f, (float) (Math.PI / 4.0));
        // Inner ring: radius 5, y baseline +11, angle starts 3π/20, y stride 0.75.
        applyBlazeRing(bones, 8, 12, 5f, 11f, 0.75f, 0.47123894f);
    }

    /**
     * Emits one ring of blaze rods. Vanilla's three loops share the same shape - radius +
     * y-baseline + per-iteration angle increment of {@code π/2} - so they fold cleanly into a
     * single loop here.
     *
     * @param bones the bones JSON object to mutate
     * @param iStart inclusive lower index (loop variable {@code i})
     * @param iEnd exclusive upper index
     * @param radius distance from the central axis
     * @param yBaseline constant added to {@code cos(i * yStride)} for the rod's Y pivot
     * @param yStride argument scale for the per-iteration {@code cos(i * yStride)} term
     * @param angleStart initial angle for {@code i = iStart}; subsequent iterations add {@code π/2}
     */
    private static void applyBlazeRing(
        @NotNull JsonObject bones,
        int iStart,
        int iEnd,
        float radius,
        float yBaseline,
        float yStride,
        float angleStart
    ) {
        float angle = angleStart;
        for (int i = iStart; i < iEnd; i++) {
            // Vanilla {@code BlazeModel.createBodyLayer} uses {@code Mth.cos / Mth.sin} (the
            // 65536-entry sin table). {@link FastTrig} ports the table bit-for-bit; libm
            // {@code Math.cos} drifts by up to ~1.8e-5 vs the table values.
            float px = FastTrig.cos(angle) * radius;
            float py = yBaseline + FastTrig.cos(i * yStride);
            float pz = FastTrig.sin(angle) * radius;
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(px, py, pz));
            bone.add("rotation", floatArray(0f, 0f, 0f));
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(0f, 0f, 0f));
            cube.add("size", floatArray(2f, 8f, 2f));
            JsonArray uv = new JsonArray();
            uv.add(0);
            uv.add(16);
            cube.add("uv", uv);
            cube.addProperty("inflate", 0.0);
            cube.addProperty("mirror", false);
            cube.add("face_uv", new JsonObject());
            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            bones.add("part_" + i, bone);
            angle += (float) (Math.PI / 2.0);
        }
    }

    /**
     * Adds the 12 spike bones that {@code GuardianModel.createBodyLayer}'s loop emits.
     * Mirrors the bytecode at offsets 151-253: per-iteration {@code i = 0..12}, the spike's
     * pivot and rotation are computed from six static {@code float[12]} arrays bound in
     * {@code GuardianModel.<clinit>} ({@code SPIKE_X}, {@code SPIKE_Y}, {@code SPIKE_Z},
     * {@code SPIKE_X_ROT}, {@code SPIKE_Y_ROT}, {@code SPIKE_Z_ROT}). The position is jittered
     * by {@code getSpikeOffset(i, partialTick, swimAnim) = 1 + cos(swimAnim*1.5 + i)*0.01 -
     * partialTick}; at static layer-build time both {@code partialTick} and {@code swimAnim}
     * are zero, so {@code getSpikeOffset(i, 0, 0) = 1 + cos(i) * 0.01} - a near-1 jitter that
     * the spike position is multiplied by, plus a constant {@code +16} on Y.
     *
     * <p>Cube: {@code addBox(-1, -4.5, -1, 2, 9, 2)} at {@code texOffs(0, 0)} (the builder
     * created at offset 129-150 before the loop and reused for every iteration).
     *
     * <p>Spikes are children of the {@code head} bone; the head bone has
     * {@code PartPose.ZERO} so the world pivot equals the local spike pivot. Both
     * {@code GuardianModel#createBodyLayer} (regular guardian) and
     * {@code #createElderGuardianLayer} share the spike loop verbatim - elder guardian wraps
     * the resulting LayerDefinition with {@code MeshTransformer.scaling(2.35f)} which the
     * parser doesn't yet handle (tracked separately in the resume marker).
     */
    private static void applyGuardianSpikes(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        for (int i = 0; i < 12; i++) {
            // Vanilla {@code GuardianModel#getSpikeOffset} uses {@code Mth.cos}, not libm
            // {@code Math.cos}. {@link FastTrig} ports the 65536-entry sin-table bit-for-bit.
            float offset = 1.0f + FastTrig.cos(i) * 0.01f;
            float px = SPIKE_X[i] * offset;
            float py = 16f + SPIKE_Y[i] * offset;
            float pz = SPIKE_Z[i] * offset;
            float rx = (float) Math.toDegrees(Math.PI * SPIKE_X_ROT[i]);
            float ry = (float) Math.toDegrees(Math.PI * SPIKE_Y_ROT[i]);
            float rz = (float) Math.toDegrees(Math.PI * SPIKE_Z_ROT[i]);
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(px, py, pz));
            bone.add("rotation", floatArray(rx, ry, rz));
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(-1f, -4.5f, -1f));
            cube.add("size", floatArray(2f, 9f, 2f));
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
            bones.add("spike_" + i, bone);
        }
    }

    /**
     * Per-spike X position factor; multiplied by {@code getSpikeOffset(i, 0, 0)} at layer build.
     */
    private static final float[] SPIKE_X = { 0f, 0f, 8f, -8f, -8f, 8f, 8f, -8f, 0f, 0f, 8f, -8f };

    /**
     * Per-spike Y position factor; the final Y is {@code 16 + SPIKE_Y[i] * getSpikeOffset(i, 0, 0)}.
     */
    private static final float[] SPIKE_Y = { -8f, -8f, -8f, -8f, 0f, 0f, 0f, 0f, 8f, 8f, 8f, 8f };

    /**
     * Per-spike Z position factor.
     */
    private static final float[] SPIKE_Z = { 8f, -8f, 0f, 0f, -8f, -8f, 8f, 8f, 8f, -8f, 0f, 0f };

    /**
     * Per-spike X rotation in units of π.
     */
    private static final float[] SPIKE_X_ROT = { 1.75f, 0.25f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 1.25f, 0.75f, 0f, 0f };

    /**
     * Per-spike Y rotation in units of π.
     */
    private static final float[] SPIKE_Y_ROT = { 0f, 0f, 0f, 0f, 0.25f, 1.75f, 1.25f, 0.75f, 0f, 0f, 0f, 0f };

    /**
     * Per-spike Z rotation in units of π.
     */
    private static final float[] SPIKE_Z_ROT = { 0f, 0f, 0.25f, 1.75f, 0f, 0f, 0f, 0f, 0f, 0f, 0.75f, 1.25f };

    /**
     * Adds the 8 segment slabs that {@code MagmaCubeModel.createBodyLayer}'s loop emits.
     * Mirrors the bytecode at offsets 13-110: per-iteration {@code i = 0..8}, the segment cube
     * is {@code addBox(-4, 16+i, -4, 8, 1, 8)} at {@code texOffs(j, k)} where the (j, k) offsets
     * branch on {@code i}:
     * <ul>
     * <li>{@code i = 0}: top cap, {@code (j, k) = (0, 0)}</li>
     * <li>{@code 0 < i < 4}: middle-top slabs, {@code (j, k) = (0, 9*i)}</li>
     * <li>{@code i >= 4}: bottom slabs, {@code (j, k) = (32, 9*i - 36)}</li>
     * </ul>
     *
     * <p>All segments share {@code PartPose.ZERO}. Bone names use the {@code getSegmentName(i)}
     * synthetic which compiles to {@code "cube" + i} via {@code makeConcatWithConstants}; the
     * parser can't resolve {@code makeConcatWithConstants} so we use {@code "cube" + i} here
     * to match what vanilla MC emits at runtime. The {@code inside_cube} bone (post-loop
     * literal name) parses correctly via the linear walker; this template only fills the loop
     * gap.
     */
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

    private static void applyMagmaCubeSegments(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        for (int i = 0; i < 8; i++) {
            int j, k;
            if (i == 0) {
                j = 0; k = 0;
            } else if (i < 4) {
                j = 0; k = 9 * i;
            } else {
                j = 32; k = 9 * i - 36;
            }
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(0f, 0f, 0f));
            bone.add("rotation", floatArray(0f, 0f, 0f));
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(-4f, 16f + i, -4f));
            cube.add("size", floatArray(8f, 1f, 8f));
            JsonArray uv = new JsonArray();
            uv.add(j);
            uv.add(k);
            cube.add("uv", uv);
            cube.addProperty("inflate", 0.0);
            cube.addProperty("mirror", false);
            cube.add("face_uv", new JsonObject());
            JsonArray cubes = new JsonArray();
            cubes.add(cube);
            bone.add("cubes", cubes);
            bones.add("cube" + i, bone);
        }
    }

    /**
     * Per-segment {@code int[]{sx, sy, sz}} from {@code SilverfishModel.<clinit>} {@code BODY_SIZES}.
     * The factory's loop reads these as cube width / height / depth and as the cumulative-pivot
     * stride for adjacent segments - duplicated here verbatim because the parser doesn't unroll
     * static {@code int[][]} field reads with dynamic indices.
     */
    private static final int[][] SILVERFISH_BODY_SIZES = {
        { 3, 2, 2 }, { 4, 3, 2 }, { 6, 4, 3 }, { 3, 3, 3 },
        { 2, 2, 3 }, { 2, 1, 2 }, { 1, 1, 2 }
    };

    /**
     * Per-segment {@code int[]{u, v}} texOffs from {@code SilverfishModel.<clinit>} {@code BODY_TEXS}.
     */
    private static final int[][] SILVERFISH_BODY_TEXS = {
        { 0, 0 }, { 0, 4 }, { 0, 9 }, { 0, 16 }, { 0, 22 }, { 11, 0 }, { 13, 4 }
    };

    /**
     * Adds the 7 segment bones plus 3 spike-layer bones that {@code SilverfishModel.createBodyLayer}
     * emits via its segment loop and the post-loop {@code layer0/1/2} block. Mirrors the bytecode
     * at offsets 24-178 (segment loop) and 179-348 (layers).
     *
     * <p>Per-segment formula (loop body, {@code i} in {@code 0..7}):
     * <ul>
     *   <li>{@code texOffs(BODY_TEXS[i][0], BODY_TEXS[i][1])}</li>
     *   <li>{@code addBox(-0.5*sx, 0, -0.5*sz, sx, sy, sz)} where {@code sx, sy, sz = BODY_SIZES[i]}</li>
     *   <li>{@code pivot = (0, 24 - sy, f)} where {@code f} starts at {@code -3.5} and accumulates
     *       {@code +0.5 * (BODY_SIZES[i][2] + BODY_SIZES[i+1][2])} per iteration</li>
     * </ul>
     *
     * <p>Spike-layer bones reference {@code BODY_SIZES[2][2]=3} (layer0 z size and z origin scale),
     * {@code BODY_SIZES[4][2]=3} (layer1/2 z origin scale), {@code BODY_SIZES[1][2]=2} (layer1/2 z
     * size), and the cumulative pivot values {@code f[2]=1.0}, {@code f[1]=-1.5} for the layer pivots.
     *
     * <p>Bone names match {@code getSegmentName(i)} ({@code "segment" + i}) and
     * {@code getLayerName(i)} ({@code "layer" + i}) - the format strings are encoded in the
     * {@code makeConcatWithConstants} bootstrap arguments at constant-pool entries
     * {@code segment} and {@code layer} (verified via {@code javap -v}). Matches what
     * vanilla MC emits at runtime so the template overwrites whatever degenerate bones the parser
     * synthesised on the loop's first iteration.
     */
    private static void applySilverfishSegments(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        float[] f = computeCumulativePivot(SILVERFISH_BODY_SIZES);
        for (int i = 0; i < SILVERFISH_BODY_SIZES.length; i++) {
            int sx = SILVERFISH_BODY_SIZES[i][0];
            int sy = SILVERFISH_BODY_SIZES[i][1];
            int sz = SILVERFISH_BODY_SIZES[i][2];
            int u = SILVERFISH_BODY_TEXS[i][0];
            int v = SILVERFISH_BODY_TEXS[i][1];
            bones.add("segment" + i, segmentBone(0f, 24f - sy, f[i], -0.5f * sx, 0f, -0.5f * sz, sx, sy, sz, u, v));
        }
        // Spike layers: pivot Z values mirror the segment loop's f[] indices that the bytecode
        // pulled from local slot 2 ({@code aload_2; iconst_X; faload}).
        bones.add("layer0", segmentBone(0f, 16f, f[2],
            -5f, 0f, SILVERFISH_BODY_SIZES[2][2] * -0.5f,
            10f, 8f, SILVERFISH_BODY_SIZES[2][2], 20, 0));
        bones.add("layer1", segmentBone(0f, 20f, f[4],
            -3f, 0f, SILVERFISH_BODY_SIZES[4][2] * -0.5f,
            6f, 4f, SILVERFISH_BODY_SIZES[4][2], 20, 11));
        bones.add("layer2", segmentBone(0f, 19f, f[1],
            -3f, 0f, SILVERFISH_BODY_SIZES[4][2] * -0.5f,
            6f, 5f, SILVERFISH_BODY_SIZES[1][2], 20, 18));
    }

    /**
     * Per-segment {@code int[]{sx, sy, sz}} from {@code EndermiteModel.<clinit>} {@code BODY_SIZES}.
     */
    private static final int[][] ENDERMITE_BODY_SIZES = {
        { 4, 3, 2 }, { 6, 4, 5 }, { 3, 3, 1 }, { 1, 2, 1 }
    };

    /**
     * Per-segment {@code int[]{u, v}} texOffs from {@code EndermiteModel.<clinit>} {@code BODY_TEXS}.
     */
    private static final int[][] ENDERMITE_BODY_TEXS = {
        { 0, 0 }, { 0, 5 }, { 0, 14 }, { 0, 18 }
    };

    /**
     * Adds the 4 segment bones that {@code EndermiteModel.createBodyLayer} emits via its segment
     * loop. Mirrors the bytecode at offsets 18-153; identical loop body to silverfish (same
     * {@code -0.5 * size} cube origin, same {@code 24 - sy} Y pivot, same {@code +0.5 * (sz_i +
     * sz_{i+1})} cumulative-Z stride starting at {@code -3.5}). Bone names match
     * {@code createSegmentName(int)} - verified as {@code "segment" + i} via the
     * {@code makeConcatWithConstants} bootstrap arg {@code segment}.
     */
    private static void applyEndermiteSegments(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        float[] f = computeCumulativePivot(ENDERMITE_BODY_SIZES);
        for (int i = 0; i < ENDERMITE_BODY_SIZES.length; i++) {
            int sx = ENDERMITE_BODY_SIZES[i][0];
            int sy = ENDERMITE_BODY_SIZES[i][1];
            int sz = ENDERMITE_BODY_SIZES[i][2];
            int u = ENDERMITE_BODY_TEXS[i][0];
            int v = ENDERMITE_BODY_TEXS[i][1];
            bones.add("segment" + i, segmentBone(0f, 24f - sy, f[i], -0.5f * sx, 0f, -0.5f * sz, sx, sy, sz, u, v));
        }
    }

    /**
     * Adds the 5 neck bones and 12 tail bones that {@code EnderDragonModel.createBodyLayer}'s
     * two loops emit. Both loops share a pre-built {@code CubeListBuilder} (local slot 4) with
     * two named cubes:
     * <ul>
     *   <li>{@code "box"}: {@code addBox(-5, -5, -5, 10, 10, 10)} at {@code texOffs(192, 104)}</li>
     *   <li>{@code "scale"}: {@code addBox(-1, -9, -3, 2, 4, 6)} at {@code texOffs(48, 0)}</li>
     * </ul>
     *
     * <p>Per-iteration:
     * <ul>
     *   <li>Neck (i=0..4): {@code name = "neck" + i}, {@code pivot = (0, 20, -12 - i*10)} - parts
     *       extend forward in -Z from the body, 10 units apart.</li>
     *   <li>Tail (i=0..11): {@code name = "tail" + i}, {@code pivot = (0, 10, 60 + i*10)} - parts
     *       extend backward in +Z from the body, 10 units apart.</li>
     * </ul>
     *
     * <p>Both loops use the same {@code makeConcatWithConstants} pattern as silverfish /
     * endermite for bone names (verified via {@code javap -v} bootstrap method args
     * {@code neck} and {@code tail}). Pre-built CubeListBuilder pattern means parent translation
     * isn't an issue here - both neck and tail parts are children of root with pivots in
     * absolute root-frame space.
     */
    private static void applyEnderDragonNeckAndTail(@NotNull JsonObject geometry) {
        JsonObject bones = geometry.getAsJsonObject("bones");
        if (bones == null) return;
        for (int i = 0; i < 5; i++)
            bones.add("neck" + i, dragonNeckTailBone(0f, 20f, -12f - i * 10f));
        for (int i = 0; i < 12; i++)
            bones.add("tail" + i, dragonNeckTailBone(0f, 10f, 60f + i * 10f));
    }

    /**
     * Builds one neck-or-tail bone JSON with the pre-built {@code [box, scale]} cube pair from
     * {@code EnderDragonModel.createBodyLayer}'s slot-4 builder. Both cubes are the same
     * regardless of which loop emits the bone; only the bone's pivot differs.
     */
    private static @NotNull JsonObject dragonNeckTailBone(float px, float py, float pz) {
        JsonObject bone = new JsonObject();
        bone.add("pivot", floatArray(px, py, pz));
        bone.add("rotation", floatArray(0f, 0f, 0f));
        JsonArray cubes = new JsonArray();
        cubes.add(makeCube(-5f, -5f, -5f, 10f, 10f, 10f, 192, 104));
        cubes.add(makeCube(-1f, -9f, -3f, 2f, 4f, 6f, 48, 0));
        bone.add("cubes", cubes);
        return bone;
    }

    /**
     * Builds a single cube JSON in the parser's serialisation shape. Used by templates that
     * need to emit raw cube records (origin/size/uv/inflate/mirror/face_uv) without going
     * through the {@code segmentBone} single-cube wrapper.
     */
    private static @NotNull JsonObject makeCube(float ox, float oy, float oz, float sx, float sy, float sz, int u, int v) {
        JsonObject cube = new JsonObject();
        cube.add("origin", floatArray(ox, oy, oz));
        cube.add("size", floatArray(sx, sy, sz));
        JsonArray uv = new JsonArray();
        uv.add(u);
        uv.add(v);
        cube.add("uv", uv);
        cube.addProperty("inflate", 0.0);
        cube.addProperty("mirror", false);
        cube.add("face_uv", new JsonObject());
        return cube;
    }

    /**
     * Computes the cumulative pivot-Z array shared by silverfish and endermite. The factory loops
     * track a running {@code f} value starting at {@code -3.5} and add
     * {@code 0.5 * (sizes[i][2] + sizes[i+1][2])} after writing {@code f} into the segment's Z
     * pivot. {@code f} is stored in a local float[] and consumed by the post-loop spike-layer
     * bones in silverfish; endermite has no post-loop consumers but the formula is identical.
     */
    private static float[] computeCumulativePivot(int[][] sizes) {
        float[] out = new float[sizes.length];
        float f = -3.5f;
        for (int i = 0; i < sizes.length; i++) {
            out[i] = f;
            if (i < sizes.length - 1)
                f += 0.5f * (sizes[i][2] + sizes[i + 1][2]);
        }
        return out;
    }

    /**
     * Builds a single-cube bone JSON shaped like the parser's per-bone records.
     */
    private static @NotNull JsonObject segmentBone(
        float px, float py, float pz,
        float ox, float oy, float oz,
        float sx, float sy, float sz,
        int u, int v
    ) {
        JsonObject bone = new JsonObject();
        bone.add("pivot", floatArray(px, py, pz));
        bone.add("rotation", floatArray(0f, 0f, 0f));
        JsonObject cube = new JsonObject();
        cube.add("origin", floatArray(ox, oy, oz));
        cube.add("size", floatArray(sx, sy, sz));
        JsonArray uv = new JsonArray();
        uv.add(u);
        uv.add(v);
        cube.add("uv", uv);
        cube.addProperty("inflate", 0.0);
        cube.addProperty("mirror", false);
        cube.add("face_uv", new JsonObject());
        JsonArray cubes = new JsonArray();
        cubes.add(cube);
        bone.add("cubes", cubes);
        return bone;
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
