package lib.minecraft.renderer.tooling.entity;

import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.PoseFlow;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which gates a baby's toggles leave off, against synthetic renderers and models mirroring the 26.1
 * bytecode of each shape the corpus carries.
 *
 * <p>A baby option carries the toggles its own model class gates, less a gate whose flag the renderer
 * stores a literal into on the baby arm of an age test. The llama is the shape that matters: its model
 * gates the chests on {@code hasChest} as the adult's does, and its renderer stores {@code false} into
 * that flag for every baby, so copying the gate would let a selection draw a baby llama wearing chests
 * vanilla never draws. Three negatives are shapes vanilla has too - the panda selects a float on the
 * same test, the turtle pins a flag on a test of something other than the age, and the donkey copies
 * its flag with no test at all - and the fourth is the llama's store with its literal on the adult's
 * arm, so a walk that answered for any of them would drop a toggle a baby can reach.
 *
 * <p>The same age test read in an entity's {@code getAgeScale} answers the scale a baby renders at,
 * which the baby option carries for the pose flow to fold at.
 *
 * <p>And an entity can be a baby with no baby option at all: the armour stand's {@code isBaby}
 * answers {@code isSmall}, and its renderer swaps the small model in on that same flag. The read
 * fires on the four facts together and on none of the first three alone, picks the swap's baby arm
 * whichever way the test jumps, refuses where the swapped model has no layer or the entity no baby
 * age, and the size option it stamps is matched by the layer it was baked from.
 *
 * <p>A baby is drawn through the class its renderer constructs around the baby's layer, and keeps the
 * key of the class heading its mesh only where the two pose alike - one descending from the other,
 * nothing between them but constructors handing the root up - which is what keeps a baby the adult's
 * class draws on a row folded at the baby's own age.
 */
@DisplayName("a baby's toggles leave off a gate its renderer pins")
class EntityBabyPinTest {

    private static final @NotNull String ENTITY = "fx/Entity";
    private static final @NotNull String STATE = "fx/State";
    private static final @NotNull String RENDERER = "fx/Renderer";
    private static final @NotNull String BASE_RENDERER = "fx/BaseRenderer";
    private static final @NotNull String MODEL = "fx/Model";
    private static final @NotNull String SADDLE_MODEL = "fx/SaddleModel";
    private static final @NotNull String EXTRACT_DESC = "(L" + ENTITY + ";L" + STATE + ";F)V";

    /** The slot the entity arrives in, which the descriptor above puts first. */
    private static final int ENTITY_SLOT = 1;

    /** The slot the render state arrives in, second after the entity. */
    private static final int STATE_SLOT = 2;

    @TempDir
    Path tempDir;

    private ClassNodeCache cache;

    @AfterEach
    void closeFixtureJar() {
        if (this.cache != null) this.cache.close();
    }

    // ------------------------------------------------------------------------------------
    // the walk, one method at a time
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a flag stored false on the arm an age test jumps to is pinned")
    void theLlamaShapeIsAPin() {
        // LlamaRenderer: hasChest = !isBaby() && hasChest(), the baby arm being IFNE's target.
        assertEquals(Map.of("hasChest", false),
            EntityAgeAxisResolver.babyPinnedFlags(extract(guardedStore(entityTest("isBaby"), "hasChest", false))),
            "the arm an age test jumps to on a baby stores false into hasChest");
    }

    @Test
    @DisplayName("the same pin written as a select falls through into the baby arm")
    void theSelectShapeIsAPin() {
        // isBaby() ? false : hasChest(), whose baby arm is IFEQ's fall-through and reaches its store
        // through the GOTO javac closes the arm with.
        assertEquals(Map.of("hasChest", false),
            EntityAgeAxisResolver.babyPinnedFlags(extract(selectedStore(entityTest("isBaby"), Opcodes.ICONST_0,
                "hasChest", "Z"))),
            "the fall-through arm of an age test stores false into hasChest");
        assertEquals(Map.of("hasChest", false),
            EntityAgeAxisResolver.babyPinnedFlags(extract(selectedStore(stateTest(), Opcodes.ICONST_0,
                "hasChest", "Z"))),
            "and the same select asked of the render state's own isBaby is the same pin");
    }

    @Test
    @DisplayName("a figure the baby arm selects is not a pin unless it is a flag")
    void thePandaShapeIsNot() {
        // PandaRenderer: rollAmount = isBaby() ? 0f : getRollAmount(partialTick).
        assertEquals(Map.of(),
            EntityAgeAxisResolver.babyPinnedFlags(extract(selectedStore(entityTest("isBaby"), Opcodes.FCONST_0,
                "rollAmount", "F"))),
            "a float the baby arm selects gates no bone");
        // The same select into an int: its literal is an ICONST, so only the store's type says no.
        assertEquals(Map.of(),
            EntityAgeAxisResolver.babyPinnedFlags(extract(selectedStore(entityTest("isBaby"), Opcodes.ICONST_0,
                "rollCounter", "I"))),
            "an int the baby arm selects gates no bone either");
    }

    @Test
    @DisplayName("a flag stored on a test of something other than the age is not a pin")
    void theTurtleLandShapeIsNot() {
        // TurtleRenderer: isOnLand = !isInWater() && onGround() - the llama's shape on another question.
        assertEquals(Map.of(),
            EntityAgeAxisResolver.babyPinnedFlags(extract(guardedStore(entityTest("isInWater"), "isOnLand", false))),
            "a test of the water holds nothing a baby is");
    }

    @Test
    @DisplayName("a flag stored on the arm an adult takes is not a pin")
    void theAdultArmIsNot() {
        // hasChest = isBaby() && hasChest(): the literal sits on the arm the test jumps to on an adult,
        // and a baby reads its entity - so a walk reading both arms would drop a toggle a baby reaches.
        assertEquals(Map.of(),
            EntityAgeAxisResolver.babyPinnedFlags(extract(guardedStore(entityTest("isBaby"), Opcodes.IFEQ,
                "hasChest", false))),
            "the arm an age test jumps to on an adult stores nothing every baby holds");
    }

    @Test
    @DisplayName("a flag any baby arm stores true is pinned true, whatever another arm stores")
    void aTrueArmOutweighsAFalseOne() {
        // The refusal reads this answer, so a later false store must not hide an earlier true one.
        InsnList code = guardedStore(entityTest("isBaby"), "hasChest", true);
        code.add(guardedStore(entityTest("isBaby"), "hasChest", false));
        assertEquals(Map.of("hasChest", true), EntityAgeAxisResolver.babyPinnedFlags(extract(code)),
            "a flag one baby arm pins true is one every baby holds true");
    }

    @Test
    @DisplayName("a flag copied with no test is not a pin")
    void theDonkeyShapeIsNot() {
        // DonkeyRenderer: hasChest = donkey.hasChest(), which a baby reaches as an adult does.
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "hasChest", "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "hasChest", "Z"));
        assertEquals(Map.of(), EntityAgeAxisResolver.babyPinnedFlags(extract(code)),
            "a copied flag is whatever the entity holds");
    }

    // ------------------------------------------------------------------------------------
    // the renderer chain
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a pin is read off every renderer the subject's extends, not its own class alone")
    void theChainIsWalked() throws IOException {
        // The subject's renderer copies the flag and the one it extends pins it, the way
        // AgeableMobRenderer's subclasses each extract on top of what their parent extracted.
        InsnList copy = new InsnList();
        copy.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        copy.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        copy.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "hasSaddle", "()Z", false));
        copy.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, "hasSaddle", "Z"));
        open(renderer(RENDERER, BASE_RENDERER, copy),
            renderer(BASE_RENDERER, "java/lang/Object", guardedStore(entityTest("isBaby"), "hasChest", false)));

        assertEquals(Set.of("hasChest"), ageResolver().pinnedOnBaby(),
            "the parent renderer's pin holds for every subject it extracts for");
    }

    @Test
    @DisplayName("a flag pinned true on every baby refuses the flow rather than resting it hidden")
    void aTruePinRefuses() throws IOException {
        // The fold rests a flag at what the render state builds it at, so a flag every baby holds
        // true would rest hidden and the marking would drop a bone every baby draws.
        open(renderer(RENDERER, "java/lang/Object", guardedStore(entityTest("isBaby"), "hasChest", true)));

        EntityAgeAxisResolver resolver = ageResolver();
        ToolingException refusal = assertThrows(ToolingException.class, resolver::pinnedOnBaby,
            "a true pin is a rest the table cannot say");
        assertNotNull(refusal.getMessage());
        assertTrue(refusal.getMessage().contains(RENDERER), "the refusal names the renderer: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("'hasChest'"), "and the flag: " + refusal.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // the baby's age scale
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a getAgeScale falling through into its baby arm answers that arm's literal")
    void theAgeScaleSelectIsRead() {
        // Camel.getAgeScale: isBaby() ? 0.6f : 1.0f, the baby arm being IFEQ's fall-through.
        assertEquals(Optional.of(0.6f), EntityAgeAxisResolver.babyAgeScale(ageScale(Opcodes.IFEQ, 0.6f, 1f)),
            "the fall-through arm of an age test returns 0.6");
    }

    @Test
    @DisplayName("a getAgeScale jumping to its baby arm answers that arm's literal, not the first one")
    void theAgeScaleJumpArmIsRead() {
        // !isBaby() ? 1.0f : 0.6f: the adult's literal comes first in the code and the baby's sits at
        // the jump's target, so a reader taking the first float literal would answer the adult's.
        assertEquals(Optional.of(0.6f), EntityAgeAxisResolver.babyAgeScale(ageScale(Opcodes.IFNE, 0.6f, 1f)),
            "the arm an age test jumps to returns 0.6");
    }

    @Test
    @DisplayName("a getAgeScale of any other shape answers nothing")
    void anotherAgeScaleShapeIsNot() {
        // return this.scale: no age test, so no arm a baby takes.
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.GET_AGE_SCALE, "()F", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY, "scale", "F"));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        assertEquals(Optional.empty(), EntityAgeAxisResolver.babyAgeScale(method),
            "a field the method returns is no literal a baby holds");
    }

    @Test
    @DisplayName("the age scale is read off the nearest getAgeScale up the entity's chain")
    void theNearestAgeScaleIsRead() throws IOException {
        // LivingEntity declares 0.5 and a goat overrides it with 0.55; the override is the answer.
        String living = "fx/LivingEntity";
        ClassNode base = fixtureClass(living, "java/lang/Object");
        base.methods.add(ageScale(Opcodes.IFEQ, 0.5f, 1f));
        ClassNode entity = fixtureClass(ENTITY, living);
        entity.methods.add(ageScale(Opcodes.IFEQ, 0.55f, 1f));
        open(entity, base);
        assertEquals(Optional.of(0.55f), ageResolver().ageScaleOnBaby(), "the entity's own override answers");

        this.cache.close();
        open(fixtureClass(ENTITY, living), base);
        assertEquals(Optional.of(0.5f), ageResolver().ageScaleOnBaby(),
            "and an entity declaring none answers what its superclass returns");
    }

    // ------------------------------------------------------------------------------------
    // the forwarded age
    // ------------------------------------------------------------------------------------

    /** The accessor the fixture entity's {@code isBaby} forwards to, as {@code ArmorStand.isSmall}. */
    private static final @NotNull String SMALL = "isSmall";

    /** A flag of the fixture entity's other than the one its {@code isBaby} forwards to, as {@code ArmorStand.showArms}. */
    private static final @NotNull String OTHER = "showArms";

    /** The {@code ModelLayers} field the fixture renderer bakes its big model from. */
    private static final @NotNull String BIG_LAYER = "FX";

    /** The {@code ModelLayers} field the fixture renderer bakes its small model from, the one it swaps in on a flag. */
    private static final @NotNull String SMALL_LAYER = "FX_SMALL";

    @Test
    @DisplayName("a renderer swapping a model in on the flag its entity's isBaby forwards to draws that model at the baby's age")
    void theForwardedAgeIsRead() throws IOException {
        // ArmorStand.isBaby is return isSmall(); ArmorStandRenderer copies isSmall into its state,
        // swaps smallModel in on it and bakes smallModel from ARMOR_STAND_SMALL.
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFEQ, SMALL, SMALL, true));

        EntityAgeAxisResolver.ForwardedAge forwarded = EntityAgeAxisResolver.forwardedAge(this.cache, subject())
            .orElseThrow();
        assertEquals(SMALL, forwarded.accessor(), "the accessor isBaby returns");
        assertEquals(SMALL_LAYER, forwarded.layer(), "the layer the swapped-in model is baked from");
        assertEquals(RENDERER, forwarded.renderer(), "the renderer whose submit swaps it in");
        assertEquals(0.5f, forwarded.age(), "the age the entity's getAgeScale answers a baby");
    }

    @Test
    @DisplayName("a swap whose baby arm is the jump's target picks that arm's model, not the fall-through's")
    void theJumpArmOfTheSwapIsRead() throws IOException {
        // isSmall ? smallModel : bigModel spelled with IFNE puts bigModel on the fall-through, so a
        // reader taking the first model loaded would answer the big one.
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFNE, SMALL, SMALL, true));

        assertEquals(SMALL_LAYER, EntityAgeAxisResolver.forwardedAge(this.cache, subject()).orElseThrow().layer());
    }

    @Test
    @DisplayName("no one of the first three facts fires alone")
    void noFactAloneFires() throws IOException {
        // The entity answering its own baby flag, with a renderer that copies and swaps on it.
        open(fixtureClass(ENTITY, "fx/LivingEntity"), living(0.5f), standRenderer(Opcodes.IFEQ, SMALL, SMALL, true));
        assertEquals(Optional.empty(), EntityAgeAxisResolver.forwardedAge(this.cache, subject()),
            "an isBaby that forwards nowhere names no accessor to follow");

        // Each of the next two misses exactly one tie, so each holds one check of the read alone: the
        // swap reads the flag that was copied, but what was copied is not the accessor; then the
        // accessor is copied, but the swap reads another flag.
        this.cache.close();
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFEQ, OTHER, OTHER, true));
        assertEquals(Optional.empty(), EntityAgeAxisResolver.forwardedAge(this.cache, subject()),
            "a renderer copying another accessor and swapping on it swaps on something other than the age");

        this.cache.close();
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFEQ, SMALL, OTHER, true));
        assertEquals(Optional.empty(), EntityAgeAxisResolver.forwardedAge(this.cache, subject()),
            "a renderer copying the accessor and swapping on another flag swaps on something other than the age");

        this.cache.close();
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFEQ, SMALL, null, true));
        assertEquals(Optional.empty(), EntityAgeAxisResolver.forwardedAge(this.cache, subject()),
            "a renderer copying it and swapping on nothing draws one model at every age");
    }

    @Test
    @DisplayName("a model swapped in on the forwarded flag and baked from no layer refuses the flow")
    void anUnbakedSwapRefuses() throws IOException {
        open(forwardingEntity(), living(0.5f), standRenderer(Opcodes.IFEQ, SMALL, SMALL, false));

        ToolingException refusal = assertThrows(ToolingException.class,
            () -> EntityAgeAxisResolver.forwardedAge(this.cache, subject()),
            "a mesh drawn at the baby's age with no layer to match a size option by");
        assertNotNull(refusal.getMessage());
        assertTrue(refusal.getMessage().contains("smallModel"), "the refusal names the model: " + refusal.getMessage());
    }

    @Test
    @DisplayName("two models swapped in on the forwarded flag refuse the flow, one age answering one mesh")
    void twoSwappedModelsRefuse() throws IOException {
        ClassNode renderer = standRenderer(Opcodes.IFEQ, SMALL, SMALL, true);
        renderer.methods.add(swapping(Opcodes.IFNE, SMALL, "tinyModel", "(Ljava/lang/Object;)V"));
        open(forwardingEntity(), living(0.5f), renderer);

        ToolingException refusal = assertThrows(ToolingException.class,
            () -> EntityAgeAxisResolver.forwardedAge(this.cache, subject()));
        assertNotNull(refusal.getMessage());
        assertTrue(refusal.getMessage().contains("smallModel") && refusal.getMessage().contains("tinyModel"),
            "the refusal names both: " + refusal.getMessage());
    }

    @Test
    @DisplayName("the four facts with no readable baby age refuse the flow rather than filing the mesh at one")
    void anUnreadForwardedAgeRefuses() throws IOException {
        ClassNode living = fixtureClass("fx/LivingEntity", "java/lang/Object");
        MethodNode scale = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.GET_AGE_SCALE, "()F", null, null);
        scale.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        scale.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "fx/LivingEntity", "scale", "F"));
        scale.instructions.add(new InsnNode(Opcodes.FRETURN));
        living.methods.add(scale);
        open(forwardingEntity(), living, standRenderer(Opcodes.IFEQ, SMALL, SMALL, true));

        ToolingException refusal = assertThrows(ToolingException.class,
            () -> EntityAgeAxisResolver.forwardedAge(this.cache, subject()));
        assertNotNull(refusal.getMessage());
        assertTrue(refusal.getMessage().contains(SMALL_LAYER), "the refusal names the layer: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains(SMALL), "and the accessor: " + refusal.getMessage());
    }

    @Test
    @DisplayName("the age lands on the size option baked from the forwarded layer and on no other, whatever the options are called")
    void theAgeIsStampedByLayer() {
        Map<String, JsonTree> options = new LinkedHashMap<>();
        options.put("small", JsonTree.object().put("geometry", "FxModel#small"));
        options.put("medium", JsonTree.object().put("geometry", "FxModel#medium"));
        Map<String, String> layers = Map.of("small", "FX_TINY", "medium", SMALL_LAYER);
        EntityAgeAxisResolver.ForwardedAge forwarded = new EntityAgeAxisResolver.ForwardedAge(SMALL, RENDERER, SMALL_LAYER, 0.5f);

        assertEquals("medium", EntitySizeAxisResolver.stampAge(options, layers, forwarded, "minecraft:fx"),
            "the option is matched by the field it was baked from, not by its name");
        assertEquals(0.5f, options.get("medium").getFloat(PoseFlow.AGE_SCALE, Float.NaN),
            "the matched option carries the age");
        assertTrue(options.get("small").find(PoseFlow.AGE_SCALE).isEmpty(), "the other option carries none");
        assertEquals(List.of("geometry", PoseFlow.AGE_SCALE), options.get("medium").keys().toList(),
            "nothing else in the option moves");
    }

    @Test
    @DisplayName("a forwarded layer no size option is baked from refuses the flow")
    void anUnmatchedForwardedLayerRefuses() {
        Map<String, JsonTree> options = Map.of("small", JsonTree.object().put("geometry", "FxModel#small"));
        EntityAgeAxisResolver.ForwardedAge forwarded = new EntityAgeAxisResolver.ForwardedAge(SMALL, RENDERER, SMALL_LAYER, 0.5f);

        ToolingException refusal = assertThrows(ToolingException.class,
            () -> EntitySizeAxisResolver.stampAge(options, Map.of("small", "FX_TINY"), forwarded, "minecraft:fx"),
            "a mesh drawn at the baby's age that no option files would be folded at one in silence");
        assertNotNull(refusal.getMessage());
        assertTrue(refusal.getMessage().contains(SMALL_LAYER), "the refusal names the layer: " + refusal.getMessage());
    }

    // ------------------------------------------------------------------------------------
    // the exclusion
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a pinned flag leaves off its gate whether the gate names a field or a getChild target")
    void aPinnedFlagLeavesItsGateOff() throws IOException {
        open(model());
        EntityBoneResolver bones = new EntityBoneResolver(context(diagnostics()));

        assertEquals(Map.of("chest", List.of("left_chest"), "horn", List.of("left_horn")),
            toggles(bones.resolve(MODEL, null, Set.of())), "with nothing pinned, both gates name a toggle");
        assertEquals(Map.of("horn", List.of("left_horn")),
            toggles(bones.resolve(MODEL, null, Set.of("hasChest"))), "a pinned field gate goes by its flag");
        // The inline gate's toggle is the stem of the bone it targets, so only its FLAG can drop it.
        assertEquals(Map.of("chest", List.of("left_chest")),
            toggles(bones.resolve(MODEL, null, Set.of("hasLeftHorn"))),
            "and a pinned getChild gate goes by its flag too");
    }

    @Test
    @DisplayName("a pinned flag leaves off its gate whether the gate writes through a part array or a negation")
    void aPinnedFlagLeavesItsArrayAndNegatedGatesOff() throws IOException {
        // The other two shapes a gate takes, each collected apart from the field gate: a loop over a
        // ModelPart[] names no bone field, and a negation groups under its flag with the other polarity.
        open(saddleModel());
        EntityBoneResolver bones = new EntityBoneResolver(context(diagnostics()));

        assertEquals(Map.of("ridden", List.of("left_saddle_line"), "sheared", List.of("mushrooms")),
            toggles(bones.resolve(SADDLE_MODEL, null, Set.of())), "with nothing pinned, both gates name a toggle");
        assertEquals(Map.of("sheared", List.of("mushrooms")),
            toggles(bones.resolve(SADDLE_MODEL, null, Set.of("isRidden"))), "a pinned array gate goes by its flag");
        assertEquals(Map.of("ridden", List.of("left_saddle_line")),
            toggles(bones.resolve(SADDLE_MODEL, null, Set.of("isSheared"))), "and so does a pinned negated gate");
    }

    @Test
    @DisplayName("a class left gating nothing answers no node, and says which gates it dropped")
    void anEmptiedClassSaysWhatItDropped() throws IOException {
        open(model(), saddleModel());
        Diagnostics diagnostics = diagnostics();
        EntityBoneResolver bones = new EntityBoneResolver(context(diagnostics));

        assertNull(bones.resolve(MODEL, null, Set.of("hasChest", "hasLeftHorn")),
            "with both gates pinned the class declares nothing a baby can move");
        assertNull(bones.resolve(SADDLE_MODEL, null, Set.of("isRidden", "isSheared")),
            "and the same holds of a class gating through a part array and a negation");
        List<String> said = new ArrayList<>();
        for (Diagnostics.Entry entry : diagnostics.entries()) said.add(entry.message());
        for (String flag : List.of("hasChest", "hasLeftHorn", "isRidden", "isSheared"))
            assertEquals(1, said.stream().filter(line -> line.contains("'" + flag + "'")).count(),
                "the drop of '" + flag + "' is said once rather than lost in silence: " + said);
    }

    // ------------------------------------------------------------------------------------
    // the class a baby is posed through
    // ------------------------------------------------------------------------------------

    private static final @NotNull String PART = "fx/Part";
    private static final @NotNull String POSER_BASE = "fx/PoserBase";

    @Test
    @DisplayName("a subclass that only hands its root up poses as its parent does, either way round")
    void aPassThroughSubclassPosesAlike() throws IOException {
        open(poser(POSER_BASE, "java/lang/Object", false, false, false),
            poser("fx/Pass", POSER_BASE, true, false, false),
            poser("fx/Deeper", "fx/Pass", true, false, false));

        assertTrue(EntityAgeAxisResolver.posesAlike(this.cache, "fx/Pass", POSER_BASE),
            "the cow's baby class adds nothing to the adult class it draws through");
        assertTrue(EntityAgeAxisResolver.posesAlike(this.cache, POSER_BASE, "fx/Deeper"),
            "which one descends is not the question, and every class between them is read");
    }

    @Test
    @DisplayName("a static member on the way changes nothing about the pose")
    void aStaticFactoryPosesAlike() throws IOException {
        open(poser(POSER_BASE, "java/lang/Object", false, false, false),
            poser("fx/WithFactory", POSER_BASE, true, false, true));

        assertTrue(EntityAgeAxisResolver.posesAlike(this.cache, "fx/WithFactory", POSER_BASE),
            "a baby class's createBodyLayer bakes a mesh and poses nothing");
    }

    @Test
    @DisplayName("a constructor that stores anything, or an instance method, poses differently")
    void aStoreOrAnOverridePosesDifferently() throws IOException {
        open(poser(POSER_BASE, "java/lang/Object", false, false, false),
            poser("fx/Stores", POSER_BASE, false, false, false),
            poser("fx/Overrides", POSER_BASE, true, true, false),
            poser("fx/Below", "fx/Overrides", true, false, false));

        assertFalse(EntityAgeAxisResolver.posesAlike(this.cache, "fx/Stores", POSER_BASE),
            "the sniffer's baby class stores a transform in its constructor");
        assertFalse(EntityAgeAxisResolver.posesAlike(this.cache, "fx/Overrides", POSER_BASE),
            "and overrides setupAnim to play it");
        assertFalse(EntityAgeAxisResolver.posesAlike(this.cache, "fx/Below", POSER_BASE),
            "an override anywhere between the two counts, not only on the nearer class");
    }

    @Test
    @DisplayName("two classes neither of which descends from the other pose differently")
    void cousinsPoseDifferently() throws IOException {
        open(poser(POSER_BASE, "java/lang/Object", false, false, false),
            poser("fx/Left", POSER_BASE, true, false, false),
            poser("fx/Right", POSER_BASE, true, false, false));

        assertFalse(EntityAgeAxisResolver.posesAlike(this.cache, "fx/Left", "fx/Right"),
            "the drowned's baby class descends from neither class heading its mesh");
    }

    /**
     * A model class taking one part: a constructor that hands it straight up or also stores into a
     * field, optionally an instance {@code setupAnim} and a static {@code createBodyLayer}.
     */
    private static @NotNull ClassNode poser(
        @NotNull String name, @NotNull String superName, boolean handsUp, boolean setupAnim, boolean factory) {

        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        String desc = "(L" + PART + ";)V";
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if ("java/lang/Object".equals(superName)) {
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V"));
        } else {
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", desc));
        }
        if (!handsUp) {
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "transform", "Ljava/lang/Object;"));
        }
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(ctor);
        if (setupAnim) {
            MethodNode pose = new MethodNode(Opcodes.ACC_PUBLIC, "setupAnim", "(Ljava/lang/Object;)V", null, null);
            pose.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(pose);
        }
        if (factory) {
            MethodNode bake = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createBodyLayer",
                "()Ljava/lang/Object;", null, null);
            bake.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            bake.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(bake);
        }
        return node;
    }

    // ------------------------------------------------------------------------------------
    // fixture plumbing
    // ------------------------------------------------------------------------------------

    /** The fixture subject: the fixture entity, drawn by the fixture renderer. */
    private static @NotNull EntitySubject subject() {
        return new EntitySubject("minecraft:fx", ENTITY, RENDERER, List.of(), List.of(), List.of());
    }

    /** {@code fx/LivingEntity}: {@code isBaby} answering false and {@code getAgeScale} answering {@code baby} on a baby. */
    private static @NotNull ClassNode living(float baby) {
        ClassNode node = fixtureClass("fx/LivingEntity", "java/lang/Object");
        MethodNode isBaby = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.IS_BABY, "()Z", null, null);
        isBaby.instructions.add(new InsnNode(Opcodes.ICONST_0));
        isBaby.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(isBaby);
        node.methods.add(ageScale(Opcodes.IFEQ, baby, 1f));
        return node;
    }

    /** The fixture entity, whose {@code isBaby} is {@code return this.isSmall()} as {@code ArmorStand}'s is. */
    private static @NotNull ClassNode forwardingEntity() {
        ClassNode node = fixtureClass(ENTITY, "fx/LivingEntity");
        MethodNode isBaby = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.IS_BABY, "()Z", null, null);
        isBaby.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        isBaby.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, SMALL, "()Z", false));
        isBaby.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(isBaby);
        MethodNode isSmall = new MethodNode(Opcodes.ACC_PUBLIC, SMALL, "()Z", null, null);
        isSmall.instructions.add(new InsnNode(Opcodes.ICONST_0));
        isSmall.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(isSmall);
        return node;
    }

    /**
     * A renderer shaped as {@code ArmorStandRenderer}: its {@code extractRenderState} copies one of
     * the entity's flags into the state, its {@code submit} swaps {@code smallModel} or
     * {@code bigModel} into {@code model} on a flag of the state's, and its constructor bakes each
     * from a {@code ModelLayers} field.
     *
     * @param jump the swap's test - {@code IFEQ} with the small model on the fall-through, {@code IFNE}
     *     with it at the jump's target
     * @param copied the flag the extraction copies, {@link #SMALL} being the accessor
     * @param swappedOn the flag the submit swaps a model on, or {@code null} for a submit drawing the
     *     big one alone
     * @param bakes whether the constructor reads a layer for the small model, or stores it bare
     */
    private static @NotNull ClassNode standRenderer(
        int jump, @NotNull String copied, @Nullable String swappedOn, boolean bakes) {

        ClassNode node = renderer(RENDERER, "java/lang/Object", stateCopy(copied));
        node.methods.add(swappedOn != null ? swapping(jump, swappedOn, "smallModel", "(L" + STATE + ";)V") : drawingBig());

        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        InsnList build = init.instructions;
        bakeInto(build, BIG_LAYER, "bigModel");
        bakeInto(build, bakes ? SMALL_LAYER : null, "smallModel");
        build.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(init);
        return node;
    }

    /**
     * A {@code submit} of the given descriptor drawing {@code baby} while the state's {@code flag} is
     * set and {@code bigModel} otherwise, the test jumping as {@code jump} says.
     */
    private static @NotNull MethodNode swapping(
        int jump, @NotNull String flag, @NotNull String baby, @NotNull String desc) {

        String model = "L" + MODEL + ";";
        MethodNode submit = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.SUBMIT, desc, null, null);
        InsnList code = submit.instructions;
        LabelNode other = new LabelNode();
        LabelNode join = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, flag, "Z"));
        code.add(new JumpInsnNode(jump, other));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER, jump == Opcodes.IFEQ ? baby : "bigModel", model));
        code.add(new JumpInsnNode(Opcodes.GOTO, join));
        code.add(other);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER, jump == Opcodes.IFEQ ? "bigModel" : baby, model));
        code.add(join);
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER, "model", SourceClasses.Descs.ref(SourceClasses.Types.ENTITY_MODEL)));
        code.add(new InsnNode(Opcodes.RETURN));
        return submit;
    }

    /** A {@code submit} drawing {@code bigModel} at every age, swapping nothing. */
    private static @NotNull MethodNode drawingBig() {
        MethodNode submit = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.SUBMIT, "(L" + STATE + ";)V", null, null);
        InsnList code = submit.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER, "bigModel", "L" + MODEL + ";"));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER, "model", SourceClasses.Descs.ref(SourceClasses.Types.ENTITY_MODEL)));
        code.add(new InsnNode(Opcodes.RETURN));
        return submit;
    }

    /** {@code state.<flag> = entity.<flag>()}, the copy an extraction makes. */
    private static @NotNull InsnList stateCopy(@NotNull String flag) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, flag, "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, flag, "Z"));
        return code;
    }

    /** {@code this.<field> = <a model baked from ModelLayers.<layer>>}, or stored with no layer read where it is null. */
    private static void bakeInto(@NotNull InsnList code, @Nullable String layer, @NotNull String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (layer != null) {
            code.add(new FieldInsnNode(Opcodes.GETSTATIC, SourceClasses.Types.MODEL_LAYERS, layer,
                SourceClasses.Descs.ref(SourceClasses.Types.MODEL_LAYER_LOCATION)));
            code.add(new InsnNode(Opcodes.POP));
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, RENDERER, field, "L" + MODEL + ";"));
    }

    private @NotNull EntityAgeAxisResolver ageResolver() {
        EntityContext context = context(diagnostics());
        return new EntityAgeAxisResolver(context, new EntityGeometryRefResolver(context));
    }

    private static @NotNull Diagnostics diagnostics() {
        return Diagnostics.root("entityModels", Diagnostics.Output.NONE, null);
    }

    /** A context over the fixture jar, naming the fixture renderer; no index is consulted. */
    private @NotNull EntityContext context(@NotNull Diagnostics diagnostics) {
        ToolingRun run = new ToolingRun(ClientOptions.defaults(), this.cache, diagnostics);
        EntityIndexes indexes = new EntityIndexes(null, null, null, null, null, null, null, null);
        EntitySubject subject = new EntitySubject("minecraft:fx", ENTITY, RENDERER, List.of(), List.of(), List.of());
        return new EntityContext(run, indexes, subject, diagnostics);
    }

    private void open(@NotNull ClassNode @NotNull ... fixtures) throws IOException {
        Path jar = this.tempDir.resolve("fixtures.jar");
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

    /** A renderer whose {@code extractRenderState} is one body. */
    private static @NotNull ClassNode renderer(
        @NotNull String name, @NotNull String superName, @NotNull InsnList body) {

        ClassNode node = fixtureClass(name, superName);
        node.methods.add(extract(body));
        return node;
    }

    /**
     * A model gating {@code leftChest} on {@code state.hasChest} and {@code head.getChild("left_horn")}
     * on {@code state.hasLeftHorn} - the donkey's field gate and the goat's inline one, in one class.
     */
    private static @NotNull ClassNode model() {
        ClassNode node = fixtureClass(MODEL, SourceClasses.Types.ENTITY_MODEL);
        MethodNode setupAnim = new MethodNode(Opcodes.ACC_PUBLIC, "setupAnim", "(L" + STATE + ";)V", null, null);
        InsnList code = setupAnim.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MODEL, "leftChest", SourceClasses.Descs.MODEL_PART_REF));
        gate(code, "hasChest");
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MODEL, "head", SourceClasses.Descs.MODEL_PART_REF));
        code.add(new LdcInsnNode("left_horn"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SourceClasses.Types.MODEL_PART,
            SourceClasses.Methods.GET_CHILD, "(Ljava/lang/String;)" + SourceClasses.Descs.MODEL_PART_REF, false));
        gate(code, "hasLeftHorn");
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setupAnim);
        return node;
    }

    /**
     * A model gating every element of {@code ridingParts} on {@code state.isRidden} and {@code mushrooms}
     * on {@code !state.isSheared} - the equine saddle's array gate and the bogged's negated one, in one
     * class, each in the shape {@code javac} gives it.
     */
    private static @NotNull ClassNode saddleModel() {
        ClassNode node = fixtureClass(SADDLE_MODEL, SourceClasses.Types.ENTITY_MODEL);
        String parts = "ridingParts";

        // ModelPart line = root.getChild("left_saddle_line"); this.ridingParts = new ModelPart[] {line};
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(" + SourceClasses.Descs.MODEL_PART_REF + ")V",
            null, null);
        InsnList build = init.instructions;
        build.add(new VarInsnNode(Opcodes.ALOAD, 1));
        build.add(new LdcInsnNode("left_saddle_line"));
        build.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SourceClasses.Types.MODEL_PART,
            SourceClasses.Methods.GET_CHILD, "(Ljava/lang/String;)" + SourceClasses.Descs.MODEL_PART_REF, false));
        build.add(new VarInsnNode(Opcodes.ASTORE, 2));
        build.add(new VarInsnNode(Opcodes.ALOAD, 0));
        build.add(new InsnNode(Opcodes.ICONST_1));
        build.add(new TypeInsnNode(Opcodes.ANEWARRAY, SourceClasses.Types.MODEL_PART));
        build.add(new InsnNode(Opcodes.DUP));
        build.add(new InsnNode(Opcodes.ICONST_0));
        build.add(new VarInsnNode(Opcodes.ALOAD, 2));
        build.add(new InsnNode(Opcodes.AASTORE));
        build.add(new FieldInsnNode(Opcodes.PUTFIELD, SADDLE_MODEL, parts, SourceClasses.Descs.MODEL_PART_ARRAY_REF));
        build.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(init);

        MethodNode setupAnim = new MethodNode(Opcodes.ACC_PUBLIC, "setupAnim", "(L" + STATE + ";)V", null, null);
        InsnList code = setupAnim.instructions;
        // for (ModelPart part : this.ridingParts) part.visible = state.isRidden;
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SADDLE_MODEL, parts, SourceClasses.Descs.MODEL_PART_ARRAY_REF));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        gate(code, "isRidden");
        code.add(new IincInsnNode(4, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        // this.mushrooms.visible = !state.isSheared;
        LabelNode sheared = new LabelNode();
        LabelNode store = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SADDLE_MODEL, "mushrooms", SourceClasses.Descs.MODEL_PART_REF));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, "isSheared", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, sheared));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.GOTO, store));
        code.add(sheared);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(store);
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, SourceClasses.Types.MODEL_PART, "visible", "Z"));
        code.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(setupAnim);
        return node;
    }

    /** {@code <part>.visible = state.<flag>}, onto whatever part the code just pushed. */
    private static void gate(@NotNull InsnList code, @NotNull String flag) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, flag, "Z"));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, SourceClasses.Types.MODEL_PART, "visible", "Z"));
    }

    private static @NotNull ClassNode fixtureClass(@NotNull String name, @NotNull String superName) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = superName;
        return node;
    }

    /** Each toggle a {@code bones} node names, to the bones it lists; empty for no node. */
    private static @NotNull Map<String, List<String>> toggles(@Nullable JsonTree node) {
        Map<String, List<String>> toggles = new LinkedHashMap<>();
        if (node == null) return toggles;
        node.findObject("toggles").ifPresent(declared -> declared.members().forEach((name, spec) -> {
            List<String> bones = new ArrayList<>();
            spec.findArray("bones").ifPresent(named ->
                named.elements().forEach(bone -> bone.asString().ifPresent(bones::add)));
            toggles.put(name, bones);
        }));
        return toggles;
    }

    /** An {@code extractRenderState} over one body. */
    private static @NotNull MethodNode extract(@NotNull InsnList body) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.EXTRACT_RENDER_STATE,
            EXTRACT_DESC, null, null);
        method.instructions = body;
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    /**
     * A {@code getAgeScale} in the shape {@code javac} gives a select on {@code isBaby()}: with
     * {@code IFEQ} the baby's literal falls through and the adult's sits at the jump's target, and
     * with {@code IFNE} the two trade places.
     */
    private static @NotNull MethodNode ageScale(int jump, float baby, float adult) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, SourceClasses.Methods.GET_AGE_SCALE, "()F", null, null);
        LabelNode other = new LabelNode();
        LabelNode exit = new LabelNode();
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, SourceClasses.Methods.IS_BABY, "()Z", false));
        code.add(new JumpInsnNode(jump, other));
        code.add(floatPush(jump == Opcodes.IFEQ ? baby : adult));
        code.add(new JumpInsnNode(Opcodes.GOTO, exit));
        code.add(other);
        code.add(floatPush(jump == Opcodes.IFEQ ? adult : baby));
        code.add(exit);
        code.add(new InsnNode(Opcodes.FRETURN));
        return method;
    }

    /** A float literal pushed as {@code javac} pushes it - {@code FCONST_<n>} for zero, one and two, {@code LDC} otherwise. */
    private static @NotNull AbstractInsnNode floatPush(float value) {
        if (value == 0f || value == 1f || value == 2f) return new InsnNode(Opcodes.FCONST_0 + (int) value);
        return new LdcInsnNode(value);
    }

    /** {@code entity.<question>()}, the test a renderer asks of the entity. */
    private static @NotNull InsnList entityTest(@NotNull String question) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, question, "()Z", false));
        return code;
    }

    /** {@code state.isBaby}, the test asked of the render state the chain already filled. */
    private static @NotNull InsnList stateTest() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, STATE, SourceClasses.Fields.IS_BABY, "Z"));
        return code;
    }

    /**
     * {@code state.<flag> = !<test> && entity.<flag>()}, the shape {@code javac} gives the llama's
     * chest: the test jumps to the arm storing {@code literal}, and the entity's own answer is read
     * only past it.
     */
    private static @NotNull InsnList guardedStore(@NotNull InsnList test, @NotNull String flag, boolean literal) {
        return guardedStore(test, Opcodes.IFNE, flag, literal);
    }

    /**
     * The guarded store with the test's own jump named - {@code IFNE} for {@code !<test> && ...}, and
     * {@code IFEQ} for {@code <test> && ...}, whose literal sits on the arm the test is false on.
     */
    private static @NotNull InsnList guardedStore(
        @NotNull InsnList test, int jump, @NotNull String flag, boolean literal) {

        LabelNode pinned = new LabelNode();
        LabelNode store = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(test);
        code.add(new JumpInsnNode(jump, pinned));
        code.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, flag, "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, pinned));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new JumpInsnNode(Opcodes.GOTO, store));
        code.add(pinned);
        code.add(new InsnNode(literal ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(store);
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, flag, "Z"));
        return code;
    }

    /**
     * {@code state.<field> = <test> ? <literal> : entity.<field>()}, the select shape: the baby arm
     * falls through into the literal and jumps over the adult's read to the store.
     */
    private static @NotNull InsnList selectedStore(
        @NotNull InsnList test, int literal, @NotNull String field, @NotNull String desc) {

        LabelNode adult = new LabelNode();
        LabelNode store = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, STATE_SLOT));
        code.add(test);
        code.add(new JumpInsnNode(Opcodes.IFEQ, adult));
        code.add(new InsnNode(literal));
        code.add(new JumpInsnNode(Opcodes.GOTO, store));
        code.add(adult);
        code.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_SLOT));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, field, "()" + desc, false));
        code.add(store);
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, STATE, field, desc));
        return code;
    }

}
