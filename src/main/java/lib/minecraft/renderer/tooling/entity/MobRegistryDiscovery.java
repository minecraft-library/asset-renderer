package lib.minecraft.renderer.tooling.entity;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.tooling.util.AsmKit;
import lib.minecraft.renderer.tooling.util.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.util.Diagnostics;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Bytecode-driven discovery of every living mob entity registered by the vanilla
 * {@code net.minecraft.world.entity.EntityType} class.
 *
 * <p>Two independent walks feed the output:
 * <ol>
 *   <li><b>Field scan</b>. Every static {@code EntityType} field carries a generic signature of
 *       shape {@code Lnet/minecraft/world/entity/EntityType<Lcom/acme/Foo;>;}. Parsing that
 *       inner parameter type gives us the concrete entity class. A field's entity class is then
 *       filtered via superclass walk - only entities whose {@code net/minecraft/world/entity/LivingEntity}
 *       ancestor chain resolves cleanly survive. This exhaustively captures living mobs
 *       regardless of {@code MobCategory} - vanilla 26.1 classifies villager, iron_golem,
 *       snow_golem, and armor_stand as {@code MISC} even though three of the four are
 *       {@code LivingEntity} subclasses.</li>
 *   <li><b>{@code <clinit>} scan</b>. The registration sequence
 *       {@code LDC "<id>"} + {@code INVOKEDYNAMIC <factory>} + {@code GETSTATIC MobCategory.<CATEGORY>} +
 *       {@code INVOKESTATIC Builder.of} + ... + {@code PUTSTATIC <FIELD>} pairs each static
 *       field with its string id and spawn category.</li>
 * </ol>
 *
 * The output merges the two: every field whose concrete class extends {@code LivingEntity} and
 * whose id was successfully paired in {@code <clinit>} lands in the returned list.
 *
 * <p>Vanilla 26.1 ships 157 {@code EntityType} registrations. 89 pass the
 * {@code extends LivingEntity} filter - that set is the authoritative seed for
 * {@code entity_models.json} emission and the {@code VariantReconciler}'s mob-match predicate.
 */
@UtilityClass
public final class MobRegistryDiscovery {

    /**
     * JVM internal name of {@code net.minecraft.world.entity.EntityType$Builder}.
     */

    /**
     * JVM internal name of {@code net.minecraft.world.entity.MobCategory}.
     */

    /**
     * Name of the builder factory method: {@code EntityType$Builder.of(EntityFactory, MobCategory)}.
     */
    private static final @NotNull String BUILDER_OF = "of";

    /**
     * Field descriptor all EntityType static fields share.
     */
    private static final @NotNull String ENTITY_TYPE_DESC = "Lnet/minecraft/world/entity/EntityType;";

    /**
     * Extracts the inner generic parameter from a signature like
     * {@code Lnet/minecraft/world/entity/EntityType<LFoo;>;}. Group 1 captures the inner
     * parameter's internal name. Non-concrete parameters (wildcards, generic variables) don't
     * match and are silently skipped.
     */
    private static final @NotNull Pattern ENTITY_TYPE_SIGNATURE = Pattern.compile(
        "^Lnet/minecraft/world/entity/EntityType<L([^;<>]+);>;$"
    );

    /**
     * A discovered living-mob {@code EntityType} registration.
     *
     * @param entityId the namespaced-less registry id, e.g. {@code "villager"}, {@code "zombie"}
     * @param fieldName the {@code EntityType} static field name, e.g. {@code "VILLAGER"}, {@code "ZOMBIE"}
     * @param entityClassInternalName the concrete entity class, e.g. {@code "net/minecraft/world/entity/monster/zombie/Zombie"}
     * @param mobCategory the {@code MobCategory} enum constant name from the registration
     */
    public record MobEntry(
        @NotNull String entityId,
        @NotNull String fieldName,
        @NotNull String entityClassInternalName,
        @NotNull String mobCategory
    ) {}

    /**
     * Walks {@code EntityType}'s fields and {@code <clinit>} and emits one {@link MobEntry}
     * per registration whose concrete type extends {@code LivingEntity}, ordered by vanilla
     * static-initializer (alphabetical) order.
     *
     * @param zip the deobfuscated client jar
     * @param diagnostics the diagnostic sink; records a WARN for any registration pattern the
     *     walker fails to parse
     * @return the ordered list of living-mob entries
     */
    public static @NotNull ConcurrentList<MobEntry> discover(
        @NotNull ZipFile zip,
        @NotNull Diagnostics diagnostics
    ) {
        ClassNode entityType = AsmKit.requireClass(zip, VanillaSourceClasses.ENTITY_TYPE, "EntityType");

        Map<String, String> fieldToClass = collectEntityTypeFieldClasses(entityType);
        Map<String, Registration> registrations = collectRegistrations(entityType, diagnostics);

        ConcurrentList<MobEntry> results = Concurrent.newList();
        for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
            String fieldName = entry.getKey();
            Registration reg = entry.getValue();

            String entityClass = fieldToClass.get(fieldName);
            if (entityClass == null) {
                diagnostics.info("EntityType.%s has no concrete generic parameter (wildcard or missing signature)", fieldName);
                continue;
            }

            if (!AsmKit.extendsClass(zip, entityClass, VanillaSourceClasses.LIVING_ENTITY))
                continue;

            results.add(new MobEntry(reg.entityId, fieldName, entityClass, reg.mobCategory));
        }

        return results;
    }

    /**
     * Reads every {@code EntityType} static field's generic signature and returns a map from
     * field name to the concrete entity class's internal name. Fields whose signature uses a
     * wildcard ({@code EntityType<?>}) or a generic variable drop out - those don't correspond
     * to any one spawn-registered entity anyway.
     */
    private static @NotNull Map<String, String> collectEntityTypeFieldClasses(@NotNull ClassNode entityType) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldNode field : entityType.fields) {
            if (!ENTITY_TYPE_DESC.equals(field.desc)) continue;
            if (field.signature == null) continue;

            Matcher matcher = ENTITY_TYPE_SIGNATURE.matcher(field.signature);
            if (!matcher.matches()) continue;

            out.put(field.name, matcher.group(1));
        }
        return out;
    }

    /**
     * Walks {@code EntityType.<clinit>} and returns a map from EntityType field name to its
     * registration metadata (entity-id + MobCategory). Preserves static-initializer (vanilla
     * alphabetical) order.
     */
    private static @NotNull Map<String, Registration> collectRegistrations(
        @NotNull ClassNode entityType,
        @NotNull Diagnostics diagnostics
    ) {
        MethodNode clinit = AsmKit.findMethod(entityType, AsmKit.CLINIT);
        if (clinit == null) {
            diagnostics.error("%s has no <clinit> method", VanillaSourceClasses.ENTITY_TYPE);
            return Map.of();
        }

        Map<String, Registration> out = new LinkedHashMap<>();
        Window window = new Window();

        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            observe(insn, window);

            if (!isBuilderOfCall(insn)) continue;
            if (window.entityId == null || window.mobCategory == null) {
                diagnostics.warn("EntityType$Builder.of call without preceding entity id and MobCategory literals");
                window.resetAfterBuilder();
                continue;
            }

            String fieldName = AsmKit.findFollowingPutStatic(insn, VanillaSourceClasses.ENTITY_TYPE, MobRegistryDiscovery::isBuilderOfCall);
            if (fieldName == null) {
                diagnostics.warn("EntityType registration for id '%s' has no PUTSTATIC field", window.entityId);
                window.resetAfterBuilder();
                continue;
            }

            out.put(fieldName, new Registration(window.entityId, window.mobCategory));
            window.resetAfterBuilder();
        }

        return out;
    }

    /**
     * Updates the rolling window of the most recent entity-id LDC and MobCategory GETSTATIC
     * seen on the instruction stream. Both are zeroed by the caller after the matching
     * {@code Builder.of} call fires.
     */
    private static void observe(@NotNull AbstractInsnNode insn, @NotNull Window window) {
        String s = AsmKit.readStringLiteral(insn);
        if (s != null) window.entityId = s;

        if (AsmKit.isGetStatic(insn, VanillaSourceClasses.MOB_CATEGORY))
            window.mobCategory = ((FieldInsnNode) insn).name;
    }

    /**
     * {@code true} when {@code insn} is an {@code INVOKESTATIC EntityType$Builder.of(...)}.
     */
    private static boolean isBuilderOfCall(@NotNull AbstractInsnNode insn) {
        return AsmKit.isInvokeStatic(insn, VanillaSourceClasses.ENTITY_TYPE_BUILDER, BUILDER_OF);
    }

    /**
     * Rolling accumulator of per-registration literals consumed across {@link #collectRegistrations}.
     */
    private static final class Window {
        @Nullable String entityId;
        @Nullable String mobCategory;

        void resetAfterBuilder() {
            this.entityId = null;
            this.mobCategory = null;
        }
    }

    /**
     * {@code <clinit>}-derived registration metadata paired with each EntityType field.
     */
    private record Registration(@NotNull String entityId, @NotNull String mobCategory) {}

}
