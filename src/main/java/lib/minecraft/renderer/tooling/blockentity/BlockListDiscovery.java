package lib.minecraft.renderer.tooling.blockentity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.asm.AsmKit;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of the ({@link BlockListDiscovery.EntityBlockMapping}) table that maps each
 * block-entity-model id to its vanilla block variants plus any sub-model {@code parts} the
 * runtime merges at draw time. Replaces the hardcoded {@code BlockListCatalog} whose
 * {@code CANONICAL_BLOCK_LIST} table shipped literal string pairs for every block.
 *
 * <p>The discovery is dispatched per block-entity family by {@link #FAMILY_DISPATCH}. Each family
 * walks three co-ordinates out of the deobfuscated client jar:
 * <ol>
 *   <li>{@code BlockEntityType.<clinit>}'s {@code validBlocks} list for the family's BE type -
 *       the ordered list of {@code Blocks.X} {@code GETSTATIC}s between the id's {@code LDC} and
 *       the following {@code PUTSTATIC}.</li>
 *   <li>A per-family texture source - typically {@code Sheets.<clinit>} via a {@code SpriteMapper}
 *       {@code defaultNamespaceApply(LDC)}, but some families (skull, copper golem, conduit)
 *       parse their renderer's {@code <clinit>} directly because the textures live there.</li>
 *   <li>A per-family block &rarr; variant dispatch - walked by chasing the block's ctor lambda in
 *       {@code Blocks.<clinit>} and reading its {@code NEW <BlockClass>} + any
 *       {@code GETSTATIC <Enum>} args (dye colour, wood type, weather state, skull type,
 *       ...).</li>
 * </ol>
 *
 * <p>The only allowed policy constants are:
 * <ul>
 *   <li>{@link #FAMILY_DISPATCH} - one entry per block-entity family in the output.</li>
 *   <li>{@link #SKULL_TYPE_TO_ENTITY_ID} - maps the 7 {@code SkullBlock.Types} enum constants to
 *       our 4-entity-id split ({@code skull_head}, {@code skull_humanoid_head},
 *       {@code skull_dragon_head}, {@code skull_piglin_head}). This is our output convention;
 *       vanilla uses a single {@code BlockEntityType.SKULL}.</li>
 *   <li>{@link #BELL_ATTACH_SUFFIX} - maps the 4 {@code BellAttachType} enum constants to our
 *       sub-id suffix ({@code floor}, {@code ceiling}, {@code wall}, {@code between_walls}).
 *       This is our output convention - {@code SINGLE_WALL} and {@code DOUBLE_WALL} don't match
 *       their serialized names ({@code single_wall} / {@code double_wall}).</li>
 *   <li>{@link #DECORATED_POT_SIDES_OFFSET} - the sub-model offset for the pot sides part.
 *       Definitionally {@code (0, 0, 0)} because {@code DecoratedPotRenderer.submit} makes no
 *       {@code PoseStack.translate} call between the base and sides submitModel calls.</li>
 *   <li>{@link #BED_FOOT_OFFSET} - the bed foot's render offset extracted from
 *       {@code BedRenderer.submit}'s {@code translate(0, 0, 16)} call. Hardcoded as 3 ints;
 *       the runtime is stable across MC versions.</li>
 * </ul>
 *
 * <p>Everything else in the output (the block id list, the per-block texture id, the
 * block &rarr; variant dispatch) is derived from the bytecode.
 */
@UtilityClass
public final class BlockListDiscovery {

    private static final @NotNull String BLOCK_ENTITY_TYPE = "net/minecraft/world/level/block/entity/BlockEntityType";
    private static final @NotNull String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final @NotNull String DYE_COLOR = "net/minecraft/world/item/DyeColor";
    private static final @NotNull String WOOD_TYPE = "net/minecraft/world/level/block/state/properties/WoodType";
    private static final @NotNull String SKULL_TYPES = "net/minecraft/world/level/block/SkullBlock$Types";
    private static final @NotNull String BELL_ATTACH_TYPE = "net/minecraft/world/level/block/state/properties/BellAttachType";
    private static final @NotNull String CHEST_SPECIAL_RENDERER = "net/minecraft/client/renderer/special/ChestSpecialRenderer";
    private static final @NotNull String SKULL_BLOCK_RENDERER = "net/minecraft/client/renderer/blockentity/SkullBlockRenderer";
    private static final @NotNull String CONDUIT_RENDERER = "net/minecraft/client/renderer/blockentity/ConduitRenderer";
    private static final @NotNull String BELL_RENDERER = "net/minecraft/client/renderer/blockentity/BellRenderer";
    private static final @NotNull String COPPER_GOLEM_OXIDATION_LEVELS = "net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevels";
    private static final @NotNull String DEFAULT_PLAYER_SKIN = "net/minecraft/client/resources/DefaultPlayerSkin";

    // ------------------------------------------------------------------------------------------
    // Record types - identical shape to the former BlockListCatalog records.
    // ------------------------------------------------------------------------------------------

    /**
     * A single block that renders as an entity-model entity.
     *
     * @param blockId the block id (e.g. {@code "minecraft:oak_sign"})
     * @param textureId the entity-texture id (e.g. {@code "minecraft:entity/signs/oak"})
     */
    public record BlockMapping(@NotNull String blockId, @NotNull String textureId) {}

    /**
     * A link to a secondary entity-model rendered on top of / next to this one. Used for
     * multi-part entities like beds (head + foot) and decorated pots (base + sides).
     *
     * @param model the sub-model entity id
     * @param offset optional pixel-space render offset against the parent
     * @param texture optional override texture for the sub-model
     */
    public record PartRef(@NotNull String model, int @Nullable [] offset, @Nullable String texture) {
        public PartRef(@NotNull String model) {
            this(model, null, null);
        }
        public PartRef(@NotNull String model, int @NotNull [] offset) {
            this(model, offset, null);
        }
    }

    /** Full binding for one entity-model id. */
    public record EntityBlockMapping(@NotNull List<BlockMapping> blocks, @Nullable List<PartRef> parts) {}

    // ------------------------------------------------------------------------------------------
    // Policy constants (the four allowed tables).
    // ------------------------------------------------------------------------------------------

    /**
     * Maps each {@code SkullBlock$Types} enum constant to our entity-id split. Vanilla uses a
     * single {@code BlockEntityType.SKULL} for all 7 skull kinds; our output breaks them into
     * 4 entity-ids keyed on the geometry + texture dimensions ({@code skull_head}: 64x32
     * mob skulls; {@code skull_humanoid_head}: 64x64 player-skin skulls; {@code skull_dragon_head}:
     * full dragon-head mesh; {@code skull_piglin_head}: piglin head + ears).
     */
    private static final @NotNull Map<String, String> SKULL_TYPE_TO_ENTITY_ID = Map.of(
        "SKELETON",        "skull_head",
        "WITHER_SKELETON", "skull_head",
        "CREEPER",         "skull_head",
        "ZOMBIE",          "skull_humanoid_head",
        "PLAYER",          "skull_humanoid_head",
        "DRAGON",          "skull_dragon_head",
        "PIGLIN",          "skull_piglin_head"
    );

    /**
     * Suffix per {@code BellAttachType} enum constant. {@code SINGLE_WALL} and
     * {@code DOUBLE_WALL} do not match their serialized names - our convention drops the
     * {@code single_} prefix on the single-wall form and renames the double-wall form to
     * {@code between_walls} for clarity.
     */
    private static final @NotNull Map<String, String> BELL_ATTACH_SUFFIX = Map.of(
        "FLOOR",       "floor",
        "CEILING",     "ceiling",
        "SINGLE_WALL", "wall",
        "DOUBLE_WALL", "between_walls"
    );

    /**
     * Render offset for {@code minecraft:decorated_pot_sides} against {@code minecraft:decorated_pot}.
     * Definitionally {@code (0, 0, 0)} because {@code DecoratedPotRenderer.submit} makes no
     * {@code PoseStack.translate} call between the base and sides submitModel calls.
     */
    private static final int @NotNull [] DECORATED_POT_SIDES_OFFSET = { 0, 0, 0 };

    /**
     * Render offset for {@code minecraft:bed_foot} against {@code minecraft:bed_head}. Pulled
     * from {@code BedRenderer.submit}'s {@code translate(0, 0, 16)} call: the foot is offset by
     * one block in the z direction (the "down" end of the bed when placed facing south).
     */
    private static final int @NotNull [] BED_FOOT_OFFSET = { 0, 0, 16 };

    /**
     * Per-family dispatch. Keyed on the {@code BlockEntityType} field name (e.g. {@code "CHEST"},
     * {@code "BED"}), values emit one or more {@code (entityId, EntityBlockMapping)} pairs.
     */
    @FunctionalInterface
    private interface FamilyAdapter {
        @NotNull Map<String, EntityBlockMapping> discover(@NotNull ZipFile zip, @NotNull Diagnostics diag);
    }

    private static final @NotNull Map<String, FamilyAdapter> FAMILY_DISPATCH = buildFamilyDispatch();

    /**
     * Builds the family-dispatch table. Emission order is:
     * <ol>
     *   <li>Primary entity ids (shulker, chest, bed_head, sign, ...) - one per BE type.</li>
     *   <li>Sub-model-only entity ids ({@code bed_foot}, {@code decorated_pot_sides},
     *       {@code banner_flag}, {@code wall_banner_flag}) at the end.</li>
     * </ol>
     * This matches the key order of {@code baseline/block_list.json} so regenerating
     * {@code block_entities.json} doesn't reshuffle the output.
     */
    private static @NotNull Map<String, FamilyAdapter> buildFamilyDispatch() {
        LinkedHashMap<String, FamilyAdapter> m = new LinkedHashMap<>();
        // Primary entries first (keyed by BE field; value emits the entity id(s)).
        m.put("SHULKER_BOX",         (z, d) -> Map.of("minecraft:shulker_box", Shulker.discover(z, d)));
        m.put("CHEST",               (z, d) -> Map.of("minecraft:chest",       Chest.discover(z, d)));
        m.put("BED",                 (z, d) -> Map.of("minecraft:bed_head",    Bed.discoverHead(z, d)));
        m.put("SIGN",                (z, d) -> Map.of("minecraft:sign",         Sign.discover(z, d)));
        m.put("HANGING_SIGN",        (z, d) -> Map.of("minecraft:hanging_sign", HangingSign.discover(z, d)));
        m.put("CONDUIT",             (z, d) -> Map.of("minecraft:conduit",      Conduit.discover(z, d)));
        m.put("BELL",                (z, d) -> Map.of("minecraft:bell_body",    Bell.discover(z, d)));
        m.put("DECORATED_POT",       (z, d) -> Map.of("minecraft:decorated_pot", DecoratedPot.discoverPot(z, d)));
        m.put("COPPER_GOLEM_STATUE", (z, d) -> Map.of("minecraft:copper_golem_statue", CopperGolem.discover(z, d)));
        m.put("SKULL",               Skull::discover);
        m.put("BANNER_PRIMARY",      Banner::discoverPrimary);
        // Sub-model-only entries last, matching baseline key order.
        m.put("BED_FOOT",            (z, d) -> Map.of("minecraft:bed_foot",     Bed.discoverFoot()));
        m.put("DECORATED_POT_SIDES", (z, d) -> Map.of("minecraft:decorated_pot_sides", DecoratedPot.discoverSides()));
        m.put("BANNER_SUBMODELS",    Banner::discoverSubModels);
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------------------------------

    /**
     * Walks the client jar and returns the ordered {@code (entity-id, EntityBlockMapping)}
     * table. The emission order is fixed by {@link #FAMILY_DISPATCH} plus the 4 empty
     * sub-model entity ids appended at the end ({@code bed_foot}, {@code decorated_pot_sides},
     * {@code banner_flag}, {@code wall_banner_flag}) - {@code bed_foot} and
     * {@code decorated_pot_sides} are emitted by their respective adapters in-place, the two
     * banner_flag entries are appended here.
     *
     * @param zip the cached deobfuscated Minecraft client jar
     * @param diag the diagnostic sink
     * @return an ordered map of entity-id to the block list + parts
     */
    public static @NotNull Map<String, EntityBlockMapping> discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        LinkedHashMap<String, EntityBlockMapping> out = new LinkedHashMap<>();
        for (Map.Entry<String, FamilyAdapter> entry : FAMILY_DISPATCH.entrySet()) {
            Map<String, EntityBlockMapping> partial = entry.getValue().discover(zip, diag);
            for (Map.Entry<String, EntityBlockMapping> p : partial.entrySet())
                out.put(p.getKey(), p.getValue());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: validBlocks for a BE type
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code BlockEntityType.<clinit>} and returns the ordered list of
     * {@code Blocks.<field>} names bound to the supplied BE-field's {@code validBlocks}
     * argument. Recognises the shape:
     * <pre>
     *   ldc "chest"                                  // <- start of the BE type's init block
     *   ... builder ops ...
     *   getstatic Blocks.CHEST                       // <- validBlocks[0]
     *   getstatic Blocks.COPPER_CHEST                // <- validBlocks[1]
     *   ... up to N GETSTATICs ...
     *   putstatic BlockEntityType.CHEST              // <- binds everything to the field
     * </pre>
     *
     * <p>The walk is bounded by the most recent {@code LDC "id"} (which starts this BE type's
     * init block) and the subsequent {@code PUTSTATIC BlockEntityType.<beField>} (which ends
     * it). Any {@code GETSTATIC Blocks.X} between those two anchors is captured in order.
     *
     * @param zip the client jar
     * @param beField the BE type field name (e.g. {@code "CHEST"})
     * @return the ordered list of {@code Blocks.X} field names bound to that BE type's valid
     *     blocks, or an empty list if the field isn't in the bytecode
     */
    static @NotNull List<String> validBlocks(@NotNull ZipFile zip, @NotNull String beField) {
        ClassNode cn = AsmKit.loadClass(zip, BLOCK_ENTITY_TYPE);
        if (cn == null) return List.of();
        MethodNode init = AsmKit.findMethod(cn, "<clinit>");
        if (init == null) return List.of();
        ConcurrentList<String> pending = Concurrent.newList();
        boolean seenLdc = false;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) {
                // Each ldc starts a new init block. Reset the pending list.
                pending.clear();
                seenLdc = true;
                continue;
            }
            if (!seenLdc) continue;
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(BLOCKS)) {
                pending.add(fi.name);
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(BLOCK_ENTITY_TYPE)) {
                if (fi.name.equals(beField))
                    return List.copyOf(pending);
                pending.clear();
                seenLdc = false;
            }
        }
        return List.of();
    }

    /**
     * Composes a lowercase-namespaced block id from a {@code Blocks.X} field name:
     * {@code "CHEST"} becomes {@code "minecraft:chest"}, {@code "OAK_SIGN"} becomes
     * {@code "minecraft:oak_sign"}.
     */
    static @NotNull String blockFieldToId(@NotNull String blockField) {
        return "minecraft:" + blockField.toLowerCase();
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: dye colour names
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code DyeColor.<clinit>} and returns a {@code (fieldName -> serializedName)} map.
     * The {@code <clinit>} shape per constant is:
     * <pre>
     *   new DyeColor; dup; ldc "WHITE"; iconst_0; iconst_0; ldc "white"; ...; invokespecial; putstatic WHITE
     * </pre>
     * The second {@code LDC} in each init block is the serialized name (the first is the
     * enum constant name, also captured as the {@code PUTSTATIC} field).
     *
     * @return an insertion-ordered map from {@code "WHITE"} to {@code "white"}, etc.
     */
    static @NotNull Map<String, String> walkDyeColorNames(@NotNull ZipFile zip) {
        return walkEnumSerializedNames(zip, DYE_COLOR);
    }

    /**
     * Walks {@code WoodType.<clinit>} and returns a {@code (fieldName -> name)} map. The
     * {@code <clinit>} shape per static binding is:
     * <pre>
     *   new WoodType; dup; ldc "oak"; ...; invokespecial; invokestatic register; putstatic OAK
     * </pre>
     * Unlike {@link #walkDyeColorNames}, there's only one {@code LDC} per init block (the
     * record's {@code name} field); the putstatic field supplies the upper-cased form.
     *
     * @return an insertion-ordered map from {@code "OAK"} to {@code "oak"}, etc.
     */
    static @NotNull Map<String, String> walkWoodTypeNames(@NotNull ZipFile zip) {
        return walkRecordSingleLdcNames(zip, WOOD_TYPE);
    }

    /**
     * Walks {@code SkullBlock$Types.<clinit>} and returns a {@code (fieldName -> serializedName)}
     * map. The shape is identical to {@link #walkDyeColorNames} -
     * {@code new ...; dup; ldc "SKELETON"; iconst_0; ldc "skeleton"; ...}.
     *
     * @return an insertion-ordered map from {@code "SKELETON"} to {@code "skeleton"}, etc.
     */
    static @NotNull Map<String, String> walkSkullTypesNames(@NotNull ZipFile zip) {
        return walkEnumSerializedNames(zip, SKULL_TYPES);
    }

    /**
     * Walks {@code BellAttachType.<clinit>} and returns the ordered list of enum field names.
     * Used both as our iteration order and as the left-hand side of {@link #BELL_ATTACH_SUFFIX}.
     *
     * @return the insertion-ordered list of field names (e.g.
     *     {@code ["FLOOR", "CEILING", "SINGLE_WALL", "DOUBLE_WALL"]})
     */
    static @NotNull List<String> walkBellAttachTypesOrder(@NotNull ZipFile zip) {
        return List.copyOf(walkEnumSerializedNames(zip, BELL_ATTACH_TYPE).keySet());
    }

    /**
     * Shared body of {@link #walkDyeColorNames} / {@link #walkSkullTypesNames} - matches the
     * standard Java enum {@code <clinit>} shape (two {@code LDC}s per constant: NAME then
     * serialized_name).
     */
    private static @NotNull Map<String, String> walkEnumSerializedNames(@NotNull ZipFile zip, @NotNull String enumInternal) {
        ClassNode cn = AsmKit.loadClass(zip, enumInternal);
        if (cn == null) return Map.of();
        MethodNode init = AsmKit.findMethod(cn, "<clinit>");
        if (init == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String firstLdc = null;  // the enum constant NAME (unused - the PUTSTATIC field is authoritative)
        String secondLdc = null; // the serialized name
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW && ti.desc.equals(enumInternal)) {
                firstLdc = null;
                secondLdc = null;
                continue;
            }
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) {
                if (firstLdc == null) firstLdc = lit;
                else if (secondLdc == null) secondLdc = lit;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(enumInternal) && secondLdc != null) {
                out.put(fi.name, secondLdc);
                firstLdc = null;
                secondLdc = null;
            }
        }
        return out;
    }

    /**
     * Shared body of {@link #walkWoodTypeNames} - matches the {@code <clinit>} shape where only
     * ONE {@code LDC} appears between each {@code NEW} and the subsequent {@code PUTSTATIC}
     * (that {@code LDC} is the serialized name; the enum name is implied by the putstatic
     * field).
     */
    private static @NotNull Map<String, String> walkRecordSingleLdcNames(@NotNull ZipFile zip, @NotNull String recordInternal) {
        ClassNode cn = AsmKit.loadClass(zip, recordInternal);
        if (cn == null) return Map.of();
        MethodNode init = AsmKit.findMethod(cn, "<clinit>");
        if (init == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String pendingName = null;
        boolean seenNew = false;
        for (AbstractInsnNode in = init.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW && ti.desc.equals(recordInternal)) {
                pendingName = null;
                seenNew = true;
                continue;
            }
            if (!seenNew) continue;
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null && pendingName == null) {
                pendingName = lit;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(recordInternal) && pendingName != null) {
                out.put(fi.name, pendingName);
                pendingName = null;
                seenNew = false;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: Blocks.X -> constructor-arg enum field
    // ------------------------------------------------------------------------------------------

    /**
     * For every {@code Blocks.<field>} in {@code blockFields}, find the {@code lambda$static$N}
     * that builds that block in {@code Blocks.<clinit>} and return the first
     * {@code GETSTATIC <enumInternal>.<field>} inside the lambda body. That's the "which enum
     * constant did this block's ctor receive" mapping - used for dye colour (beds, shulker,
     * banners), wood type (signs), weather state (copper chests, copper golem statue), and
     * skull type (skulls).
     *
     * <p>Also restricts matches to lambdas whose {@code NEW} target is one of
     * {@code blockClassPrefixes} - each prefix is a strict equality check against the
     * {@code NEW <ClassInternal>}. That lets a family scope its walk to (for example)
     * {@code BedBlock} only (ignoring {@code ShulkerBoxBlock} in the same {@code Blocks}
     * lambda pool).
     *
     * @param zip the client jar
     * @param blockFields the {@code Blocks.<field>} names to dispatch
     * @param enumInternal the enum class to search for (e.g. {@code DYE_COLOR})
     * @param blockClassNames the set of acceptable {@code NEW <Class>} targets; may be empty
     *     (all accepted)
     * @return a map from {@code "Blocks.<field>"} to the enum field name (e.g.
     *     {@code "RED_BED" -> "RED"}); blocks whose lambda doesn't match are absent
     */
    static @NotNull Map<String, String> walkBlocksToCtorEnum(
        @NotNull ZipFile zip,
        @NotNull List<String> blockFields,
        @NotNull String enumInternal,
        @NotNull List<String> blockClassNames,
        @NotNull Diagnostics diag
    ) {
        ClassNode blocksClass = AsmKit.loadClass(zip, BLOCKS);
        if (blocksClass == null) {
            diag.warn("Blocks class missing - cannot resolve block -> %s map", enumInternal);
            return Map.of();
        }
        java.util.Set<String> fieldSet = new java.util.HashSet<>(blockFields);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        MethodNode clinit = AsmKit.findMethod(blocksClass, "<clinit>");
        if (clinit == null) return out;

        // Walk Blocks.<clinit> linearly. For each register pair (LDC id; ...; PUTSTATIC Blocks.X),
        // capture the most recent GETSTATIC <enumInternal>.Y between the last "anchor" (LDC id or
        // another Blocks.PUTSTATIC) and the PUTSTATIC Blocks.X. This captures three shapes:
        //
        //   (a) direct lambda - GETSTATIC happens inside lambda$static$N; we resolve the lambda
        //       and walk its body.
        //   (b) ctor reference ({@code Foo::new} => H_NEWINVOKESPECIAL) - no lambda body to
        //       walk; the ctor is Foo.<init>(Properties) which might push a GETSTATIC
        //       <enumInternal>.Y in its super() call. We walk the ctor for the GETSTATIC.
        //   (c) helper call (e.g. registerBed) - the GETSTATIC happens in Blocks.<clinit> right
        //       before the helper invokestatic; we just see it in the outer scan.
        //
        // The `blockClassNames` filter is only applied when we have a concrete NEW class name
        // (case a and b): empty list accepts all. For (c) helpers, we accept whatever enum is
        // in the <clinit> scope because we can't easily tell what class the helper new'd.
        String pendingLambda = null;
        String pendingCtorClass = null;
        String pendingEnumField = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof InvokeDynamicInsnNode indy) {
                String lambda = resolveIndyStaticLambda(indy, blocksClass);
                if (lambda != null) pendingLambda = lambda;
                String ctorRef = resolveIndyCtorRef(indy);
                if (ctorRef != null) pendingCtorClass = ctorRef;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(enumInternal)) {
                pendingEnumField = fi.name;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(BLOCKS)) {
                if (!fieldSet.contains(fi.name)) {
                    pendingLambda = null;
                    pendingCtorClass = null;
                    pendingEnumField = null;
                    continue;
                }
                String enumField = pendingEnumField;
                String newClass = pendingCtorClass;
                if (enumField == null && pendingLambda != null) {
                    // Fall back to the lambda's first enum GETSTATIC when the outer scan didn't find one.
                    MethodNode lambda = findLambda(blocksClass, pendingLambda);
                    if (lambda != null) {
                        String lambdaNewClass = findLambdaNewClass(lambda);
                        if (newClass == null) newClass = lambdaNewClass;
                        if (blockClassNames.isEmpty() || (lambdaNewClass != null && blockClassNames.contains(lambdaNewClass)))
                            enumField = findFirstEnumGetstatic(lambda, enumInternal);
                    }
                }
                if (enumField == null && pendingCtorClass != null) {
                    // Fall back to walking the ctor class's own <init> for the enum GETSTATIC.
                    enumField = walkCtorForEnumGetstatic(zip, pendingCtorClass, enumInternal);
                }
                if (enumField != null && !blockClassNames.isEmpty() && newClass != null && !blockClassNames.contains(newClass)) {
                    enumField = null;
                }
                if (enumField != null) out.put(fi.name, enumField);
                pendingLambda = null;
                pendingCtorClass = null;
                pendingEnumField = null;
            }
        }
        return out;
    }

    /**
     * Walks {@code classInternal}'s {@code <init>} methods for the first
     * {@code GETSTATIC <enumInternal>.X}. Used to recover the enum arg from a ctor reference
     * lambda ({@code Foo::new}) whose body is just {@code super(EnumConstant, ...)}.
     */
    private static @Nullable String walkCtorForEnumGetstatic(@NotNull ZipFile zip, @NotNull String classInternal, @NotNull String enumInternal) {
        ClassNode cn = AsmKit.loadClass(zip, classInternal);
        if (cn == null) return null;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>")) continue;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
                if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(enumInternal))
                    return fi.name;
        }
        return null;
    }

    /**
     * Returns the block-class instantiated by {@code blockField}'s registration in
     * {@code Blocks.<clinit>}. Recognises three shapes:
     * <ol>
     *   <li>{@code invokedynamic apply} bound to a {@code lambda$static$N} - walk the lambda
     *       for its first {@code NEW}.</li>
     *   <li>{@code invokedynamic apply} bound to {@code Foo::new} ({@code H_NEWINVOKESPECIAL}) -
     *       the ctor class is {@code Foo}.</li>
     *   <li>Helper method call ({@code registerBed}) - returns {@code null} since the helper's
     *       own lambda class is what the register sees, not the semantic Block subclass.</li>
     * </ol>
     */
    static @Nullable String walkBlockNewClass(@NotNull ZipFile zip, @NotNull String blockField) {
        ClassNode blocksClass = AsmKit.loadClass(zip, BLOCKS);
        if (blocksClass == null) return null;
        MethodNode clinit = AsmKit.findMethod(blocksClass, "<clinit>");
        if (clinit == null) return null;
        String pendingLambda = null;
        String pendingCtorClass = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof InvokeDynamicInsnNode indy) {
                String lambda = resolveIndyStaticLambda(indy, blocksClass);
                if (lambda != null) pendingLambda = lambda;
                String ctorRef = resolveIndyCtorRef(indy);
                if (ctorRef != null) pendingCtorClass = ctorRef;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(BLOCKS)) {
                if (fi.name.equals(blockField)) {
                    if (pendingCtorClass != null) return pendingCtorClass;
                    if (pendingLambda != null) {
                        MethodNode lambda = findLambda(blocksClass, pendingLambda);
                        if (lambda != null) return findLambdaNewClass(lambda);
                    }
                    return null;
                }
                pendingLambda = null;
                pendingCtorClass = null;
            }
        }
        return null;
    }

    /**
     * Walks {@code Blocks.<clinit>} and indexes every {@code PUTSTATIC <field>} back to the
     * {@code INVOKEDYNAMIC apply:()Ljava/util/function/Function;} immediately preceding it.
     * The invokedynamic's bootstrap handle names the ctor lambda.
     *
     * @return a map from {@code Blocks.<field>} to {@code lambda$static$N} name
     */
    private static @NotNull Map<String, String> indexBlocksLambdas(@NotNull ClassNode blocksClass) {
        MethodNode clinit = AsmKit.findMethod(blocksClass, "<clinit>");
        if (clinit == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String pendingLambda = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof InvokeDynamicInsnNode indy) {
                String lambda = resolveIndyStaticLambda(indy, blocksClass);
                if (lambda != null) pendingLambda = lambda;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(BLOCKS) && pendingLambda != null) {
                out.put(fi.name, pendingLambda);
                pendingLambda = null;
            }
        }
        return out;
    }

    /**
     * Resolves an {@code INVOKEDYNAMIC apply} bootstrapped via {@link java.lang.invoke.LambdaMetafactory}
     * to the {@code lambda$static$N} name when the handle is {@code H_INVOKESTATIC} and targets
     * the enclosing class.
     */
    private static @Nullable String resolveIndyStaticLambda(@NotNull InvokeDynamicInsnNode indy, @NotNull ClassNode owner) {
        if (indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;
        if (handle.getTag() != Opcodes.H_INVOKESTATIC) return null;
        if (!handle.getOwner().equals(owner.name)) return null;
        return handle.getName();
    }

    /**
     * Resolves an {@code INVOKEDYNAMIC apply} bootstrapped via {@link java.lang.invoke.LambdaMetafactory}
     * to the ctor class ({@code Foo} in {@code Foo::new}) when the handle is
     * {@code H_NEWINVOKESPECIAL}. Returns {@code null} for other handle tags.
     */
    private static @Nullable String resolveIndyCtorRef(@NotNull InvokeDynamicInsnNode indy) {
        if (indy.bsmArgs.length < 2) return null;
        if (!(indy.bsmArgs[1] instanceof Handle handle)) return null;
        if (handle.getTag() != Opcodes.H_NEWINVOKESPECIAL) return null;
        return handle.getOwner();
    }

    /** Looks up a {@code lambda$static$N} method on {@code owner} by name. */
    private static @Nullable MethodNode findLambda(@NotNull ClassNode owner, @NotNull String lambdaName) {
        for (MethodNode m : owner.methods)
            if (m.name.equals(lambdaName)) return m;
        return null;
    }

    /** Returns the internal name of the first {@code NEW} type-insn in {@code lambda}'s body. */
    private static @Nullable String findLambdaNewClass(@NotNull MethodNode lambda) {
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext())
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) return ti.desc;
        return null;
    }

    /** Returns the first {@code GETSTATIC} field name on {@code enumInternal} within {@code lambda}. */
    private static @Nullable String findFirstEnumGetstatic(@NotNull MethodNode lambda, @NotNull String enumInternal) {
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext())
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(enumInternal))
                return fi.name;
        return null;
    }

    /**
     * Returns {@code true} if {@code blockField}'s ctor lambda pushes an {@code ACONST_NULL} as
     * its immediate argument to the constructor (before {@code ALOAD_0}). Used by the shulker
     * adapter to distinguish the uncolored variant (null DyeColor) from the dyed ones.
     */
    static boolean walkBlockCtorHasNullArg(@NotNull ZipFile zip, @NotNull String blockField) {
        ClassNode blocksClass = AsmKit.loadClass(zip, BLOCKS);
        if (blocksClass == null) return false;
        Map<String, String> blockToLambda = indexBlocksLambdas(blocksClass);
        String lambdaName = blockToLambda.get(blockField);
        if (lambdaName == null) return false;
        MethodNode lambda = findLambda(blocksClass, lambdaName);
        if (lambda == null) return false;
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext())
            if (in.getOpcode() == Opcodes.ACONST_NULL) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: ChestSpecialRenderer.<clinit> - per-variant texture base-name
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code ChestSpecialRenderer.<clinit>} for the {@code (FIELD -> texture-base)}
     * pairs. Two shapes are recognised:
     * <pre>
     *   // MultiblockChestResources variants:
     *   ldc "normal" -> invokestatic createDefaultTextures -> putstatic REGULAR
     *   // Single Identifier variant (ENDER_CHEST):
     *   ldc "ender" -> invokestatic Identifier.withDefaultNamespace -> putstatic ENDER_CHEST
     * </pre>
     *
     * @return a map keyed by the field name ({@code "REGULAR"}, {@code "ENDER_CHEST"}, etc.)
     */
    static @NotNull Map<String, String> walkChestSpecialRendererVariants(@NotNull ZipFile zip) {
        ClassNode cn = AsmKit.loadClass(zip, CHEST_SPECIAL_RENDERER);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String pendingLdc = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) {
                pendingLdc = lit;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(CHEST_SPECIAL_RENDERER) && pendingLdc != null) {
                out.put(fi.name, pendingLdc);
                pendingLdc = null;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: CopperGolemOxidationLevels.<clinit>
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code CopperGolemOxidationLevels.<clinit>} for the
     * {@code (WeatherStateField -> texture-path)} pairs. The shape per binding is:
     * <pre>
     *   new CopperGolemOxidationLevel; dup; ...sounds...;
     *       ldc "textures/entity/copper_golem/copper_golem.png";
     *       invokestatic Identifier.withDefaultNamespace;
     *       ldc "textures/entity/copper_golem/copper_golem_eyes.png";  // <- dropped
     *       invokestatic ...;
     *       invokespecial <init>;
     *       putstatic UNAFFECTED
     * </pre>
     * We capture the FIRST {@code LDC} string between each {@code NEW} and {@code PUTSTATIC}
     * (the body texture), strip the {@code textures/} prefix + {@code .png} suffix, and bind it
     * to the {@code PUTSTATIC} field name.
     *
     * @return a map from {@code "UNAFFECTED"} to {@code "entity/copper_golem/copper_golem"}, etc.
     */
    static @NotNull Map<String, String> walkCopperGolemOxidationLevels(@NotNull ZipFile zip) {
        ClassNode cn = AsmKit.loadClass(zip, COPPER_GOLEM_OXIDATION_LEVELS);
        if (cn == null) return Map.of();
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String firstLdcAfterNew = null;
        boolean afterNew = false;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) {
                firstLdcAfterNew = null;
                afterNew = true;
                continue;
            }
            if (!afterNew) continue;
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null && firstLdcAfterNew == null && lit.startsWith("textures/") && lit.endsWith(".png")) {
                firstLdcAfterNew = stripTexturesPrefixAndPngSuffix(lit);
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(COPPER_GOLEM_OXIDATION_LEVELS) && firstLdcAfterNew != null) {
                out.put(fi.name, firstLdcAfterNew);
                firstLdcAfterNew = null;
                afterNew = false;
            }
        }
        return out;
    }

    /** Strips a leading {@code textures/} and trailing {@code .png} from a string, returning whatever remains. */
    private static @NotNull String stripTexturesPrefixAndPngSuffix(@NotNull String s) {
        String trimmed = s;
        if (trimmed.startsWith("textures/")) trimmed = trimmed.substring("textures/".length());
        if (trimmed.endsWith(".png")) trimmed = trimmed.substring(0, trimmed.length() - ".png".length());
        return trimmed;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: SkullBlockRenderer.lambda$static$0 - (SkullType -> texture-path) map
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code SkullBlockRenderer.lambda$static$0} - the static-initialiser lambda that
     * populates the {@code SKIN_BY_TYPE} HashMap - for
     * {@code (SkullBlock$Types.X, "textures/entity/Y.png")} pairs. PLAYER is special: its value
     * is the result of {@code DefaultPlayerSkin.getDefaultTexture()} rather than a literal LDC.
     * For PLAYER we chase the getter through
     * {@code DEFAULT_SKINS[6].body().texturePath()} - see {@link #walkPlayerSkullTexture}.
     *
     * @return a map from skull type field name ({@code "SKELETON"}) to canonical texture path
     *     ({@code "entity/skeleton/skeleton"} - no {@code textures/} prefix or {@code .png}
     *     suffix)
     */
    static @NotNull Map<String, String> walkSkullSkinMap(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, SKULL_BLOCK_RENDERER);
        if (cn == null) {
            diag.warn("SkullBlockRenderer missing - cannot resolve skull skin map");
            return Map.of();
        }
        MethodNode lambda = findSkullStaticLambda(cn);
        if (lambda == null) {
            diag.warn("SkullBlockRenderer.lambda$static$0 missing - cannot resolve skull skin map");
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String pendingType = null;
        String pendingTex = null;
        boolean pendingPlayerFollow = false;
        for (AbstractInsnNode in = lambda.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(SKULL_TYPES)) {
                pendingType = fi.name;
                pendingTex = null;
                pendingPlayerFollow = false;
                continue;
            }
            if (pendingType == null) continue;
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null && lit.startsWith("textures/") && lit.endsWith(".png")) {
                pendingTex = stripTexturesPrefixAndPngSuffix(lit);
                continue;
            }
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(DEFAULT_PLAYER_SKIN) && mi.name.equals("getDefaultTexture")) {
                pendingPlayerFollow = true;
                continue;
            }
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && mi.owner.equals("java/util/HashMap") && mi.name.equals("put")) {
                if (pendingTex != null) {
                    out.put(pendingType, pendingTex);
                } else if (pendingPlayerFollow) {
                    String playerTex = walkPlayerSkullTexture(zip, diag);
                    if (playerTex != null) out.put(pendingType, playerTex);
                    else diag.warn("Could not resolve PLAYER skull default texture - skipping");
                }
                pendingType = null;
                pendingTex = null;
                pendingPlayerFollow = false;
            }
        }
        return out;
    }

    /**
     * Locates {@code SkullBlockRenderer}'s {@code lambda$static$N(HashMap)} helper - the
     * lambda that populates the {@code SKIN_BY_TYPE} map inside {@code Util.make}.
     */
    private static @Nullable MethodNode findSkullStaticLambda(@NotNull ClassNode cn) {
        // The lambda signature is (Ljava/util/HashMap;)V - it populates a freshly-built HashMap
        // passed in via Util.make.
        for (MethodNode m : cn.methods)
            if (m.name.startsWith("lambda$static$") && m.desc.equals("(Ljava/util/HashMap;)V"))
                return m;
        return null;
    }

    /**
     * Resolves {@code DefaultPlayerSkin.getDefaultTexture()} -&gt; its canonical texture path by
     * walking:
     * <ol>
     *   <li>{@code DefaultPlayerSkin.getDefaultSkin()} for the {@code bipush N; aaload} that picks
     *       the default skin index out of {@code DEFAULT_SKINS}.</li>
     *   <li>{@code DefaultPlayerSkin.<clinit>} for the {@code aastore}'d string at that index.</li>
     * </ol>
     *
     * <p>The {@code body()} record accessor is a trivial getter and the {@code texturePath()}
     * on the resulting {@code ClientAsset$ResourceTexture} is also a trivial getter - both
     * pass the LDC string through unchanged, so we don't need to walk them.
     *
     * @return the canonical texture path (e.g. {@code "entity/player/slim/steve"}), or {@code null}
     *     when the walk can't resolve
     */
    static @Nullable String walkPlayerSkullTexture(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, DEFAULT_PLAYER_SKIN);
        if (cn == null) {
            diag.warn("DefaultPlayerSkin missing - cannot resolve PLAYER skull texture");
            return null;
        }
        Integer defaultSkinIndex = readDefaultSkinIndex(cn);
        if (defaultSkinIndex == null) {
            diag.warn("DefaultPlayerSkin.getDefaultSkin missing the [N]aaload pattern");
            return null;
        }
        String path = readDefaultSkinsEntry(cn, defaultSkinIndex);
        if (path == null) {
            diag.warn("DefaultPlayerSkin.<clinit> missing aastore at index %d", defaultSkinIndex);
            return null;
        }
        return path;
    }

    /**
     * Reads {@code DefaultPlayerSkin.getDefaultSkin()} for the {@code iconst_/bipush/sipush/ldc <N>}
     * pushed right before the {@code aaload}.
     */
    private static @Nullable Integer readDefaultSkinIndex(@NotNull ClassNode cn) {
        MethodNode m = AsmKit.findMethod(cn, "getDefaultSkin");
        if (m == null) return null;
        Integer pending = null;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            Integer lit = AsmKit.readIntLiteral(in);
            if (lit != null) pending = lit;
            if (in.getOpcode() == Opcodes.AALOAD) return pending;
        }
        return null;
    }

    /**
     * Reads {@code DefaultPlayerSkin.<clinit>} for the {@code LDC} that populates
     * {@code DEFAULT_SKINS[targetIndex]}. The shape is:
     * <pre>
     *   dup; iconst_ N; ldc "entity/player/..."; getstatic PlayerModelType.X;
     *   invokestatic create; aastore
     * </pre>
     * We scan for each {@code aastore}'s preceding {@code LDC} and match on the preceding
     * integer literal.
     */
    private static @Nullable String readDefaultSkinsEntry(@NotNull ClassNode cn, int targetIndex) {
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) return null;
        Integer pendingIdx = null;
        String pendingLdc = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            Integer lit = AsmKit.readIntLiteral(in);
            if (lit != null) {
                pendingIdx = lit;
                pendingLdc = null;
                continue;
            }
            String s = AsmKit.readStringLiteral(in);
            if (s != null && pendingIdx != null) {
                pendingLdc = s;
                continue;
            }
            if (in.getOpcode() == Opcodes.AASTORE) {
                if (pendingIdx != null && pendingIdx == targetIndex && pendingLdc != null)
                    return pendingLdc;
                pendingIdx = null;
                pendingLdc = null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: ConduitRenderer.<clinit> - shell texture
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code ConduitRenderer.<clinit>} for the {@code (SHELL_TEXTURE field ->
     * defaultNamespaceApply LDC)} binding. Returns the {@code SHELL_TEXTURE} field's string arg
     * composed against the conduit MAPPER's base path.
     *
     * @return {@code "entity/conduit/base"} (or whatever the current jar has), or {@code null}
     *     when the walk can't resolve
     */
    static @Nullable String resolveConduitShellTexture(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, CONDUIT_RENDERER);
        if (cn == null) {
            diag.warn("ConduitRenderer missing - cannot resolve conduit shell texture");
            return null;
        }
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) {
            diag.warn("ConduitRenderer.<clinit> missing");
            return null;
        }
        // Walk pattern: LDC "entity/conduit"; NEW SpriteMapper; ... PUTSTATIC MAPPER.
        // Then for each SHELL_TEXTURE etc: ALOAD MAPPER; LDC "base"; INVOKEVIRTUAL defaultNamespaceApply; PUTSTATIC SHELL_TEXTURE
        String mapperBasePath = null;
        String pendingLdc = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) {
                if (mapperBasePath == null && lit.contains("/")) {
                    // First path-like LDC is the mapper base path. Tentatively record.
                    mapperBasePath = lit;
                }
                pendingLdc = lit;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(CONDUIT_RENDERER)
                && fi.name.equals("SHELL_TEXTURE") && pendingLdc != null && mapperBasePath != null) {
                return mapperBasePath + "/" + pendingLdc;
            }
        }
        diag.warn("ConduitRenderer.SHELL_TEXTURE binding not found");
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Shared primitive: BellRenderer.<clinit> - bell body texture
    // ------------------------------------------------------------------------------------------

    /**
     * Walks {@code BellRenderer.<clinit>} for the {@code BELL_TEXTURE} field's string.
     * Shape: {@code GETSTATIC Sheets.BLOCK_ENTITIES_MAPPER; LDC "bell/bell_body"; INVOKEVIRTUAL defaultNamespaceApply; PUTSTATIC BELL_TEXTURE}.
     * The Sheets BLOCK_ENTITIES_MAPPER base is {@code "entity"}, so we prepend that.
     */
    static @Nullable String resolveBellTexture(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
        ClassNode cn = AsmKit.loadClass(zip, BELL_RENDERER);
        if (cn == null) {
            diag.warn("BellRenderer missing - cannot resolve bell body texture");
            return null;
        }
        MethodNode clinit = AsmKit.findMethod(cn, "<clinit>");
        if (clinit == null) {
            diag.warn("BellRenderer.<clinit> missing");
            return null;
        }
        String pendingLdc = null;
        for (AbstractInsnNode in = clinit.instructions.getFirst(); in != null; in = in.getNext()) {
            String lit = AsmKit.readStringLiteral(in);
            if (lit != null) {
                pendingLdc = lit;
                continue;
            }
            if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC && fi.owner.equals(BELL_RENDERER)
                && fi.name.equals("BELL_TEXTURE") && pendingLdc != null) {
                // BLOCK_ENTITIES_MAPPER base path is "entity".
                return "entity/" + pendingLdc;
            }
        }
        diag.warn("BellRenderer.BELL_TEXTURE binding not found");
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Per-family adapters
    // ------------------------------------------------------------------------------------------

    /**
     * Chest family. Unions {@code BlockEntityType.CHEST}, {@code TRAPPED_CHEST}, and
     * {@code ENDER_CHEST}'s {@code validBlocks} lists under the single {@code minecraft:chest}
     * entity-id (matching {@code SourceDiscovery}'s {@code seenRenderers} dedupe on shared
     * ChestRenderer). Per-block variant dispatch uses {@link #walkBlocksToCtorEnum} with the
     * {@code WeatheringCopper$WeatherState} enum for copper chests; the vanilla/trapped/ender
     * classes map to REGULAR/TRAPPED/ENDER_CHEST by {@code NEW} class name.
     */
    @UtilityClass
    private static final class Chest {

        private static final String CHEST_BLOCK = "net/minecraft/world/level/block/ChestBlock";
        private static final String TRAPPED_CHEST_BLOCK = "net/minecraft/world/level/block/TrappedChestBlock";
        private static final String ENDER_CHEST_BLOCK = "net/minecraft/world/level/block/EnderChestBlock";
        private static final String COPPER_CHEST_BLOCK = "net/minecraft/world/level/block/CopperChestBlock";
        private static final String WEATHERING_COPPER_CHEST_BLOCK = "net/minecraft/world/level/block/WeatheringCopperChestBlock";
        private static final String WEATHER_STATE = "net/minecraft/world/level/block/WeatheringCopper$WeatherState";

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            ConcurrentList<String> blocks = Concurrent.newList();
            blocks.addAll(validBlocks(zip, "CHEST"));
            blocks.addAll(validBlocks(zip, "TRAPPED_CHEST"));
            blocks.addAll(validBlocks(zip, "ENDER_CHEST"));

            Map<String, String> variantBaseName = walkChestSpecialRendererVariants(zip);
            if (variantBaseName.isEmpty()) diag.warn("ChestSpecialRenderer variant map empty - chest entity texture paths will be missing");

            Map<String, String> blockToWeather = walkBlocksToCtorEnum(zip, blocks, WEATHER_STATE,
                List.of(COPPER_CHEST_BLOCK, WEATHERING_COPPER_CHEST_BLOCK), diag);

            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String blockField : blocks) {
                String variant = pickChestVariant(zip, blockField, blockToWeather);
                String baseName = variantBaseName.get(variant);
                if (baseName == null) {
                    diag.warn("Chest variant '%s' has no base-name binding in ChestSpecialRenderer", variant);
                    continue;
                }
                mappings.add(new BlockMapping(blockFieldToId(blockField), "minecraft:entity/chest/" + baseName));
            }
            return new EntityBlockMapping(List.copyOf(mappings), null);
        }

        /**
         * Picks the chest-variant field name ({@code REGULAR}, {@code TRAPPED}, {@code ENDER_CHEST},
         * {@code COPPER_UNAFFECTED}, etc.) from the block's ctor lambda.
         */
        private static @NotNull String pickChestVariant(@NotNull ZipFile zip, @NotNull String blockField, @NotNull Map<String, String> blockToWeather) {
            String newClass = walkBlockNewClass(zip, blockField);
            if (newClass == null) return "UNKNOWN";
            return switch (newClass) {
                case CHEST_BLOCK -> "REGULAR";
                case TRAPPED_CHEST_BLOCK -> "TRAPPED";
                case ENDER_CHEST_BLOCK -> "ENDER_CHEST";
                case COPPER_CHEST_BLOCK, WEATHERING_COPPER_CHEST_BLOCK -> {
                    String weather = blockToWeather.get(blockField);
                    yield weather == null ? "COPPER_UNAFFECTED" : "COPPER_" + weather;
                }
                default -> "UNKNOWN";
            };
        }
    }

    /**
     * Bed family. Walks {@code BlockEntityType.BED} for the 16 dyed-bed block fields, then walks
     * their ctor lambdas for the {@code GETSTATIC DyeColor.X}. The texture id is composed as
     * {@code entity/bed/<color.getName()>} (the lowercase serialized name, matching
     * {@code Sheets.BED_TEXTURES}'s {@code createBedSprite(color)} factory).
     *
     * <p>Emits one {@code parts} entry for {@code minecraft:bed_foot} with
     * offset {@link #BED_FOOT_OFFSET}.
     */
    @UtilityClass
    private static final class Bed {

        private static final String BED_BLOCK = "net/minecraft/world/level/block/BedBlock";

        static @NotNull EntityBlockMapping discoverHead(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "BED");
            Map<String, String> dyeColorName = walkDyeColorNames(zip);
            // Bed blocks don't use the standard invokedynamic ctor pattern - they delegate to
            // Blocks.registerBed(String, DyeColor) which in turn invokedynamics. The outer scan
            // finds the GETSTATIC DyeColor.X that's loaded as the helper's second argument; we
            // accept any NEW class because the lambda is an inner wrapper whose class is the
            // helper method's own.
            Map<String, String> blockToColor = walkBlocksToCtorEnum(zip, blocks, DYE_COLOR, List.of(), diag);
            LinkedHashMap<String, BlockMapping> byColor = new LinkedHashMap<>();
            for (String blockField : blocks) {
                String colorField = blockToColor.get(blockField);
                if (colorField == null) {
                    diag.warn("Bed block '%s' has no DyeColor arg - skipped", blockField);
                    continue;
                }
                String colorName = dyeColorName.get(colorField);
                if (colorName == null) {
                    diag.warn("DyeColor.%s has no serialized name - skipped", colorField);
                    continue;
                }
                byColor.put(colorField, new BlockMapping(blockFieldToId(blockField), "minecraft:entity/bed/" + colorName));
            }
            // Order by DyeColor declaration order so the output matches the Sheets BED_TEXTURES
            // (sorted by DyeColor.getId()) order.
            ConcurrentList<BlockMapping> ordered = Concurrent.newList();
            for (String colorField : dyeColorName.keySet()) {
                BlockMapping bm = byColor.get(colorField);
                if (bm != null) ordered.add(bm);
            }
            return new EntityBlockMapping(List.copyOf(ordered), List.of(new PartRef("minecraft:bed_foot", BED_FOOT_OFFSET)));
        }

        static @NotNull EntityBlockMapping discoverFoot() {
            return new EntityBlockMapping(List.of(), null);
        }
    }

    /**
     * Shulker box family. One uncolored + 16 dyed variants. The uncolored variant's ctor passes
     * {@code ACONST_NULL} instead of a {@code GETSTATIC DyeColor.X}; dyed variants pass the
     * color. Texture path is {@code entity/shulker/shulker} for uncolored,
     * {@code entity/shulker/shulker_<color>} for dyed (matching the
     * {@code Sheets.colorToShulkerSprite} string-concat).
     */
    @UtilityClass
    private static final class Shulker {

        private static final String SHULKER_BOX_BLOCK = "net/minecraft/world/level/block/ShulkerBoxBlock";

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "SHULKER_BOX");
            Map<String, String> dyeColorName = walkDyeColorNames(zip);
            Map<String, String> blockToColor = walkBlocksToCtorEnum(zip, blocks, DYE_COLOR, List.of(SHULKER_BOX_BLOCK), diag);

            ConcurrentList<BlockMapping> uncolored = Concurrent.newList();
            // Index the dyed variants by their DyeColor field name so ordering by DyeColor
            // iteration order is unambiguous.
            LinkedHashMap<String, BlockMapping> dyedByColor = new LinkedHashMap<>();
            for (String blockField : blocks) {
                String colorField = blockToColor.get(blockField);
                if (colorField == null) {
                    uncolored.add(new BlockMapping(blockFieldToId(blockField), "minecraft:entity/shulker/shulker"));
                    continue;
                }
                String colorName = dyeColorName.get(colorField);
                if (colorName == null) continue;
                dyedByColor.put(colorField, new BlockMapping(blockFieldToId(blockField), "minecraft:entity/shulker/shulker_" + colorName));
            }
            ConcurrentList<BlockMapping> out = Concurrent.newList();
            out.addAll(uncolored);
            for (String colorField : dyeColorName.keySet()) {
                BlockMapping bm = dyedByColor.get(colorField);
                if (bm != null) out.add(bm);
            }
            return new EntityBlockMapping(List.copyOf(out), null);
        }
    }

    /**
     * Sign family. 24 sign blocks (12 standing + 12 wall), all routed under
     * {@code minecraft:sign}. Each block's ctor lambda has a {@code GETSTATIC WoodType.X}; we
     * compose {@code entity/signs/<wood.name()>}.
     */
    @UtilityClass
    private static final class Sign {

        private static final String STANDING_SIGN_BLOCK = "net/minecraft/world/level/block/StandingSignBlock";
        private static final String WALL_SIGN_BLOCK = "net/minecraft/world/level/block/WallSignBlock";

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            return SignLike.discover(zip, diag, "SIGN",
                "entity/signs/",
                List.of(STANDING_SIGN_BLOCK, WALL_SIGN_BLOCK));
        }
    }

    /**
     * Hanging-sign family. 24 hanging-sign blocks (12 standing + 12 wall). Identical wiring to
     * {@link Sign} but {@code entity/signs/hanging/<wood>} and
     * {@code CeilingHangingSignBlock} / {@code WallHangingSignBlock} as the accepted ctor
     * classes.
     */
    @UtilityClass
    private static final class HangingSign {

        private static final String CEILING_HANGING_SIGN_BLOCK = "net/minecraft/world/level/block/CeilingHangingSignBlock";
        private static final String WALL_HANGING_SIGN_BLOCK = "net/minecraft/world/level/block/WallHangingSignBlock";

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            return SignLike.discover(zip, diag, "HANGING_SIGN",
                "entity/signs/hanging/",
                List.of(CEILING_HANGING_SIGN_BLOCK, WALL_HANGING_SIGN_BLOCK));
        }
    }

    /**
     * Shared adapter body for the two sign families. Walks the BE type's {@code validBlocks},
     * matches each block to its {@code WoodType}, and emits a texture id with the supplied
     * prefix.
     */
    @UtilityClass
    private static final class SignLike {
        static @NotNull EntityBlockMapping discover(
            @NotNull ZipFile zip,
            @NotNull Diagnostics diag,
            @NotNull String beField,
            @NotNull String texturePrefix,
            @NotNull List<String> acceptedBlockClasses
        ) {
            List<String> blocks = validBlocks(zip, beField);
            Map<String, String> woodName = walkWoodTypeNames(zip);
            Map<String, String> blockToWood = walkBlocksToCtorEnum(zip, blocks, WOOD_TYPE, acceptedBlockClasses, diag);
            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String blockField : blocks) {
                String woodField = blockToWood.get(blockField);
                if (woodField == null) {
                    diag.warn("%s block '%s' has no WoodType arg - skipped", beField, blockField);
                    continue;
                }
                String wood = woodName.get(woodField);
                if (wood == null) {
                    diag.warn("WoodType.%s has no name - skipped", woodField);
                    continue;
                }
                mappings.add(new BlockMapping(blockFieldToId(blockField), "minecraft:" + texturePrefix + wood));
            }
            return new EntityBlockMapping(List.copyOf(mappings), null);
        }
    }

    /**
     * Banner / wall-banner family. 32 banner blocks total (16 standing + 16 wall), split into
     * two entity ids by the block's {@code NEW} class name ({@code BannerBlock} vs
     * {@code WallBannerBlock}). All 32 share the single {@code entity/banner/banner_base}
     * texture; per-color appearance comes from {@code BannerBlock.getBaseColor()} applied as a
     * render-time tint.
     *
     * <p>Emits four entity ids: {@code banner}, {@code wall_banner}, plus empty
     * {@code banner_flag} / {@code wall_banner_flag} for the flag sub-models.
     */
    @UtilityClass
    private static final class Banner {

        private static final String BANNER_BLOCK = "net/minecraft/world/level/block/BannerBlock";
        private static final String WALL_BANNER_BLOCK = "net/minecraft/world/level/block/WallBannerBlock";
        private static final @NotNull String BANNER_BASE_TEXTURE = "minecraft:entity/banner/banner_base";

        /**
         * Emits the primary {@code banner} and {@code wall_banner} entity-ids with their
         * colour-ordered block lists. The empty sub-model ids ({@code banner_flag},
         * {@code wall_banner_flag}) are emitted separately by {@link #discoverSubModels}
         * at the end of the dispatch table so they cluster with the other empty sub-models.
         */
        static @NotNull Map<String, EntityBlockMapping> discoverPrimary(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "BANNER");
            Map<String, String> dyeColorName = walkDyeColorNames(zip);
            Map<String, String> blockToColor = walkBlocksToCtorEnum(zip, blocks, DYE_COLOR,
                List.of(BANNER_BLOCK, WALL_BANNER_BLOCK), diag);

            ConcurrentList<BlockMapping> standing = Concurrent.newList();
            ConcurrentList<BlockMapping> wall = Concurrent.newList();
            for (String blockField : blocks) {
                String newClass = walkBlockNewClass(zip, blockField);
                if (newClass == null) continue;
                BlockMapping bm = new BlockMapping(blockFieldToId(blockField), BANNER_BASE_TEXTURE);
                if (newClass.equals(BANNER_BLOCK)) standing.add(bm);
                else if (newClass.equals(WALL_BANNER_BLOCK)) wall.add(bm);
            }
            ConcurrentList<BlockMapping> orderedStanding = orderByDyeColor(standing, blockToColor, dyeColorName);
            ConcurrentList<BlockMapping> orderedWall = orderByDyeColor(wall, blockToColor, dyeColorName);

            LinkedHashMap<String, EntityBlockMapping> out = new LinkedHashMap<>();
            out.put("minecraft:banner",       new EntityBlockMapping(List.copyOf(orderedStanding), List.of(new PartRef("minecraft:banner_flag"))));
            out.put("minecraft:wall_banner",  new EntityBlockMapping(List.copyOf(orderedWall),     List.of(new PartRef("minecraft:wall_banner_flag"))));
            return out;
        }

        /** Emits the empty {@code banner_flag} and {@code wall_banner_flag} entity-ids. */
        static @NotNull Map<String, EntityBlockMapping> discoverSubModels(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            LinkedHashMap<String, EntityBlockMapping> out = new LinkedHashMap<>();
            out.put("minecraft:banner_flag",      new EntityBlockMapping(List.of(), null));
            out.put("minecraft:wall_banner_flag", new EntityBlockMapping(List.of(), null));
            return out;
        }

        /**
         * Orders banner blocks by DyeColor iteration order. Re-derives block-field -> color
         * field lookups via {@code blockToColor}.
         */
        private static @NotNull ConcurrentList<BlockMapping> orderByDyeColor(
            @NotNull List<BlockMapping> mappings,
            @NotNull Map<String, String> blockToColor,
            @NotNull Map<String, String> dyeColorName
        ) {
            ConcurrentList<BlockMapping> out = Concurrent.newList();
            for (String colorField : dyeColorName.keySet()) {
                for (BlockMapping m : mappings) {
                    String fieldName = m.blockId().substring("minecraft:".length()).toUpperCase();
                    String cf = blockToColor.get(fieldName);
                    if (colorField.equals(cf)) out.add(m);
                }
            }
            return out;
        }
    }

    /**
     * Skull family. Splits {@code BlockEntityType.SKULL.validBlocks} (14 entries: 7 types x 2
     * standing/wall) into 4 entity-ids per {@link #SKULL_TYPE_TO_ENTITY_ID}. The texture for
     * each block comes from {@code SkullBlockRenderer.SKIN_BY_TYPE} (walked by
     * {@link #walkSkullSkinMap}).
     */
    @UtilityClass
    private static final class Skull {

        static @NotNull Map<String, EntityBlockMapping> discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "SKULL");
            Map<String, String> typeTexture = walkSkullSkinMap(zip, diag);
            Map<String, String> blockToType = walkBlocksToSkullType(zip, blocks, diag);

            LinkedHashMap<String, ConcurrentList<BlockMapping>> byEntityId = new LinkedHashMap<>();
            // Emit entity ids in the baseline order: skull_head, skull_dragon_head, skull_piglin_head, skull_humanoid_head.
            // Ordering is derived from the first skull block's type's SKULL_TYPE_TO_ENTITY_ID
            // value, not from the policy map's ordering (Map.of's iteration is unspecified).
            for (String blockField : blocks) {
                String type = blockToType.get(blockField);
                if (type == null) {
                    diag.warn("Skull block '%s' has no SkullBlock$Types binding - skipped", blockField);
                    continue;
                }
                String entityId = SKULL_TYPE_TO_ENTITY_ID.get(type);
                if (entityId == null) {
                    diag.warn("SkullBlock$Types.%s has no SKULL_TYPE_TO_ENTITY_ID entry - skipped", type);
                    continue;
                }
                String texture = typeTexture.get(type);
                if (texture == null) continue;
                byEntityId.computeIfAbsent("minecraft:" + entityId, k -> Concurrent.newList())
                          .add(new BlockMapping(blockFieldToId(blockField), "minecraft:" + texture));
            }
            LinkedHashMap<String, EntityBlockMapping> out = new LinkedHashMap<>();
            for (Map.Entry<String, ConcurrentList<BlockMapping>> e : byEntityId.entrySet())
                out.put(e.getKey(), new EntityBlockMapping(List.copyOf(e.getValue()), null));
            return out;
        }

        /**
         * Maps every skull block field -&gt; its SkullBlock$Types field via either (a) a direct
         * {@code GETSTATIC SkullBlock$Types.X} in the block's ctor lambda (SkullBlock /
         * WallSkullBlock), or (b) walking the block class's own {@code <init>()V} method for
         * a {@code GETSTATIC SkullBlock$Types.X; INVOKESPECIAL AbstractSkullBlock.<init>} pattern
         * (WitherSkullBlock, WitherWallSkullBlock, PiglinWallSkullBlock).
         */
        private static @NotNull Map<String, String> walkBlocksToSkullType(@NotNull ZipFile zip, @NotNull List<String> blocks, @NotNull Diagnostics diag) {
            // Attempt 1: generic ctor-enum walk over the block's lambda body.
            Map<String, String> direct = walkBlocksToCtorEnum(zip, blocks, SKULL_TYPES, List.of(), diag);
            Map<String, String> out = new LinkedHashMap<>(direct);
            for (String blockField : blocks) {
                if (out.containsKey(blockField)) continue;
                // Attempt 2: the block class's own ctor takes no Type arg; it passes a fixed one
                // to super(). Walk the block class's <init>() for GETSTATIC SkullBlock$Types.X.
                String newClass = walkBlockNewClass(zip, blockField);
                if (newClass == null) continue;
                String type = walkBlockCtorSuperType(zip, newClass);
                if (type != null) out.put(blockField, type);
            }
            return out;
        }

        private static @Nullable String walkBlockCtorSuperType(@NotNull ZipFile zip, @NotNull String blockClass) {
            ClassNode cn = AsmKit.loadClass(zip, blockClass);
            if (cn == null) return null;
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("<init>")) continue;
                for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext())
                    if (in instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETSTATIC && fi.owner.equals(SKULL_TYPES))
                        return fi.name;
            }
            return null;
        }
    }

    /**
     * Decorated pot family. Single block ({@code minecraft:decorated_pot}) with a sub-model
     * part ({@code minecraft:decorated_pot_sides}) whose offset is the policy constant
     * {@link #DECORATED_POT_SIDES_OFFSET}. Textures come from {@code Sheets.DECORATED_POT_BASE}
     * / {@code Sheets.DECORATED_POT_SIDE} which share the {@code entity/decorated_pot} base
     * path and the per-texture LDC strings {@code "decorated_pot_base"} / {@code "decorated_pot_side"}.
     */
    @UtilityClass
    private static final class DecoratedPot {

        private static final @NotNull String BASE_TEXTURE = "minecraft:entity/decorated_pot/decorated_pot_base";
        private static final @NotNull String SIDE_TEXTURE = "minecraft:entity/decorated_pot/decorated_pot_side";

        static @NotNull EntityBlockMapping discoverPot(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "DECORATED_POT");
            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String blockField : blocks)
                mappings.add(new BlockMapping(blockFieldToId(blockField), BASE_TEXTURE));
            return new EntityBlockMapping(List.copyOf(mappings),
                List.of(new PartRef("minecraft:decorated_pot_sides", DECORATED_POT_SIDES_OFFSET, SIDE_TEXTURE)));
        }

        static @NotNull EntityBlockMapping discoverSides() {
            return new EntityBlockMapping(List.of(), null);
        }
    }

    /**
     * Conduit family. Single block. Texture {@code entity/conduit/base} resolved via
     * {@link #resolveConduitShellTexture}.
     */
    @UtilityClass
    private static final class Conduit {

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "CONDUIT");
            String shellTex = resolveConduitShellTexture(zip, diag);
            if (shellTex == null) return new EntityBlockMapping(List.of(), null);
            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String blockField : blocks)
                mappings.add(new BlockMapping(blockFieldToId(blockField), "minecraft:" + shellTex));
            return new EntityBlockMapping(List.copyOf(mappings), null);
        }
    }

    /**
     * Bell family. {@code BlockEntityType.BELL.validBlocks} is {@code [BELL]}, but our output
     * splits into 4 faux block ids keyed on {@code BellAttachType} enum values per
     * {@link #BELL_ATTACH_SUFFIX}. All 4 share {@code entity/bell/bell_body} resolved via
     * {@link #resolveBellTexture}.
     */
    @UtilityClass
    private static final class Bell {

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            String tex = resolveBellTexture(zip, diag);
            if (tex == null) return new EntityBlockMapping(List.of(), null);
            List<String> attachOrder = walkBellAttachTypesOrder(zip);
            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String attachField : attachOrder) {
                String suffix = BELL_ATTACH_SUFFIX.get(attachField);
                if (suffix == null) continue;
                mappings.add(new BlockMapping("minecraft:bell_" + suffix, "minecraft:" + tex));
            }
            return new EntityBlockMapping(List.copyOf(mappings), null);
        }
    }

    /**
     * Copper golem statue family. Walks {@code BlockEntityType.COPPER_GOLEM_STATUE}'s
     * {@code validBlocks}, then dispatches each block via
     * {@code WeatheringCopper$WeatherState}. Textures come from
     * {@link #walkCopperGolemOxidationLevels} ({@code CopperGolemOxidationLevels.<clinit>}).
     *
     * <p>If {@code BlockEntityType.COPPER_GOLEM_STATUE} is absent, emits a {@code diag.warn} and
     * returns an empty mapping - the 26.1 jar may or may not have shipped the field depending
     * on the build.
     */
    @UtilityClass
    private static final class CopperGolem {

        private static final String WEATHER_STATE = "net/minecraft/world/level/block/WeatheringCopper$WeatherState";
        private static final String STATUE_BLOCK = "net/minecraft/world/level/block/CopperGolemStatueBlock";
        private static final String WEATHERING_STATUE_BLOCK = "net/minecraft/world/level/block/WeatheringCopperGolemStatueBlock";

        static @NotNull EntityBlockMapping discover(@NotNull ZipFile zip, @NotNull Diagnostics diag) {
            List<String> blocks = validBlocks(zip, "COPPER_GOLEM_STATUE");
            if (blocks.isEmpty()) {
                diag.warn("BlockEntityType.COPPER_GOLEM_STATUE not bound in client jar - copper_golem_statue entity id skipped");
                return new EntityBlockMapping(List.of(), null);
            }
            Map<String, String> weatherToTexture = walkCopperGolemOxidationLevels(zip);
            Map<String, String> blockToWeather = walkBlocksToCtorEnum(zip, blocks, WEATHER_STATE,
                List.of(STATUE_BLOCK, WEATHERING_STATUE_BLOCK), diag);
            ConcurrentList<BlockMapping> mappings = Concurrent.newList();
            for (String blockField : blocks) {
                String weather = blockToWeather.getOrDefault(blockField, "UNAFFECTED");
                String texture = weatherToTexture.get(weather);
                if (texture == null) {
                    diag.warn("Copper golem statue '%s' has no texture for weather state '%s' - skipped", blockField, weather);
                    continue;
                }
                mappings.add(new BlockMapping(blockFieldToId(blockField), "minecraft:" + texture));
            }
            return new EntityBlockMapping(List.copyOf(mappings), null);
        }
    }

}
