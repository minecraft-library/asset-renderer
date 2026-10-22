package lib.minecraft.renderer.tooling.entity;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.gson.JsonTree;
import lib.minecraft.renderer.diagnostic.Diagnostics;
import lib.minecraft.renderer.tooling.animation.AgePace;
import lib.minecraft.renderer.tooling.animation.EntityPace;
import lib.minecraft.renderer.tooling.animation.WalkPace;
import lib.minecraft.renderer.tooling.asm.ClassKit;
import lib.minecraft.renderer.tooling.asm.ClassNodeCache;
import lib.minecraft.renderer.tooling.entity.EntityCeilingResolver.Handed;
import lib.minecraft.renderer.tooling.entity.EntityMovementSpeedResolver.MovementSpeeds;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Body;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operand;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Operands;
import lib.minecraft.renderer.tooling.entity.EntityWanderResolver.Stroll;
import lib.minecraft.renderer.tooling.entity.PaceChain.SwimFactors;
import lib.minecraft.renderer.tooling.entity.PaceChain.WalkFeed;
import lib.minecraft.renderer.tooling.exception.ToolingException;
import lib.minecraft.renderer.tooling.names.SourceClasses;
import lib.minecraft.renderer.tooling.run.ToolingRun;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Every subject's ambling and fastest walk, by age - the readers joined into an {@link EntityPace}.
 *
 * <p>Per subject: the subject whose inputs are read (its own, or the one {@link EntityPacePolicies}
 * says it borrows); that subject's movement speed, rolled at spawn or not; its idle stroll; its walk
 * feed and the move control its constructor chain installs; and the chain the control walks it on -
 * the ground where the control defers to the base one, the medium the policy names where it does not.
 * Each age the model row offers is then paced on those inputs, a baby on its own movement speed.
 *
 * <p>The fastest walk is paced on the same inputs at the largest speed setting the subject's own
 * behaviours reach at that age, never under the stroll's, with the policy's ceiling rows applied where
 * the jar cannot say: a parameter-borne setting, an attack boost, a targeted base, a medium, a velocity.
 *
 * <p>A subject left without a pace is not logged one by one: one census line names them all, each with
 * why. A second names every side whose fastest walk a policy row decides.
 */
@UtilityClass
public final class EntityPaceResolver {

    private static final @NotNull String ADULT = "adult";
    private static final @NotNull String BABY = "baby";

    /** The descriptor of {@code updateWalkAnimation}. */
    private static final @NotNull String FEED_DESC = "(F)V";

    /** The descriptor of {@code Math.min} over two floats. */
    private static final @NotNull String CAP_DESC = "(FF)F";

    /** The descriptor of {@code WalkAnimationState.update}. */
    private static final @NotNull String CLOCK_DESC = "(FFF)V";

    /** The descriptor of a no-argument void member. */
    private static final @NotNull String NO_ARG_VOID = "()V";

    /** The descriptor of a no-argument flag. */
    private static final @NotNull String FLAG_DESC = "()Z";

    /** The descriptor of the field a move control is installed in. */
    private static final @NotNull String MOVE_CONTROL_REF = SourceClasses.Descs.ref(SourceClasses.Types.MOVE_CONTROL);

    /**
     * The control a subject's constructor chain installs.
     *
     * @param type the control's class
     * @param defers whether its tick reaches the base control's, which hands the navigation the plain set speed
     * @param swim the two shares a smooth swimmer is built with, where the control is one and both read
     */
    private record Control(@NotNull String type, boolean defers, @NotNull Optional<SwimFactors> swim) { }

    /**
     * What one subject is paced on.
     *
     * @param subject the subject paced
     * @param inputs the subject its inputs are read off
     * @param speeds the inputs' declared speeds
     * @param movement the movement speed an adult walks on - the supplier's, or the spawn roll's mean
     * @param stroll the idle stroll
     * @param feed the walk feed
     * @param chain the chain the wander walks on
     * @param mediumDeclared whether the policy named the chain rather than the control deciding it
     * @param swim the smooth swimmer's shares, where it is one
     */
    private record Inputs(
        @NotNull EntitySubject subject, @NotNull EntitySubject inputs, @NotNull MovementSpeeds speeds, double movement,
        @NotNull Stroll stroll, @NotNull WalkFeed feed, @NotNull PaceChain chain, boolean mediumDeclared,
        @NotNull Optional<SwimFactors> swim) {

        /**
         * Whether the inputs are another subject's.
         *
         * @return whether the subject borrows its pace
         */
        boolean borrowed() {
            return !this.subject.entityId().equals(this.inputs.entityId());
        }
    }

    /**
     * Every subject's walks, in subject order - {@link EntityPace#NONE} where nothing resolves, so a
     * reader never meets a missing key.
     *
     * @param session the live run
     * @param subjects the subjects, in registry order
     * @param models the model table's {@code models} node, whose age axis says which ages a subject offers
     * @param diagnostics the pace scope
     * @return each subject's walks, keyed by entity id
     * @throws ToolingException if a policy row names a subject that is not one, whose control defers, that
     *     takes no pace, or a medium its subject cannot walk
     */
    public static @NotNull Map<String, EntityPace> resolve(
        @NotNull ToolingRun session, @NotNull List<EntitySubject> subjects,
        @NotNull JsonTree models, @NotNull Diagnostics diagnostics) {

        Map<String, EntitySubject> byId = new LinkedHashMap<>();
        subjects.forEach(subject -> byId.put(subject.entityId(), subject));
        Map<String, MovementSpeeds> speeds = EntityMovementSpeedResolver.resolve(session.cache(), subjects, diagnostics);

        Map<String, EntityPace> out = new LinkedHashMap<>();
        Map<String, String> unpaced = new TreeMap<>();
        Map<String, List<String>> decided = new TreeMap<>();
        for (EntitySubject subject : subjects) {
            Optional<Inputs> paced = inputs(session, subject, byId, speeds, unpaced, diagnostics);
            if (paced.isEmpty()) {
                out.put(subject.entityId(), EntityPace.NONE);
                continue;
            }
            Inputs inputs = paced.get();
            Optional<AgePace> adult = Optional.of(side(session, inputs, ADULT, decided, diagnostics));
            boolean offersBaby = models.find(subject.entityId())
                .flatMap(row -> row.findPath("axes", "age", "options", BABY))
                .isPresent();
            Optional<AgePace> baby = offersBaby ? Optional.of(side(session, inputs, BABY, decided, diagnostics)) : Optional.empty();
            out.put(subject.entityId(), new EntityPace(adult, baby));
        }

        List<EntityPacePolicies> ceilingRows = List.of(EntityPacePolicies.VILLAGER_FASTEST, EntityPacePolicies.ATTACK_SPEED_MODIFIERS,
            EntityPacePolicies.RAVAGER_TARGET_SPEED, EntityPacePolicies.CEILING_MEDIA, EntityPacePolicies.CHARGE_VELOCITY);
        for (EntityPacePolicies row : ceilingRows) {
            for (String named : row.named()) {
                if (out.getOrDefault(named, EntityPace.NONE).equals(EntityPace.NONE))
                    throw new ToolingException("Pace policy row '%s' names '%s', which takes no pace", row, named);
            }
        }

        diagnostics.info("%d of %d subject(s) take no pace: %s", unpaced.size(), subjects.size(),
            unpaced.entrySet().stream().map(entry -> entry.getKey() + " (" + entry.getValue() + ")").collect(Collectors.joining(", ")));
        diagnostics.info("%d paced side(s) take a ceiling a policy row decides: %s", decided.size(),
            decided.entrySet().stream().map(entry -> entry.getKey() + " (" + String.join(", ", entry.getValue()) + ")")
                .collect(Collectors.joining(", ")));
        return out;
    }

    // ------------------------------------------------------------------------------------
    // the inputs
    // ------------------------------------------------------------------------------------

    /**
     * What a subject is paced on, or empty with the reason recorded where it takes no pace.
     */
    private static @NotNull Optional<Inputs> inputs(
        @NotNull ToolingRun session, @NotNull EntitySubject subject, @NotNull Map<String, EntitySubject> byId,
        @NotNull Map<String, MovementSpeeds> speeds, @NotNull Map<String, String> unpaced, @NotNull Diagnostics diagnostics) {

        ClassNodeCache cache = session.cache();
        EntitySubject inputs = EntityPacePolicies.borrowedFrom(subject.entityId())
            .map(lender -> {
                EntitySubject found = byId.get(lender);
                if (found == null) {
                    throw new ToolingException("Pace policy row '%s' lends '%s' the pace of '%s', which is no subject",
                        EntityPacePolicies.BORROWED_PACES, subject.entityId(), lender);
                }
                return found;
            })
            .orElse(subject);
        String id = inputs.entityId();

        MovementSpeeds declared = speeds.get(id);
        if (declared == null) return unpaced(unpaced, subject, "no-attribute");
        double movement = declared.movement();
        if (declared.rolled()) {
            OptionalDouble mean = EntityPacePolicies.rollMeanOf(id);
            if (mean.isEmpty()) return unpaced(unpaced, subject, "unmeaned-roll");
            movement = mean.getAsDouble();
        }
        Optional<Stroll> stroll = EntityWanderResolver.stroll(session, inputs, diagnostics);
        if (stroll.isEmpty()) return unpaced(unpaced, subject, "no-stroll");
        Optional<WalkFeed> feed = walkFeed(cache, inputs.entityClass());
        if (feed.isEmpty()) return unpaced(unpaced, subject, "no-feed");

        Control control = moveControl(cache, inputs.entityClass());
        Optional<PaceChain> medium = EntityPacePolicies.mediumOf(id);
        PaceChain chain;
        if (control.defers()) {
            if (medium.isPresent()) {
                throw new ToolingException("Pace policy row '%s' names '%s', whose control '%s' defers to the base one",
                    EntityPacePolicies.PACE_MEDIA, id, ClassKit.simpleName(control.type()));
            }
            chain = PaceChain.GROUND;
        } else {
            if (medium.isEmpty()) return unpaced(unpaced, subject, "no-medium");
            chain = medium.get();
        }
        requireWalkable(chain, id, control, declared);
        return Optional.of(new Inputs(subject, inputs, declared, movement, stroll.get(), feed.get(), chain, medium.isPresent(), control.swim()));
    }

    /** Records why a subject takes no pace. */
    private static @NotNull Optional<Inputs> unpaced(
        @NotNull Map<String, String> unpaced, @NotNull EntitySubject subject, @NotNull String reason) {

        unpaced.put(subject.entityId(), reason);
        return Optional.empty();
    }

    /** Refuses a chain its subject cannot be paced on - a smooth arm without a smooth control, a flight without a flying speed. */
    private static void requireWalkable(
        @NotNull PaceChain chain, @NotNull String id, @NotNull Control control, @NotNull MovementSpeeds speeds) {

        boolean smooth = chain == PaceChain.SMOOTH_ON_LAND || chain == PaceChain.SMOOTH_SWIM;
        if (smooth && control.swim().isEmpty()) {
            throw new ToolingException("Pace policy row '%s' walks '%s' as a smooth swimmer under '%s', which is none",
                EntityPacePolicies.PACE_MEDIA, id, ClassKit.simpleName(control.type()));
        }
        if (chain == PaceChain.FLYING && speeds.flying().isEmpty()) {
            throw new ToolingException("Pace policy row '%s' flies '%s', whose supplier declares no flying speed",
                EntityPacePolicies.PACE_MEDIA, id);
        }
    }

    // ------------------------------------------------------------------------------------
    // one age
    // ------------------------------------------------------------------------------------

    /** One age's wander and ceiling, recording which policy rows decide the ceiling. */
    private static @NotNull AgePace side(
        @NotNull ToolingRun session, @NotNull Inputs inputs, @NotNull String age,
        @NotNull Map<String, List<String>> decided, @NotNull Diagnostics diagnostics) {

        boolean baby = BABY.equals(age);
        String id = inputs.inputs().entityId();
        double attribute = attribute(inputs, baby);
        WalkPace wander = inputs.chain().pace(inputs.stroll().speed(), attribute, baby, inputs.swim(), inputs.feed(),
            wanderBasis(inputs, baby, attribute));

        // The setting: a declared one, else the census's, and never under the stroll.
        Setting generic = atLeastTheStroll(inputs, EntityCeilingResolver.fastest(session, inputs.inputs(), age, diagnostics)
            .map(fastest -> new Setting(fastest.modifier(), fastest.source())));
        Setting unexcluded = atLeastTheStroll(inputs, EntityCeilingResolver.fastest(session, inputs.inputs(), age, (goal, at) -> false, diagnostics)
            .map(fastest -> new Setting(fastest.modifier(), fastest.source())));
        OptionalDouble declared = EntityPacePolicies.fastestSpeedOf(id);
        Setting setting = declared.isPresent()
            ? atLeastTheStroll(inputs, Optional.of(new Setting(declared.getAsDouble(), EntityPacePolicies.VILLAGER_FASTEST.name())))
            : generic;

        // The attribute: an adult's attack boost or targeted base, on the movement speed.
        OptionalDouble attack = baby || inputs.chain() == PaceChain.FLYING ? OptionalDouble.empty() : EntityPacePolicies.attackModifierOf(id);
        OptionalDouble target = baby || inputs.chain() == PaceChain.FLYING ? OptionalDouble.empty() : EntityPacePolicies.targetSpeedOf(id);
        double fastestAttribute = attack.isPresent() ? attribute + attack.getAsDouble()
            : target.isPresent() ? target.getAsDouble()
            : attribute;
        String boost = attack.isPresent()
            ? " (%s +%s %s)".formatted(number(attribute), number(attack.getAsDouble()), EntityPacePolicies.ATTACK_SPEED_MODIFIERS)
            : target.isPresent() ? " (%s %s)".formatted(number(attribute), EntityPacePolicies.RAVAGER_TARGET_SPEED)
            : "";

        // The medium: the one the fastest is taken in, where it is not the wander's.
        Optional<PaceChain> declaredMedium = EntityPacePolicies.ceilingMediumOf(id);
        PaceChain chain = declaredMedium.orElse(inputs.chain());
        WalkPace ceiling = chain.pace(setting.modifier(), fastestAttribute, baby, inputs.swim(), inputs.feed(),
            ceilingBasis(inputs, baby, setting, chain, declaredMedium.isPresent(), fastestAttribute, boost));

        // A row decides the ceiling where the walk without that row's input alone would differ.
        List<String> rows = new ArrayList<>();
        if (declared.isPresent() && !same(ceiling, paceAt(inputs, generic.modifier(), fastestAttribute, chain, baby)))
            rows.add(EntityPacePolicies.VILLAGER_FASTEST.name());
        if (declared.isEmpty() && !same(ceiling, paceAt(inputs, unexcluded.modifier(), fastestAttribute, chain, baby)))
            rows.add(EntityPacePolicies.CEILING_GOAL_EXCLUSIONS.name());
        if (attack.isPresent() && !same(ceiling, paceAt(inputs, setting.modifier(), attribute, chain, baby)))
            rows.add(EntityPacePolicies.ATTACK_SPEED_MODIFIERS.name());
        if (target.isPresent() && !same(ceiling, paceAt(inputs, setting.modifier(), attribute, chain, baby)))
            rows.add(EntityPacePolicies.RAVAGER_TARGET_SPEED.name());
        if (declaredMedium.isPresent() && !same(ceiling, paceAt(inputs, setting.modifier(), fastestAttribute, inputs.chain(), baby)))
            rows.add(EntityPacePolicies.CEILING_MEDIA.name());

        // The velocity: an adult's charge, where it outruns every walk - and then no walk's input decides it.
        OptionalDouble charge = baby ? OptionalDouble.empty() : EntityPacePolicies.chargeVelocityOf(id);
        if (charge.isPresent()) {
            WalkPace velocity = PaceChain.VELOCITY.pace(charge.getAsDouble(), 0, false, inputs.swim(), inputs.feed(),
                "velocity: %s (%s), %s blocks a tick, feed %s".formatted(number(charge.getAsDouble()), EntityPacePolicies.CHARGE_VELOCITY,
                    number(charge.getAsDouble()), feed(inputs.feed(), false)));
            if (faster(velocity, ceiling)) {
                ceiling = velocity;
                rows.clear();
                rows.add(EntityPacePolicies.CHARGE_VELOCITY.name());
            }
        }
        if (inputs.borrowed()) rows.addFirst(EntityPacePolicies.BORROWED_PACES.name());

        if (!rows.isEmpty()) decided.put(inputs.subject().entityId() + " " + age, rows);
        return new AgePace(wander, ceiling);
    }

    /**
     * A speed setting and the behaviour it was read off.
     *
     * @param modifier the speed modifier
     * @param source the behaviour, as the basis spells it
     */
    private record Setting(double modifier, @NotNull String source) { }

    /** A setting, or the stroll's where there is none or it is slower. */
    private static @NotNull Setting atLeastTheStroll(@NotNull Inputs inputs, @NotNull Optional<Setting> found) {
        Stroll stroll = inputs.stroll();
        if (found.isEmpty() || found.get().modifier() < stroll.speed())
            return new Setting(stroll.speed(), "stroll (" + stroll.source() + ")");
        return found.get();
    }

    /** The speed attribute one age walks on - the flying speed in flight, a baby's own movement speed where it has one. */
    private static double attribute(@NotNull Inputs inputs, boolean baby) {
        if (inputs.chain() == PaceChain.FLYING) return inputs.speeds().flying().orElseThrow();
        if (baby && inputs.speeds().babyMovement().isPresent()) return inputs.speeds().babyMovement().getAsDouble();
        return inputs.movement();
    }

    private static @NotNull WalkPace paceAt(
        @NotNull Inputs inputs, double modifier, double attribute, @NotNull PaceChain chain, boolean baby) {
        return chain.pace(modifier, attribute, baby, inputs.swim(), inputs.feed(), "");
    }

    private static boolean same(@NotNull WalkPace one, @NotNull WalkPace other) {
        return one.speed() == other.speed() && one.rate() == other.rate();
    }

    /** Whether one walk outruns another - by its clock, then by its swing. */
    private static boolean faster(@NotNull WalkPace one, @NotNull WalkPace other) {
        if (one.rate() != other.rate()) return one.rate() > other.rate();
        return one.speed() > other.speed();
    }

    // ------------------------------------------------------------------------------------
    // the walk feed and the move control
    // ------------------------------------------------------------------------------------

    /**
     * How the nearest {@code updateWalkAnimation} up a class turns a distance into the walk clock: the
     * literal the distance is multiplied by, the cap the one {@code Math.min} clamps the target at, and
     * the scale a baby's clock is read at - the literal on the baby arm of an age test where the clock's
     * third argument is one, the literal itself where it is not.
     */
    private static @NotNull Optional<WalkFeed> walkFeed(@NotNull ClassNodeCache cache, @NotNull String entityClass) {
        Optional<Body> declared = EntityWanderResolver.nearest(cache, entityClass, SourceClasses.Methods.UPDATE_WALK_ANIMATION, FEED_DESC);
        if (declared.isEmpty()) return Optional.empty();
        MethodNode feed = declared.get().method();

        List<MethodInsnNode> caps = AsmWalker.over(feed).ofType(MethodInsnNode.class)
            .where(call -> call.getOpcode() == Opcodes.INVOKESTATIC && SourceClasses.Types.JAVA_MATH.equals(call.owner)
                && SourceClasses.Methods.MIN.equals(call.name) && CAP_DESC.equals(call.desc))
            .toList();
        if (caps.size() != 1) return Optional.empty();
        Float cap = AsmWalker.floatLiteral(AsmWalker.previousReal(caps.getFirst()));
        AbstractInsnNode multiply = AsmWalker.previousReal(AsmWalker.previousReal(caps.getFirst()));
        if (cap == null || multiply == null || multiply.getOpcode() != Opcodes.FMUL) return Optional.empty();
        AbstractInsnNode gainPush = AsmWalker.previousReal(multiply);
        Float gain = AsmWalker.floatLiteral(gainPush);
        if (gain == null || !(AsmWalker.previousReal(gainPush) instanceof VarInsnNode distance)
            || distance.getOpcode() != Opcodes.FLOAD || distance.var != 1) return Optional.empty();

        return babyScale(feed).map(scale -> new WalkFeed(ClassKit.simpleName(declared.get().owner()), gain, cap, scale));
    }

    /** The scale a baby's clock is read at - the third argument the walk clock's update is handed. */
    private static @NotNull Optional<Float> babyScale(@NotNull MethodNode feed) {
        List<Operand> handed = new ArrayList<>();
        Operands.run(feed, new Operands.Listener() {
            @Override
            public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                if (SourceClasses.Types.WALK_ANIMATION_STATE.equals(call.owner) && SourceClasses.Methods.UPDATE.equals(call.name)
                    && CLOCK_DESC.equals(call.desc)) handed.add(arguments.get(2));
            }
        });
        if (handed.size() != 1) return Optional.empty();
        if (handed.getFirst() instanceof Operand.Literal literal) return Optional.of(literal.value().floatValue());
        if (!(handed.getFirst() instanceof Operand.Choice choice)) return Optional.empty();

        List<Float> babyArms = AsmWalker.over(feed).ofType(MethodInsnNode.class)
            .where(call -> SourceClasses.Methods.IS_BABY.equals(call.name) && FLAG_DESC.equals(call.desc))
            .mapNotNull(call -> {
                if (!(AsmWalker.nextReal(call) instanceof JumpInsnNode test)) return null;
                return switch (test.getOpcode()) {
                    case Opcodes.IFEQ -> AsmWalker.floatLiteral(AsmWalker.nextReal(test));
                    case Opcodes.IFNE -> AsmWalker.floatLiteral(AsmWalker.nextReal(test.label));
                    default -> null;
                };
            })
            .toList();
        if (babyArms.size() != 1 || !Operand.literalsOf(choice).contains((double) babyArms.getFirst())) return Optional.empty();
        return Optional.of(babyArms.getFirst());
    }

    /**
     * The control the subject's constructor chain installs - the class allocated into {@code moveControl}
     * by the first constructor storing one, leaf first, which is the leaf's override; {@code Mob}'s own
     * where none does.
     */
    private static @NotNull Control moveControl(@NotNull ClassNodeCache cache, @NotNull String entityClass) {
        Handed[] installed = {null};
        ClassKit.walkSuperChainUntil(cache, entityClass, node -> {
            for (MethodNode constructor : node.methods) {
                if (!ClassKit.INIT.equals(constructor.name)) continue;
                Map<TypeInsnNode, Handed> built = new HashMap<>();
                Operands.run(constructor, new Operands.Listener() {
                    @Override
                    public void call(@NotNull MethodInsnNode call, @Nullable Operand receiver, @NotNull List<Operand> arguments) {
                        if (receiver instanceof Operand.Fresh fresh && ClassKit.INIT.equals(call.name))
                            built.put(fresh.site(), new Handed(call.owner, call.desc, List.copyOf(arguments)));
                    }

                    @Override
                    public void put(@NotNull FieldInsnNode put, @NotNull Operand receiver, @NotNull Operand value) {
                        if (installed[0] == null && receiver == Operand.Self.THIS && value instanceof Operand.Fresh fresh
                            && SourceClasses.Fields.MOVE_CONTROL.equals(put.name) && MOVE_CONTROL_REF.equals(put.desc)) {
                            installed[0] = built.getOrDefault(fresh.site(),
                                new Handed(fresh.site().desc, NO_ARG_VOID, List.of()));
                        }
                    }
                });
                if (installed[0] != null) return true;
            }
            return false;
        });

        String type = installed[0] == null ? SourceClasses.Types.MOVE_CONTROL : installed[0].owner();
        Optional<SwimFactors> swim = installed[0] != null
            && ClassKit.extendsClass(cache, type, SourceClasses.Types.SMOOTH_SWIMMING_MOVE_CONTROL)
            ? swimFactors(cache, installed[0])
            : Optional.empty();
        return new Control(type, defers(cache, type, new HashSet<>()), swim);
    }

    /** Whether a control's tick reaches the base control's - it is the base, inherits its tick, or calls up to one that does. */
    private static boolean defers(@NotNull ClassNodeCache cache, @NotNull String type, @NotNull Set<String> visited) {
        if (SourceClasses.Types.MOVE_CONTROL.equals(type)) return true;
        if (!visited.add(type)) return false;
        Optional<Body> tick = EntityWanderResolver.nearest(cache, type, SourceClasses.Methods.TICK, NO_ARG_VOID);
        if (tick.isEmpty()) return false;
        if (SourceClasses.Types.MOVE_CONTROL.equals(tick.get().owner())) return true;
        MethodInsnNode up = AsmWalker.over(tick.get().method()).ofType(MethodInsnNode.class)
            .first(call -> call.getOpcode() == Opcodes.INVOKESPECIAL && SourceClasses.Methods.TICK.equals(call.name)
                && NO_ARG_VOID.equals(call.desc));
        return up != null && defers(cache, up.owner, visited);
    }

    /** The two shares a smooth swimmer is built with - the literals handed the parameters stored in its two share fields. */
    private static @NotNull Optional<SwimFactors> swimFactors(@NotNull ClassNodeCache cache, @NotNull Handed built) {
        Float inWater = null;
        Float outsideWater = null;
        for (int parameter = 0; parameter < built.arguments().size(); parameter++) {
            Set<String> fields = EntityCeilingResolver.fieldsReached(cache, built.owner(), built.desc(), parameter);
            if (!(built.arguments().get(parameter) instanceof Operand.Literal literal)) continue;
            if (fields.contains(SourceClasses.Fields.IN_WATER_SPEED_MODIFIER)) inWater = literal.value().floatValue();
            if (fields.contains(SourceClasses.Fields.OUTSIDE_WATER_SPEED_MODIFIER)) outsideWater = literal.value().floatValue();
        }
        if (inWater == null || outsideWater == null) return Optional.empty();
        return Optional.of(new SwimFactors(inWater, outsideWater));
    }

    // ------------------------------------------------------------------------------------
    // the basis
    // ------------------------------------------------------------------------------------

    /** The wander's inputs, as its diagnostics line spells them. */
    private static @NotNull String wanderBasis(@NotNull Inputs inputs, boolean baby, double attribute) {
        Stroll stroll = inputs.stroll();
        return "%s: stroll %s (%s) x %s = set %s, %s blocks a tick, feed %s".formatted(
            chainToken(inputs.chain(), inputs.mediumDeclared() ? EntityPacePolicies.PACE_MEDIA.name() : ""),
            number(stroll.speed()), stroll.source(),
            attributeText(inputs, baby, attribute, ""),
            number(PaceChain.setSpeed(stroll.speed(), attribute)),
            number(inputs.chain().distance(stroll.speed(), attribute, baby, inputs.swim())),
            feed(inputs.feed(), baby));
    }

    /** The ceiling's inputs, as its diagnostics line spells them. */
    private static @NotNull String ceilingBasis(
        @NotNull Inputs inputs, boolean baby, @NotNull Setting setting, @NotNull PaceChain chain, boolean mediumMoved,
        double attribute, @NotNull String boost) {

        String row = mediumMoved ? EntityPacePolicies.CEILING_MEDIA.name() : inputs.mediumDeclared() ? EntityPacePolicies.PACE_MEDIA.name() : "";
        return "%s: %s %s x %s = set %s, %s blocks a tick, feed %s".formatted(
            chainToken(chain, row),
            setting.source(), number(setting.modifier()),
            attributeText(inputs, baby, attribute, boost),
            number(PaceChain.setSpeed(setting.modifier(), attribute)),
            number(chain.distance(setting.modifier(), attribute, baby, inputs.swim())),
            feed(inputs.feed(), baby));
    }

    private static @NotNull String chainToken(@NotNull PaceChain chain, @NotNull String row) {
        String token = chain.name().toLowerCase(Locale.ROOT);
        return row.isEmpty() ? token : token + " (" + row + ")";
    }

    /** The speed attribute a walk reads, with every input that moved it named. */
    private static @NotNull String attributeText(@NotNull Inputs inputs, boolean baby, double attribute, @NotNull String boost) {
        String name = inputs.chain() == PaceChain.FLYING ? "flying"
            : baby && inputs.speeds().babyMovement().isPresent() ? "baby movement"
            : "movement";
        StringBuilder text = new StringBuilder(name).append(' ').append(number(attribute)).append(boost);
        if (inputs.speeds().rolled()) text.append(" (spawn roll mean)");
        if (inputs.borrowed()) text.append(" (borrowed from ").append(inputs.inputs().entityId()).append(')');
        return text.toString();
    }

    private static @NotNull String feed(@NotNull WalkFeed feed, boolean baby) {
        String text = "%s x%s cap %s".formatted(feed.declaring(), compact(feed.gain()), compact(feed.cap()));
        return baby ? text + ", baby x" + compact(feed.babyScale()) : text;
    }

    /** An input spelled locale-free to four places, so a log line reads the same on every machine. */
    private static @NotNull String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** A feed constant spelled as written - a whole number without its point. */
    private static @NotNull String compact(float value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Float.toString(value);
    }

}
