package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement speeds a subject's attribute supplier declares, against synthetic registries and
 * suppliers in the shape the 26.1 jar builds them.
 *
 * <p>The supplier is found the way the renderer registry is - a type read, then the call it pairs
 * with - and its chain is evaluated in order, a parent creator first and the last add of an attribute
 * winning, an add with no value taking the default the attribute was declared with. A class two types
 * share joins neither. A baby's modifier is read where its {@code setBaby} adds it and applied by its
 * own operation, and a modifier of a shape the read does not know answers nothing rather than a guess.
 */
@DisplayName("movement speeds: the supplier join, its chain, the baby modifier and the spawn roll")
class EntityMovementSpeedResolverTest {

    private static final @NotNull String ENTITY_TYPE = SourceClasses.Types.ENTITY_TYPE;
    private static final @NotNull String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    private static final @NotNull String DEFAULT_ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/DefaultAttributes";
    private static final @NotNull String BUILDER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder";
    private static final @NotNull String RANGED = "net/minecraft/world/entity/ai/attributes/RangedAttribute";
    private static final @NotNull String INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    private static final @NotNull String MODIFIER = "net/minecraft/world/entity/ai/attributes/AttributeModifier";
    private static final @NotNull String OPERATION = MODIFIER + "$Operation";
    private static final @NotNull String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final @NotNull String BUILDER_REF = "L" + BUILDER + ";";
    private static final @NotNull String SUPPLIER_DESC = "()" + BUILDER_REF;

    private static final @NotNull String LIVING = "fx/Living";
    private static final @NotNull String MONSTER = "fx/Monster";
    private static final @NotNull String ZOMBIE = "fx/Zombie";
    private static final @NotNull String HORSE = "fx/Horse";
    private static final @NotNull String BOAT = "fx/Boat";

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    @Test
    @DisplayName("a chain evaluates its parent creator first and the last add of an attribute wins")
    void aChainTakesItsParentFirstAndItsLastAdd() throws IOException {
        open(fixtures(babyModifier("ADD_MULTIPLIED_BASE")));
        EntityMovementSpeedResolver.MovementSpeeds zombie = resolve().get("minecraft:zombie");
        // The monster creator adds the speed with no value, the zombie's own chain adds 0.23 after it.
        assertEquals(0.23, zombie.movement(), 0d, "the zombie's own add overrides its parent's");
        assertEquals(OptionalDouble.empty(), zombie.flying(), "a chain adding no flying speed declares none");
    }

    @Test
    @DisplayName("an add with no value takes the default the attribute is declared with")
    void anAddWithNoValueTakesTheDeclaredDefault() throws IOException {
        open(fixtures(babyModifier("ADD_MULTIPLIED_BASE")));
        EntityMovementSpeedResolver.MovementSpeeds horse = resolve().get("minecraft:horse");
        assertEquals(0.7, horse.movement(), 0d, "the speed the attribute is declared at");
        assertEquals(OptionalDouble.of(0.4), horse.flying(), "the flying speed the attribute is declared at");
    }

    @Test
    @DisplayName("a parent creator javac names on the leaf resolves up to the class declaring it")
    void aParentCreatorNamedOnTheLeafResolvesUpward() throws IOException {
        // The zombie calls createMonsterAttributes with itself as the owner, declared on its parent.
        open(fixtures(babyModifier("ADD_MULTIPLIED_BASE")));
        assertTrue(resolve().containsKey("minecraft:zombie"), "the zombie's chain evaluates through its parent's creator");
    }

    @Test
    @DisplayName("a class two types share joins neither subject")
    void aClassBehindTwoTypesJoinsNoSubject() throws IOException {
        open(fixtures(babyModifier("ADD_MULTIPLIED_BASE")));
        assertFalse(resolve().containsKey("minecraft:boat"), "a class behind two types names no one supplier");
    }

    @Test
    @DisplayName("a baby modifier is applied by its own operation")
    void aBabyModifierAppliesByItsOperation() throws IOException {
        assertEquals(OptionalDouble.of(0.23 + 0.5), babyMovement("ADD_VALUE"), "added to the base");
        this.cache.close();
        assertEquals(OptionalDouble.of(0.23 + 0.23 * 0.5), babyMovement("ADD_MULTIPLIED_BASE"), "a share of the base added");
        this.cache.close();
        assertEquals(OptionalDouble.of(0.23 * (1 + 0.5)), babyMovement("ADD_MULTIPLIED_TOTAL"), "the total scaled");
    }

    @Test
    @DisplayName("a baby modifier of another shape answers nothing and says so")
    void aBabyModifierOfAnotherShapeAnswersNothing() throws IOException {
        open(fixtures(babyModifier("ADD_RANDOMLY")));
        Diagnostics diagnostics = diagnostics();
        EntityMovementSpeedResolver.MovementSpeeds zombie =
            EntityMovementSpeedResolver.resolve(this.cache, subjects(), diagnostics).get("minecraft:zombie");
        assertEquals(OptionalDouble.empty(), zombie.babyMovement(), "an operation the read does not know");
        assertTrue(diagnostics.entries().stream().anyMatch(entry -> entry.message().startsWith("minecraft:zombie adds a baby movement modifier")),
            "the subject is named: " + diagnostics.entries());
    }

    @Test
    @DisplayName("a subject whose spawn rolls its speed is marked rolled, and one rolling something else is not")
    void aSubjectWhoseSpawnRollsItsSpeedIsRolled() throws IOException {
        open(fixtures(babyModifier("ADD_MULTIPLIED_BASE")));
        Map<String, EntityMovementSpeedResolver.MovementSpeeds> speeds = resolve();
        assertTrue(speeds.get("minecraft:horse").rolled(), "the horse rolls its movement speed");
        assertFalse(speeds.get("minecraft:zombie").rolled(), "the zombie rolls only its health");
    }

    // ------------------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------------------

    private @NotNull OptionalDouble babyMovement(@NotNull String operation) throws IOException {
        open(fixtures(babyModifier(operation)));
        return resolve().get("minecraft:zombie").babyMovement();
    }

    private @NotNull Map<String, EntityMovementSpeedResolver.MovementSpeeds> resolve() {
        return EntityMovementSpeedResolver.resolve(this.cache, subjects(), diagnostics());
    }

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
    }

    private static @NotNull List<EntitySubject> subjects() {
        return List.of(subject("minecraft:zombie", ZOMBIE), subject("minecraft:horse", HORSE), subject("minecraft:boat", BOAT));
    }

    private static @NotNull EntitySubject subject(@NotNull String id, @NotNull String entityClass) {
        return new EntitySubject(id, entityClass, "fx/Renderer", List.of(), List.of(), List.of());
    }

    /** The registries, the attribute declarations and three subjects; the zombie's baby modifier given. */
    private static @NotNull List<ClassNode> fixtures(@NotNull ClassNode zombie) {
        List<ClassNode> out = new ArrayList<>();
        out.add(entityType());
        out.add(attributes());
        out.add(defaultAttributes());
        out.add(living());
        out.add(monster());
        out.add(zombie);
        out.add(horse());
        out.add(fixtureClass(BOAT, LIVING));
        return out;
    }

    /** {@code EntityType}: one typed field per subject, and two sharing the boat's class. */
    private static @NotNull ClassNode entityType() {
        ClassNode node = fixtureClass(ENTITY_TYPE, "java/lang/Object");
        for (String[] field : new String[][] {{"ZOMBIE", ZOMBIE}, {"HORSE", HORSE}, {"BOAT", BOAT}, {"CHEST_BOAT", BOAT}}) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field[0],
                "L" + ENTITY_TYPE + ";", "L" + ENTITY_TYPE + "<L" + field[1] + ";>;", null));
        }
        return node;
    }

    /** {@code Attributes}: movement speed declared at 0.7 and flying speed at 0.4, as vanilla builds each. */
    private static @NotNull ClassNode attributes() {
        ClassNode node = fixtureClass(ATTRIBUTES, "java/lang/Object");
        InsnList code = new InsnList();
        declare(code, "movement_speed", 0.7, "MOVEMENT_SPEED");
        declare(code, "flying_speed", 0.4, "FLYING_SPEED");
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "<clinit>", "()V", code));
        return node;
    }

    private static void declare(@NotNull InsnList code, @NotNull String id, double fallback, @NotNull String field) {
        code.add(new LdcInsnNode(id));
        code.add(new TypeInsnNode(Opcodes.NEW, RANGED));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new LdcInsnNode("attribute.name." + id));
        code.add(new LdcInsnNode(fallback));
        code.add(new InsnNode(Opcodes.DCONST_0));
        code.add(new LdcInsnNode(1024.0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, RANGED, "<init>", "(Ljava/lang/String;DDD)V", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ATTRIBUTES, "register",
            "(Ljava/lang/String;Lnet/minecraft/world/entity/ai/attributes/Attribute;)" + HOLDER, false));
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, ATTRIBUTES, field, HOLDER));
    }

    /** {@code DefaultAttributes}: each type read, then the supplier it pairs with. */
    private static @NotNull ClassNode defaultAttributes() {
        ClassNode node = fixtureClass(DEFAULT_ATTRIBUTES, "java/lang/Object");
        InsnList code = new InsnList();
        for (String[] pair : new String[][] {{"ZOMBIE", ZOMBIE}, {"HORSE", HORSE}, {"BOAT", BOAT}, {"CHEST_BOAT", BOAT}}) {
            code.add(new FieldInsnNode(Opcodes.GETSTATIC, ENTITY_TYPE, pair[0], "L" + ENTITY_TYPE + ";"));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, pair[1], "createAttributes", SUPPLIER_DESC, false));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BUILDER, "build", "()Ljava/lang/Object;", false));
            code.add(new InsnNode(Opcodes.POP));
            code.add(new InsnNode(Opcodes.POP));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "<clinit>", "()V", code));
        return node;
    }

    /**
     * The living base: a creator adding the movement speed with no value, and the creator the horse and
     * the boat inherit, adding the flying speed with no value over it.
     */
    private static @NotNull ClassNode living() {
        ClassNode node = fixtureClass(LIVING, "java/lang/Object");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/world/entity/ai/attributes/AttributeSupplier",
            "builder", SUPPLIER_DESC, false));
        add(code, "MOVEMENT_SPEED", null);
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "createLivingAttributes", SUPPLIER_DESC, code));
        InsnList flying = new InsnList();
        flying.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LIVING, "createLivingAttributes", SUPPLIER_DESC, false));
        add(flying, "FLYING_SPEED", null);
        flying.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "createAttributes", SUPPLIER_DESC, flying));
        return node;
    }

    /** The monster base: a creator adding the speed with no value over a parent of its own. */
    private static @NotNull ClassNode monster() {
        ClassNode node = fixtureClass(MONSTER, LIVING);
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MONSTER, "createLivingAttributes", SUPPLIER_DESC, false));
        add(code, "MOVEMENT_SPEED", null);
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "createMonsterAttributes", SUPPLIER_DESC, code));
        return node;
    }

    /**
     * The zombie: a chain over the monster's creator, named on itself as javac names an inherited
     * static, then its own 0.23; a {@code setBaby} adding its modifier; a spawn roll of its health.
     */
    private static @NotNull ClassNode babyModifier(@NotNull String operation) {
        ClassNode node = fixtureClass(ZOMBIE, MONSTER);
        InsnList supplier = new InsnList();
        supplier.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ZOMBIE, "createMonsterAttributes", SUPPLIER_DESC, false));
        add(supplier, "MOVEMENT_SPEED", 0.23);
        supplier.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "createAttributes", SUPPLIER_DESC, supplier));

        InsnList clinit = new InsnList();
        clinit.add(new TypeInsnNode(Opcodes.NEW, MODIFIER));
        clinit.add(new InsnNode(Opcodes.DUP));
        clinit.add(new InsnNode(Opcodes.ACONST_NULL));
        clinit.add(new LdcInsnNode(0.5));
        clinit.add(new FieldInsnNode(Opcodes.GETSTATIC, OPERATION, operation, "L" + OPERATION + ";"));
        clinit.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MODIFIER, "<init>",
            "(Lnet/minecraft/resources/Identifier;DL" + OPERATION + ";)V", false));
        clinit.add(new FieldInsnNode(Opcodes.PUTSTATIC, ZOMBIE, "SPEED_MODIFIER_BABY", "L" + MODIFIER + ";"));
        clinit.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method(Opcodes.ACC_STATIC, "<clinit>", "()V", clinit));

        // if (isBaby) getAttribute(MOVEMENT_SPEED).addTransientModifier(SPEED_MODIFIER_BABY)
        InsnList baby = new InsnList();
        LabelNode done = new LabelNode();
        baby.add(new VarInsnNode(Opcodes.ALOAD, 0));
        baby.add(new FieldInsnNode(Opcodes.GETSTATIC, ATTRIBUTES, "MOVEMENT_SPEED", HOLDER));
        baby.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ZOMBIE, "getAttribute", "(" + HOLDER + ")L" + INSTANCE + ";", false));
        baby.add(new VarInsnNode(Opcodes.ASTORE, 2));
        baby.add(new VarInsnNode(Opcodes.ILOAD, 1));
        baby.add(new JumpInsnNode(Opcodes.IFEQ, done));
        baby.add(new VarInsnNode(Opcodes.ALOAD, 2));
        baby.add(new FieldInsnNode(Opcodes.GETSTATIC, ZOMBIE, "SPEED_MODIFIER_BABY", "L" + MODIFIER + ";"));
        baby.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INSTANCE, "addTransientModifier", "(L" + MODIFIER + ";)V", false));
        baby.add(done);
        baby.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method(0, "setBaby", "(Z)V", baby));
        node.methods.add(method(0, "randomizeAttributes", "(Lnet/minecraft/util/RandomSource;)V", rolling("MAX_HEALTH")));
        return node;
    }

    /** The horse: the living creator as its supplier, and a spawn roll of its movement speed. */
    private static @NotNull ClassNode horse() {
        ClassNode node = fixtureClass(HORSE, LIVING);
        node.methods.add(method(0, "randomizeAttributes", "(Lnet/minecraft/util/RandomSource;)V", rolling("MOVEMENT_SPEED")));
        return node;
    }

    /** {@code getAttribute(<attribute>).setBaseValue(0.25)}. */
    private static @NotNull InsnList rolling(@NotNull String attribute) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, ATTRIBUTES, attribute, HOLDER));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "getAttribute", "(" + HOLDER + ")L" + INSTANCE + ";", false));
        code.add(new LdcInsnNode(0.25));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INSTANCE, "setBaseValue", "(D)V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    /** {@code .add(<attribute>[, value])} onto the builder the chain holds. */
    private static void add(@NotNull InsnList code, @NotNull String attribute, Double value) {
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, ATTRIBUTES, attribute, HOLDER));
        if (value != null) code.add(new LdcInsnNode(value));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BUILDER, "add",
            "(" + HOLDER + (value != null ? "D" : "") + ")" + BUILDER_REF, false));
    }

    private static @NotNull MethodNode method(int access, @NotNull String name, @NotNull String desc, @NotNull InsnList code) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | access, name, desc, null, null);
        method.instructions = code;
        return method;
    }

    private static @NotNull ClassNode fixtureClass(@NotNull String name, @NotNull String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

    private void open(@NotNull List<ClassNode> fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures-" + System.nanoTime() + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ClassNode fixture : fixtures) {
                zip.putNextEntry(new ZipEntry(fixture.name + ".class"));
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                fixture.accept(writer);
                zip.write(writer.toByteArray());
                zip.closeEntry();
            }
        }
        this.cache = ClassNodeCache.open(jar);
    }

}
