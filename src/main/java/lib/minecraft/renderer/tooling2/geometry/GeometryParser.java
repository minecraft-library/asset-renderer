package lib.minecraft.renderer.tooling2.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.tooling2.kernel.AsmKit;
import lib.minecraft.renderer.tooling2.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling2.kernel.Diagnostics;
import lib.minecraft.renderer.tooling2.kernel.JsonNode;
import lib.minecraft.renderer.tooling2.kernel.VanillaSourceClasses;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared ASM bytecode walker for vanilla {@code LayerDefinition.create / CubeListBuilder /
 * PartPose / addOrReplaceChild} geometry - the KEPT tooling2 copy of the legacy parser with
 * the edge refit ONLY (SPINE decision 31): cache-fed, {@link GeometryRequest} in,
 * {@link JsonNode} out, 3-component grow. Internals are verbatim (hard-won comments intact,
 * {@link FastTrig} stays nested); the Mode/WalkState/CallFrame rehab is staged for phase 8
 * under a byte pin. Consumed by {@code GeometryFlow} for both the entity and block flows -
 * the bytecode shape is identical; only the requests differ.
 *
 * <p>Internal JSON assembly deliberately stays on Gson types as part of the verbatim body
 * (flagged, not silent: converting ~3000 lines to JsonNode would be the internal
 * restructuring the phase-8 byte pin exists to gate); {@link JsonNode#wrap} adapts the
 * output edge.
 *
 * <p>Parses the {@code createSingleBodyLayer()} / {@code createBodyLayer()} methods of
 * model classes to extract cube definitions, UV offsets, pivot points, and texture
 * dimensions into {@link EntityModelData}-compatible JSON. Tracks a synthetic operand stack
 * of numeric literals plus per-slot local-variable state and recognises the canonical
 * builder-chain pattern:
 * <pre>{@code
 * root.addOrReplaceChild("name",
 *     CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d),
 *     PartPose.offset(px, py, pz));
 * }</pre>
 * Each {@code addOrReplaceChild} call emits a bone with its cubes. The texture dimensions
 * are extracted from the final {@code LayerDefinition.create(mesh, texW, texH)} call.
 *
 * <p>Beyond the flat builder chain, the walker also reconstructs: parent/child bone
 * hierarchies ({@code head.addOrReplaceChild(...)} + {@code getChild}), {@code CubeDeformation}
 * inflate, {@code PartPose.offsetAndRotation} / {@code scaled}, {@code MeshTransformer.scaling}
 * layer scale, {@code retainPartsAndChildren} / {@code clearChild} bone pruning, cross-class
 * {@code invokestatic} delegation (inlining the callee's body with save/restore of call-frame
 * locals), and javac procedural for-loop unrolling (tentacle / spike / segment loops) with a
 * miniature arithmetic evaluator for the loop-body expressions.
 */
@UtilityClass
public final class GeometryParser {

    /**
     * JVM field-descriptor form of {@code net.minecraft.client.model.geom.builders.MeshTransformer}
     * ({@code L<...>/MeshTransformer;}), used to match {@code GETSTATIC} / {@code PUTSTATIC} of
     * static {@code MeshTransformer} fields and {@code scaling} factory return types.
     */
    private static final @NotNull String MESH_TRANSFORMER_DESC = VanillaSourceClasses.Descs.MESH_TRANSFORMER_REF;


    /**
     * Parses one {@link GeometryRequest}'s factory bytecode and returns the raw parse
     * product - {@code textureWidth}, {@code textureHeight}, {@code bones} - as a
     * {@link JsonNode}, or {@code null} when the parse produced no bone with cubes.
     * Schema assembly (the {@code source} twin, {@code texture_size} pairing, overrides,
     * {@code y_axis}, {@code cull}) is {@code GeometryFlow}'s job; missing classes /
     * methods (typically a MC version bump rename) and parse failures are ERRORs on
     * {@code diagnostics} with a {@code null} return, never a literal fallback
     * (decision 10).
     *
     * @param cache the session's jar cache
     * @param request the parse to perform
     * @param diagnostics the flow's diagnostics scope
     * @return the parse product ({@code textureWidth}, {@code textureHeight},
     *     {@code bones}), or {@code null} when nothing was emitted
     */
    public static @Nullable JsonNode parse(@NotNull ClassNodeCache cache, @NotNull GeometryRequest request, @NotNull Diagnostics diagnostics) {
        ClassNode classNode = cache.load(request.factoryClass());
        if (classNode == null) {
            diagnostics.error("%s: class '%s' not found in client jar (renamed in MC version bump?)", request.subjectId(), request.factoryClass());
            return null;
        }
        MethodNode method = AsmKit.findMethod(classNode, request.factoryMethod());
        if (method == null) {
            diagnostics.error("%s: method '%s' not found on class '%s' (renamed in MC version bump?)", request.subjectId(), request.factoryMethod(), request.factoryClass());
            return null;
        }
        try {
            JsonObject model = parseLayerMethod(method.instructions, cache, request, diagnostics);
            return model == null ? null : JsonNode.wrap(model);
        } catch (Exception ex) {
            diagnostics.error("%s: parse failure - %s", request.subjectId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Parses a single layer-creation method's bytecode and extracts the model geometry.
     * Invokestatic calls targeting other model-building methods (not in the builder/geom
     * package) are followed recursively so chains like
     * {@code PiglinHeadModel.createHeadModel -> PiglinModel.addHead} resolve without
     * needing a dedicated source entry per delegate.
     *
     * <p>Seeds a fresh {@link ParseState} from the {@link GeometryRequest}'s substitution
     * hooks ({@code paramIntValues} / {@code paramFloatValues} / {@code grow} /
     * {@code appliedMeshTransformerScale} / {@code refParam}), runs {@link #walkInstructions},
     * then applies the post-walk passes ({@link #applyRetainedNamesFilter},
     * {@link #applyClearedBonesFilter}, {@link #applyMeshTransformerScaling}) before
     * serialising the accumulated bones.
     *
     * @param instructions the layer-creation method's bytecode
     * @param cache the session's jar cache, for resolving inlined statics and static-array {@code <clinit>}s
     * @param request the request whose substitution hooks and diagnostics tag seed the parse
     * @param diagnostics diagnostic sink for underflow / overflow / unhandled-dispatch warnings
     * @return the model JSON ({@code textureWidth}, {@code textureHeight}, {@code bones}), or
     *     {@code null} when no bone with cubes was emitted
     */
    private static @Nullable JsonObject parseLayerMethod(@NotNull InsnList instructions, @NotNull ClassNodeCache cache, @NotNull GeometryRequest request, @NotNull Diagnostics diagnostics) {
        ParseState state = new ParseState();
        state.paramIntValues = request.paramIntValues();
        state.paramFloatValues = request.paramFloatValues();
        // A request carrying a float-substitution table opts into the EVALUATING walk
        // (arithmetic / loops / inflate); its absence is the byte-stable LEGACY_LINEAR
        // block-entity walk. Fixed once here - the mode is invariant for the parse.
        state.mode = request.paramFloatValues() != null ? Mode.EVALUATING : Mode.LEGACY_LINEAR;
        // Pre-seed pendingInflate from the request's grow so factory methods that take a
        // {@code CubeDeformation} arg (instead of constructing one inline) emit cubes with
        // the call-site-provided inflate. The composite-overlay flow uses this for
        // {@code DROWNED_OUTER_LAYER} -> {@code DrownedModel.createBodyLayer(new
        // CubeDeformation(0.25F))}: the parser starts inside createBodyLayer where the 0.25
        // is invisible, but the request carries it through {@code grow} (3-component -
        // decision 24; the lossy /3 average dies).
        state.defaultInflate = request.grow().clone();
        state.pendingInflate = request.grow().clone();
        // Pre-seed meshTransformerScale from the resolver-captured chain on the request.
        // The resolver picks up LayerDefinitions-level {@code .apply(MeshTransformer)}
        // chains that don't appear inline in the factory body (cat / horse) - the F lives
        // on a class-level static field or a local slot in {@code createRoots}, not in the
        // model's own {@code createBodyLayer}. Folds with inline {@code MeshTransformer.scaling}
        // captures during the walk so both layers compose correctly.
        state.meshTransformerScale = request.appliedMeshTransformerScale();
        // Bind an object-reference parameter to a concrete enum constant so the parser can
        // follow {@code if (attachment == Attachment.X)} branches - splits
        // {@code createHangingSignLayer(Attachment)} into one mesh per attachment.
        GeometryRequest.RefParam refParam = request.refParam();
        if (refParam != null) {
            state.refParamSlot = refParam.slot();
            state.refParamOwner = refParam.ownerInternal();
            state.refParamValue = refParam.value();
        }
        state.currentSource = request;
        state.diagnostics = diagnostics;
        walkInstructions(instructions, state, cache);

        // Literals left on the numeric stack after a parse usually mean a method-owner
        // descriptor we didn't recognise pushed arguments we never consumed. Kept at INFO
        // severity (does not fail strict mode) because end-of-method leftovers don't
        // corrupt output - only underflow does, and that has its own strict-failing
        // diagnostic at every addBox / PartPose site. The three 26.1 sources that
        // currently hit this ({@code decorated_pot}, {@code copper_golem_statue},
        // {@code skull_dragon_head}) all produce correct geometry; the leftovers are
        // just accounting gaps in the parser's method-owner dispatch.
        if (!state.numStack.isEmpty())
            diagnostics.info("%s: %d leftover literal(s) on numStack after parse - unhandled method-owner descriptor?", request.subjectId(), state.numStack.size());

        applyRetainedNamesFilter(state);
        applyClearedBonesFilter(state);
        applyMeshTransformerScaling(state);

        if (state.bones.isEmpty()) return null;

        JsonObject model = new JsonObject();
        model.addProperty("textureWidth", state.texWidth);
        model.addProperty("textureHeight", state.texHeight);
        model.add("bones", state.bones);
        return model;
    }

    /**
     * Resolves a {@code GETSTATIC <owner>.<name> : MeshTransformer} reference back to the
     * scaling factor F by walking the owning class's {@code <clinit>}. Matches the canonical
     * pattern
     * <pre>{@code
     *   ldc F
     *   invokestatic MeshTransformer.scaling(F)MeshTransformer
     *   putstatic <name> : MeshTransformer
     * }</pre>
     * Tracks a tiny synthetic stack across the {@code <clinit>} walk: an {@code LDC} of a
     * {@code Float} sets {@code pendingFloat}; an {@code INVOKESTATIC} on
     * {@code MeshTransformer.scaling} consumes {@code pendingFloat} and sets the "scaled"
     * marker {@code pendingScaled} carrying F; a {@code PUTSTATIC} of a {@code MeshTransformer}
     * field consumes {@code pendingScaled} and records {@code field -> F}. Any unrelated
     * instruction clears {@code pendingFloat} (so a stale F can't bind to a later putstatic
     * preceded by other init work) but deliberately keeps {@code pendingScaled}, so the
     * canonical {@code ldc / invokestatic / putstatic} triplet still binds even with
     * no-op-ish instructions between the invokestatic and the putstatic. Non-canonical
     * initialisers (combined transformers from {@code invokedynamic}, math on F, etc.) never
     * reach the scaling-invokestatic branch, so their field maps to {@code null} and the
     * caller defaults to no scale.
     * <p>
     * Memoised per cache instance inside {@link AsmKit#resolveStaticScalingFactor} so repeat
     * references across the whole session don't re-walk. The walk binds <b>every</b>
     * canonical {@code MeshTransformer} field it sees in the {@code <clinit>}, not just the
     * requested one, so sibling fields resolve without re-walking.
     *
     * @param owner the field's owning class internal name
     * @param name the static {@code MeshTransformer} field name being resolved
     * @param cache the session's jar cache
     * @return F when the field's {@code <clinit>} initialiser is a literal
     *     {@code MeshTransformer.scaling(F)}; {@code null} for unhandled patterns
     */
    private static @Nullable Float resolveStaticMeshTransformer(
        @NotNull String owner, @NotNull String name, @NotNull ClassNodeCache cache
    ) {
        return AsmKit.resolveStaticScalingFactor(cache, owner, name,
            VanillaSourceClasses.Types.MESH_TRANSFORMER, VanillaSourceClasses.Methods.SCALING, MESH_TRANSFORMER_DESC);
    }

    /**
     * Resolves a static {@code MeshTransformer} field whose {@code <clinit>} initialises it
     * via an {@code invokedynamic apply -> lambda$static$N} pair to the underlying
     * {@code modifyMesh(PartDefinition)V} method the lambda invokes. This is the canonical
     * vanilla pattern for transformers that mutate a {@code MeshDefinition} rather than wrap
     * it with a uniform scale - {@code DonkeyModel.DONKEY_TRANSFORMER} is the only example in
     * vanilla 26.1: it appends taller ear bones and adds {@code left_chest} / {@code right_chest}
     * to the base {@code AbstractEquineModel} mesh before the per-renderer scale is applied.
     *
     * <p>The expected {@code <clinit>} shape is
     * <pre>{@code
     *   invokedynamic apply -> lambda$static$N (LambdaMetafactory)
     *   putstatic     <fieldName> : MeshTransformer
     * }</pre>
     * with {@code lambda$static$N} body
     * <pre>{@code
     *   aload_0                    // MeshDefinition
     *   invokevirtual getRoot      // -> PartDefinition
     *   invokestatic  modifyMesh   // (PartDefinition)V
     *   aload_0
     *   areturn
     * }</pre>
     * Returns the {@code modifyMesh} {@link MethodNode} the caller can feed to
     * {@link #inlineStaticMethodBody}, resolved through the class hierarchy so a
     * {@code modifyMesh} defined on a superclass still binds. Returns {@code null} for any
     * non-matching shape (different bootstrap, lambda handle not an {@code H_INVOKESTATIC} on
     * {@code owner}, no lambda body, lambda doesn't invoke a {@code (PartDefinition)V} static).
     *
     * @param owner the field's owning class internal name
     * @param fieldName the static {@code MeshTransformer} field name being resolved
     * @param cache the session's jar cache
     * @return the {@code modifyMesh(PartDefinition)V} method node, or {@code null} for any
     *     non-matching {@code <clinit>} shape
     */
    private static @Nullable MethodNode findStaticModifyMeshTarget(
        @NotNull String owner, @NotNull String fieldName, @NotNull ClassNodeCache cache
    ) {
        ClassNode cls = cache.load(owner);
        MethodNode clinit = cls != null ? AsmKit.findMethod(cls, AsmKit.CLINIT) : null;
        if (clinit == null) return null;

        InvokeDynamicInsnNode pendingIndy = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (AsmKit.isPseudoNode(in)) continue;
            if (in instanceof InvokeDynamicInsnNode indy && AsmKit.isLambdaInvokeDynamic(indy)) {
                pendingIndy = indy;
                continue;
            }
            if (in instanceof FieldInsnNode fi
                && in.getOpcode() == Opcodes.PUTSTATIC
                && MESH_TRANSFORMER_DESC.equals(fi.desc)
                && fi.owner.equals(owner)
                && fi.name.equals(fieldName)
                && pendingIndy != null) {
                Handle handle = AsmKit.extractLambdaHandle(pendingIndy);
                if (handle == null
                    || handle.getTag() != Opcodes.H_INVOKESTATIC
                    || !handle.getOwner().equals(owner)) return null;
                MethodNode lambda = AsmKit.findMethod(cls, handle.getName(), handle.getDesc());
                if (lambda == null) return null;
                // The lambda body is the canonical `mesh.getRoot(); modifyMesh(...);
                // aload_0; areturn` pattern. Find the first INVOKESTATIC whose descriptor is
                // (Lnet/.../PartDefinition;)V - that's the modifyMesh-style callback.
                for (AbstractInsnNode body = lambda.instructions.getFirst(); body != null; body = body.getNext()) {
                    if (AsmKit.isPseudoNode(body)) continue;
                    if (body instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.desc.startsWith("(L" + VanillaSourceClasses.Types.PART_DEFINITION + ";)V")) {
                        return AsmKit.findMethodInHierarchy(cache, mi.owner, mi.name, mi.desc);
                    }
                }
                return null;
            }
            pendingIndy = null;
        }
        return null;
    }

    /**
     * Bakes the captured {@link ParseState#meshTransformerScale} (from
     * {@code MeshTransformer.scaling(F)} call(s) on the {@code LayerDefinition}) into every
     * emitted bone. Vanilla's expansion is, per {@code PartPose},
     * {@code pose.scaled(F).translated(0, 24.016*(1-F), 0)} - scales bone pivots uniformly
     * around the entity's feet anchor at {@code y=24.016 pixels} (= {@code 1.501 blocks * 16
     * px/block}, the LER chain's {@code translate(0, -1.501, 0)}) and multiplies the bone's
     * {@code PartPose.scale} field by F. Both halves land here together; the kit's
     * {@code EntityGeometryKit#buildTriangles} consumes the
     * {@code scale} field to multiply local cube vertices by F at the pivot translate, which
     * is algebraically equivalent to vanilla's per-vertex {@code poseStack.scale(F)} call
     * sitting AFTER the pivot translate and BEFORE the cube render.
     * <p>
     * Cubes ({@code origin}, {@code size}, {@code inflate}, {@code uv}) are left untouched -
     * the kit applies the scale to cube vertices at render time without affecting UV
     * resolution. No-op when {@code meshTransformerScale == 1f} (the common case) so
     * byte-stable legacy + non-scaling entity parses stay byte-stable.
     * <p>
     * The feet-anchor {@code +dy} translate is a single root-level translate. Descendants
     * inherit the translate through the parent chain, so {@code +dy} is applied to top-level
     * bones only ({@code parent == null}); applying it to every bone would double-count it
     * down the chain. The {@code f *} spacing scale applies to every bone (chain translations
     * sum, so a uniform per-bone {@code f} yields a uniform model scale).
     *
     * @param state the parse state whose emitted bones are re-walked and scaled in place
     */
    private static void applyMeshTransformerScaling(@NotNull ParseState state) {
        float f = state.meshTransformerScale;
        if (f == 1f) return;
        float dy = 24.016f * (1f - f);
        for (Map.Entry<String, JsonElement> entry : state.bones.entrySet()) {
            JsonObject bone = entry.getValue().getAsJsonObject();
            // Only top-level bones (no parent) get the feet-anchor +dy translate; descendants
            // inherit it via the parent chain at render time.
            boolean applyDy = state.boneParents.get(entry.getKey()) == null;
            JsonArray pivot = bone.getAsJsonArray("pivot");
            if (pivot != null && pivot.size() == 3) {
                float px = pivot.get(0).getAsFloat();
                float py = pivot.get(1).getAsFloat();
                float pz = pivot.get(2).getAsFloat();
                JsonArray scaled = new JsonArray();
                scaled.add(f * px);
                scaled.add(f * py + (applyDy ? dy : 0f));
                scaled.add(f * pz);
                bone.add("pivot", scaled);
            }
            float existing = bone.has("scale") ? bone.get("scale").getAsFloat() : 1f;
            float combined = existing * f;
            if (combined == 1f) {
                bone.remove("scale");
            } else {
                bone.addProperty("scale", combined);
            }
        }
    }

    /**
     * Drops bones whose ancestor chain (self -> root) contains no name in
     * {@link ParseState#retainedNames}. Mirrors the effect of
     * {@code PartDefinition.retainPartsAndChildren(Set)} on the mesh root: vanilla replaces
     * every non-retained bone's cubes with empty (recursing through children, leaving
     * subtrees rooted at a retained name fully intact). Since {@link #flushPendingBone}
     * skips JSON emission for cube-less bones, removing the JSON entry produces the same
     * render output as vanilla's "strip cubes, keep empty placeholder" path.
     *
     * <p>No-op when {@code retainedNames} is null (no filter requested) or empty (vanishingly
     * unusual, would drop every bone). Walks via {@link ParseState#boneParents} populated
     * during {@link #flushPendingBone}.
     *
     * @param state the parse state whose {@link ParseState#bones} are filtered in place
     */
    private static void applyRetainedNamesFilter(@NotNull ParseState state) {
        Set<String> retained = state.retainedNames;
        if (retained == null) return;
        List<String> toRemove = new java.util.ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : state.bones.entrySet()) {
            if (!hasRetainedAncestor(entry.getKey(), retained, state.boneParents))
                toRemove.add(entry.getKey());
        }
        for (String name : toRemove) state.bones.remove(name);
    }

    /**
     * Drops every bone named in {@link ParseState#clearedBones} along with every descendant
     * (walked via {@link ParseState#boneParents}). Mirrors {@code PartDefinition.clearChild}'s
     * cascading delete - removing a child from a PartDefinition orphans its sub-tree, which
     * vanilla then renders nothing for. The canonical case (AdultPiglinModel) clears a leaf
     * ("hat"), so the descendant walk is just a safety net for future models that might
     * prune a non-leaf.
     *
     * <p>No-op when {@link ParseState#clearedBones} is empty.
     *
     * @param state the parse state whose {@link ParseState#bones} are filtered in place
     */
    private static void applyClearedBonesFilter(@NotNull ParseState state) {
        if (state.clearedBones.isEmpty()) return;
        Set<String> toRemove = new LinkedHashSet<>(state.clearedBones);
        // Expand to include descendants: any bone whose parent chain hits a cleared name.
        for (String candidate : state.boneParents.keySet()) {
            if (toRemove.contains(candidate)) continue;
            String cursor = state.boneParents.get(candidate);
            Set<String> seen = new LinkedHashSet<>();
            while (cursor != null && seen.add(cursor)) {
                if (toRemove.contains(cursor)) {
                    toRemove.add(candidate);
                    break;
                }
                cursor = state.boneParents.get(cursor);
            }
        }
        for (String name : toRemove) state.bones.remove(name);
    }

    /**
     * Returns {@code true} if {@code name} or any of its ancestors (walked via
     * {@code parents}) appears in {@code retained}. Traversal stops at the first cycle or
     * when the parent chain bottoms out at {@code null} (root).
     *
     * @param name the bone to test
     * @param retained the set of retained bone names
     * @param parents the child-bone &rarr; parent-bone map
     * @return {@code true} when {@code name} or any ancestor is retained
     */
    private static boolean hasRetainedAncestor(
        @NotNull String name,
        @NotNull Set<String> retained,
        @NotNull Map<String, String> parents
    ) {
        Set<String> seen = new LinkedHashSet<>();
        String cursor = name;
        while (cursor != null && seen.add(cursor)) {
            if (retained.contains(cursor)) return true;
            cursor = parents.get(cursor);
        }
        return false;
    }

    /**
     * Walks an instruction list, accumulating numeric literals on a stack and matching
     * builder-chain patterns. Recurses via {@link #handleMethodInsn}'s invokestatic-follow
     * branch so a single {@link ParseState} spans the entire dispatch chain.
     *
     * <p>Thin wrapper around {@link #walkRange}; the per-instruction logic lives in
     * {@link #handleInstruction}. Split into three methods so for-loop unrolling can
     * recursively re-enter the same per-instruction dispatch over a sub-range of the
     * same {@code InsnList}.
     *
     * @param instructions the instruction list to walk end to end
     * @param state the parse state to mutate
     * @param cache the session's jar cache
     */
    private static void walkInstructions(@NotNull InsnList instructions, @NotNull ParseState state, @NotNull ClassNodeCache cache) {
        walkRange(instructions, instructions.getFirst(), null, state, cache);
    }

    /**
     * Walks {@code [first, endExclusive)} of {@code instructions} via repeated
     * {@link #handleInstruction} dispatch. When {@code endExclusive} is {@code null} the
     * walk continues until {@code node.getNext()} returns null (i.e. end of the list).
     *
     * <p>{@link #handleInstruction} returns the node to advance from - normally that's the
     * original {@code node} (caller advances to its next), but for taken jumps / switch
     * branches it's the jump target so the caller advances past it.
     *
     * @param instructions the enclosing instruction list
     * @param first the first node to process, or {@code null} for an empty walk
     * @param endExclusive the node to stop before, or {@code null} to run to end of list
     * @param state the parse state to mutate
     * @param cache the session's jar cache
     */
    private static void walkRange(
        @NotNull InsnList instructions,
        @Nullable AbstractInsnNode first,
        @Nullable AbstractInsnNode endExclusive,
        @NotNull ParseState state,
        @NotNull ClassNodeCache cache
    ) {
        AbstractInsnNode node = first;
        while (node != null && node != endExclusive) {
            AbstractInsnNode advanceFrom = handleInstruction(instructions, node, state, cache);
            node = advanceFrom.getNext();
        }
    }

    /**
     * Processes a single instruction node, accumulating numeric literals, advancing builder
     * chains, and dispatching to {@link #handleMethodInsn} / {@link #handlePartPose} / etc.
     *
     * <p>Returns the {@link AbstractInsnNode} to advance from: normally the original
     * {@code node} (caller does {@code node.getNext()} to step linearly), but for a taken
     * jump or switch branch it's the jump target so the caller advances past the target's
     * label rather than the jump opcode.
     *
     * @param instructions the enclosing instruction list (for forward-jump / for-loop resolution)
     * @param node the instruction to process
     * @param state the parse state to mutate
     * @param cache the session's jar cache
     * @return the node the caller should advance from via {@code getNext()}
     */
    private static @NotNull AbstractInsnNode handleInstruction(
        @NotNull InsnList instructions,
        @NotNull AbstractInsnNode node,
        @NotNull ParseState state,
        @NotNull ClassNodeCache cache
    ) {
        // Canonical javac for-loop unrolling: when {@code node} opens the
        // {@code <init>; ISTORE slot; ILOAD slot; <bound>; IF_ICMPGE exit ; body ;
        // IINC slot, +step; GOTO test ; exit:} pattern, replay the body N times with
        // {@code paramIntValues[slot] = i} so the body's {@code ILOAD slot} substitution
        // (see the ILOAD handler below) folds to the literal iteration index. Vanilla
        // procedural-loop entity factories (squid / blaze / ghast / magma_cube / guardian
        // / silverfish / endermite / ender_dragon / elder_guardian) all use this exact
        // shape to emit N tentacles / segments / spikes per loop. Skipped when
        // {@code LEGACY_LINEAR} so legacy block-entity literal-stack walkers
        // (which never opted into arithmetic evaluation) keep their byte-stable linear walk.
        //
        // <p>The body range {@code [firstBodyInsn, firstInsnAfterLoop)} naturally contains
        // the trailing {@code IINC} (unhandled - no IINC handler exists, so iterator slot
        // stays at our injected value) and the closing backward {@code GOTO test} (the
        // GOTO handler only follows forward jumps, so it falls through linearly past the
        // exit label and walkRange stops at {@code endExclusive = firstInsnAfterLoop}).
        // Return {@code firstInsnAfterLoop.getPrevious()} so the outer walkRange's
        // {@code .getNext()} lands on {@code firstInsnAfterLoop} - i.e. the parser
        // resumes at the first real instruction after the loop.
        if (state.mode == Mode.EVALUATING) {
            AsmKit.IntForLoop loop = AsmKit.detectIntForLoop(node);
            if (loop != null) {
                int slot = loop.iteratorSlot();
                int[] previousInts = state.paramIntValues;
                int[] working = ensureIntSlotCapacity(previousInts, slot);
                int savedAtSlot = working[slot];
                Number savedNumericLocal = state.numericLocals.remove(slot);
                state.paramIntValues = working;
                try {
                    for (int i = loop.initValue(); i < loop.boundExclusive(); i += loop.step()) {
                        working[slot] = i;
                        // Wipe any numericLocals entry the body may have STORE'd into the
                        // iterator slot in a prior iteration; otherwise the next iteration's
                        // ILOAD <slot> would read the stale captured value and shadow the
                        // freshly-injected paramIntValues[slot] = i.
                        state.numericLocals.remove(slot);
                        walkRange(instructions, loop.firstBodyInsn(), loop.firstInsnAfterLoop(), state, cache);
                    }
                } finally {
                    working[slot] = savedAtSlot;
                    state.paramIntValues = previousInts;
                    if (savedNumericLocal != null) {
                        state.numericLocals.put(slot, savedNumericLocal);
                    } else {
                        state.numericLocals.remove(slot);
                    }
                }
                AbstractInsnNode resumeAt = loop.firstInsnAfterLoop().getPrevious();
                return resumeAt != null ? resumeAt : loop.firstInsnAfterLoop();
            }
        }

        Number literal = readNumericLiteral(node);
        if (literal != null) {
            // LiteralStack auto-evicts the oldest on capacity overflow; surface the first
            // overflow as a WARN so a true accounting bug surfaces (subsequent overflows
            // stay silent to avoid spamming when a broken source pushes 1000 literals).
            boolean willOverflow = state.numStack.size() >= ParseState.NUM_STACK_CAPACITY;
            state.numStack.push(literal);
            if (willOverflow && state.diagnostics != null && !state.overflowWarned && state.currentSource != null) {
                state.diagnostics.warn("%s: numStack overflow (>%d literals) - oldest literals being dropped, pop accounting may be broken", state.currentSource.subjectId(), ParseState.NUM_STACK_CAPACITY);
                state.overflowWarned = true;
            }
            return node;
        }

        int opcode = node.getOpcode();

        // Conditional / unconditional jumps + their JVM-stack-pop accounting. The pop
        // accounting is gated on {@code EVALUATING} (Java pipeline opt-in)
        // so legacy literal-stack walkers keep their literal-only walk. Branch-following
        // remains gated on {@code paramIntValues != null} - without a known parameter
        // value the parser falls through linearly. Decoupling the two gates means Java
        // entities at the top-level Source (where {@code paramIntValues == null} but
        // {@code EVALUATING}) still pop the comparison values, preventing
        // the leftover-literal warnings produced by for-loop {@code IF_ICMPGE} etc.
        if (node instanceof JumpInsnNode jumpInsn) {
            boolean canFollow = state.paramIntValues != null;
            switch (opcode) {
                case Opcodes.GOTO -> {
                    // Forward GOTO only - skips the not-taken branch of an if/else
                    // (vanilla model factories use this for variant dispatch). Backward
                    // GOTOs (loop tails, e.g. the spike loop in
                    // {@code GuardianModel.createBodyMesh}) would loop the linear walker
                    // forever, so they fall through linearly, walking the loop body once.
                    if (canFollow && isForwardJump(instructions, node, jumpInsn.label)) {
                        return jumpInsn.label;
                    }
                }
                case Opcodes.IFEQ, Opcodes.IFNE,
                     Opcodes.IFLT, Opcodes.IFGE,
                     Opcodes.IFGT, Opcodes.IFLE -> {
                    // Unary int comparison: pops 1 int. Java pipeline pops from
                    // numStack via {@link AsmKit.LiteralStack#popLiteralNumber} (which
                    // returns null when the popped entry is the non-literal sentinel,
                    // distinguishing "real compile-time literal" from "marker"); legacy
                    // pipeline pops from branchStack (where ILOAD-of-paramIntValues lives,
                    // used by the banner standing/wall split).
                    Integer value = null;
                    if (state.mode == Mode.EVALUATING && !state.numStack.isEmpty()) {
                        Number popped = state.numStack.popLiteralNumber();
                        if (popped != null) value = popped.intValue();
                    } else if (canFollow && !state.branchStack.isEmpty()) {
                        value = state.branchStack.remove(state.branchStack.size() - 1);
                    }
                    // Branch-following for all six unary comparisons when the value is a
                    // resolved literal. Patterns: IFLE / IF_ICMPGE inside an unrolled loop
                    // body (e.g. MagmaCubeModel's per-iteration `if (i > 0 && i < 4)`)
                    // need full follow so each iteration takes the correct branch.
                    if (canFollow && value != null
                        && AsmKit.evaluateIntComparison(opcode, value, 0)
                        && isForwardJump(instructions, node, jumpInsn.label)) {
                        return jumpInsn.label;
                    }
                }
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                     Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                    // Binary int comparison: pops 2 ints. Used by for-loop exit checks
                    // ({@code iload_N; bipush <limit>; if_icmpge end}) which the parser
                    // doesn't follow back to the loop top, so the operands need cleaning
                    // up to keep numStack aligned for the post-loop code.
                    //
                    // <p>When both operands are resolved literals (e.g. the iterator slot's
                    // injected value vs a literal bound during unrolling), the comparison
                    // is evaluated and the branch followed when satisfied. Non-literal
                    // operands fall through linearly with the JVM stack still aligned -
                    // popLiteralNumber consumes the entry regardless.
                    Integer rhs = null;
                    Integer lhs = null;
                    if (state.mode == Mode.EVALUATING) {
                        if (!state.numStack.isEmpty()) {
                            Number poppedB = state.numStack.popLiteralNumber();
                            if (poppedB != null) rhs = poppedB.intValue();
                        }
                        if (!state.numStack.isEmpty()) {
                            Number poppedA = state.numStack.popLiteralNumber();
                            if (poppedA != null) lhs = poppedA.intValue();
                        }
                    }
                    if (canFollow && lhs != null && rhs != null
                        && AsmKit.evaluateIntComparison(opcode, lhs, rhs)
                        && isForwardJump(instructions, node, jumpInsn.label)) {
                        return jumpInsn.label;
                    }
                }
                case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                     Opcodes.IFNULL, Opcodes.IFNONNULL -> {
                    // Object-reference comparisons. Object refs aren't tracked on
                    // numStack. When an enum-reference parameter is bound (sign-hanging
                    // attachment split), the {@code aload <slot>; getstatic <Enum>.<C>;
                    // if_acmp*} triplet pushed a sentinel + the constant name onto
                    // {@link ParseState#refStack}; pop both and evaluate the comparison so the
                    // parser follows the correct attachment branch.
                    if (state.refParamOwner != null
                        && (opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE)
                        && state.refStack.size() >= 2) {
                        String b = state.refStack.removeLast();
                        String a = state.refStack.removeLast();
                        String constant = ParseState.REF_PARAM_SENTINEL.equals(a) ? b
                            : ParseState.REF_PARAM_SENTINEL.equals(b) ? a : null;
                        boolean sentinelSeen = ParseState.REF_PARAM_SENTINEL.equals(a) || ParseState.REF_PARAM_SENTINEL.equals(b);
                        if (sentinelSeen && constant != null) {
                            boolean equal = constant.equals(state.refParamValue);
                            boolean takeJump = opcode == Opcodes.IF_ACMPEQ ? equal : !equal;
                            if (takeJump && isForwardJump(instructions, node, jumpInsn.label))
                                return jumpInsn.label;
                        }
                    }
                }
                default -> { /* not a jump opcode we model */ }
            }
        }

        // TABLESWITCH / LOOKUPSWITCH evaluation. Follows the same {@code paramIntValues}
        // -driven branch-evaluation gate as IFEQ / IFNE - when the top of
        // {@link ParseState#branchStack} holds a concrete value (put there by a preceding
        // ILOAD of a paramIntValues-registered slot), jump to the matching case label.
        // Otherwise the parser falls through linearly, and - when {@code paramIntValues}
        // is set but the switch value is unknown - surfaces a {@code WARN:} so the
        // maintainer knows an unmodelled dispatch slipped through.
        if (node instanceof TableSwitchInsnNode tableSwitch && state.paramIntValues != null) {
            Integer value = popIntForBranch(state);
            if (value != null) {
                LabelNode target = value >= tableSwitch.min && value <= tableSwitch.max
                    ? tableSwitch.labels.get(value - tableSwitch.min)
                    : tableSwitch.dflt;
                if (isForwardJump(instructions, node, target)) {
                    return target;
                }
            }
            if (state.diagnostics != null && state.currentSource != null)
                state.diagnostics.warn("%s: TABLESWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.subjectId());
        }
        if (node instanceof LookupSwitchInsnNode lookupSwitch && state.paramIntValues != null) {
            Integer value = popIntForBranch(state);
            if (value != null) {
                int idx = lookupSwitch.keys.indexOf(value);
                LabelNode target = idx >= 0 ? lookupSwitch.labels.get(idx) : lookupSwitch.dflt;
                if (isForwardJump(instructions, node, target)) {
                    return target;
                }
            }
            if (state.diagnostics != null && state.currentSource != null)
                state.diagnostics.warn("%s: LOOKUPSWITCH encountered with unknown value - falling through linearly, case bodies may corrupt numStack", state.currentSource.subjectId());
        }

        // ILOAD N: if the source declared a value for slot N, push it onto the
        // branch stack so the upcoming IFEQ / IFNE / switch can evaluate the
        // conditional. If the slot is NOT in {@code paramIntValues} (or the source
        // didn't supply any values), call {@code state.numStack.pushNonLiteral()} to
        // mark the entry as non-literal on {@link ParseState#numStack} - when a
        // downstream addBox / PartPose consumes it, {@link #popIntWithDiagnostics}
        // surfaces a {@code WARN:} so the silent-zero failure mode doesn't get baked
        // into the output cube.
        if (node instanceof VarInsnNode varInsn && opcode == Opcodes.ILOAD) {
            int slot = varInsn.var;
            // numericLocals first: an in-method ISTORE captured a precise value (overrides
            // any param-table default for the same slot). Java pipeline only - legacy
            // literal-stack walkers don't STORE into numericLocals.
            Number local = state.mode == Mode.EVALUATING ? state.numericLocals.get(slot) : null;
            if (local != null) {
                state.numStack.push(local.intValue());
            } else {
                boolean resolved = state.paramIntValues != null && slot >= 0 && slot < state.paramIntValues.length;
                if (resolved) {
                    // Java pipeline ({@code EVALUATING} mode) routes ILOAD through numStack
                    // so call-site-propagated literals (pig's {@code legSize=6}) feed the
                    // subsequent {@code 18 - legSize} {@link Opcodes#ISUB} arithmetic. The
                    // matching IFEQ / IFNE / switch consumer above pops from numStack in
                    // the same gated branch. Legacy pipeline keeps the legacy branchStack
                    // path so banner standing/wall and similar paramIntValues uses are
                    // unaffected.
                    if (state.mode == Mode.EVALUATING)
                        state.numStack.push(state.paramIntValues[slot]);
                    else
                        state.branchStack.add(state.paramIntValues[slot]);
                } else {
                    state.numStack.pushNonLiteral();
                }
            }
        }

        // FLOAD / DLOAD / LLOAD: the value comes from a local variable the parser
        // can't resolve. Call {@code state.numStack.pushNonLiteral()} so the next
        // {@link #popFloatWithDiagnostics} / {@link #popIntWithDiagnostics} surfaces
        // the attribution instead of silently consuming a stale zero off an earlier
        // literal or a fresh zero from an empty stack.
        //
        // When {@code paramFloatValues} is supplied (Java-derived entity sources opt in
        // for arithmetic evaluation), {@code FLOAD slot} substitutes the known value
        // so adjacent {@code FADD}/{@code FMUL}/etc. ops can fold in the parameter.
        if (node instanceof VarInsnNode varInsn
            && (opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD || opcode == Opcodes.LLOAD)) {
            int slot = varInsn.var;
            // numericLocals first: an in-method FSTORE / DSTORE / LSTORE captured a precise
            // value (overrides any param-table default for the same slot).
            Number local = state.mode == Mode.EVALUATING ? state.numericLocals.get(slot) : null;
            if (local != null) {
                state.numStack.push(local);
            } else if (state.mode == Mode.EVALUATING && slot >= 0 && slot < state.paramFloatValues.length) {
                state.numStack.push(state.paramFloatValues[slot]);
            } else {
                state.numStack.pushNonLiteral();
            }
        }

        // ISTORE / FSTORE / DSTORE / LSTORE: consume the value that the matching
        // LDC / arithmetic op pushed onto numStack so the JVM stack and our symbolic
        // stack stay in sync. Without this, a {@code ldc <value>; fstore_N} sequence
        // (e.g. {@code WitherBossModel.createBodyLayer}'s {@code RIBCAGE_X_ROT_OFFSET = 0.20420352f})
        // leaks the LDC value, which then sits at the bottom of every subsequent pop
        // and surfaces as a "leftover literal" warning at end-of-parse. Gated on
        // {@code EVALUATING} for byte-stability - legacy literal-stack
        // walkers use {@code ASTORE} (handled in the switch below) for their bone slot
        // tracking, never primitive STOREs in {@code createBodyLayer}-shaped code.
        // ASTORE is intentionally NOT included here; its bone-slot tracking remains in
        // the existing switch case below.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.ISTORE || opcode == Opcodes.FSTORE
                || opcode == Opcodes.DSTORE || opcode == Opcodes.LSTORE)
            && node instanceof VarInsnNode storeInsn) {
            // popNumber returns null when the stack is empty - guard so we still drop a
            // stale captured-local entry on the slot rather than holding onto a value the
            // bytecode just overwrote with an unknown computation.
            Number popped = state.numStack.isEmpty() ? null : state.numStack.popNumber();
            if (popped != null) {
                state.numericLocals.put(storeInsn.var, popped);
            } else {
                state.numericLocals.remove(storeInsn.var);
            }
        }

        // Explicit stack pops: {@code POP} discards 1 category-1 slot (int / float /
        // ref); {@code POP2} discards 1 category-2 slot (long / double) or 2 category-1
        // slots. Our numStack treats long / double as single Number entries, so POP2
        // of a wide value pops 1 entry. javac never emits POP2 for two narrow values
        // (it uses POP; POP), so the single-entry pop is correct in practice.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.POP || opcode == Opcodes.POP2)
            && !state.numStack.isEmpty()) {
            state.numStack.pop();
        }

        // Array load / store / metadata ops. The JVM stack effects of these aren't
        // visible to the literal walk so the index ints and any result ints leak as
        // leftovers - silverfish's {@code BODY_SIZES[i][j]} pattern produces 9
        // {@code AALOAD; ICONST j; IALOAD} chains, every one leaving the {@code i}
        // and the {@code IALOAD} result hanging.
        //
        // <ul>
        //   <li>AALOAD pops 1 ref + 1 int, pushes 1 ref. numStack effect: pop 1 int.</li>
        //   <li>IALOAD / BALOAD / SALOAD / CALOAD: pop 1 ref + 1 int, push 1 int.
        //       numStack effect: pop 1 int, push 1 NL int.</li>
        //   <li>FALOAD / DALOAD / LALOAD: same shape with float / double / long result -
        //       still represented as a single non-literal marker on numStack.</li>
        //   <li>ARRAYLENGTH: pop 1 ref, push 1 int. Push NL.</li>
        // </ul>
        // Gated on {@code EVALUATING} for byte-stability.
        //
        // <p>For {@code IALOAD} / {@code FALOAD} the parser also tries
        // {@link #tryFoldStaticArrayRead}, which walks back over the prior real
        // instructions to detect the canonical static-array index patterns
        // {@code GETSTATIC <[[I>; ILOAD; AALOAD; <int literal>; IALOAD},
        // {@code GETSTATIC <[I>; ILOAD; IALOAD}, and
        // {@code GETSTATIC <[F>; ILOAD; FALOAD}. On match, the resolved literal cell value
        // is pushed instead of the non-literal marker, so vanilla's silverfish / endermite
        // {@code BODY_SIZES[i][j]} / {@code BODY_TEXS[i][j]} reads and guardian's
        // {@code SPIKE_X[i]} / {@code SPIKE_Y[i]} / {@code SPIKE_Z[i]} +
        // {@code SPIKE_*_ROT[i]} reads fold to compile-time constants per unrolled
        // iteration.
        if (state.mode == Mode.EVALUATING) {
            if (opcode == Opcodes.AALOAD) {
                if (!state.numStack.isEmpty()) state.numStack.pop();
            } else if (opcode == Opcodes.IALOAD || opcode == Opcodes.FALOAD) {
                Number resolved = tryFoldStaticArrayRead(node, state, cache);
                if (!state.numStack.isEmpty()) state.numStack.pop();
                if (resolved != null) state.numStack.push(resolved);
                else state.numStack.pushNonLiteral();
            } else if (opcode == Opcodes.BALOAD || opcode == Opcodes.SALOAD
                    || opcode == Opcodes.CALOAD || opcode == Opcodes.DALOAD
                    || opcode == Opcodes.LALOAD) {
                if (!state.numStack.isEmpty()) state.numStack.pop();
                state.numStack.pushNonLiteral();
            } else if (opcode == Opcodes.ARRAYLENGTH) {
                state.numStack.pushNonLiteral();
            } else if (opcode == Opcodes.NEWARRAY) {
                // Pop 1 int (length); push ref (refs aren't tracked on numStack). Also
                // captures the length + element-type on {@link ParseState#pendingFreshArrayLength}
                // so the next {@code ASTORE} can bind the array to a slot and the parser
                // can subsequently fold {@code FASTORE} writes and {@code FALOAD} reads
                // against the tracked local array. Silverfish's {@code float[7]}
                // cumulative-pivot cache uses this; other vanilla model factories don't
                // currently allocate local primitive arrays.
                Number lengthN = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                if (lengthN != null && node instanceof IntInsnNode arrInsn) {
                    int length = lengthN.intValue();
                    if (length >= 0 && length < 1024) {
                        if (arrInsn.operand == Opcodes.T_FLOAT) {
                            state.pendingFreshArrayLength = length;
                            state.pendingFreshArrayType = 'F';
                        } else if (arrInsn.operand == Opcodes.T_INT) {
                            state.pendingFreshArrayLength = length;
                            state.pendingFreshArrayType = 'I';
                        }
                    }
                }
            } else if (opcode == Opcodes.ANEWARRAY) {
                // Pop 1 int (length); push ref (refs aren't tracked on numStack).
                if (!state.numStack.isEmpty()) state.numStack.pop();
            } else if (opcode == Opcodes.IASTORE || opcode == Opcodes.BASTORE
                    || opcode == Opcodes.SASTORE || opcode == Opcodes.CASTORE
                    || opcode == Opcodes.DASTORE || opcode == Opcodes.LASTORE) {
                // Array element store (non-float): JVM pops ref + int + value. numStack
                // effect: pop value (1 entry) + index (1 int).
                if (!state.numStack.isEmpty()) state.numStack.pop();
                if (!state.numStack.isEmpty()) state.numStack.pop();
            } else if (opcode == Opcodes.FASTORE) {
                // {@code FASTORE} writes into a {@code float[]}. Pops value + index +
                // array-ref. Walks back over the prior real instructions to find the
                // {@code ALOAD <slot>} that pushed the array ref; when the slot has a
                // tracked {@link ParseState#localFloatArrays} entry AND both popped
                // operands are real literals, writes {@code arr[idx] = value}. Otherwise
                // just pops to keep the JVM stack aligned. SilverfishModel's
                // {@code aload_2; iload i; fload f; fastore} cumulative-pivot pattern
                // matches this exactly.
                Number value = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                Number idx = state.numStack.isEmpty() ? null : state.numStack.popLiteralNumber();
                if (value != null && idx != null) {
                    // Walk back: prev1 = value insn, prev2 = idx insn, prev3 = ALOAD slot.
                    AbstractInsnNode v = AsmKit.previousReal(node);
                    AbstractInsnNode i = v != null ? AsmKit.previousReal(v) : null;
                    AbstractInsnNode a = i != null ? AsmKit.previousReal(i) : null;
                    if (a instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                        float[] arr = state.localFloatArrays.get(aload.var);
                        int idxInt = idx.intValue();
                        if (arr != null && idxInt >= 0 && idxInt < arr.length) {
                            arr[idxInt] = value.floatValue();
                        }
                    }
                }
            } else if (opcode == Opcodes.AASTORE) {
                // Array reference store: JVM pops ref + int + ref. numStack effect:
                // pop index only (the value ref isn't on numStack).
                if (!state.numStack.isEmpty()) state.numStack.pop();
            }
        }

        // Comparison ops that push an int result: {@code LCMP} (long / long),
        // {@code FCMPL} / {@code FCMPG} (float / float), {@code DCMPL} / {@code DCMPG}
        // (double / double). Each pops two operands and pushes -1 / 0 / 1 onto the JVM
        // stack. Our walker can't statically know the result so push a non-literal
        // marker - the next IFEQ / IFNE / IF_ICMP* handler above pops it and falls
        // through linearly without taking the branch.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.LCMP || opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG
                || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG)) {
            if (!state.numStack.isEmpty()) state.numStack.pop();
            if (!state.numStack.isEmpty()) state.numStack.pop();
            state.numStack.pushNonLiteral();
        }

        // Binary integer arithmetic: pops two ints, pushes the result. Same
        // {@code EVALUATING} gate as the float / double block below so
        // legacy literal-stack walkers keep the legacy literal-stack-only walk. Vanilla
        // shares parameterised quadruped construction in {@code QuadrupedModel
        // .createBodyMesh(int legSize, ...)} which computes head/body Y as
        // {@code bipush 18; iload_0; isub; i2f}; without this block the {@code isub}
        // is a no-op and pig head ends up at world Y=18 instead of 12.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.IADD || opcode == Opcodes.ISUB
                || opcode == Opcodes.IMUL || opcode == Opcodes.IDIV
                || opcode == Opcodes.IREM)
            && state.numStack.size() >= 2) {
            int b = state.numStack.popNumber().intValue();
            int a = state.numStack.popNumber().intValue();
            int r = switch (opcode) {
                case Opcodes.IADD -> a + b;
                case Opcodes.ISUB -> a - b;
                case Opcodes.IMUL -> a * b;
                case Opcodes.IDIV -> b == 0 ? 0 : a / b;
                case Opcodes.IREM -> b == 0 ? 0 : a % b;
                default -> 0;
            };
            state.numStack.push(r);
        }

        // Unary numeric negation: pops 1, pushes 1. INEG = -i, FNEG = -f, etc.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.INEG || opcode == Opcodes.FNEG
                || opcode == Opcodes.DNEG || opcode == Opcodes.LNEG)
            && !state.numStack.isEmpty()) {
            Number top = state.numStack.popNumber();
            Number negated = switch (opcode) {
                case Opcodes.INEG -> -top.intValue();
                case Opcodes.FNEG -> -top.floatValue();
                case Opcodes.DNEG -> -top.doubleValue();
                case Opcodes.LNEG -> -top.longValue();
                default -> top;
            };
            state.numStack.push(negated);
        }

        // Binary float / double arithmetic: only fires when the source opted into
        // arithmetic evaluation via {@code EVALUATING}. Legacy
        // sources never set this so the legacy linear walk is preserved unchanged.
        // For Java-side sources, this fixes patterns like
        // {@code HumanoidModel.createMesh}'s arm pivot {@code 2 + yOffset} where
        // yOffset is a parameter and the {@code FADD} would otherwise leave the stack
        // mis-aligned. Non-literal markers are treated as zero during the operation.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.FADD || opcode == Opcodes.FSUB || opcode == Opcodes.FMUL || opcode == Opcodes.FDIV || opcode == Opcodes.FREM
                || opcode == Opcodes.DADD || opcode == Opcodes.DSUB || opcode == Opcodes.DMUL || opcode == Opcodes.DDIV || opcode == Opcodes.DREM)) {
            if (state.numStack.size() >= 2) {
                Number bN = state.numStack.popNumber();
                Number aN = state.numStack.popNumber();
                // JVM float / double arithmetic opcodes interleave (FADD=98, DADD=99,
                // FSUB=102, DSUB=103, FMUL=106, DMUL=107, FDIV=110, DDIV=111, FREM=114,
                // DREM=115) - a {@code >= DADD && <= DDIV} range check would misclassify
                // FSUB / FMUL / FDIV / FREM as double, causing the switch below to fall
                // to its zero-return default. Use explicit equality.
                boolean isDouble = opcode == Opcodes.DADD || opcode == Opcodes.DSUB
                    || opcode == Opcodes.DMUL || opcode == Opcodes.DDIV
                    || opcode == Opcodes.DREM;
                if (isDouble) {
                    double a = aN.doubleValue();
                    double b = bN.doubleValue();
                    double r = switch (opcode) {
                        case Opcodes.DADD -> a + b;
                        case Opcodes.DSUB -> a - b;
                        case Opcodes.DMUL -> a * b;
                        case Opcodes.DDIV -> b == 0.0 ? 0.0 : a / b;
                        case Opcodes.DREM -> b == 0.0 ? 0.0 : a % b;
                        default -> 0.0;
                    };
                    state.numStack.push(r);
                } else {
                    float a = aN.floatValue();
                    float b = bN.floatValue();
                    float r = switch (opcode) {
                        case Opcodes.FADD -> a + b;
                        case Opcodes.FSUB -> a - b;
                        case Opcodes.FMUL -> a * b;
                        case Opcodes.FDIV -> b == 0f ? 0f : a / b;
                        case Opcodes.FREM -> b == 0f ? 0f : a % b;
                        default -> 0f;
                    };
                    state.numStack.push(r);
                }
            }
        }

        // Type-conversion ops between numeric stack slots. Mirrored on the literal
        // stack so subsequent arithmetic / argument-pop sees the correct precision.
        // Gated on {@code EVALUATING} for the same byte-stability reason as the
        // arithmetic block above.
        if (state.mode == Mode.EVALUATING
            && (opcode == Opcodes.I2F || opcode == Opcodes.I2D || opcode == Opcodes.F2D
                || opcode == Opcodes.D2F || opcode == Opcodes.F2I || opcode == Opcodes.D2I)
            && !state.numStack.isEmpty()) {
            Number top = state.numStack.popNumber();
            Number converted = switch (opcode) {
                case Opcodes.I2F -> (float) top.intValue();
                case Opcodes.I2D -> (double) top.intValue();
                case Opcodes.F2D -> (double) top.floatValue();
                case Opcodes.D2F -> (float) top.doubleValue();
                case Opcodes.F2I -> (int) top.floatValue();
                case Opcodes.D2I -> (int) top.doubleValue();
                default -> top;
            };
            state.numStack.push(converted);
        }

        switch (node) {
            case FieldInsnNode fieldInsn when opcode == Opcodes.GETSTATIC -> {
                // Enum-reference attachment split: a constant of the bound enum class pushes its
                // field name so the upcoming {@code if_acmp*} can compare against the bound value.
                if (fieldInsn.owner.equals(state.refParamOwner))
                    state.refStack.add(fieldInsn.name);
                if (fieldInsn.owner.equals(VanillaSourceClasses.Types.PART_POSE) && fieldInsn.name.equals("ZERO")) {
                    state.pendingPivot = new float[]{ 0, 0, 0 };
                    state.pendingRotation = new float[]{ 0, 0, 0 };
                    state.pendingScale = 1f;
                }
                // {@code GETSTATIC <field>: MeshTransformer} - vanilla static-field pattern
                // for layer-level scale wraps that don't appear inline in the factory body.
                // {@code GuardianModel.createElderGuardianLayer} reads
                // {@code ELDER_GUARDIAN_SCALE} (a private static final MeshTransformer
                // initialised in {@code <clinit>} as
                // {@code MeshTransformer.scaling(2.35f)}) via {@code getstatic} then
                // {@code LayerDefinition.apply(MeshTransformer)} - the parser sees the
                // getstatic but not the scaling literal. Lazily walk the field owner's
                // {@code <clinit>} for the matching {@code putstatic} and fold the captured
                // F into {@link ParseState#meshTransformerScale}; the subsequent
                // {@code apply()} call is then a no-op for our tracking (we don't track
                // LayerDefinition refs anyway). Cached per-class so repeated parses of the
                // same source don't reload. Gated on {@code EVALUATING} so
                // legacy literal-stack walkers keep their byte-stable behaviour.
                //
                // <p>Fallback for non-scaling transformers: when the field is initialised
                // via {@code invokedynamic apply -> lambda$static$N} that calls
                // {@code <Owner>.modifyMesh(MeshDefinition.getRoot())} (the
                // {@code DonkeyModel.DONKEY_TRANSFORMER} pattern), inline that
                // {@code modifyMesh} method into the current parse so the
                // {@code addOrReplaceChild} calls inside it land in our bone tree before
                // the subsequent {@code .apply(MeshTransformer.scaling(F))} bakes the
                // per-renderer scale across every bone. modifyMesh's
                // {@code body.addOrReplaceChild("left_chest", ...)} relies on the
                // {@link ParseState#boneMeta} entries already populated by
                // {@code AbstractEquineModel.createBodyMesh}, which flow through
                // {@link #inlineStaticMethodBody}'s save/restore set untouched.
                else if (state.mode == Mode.EVALUATING
                    && MESH_TRANSFORMER_DESC.equals(fieldInsn.desc)) {
                    Float f = resolveStaticMeshTransformer(fieldInsn.owner, fieldInsn.name, cache);
                    if (f != null) {
                        state.meshTransformerScale *= f;
                    } else {
                        MethodNode modifyMesh = findStaticModifyMeshTarget(fieldInsn.owner, fieldInsn.name, cache);
                        if (modifyMesh != null) inlineStaticMethodBody(modifyMesh, null, state, cache);
                    }
                }
            }
            case MethodInsnNode methodInsn -> handleMethodInsn(methodInsn, opcode, state, cache);
            // {@code invokedynamic makeConcatWithConstants:(I)Ljava/lang/String;} - javac
            // emits this for inline {@code "name" + i} expressions. Pop the int from
            // numStack, apply the bootstrap recipe (with {@code \u0001} as the dynamic
            // placeholder), and stash the result in {@code pendingPartName} so the
            // surrounding {@code addOrReplaceChild} flush picks it up as the bone name.
            // Vanilla procedural-loop factories (ghast tentacle scaling, etc.) emit the
            // indy directly; helper-wrapped variants (squid's createTentacleName, blaze's
            // getPartName) are resolved in {@link #handleMethodInsn}'s invokestatic-follow.
            case InvokeDynamicInsnNode indy when state.mode == Mode.EVALUATING
                && indy.desc.equals("(I)Ljava/lang/String;")
                && !state.numStack.isEmpty() -> {
                String recipe = AsmKit.resolveStringConcatRecipe(indy);
                if (recipe != null) {
                    int i = state.numStack.popNumber().intValue();
                    state.pendingPartName = AsmKit.applyStringConcatRecipeWithInt(recipe, i);
                }
            }
            // Track local-variable slot -> bone mapping so child bones inherit their
            // parent's pivot + scale. Vanilla models use
            // {@code head = root.addOrReplaceChild("head", ...); head.addOrReplaceChild("jaw", ...);}
            // which compiles to {@code invokevirtual; astore_N; aload_N;} around the child's
            // builder chain - so astore-after-flush and aload-before-chain are our hooks.
            // Additionally, slots may hold a pre-built CubeListBuilder that multiple
            // addOrReplaceChild calls share (DecoratedPotRenderer stores one builder and
            // reuses it for both {@code top} and {@code bottom} bones). Snapshot pending
            // cubes into {@link ParseState#slotToCubes} so a later aload_N can re-hydrate
            // them for the next bone without re-reading the same addBox literals.
            case VarInsnNode varInsn when opcode == Opcodes.ASTORE -> {
                if (state.pendingFreshArrayLength != null && state.pendingFreshArrayType != '\0') {
                    // The previous {@code NEWARRAY <type>} captured a length + element-type
                    // on the pending fields; bind a tracked array to this slot now so
                    // subsequent {@code FASTORE} writes and {@code FALOAD} reads can fold
                    // against it. SilverfishModel's {@code bipush 7; newarray float;
                    // astore_2} cumulative-pivot cache hits this exactly.
                    int len = state.pendingFreshArrayLength;
                    if (state.pendingFreshArrayType == 'F') {
                        state.localFloatArrays.put(varInsn.var, new float[len]);
                    }
                    state.pendingFreshArrayLength = null;
                    state.pendingFreshArrayType = '\0';
                } else if (state.pendingRandomSource != null) {
                    // The previous {@code RandomSource.createThreadLocalInstance(J)}
                    // captured a seeded {@link java.util.Random}; bind it to this slot so
                    // subsequent {@code aload <slot>; <bound>; invokeinterface nextInt}
                    // calls can step it. GhastModel's {@code ldc2_w 1660L;
                    // invokestatic createThreadLocalInstance; astore_2} hits this exactly.
                    state.localRandomSources.put(varInsn.var, state.pendingRandomSource);
                    state.pendingRandomSource = null;
                } else if (state.pendingFreshDeformationInflate != null) {
                    state.cubeDeformationSlots.put(varInsn.var, state.pendingFreshDeformationInflate);
                    state.pendingFreshDeformationInflate = null;
                    // The fresh deformation just got stashed into a slot for later
                    // reuse; it's no longer the "active" inflate. Reset to the
                    // factory default so the next addBox(...,CubeDeformation) picks up
                    // its own arg (via ALOAD slot lookup) or the call-site default,
                    // not the leftover constructor value. AdultFelineModel triggers
                    // this: {@code CubeDeformation tail_g = new CubeDeformation(-0.02F)}
                    // followed immediately by {@code addBox("main", ..., g)} where
                    // {@code g} is the parameter (call-site default), would otherwise
                    // emit the head main cube with the stale -0.02 instead of 0.
                    state.pendingInflate = state.defaultInflate.clone();
                } else if (state.lastFlushedBone != null) {
                    state.localSlotBone.put(varInsn.var, state.lastFlushedBone);
                    state.lastFlushedBone = null;
                    // Storing a bone reference defers its use to a later ALOAD; drop any parent a
                    // preceding getChild propagated so it doesn't leak onto the next inline bone
                    // (the ALOAD re-derives nextParent from localSlotBone). No-op outside the
                    // getChild-then-store shape, where nextParent is already null at this point.
                    state.nextParent = null;
                } else if (!state.pendingCubes.isEmpty()) {
                    ConcurrentList<float[]> snapshot = Concurrent.newList();
                    for (float[] c : state.pendingCubes) snapshot.add(c.clone());
                    state.slotToCubes.put(varInsn.var, snapshot);
                    state.pendingCubes = Concurrent.newList();
                    state.pendingUv = new int[]{ 0, 0 };
                }
            }
            case VarInsnNode varInsn when opcode == Opcodes.ALOAD -> {
                // Enum-reference attachment split: loading the bound parameter slot pushes the
                // sentinel so the upcoming {@code getstatic <Enum>.<C>; if_acmp*} resolves.
                if (state.refParamOwner != null && varInsn.var == state.refParamSlot)
                    state.refStack.add(ParseState.REF_PARAM_SENTINEL);
                float[] deformationInflate = state.cubeDeformationSlots.get(varInsn.var);
                if (deformationInflate != null)
                    state.pendingInflate = deformationInflate.clone();
                String parent = state.localSlotBone.get(varInsn.var);
                if (parent != null)
                    state.nextParent = parent;
                ConcurrentList<float[]> savedCubes = state.slotToCubes.get(varInsn.var);
                if (savedCubes != null) {
                    for (float[] c : savedCubes) state.pendingCubes.add(c.clone());
                }
            }
            case LdcInsnNode ldc when ldc.cst instanceof String s ->
                state.pendingPartName = s;
            default -> { }
        }
        return node;
    }

    /**
     * Dispatches a {@code MethodInsnNode} by owner: builder chains (CubeListBuilder,
     * PartPose), bone-finalising ({@code PartDefinition.addOrReplaceChild}), texture dim
     * extraction ({@code LayerDefinition.create}), bone-pruning ({@code getChild} /
     * {@code retainPartsAndChildren} / {@code clearChild} / {@code Set.of}), the
     * {@code CubeDeformation}, {@code MeshTransformer.scaling}, {@code Mth.cos/sin},
     * {@code Math.cos/sin}, and {@code RandomSource} handlers, indexed-part-name helpers, and
     * model-package invokestatic-follow for cross-class delegation patterns like
     * {@code PiglinHeadModel.createHeadModel -> PiglinModel.addHead}.
     *
     * @param methodInsn the method call instruction being dispatched
     * @param opcode the call opcode ({@code INVOKESTATIC} / {@code INVOKEVIRTUAL} /
     *     {@code INVOKEINTERFACE} / {@code INVOKESPECIAL})
     * @param state the parse state to mutate
     * @param cache the session's jar cache
     */
    private static void handleMethodInsn(@NotNull MethodInsnNode methodInsn, int opcode, @NotNull ParseState state, @NotNull ClassNodeCache cache) {
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.CUBE_LIST_BUILDER)) {
            handleCubeListBuilder(methodInsn, state);
            return;
        }
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_POSE)) {
            handlePartPose(methodInsn, state);
            return;
        }
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_DEFINITION) && methodInsn.name.equals("addOrReplaceChild")) {
            flushPendingBone(state);
            return;
        }
        // PartDefinition.getChild(String name) returns the named child PartDefinition.
        // The preceding LDC pushed the child name into {@link ParseState#pendingPartName};
        // re-aim {@link ParseState#lastFlushedBone} at it so the following ASTORE associates
        // the local slot with the correct bone (the named child), not the most-recently-
        // created bone. Without this, patterns like
        // {@code PartDefinition nose = head.getChild("nose"); nose.addOrReplaceChild("mole", ...);}
        // (WitchModel) would attribute "mole"'s parent to whatever bone happened to be
        // flushed last - in witch's case "hat4", landing mole's pivot accumulated through
        // the wrong rotation chain.
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_DEFINITION) && methodInsn.name.equals("getChild")) {
            if (state.pendingPartName != null) {
                state.lastFlushedBone = state.pendingPartName;
                // A getChild result is the parent for whatever addOrReplaceChild is invoked on
                // it. Propagate it to nextParent so a directly method-chained
                // {@code getRoot().getChild("root").getChild("shell").addOrReplaceChild("corals", ...)}
                // ({@code ZombieNautilusCoralModel.createBodyLayer}, adding corals onto the base
                // mesh's shell) parents "corals" under "shell" - without it the flush resolves no
                // parent and drops shell's pivot, floating the corals above the entity. The stored
                // form ({@code nose = head.getChild("nose"); nose.addOrReplaceChild("mole", ...)},
                // WitchModel) clears this at the following ASTORE and re-derives it at the ALOAD, so
                // both forms resolve the same parent.
                state.nextParent = state.pendingPartName;
                state.pendingPartName = null;
            }
            return;
        }
        // Java pipeline filters: {@code retainPartsAndChildren(Set)} on a PartDefinition strips
        // cubes from any bone whose ancestor chain doesn't contain a name in the set (vanilla
        // recurses through children, replacing cubes with empty along the way; subtrees rooted
        // at a retained name are left untouched). Captured here on the trailing
        // {@code retainPartsAndChildren} dispatch using {@link ParseState#pendingRetainSet}
        // populated by the preceding {@code Set.of} branch below; consumed post-walk in
        // {@link #parseLayerMethod} once the full bone tree has flushed. Gated on
        // {@code EVALUATING} so legacy literal-stack walkers (which never call
        // retainPartsAndChildren) keep their byte-stable output.
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_DEFINITION)
            && methodInsn.name.equals("retainPartsAndChildren")
            && state.mode == Mode.EVALUATING) {
            if (state.pendingRetainSet != null) {
                state.retainedNames = state.pendingRetainSet;
                state.pendingRetainSet = null;
            }
            return;
        }
        // Post-build pruning: {@code <parent>.clearChild("<name>")} drops the named child
        // (and its sub-tree) from the parent's PartDefinition. The previous {@code ALOAD}
        // identified the parent slot and the previous {@code LDC} pushed the child name into
        // {@link ParseState#pendingPartName}; record the name and let
        // {@link #applyClearedBonesFilter} drop it (and any descendants) from {@link #bones}
        // after the walk completes. The parent identification is informational only - bone
        // names are globally unique within a model, so name-keyed removal is sufficient.
        // Canonical case: {@code AdultPiglinModel.createBodyLayer} inherits "hat" from
        // {@code PlayerModel.createMesh}, then prunes it via {@code head.clearChild("hat")}.
        // Gated on {@code EVALUATING} so legacy block-entity walkers (which
        // never see clearChild) keep their byte-stable output.
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_DEFINITION)
            && methodInsn.name.equals("clearChild")
            && state.mode == Mode.EVALUATING) {
            if (state.pendingPartName != null) {
                state.clearedBones.add(state.pendingPartName);
                state.pendingPartName = null;
            }
            return;
        }
        // Set.of(name, ...) immediately precedes retainPartsAndChildren in vanilla model
        // factories ({@code BreezeModel.createBodyLayer} -> {@code Set.of("head", "rods")},
        // {@code BreezeModel.createWindLayer} -> {@code Set.of("wind_body")}, etc). Walks
        // back from the methodInsn collecting the N preceding LDC strings (where N is the
        // descriptor's ref-arg count) and stashes them on {@link ParseState#pendingRetainSet}
        // for the next {@code retainPartsAndChildren} dispatch. Skips line / frame / label
        // pseudo-nodes during the walkback. The varargs {@code Set.of([Ljava/lang/Object;)}
        // overload would need an anewarray walker; not used by any vanilla entity factory
        // observed so far so the implementation is deferred. Gated on
        // {@code EVALUATING}.
        if (methodInsn.owner.equals("java/util/Set")
            && methodInsn.name.equals("of")
            && opcode == Opcodes.INVOKESTATIC
            && state.mode == Mode.EVALUATING) {
            state.pendingRetainSet = collectSetOfStringArgs(methodInsn);
            return;
        }
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.LAYER_DEFINITION) && methodInsn.name.equals("create")) {
            requireStack(state, 2, "LayerDefinition.create(mesh,II)");
            state.texHeight = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texHeight");
            state.texWidth = popIntWithDiagnostics(state, "LayerDefinition.create(mesh,II) texWidth");
            return;
        }
        // {@code invokestatic <Owner>.<helper>(I)Ljava/lang/String;} where the helper body
        // is a thin wrapper around an inline {@code "prefix" + i} concat (e.g.
        // {@code SquidModel.createTentacleName} - {@code "tentacle" + i};
        // {@code BlazeModel.getPartName} - {@code "part" + i}). Walks the helper's
        // instructions for the inner {@code makeConcatWithConstants} invokedynamic via
        // {@link AsmKit#findStringConcatRecipeIn}, pops the int from numStack, applies the
        // recipe's dynamic-placeholder substitution and stashes the result in
        // {@code pendingPartName}. The general helper-walk approach subsumes the
        // PartNames-specific hack below (the helper name was the literal recipe prefix)
        // while also working for SquidModel / BlazeModel-style helpers where the helper
        // name ({@code createTentacleName} / {@code getPartName}) differs from the recipe
        // prefix ({@code tentacle} / {@code part}).
        //
        // <p>Falls through to the PartNames-name-equals-prefix legacy path below when the
        // helper has no {@code makeConcatWithConstants} indy (e.g. {@code PartNames}'s
        // static methods just return a constant String field via {@code areturn}).
        if (opcode == Opcodes.INVOKESTATIC
            && methodInsn.desc.equals("(I)Ljava/lang/String;")
            && state.mode == Mode.EVALUATING
            && !state.numStack.isEmpty()) {
            MethodNode helper = AsmKit.findMethodInHierarchy(cache, methodInsn.owner, methodInsn.name, methodInsn.desc);
            if (helper != null) {
                String recipe = AsmKit.findStringConcatRecipeIn(helper);
                if (recipe != null) {
                    int i = state.numStack.popNumber().intValue();
                    state.pendingPartName = AsmKit.applyStringConcatRecipeWithInt(recipe, i);
                    return;
                }
            }
        }
        // PartNames is a vanilla utility class with String constants and indexed name
        // generators ({@code tentacle(int)}, etc.). The indexed methods compile to
        // {@code makeConcatWithConstants} which the parser can't follow; intercept the
        // call and synthesise the {@code "name" + i} the JVM produces, so subsequent
        // {@code addOrReplaceChild} flushes pick up a name. The HappyGhastModel uses
        // {@code PartNames.tentacle(0)}..{@code (8)} for its 9 explicit tentacle bones.
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.PART_NAMES)
            && opcode == Opcodes.INVOKESTATIC
            && methodInsn.desc.startsWith("(I)") && methodInsn.desc.endsWith("Ljava/lang/String;")
            && !state.numStack.isEmpty()) {
            int i = state.numStack.popNumber().intValue();
            state.pendingPartName = methodInsn.name + i;
            return;
        }
        if (methodInsn.owner.equals(VanillaSourceClasses.Types.CUBE_DEFORMATION)) {
            handleCubeDeformation(methodInsn, state);
            return;
        }
        // MeshTransformer.scaling(F): some entity models append a uniform scale to the final
        // {@code LayerDefinition.create(...).apply(MeshTransformer.scaling(N))} chain
        // (PolarBearModel = 1.2, GhastModel = 4.5, HappyGhastModel = 4.0, etc). Vanilla
        // expands this per {@code PartPose} as {@code pose.scaled(F).translated(0,
        // 24.016*(1-F), 0)} - scales pivots around the entity's feet anchor (y=24.016) AND
        // multiplies the bone's {@code PartPose.scale} field by F, which the kit consumes
        // via {@link Bone#getScale()}
        // when emitting the bone's cubes. We capture F here, then re-walk the emitted bone
        // tree in {@link #applyMeshTransformerScaling} post-walk so the math agrees with
        // vanilla's apply-after-build semantics. Multiplies into any existing capture so
        // sequential {@code .apply(scaling(a)).apply(scaling(b))} chains compose (none
        // observed in vanilla 26.1, but cheap to support). Gated on
        // {@code EVALUATING} so legacy literal-stack walkers, which never call
        // MeshTransformer, are unaffected.
        if (state.mode == Mode.EVALUATING
            && opcode == Opcodes.INVOKESTATIC
            && methodInsn.owner.equals(VanillaSourceClasses.Types.MESH_TRANSFORMER)
            && methodInsn.name.equals(VanillaSourceClasses.Methods.SCALING)
            && methodInsn.desc.equals("(F)" + MESH_TRANSFORMER_DESC)
            && !state.numStack.isEmpty()) {
            float f = state.numStack.popNumber().floatValue();
            // Vanilla never calls {@code scaling(0)}; a captured 0 means the synthetic
            // {@link Source}'s {@code paramFloatValues} didn't supply the {@code createBodyLayer}
            // float parameter that this site references via {@code fload_0}. Donkey / mule hit
            // this: their {@code createBodyLayer(float)} reads the renderer's per-variant scale,
            // which our tooling-side source builder doesn't currently populate. Skip the capture
            // so the bone tree stays unscaled rather than collapsing to a flat plane; the static-
            // field {@code DONKEY_TRANSFORMER} side (also unhandled) remains an open A5 gap.
            if (f == 0f) {
                if (state.diagnostics != null && state.currentSource != null)
                    state.diagnostics.info("%s: MeshTransformer.scaling(0) skipped - synthetic source missing paramFloatValues", state.currentSource.subjectId());
                return;
            }
            state.meshTransformerScale *= f;
            return;
        }
        // Mth.cos(D)F / Mth.sin(D)F: vanilla model factories occasionally precompute bind-pose
        // offsets via inline trig - e.g. WitherBossModel.createBodyLayer's tail pivot
        // (6.9 + Mth.cos(0.20420352F) * 10 for Y, -0.5 + Mth.sin(0.20420352F) * 10 for Z).
        // Pop the top double from numStack, compute the result via the FastTrig table lookup,
        // push the float so the surrounding FMUL / FADD chain folds correctly. Gated on
        // {@code EVALUATING} so legacy literal-stack walkers keep their byte-stable
        // parse - none observed call Mth.cos / sin during their layer build.
        //
        // Vanilla's Mth.cos(double) / Mth.sin(double) are 65536-entry sin-table lookups,
        // NOT Math.cos / Math.sin. The table values differ from libm by up to 1.8e-5 (table
        // granularity 2*PI/65536). Substituting Math.cos here would compute the right
        // rotation but a slightly different float result, enough to flip the wither tail
        // pivot Y across a canvas-pixel rounding boundary (Math: 16.6922283, Mth: 16.6924076).
        // FastTrig.cos / sin reproduce vanilla's bytecode bit-for-bit.
        if (state.mode == Mode.EVALUATING
            && opcode == Opcodes.INVOKESTATIC
            && methodInsn.owner.equals(VanillaSourceClasses.Types.MTH)
            && (methodInsn.name.equals("cos") || methodInsn.name.equals("sin"))
            && methodInsn.desc.equals("(D)F")
            && !state.numStack.isEmpty()) {
            double arg = state.numStack.popNumber().doubleValue();
            float result = methodInsn.name.equals("cos")
                ? FastTrig.cos(arg)
                : FastTrig.sin(arg);
            state.numStack.push(result);
            return;
        }
        // Math.cos(D)D / Math.sin(D)D: libm-precision sibling of the Mth handler above.
        // Vanilla {@code SquidModel.createBodyLayer} uses {@code Math.cos / Math.sin} on the
        // doubles {@code i * 2*PI / 8} for tentacle pivots (NOT {@code Mth.cos / sin} which
        // returns float via a 65536-entry table lookup). The values differ by up to 1.8e-5
        // - enough to flip a pivot across a canvas-pixel rounding boundary - so this handler
        // returns {@code Math.cos / sin} double directly. The subsequent
        // {@code D2F / DMUL / DADD / DSUB} arithmetic in the body folds the double back to
        // the target precision. Gated on {@code EVALUATING} so legacy
        // literal-stack walkers, which never see Math.cos / sin in their createBodyLayer
        // bodies, keep their byte-stable output.
        if (state.mode == Mode.EVALUATING
            && opcode == Opcodes.INVOKESTATIC
            && methodInsn.owner.equals("java/lang/Math")
            && (methodInsn.name.equals("cos") || methodInsn.name.equals("sin"))
            && methodInsn.desc.equals("(D)D")
            && !state.numStack.isEmpty()) {
            double arg = state.numStack.popNumber().doubleValue();
            double result = methodInsn.name.equals("cos")
                ? Math.cos(arg)
                : Math.sin(arg);
            state.numStack.push(result);
            return;
        }
        // {@code RandomSource.createThreadLocalInstance(J)} - seeded factory. Mojang's
        // {@code SingleThreadedRandomSource} ctor calls {@code setSeed} with the same LCG
        // as {@link java.util.Random#setSeed} (multiplier {@code 25214903917L}, increment
        // {@code 11L}, modulus mask {@code (1L << 48) - 1}). The subsequent
        // {@code BitRandomSource#nextInt(int)} default method is also identical to
        // {@code java.util.Random#nextInt(int)} - same power-of-2 fast path, same
        // rejection-sampling loop for non-power-of-2 bounds. So substituting
        // {@code new java.util.Random(seed)} produces bit-identical results. Pops the
        // long seed from numStack, stashes a fresh Random on
        // {@link ParseState#pendingRandomSource}; the next {@code ASTORE} binds it to a
        // local slot.
        //
        // <p>GhastModel.createBodyLayer uses seed {@code 1660L} to deterministically
        // produce 9 tentacle heights via repeated {@code nextInt(7) + 8} calls.
        if (state.mode == Mode.EVALUATING
            && opcode == Opcodes.INVOKESTATIC
            && methodInsn.owner.equals(VanillaSourceClasses.Types.RANDOM_SOURCE)
            && methodInsn.name.equals("createThreadLocalInstance")
            && methodInsn.desc.equals(VanillaSourceClasses.Descs.of(
                VanillaSourceClasses.Descs.ref(VanillaSourceClasses.Types.RANDOM_SOURCE), "J"))) {
            // The long seed isn't tracked on numStack (the parser's literal walk handles
            // int / float / double only). Walk back to the preceding {@code LDC2_W} or
            // {@code LCONST_0} / {@code LCONST_1} directly via {@link AsmKit#readLongLiteral}.
            AbstractInsnNode seedNode = AsmKit.previousReal(methodInsn);
            Long seed = seedNode != null ? AsmKit.readLongLiteral(seedNode) : null;
            if (seed != null) {
                state.pendingRandomSource = new java.util.Random(seed);
            }
            return;
        }
        // {@code RandomSource.nextInt(I)I} via invokeinterface. Walks back over preceding
        // real instructions to find the {@code ALOAD <slot>} that pushed the random
        // reference; when the slot has a tracked {@link java.util.Random} AND the bound
        // operand is a real literal, steps the random and pushes the literal int result.
        // Otherwise pops the bound and pushes a non-literal marker so the JVM stack stays
        // aligned.
        if (state.mode == Mode.EVALUATING
            && opcode == Opcodes.INVOKEINTERFACE
            && methodInsn.owner.equals(VanillaSourceClasses.Types.RANDOM_SOURCE)
            && methodInsn.name.equals("nextInt")
            && methodInsn.desc.equals("(I)I")
            && !state.numStack.isEmpty()) {
            Number bound = state.numStack.popLiteralNumber();
            if (bound != null) {
                AbstractInsnNode boundNode = AsmKit.previousReal(methodInsn);
                AbstractInsnNode aloadNode = boundNode != null ? AsmKit.previousReal(boundNode) : null;
                if (aloadNode instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                    java.util.Random rng = state.localRandomSources.get(aload.var);
                    if (rng != null && bound.intValue() > 0) {
                        state.numStack.push(rng.nextInt(bound.intValue()));
                        return;
                    }
                }
            }
            state.numStack.pushNonLiteral();
            return;
        }
        // Invokestatic-follow: recurse into model-building statics outside the builder/geom
        // package (e.g. PiglinHeadModel.createHeadModel -> PiglinModel.addHead). The JVM
        // resolves invokestatic through the superclass chain, so {@link AsmKit#findMethodInHierarchy}
        // walks {@code superName} until the method is found.
        if (opcode == Opcodes.INVOKESTATIC
            && methodInsn.owner.startsWith(GeometryParsePolicies.INVOKESTATIC_FOLLOW_PACKAGE_GATE.value())
            && !methodInsn.owner.startsWith(VanillaSourceClasses.Types.CLIENT_MODEL_GEOM_ROOT)) {
            MethodNode inlined = AsmKit.findMethodInHierarchy(cache, methodInsn.owner, methodInsn.name, methodInsn.desc);
            if (inlined != null) inlineStaticMethodBody(inlined, methodInsn.desc, state, cache);
        }
    }

    /**
     * Walks {@code inlined}'s instructions on {@code state}, saving and restoring every
     * call-frame-local field (slot maps, pending pose, pending mirror, paramIntValues /
     * paramFloatValues) around the call. Output containers ({@link ParseState#bones},
     * {@link ParseState#boneMeta}, {@link ParseState#boneParents}) flow through unchanged
     * so bones flushed inside the callee land in the caller's geometry.
     *
     * <p>JVM local-variable slots are method-scoped: {@code astore_2} inside the callee
     * writes to a slot independent of the caller's slot 2, so the caller's {@code aload_2}
     * after the helper returns must not pick up the callee's bone bindings. Without this
     * reset, the callee's last flushed bone (e.g. {@code left_front_leg}) leaks into the
     * caller's slot map and the caller's next {@code addOrReplaceChild} mis-parents to a
     * leg instead of the mesh root. Builder-level {@link ParseState#pendingMirror} is
     * scoped to the current CubeListBuilder chain - without saving it across the recurse,
     * a callee that internally toggles mirror (HumanoidModel.createMesh's left-arm /
     * left-leg .mirror() calls) leaks the flag into the caller's continuation.
     *
     * <p>When {@code callDesc} is non-null and {@link ParseState#paramFloatValues} is
     * non-null, the call-site's numeric literals are captured into the callee's parameter
     * slots via {@link #captureInlineParams}. Vanilla shares quadruped construction in
     * {@code QuadrupedModel.createBodyMesh(int legSize, ...)} - the call site pushes
     * literal ints (pig's {@code bipush 6, iconst_1, iconst_0}); without this the inlined
     * method's {@code iload_0} resolves to a non-literal marker and downstream
     * {@code 18 - legSize} arithmetic produces 18 instead of 12. Pass {@code null} for
     * synthetic call sites (e.g. lambda-mediated MeshTransformer invokes) that have no
     * on-stack numeric args.
     *
     * @param inlined the callee method whose body is walked in the current parse
     * @param callDesc the call-site descriptor for parameter capture, or {@code null} to skip
     * @param state the parse state to mutate (output containers flow through; locals restored)
     * @param cache the session's jar cache
     */
    private static void inlineStaticMethodBody(
        @NotNull MethodNode inlined,
        @Nullable String callDesc,
        @NotNull ParseState state,
        @NotNull ClassNodeCache cache
    ) {
        int[] previousInts = state.paramIntValues;
        float[] previousFloats = state.paramFloatValues;
        if (callDesc != null && state.mode == Mode.EVALUATING) {
            InlineParams params = captureInlineParams(callDesc, state, inlined.maxLocals);
            state.paramIntValues = params.ints;
            state.paramFloatValues = params.floats;
        }
        ConcurrentMap<Integer, String> savedLocalSlotBone = state.localSlotBone;
        ConcurrentMap<Integer, ConcurrentList<float[]>> savedSlotToCubes = state.slotToCubes;
        // JVM scoping: callee's ISTORE / FSTORE / DSTORE writes its own slot N, not the
        // caller's slot N. Swap a fresh numericLocals map for the callee and restore the
        // caller's on exit so a static helper's locals don't leak into the surrounding
        // factory's slot table.
        ConcurrentMap<Integer, Number> savedNumericLocals = state.numericLocals;
        // Same scoping applies to local primitive-array slots - the callee can't
        // see / write the caller's tracked float[] / int[] arrays.
        ConcurrentMap<Integer, float[]> savedLocalFloatArrays = state.localFloatArrays;
        Integer savedPendingFreshArrayLength = state.pendingFreshArrayLength;
        char savedPendingFreshArrayType = state.pendingFreshArrayType;
        ConcurrentMap<Integer, java.util.Random> savedLocalRandomSources = state.localRandomSources;
        java.util.Random savedPendingRandomSource = state.pendingRandomSource;
        String savedPendingPartName = state.pendingPartName;
        String savedBoneName = state.boneName;
        String savedParentBone = state.parentBone;
        String savedNextParent = state.nextParent;
        String savedLastFlushedBone = state.lastFlushedBone;
        ConcurrentList<float[]> savedPendingCubes = state.pendingCubes;
        int[] savedPendingUv = state.pendingUv;
        float[] savedPendingPivot = state.pendingPivot;
        float[] savedPendingRotation = state.pendingRotation;
        float savedPendingScale = state.pendingScale;
        boolean savedPendingMirror = state.pendingMirror;
        state.localSlotBone = Concurrent.newMap();
        state.slotToCubes = Concurrent.newMap();
        state.numericLocals = Concurrent.newMap();
        state.localFloatArrays = Concurrent.newMap();
        state.pendingFreshArrayLength = null;
        state.pendingFreshArrayType = '\0';
        state.localRandomSources = Concurrent.newMap();
        state.pendingRandomSource = null;
        state.pendingPartName = null;
        state.boneName = null;
        state.parentBone = null;
        state.nextParent = null;
        state.lastFlushedBone = null;
        state.pendingCubes = Concurrent.newList();
        state.pendingUv = new int[]{ 0, 0 };
        state.pendingPivot = new float[]{ 0, 0, 0 };
        state.pendingRotation = new float[]{ 0, 0, 0 };
        state.pendingScale = 1f;
        try {
            walkInstructions(inlined.instructions, state, cache);
        } finally {
            state.paramIntValues = previousInts;
            state.paramFloatValues = previousFloats;
            state.localSlotBone = savedLocalSlotBone;
            state.slotToCubes = savedSlotToCubes;
            state.numericLocals = savedNumericLocals;
            state.localFloatArrays = savedLocalFloatArrays;
            state.pendingFreshArrayLength = savedPendingFreshArrayLength;
            state.pendingFreshArrayType = savedPendingFreshArrayType;
            state.localRandomSources = savedLocalRandomSources;
            state.pendingRandomSource = savedPendingRandomSource;
            state.pendingPartName = savedPendingPartName;
            state.boneName = savedBoneName;
            state.parentBone = savedParentBone;
            state.nextParent = savedNextParent;
            state.lastFlushedBone = savedLastFlushedBone;
            state.pendingCubes = savedPendingCubes;
            state.pendingUv = savedPendingUv;
            state.pendingPivot = savedPendingPivot;
            state.pendingRotation = savedPendingRotation;
            state.pendingScale = savedPendingScale;
            state.pendingMirror = savedPendingMirror;
        }
    }

    /**
     * Snapshot of the captured inlined-method parameters; just a pair of arrays sized to
     * the callee's local-variable count.
     *
     * @param ints the int view of each captured numeric arg, indexed by callee local slot
     * @param floats the float view of each captured numeric arg, indexed by callee local slot
     */
    private record InlineParams(int @NotNull [] ints, float @NotNull [] floats) {}

    /**
     * Captures call-site numeric literals as parameter slot values for an inlined static
     * method. Walks the descriptor's arg types in reverse, popping one numeric off
     * {@link ParseState#numStack} per primitive arg, and writes the value into the
     * matching slot of two parallel arrays sized to {@code maxLocals}. Reference args
     * (CubeDeformation, PartDefinition, etc.) skip the pop since they never appear on
     * {@code numStack}. Long / double args occupy two slots per JVM convention; only the
     * first slot receives the captured value.
     *
     * <p>Mirrors what the JVM does at an actual {@code invokestatic}: the args are popped
     * from the operand stack in reverse, then bound to slots 0..N for the callee. The
     * parser's symbolic stack carries only numeric values, so the capture pops only the
     * numeric subset and leaves slot bindings for refs as zero (the inlined method's
     * {@code aload} for a ref slot already doesn't touch {@code numStack}, so leaving the
     * slot zero is harmless).
     *
     * @param descriptor the call-site method descriptor
     * @param state the parse state whose {@code numStack} supplies the arg literals
     * @param maxLocals the callee's local-variable count (arrays are sized to at least this)
     * @return the captured int / float parameter arrays, indexed by callee local slot
     */
    private static @NotNull InlineParams captureInlineParams(
        @NotNull String descriptor,
        @NotNull ParseState state,
        int maxLocals
    ) {
        int slots = Math.max(maxLocals, 8);
        int[] ints = new int[slots];
        float[] floats = new float[slots];
        char[] argTypes = parseArgTypes(descriptor);
        int slotCursor = 0;
        int[] slotPerArg = new int[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            slotPerArg[i] = slotCursor;
            slotCursor += (argTypes[i] == 'D' || argTypes[i] == 'J') ? 2 : 1;
        }
        // Pop numeric args from top (last) to bottom (first) - matches stack reverse order.
        for (int i = argTypes.length - 1; i >= 0; i--) {
            char t = argTypes[i];
            if (t != 'L' && t != '[') {
                // popNumber returns null on empty / non-numeric top; treat both as zero
                // (matches the previous NON_LITERAL fallback's silent-zero arithmetic).
                Number popped = state.numStack.popNumber();
                int slot = slotPerArg[i];
                if (slot < slots) {
                    ints[slot] = popped == null ? 0 : popped.intValue();
                    floats[slot] = popped == null ? 0f : popped.floatValue();
                }
            }
        }
        return new InlineParams(ints, floats);
    }

    /**
     * Returns the arg-type characters from a JVM method descriptor in source order.
     * E.g. {@code (IZZLnet/minecraft/X;)V} yields {@code ['I', 'Z', 'Z', 'L']}. Reference
     * types collapse to {@code 'L'}; arrays collapse to {@code '['}. Used by
     * {@link #captureInlineParams} to decide which args are primitives that pop a numeric
     * off {@link ParseState#numStack}.
     *
     * @param descriptor the JVM method descriptor
     * @return the arg-type characters in source order, or an empty array on a malformed
     *     descriptor
     */
    private static char @NotNull [] parseArgTypes(@NotNull String descriptor) {
        int paren = descriptor.indexOf('(');
        int close = descriptor.indexOf(')');
        if (paren < 0 || close < 0) return new char[0];
        java.util.List<Character> out = new java.util.ArrayList<>();
        int i = paren + 1;
        while (i < close) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                out.add('L');
                int end = descriptor.indexOf(';', i);
                if (end < 0) return new char[0];
                i = end + 1;
            } else if (c == '[') {
                out.add('[');
                while (i < close && descriptor.charAt(i) == '[') i++;
                if (i < close && descriptor.charAt(i) == 'L') {
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) return new char[0];
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                out.add(c);
                i++;
            }
        }
        char[] arr = new char[out.size()];
        for (int j = 0; j < out.size(); j++) arr[j] = out.get(j);
        return arr;
    }

    /**
     * Collects the string-typed args of an immediately-preceding {@code Set.of(...)} call.
     * Walks backwards from {@code methodInsn} through the InsnList, skipping pseudo-nodes
     * (line numbers, frames, labels) until {@code expectedCount} {@link LdcInsnNode} String
     * loads have been gathered or a non-string instruction is hit. Source order is preserved
     * (oldest LDC first). Returns {@code null} when the expected count couldn't be matched
     * - the caller treats null as "no filter" so a malformed walk doesn't drop every bone.
     *
     * <p>Used by the {@code Set.of} dispatch in {@link #handleMethodInsn} to capture the
     * retained bone names ahead of a {@code retainPartsAndChildren} call. Only the
     * fixed-arity overloads ({@code Set.of()} through {@code Set.of(Object x10)}) are
     * recognised; the varargs {@code Set.of(Object[])} overload would need an
     * {@code anewarray}/{@code aastore} walker and isn't used by any vanilla entity factory
     * observed so far.
     *
     * @param methodInsn the {@code Set.of(...)} call instruction to walk back from
     * @return the string args in source order, or {@code null} when the expected count
     *     couldn't be matched (treated as "no filter" by the caller)
     */
    private static @Nullable Set<String> collectSetOfStringArgs(@NotNull MethodInsnNode methodInsn) {
        char[] argTypes = parseArgTypes(methodInsn.desc);
        // Varargs Set.of([Ljava/lang/Object;) collapses to a single '[' arg - skip it for now.
        for (char t : argTypes) if (t != 'L') return null;
        int expectedCount = argTypes.length;
        Set<String> names = new LinkedHashSet<>();
        java.util.Deque<String> collected = new java.util.ArrayDeque<>();
        AbstractInsnNode prev = methodInsn.getPrevious();
        while (prev != null && collected.size() < expectedCount) {
            if (prev instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                collected.addFirst(s);
            } else if (AsmKit.isPseudoNode(prev)) {
                // line number / frame / label nodes - skip silently
            } else {
                return null;
            }
            prev = prev.getPrevious();
        }
        if (collected.size() != expectedCount) return null;
        names.addAll(collected);
        return names;
    }

    /**
     * Warns when {@code state.numStack} has fewer than {@code required} entries at a
     * builder-dispatch site. The pop still proceeds with zero-fill (via
     * {@link #popIntWithDiagnostics} / {@link #popFloatWithDiagnostics}'s empty-stack
     * fallback), but the diagnostic surfaces the underflow so a bogus-coord cube doesn't
     * silently ship.
     *
     * @param state the parse state whose {@code numStack} depth is checked
     * @param required the minimum number of entries the dispatch site needs
     * @param where a human-readable dispatch-site label for the diagnostic
     */
    private static void requireStack(@NotNull ParseState state, int required, @NotNull String where) {
        if (state.diagnostics == null || state.currentSource == null) return;
        int have = state.numStack.size();
        if (have < required)
            state.diagnostics.warn(
                "%s at %s: numStack underflow (need %d, have %d) - output coords likely wrong",
                state.currentSource.subjectId(), where, required, have
            );
    }

    /**
     * Handles {@code CubeListBuilder.create / texOffs / addBox / mirror} calls, consuming
     * literals off {@link ParseState#numStack} and emitting pending cubes. Five {@code addBox}
     * variants are recognised - see the inline comment at the {@code addBox} case for the
     * per-variant descriptor and pop order.
     *
     * @param methodInsn the {@code CubeListBuilder} method call being dispatched
     * @param state the parse state whose pending cube / UV / mirror fields are mutated
     */
    private static void handleCubeListBuilder(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
        switch (methodInsn.name) {
            case "create" -> {
                // CubeListBuilder.create() opens a builder chain. Snapshot the outer bone
                // name (the ldc String pushed before the chain) into {@code boneName} so
                // inner ldc Strings from per-cube addBox(String, ...) variants don't
                // overwrite the addOrReplaceChild key. Also snapshot the parent captured
                // from the most recent aload (typically the slot holding the parent
                // PartDefinition returned by an earlier addOrReplaceChild).
                // Clear {@code lastFlushedBone} since a new builder is now on the operand
                // stack - any astore_N that follows stores the builder, not a stale
                // PartDefinition the caller already discarded via {@code pop}.
                //
                // The parentBone snapshot is conditional on having a pendingPartName so
                // shared-builder factories (DonkeyModel.modifyMesh pre-builds one chest
                // CubeListBuilder and reuses it for left_chest + right_chest) don't
                // capture stale {@code nextParent} from a previous bone group. The
                // shared-builder pattern is: create() with no preceding ldc String, then
                // later aload(parent), aload(builder_slot), ldc(name), PartPose,
                // addOrReplaceChild. By the time the flush fires, the parent has been
                // re-aload'd into {@code nextParent} so resolvedParent picks it up
                // through the nextParent fallback in {@link #flushPendingBone}.
                if (state.pendingPartName != null) {
                    state.boneName = state.pendingPartName;
                    state.parentBone = state.nextParent;
                }
                state.nextParent = null;
                state.lastFlushedBone = null;
                // Each new builder starts fresh - clear the mirror flag so it doesn't leak
                // from a previous bone's CubeListBuilder.mirror() call. Vanilla constructs a
                // new CubeListBuilder per addOrReplaceChild via create(), and the new
                // builder's mirror starts false.
                state.pendingMirror = false;
            }
            case "texOffs" -> {
                if (methodInsn.desc.startsWith("(II")) {
                    requireStack(state, 2, "CubeListBuilder.texOffs(II)");
                    state.pendingUv[1] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) v");
                    state.pendingUv[0] = popIntWithDiagnostics(state, "CubeListBuilder.texOffs(II) u");
                }
            }
            case "addBox" -> {
                // Five addBox variants observed in vanilla (names/CubeDeformation refs don't
                // land on numStack, so only the numeric literals + boolean mirror flag drive
                // the pop order):
                //  1. (FFFFFF) or (FFFFFF + CubeDeformation) - origin xyz + size whd; uses current texOffs.
                //  2. (FFFFFFZ) or (FFFFFFZ + CubeDeformation) - origin + size + mirror flag.
                //     {@code GuardianModel.createBodyLayer}'s third head cube uses this with
                //     {@code mirror=true}; without popping the boolean, the numStack top
                //     consumed as the d-size is the mirror int (1), not the actual depth.
                //  3. (Ljava/lang/String;FFFFFF) - named single-cube, uses current texOffs.
                //  4. (Ljava/lang/String;FFFIIIII) - named multi-cube with inline (w,h,d,u,v) ints.
                //     Dragon's head bone stacks 6 cubes this way, each with its own UV.
                //  5. (Ljava/lang/String;FFFIII,CubeDeformation,II) - named multi-cube with
                //     int (w,h,d) + inline UV (u,v), split by a CubeDeformation ref (cat / ocelot
                //     nose / ear / tail via AdultFelineModel.createBodyMesh). The two named-int
                //     variants (4, 5) are disambiguated by whether "CubeDeformation;II" appears
                //     in the descriptor, since both start (Ljava/lang/String;FFFIII).
                if (methodInsn.desc.startsWith("(Ljava/lang/String;FFFIIIII")) {
                    requireStack(state, 8, "CubeListBuilder.addBox(name,FFFIIIII)");
                    int v = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) v");
                    int u = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) u");
                    int d = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) d");
                    int h = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) h");
                    int w = popIntWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) w");
                    float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) z");
                    float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) y");
                    float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(name,FFFIIIII) x");
                    emitCube(state, x, y, z, w, h, d, u, v);
                } else if (methodInsn.desc.startsWith("(Ljava/lang/String;FFFIII")
                        && methodInsn.desc.contains("CubeDeformation;II")) {
                    // Variant: addBox(name, F, F, F, I, I, I, CubeDeformation, I, I) - named
                    // multi-cube with int dimensions (w, h, d) plus per-cube UV (u, v) split by
                    // a CubeDeformation arg. Used by AdultFelineModel.createBodyMesh's nose /
                    // ear / tail bones (cat / ocelot family). The CubeDeformation is an object
                    // ref, not on numStack; pop the 5 ints + 3 floats around it.
                    requireStack(state, 8, "CubeListBuilder.addBox(name,FFFIII,CubeDeformation,II)");
                    int v = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) v");
                    int u = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) u");
                    int d = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) d");
                    int h = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) h");
                    int w = popIntWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) w");
                    float z = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) z");
                    float y = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) y");
                    float x = popFloatWithDiagnostics(state, "addBox(name,FFFIII,CubeDeformation,II) x");
                    emitCube(state, x, y, z, w, h, d, u, v);
                } else if (methodInsn.desc.startsWith("(FFFFFFZ") || methodInsn.desc.startsWith("(Ljava/lang/String;FFFFFFZ")) {
                    // Mirror-flagged addBox: pop the trailing boolean first so the float
                    // pop order isn't shifted by one. {@code mirror=true} flips face UVs on
                    // this cube only (does not affect the builder's pendingMirror state for
                    // subsequent cubes).
                    requireStack(state, 7, "CubeListBuilder.addBox(FFFFFFZ)");
                    int cubeMirror = popIntWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) mirror");
                    float d = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) d");
                    float h = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) h");
                    float w = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) w");
                    float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) z");
                    float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) y");
                    float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFFZ) x");
                    boolean savedMirror = state.pendingMirror;
                    if (state.mode == Mode.EVALUATING) state.pendingMirror = cubeMirror != 0;
                    emitCube(state, x, y, z, w, h, d, state.pendingUv[0], state.pendingUv[1]);
                    state.pendingMirror = savedMirror;
                } else if (methodInsn.desc.startsWith("(FFFFFF") || methodInsn.desc.startsWith("(Ljava/lang/String;FFFFFF")) {
                    requireStack(state, 6, "CubeListBuilder.addBox(FFFFFF)");
                    float d = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) d");
                    float h = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) h");
                    float w = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) w");
                    float z = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) z");
                    float y = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) y");
                    float x = popFloatWithDiagnostics(state, "CubeListBuilder.addBox(FFFFFF) x");
                    emitCube(state, x, y, z, w, h, d, state.pendingUv[0], state.pendingUv[1]);
                }
            }
            case "mirror" -> {
                // CubeListBuilder.mirror(Z) sets the builder's mirror flag explicitly.
                // CubeListBuilder.mirror() is a no-arg shortcut for mirror(true) - vanilla's
                // AbstractEquineModel.createBodyMesh / similar use this for the right-side
                // legs / right ear to flip UVs so the leg's outer face draws the same texture
                // region as the left leg's outer face. Captured into
                // {@link ParseState#pendingMirror} so it propagates to the cube's emitted
                // {@code mirror} field; the kit's UV resolution then flips face UVs
                // horizontally for those cubes (already wired via
                // {@code rect.toUvCorners(..., mirror)}).
                if (methodInsn.desc.startsWith("(Z")) {
                    requireStack(state, 1, "CubeListBuilder.mirror(Z)");
                    int mirrorVal = popIntWithDiagnostics(state, "CubeListBuilder.mirror(Z)");
                    if (state.mode == Mode.EVALUATING)
                        state.pendingMirror = mirrorVal != 0;
                } else if (methodInsn.desc.startsWith("()")) {
                    // No-arg mirror() is the equivalent of mirror(true).
                    if (state.mode == Mode.EVALUATING)
                        state.pendingMirror = true;
                }
            }
            default -> { }
        }
    }

    /**
     * Appends one cube to {@link ParseState#pendingCubes}, capturing the current
     * {@link ParseState#pendingInflate} (indices 8-10, the 3-component grow) and
     * {@link ParseState#pendingMirror} (index 11, {@code 0f} / {@code 1f}), then resets the
     * pending inflate back to {@link ParseState#defaultInflate} (the call-site's
     * {@code CubeDeformation}, zero for normal sources) so a per-cube inline deformation
     * doesn't leak into the next addBox in the same builder chain. Cube layout is
     * {@code [x, y, z, w, h, d, u, v, gx, gy, gz, mirror]}.
     *
     * @param state the parse state whose pending cube list and inflate/mirror flags are read
     * @param x cube origin (min corner) X
     * @param y cube origin (min corner) Y
     * @param z cube origin (min corner) Z
     * @param w cube size (extent) along X
     * @param h cube size (extent) along Y
     * @param d cube size (extent) along Z
     * @param u texture U offset
     * @param v texture V offset
     */
    private static void emitCube(@NotNull ParseState state, float x, float y, float z, float w, float h, float d, int u, int v) {
        // Cube layout slot 11: mirror flag (0f = not mirrored, 1f = mirrored). Per-cube
        // mirror-flagged addBox variants pre-set {@code state.pendingMirror} and restore it
        // after; builder-level {@code mirror(Z)} state persists until changed.
        state.pendingCubes.add(new float[]{
            x, y, z, w, h, d, u, v,
            state.pendingInflate[0], state.pendingInflate[1], state.pendingInflate[2],
            state.pendingMirror ? 1f : 0f
        });
        // Reset to the source's defaultInflate (zero for normal entity sources, non-zero for
        // composite-overlay sources whose factory takes a {@code CubeDeformation} arg) so
        // every cube in the chain picks up the call-site's deformation by default. Inline
        // {@code new CubeDeformation(F)} / {@code .extend(F)} per-cube overrides still run
        // through their handlers and replace pendingInflate before the next emitCube fires.
        state.pendingInflate = state.defaultInflate.clone();
        // Clear the inline-deformation marker too - if a {@code new CubeDeformation(F)} was
        // consumed inline by THIS addBox (no intervening ASTORE), it shouldn't leak into the
        // next ASTORE and accidentally tag an unrelated slot.
        state.pendingFreshDeformationInflate = null;
    }

    /**
     * Handles {@code CubeDeformation.extend(F)} / {@code .extend(FFF)} which consume float
     * inflate args from the operand stack adjacent to a subsequent {@code CubeListBuilder
     * .addBox}. Without this, the inflate arg leaks onto {@link ParseState#numStack} and
     * shifts the addBox pop order, producing garbage cube dimensions (HumanoidModel's hat
     * bone is the canonical example: a {@code .extend(0.5f)} on the deformation arg
     * leaves {@code 0.5} above the addBox's d-arg, so addBox sees d=0.5 and h/w/z/y/x
     * shifted one slot down).
     *
     * <p>Constructor variants ({@code CubeDeformation.<init>(F)} / {@code <init>(FFF)}) pop
     * the inflate literal(s) <b>unconditionally</b> (NOT gated on the Java pipeline) - the
     * legacy block-entity walk (which runs with {@code LEGACY_LINEAR}) would
     * otherwise leave the inflate float on {@code numStack} and corrupt the following
     * {@code addBox}'s coordinate pops. Asymmetric {@code (FFF)} variants keep their three
     * components (decision 24 - the legacy lossy /3 average dies here). {@code .extend}
     * always <i>adds</i> to the current {@code pendingInflate}; a {@code <init>}
     * <i>replaces</i> it and records {@link ParseState#pendingFreshDeformationInflate} for
     * the next {@code ASTORE}.
     *
     * @param methodInsn the {@code CubeDeformation} method call being dispatched
     * @param state the parse state whose pending inflate is set
     */
    private static void handleCubeDeformation(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
        if ("extend".equals(methodInsn.name)) {
            if (methodInsn.desc.startsWith("(FFF")) {
                requireStack(state, 3, "CubeDeformation.extend(FFF)");
                float ez = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) z");
                float ey = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) y");
                float ex = popFloatWithDiagnostics(state, "CubeDeformation.extend(FFF) x");
                if (state.mode == Mode.EVALUATING)
                    state.pendingInflate = new float[]{
                        state.pendingInflate[0] + ex, state.pendingInflate[1] + ey, state.pendingInflate[2] + ez
                    };
            } else if (methodInsn.desc.startsWith("(F")) {
                requireStack(state, 1, "CubeDeformation.extend(F)");
                float e = popFloatWithDiagnostics(state, "CubeDeformation.extend(F)");
                if (state.mode == Mode.EVALUATING)
                    state.pendingInflate = new float[]{
                        state.pendingInflate[0] + e, state.pendingInflate[1] + e, state.pendingInflate[2] + e
                    };
            }
            return;
        }
        // Inline {@code new CubeDeformation(F)} / {@code (FFF)} - pop the inflate literal(s)
        // unconditionally (NOT gated on the Java pipeline). The block-entity (legacy) pipeline
        // runs with {@code LEGACY_LINEAR}; gating the pop there left the inflate float
        // on numStack, corrupting the following addBox's coordinate pops. copper_golem_statue is
        // the first block-entity model to construct a CubeDeformation inline (head cube + the two
        // antenna cubes use {@code new CubeDeformation(0.015F)} / {@code (-0.015F)}); before it,
        // block-entity models only referenced {@code CubeDeformation.NONE}. emitCube applies
        // pendingInflate in both pipelines, so the inflate is now also baked into those cubes.
        if (AsmKit.INIT.equals(methodInsn.name)) {
            if (methodInsn.desc.startsWith("(FFF")) {
                requireStack(state, 3, "CubeDeformation.<init>(FFF)");
                float dz = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) z");
                float dy = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) y");
                float dx = popFloatWithDiagnostics(state, "CubeDeformation.<init>(FFF) x");
                state.pendingInflate = new float[]{ dx, dy, dz };
                state.pendingFreshDeformationInflate = state.pendingInflate.clone();
            } else if (methodInsn.desc.startsWith("(F")) {
                requireStack(state, 1, "CubeDeformation.<init>(F)");
                float f = popFloatWithDiagnostics(state, "CubeDeformation.<init>(F)");
                state.pendingInflate = new float[]{ f, f, f };
                state.pendingFreshDeformationInflate = state.pendingInflate.clone();
            }
        }
    }

    /**
     * Handles {@code PartPose.offset / rotation / offsetAndRotation / scaled} calls,
     * consuming literals off {@link ParseState#numStack} and storing the result on
     * {@link ParseState#pendingPivot} / {@link ParseState#pendingRotation} /
     * {@link ParseState#pendingScale} for the next {@code addOrReplaceChild} flush. Rotation
     * args arrive in radians and are converted to degrees for the JSON.
     *
     * @param methodInsn the {@code PartPose} method call being dispatched
     * @param state the parse state whose pending pivot / rotation / scale are set
     */
    private static void handlePartPose(@NotNull MethodInsnNode methodInsn, @NotNull ParseState state) {
        switch (methodInsn.name) {
            case "offset" -> {
                if (methodInsn.desc.startsWith("(FFF")) {
                    requireStack(state, 3, "PartPose.offset(FFF)");
                    float pz = popFloatWithDiagnostics(state, "PartPose.offset(FFF) z");
                    float py = popFloatWithDiagnostics(state, "PartPose.offset(FFF) y");
                    float px = popFloatWithDiagnostics(state, "PartPose.offset(FFF) x");
                    state.pendingPivot = new float[]{ px, py, pz };
                    state.pendingRotation = new float[]{ 0, 0, 0 };
                }
            }
            case "rotation" -> {
                // PartPose.rotation(rx, ry, rz) - rotation only, pivot stays at origin.
                // Used by BedRenderer legs.
                if (methodInsn.desc.startsWith("(FFF")) {
                    requireStack(state, 3, "PartPose.rotation(FFF)");
                    float rz = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) z");
                    float ry = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) y");
                    float rx = popFloatWithDiagnostics(state, "PartPose.rotation(FFF) x");
                    state.pendingPivot = new float[]{ 0, 0, 0 };
                    state.pendingRotation = new float[]{
                        (float) Math.toDegrees(rx),
                        (float) Math.toDegrees(ry),
                        (float) Math.toDegrees(rz)
                    };
                }
            }
            case "offsetAndRotation" -> {
                if (methodInsn.desc.startsWith("(FFFFFF")) {
                    requireStack(state, 6, "PartPose.offsetAndRotation(FFFFFF)");
                    float rz = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) rz");
                    float ry = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) ry");
                    float rx = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) rx");
                    float pz = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) pz");
                    float py = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) py");
                    float px = popFloatWithDiagnostics(state, "PartPose.offsetAndRotation(FFFFFF) px");
                    state.pendingPivot = new float[]{ px, py, pz };
                    state.pendingRotation = new float[]{
                        (float) Math.toDegrees(rx),
                        (float) Math.toDegrees(ry),
                        (float) Math.toDegrees(rz)
                    };
                }
            }
            case "scaled" -> {
                // PartPose.scaled(F) - uniform scale around pivot at render time. Vanilla's
                // render order is translate(pivot) * rotation * scale * cube, so applying
                // scale to each cube's origin + size (before rotation + pivot) reproduces it.
                // Baked in {@link #flushPendingBone} so the scale is tied to the cubes it
                // applies to and resets when the next addOrReplaceChild finalises the bone.
                if (methodInsn.desc.startsWith("(F") && !methodInsn.desc.startsWith("(FF")) {
                    requireStack(state, 1, "PartPose.scaled(F)");
                    state.pendingScale = popFloatWithDiagnostics(state, "PartPose.scaled(F)");
                }
            }
            default -> { }
        }
    }

    /**
     * Closes the current pending bone, records its cumulative {@link BoneMeta} scale for future
     * children, builds the parent-local bone JSON, then resets all pending state for the next
     * {@code addOrReplaceChild}.
     * <p>
     * Bones are emitted in the parent-local relative frame - {@code parentScale * local_pivot},
     * local rotation, plus the {@code parent} link - preserving cube-less pose-only parents /
     * container groups (wolf {@code head}/{@code tail}, bogged {@code mushrooms}) so the kit can
     * resolve the chain and hide groups as a unit. The kit composes each ancestor's rotation +
     * translation at render, so no world fold is baked here.
     *
     * @param state the parse state whose pending bone fields are flushed and reset
     */
    private static void flushPendingBone(@NotNull ParseState state) {
        // Prefer the snapshot taken at CubeListBuilder.create(); fall back to pendingPartName
        // for models that set the name immediately before addOrReplaceChild (no builder
        // chain - rare, but cheap to support).
        String name = state.boneName != null ? state.boneName : state.pendingPartName;
        if (name != null) {
            // Resolve the parent. Fall back to nextParent when parentBone wasn't captured at a
            // CubeListBuilder.create() call: vanilla's pre-built-builder pattern
            // ({@code AdultAxolotlModel.createBodyLayer} pre-builds gill / leg cube lists into
            // local slots and reuses them across multiple {@code addOrReplaceChild} calls)
            // doesn't fire {@code create()} between the parent's {@code aload} and the child's
            // flush, so parentBone stays null; nextParent still holds the most recent
            // {@code aload} of the parent's PartDefinition slot. For the standard chain (where
            // create() captures parentBone) nextParent is null at flush so this is a no-op.
            String resolvedParent = state.parentBone != null ? state.parentBone : state.nextParent;
            BoneMeta parentMeta = resolvedParent != null ? state.boneMeta.get(resolvedParent) : null;
            float parentScale = parentMeta != null ? parentMeta.scale : 1f;

            // Cumulative model scale (ancestor scale times this bone's local scale). Only the scale
            // is folded here - the kit's ModelPart-style chain
            // ({@link lib.minecraft.renderer.engine.kit.EntityGeometryKit} resolveChainFrom / the
            // shared {@link lib.minecraft.renderer.engine.kit.BoneKit}) supplies each ancestor's
            // rotation + translation at render. Children read the cumulative scale back via
            // {@link BoneMeta}.
            float worldScale = parentScale * state.pendingScale;
            state.boneMeta.put(name, new BoneMeta(worldScale));

            // Emit the bone in its PARENT-LOCAL frame: local pivot scaled by the ancestor cumulative
            // scale, local rotation, plus the {@code parent} link. Cube-less pose-only parents /
            // container groups (wolf head/tail, bogged mushrooms) are emitted too - the kit needs the
            // parent present to resolve the chain (a missing parent silently degrades to root,
            // dropping the group transform), and keeping groups lets their children hide as a unit.
            float[] localPivot = {
                parentScale * state.pendingPivot[0],
                parentScale * state.pendingPivot[1],
                parentScale * state.pendingPivot[2]
            };
            state.bones.add(name, buildBone(localPivot, state.pendingRotation, worldScale, state.pendingCubes, resolvedParent));
            // Record the resolved parent so the post-walk retainedNames / clearedBones filters
            // can chase the ancestor chain. Root-level bones map to a null parent.
            state.boneParents.put(name, resolvedParent);
            state.lastFlushedBone = name;
        }
        state.pendingPartName = null;
        state.boneName = null;
        state.parentBone = null;
        state.pendingCubes = Concurrent.newList();
        state.pendingPivot = new float[]{ 0, 0, 0 };
        state.pendingRotation = new float[]{ 0, 0, 0 };
        state.pendingUv = new int[]{ 0, 0 };
        state.pendingScale = 1f;
    }

    /**
     * The two walk modes the parser runs in, replacing the historical
     * {@code paramFloatValues}-nullness dual-mode gate with an explicit,
     * once-determined mode. {@link #EVALUATING} is the Java entity pipeline
     * (arithmetic evaluation, stack accounting, loop unrolling, inflate / mirror
     * capture); {@link #LEGACY_LINEAR} is the block-entity literal-stack-only walk
     * that is byte-stable by construction. The mode is fixed at parse seed from
     * whether the request carries a {@code paramFloatValues} table and is invariant
     * for the whole walk - {@link #inlineStaticMethodBody} swaps the underlying
     * float table for an inlined callee but preserves its non-null-ness, so the mode
     * never changes mid-parse.
     */
    private enum Mode {
        LEGACY_LINEAR,
        EVALUATING
    }

    /**
     * Mutable parse state threaded through one top-level method parse (plus any inlined invokestatic targets).
     */
    private static final class ParseState {

        /**
         * The walk mode for this parse, fixed at seed (see {@link Mode}). Every historical
         * {@code paramFloatValues}-nullness gate reads {@code mode == Mode.EVALUATING}; the
         * {@link #paramFloatValues} table itself is still consulted as substitution data.
         */
        @NotNull Mode mode = Mode.LEGACY_LINEAR;

        /**
         * Bounded retention for the symbolic operand stack: 16 entries is comfortably above
         * the deepest single-expression stack vanilla bytecode pushes (PartPose six-float
         * factory + a few coercions) while still being a hard cap that surfaces parser
         * accounting bugs as overflow warnings rather than runaway growth.
         */
        private static final int NUM_STACK_CAPACITY = 16;

        /**
         * Marker pushed onto {@link #refStack} when the bound reference parameter is loaded. A
         * sentinel distinct from any enum constant field name so the {@code IF_ACMP*} evaluator
         * can tell the parameter side from the constant side of the comparison.
         */
        private static final @NotNull String REF_PARAM_SENTINEL = " REF_PARAM";

        final @NotNull AsmKit.LiteralStack numStack = new AsmKit.LiteralStack(NUM_STACK_CAPACITY);

        /**
         * Int values to substitute for {@code ILOAD_N} parameters when evaluating branches.
         * On an {@code ILOAD slot} the resolved {@code paramIntValues[slot]} is pushed onto
         * whichever stack the current pipeline consumes: {@link #numStack} in the Java
         * pipeline ({@link #paramFloatValues} non-null, so the value can also feed
         * {@code IADD} / {@code ISUB} arithmetic) or {@link #branchStack} in the legacy
         * literal-stack pipeline. The subsequent {@code IFEQ} / {@code IFNE} / switch then
         * pops a concrete value and follows (or not) the branch. {@code null} disables branch
         * evaluation - the parser falls back to its default linear walk and lets both sides
         * of any conditional land on the stack. Also grown on demand by the for-loop unroller
         * (see {@link #ensureIntSlotCapacity}) to hold the injected iterator value.
         */
        int @Nullable [] paramIntValues;

        /**
         * Float values to substitute for {@code FLOAD slot} parameter loads when
         * evaluating arithmetic. {@code null} disables float param substitution AND
         * arithmetic evaluation entirely (the legacy behaviour). When non-null,
         * see {@link Source#paramFloatValues()} for the substitution rules.
         */
        float @Nullable [] paramFloatValues;

        /**
         * Pushed by ILOAD when the slot maps to a paramIntValues entry; consumed by IFEQ / IFNE.
         */
        final @NotNull ConcurrentList<Integer> branchStack = Concurrent.newList();

        /**
         * Enum-reference branch evaluation (set from {@link Source#refParam()}). When
         * {@link #refParamOwner} is non-null, {@code ALOAD <refParamSlot>} pushes the
         * {@link #REF_PARAM_SENTINEL} marker onto {@link #refStack}, {@code GETSTATIC
         * <refParamOwner>.<name>} pushes the constant field name, and {@code IF_ACMPEQ} /
         * {@code IF_ACMPNE} compares the bound {@link #refParamValue} against the constant to
         * follow the branch. Used to split {@code createHangingSignLayer(Attachment)} per
         * attachment without hardcoding the mesh.
         */
        @Nullable String refParamOwner;
        @Nullable String refParamValue;
        int refParamSlot = -1;

        /**
         * Object-reference branch stack for {@link #refParamOwner} evaluation. Holds either
         * {@link #REF_PARAM_SENTINEL} (the bound attachment parameter) or an enum constant field
         * name pushed by {@code GETSTATIC <refParamOwner>}. Each {@code aload;getstatic;if_acmp}
         * triplet pushes two entries and pops them in the comparison, keeping the stack balanced.
         */
        final @NotNull ConcurrentList<String> refStack = Concurrent.newList();

        /**
         * Most recent ldc String - tracks both bone names and inner cube names.
         */
        @Nullable String pendingPartName;

        /**
         * Snapshot of {@link #pendingPartName} at CubeListBuilder.create(); preserved across inner ldc Strings from addBox(String, ...) variants.
         */
        @Nullable String boneName;

        /**
         * Parent bone name captured from {@link #nextParent} at CubeListBuilder.create().
         */
        @Nullable String parentBone;

        /**
         * Parent bone captured from the most recent aload_N; consumed by CubeListBuilder.create().
         */
        @Nullable String nextParent;

        /**
         * Most recently flushed bone; the next astore_N after flush binds it to that slot.
         */
        @Nullable String lastFlushedBone;

        /**
         * JVM local-variable slot -> bone name that was stored there via astore_N.
         */
        @NotNull ConcurrentMap<Integer, String> localSlotBone = Concurrent.newMap();

        /**
         * JVM local-variable slot -> captured CubeListBuilder cubes, for builders reused by multiple addOrReplaceChild calls.
         */
        @NotNull ConcurrentMap<Integer, ConcurrentList<float[]>> slotToCubes = Concurrent.newMap();

        /**
         * JVM local-variable slot -> last numeric value stored to that slot by ISTORE /
         * FSTORE / DSTORE / LSTORE. Read back on ILOAD / FLOAD / DLOAD / LLOAD before the
         * {@link #paramIntValues} / {@link #paramFloatValues} fallback, so a
         * {@code ldc <value>; fstore <slot>; ...; fload <slot>} sequence (vanilla
         * {@code BlazeModel.createBodyLayer}'s rolling-angle accumulator slot 2;
         * {@code SquidModel.createBodyLayer}'s reused-angle double slot 7) folds back to the
         * literal value instead of the param-table default. Reset by
         * {@link #inlineStaticMethodBody} so JVM scoping (callee locals don't leak into
         * caller) is preserved.
         */
        @NotNull ConcurrentMap<Integer, Number> numericLocals = Concurrent.newMap();

        /**
         * JVM local-variable slot -> tracked {@code float[]} created by a
         * {@code NEWARRAY float; ASTORE <slot>} pair earlier in the method. Vanilla's
         * {@code SilverfishModel.createBodyLayer} uses this for its cumulative-pivot
         * {@code float[7]}: each loop iteration writes {@code f[i] = currentF} via
         * {@code FASTORE} and the post-loop layer bones read back via
         * {@code FALOAD}. Cleared in {@link #inlineStaticMethodBody} so the callee can't
         * leak its local arrays into the caller's scope.
         */
        @NotNull ConcurrentMap<Integer, float[]> localFloatArrays = Concurrent.newMap();

        /**
         * JVM local-variable slot -> tracked {@link java.util.Random} created by a seeded
         * {@code RandomSource.createThreadLocalInstance(J)} factory + {@code ASTORE} pair.
         * Each subsequent {@code aload <slot>; <bound>; invokeinterface
         * RandomSource.nextInt(I)I} sequence steps the random and pushes the literal
         * result. Vanilla's {@code GhastModel.createBodyLayer} uses seed {@code 1660L} to
         * deterministically produce tentacle heights; the parser substitutes the same
         * {@link java.util.Random} algorithm (Mojang's {@code BitRandomSource} matches the
         * standard LCG bit-for-bit). Cleared in {@link #inlineStaticMethodBody}.
         */
        @NotNull ConcurrentMap<Integer, java.util.Random> localRandomSources = Concurrent.newMap();

        /**
         * Random instance captured by a {@code RandomSource.createThreadLocalInstance(J)}
         * invokestatic that hasn't yet been bound to a slot via the subsequent
         * {@code ASTORE}. Reset on consumption. Non-null only across that single
         * createThreadLocalInstance-then-ASTORE pair.
         */
        @Nullable java.util.Random pendingRandomSource;

        /**
         * Length captured by a {@code NEWARRAY <T>} instruction that hasn't yet been bound
         * to a slot via the subsequent {@code ASTORE}. Reset on consumption. Non-null only
         * across a single {@code NEWARRAY -> ASTORE} pair.
         */
        @Nullable Integer pendingFreshArrayLength;

        /**
         * Element-type tag for the pending {@code NEWARRAY} - {@code 'F'} for float,
         * {@code 'I'} for int, {@code '\0'} when no pending. Mirrors {@code IntInsnNode
         * .operand} via {@code T_FLOAT} / {@code T_INT}.
         */
        char pendingFreshArrayType;

        /**
         * Flattened pivot + scale for each flushed bone, used to resolve child inheritance.
         */
        final @NotNull ConcurrentMap<String, BoneMeta> boneMeta = Concurrent.newMap();

        /**
         * Child-bone -> resolved-parent-bone, populated by {@link #flushPendingBone}. Walks
         * with {@link #retainedNames} to decide which bones to keep after a
         * {@code PartDefinition.retainPartsAndChildren} call. Root-level bones (children of
         * the mesh root, no PartDefinition parent) map to {@code null}.
         */
        final @NotNull Map<String, String> boneParents = new LinkedHashMap<>();

        /**
         * Bone names captured from a {@code Set.of(...)} call immediately preceding a
         * {@link #retainedNames}-bound {@code retainPartsAndChildren} dispatch. Walked back
         * from the {@code Set.of} {@code MethodInsnNode} since the parser doesn't carry a
         * reference stack. Null when no pending capture is active.
         */
        @Nullable Set<String> pendingRetainSet;

        /**
         * Bones to keep when filtering after the parse. Populated by
         * {@code PartDefinition.retainPartsAndChildren(Set)} from {@link #pendingRetainSet}.
         * After {@link #walkInstructions} returns, every emitted bone whose ancestor chain
         * (self -> root) contains no name in this set is dropped from {@link #bones}. Null
         * means no filter was applied. Vanilla's {@code retainPartsAndChildren} replaces a
         * non-retained bone's cubes with empty (recursing into its children); since
         * {@link #flushPendingBone} skips JSON emission for cube-less bones, "strip cubes"
         * and "drop bone from JSON" produce the same render output. Only set when
         * {@code EVALUATING} so legacy walker parses are unaffected.
         */
        @Nullable Set<String> retainedNames;

        /**
         * Bone names removed via {@code PartDefinition.clearChild(String)} after the bone
         * was already flushed (vanilla pattern: build a sub-tree via a shared helper, then
         * post-prune unwanted children). Canonical case: {@code AdultPiglinModel.createBodyLayer}
         * inherits a "hat" bone via {@code PlayerModel.createMesh}, then calls
         * {@code head.clearChild("hat")} to drop it. Applied in
         * {@link #applyClearedBonesFilter} after the walk, which also drops descendants
         * (since {@code clearChild} cascades through the child's sub-tree in vanilla).
         * Only populated when {@code EVALUATING}.
         */
        final @NotNull Set<String> clearedBones = new LinkedHashSet<>();

        /**
         * Cubes accumulated for the current builder chain, flushed by the next {@code addOrReplaceChild}.
         */
        @NotNull ConcurrentList<float[]> pendingCubes = Concurrent.newList();

        /**
         * Current {@code texOffs(u, v)} values used by subsequent {@code addBox} variants that omit inline UV.
         */
        int @NotNull [] pendingUv = { 0, 0 };

        /**
         * Current {@code PartPose} pivot for the next bone flush; defaults to origin.
         */
        float @NotNull [] pendingPivot = { 0, 0, 0 };

        /**
         * Current {@code PartPose} rotation (Euler degrees) for the next bone flush.
         */
        float @NotNull [] pendingRotation = { 0, 0, 0 };

        /**
         * Uniform scale from {@code PartPose.scaled}; {@code 1f} when no scale was applied.
         */
        float pendingScale = 1f;

        /**
         * Whole-layer uniform scale captured from {@code MeshTransformer.scaling(F)} call(s)
         * on the {@code LayerDefinition}; {@code 1f} when no MeshTransformer is applied.
         * Re-walked into the emitted bone tree by {@link #applyMeshTransformerScaling} after
         * {@link #walkInstructions} returns. Multiplies on subsequent calls so
         * {@code .apply(scaling(a)).apply(scaling(b))} composes as {@code a * b}.
         */
        float meshTransformerScale = 1f;

        /**
         * The 3-component inflate captured from the most recent {@code new CubeDeformation}
         * or {@code .extend} call; consumed by the next {@code addBox} variant and reset to
         * {@link #defaultInflate} after the cube emits. Asymmetric {@code (FFF)} variants
         * keep their components (decision 24 - the legacy lossy /3 average dies; the bridge
         * re-averages {@code (x + y + z) / 3f} on the emitted floats for legacy parity).
         */
        float @NotNull [] pendingInflate = { 0f, 0f, 0f };

        /**
         * Current builder-level mirror flag set by {@code CubeListBuilder.mirror(true)} -
         * applies to every subsequent {@code addBox} cube until the builder hits a
         * {@code mirror(false)} call or the chain ends. Captured on the
         * {@code CubeListBuilder.mirror(Z)} dispatch (the boolean is popped into this slot
         * instead of being discarded). The {@code addBox(..., Z, ...)} mirror-flagged
         * variants override this on a per-cube basis. Vanilla's
         * {@code AbstractEquineModel.createBodyMesh} flips {@code mirror=true} for the
         * right-side legs / right ear so the leg's outer-face UV draws the same texture
         * region as the left leg's outer face rather than its mirror; without propagating
         * this through to the kit, both right legs render facing the wrong way (the
         * skeleton-horse user report). The kit already consumes
         * {@link EntityModelData.Cube#isMirror()} via {@code rect.toUvCorners(..., mirror)}.
         */
        boolean pendingMirror = false;

        /**
         * The factory's default {@code CubeDeformation} inflate, captured at the call site
         * in {@link EntityLayerDefinitionResolver} (e.g. {@code 0.25} for
         * {@code DROWNED_OUTER_LAYER}'s {@code DrownedModel.createBodyLayer(new
         * CubeDeformation(0.25F))}). {@link #pendingInflate} resets to this value after every
         * {@code emitCube} so all cubes in the factory pick up the call-site-provided inflate
         * by default, while inline {@code new CubeDeformation(F)} / {@code .extend(F)}
         * per-cube overrides still take precedence on the next addBox. Zero for normal entity
         * sources whose factory takes no {@code CubeDeformation} arg.
         */
        float @NotNull [] defaultInflate = { 0f, 0f, 0f };

        /**
         * JVM local-variable slot -> CubeDeformation inflate value, populated when a
         * {@code new CubeDeformation(F); <init>} is immediately followed by {@code astore N}
         * and the slot is then re-loaded later via {@code aload N} before a subsequent
         * {@code addBox(..., CubeDeformation)} call. Vanilla's
         * {@code AdultBeeModel.createBodyLayer} stores a {@code CubeDeformation(0.001F)} into
         * a local slot once and reuses it for BOTH wing addBox calls; without slot tracking,
         * the second addBox emits {@code inflate=0} because {@link #pendingInflate} resets
         * to {@link #defaultInflate} after each {@code emitCube}. Re-hydrated by the ALOAD
         * handler so the next addBox picks up the correct inflate. Only populated when
         * {@code EVALUATING}.
         */
        final @NotNull java.util.Map<Integer, float[]> cubeDeformationSlots = new java.util.HashMap<>();

        /**
         * Inflate value of the most recent {@code new CubeDeformation(F); <init>(F)} that
         * hasn't yet been ASTORE'd or consumed by an inline addBox. ASTORE captures into
         * {@link #cubeDeformationSlots}; emitCube clears it. Null when no fresh deformation
         * is pending.
         */
        float @Nullable [] pendingFreshDeformationInflate;

        /**
         * Accumulated per-bone JSON objects keyed by bone name. Written to the final model.
         */
        final @NotNull JsonObject bones = new JsonObject();

        /**
         * Texture width extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64.
         */
        int texWidth = 64;

        /**
         * Texture height extracted from {@code LayerDefinition.create(mesh, W, H)}; defaults to 64.
         */
        int texHeight = 64;

        /**
         * The top-level request whose bytecode is being parsed. Used to tag diagnostics.
         */
        @Nullable GeometryRequest currentSource;

        /**
         * Diagnostics sink for strict-mode surfacing of silent failures.
         */
        @Nullable Diagnostics diagnostics;

        /**
         * Set after the first overflow warn so a single parse doesn't spam the log.
         */
        boolean overflowWarned;

    }

    /**
     * Parent lookup data: the bone's cumulative (ancestor-folded) uniform scale. A child reads its
     * parent's cumulative scale to scale its own local pivot and to accumulate its own scale; the
     * kit composes each ancestor's rotation + translation at render, so no world pivot/rotation is
     * folded here.
     *
     * @param scale the bone's cumulative uniform scale
     */
    private record BoneMeta(float scale) {}

    /**
     * Builds the JSON object for one bone from its pivot, rotation, scale, cube list, and
     * optional parent link, in the v2 dialect (SPINE 4): {@code grow} is a scalar when
     * uniform and {@code [x, y, z]} when asymmetric, omitted at zero; {@code mirror} is
     * omitted at false; {@code face_uv} is deleted [X1]. A {@code scale} of exactly
     * {@code 1f} and a {@code null} {@code parent} are omitted. The bone schema is otherwise
     * unchanged (parent-local pivot, degrees, cumulative scale).
     * <p>
     * {@code pivot} / {@code rotation} are parent-local and {@code parent} names the owning bone
     * (or {@code null} for a root bone); the kit composes the ancestor chain at render.
     *
     * @param pivot the bone's parent-local pivot {@code [x, y, z]}
     * @param rotation the bone's parent-local rotation in degrees {@code [pitch, yaw, roll]}
     * @param scale the bone's cumulative uniform scale
     * @param cubes the accumulated cubes, each {@code [x, y, z, w, h, d, u, v, gx, gy, gz, mirror]}
     * @param parent the parent bone name, or {@code null} for a root bone
     * @return the bone JSON object
     */
    private static @NotNull JsonObject buildBone(float @NotNull [] pivot, float @NotNull [] rotation, float scale, @NotNull ConcurrentList<float[]> cubes, @Nullable String parent) {
        JsonObject bone = new JsonObject();
        bone.add("pivot", floatArray(pivot));
        bone.add("rotation", floatArray(rotation));
        if (scale != 1f)
            bone.addProperty("scale", scale);
        if (parent != null)
            bone.addProperty("parent", parent);

        JsonArray cubeArray = new JsonArray();
        for (float[] c : cubes) {
            JsonObject cube = new JsonObject();
            cube.add("origin", floatArray(c[0], c[1], c[2]));
            cube.add("size", floatArray(c[3], c[4], c[5]));

            JsonArray uv = new JsonArray();
            uv.add((int) c[6]);
            uv.add((int) c[7]);
            cube.add("uv", uv);

            // v2 emission (SPINE 4): grow scalar-or-[x,y,z] omitted at zero; mirror omitted
            // at false; face_uv deleted [X1]. Every emitCube array is 12 slots; the length
            // guard keeps a defensive zero default should a snapshot path shrink one.
            float gx = c.length >= 12 ? c[8] : 0f;
            float gy = c.length >= 12 ? c[9] : 0f;
            float gz = c.length >= 12 ? c[10] : 0f;
            if (gx != 0f || gy != 0f || gz != 0f) {
                if (gx == gy && gy == gz) cube.addProperty("grow", gx);
                else cube.add("grow", floatArray(gx, gy, gz));
            }
            if (c.length >= 12 && c[11] != 0f) cube.addProperty("mirror", true);
            cubeArray.add(cube);
        }
        bone.add("cubes", cubeArray);
        return bone;
    }

    /**
     * Builds a {@link JsonArray} from a variadic float list.
     *
     * @param values the floats to wrap
     * @return a JSON array holding {@code values} in order
     */
    private static @NotNull JsonArray floatArray(float @NotNull ... values) {
        JsonArray arr = new JsonArray();
        for (float v : values) arr.add(v);
        return arr;
    }

    /**
     * Returns whether {@code target} occurs after {@code source} in {@code instructions}.
     * The walker follows forward jumps to skip the not-taken branch of an if/else; backward
     * jumps (loop tails) would loop the linear walker forever, so this guard returns
     * {@code false} for them and the caller falls through linearly. {@code InsnList.indexOf}
     * caches indices after first call, so the lookup is amortised O(1) per method.
     *
     * @param instructions the enclosing instruction list
     * @param source the jump / switch instruction
     * @param target the candidate branch target, or {@code null}
     * @return {@code true} when {@code target} is non-null and lies after {@code source}
     */
    private static boolean isForwardJump(@NotNull InsnList instructions, @NotNull AbstractInsnNode source, @Nullable LabelNode target) {
        return target != null && instructions.indexOf(target) > instructions.indexOf(source);
    }

    /**
     * Pops an int from whichever stack the current parser config feeds branch evaluators.
     * When {@code EVALUATING} (Java pipeline) ints flow through {@code numStack}
     * so the call-site-propagated literal can also feed {@code IADD/ISUB/...} arithmetic;
     * when {@code LEGACY_LINEAR} (legacy literal-stack walkers) the legacy
     * branchStack consumer is preserved. Returns {@code null} when neither stack has a
     * value, signalling the caller to fall through linearly.
     *
     * @param state the parse state whose {@code numStack} / {@code branchStack} is popped
     * @return the popped int, or {@code null} when no branch value is available
     */
    private static @Nullable Integer popIntForBranch(@NotNull ParseState state) {
        if (state.mode == Mode.EVALUATING && !state.numStack.isEmpty())
            return state.numStack.popNumber().intValue();
        if (!state.branchStack.isEmpty())
            return state.branchStack.removeLast();
        return null;
    }

    /**
     * Returns a {@code paramIntValues}-compatible {@code int[]} of at least {@code slot+1}
     * entries, allocating a new array (and copying existing values) when {@code current}
     * is {@code null} or too small. Used by the for-loop unroller in
     * {@link #handleInstruction} to inject the iterator's per-iteration value into a slot
     * that might not have been pre-sized by the {@link Source}'s {@code paramIntValues}
     * (top-level Java entity sources don't set {@code paramIntValues} at all, so the slot
     * is unallocated until the first loop fires).
     *
     * @param current the existing {@code paramIntValues} array, or {@code null}
     * @param slot the slot index that must be addressable
     * @return {@code current} when already large enough, else a new array with {@code current}
     *     copied in
     */
    private static int @NotNull [] ensureIntSlotCapacity(int @Nullable [] current, int slot) {
        if (current != null && slot < current.length)
            return current;
        int currentLength = current == null ? 0 : current.length;
        int newLength = Math.max(slot + 1, Math.max(currentLength * 2, 16));
        int[] resized = new int[newLength];
        if (current != null)
            System.arraycopy(current, 0, resized, 0, currentLength);
        return resized;
    }

    /**
     * Folds a static-array index expression at an {@code IALOAD} / {@code FALOAD} site by
     * walking back over preceding real instructions to detect the canonical javac shapes:
     * <ul>
     *   <li>{@code GETSTATIC <[[I>; ILOAD slot; AALOAD; <int literal>; IALOAD} -
     *       silverfish / endermite's {@code BODY_SIZES[i][j]} / {@code BODY_TEXS[i][j]}</li>
     *   <li>{@code GETSTATIC <[I>; ILOAD slot; IALOAD} - 1D int array lookup</li>
     *   <li>{@code GETSTATIC <[F>; ILOAD slot; FALOAD} - guardian's {@code SPIKE_*[i]} +
     *       {@code SPIKE_*_ROT[i]}</li>
     * </ul>
     * Returns the resolved literal value when all three pieces match AND the row slot
     * resolves to a literal via {@link ParseState#numericLocals} or
     * {@link ParseState#paramIntValues}. Returns {@code null} otherwise (caller falls back
     * to the non-literal marker).
     *
     * <p>The {@code <clinit>} initializer is walked once per (owner, field) pair via the
     * parser-private static-array readers below (re-homed from the legacy kit - the parser
     * is their only consumer, so they live here as sanctioned private helpers; the
     * ClassNode itself is cached by the session {@link ClassNodeCache}, so each fold is one
     * small linear walk per array access, negligible against the surrounding parser cost).
     *
     * @param loadNode the {@code IALOAD} / {@code FALOAD} instruction to fold
     * @param state the parse state supplying tracked local arrays and slot values
     * @param cache the session's jar cache
     * @return the resolved literal cell value, or {@code null} when the shape doesn't match
     *     or any piece is non-literal
     */
    private static @Nullable Number tryFoldStaticArrayRead(
        @NotNull AbstractInsnNode loadNode,
        @NotNull ParseState state,
        @NotNull ClassNodeCache cache
    ) {
        int loadOp = loadNode.getOpcode();
        AbstractInsnNode prev1 = AsmKit.previousReal(loadNode);
        if (prev1 == null) return null;

        if (loadOp == Opcodes.IALOAD) {
            // 2D shape: <field>:[[I; <row expr>; AALOAD; <int lit col>; IALOAD
            Integer colIdx = AsmKit.readIntLiteral(prev1);
            if (colIdx != null) {
                AbstractInsnNode aaload = AsmKit.previousReal(prev1);
                if (aaload != null && aaload.getOpcode() == Opcodes.AALOAD) {
                    AbstractInsnNode beforeAaload = AsmKit.previousReal(aaload);
                    RowResolution rr = resolveRowExpression(beforeAaload, state);
                    if (rr != null) {
                        AbstractInsnNode getstaticNode = AsmKit.previousReal(rr.startNode());
                        if (getstaticNode instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && "[[I".equals(field.desc)) {
                            int[][] arr = readStaticIntArray2D(cache, field.owner, field.name);
                            if (arr != null && rr.value() >= 0 && rr.value() < arr.length
                                && arr[rr.value()] != null && colIdx >= 0 && colIdx < arr[rr.value()].length) {
                                return arr[rr.value()][colIdx];
                            }
                        }
                    }
                }
            }
            // 1D shape: <field>:[I; <row expr>; IALOAD
            RowResolution rr1d = resolveRowExpression(prev1, state);
            if (rr1d != null) {
                AbstractInsnNode getstaticNode = AsmKit.previousReal(rr1d.startNode());
                if (getstaticNode instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "[I".equals(field.desc)) {
                    int[] arr = readStaticIntArray1D(cache, field.owner, field.name);
                    if (arr != null && rr1d.value() >= 0 && rr1d.value() < arr.length) {
                        return arr[rr1d.value()];
                    }
                }
            }
        }
        if (loadOp == Opcodes.FALOAD) {
            // 1D static shape: <field>:[F; <row expr>; FALOAD
            RowResolution rr = resolveRowExpression(prev1, state);
            if (rr != null) {
                AbstractInsnNode getstaticNode = AsmKit.previousReal(rr.startNode());
                if (getstaticNode instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && "[F".equals(field.desc)) {
                    float[] arr = readStaticFloatArray1D(cache, field.owner, field.name);
                    if (arr != null && rr.value() >= 0 && rr.value() < arr.length) {
                        return arr[rr.value()];
                    }
                }
            }
            // 1D local shape: ALOAD <slot>; <row expr>; FALOAD - the slot must hold a
            // tracked float[] populated by an earlier NEWARRAY + ASTORE + (FASTORE)*
            // sequence. Silverfish's post-loop layer bones use
            // {@code aload_2; iconst_<idx>; faload} to read its cumulative-pivot cache.
            if (rr != null) {
                AbstractInsnNode aloadNode = AsmKit.previousReal(rr.startNode());
                if (aloadNode instanceof VarInsnNode aload && aload.getOpcode() == Opcodes.ALOAD) {
                    float[] arr = state.localFloatArrays.get(aload.var);
                    if (arr != null && rr.value() >= 0 && rr.value() < arr.length) {
                        return arr[rr.value()];
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolution result for a row-index expression preceding an {@code AALOAD} or
     * {@code [IF]ALOAD}: the literal index value and the first real instruction in the
     * expression. The caller scans backward from {@code startNode().getPrevious()} to find
     * the {@code GETSTATIC} of the array field.
     *
     * @param value the resolved literal row index
     * @param startNode the first real instruction of the row-index expression
     */
    private record RowResolution(int value, @NotNull AbstractInsnNode startNode) {}

    /**
     * Resolves a row-index expression at {@code endNode}, returning the literal value and
     * the expression's starting instruction. Supports:
     * <ul>
     *   <li>{@code ILOAD slot} - the simple case, when {@link #resolveSlotInt} can resolve
     *       the slot via {@link ParseState#numericLocals} or
     *       {@link ParseState#paramIntValues}.</li>
     *   <li>{@code ILOAD slot; <int lit>; IADD} / {@code ISUB} - silverfish / endermite
     *       update {@code f += sizes[i][2] + sizes[i+1][2]} which compiles to
     *       {@code iload <slot>; iconst_1; iadd; aaload}.</li>
     * </ul>
     * Returns {@code null} when the expression doesn't match a supported shape or any
     * piece is non-literal.
     *
     * @param endNode the last instruction of the row-index expression, or {@code null}
     * @param state the parse state supplying slot values via {@link #resolveSlotInt}
     * @return the resolved literal row index and expression-start node, or {@code null} on
     *     an unsupported / non-literal shape
     */
    private static @Nullable RowResolution resolveRowExpression(
        @Nullable AbstractInsnNode endNode,
        @NotNull ParseState state
    ) {
        if (endNode == null) return null;
        // Literal int row: silverfish's post-loop layer bones use ICONST_2 / ICONST_4 /
        // ICONST_1 as direct row indices into BODY_SIZES.
        Integer literalRow = AsmKit.readIntLiteral(endNode);
        if (literalRow != null) {
            return new RowResolution(literalRow, endNode);
        }
        if (endNode instanceof VarInsnNode iload && iload.getOpcode() == Opcodes.ILOAD) {
            Integer v = resolveSlotInt(state, iload.var);
            if (v != null) return new RowResolution(v, iload);
        }
        if (endNode.getOpcode() == Opcodes.IADD || endNode.getOpcode() == Opcodes.ISUB) {
            AbstractInsnNode rhs = AsmKit.previousReal(endNode);
            AbstractInsnNode lhs = rhs == null ? null : AsmKit.previousReal(rhs);
            if (rhs != null && lhs instanceof VarInsnNode iload && iload.getOpcode() == Opcodes.ILOAD) {
                Integer rhsLit = AsmKit.readIntLiteral(rhs);
                Integer base = resolveSlotInt(state, iload.var);
                if (rhsLit != null && base != null) {
                    int v = endNode.getOpcode() == Opcodes.IADD ? base + rhsLit : base - rhsLit;
                    return new RowResolution(v, iload);
                }
            }
        }
        return null;
    }

    /**
     * Returns the resolved int for a JVM local slot - first checking
     * {@link ParseState#numericLocals} (where in-body {@code ISTORE}s are captured), then
     * {@link ParseState#paramIntValues} (where the for-loop unroller injects the iterator
     * value and {@code captureInlineParams} injects call-site literals). Returns
     * {@code null} when neither holds a value, signalling the static-array fold to fall
     * through.
     *
     * @param state the parse state holding the local / param slot tables
     * @param slot the JVM local-variable slot to resolve
     * @return the resolved int, or {@code null} when neither table holds a value
     */
    private static @Nullable Integer resolveSlotInt(@NotNull ParseState state, int slot) {
        Number local = state.numericLocals.get(slot);
        if (local != null) return local.intValue();
        if (state.paramIntValues != null && slot >= 0 && slot < state.paramIntValues.length) {
            return state.paramIntValues[slot];
        }
        return null;
    }

    /**
     * Decodes an int, float, or double literal from the instruction, returning the boxed
     * numeric value or {@code null} when the node is not a compile-time numeric push. The
     * geometry walker tracks these on a single {@code Number}-typed stack so a downstream
     * {@code addBox(FFFFFF)} can pop floats from the same list that earlier collected ints
     * for an {@code addBox(name,FFFIIIII)} variant. Doubles ({@code DCONST_0/1},
     * {@code LDC2_W}) feed the {@code Mth.cos(D)F} / {@code Mth.sin(D)F} dispatch in
     * {@link #handleMethodInsn} so vanilla's inline trig in {@code createBodyLayer} (e.g.
     * {@code WitherBossModel}'s tail offset {@code -2 + cos(0.2042) * 10}) folds at parse time.
     *
     * @param node the instruction to decode
     * @return the boxed int / float / double literal, or {@code null} when {@code node} is not
     *     a compile-time numeric push
     */
    private static @Nullable Number readNumericLiteral(@NotNull AbstractInsnNode node) {
        Integer asInt = AsmKit.readIntLiteral(node);
        if (asInt != null) return asInt;
        Float asFloat = AsmKit.readFloatLiteral(node);
        if (asFloat != null) return asFloat;
        return AsmKit.readDoubleLiteral(node);
    }

    /**
     * Builder-dispatch int pop. Routes through {@link AsmKit.LiteralStack#popIntOrZero}
     * so the non-literal sentinel (pushed by {@link AsmKit.LiteralStack#pushNonLiteral})
     * fires the canonical "non-literal argument consumed" WARN tagged with the entity id.
     * Empty stack is silent zero - matches the upstream "accounting boundary" convention.
     *
     * @param state the parse state whose {@code numStack} is popped
     * @param where a human-readable dispatch-site label for the diagnostic
     * @return the popped int, or {@code 0} on empty stack / non-literal top
     */
    private static int popIntWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
        if (state.diagnostics == null || state.currentSource == null) {
            // No diagnostic sink attached - fall back to the silent-coerce path so legacy
            // callers (single-source parsing without a Diagnostics) don't NPE.
            Number top = state.numStack.popNumber();
            return top == null ? 0 : top.intValue();
        }
        return state.numStack.popIntOrZero(state.diagnostics, state.currentSource.subjectId(), where);
    }

    /**
     * Float-typed counterpart of {@link #popIntWithDiagnostics}.
     *
     * @param state the parse state whose {@code numStack} is popped
     * @param where a human-readable dispatch-site label for the diagnostic
     * @return the popped float, or {@code 0f} on empty stack / non-literal top
     */
    private static float popFloatWithDiagnostics(@NotNull ParseState state, @NotNull String where) {
        if (state.diagnostics == null || state.currentSource == null) {
            Number top = state.numStack.popNumber();
            return top == null ? 0f : top.floatValue();
        }
        return state.numStack.popFloatOrZero(state.diagnostics, state.currentSource.subjectId(), where);
    }


    // ----------------------------------------------------------------------------------------
    // Static array initializer readers - re-homed from the legacy kit as parser-private
    // helpers (the parser is their only consumer; SPINE 5.2 deleted them from AsmKit)
    // ----------------------------------------------------------------------------------------

    /**
     * Reads a {@code static final int[]} field's initializer values by walking the owning
     * class's {@code <clinit>} for the canonical javac pattern
     * ({@code <length>; NEWARRAY int; (DUP; <idx>; <value>; IASTORE)*; PUTSTATIC}).
     * Returns the populated {@code int[]} or {@code null} when the class is missing, the
     * field isn't found, or the initializer doesn't match the canonical shape.
     */
    private static int @Nullable [] readStaticIntArray1D(@NotNull ClassNodeCache cache, @NotNull String ownerInternalName, @NotNull String fieldName) {
        ClassNode owner = cache.load(ownerInternalName);
        if (owner == null) return null;
        MethodNode clinit = AsmKit.findMethod(owner, AsmKit.CLINIT, "()V");
        if (clinit == null) return null;
        return findIntArray1DInitializer(clinit, fieldName);
    }

    /**
     * Reads a {@code static final int[][]} field's initializer values - the 2D shape of
     * {@link #readStaticIntArray1D} ({@code ANEWARRAY [I} outer + per-row inner packs).
     */
    private static int @Nullable [] @Nullable [] readStaticIntArray2D(@NotNull ClassNodeCache cache, @NotNull String ownerInternalName, @NotNull String fieldName) {
        ClassNode owner = cache.load(ownerInternalName);
        if (owner == null) return null;
        MethodNode clinit = AsmKit.findMethod(owner, AsmKit.CLINIT, "()V");
        if (clinit == null) return null;
        return findIntArray2DInitializer(clinit, fieldName);
    }

    /**
     * Reads a {@code static final float[]} field's initializer values - same shape as
     * {@link #readStaticIntArray1D} but with {@code NEWARRAY float} + {@code FASTORE}.
     */
    private static float @Nullable [] readStaticFloatArray1D(@NotNull ClassNodeCache cache, @NotNull String ownerInternalName, @NotNull String fieldName) {
        ClassNode owner = cache.load(ownerInternalName);
        if (owner == null) return null;
        MethodNode clinit = AsmKit.findMethod(owner, AsmKit.CLINIT, "()V");
        if (clinit == null) return null;
        return findFloatArray1DInitializer(clinit, fieldName);
    }

    /**
     * Walks {@code clinit} for the first {@code PUTSTATIC <fieldName>:<desc>} and returns it.
     * Returns {@code null} when no matching PUTSTATIC exists.
     */
    private static @Nullable FieldInsnNode findPutstatic(@NotNull MethodNode clinit, @NotNull String fieldName, @NotNull String desc) {
        for (AbstractInsnNode node = clinit.instructions.getFirst(); node != null; node = node.getNext()) {
            if (!(node instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) continue;
            if (fieldName.equals(put.name) && desc.equals(put.desc)) return put;
        }
        return null;
    }

    /**
     * A {@code NEWARRAY} instruction paired with the array length its preceding literal push
     * declared.
     *
     * @param newArrayNode the {@code NEWARRAY} instruction node
     * @param length the array length read from the literal immediately before {@code newArrayNode}
     */
    private record PrimitiveNewArray(@NotNull AbstractInsnNode newArrayNode, int length) {}

    /**
     * Walks backward from {@code putstatic} for the first {@code NEWARRAY <newArrayOperand>}
     * instruction and returns it paired with the preceding length literal. Returns
     * {@code null} when no matching NEWARRAY is found, the length is missing, or the length
     * is non-literal / negative.
     */
    private static @Nullable PrimitiveNewArray findPrimitiveNewArrayBefore(@NotNull FieldInsnNode putstatic, int newArrayOperand) {
        for (AbstractInsnNode cursor = putstatic.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            if (AsmKit.isPseudoNode(cursor)) continue;
            if (cursor instanceof IntInsnNode intInsn && intInsn.getOpcode() == Opcodes.NEWARRAY && intInsn.operand == newArrayOperand) {
                AbstractInsnNode lenNode = AsmKit.previousReal(cursor);
                if (lenNode == null) return null;
                Integer length = AsmKit.readIntLiteral(lenNode);
                if (length == null || length < 0) return null;
                return new PrimitiveNewArray(cursor, length);
            }
        }
        return null;
    }

    /**
     * Per-entry callback for {@link #walkDupStoreEntries}. Returns {@code false} to abort the
     * walk (signals a malformed or non-literal value at this entry).
     */
    @FunctionalInterface
    private interface DupStoreEntryHandler {

        /**
         * Handles one {@code (DUP; idx; value; storeOpcode)} entry.
         *
         * @param idx the array index this entry writes to (already bounds-checked against the
         *     declared length)
         * @param valueNode the instruction that pushes the value being stored
         * @return {@code true} to continue the walk, {@code false} to abort (non-literal / malformed value)
         */
        boolean handleEntry(int idx, @NotNull AbstractInsnNode valueNode);
    }

    /**
     * Walks the canonical {@code (DUP; <idx>; <value>; storeOpcode)*} entry sequence between
     * {@code newArrayNode} and {@code stopAt}, invoking {@code handler} per matched entry.
     * Returns {@code true} when every encountered DUP-rooted tuple matches the expected shape
     * and the cursor lands exactly at {@code stopAt}; {@code false} on any malformed tuple.
     *
     * <p>When {@code skipUnknownNodes} is {@code true}, non-DUP nodes between entries are
     * silently skipped (1D usage). When {@code false}, any non-DUP node aborts the walk (the
     * 2D inner loop's stricter pack).
     */
    private static boolean walkDupStoreEntries(
        @NotNull AbstractInsnNode newArrayNode,
        @NotNull AbstractInsnNode stopAt,
        int storeOpcode,
        int length,
        boolean skipUnknownNodes,
        @NotNull DupStoreEntryHandler handler
    ) {
        AbstractInsnNode cursor = AsmKit.nextReal(newArrayNode);
        while (cursor != null && cursor != stopAt) {
            if (cursor.getOpcode() != Opcodes.DUP) {
                if (!skipUnknownNodes) return false;
                cursor = AsmKit.nextReal(cursor);
                continue;
            }
            AbstractInsnNode idxNode = AsmKit.nextReal(cursor);
            AbstractInsnNode valueNode = AsmKit.nextReal(idxNode);
            AbstractInsnNode storeNode = AsmKit.nextReal(valueNode);
            if (idxNode == null || valueNode == null || storeNode == null) return false;
            if (storeNode.getOpcode() != storeOpcode) return false;
            Integer idx = AsmKit.readIntLiteral(idxNode);
            if (idx == null || idx < 0 || idx >= length) return false;
            if (!handler.handleEntry(idx, valueNode)) return false;
            cursor = AsmKit.nextReal(storeNode);
        }
        return cursor == stopAt;
    }

    /**
     * Walks {@code clinit} for a {@code PUTSTATIC <fieldName>:[I} matching the canonical
     * literal-int-array initializer. Returns the values when the shape matches; {@code null}
     * otherwise.
     */
    private static int @Nullable [] findIntArray1DInitializer(@NotNull MethodNode clinit, @NotNull String fieldName) {
        FieldInsnNode put = findPutstatic(clinit, fieldName, "[I");
        if (put == null) return null;
        PrimitiveNewArray loc = findPrimitiveNewArrayBefore(put, Opcodes.T_INT);
        if (loc == null) return null;
        int[] out = new int[loc.length()];
        boolean ok = walkDupStoreEntries(loc.newArrayNode(), put, Opcodes.IASTORE, loc.length(), true, (idx, valueNode) -> {
            Integer value = AsmKit.readIntLiteral(valueNode);
            if (value == null) return false;
            out[idx] = value;
            return true;
        });
        return ok ? out : null;
    }

    /**
     * Walks {@code clinit} for a {@code PUTSTATIC <fieldName>:[[I} matching the canonical
     * literal-int-2D-array initializer. Returns the values when the shape matches;
     * {@code null} otherwise.
     */
    private static int @Nullable [] @Nullable [] findIntArray2DInitializer(@NotNull MethodNode clinit, @NotNull String fieldName) {
        FieldInsnNode put = findPutstatic(clinit, fieldName, "[[I");
        if (put == null) return null;
        AbstractInsnNode aNewArrayNode = null;
        AbstractInsnNode lengthNode = null;
        for (AbstractInsnNode cursor = put.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            if (AsmKit.isPseudoNode(cursor)) continue;
            if (cursor instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.ANEWARRAY && "[I".equals(typeInsn.desc)) {
                aNewArrayNode = cursor;
                lengthNode = AsmKit.previousReal(cursor);
                break;
            }
        }
        if (aNewArrayNode == null || lengthNode == null) return null;
        Integer outerLength = AsmKit.readIntLiteral(lengthNode);
        if (outerLength == null || outerLength < 0) return null;
        int[][] out = new int[outerLength][];
        AbstractInsnNode cursor = AsmKit.nextReal(aNewArrayNode);
        while (cursor != null && cursor != put) {
            if (cursor.getOpcode() != Opcodes.DUP) {
                cursor = AsmKit.nextReal(cursor);
                continue;
            }
            // DUP; <row>; <inner-len>; NEWARRAY int; ... AASTORE
            AbstractInsnNode rowNode = AsmKit.nextReal(cursor);
            AbstractInsnNode innerLenNode = AsmKit.nextReal(rowNode);
            AbstractInsnNode innerNewArrayNode = AsmKit.nextReal(innerLenNode);
            if (rowNode == null || innerLenNode == null || innerNewArrayNode == null) return null;
            Integer row = AsmKit.readIntLiteral(rowNode);
            Integer innerLen = AsmKit.readIntLiteral(innerLenNode);
            if (row == null || innerLen == null || row < 0 || row >= outerLength) return null;
            if (!(innerNewArrayNode instanceof IntInsnNode innerNewArray)
                || innerNewArray.getOpcode() != Opcodes.NEWARRAY
                || innerNewArray.operand != Opcodes.T_INT) return null;
            AbstractInsnNode aastore = findFollowingAastore(innerNewArrayNode, put);
            if (aastore == null) return null;
            int[] inner = new int[innerLen];
            boolean ok = walkDupStoreEntries(innerNewArrayNode, aastore, Opcodes.IASTORE, innerLen, false, (innerIdx, valueNode) -> {
                Integer value = AsmKit.readIntLiteral(valueNode);
                if (value == null) return false;
                inner[innerIdx] = value;
                return true;
            });
            if (!ok) return null;
            out[row] = inner;
            cursor = AsmKit.nextReal(aastore);
        }
        return out;
    }

    /**
     * Walks forward from {@code from} (exclusive) for the first {@code AASTORE} preceding
     * {@code stopAt}. Returns {@code null} when none is found before {@code stopAt}. Used by
     * the 2D walker to bracket each row's inner-entry pack.
     */
    private static @Nullable AbstractInsnNode findFollowingAastore(@NotNull AbstractInsnNode from, @NotNull AbstractInsnNode stopAt) {
        for (AbstractInsnNode cursor = AsmKit.nextReal(from); cursor != null && cursor != stopAt; cursor = AsmKit.nextReal(cursor))
            if (cursor.getOpcode() == Opcodes.AASTORE) return cursor;
        return null;
    }

    /**
     * Walks {@code clinit} for a {@code PUTSTATIC <fieldName>:[F} matching the canonical
     * literal-float-array initializer. Returns the values when the shape matches; {@code null}
     * otherwise.
     */
    private static float @Nullable [] findFloatArray1DInitializer(@NotNull MethodNode clinit, @NotNull String fieldName) {
        FieldInsnNode put = findPutstatic(clinit, fieldName, "[F");
        if (put == null) return null;
        PrimitiveNewArray loc = findPrimitiveNewArrayBefore(put, Opcodes.T_FLOAT);
        if (loc == null) return null;
        float[] out = new float[loc.length()];
        boolean ok = walkDupStoreEntries(loc.newArrayNode(), put, Opcodes.FASTORE, loc.length(), true, (idx, valueNode) -> {
            Float value = AsmKit.readFloatLiteral(valueNode);
            if (value == null) return false;
            out[idx] = value;
            return true;
        });
        return ok ? out : null;
    }

    /**
     * Bit-identical port of vanilla Minecraft's {@code net.minecraft.util.Mth.sin / Mth.cos} table
     * lookup. Used only by the {@code Mth.cos/sin} arm of the method-call handler above, when
     * unrolling a vanilla bytecode {@code INVOKESTATIC Mth.sin (D)F} / {@code Mth.cos (D)F} call so
     * the pre-baked float lands at the same bit pattern vanilla's runtime would produce.
     * Package-private (not shared) - it is a parse-time implementation detail of this walker, kept
     * out of the general util surface; its bit-parity is pinned by {@code GeometryParserTrigTest} (doc-12 K10).
     *
     * <p>Vanilla's implementation:
     * <pre>{@code
     *   private static final float[] SIN = new float[65536];
     *   static {
     *       for (int i = 0; i < 65536; i++)
     *           SIN[i] = (float) Math.sin((double) i / 10430.378350470453);  // i / (65536 / 2pi)
     *   }
     *   public static float sin(double d) {
     *       return SIN[(int) (long) (d * 10430.378350470453) & 65535];
     *   }
     *   public static float cos(double d) {
     *       return SIN[(int) (long) (d * 10430.378350470453 + 16384.0) & 65535];
     *   }
     * }</pre>
     * Every operation matches the bytecode: index math in {@code double}, conversion to
     * {@code long} via Java's narrowing convention, mask with {@code 0xFFFF} (65535), narrow to
     * {@code int}, array load. The {@code cos} offset {@code 16384.0} is a quarter rotation
     * ({@code 65536 / 4}), the table's phase shift from sine to cosine.
     *
     * <p>Why this matters: {@code Math.cos / Math.sin} are libm calls accurate to roughly machine
     * epsilon. The 65536-entry table samples sin at multiples of {@code 2pi/65536 ~= 9.587e-5 rad}
     * and rounds intermediate values to single-precision float when populating the array. The
     * table-vs-libm gap is up to ~1.8e-5 in absolute value - tiny, but multiplied by the
     * {@code * 10.0F} in WitherBossModel's tail-pivot computation it surfaces as a 0.0002-unit
     * float drift on the tail pivot Y, which is enough to shift the entity's projected screen
     * bounds across the canvas-pixel rounding boundary.
     */
    static final class FastTrig {

        /**
         * Vanilla's {@code 65536 / (2 * PI)} constant - the index-per-radian scale factor. Held as
         * the exact {@code double} literal vanilla hardcodes (not recomputed) so the multiply that
         * feeds the table index is bit-identical.
         */
        private static final double MTH_PI_RATIO = 10430.378350470453;

        /**
         * The 65536-entry sin lookup table. Element {@code i} holds
         * {@code (float) Math.sin(i / 10430.378350470453)}. Initialised eagerly so the first
         * call site does not pay table-population cost.
         */
        private static final float[] SIN = new float[65536];

        static {
            for (int i = 0; i < 65536; i++)
                SIN[i] = (float) Math.sin((double) i / MTH_PI_RATIO);
        }

        private FastTrig() {}

        /**
         * Bit-identical reproduction of vanilla {@code Mth.sin(double)}.
         *
         * @param d the angle in radians
         * @return {@code sin(d)} sampled from the 65536-entry table
         */
        static float sin(double d) {
            return SIN[(int) (long) (d * MTH_PI_RATIO) & 65535];
        }

        /**
         * Bit-identical reproduction of vanilla {@code Mth.cos(double)}.
         *
         * @param d the angle in radians
         * @return {@code cos(d)} sampled from the 65536-entry table (offset by a quarter rotation)
         */
        static float cos(double d) {
            return SIN[(int) (long) (d * MTH_PI_RATIO + 16384.0) & 65535];
        }
    }

}
