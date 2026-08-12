package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.tooling.geometry.GeometryManifest;
import lib.minecraft.renderer.tooling.geometry.GeometryRequest;
import lib.minecraft.renderer.tooling.kernel.ClassKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.ToolingException;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.policy.Navigation;
import lib.minecraft.renderer.tooling.vanilla.LayerDefinitionIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.CommitWalk;
import lib.minecraft.renderer.tooling.walk.Insn;
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
 * <p>Body-mesh membership is declared per entity (pufferfish / salmon); natural-size membership is
 * the subject deriving from the class the natural-size coordinate names, and the concrete meshes /
 * factors are derived either way. Option names come from the candidate field's suffix matched
 * against the size domain ({@code PUFFERFISH_MEDIUM} to {@code medium}); default = the option-less
 * domain member; option members emit in domain order.
 */
final class EntitySizeAxisResolver {

    /** The caller label a stale natural-size coordinate is reported under. */
    private static final @NotNull String NATURAL_SIZES = "the natural-size set";

    /** The caller label a stale size-domain coordinate is reported under. */
    private static final @NotNull String OPTION_DOMAIN = "the size-axis option domain";

    private final @NotNull ClassNodeCache cache;
    private final @NotNull List<String> sizeDomain;
    private final @NotNull EntitySubject subject;
    private final @NotNull LayerDefinitionIndex layerDefinitions;
    private final @NotNull EntityGeometryRefResolver geometryRef;
    private final @NotNull GeometryManifest manifest;
    private final @NotNull Diagnostics diagnostics;

    EntitySizeAxisResolver(@NotNull EntityContext context, @NotNull EntityGeometryRefResolver geometryRef) {
        this.cache = context.cache();
        this.sizeDomain = sizeDomain(this.cache);
        this.subject = context.subject();
        this.layerDefinitions = context.indexes().layerDefinitions();
        this.geometryRef = geometryRef;
        this.manifest = context.indexes().manifest();
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
     * @return the option domain in declaration order
     * @throws ToolingException if the coordinate binds a serialized id to no member, or to fewer
     *     members than it declares
     */
    static @NotNull List<String> sizeDomain(@NotNull ClassNodeCache cache) {
        Navigation.At coordinate = EntityAxisPolicies.sizeDomainCoordinate();
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
        Navigation.At coordinate = EntityAxisPolicies.naturalSizeCoordinate();
        if (!ClassKit.extendsClass(this.cache, this.subject.entityClass(), coordinate.owner())) return null;
        ClassNode owner = ClassKit.requireClass(this.cache, coordinate.owner(), NATURAL_SIZES);
        MethodNode spawn = ClassKit.requireMethod(owner, coordinate.member(), NATURAL_SIZES);
        Integer draws = AsmWalker.over(spawn).real()
            .latch(AsmWalker::intLiteral)
            .commitAt(Insn.of(MethodInsnNode.class, call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                && VanillaSourceClasses.Types.RANDOM_SOURCE.equals(call.owner)
                && VanillaSourceClasses.Methods.NEXT_INT.equals(call.name)))
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
        List<Integer> sizes = new ArrayList<>(draws);
        for (int draw = 0; draw < draws; draw++) sizes.add(base << draw);
        return sizes;
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
        Map<String, JsonTree> options = new LinkedHashMap<>();
        int base = naturalSizes.getFirst();
        for (int index = 1; index < naturalSizes.size(); index++)
            options.put(domain.get(index), JsonTree.object().put("scale", (float) naturalSizes.get(index) / base));
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
     */
    private @Nullable JsonTree meshForm() {
        String primaryField = this.geometryRef.primaryFieldName();
        if (this.geometryRef.resolvedEntry() == null || primaryField == null) return null;
        List<String> domain = this.sizeDomain;

        Map<String, LayerDefinitionIndex.Entry> candidates = new LinkedHashMap<>();
        for (String field : new LinkedHashSet<>(this.geometryRef.tripleSites())) {
            if (field.equals(primaryField)) continue;
            String option = field.substring(field.lastIndexOf('_') + 1).toLowerCase(Locale.ROOT);
            if (!domain.contains(option)) {
                this.diagnostics.info("extra body mesh ModelLayers.%s outside the size domain - not a size option", field);
                continue;
            }
            LayerDefinitionIndex.Entry entry = this.layerDefinitions.get(field);
            if (entry != null) candidates.put(option, entry);
        }
        if (candidates.isEmpty()) {
            this.diagnostics.warn("policy declares a size axis but no domain-suffixed body meshes resolved");
            return null;
        }

        Map<String, JsonTree> options = new LinkedHashMap<>();
        for (Map.Entry<String, LayerDefinitionIndex.Entry> candidate : candidates.entrySet()) {
            LayerDefinitionIndex.Entry entry = candidate.getValue();
            String key = this.manifest.register(GeometryRequest.shape(
                    entry.factoryClass(), entry.factoryMethod(), this.subject.entityId(),
                    entry.texWidthOverride(), entry.texHeightOverride(),
                    entry.floatParam(), entry.grow(), entry.appliedMeshTransformerScale())
                .withBabyTransform(entry.appliedBabyTransform()));
            options.put(candidate.getKey(), JsonTree.object().put("geometry", key));
        }
        this.diagnostics.info("size axis via declared membership: options %s", options.keySet());
        return sizeNode(domain, options);
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
