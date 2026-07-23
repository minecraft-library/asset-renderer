package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.EntityFrameRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity sweep. Each subject is a transient entity, allocated without being added to any level, so
 * no tick, AI or movement ever runs; its rotations are zeroed so its pose comes purely from the
 * harness iso rotation; and it is rendered through the same pipeline vanilla uses for inventory
 * entity previews.
 *
 * <p>Output dimensions are <b>family-locked</b> rather than fixed. Before the first render, a
 * pre-pass measures each family's members, unions their bounds, and sizes one canvas per family with
 * the union centre as the anchor. Shared geometry then lands on identical canvas pixels across
 * family members - the cow body is the same region of bytes in the cow and mooshroom references -
 * and overlay extras such as mushrooms or a wool coat protrude into otherwise empty canvas rather
 * than squashing the body to fit.
 *
 * <p><b>The pre-pass enumeration is deliberately not the render enumeration.</b> It measures the
 * registry variants of the variant-bearing types and the plain default of everything else,
 * <em>including</em> horse and mooshroom, whose coats and colours the render pass expands but the
 * measurement pass does not. Bounds unions only ever grow, so measuring those expansions would grow
 * their families' canvases and move every horse and mooshroom reference. Converging the two lists is
 * a real improvement and a real byte move; it is not this.
 *
 * <p>Canvases are keyed by family <em>and cohort</em>. Subjects whose silhouettes are not comparable
 * must not share one: unioning a baby into its family would grow every adult reference in it, and
 * unioning an adult into the babies would leave every baby adrift in an adult-sized frame. Keying by
 * cohort keeps each group sized to itself, and is what makes adding a cohort provably unable to move
 * a reference that already exists.
 */
public final class EntitySweep implements Sweep<EntitySweep.Subject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final EntityFrameRenderer frameRenderer = new EntityFrameRenderer();

    /** One canvas per (family root, cohort), measured by {@link #prepare}. */
    private Map<CanvasKey, Canvas> familyFits = Map.of();

    /**
     * What a family canvas is measured across.
     *
     * <p>Keying by cohort as well as family is what makes a new cohort provably unable to move an
     * existing reference: every subject emitted before the age axis is in the default cohort, so that
     * cohort unions exactly the members it always did.
     *
     * @param family the family root the canvas is shared across
     * @param cohort the group within that family whose silhouettes are comparable
     */
    private record CanvasKey(EntityType<?> family, Appearance.Cohort cohort) {}

    /**
     * One rendered entity - a type, optionally reconstructed from a variant payload.
     *
     * <p>A descriptor rather than a live entity: the entity is built inside the render, because
     * building one needs the level.
     *
     * @param type the entity type to build
     * @param appearance what it should look like
     * @param qualifier the name appended to the reference stem, or empty for the bare type
     */
    public record Subject(EntityType<?> type, Appearance appearance, Optional<String> qualifier) {

        private static Subject plain(EntityType<?> type) {
            return new Subject(type, Appearance.DEFAULT, Optional.empty());
        }

        private static Subject coated(EntityType<?> type, Appearance.Coat coat) {
            return new Subject(type, Appearance.of(coat), Optional.of(coat.name()));
        }

        /** Returns this subject aged down. */
        private Subject asBaby() {
            return new Subject(type, appearance.asBaby(), qualifier);
        }

        /** The canvas group this subject is sized within. */
        private CanvasKey canvasKey() {
            return new CanvasKey(EntityRoster.familyRoot(type), appearance.cohort());
        }
    }

    /**
     * Whether this subject is the one its model renders at when nothing selects a coat.
     *
     * <p>An axis that is not the coat axis is enumerated once per model, at that model's default
     * coat, so this is what picks the single subject it hangs off.
     */
    private static boolean isDefaultAppearance(Subject subject) {
        String defaultCoat = EntityRoster.DEFAULT_COAT.get(subject.type());
        return defaultCoat == null
            ? subject.qualifier().isEmpty()
            : subject.qualifier().map(defaultCoat::equals).orElse(false);
    }

    /**
     * Whether vanilla gives this subject a baby form.
     *
     * <p>Built and discarded - the answer is a property of the type, and the render pass builds its
     * own subject anyway.
     */
    private static boolean hasBabyForm(SweepContext ctx, Subject subject) {
        Entity entity = AppearanceApplier.build(ctx, subject.type(), subject.appearance());
        return entity != null && AppearanceApplier.supportsBaby(entity);
    }

    @Override
    public String outputDir() {
        return "entities";
    }

    @Override
    public List<Subject> enumerate(SweepContext ctx) {
        List<Subject> subjects = new ArrayList<>();
        for (EntityType<?> type : selectTypes(ctx)) {
            ResourceKey<? extends Registry<?>> variantRegistry = EntityRoster.VARIANT_REGISTRIES.get(type);
            if (type == EntityType.HORSE) {
                // Horse coat colour is an enum packed into an integer NBT key rather than a
                // data-driven variant registry, so it needs its own enumeration.
                for (Variant coat : Variant.values())
                    subjects.add(Subject.coated(type,
                        Appearance.Coat.ofInt("Variant", coat.getId(), coat.getSerializedName())));
            } else if (type == EntityType.MOOSHROOM) {
                // Mushroom colour is likewise an enum, persisted as a string NBT key.
                for (MushroomCow.Variant variant : new MushroomCow.Variant[]{
                    MushroomCow.Variant.RED, MushroomCow.Variant.BROWN}) {
                    String name = variant.getSerializedName();
                    subjects.add(Subject.coated(type, Appearance.Coat.ofString("Type", name, name)));
                }
            } else if (variantRegistry != null) {
                // Sorted so the output filenames land in a stable, alphabetised order across runs.
                Registry<?> registry = ctx.level().registryAccess().lookupOrThrow(variantRegistry);
                for (Identifier variantId : new TreeSet<>(registry.keySet()))
                    subjects.add(Subject.coated(type,
                        Appearance.Coat.ofString("variant", variantId.toString(), variantId.getPath())));
            } else {
                // A family whose coats are an enum rather than a registry ships one reference, and it
                // is named after the coat it actually is rather than left bare.
                String defaultCoat = EntityRoster.DEFAULT_COAT.get(type);
                subjects.add(defaultCoat == null
                    ? Subject.plain(type)
                    : new Subject(type, Appearance.DEFAULT, Optional.of(defaultCoat)));
            }
        }
        // The age axis. Which entities have a baby form is derived from vanilla rather than
        // hand-listed - probing costs one entity construction per type and no render, and it cannot
        // fall out of date the way a table can.
        //
        // One baby per model, not one per coat. A baby is a different mesh, not a differently
        // textured one, so a second coat adds nothing a reader could not already see; the coat axis
        // is covered at adult size where the coat is the whole point. The baby is rendered at the
        // family's default coat, and named for it, because a variant family always names its coat.
        List<Subject> withBabies = new ArrayList<>(subjects);
        for (Subject subject : subjects) {
            if (!isDefaultAppearance(subject) || !hasBabyForm(ctx, subject)) continue;
            withBabies.add(subject.asBaby());
        }
        LOG.info("EntitySweep built: {} subjects ({} of them babies)",
            withBabies.size(), withBabies.size() - subjects.size());
        return withBabies;
    }

    /**
     * Selects the entity types in scope, closing the target filter over the family map first.
     *
     * <p>Canvas sizing unions every member of a family, so selecting one member alone would size
     * that family from a strict subset of its geometry and write a reference narrower than the full
     * sweep's. Selecting any member therefore pulls in every member sharing its family root.
     */
    private static List<EntityType<?>> selectTypes(SweepContext ctx) {
        List<EntityType<?>> renderable = new ArrayList<>();
        int total = 0;
        for (Holder.Reference<EntityType<?>> holder : BuiltInRegistries.ENTITY_TYPE.listElements().toList()) {
            total++;
            EntityType<?> type = holder.value();
            Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            // Every type reports the same base class in 26.1, so filter by category instead. MISC
            // holds items, vehicles, projectiles and a handful of living subclasses that are
            // deliberately not categorised as mobs - those come back through the allowlist while
            // items and lightning stay excluded.
            if (type.getCategory() == MobCategory.MISC && !EntityRoster.MISC_ALLOWLIST.contains(typeKey)) continue;
            renderable.add(type);
        }
        int living = renderable.size();

        Set<EntityType<?>> requestedFamilies = new HashSet<>();
        if (!ctx.targets().isEmpty()) {
            for (EntityType<?> type : renderable) {
                if (ctx.targets().accepts(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()))
                    requestedFamilies.add(EntityRoster.familyRoot(type));
            }
        }

        List<EntityType<?>> selected = new ArrayList<>();
        for (EntityType<?> type : renderable) {
            if (!ctx.targets().isEmpty() && !requestedFamilies.contains(EntityRoster.familyRoot(type))) continue;
            selected.add(type);
        }
        LOG.info("EntitySweep types: {} selected (total={}, living={})", selected.size(), total, living);
        return selected;
    }

    /**
     * Measures one canvas per family.
     *
     * <p>Deliberately re-derives its own measurement list rather than reading the flattened work
     * list - see the class doc for why that distinction is load-bearing.
     */
    @Override
    public void prepare(SweepContext ctx, List<Subject> subjects) {
        Map<CanvasKey, Bounds> familyBounds = new HashMap<>();
        long t0 = System.nanoTime();
        int measured = 0;
        for (EntityType<?> type : selectTypes(ctx)) {
            ResourceKey<? extends Registry<?>> variantRegistry = EntityRoster.VARIANT_REGISTRIES.get(type);
            List<Subject> measuredSubjects = new ArrayList<>();
            if (variantRegistry != null) {
                Registry<?> registry = ctx.level().registryAccess().lookupOrThrow(variantRegistry);
                for (Identifier variantId : registry.keySet())
                    measuredSubjects.add(Subject.coated(type,
                        Appearance.Coat.ofString("variant", variantId.toString(), variantId.getPath())));
            } else {
                measuredSubjects.add(Subject.plain(type));
            }
            for (Subject subject : measuredSubjects) {
                Bounds bounds = measure(ctx, subject);
                if (bounds == null) continue;
                familyBounds.merge(subject.canvasKey(), bounds, Bounds::union);
                measured++;
                // The baby of a measured subject is measured too - its mesh is a different shape
                // rather than a smaller one, so its extent cannot be derived from the adult's.
                if (!isDefaultAppearance(subject) || !hasBabyForm(ctx, subject)) continue;
                Subject baby = subject.asBaby();
                Bounds babyBounds = measure(ctx, baby);
                if (babyBounds == null) continue;
                familyBounds.merge(baby.canvasKey(), babyBounds, Bounds::union);
                measured++;
            }
        }

        // A baby's canvas is seeded from its family's adult canvas and then grown by the baby itself.
        // That is not a stylistic choice: the asset-renderer sizes a baby against the union of the
        // baby geometry, every adult coat, and every group member's adult default, so a harness that
        // sized babies against babies alone would produce a reference of different dimensions and the
        // comparison would measure framing rather than the render.
        for (Map.Entry<CanvasKey, Bounds> entry : Map.copyOf(familyBounds).entrySet()) {
            if (entry.getKey().cohort() != Appearance.Cohort.BABY) continue;
            Bounds adult = familyBounds.get(new CanvasKey(entry.getKey().family(), Appearance.Cohort.DEFAULT));
            if (adult != null) familyBounds.merge(entry.getKey(), adult, Bounds::union);
        }

        Map<CanvasKey, Canvas> fits = new HashMap<>();
        for (Map.Entry<CanvasKey, Bounds> entry : familyBounds.entrySet()) {
            Bounds b = entry.getValue();
            int canvasW = Math.max(1, (int) Math.ceil(b.width() * HarnessConfig.PIXELS_PER_BLOCK));
            int canvasH = Math.max(1, (int) Math.ceil(b.height() * HarnessConfig.PIXELS_PER_BLOCK));
            float scale = HarnessConfig.PIXELS_PER_BLOCK;
            // Cap oversized canvases (ender_dragon, full-scale wither, giant) by shrinking canvas and
            // scale together so the longer side meets the cap. The anchor is in entity-local screen
            // coords and is unaffected; only the canvas-pixel mapping changes. Within-family parity
            // still holds - every member uses the same scale - while cross-family parity above the
            // cap does not, which was already only approximate.
            int longest = Math.max(canvasW, canvasH);
            if (longest > HarnessConfig.MAX_CANVAS_SIZE) {
                float shrink = (float) HarnessConfig.MAX_CANVAS_SIZE / longest;
                canvasW = Math.max(1, (int) Math.ceil(canvasW * shrink));
                canvasH = Math.max(1, (int) Math.ceil(canvasH * shrink));
                scale *= shrink;
            }
            fits.put(entry.getKey(),
                Canvas.of(canvasW, canvasH, new Canvas.Fit(scale, b.centerX(), b.centerY())));
        }
        familyFits = Map.copyOf(fits);
        LOG.info("EntitySweep: canvas pre-pass measured {} subjects in {} cohorts ({} ms)",
            measured, fits.size(), (System.nanoTime() - t0) / 1_000_000L);
        // One line per cohort. The manifest says whether the rendered bytes moved; this says whether
        // the canvas each subject was sized against is the same one, which is the claim a refactor of
        // the sizing pass actually has to make.
        fits.entrySet().stream()
            .map(fit -> String.format("  fit %s/%s -> %dx%d @ %.6f (%.6f, %.6f)",
                EntityType.getKey(fit.getKey().family()), fit.getKey().cohort(),
                fit.getValue().width(), fit.getValue().height(),
                fit.getValue().fit().orElseThrow().scale(),
                fit.getValue().fit().orElseThrow().anchorX(),
                fit.getValue().fit().orElseThrow().anchorY()))
            .sorted()
            .forEach(LOG::info);
    }

    private Bounds measure(SweepContext ctx, Subject subject) {
        try {
            Entity entity = build(ctx, subject);
            if (entity == null) return null;
            return frameRenderer.measureBounds(ctx.client(), entity);
        } catch (RuntimeException ex) {
            LOG.warn("EntitySweep: measureBounds failed for {}: {}", subject.type(), ex.toString());
            return null;
        }
    }

    @Override
    public RefKey key(Subject subject) {
        RefKey key = RefKey.of(BuiltInRegistries.ENTITY_TYPE.getKey(subject.type()));
        key = subject.qualifier().map(key::with).orElse(key);
        if (subject.appearance().baby()) key = key.token("age", "baby");
        return key;
    }

    @Override
    public Canvas canvas(SweepContext ctx, Subject subject) {
        return familyFits.get(subject.canvasKey());
    }

    @Override
    public boolean render(SweepContext ctx, Subject subject, Canvas canvas, Path out) throws IOException {
        if (canvas == null) {
            LOG.warn("EntitySweep: no family canvas for {} (pre-pass missed it?)", key(subject).fileName());
            return false;
        }
        Entity entity = build(ctx, subject);
        if (entity == null) {
            LOG.warn("EntitySweep: could not build {}", key(subject).fileName());
            return false;
        }
        return frameRenderer.render(ctx.client(), entity, canvas, out);
    }

    private static Entity build(SweepContext ctx, Subject subject) {
        return AppearanceApplier.build(ctx, subject.type(), subject.appearance());
    }
}
