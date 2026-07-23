package lib.minecraft.refharness.sweep;

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

import lib.minecraft.refharness.EntityFrameRenderer;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public final class EntitySweep implements Sweep<EntitySweep.Subject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    private final EntityFrameRenderer frameRenderer = new EntityFrameRenderer();

    /** One canvas per family root, measured by {@link #prepare}. */
    private Map<EntityType<?>, Canvas> familyFits = Map.of();

    /**
     * One rendered entity - a type, optionally reconstructed from a variant payload.
     *
     * <p>A descriptor rather than a live entity: the entity is built inside the render, because
     * building one needs the level.
     *
     * @param type the entity type to build
     * @param payload the NBT vanilla's deserialiser applies to select a variant, or empty for the
     *                type's default appearance
     * @param qualifier the variant name appended to the reference stem, or empty for the default
     */
    public record Subject(EntityType<?> type, Optional<CompoundTag> payload, Optional<String> qualifier) {

        private static Subject plain(EntityType<?> type) {
            return new Subject(type, Optional.empty(), Optional.empty());
        }

        private static Subject variant(EntityType<?> type, CompoundTag payload, String qualifier) {
            return new Subject(type, Optional.of(payload), Optional.of(qualifier));
        }
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
                for (Variant coat : Variant.values()) {
                    CompoundTag nbt = new CompoundTag();
                    nbt.putInt("Variant", coat.getId());
                    subjects.add(Subject.variant(type, nbt, coat.getSerializedName()));
                }
            } else if (type == EntityType.MOOSHROOM) {
                // Mushroom colour is likewise an enum, persisted as a string NBT key.
                for (MushroomCow.Variant variant : new MushroomCow.Variant[]{
                    MushroomCow.Variant.RED, MushroomCow.Variant.BROWN}) {
                    CompoundTag nbt = new CompoundTag();
                    nbt.putString("Type", variant.getSerializedName());
                    subjects.add(Subject.variant(type, nbt, variant.getSerializedName()));
                }
            } else if (variantRegistry != null) {
                // Sorted so the output filenames land in a stable, alphabetised order across runs.
                Registry<?> registry = ctx.level().registryAccess().lookupOrThrow(variantRegistry);
                for (Identifier variantId : new TreeSet<>(registry.keySet())) {
                    CompoundTag nbt = new CompoundTag();
                    nbt.putString("variant", variantId.toString());
                    subjects.add(Subject.variant(type, nbt, variantId.getPath()));
                }
            } else {
                subjects.add(Subject.plain(type));
            }
        }
        LOG.info("EntitySweep built: {} subjects", subjects.size());
        return subjects;
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
        Map<EntityType<?>, Bounds> familyBounds = new HashMap<>();
        long t0 = System.nanoTime();
        int measured = 0;
        for (EntityType<?> type : selectTypes(ctx)) {
            EntityType<?> family = EntityRoster.familyRoot(type);
            ResourceKey<? extends Registry<?>> variantRegistry = EntityRoster.VARIANT_REGISTRIES.get(type);
            if (variantRegistry != null) {
                Registry<?> registry = ctx.level().registryAccess().lookupOrThrow(variantRegistry);
                for (Identifier variantId : registry.keySet()) {
                    CompoundTag nbt = new CompoundTag();
                    nbt.putString("variant", variantId.toString());
                    Bounds bounds = measure(ctx, new Subject(type, Optional.of(nbt), Optional.empty()));
                    if (bounds == null) continue;
                    familyBounds.merge(family, bounds, Bounds::union);
                    measured++;
                }
            } else {
                Bounds bounds = measure(ctx, Subject.plain(type));
                if (bounds == null) continue;
                familyBounds.merge(family, bounds, Bounds::union);
                measured++;
            }
        }

        Map<EntityType<?>, Canvas> fits = new HashMap<>();
        for (Map.Entry<EntityType<?>, Bounds> entry : familyBounds.entrySet()) {
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
        LOG.info("EntitySweep: canvas pre-pass measured {} subjects in {} families ({} ms)",
            measured, fits.size(), (System.nanoTime() - t0) / 1_000_000L);
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
        return subject.qualifier().map(key::with).orElse(key);
    }

    @Override
    public Canvas canvas(SweepContext ctx, Subject subject) {
        return familyFits.get(EntityRoster.familyRoot(subject.type()));
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

    /**
     * Builds one subject's entity, rotation-zeroed and ready to render.
     *
     * <p>A subject carrying a variant payload is reconstructed through vanilla's own deserialiser -
     * the same path a world load takes - so the result is indistinguishable from a server-spawned
     * variant pick. Plain creation does not accept NBT, which is why the payload cannot simply be
     * applied afterwards.
     */
    private static Entity build(SweepContext ctx, Subject subject) {
        Entity entity = subject.payload()
            .map(nbt -> EntityType.loadEntityRecursive(subject.type(), nbt, ctx.level(),
                EntitySpawnReason.LOAD, EntityProcessor.NOP))
            .orElseGet(() -> subject.type().create(ctx.level(), EntitySpawnReason.LOAD));
        if (entity == null) return null;
        EntitySubjects.zeroRotations(entity);
        return entity;
    }
}
