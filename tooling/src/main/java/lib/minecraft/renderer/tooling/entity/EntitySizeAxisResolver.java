package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.PoseFlow;
import lib.minecraft.renderer.tooling.animation.RestStrip;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.asm.Insn;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.index.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.interp.Cells;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.policy.AsmContext;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Node {@code axes.size} - the option-encoded size axis, in two forms:
 *
 * <ul>
 *   <li><b>mesh</b> - each option registers its own {@code GeometryRequest}, whether the mesh is a
 *       distinct factory (pufferfish small / medium from the renderer ctor's multiple
 *       {@code ModelLayers.PUFFERFISH_*} references, big = primary) or the primary factory under a
 *       whole-mesh transformer - a {@code MeshTransformer.scaling} factor (salmon 0.5 / 1.5 off
 *       {@code SALMON_SMALL} / {@code SALMON_LARGE}) or the aged-down rewrite the armor stand's
 *       {@code ARMOR_STAND_SMALL} is registered through. All three bake to a mesh: vanilla's
 *       {@code SalmonRenderer} likewise holds three baked {@code SalmonModel} instances and picks
 *       one, and {@code ArmorStandRenderer} holds two, so the transformed mesh is emitted as
 *       geometry the parser bakes the transformer into, not a render-time scale rider.</li>
 *   <li><b>scale</b> - the option multiplies {@code rendererScale}: slime / magma_cube 2.0 / 4.0
 *       proportional to their natural-size set (size-proportional per {@code SlimeRenderer.scale} at
 *       squish 0). These have no per-size mesh - vanilla scales the one model at render.</li>
 * </ul>
 *
 * <p>A mesh option carries the {@code toggles} its own model class gates, expanded against its own
 * mesh, because a size swaps the mesh and vanilla gates the bones of the model that draws:
 * {@code ArmorStandModel.setupAnim} gates the small stand's arms on {@code showArms} and its plate on
 * {@code showBasePlate} exactly as it gates the full-size stand's. Without them the mesh marking
 * would find the small stand's arms resting hidden with nothing to reach them and drop both. The
 * pufferfish and salmon classes gate no bone, so their options carry none.
 *
 * <p>A mesh option the renderer draws exactly while its entity is a baby carries the age it renders
 * at as the generation-only {@link PoseFlow#AGE_SCALE}: vanilla's armour stand answers
 * {@code isBaby} with {@code isSmall}, so the small stand renders at the baby's age, and the pose flow
 * files the option's sites there. The read is {@link EntityAgeAxisResolver#forwardedAge}, and the
 * option is matched by the layer it was baked from in {@link #stampAge}.
 *
 * <p>Body-mesh membership is declared per entity (pufferfish / salmon); natural-size membership is
 * the subject deriving from the class the natural-size coordinate names, and the concrete meshes /
 * factors are derived either way. Option names come from the candidate field's suffix matched
 * against the size domain ({@code PUFFERFISH_MEDIUM} to {@code medium}); default = the option-less
 * domain member; option members emit in domain order.
 */
public final class EntitySizeAxisResolver {

    /** The caller label a stale natural-size coordinate is reported under. */
    private static final @NotNull String NATURAL_SIZES = "the natural-size set";

    /** The caller label a stale size-domain coordinate is reported under. */
    private static final @NotNull String OPTION_DOMAIN = "the size-axis option domain";

    private final @NotNull ClassNodeCache cache;

    /** The frame every policy consultation here is made on - this subject, no anchor class. */
    private final @NotNull AsmContext frame;

    private final @NotNull List<String> sizeDomain;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull EntityBoneResolver bones;
    private final @NotNull Diagnostics diagnostics;

    EntitySizeAxisResolver(@NotNull EntityContext context, @NotNull EntityGeometryRefResolver geometryRef) {
        this.cache = context.cache();
        this.frame = new AsmContext(context.session(), context.subject().entityId(), null, context.diagnostics());
        this.sizeDomain = sizeDomain(this.cache, this.frame);
        this.subject = context.subject();
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.geometryRef = geometryRef;
        this.manifest = context.indexes().manifest();
        this.bones = new EntityBoneResolver(context.scope("bones"));
        this.diagnostics = context.diagnostics();
    }

    /**
     * The size-axis option domain, in the order the coordinate's enum declares its members. Each
     * member is allocated with its constant name pushed first and its serialized id second, so the
     * id is the second string its allocation pushes; a member bound by aliasing another pushes none
     * and is no part of the domain. The recovered count is held against the enum-member count the
     * same class's field table carries, which is an independent reading of the same class, so a
     * member the walk fails to read is loud rather than a silently shorter domain.
     *
     * @param cache the session's jar cache
     * @param frame the frame the policy is consulted on
     * @return the option domain in declaration order
     * @throws ToolingException if the coordinate binds a serialized id to no member, or to fewer
     *     members than it declares
     */
    static @NotNull List<String> sizeDomain(@NotNull ClassNodeCache cache, @NotNull AsmContext frame) {
        Navigation.At coordinate = EntityAxisPolicies.SIZE_DOMAIN.requireAt(frame);
        ClassNode owner = ClassKit.requireClass(cache, coordinate.owner(), OPTION_DOMAIN);
        List<String> domain = new ArrayList<>();
        // The cell holds the strings pushed since the member's NEW - position 0 the constant name,
        // position 1 the serialized id. The store hook manages its own reset: an emitting store
        // clears the cell, a store with no second string keeps it.
        Cells.ListCell<String> pushed = Cells.list();
        AsmWalker.over(ClassKit.requireMethod(owner, coordinate.member(), OPTION_DOMAIN))
            .feed(pushed)
            .on(Insn.of(TypeInsnNode.class, type -> type.getOpcode() == Opcodes.NEW
                && type.desc.equals(owner.name)), type -> pushed.clear())
            .on(Insn.of(LdcInsnNode.class, ldc -> ldc.cst instanceof String), ldc -> pushed.add((String) ldc.cst))
            .on(Insn.putStatic(owner.name), put -> {
                List<String> held = pushed.values();
                if (held.size() >= 2) {
                    domain.add(held.get(1));
                    pushed.clear();
                }
            })
            .run();
        if (domain.isEmpty())
            throw new ToolingException(
                "Class '%s' declares no member carrying a serialized id for %s - the jar is either obfuscated or from an unsupported version",
                owner.name, OPTION_DOMAIN
            );
        int declared = 0;
        for (FieldNode field : owner.fields)
            if ((field.access & Opcodes.ACC_ENUM) != 0) declared++;
        if (domain.size() != declared)
            throw new ToolingException(
                "Class '%s' declares '%s' enum members but binds a serialized id to only '%s' for %s - the jar is either obfuscated or from an unsupported version",
                owner.name, declared, domain.size(), OPTION_DOMAIN
            );
        return domain;
    }

    /**
     * The size node, or {@code null} when the entity has neither a natural-size set nor a
     * declared size-shape axis (or the declared membership derives no options).
     *
     * @return the node, or {@code null} to omit
     */
    @Nullable JsonTree resolve() {
        List<Integer> naturalSizes = naturalSizes();
        if (naturalSizes != null) return naturalSizeForm(naturalSizes);
        if (!"size".equals(EntityAxisPolicies.shapeSizeAxisFor(this.subject.entityId()))) return null;
        return meshForm();
    }

    /**
     * The natural sizes the subject spawns at, or {@code null} when it neither is nor derives from
     * the class the coordinate names. The spawn finaliser draws one value below a literal bound and
     * shifts a literal base left by it, so the set is that base shifted by each value the bound
     * admits.
     *
     * @return the ordered natural sizes, or {@code null} when the subject carries none
     * @throws ToolingException if the coordinate carries no bounded draw and no shift
     */
    private @Nullable List<Integer> naturalSizes() {
        Navigation.At coordinate = EntityAxisPolicies.NATURAL_SIZE_SET.requireAt(this.frame);
        if (!ClassKit.extendsClass(this.cache, this.subject.entityClass(), coordinate.owner())) return null;
        ClassNode owner = ClassKit.requireClass(this.cache, coordinate.owner(), NATURAL_SIZES);
        MethodNode spawn = ClassKit.requireMethod(owner, coordinate.member(), NATURAL_SIZES);
        Integer draws = AsmWalker.over(spawn).real()
            .latch(AsmWalker::intLiteral)
            .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                && SourceClasses.Types.RANDOM_SOURCE.equals(call.owner)
                && SourceClasses.Methods.NEXT_INT.equals(call.name)))
            .firstNotNull(CommitWalk.Commit::value);
        AbstractInsnNode shift = AsmWalker.over(spawn).real().first(Insn.opcode(Opcodes.ISHL));
        Integer base = shift == null
            ? null
            : AsmWalker.intLiteral(AsmWalker.previousReal(AsmWalker.previousReal(shift)));
        if (draws == null || draws < 1 || base == null)
            throw new ToolingException(
                "Method '%s.%s' carries no bounded draw shifted into a size for %s - the jar is either obfuscated or from an unsupported version",
                owner.name, spawn.name, NATURAL_SIZES
            );
        return IntStream.range(0, draws)
            .mapToObj(draw -> base << draw)
            .collect(Collectors.toList());
    }

    /**
     * Builds scale options proportional to the natural sizes (base = the first,
     * option-less), named into the size domain positionally.
     */
    private @Nullable JsonTree naturalSizeForm(@NotNull List<Integer> naturalSizes) {
        List<String> domain = this.sizeDomain;
        if (naturalSizes.size() > domain.size()) {
            this.diagnostics.warn("natural-size set %s exceeds the size domain %s - size axis omitted", naturalSizes, domain);
            return null;
        }
        int base = naturalSizes.getFirst();
        Map<String, JsonTree> options = IntStream.range(1, naturalSizes.size())
            .boxed()
            .collect(Collectors.toMap(domain::get,
                index -> JsonTree.object().put("scale", (float) naturalSizes.get(index) / base),
                (first, second) -> second, LinkedHashMap::new));
        this.diagnostics.info("size axis via natural sizes %s (scale-per-size proportional)", naturalSizes);
        return sizeNode(domain, options);
    }

    /**
     * Extra body-mesh candidates from the ctor-chain triples, each emitted as its own geometry. A
     * candidate on a distinct factory (pufferfish) registers that factory; one on the primary factory
     * under a {@code MeshTransformer.scaling} factor (salmon) registers the same factory with the
     * captured scale, which the parser bakes into the mesh exactly as vanilla bakes its
     * {@code smallSalmonModel} / {@code largeSalmonModel} - so both are a mesh swap, never a
     * render-time scale.
     *
     * <p>Each option carries the {@code toggles} the candidate's own factory class gates, expanded
     * against the option's own request: that class is the one the option's coordinate names and the
     * one that poses it, and the request is the transformed mesh the selection has to reach, so a
     * bone that mesh lacks leaves the toggle and one it keeps stays in it.
     *
     * <p>The option whose mesh renders at a baby's age carries that age as {@link PoseFlow#AGE_SCALE},
     * matched by the {@code ModelLayers} field it was baked from - see {@link #stampAge}.
     */
    private @Nullable JsonTree meshForm() {
        String primaryField = this.geometryRef.primaryFieldName();
        if (this.geometryRef.resolvedEntry() == null || primaryField == null) return null;
        List<String> domain = this.sizeDomain;

        Map<String, LayerDefinitionIndex.Entry> candidates = new LinkedHashMap<>();
        Map<String, String> layers = new LinkedHashMap<>();
        for (String field : new LinkedHashSet<>(this.geometryRef.tripleSites())) {
            if (field.equals(primaryField)) continue;
            String option = field.substring(field.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT);
            if (!domain.contains(option)) {
                this.diagnostics.info("extra body mesh ModelLayers.%s outside the size domain - not a size option", field);
                continue;
            }
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(field);
            if (entry == null) continue;
            candidates.put(option, entry);
            layers.put(option, field);
        }
        if (candidates.isEmpty()) {
            this.diagnostics.warn("policy declares a size axis but no domain-suffixed body meshes resolved");
            return null;
        }

        Map<String, JsonTree> options = new LinkedHashMap<>();
        for (Map.Entry<String, LayerDefinitionIndex.Entry> candidate : candidates.entrySet()) {
            LayerDefinitionIndex.Entry entry = candidate.getValue();
            GeometryRequest request = GeometryRequest.shape(
                    entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
                    entry.texWidthOverride(), entry.texHeightOverride(),
                    entry.floatParam(), entry.grow(), entry.appliedMeshTransformerScale())
                .withBabyTransform(entry.appliedBabyTransform());
            JsonTree option = JsonTree.object().put("geometry", this.manifest.register(request));
            // The toggles alone: the never-drawn half is the family's, which the pose flow joins onto
            // the option, and the class is the one the coordinate names, so the node names no poser.
            JsonTree gated = this.bones.resolve(entry.factoryClass(), request);
            if (gated != null) gated.findObject("toggles").ifPresent(toggles -> option.put("toggles", toggles));
            options.put(candidate.getKey(), option);
        }
        EntityAgeAxisResolver.forwardedAge(this.cache, this.subject).ifPresent(forwarded -> {
            String aged = stampAge(options, layers, forwarded, this.subject.entityId());
            this.diagnostics.info("size option '%s' renders at %s %s - %s.isBaby forwards to %s, and %s draws ModelLayers.%s on it",
                aged, PoseFlow.AGE_SCALE, forwarded.age(), ClassKit.simpleName(this.subject.entityClass()),
                forwarded.accessor(), ClassKit.simpleName(forwarded.renderer()), forwarded.layer());
        });
        this.diagnostics.info("size axis via declared membership: options %s", options.keySet());
        return sizeNode(domain, options);
    }

    /**
     * Writes the age a forwarded-age mesh renders at onto the one size option drawing it, as the
     * generation-only {@link PoseFlow#AGE_SCALE} the pose flow files the option's sites at and
     * {@link RestStrip} takes off before the table is written.
     *
     * <p>The option is matched by the {@code ModelLayers} field it was baked from, never by its name,
     * because the field is what the renderer swaps in on the forwarded flag and a name is only this
     * table's word for it. Nothing else in the option changes, so neither its mesh nor its key can.
     *
     * @param options each option's node, by option name, written in place
     * @param layers the {@code ModelLayers} field each option was baked from, by option name
     * @param forwarded the mesh the subject's renderer draws while its entity is a baby
     * @param entityId the subject, for the refusal
     * @return the option stamped
     * @throws ToolingException if no option was baked from the forwarded layer, which would leave the
     *     mesh rendering at the baby's age filed at one
     */
    static @NotNull String stampAge(
        @NotNull Map<String, JsonTree> options, @NotNull Map<String, String> layers,
        @NotNull EntityAgeAxisResolver.ForwardedAge forwarded, @NotNull String entityId) {

        String aged = layers.entrySet()
            .stream()
            .filter(baked -> baked.getValue().equals(forwarded.layer()))
            .map(Map.Entry::getKey)
            .filter(options::containsKey)
            .findFirst()
            .orElseThrow(() -> new ToolingException(
                "Entity '%s' draws 'ModelLayers.%s' at %s '%s', and no size option is baked from that layer: %s",
                entityId, forwarded.layer(), PoseFlow.AGE_SCALE, forwarded.age(), layers
            ));
        options.get(aged).put(PoseFlow.AGE_SCALE, forwarded.age());
        return aged;
    }

    /**
     * Assembles the node: the non-default delta options in size-domain order, option-less default (the
     * base mesh the family {@code geometry} already renders). The domain lives in the size-axis policy,
     * not a per-family {@code values} list.
     *
     * <p>The default is the <b>last</b> option-less member rather than the first, which matters only
     * for a family that fills fewer than two of the three: the armor stand's one option is
     * {@code small}, and the mesh its {@code geometry} already renders is the full-size one rather
     * than a middling one. Every family filling two options has exactly one member left, so the two
     * readings agree on all of them.
     */
    private static @NotNull JsonTree sizeNode(@NotNull List<String> domain, @NotNull Map<String, JsonTree> options) {
        String dflt = domain.getLast();
        for (String member : domain.reversed())
            if (!options.containsKey(member)) {
                dflt = member;
                break;
            }
        JsonTree node = JsonTree.object().put("default", dflt);
        JsonTree optionsNode = node.child("options");
        for (String member : domain) {
            JsonTree option = options.get(member);
            if (option != null) optionsNode.put(member, option);
        }
        return node;
    }

}
