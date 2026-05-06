package lib.minecraft.refharness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven entity sweep. Each iteration:
 * <ol>
 *   <li>Allocates a transient {@link Entity} via {@link EntityType#create(net.minecraft.world.level.Level,
 *       EntitySpawnReason) EntityType.create} - the constructor-equivalent that does
 *       <em>not</em> add the entity to any level. No tick / AI / movement ever runs.</li>
 *   <li>Zeros all rotation fields so the {@link EntityFrameRenderer} only applies the iso
 *       pose, not the entity's spawn rotation.</li>
 *   <li>Renders through {@link EntityFrameRenderer} - the same pipeline vanilla uses for
 *       inventory entity previews ({@code GuiEntityRenderer} PIP +
 *       {@link com.mojang.blaze3d.platform.Lighting.Entry#ENTITY_IN_UI ENTITY_IN_UI}
 *       lighting).</li>
 *   <li>Drops the entity (just becomes GC-eligible).</li>
 * </ol>
 *
 * <p>Output PNG sizes are <b>family-locked</b> rather than fixed. Before the first render,
 * a pre-pass measures every (entity, variant) target's screen-space bounds, groups them by
 * family root (cow + cow_cold + cow_warm + mooshroom collapse to one family via
 * {@link #FAMILY_OVERRIDES}), and unions the bounds inside each family. Every member of a
 * family then renders to a canvas sized to its family-union × {@link HarnessConfig#PIXELS_PER_BLOCK},
 * with the union centre as the canvas-centre anchor. Result: shared geometry across variants
 * lands on identical canvas pixels - cow body in {@code cow.png} is byte-for-byte the same
 * region as cow body in {@code mooshroom.png}, and overlay extras (mushrooms, chicken_cold
 * crest, sheep wool, etc.) protrude into otherwise-empty canvas space rather than squishing
 * the body to fit. The legacy fit-to-canvas mode is still reachable through
 * {@link EntityFrameRenderer#renderAndWrite(Minecraft, Entity, int, Path, Quaternionf)} for
 * the pitch-roll-sweep diagnostic.
 *
 * <p>The block sweeper still drives the camera-less PIP path through {@link ItemFrameRenderer}
 * at the fixed {@link HarnessConfig#IMAGE_SIZE}. Both phases are level-independent at render
 * time, but a {@link net.minecraft.client.multiplayer.ClientLevel} is still required as the
 * {@code Level} argument to {@code EntityType.create} (most entity constructors call
 * {@code level.registryAccess()} during init).
 */
public final class EntitySweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Allowlist of {@link MobCategory#MISC} entity types that should still be rendered
     * despite the MISC-filter exclusion. Everything in MISC is non-renderable for our
     * purposes (items, lightning, projectiles, vehicles, paintings) <em>except</em> a few
     * {@link LivingEntity} subclasses that vanilla deliberately categorises as MISC because
     * they don't behave like mobs (no AI, no spawn rules) but still have a model and a
     * renderer that produces a useful inventory portrait. Add ids here when a new such
     * entity ships - the entry must be the {@link Identifier} key of an entity type whose
     * {@link EntityType#create} returns a {@link LivingEntity}, otherwise the rest of the
     * sweeper's render path (which assumes a {@code LivingEntity} for things like body-rot
     * zeroing) will throw at runtime.
     */
    private static final Set<Identifier> MISC_ALLOWLIST = Set.of(
        Identifier.withDefaultNamespace("armor_stand")
    );

    /**
     * Entity types whose vanilla renderer dispatches to a different texture / model based
     * on a registry-driven variant ({@code CowVariant}, {@code PigVariant}, ...). For each
     * type listed here the sweeper enumerates every variant in the corresponding registry
     * and writes one PNG per {@code (entity, variant)} pair instead of just the type's
     * default - this is what produces the {@code minecraft__cow_warm.png} / {@code _cold.png}
     * companions to the plain {@code minecraft__cow.png} reference render.
     * <p>
     * The variant is set via NBT (the field name {@code "variant"} is what
     * {@code VariantUtils.readVariant} keys off of) and the entity is reconstructed through
     * {@link EntityType#loadEntityRecursive(EntityType, CompoundTag, net.minecraft.world.level.Level,
     * EntitySpawnReason, EntityProcessor) loadEntityRecursive} so vanilla's deserialiser runs
     * its tag→holder lookup and applies the variant before render-state extraction. Plain
     * {@link EntityType#create} doesn't accept NBT and would just hand back the default
     * variant, which is what the pre-task-#2 sweeper produced.
     * <p>
     * Adding a new variant-bearing entity is one line here plus its {@code Registries.X_VARIANT}
     * import - no other changes needed.
     */
    private static final Map<EntityType<?>, ResourceKey<? extends Registry<?>>> VARIANT_REGISTRIES = Map.of(
        EntityType.COW, Registries.COW_VARIANT,
        EntityType.PIG, Registries.PIG_VARIANT,
        EntityType.CHICKEN, Registries.CHICKEN_VARIANT,
        EntityType.FROG, Registries.FROG_VARIANT,
        EntityType.WOLF, Registries.WOLF_VARIANT
    );

    /**
     * Cross-{@link EntityType} family overrides for the family-locked sizing pre-pass.
     * Maps a "secondary" entity type to the "primary" entity type whose family it should
     * share (and thus whose canvas + scale + anchor it renders with). Mooshroom is the
     * canonical case: it's a separate {@code EntityType} from cow, but uses the cow model
     * for its body, so visually it should render at the same scale as cow / cow_cold /
     * cow_warm with the mushroom overlays protruding into otherwise-empty top canvas space.
     * <p>
     * Variants of the same {@code EntityType} (cow_cold, cow_warm, ...) are family-grouped
     * automatically since they all key on {@code EntityType.COW} - no override needed.
     * Add an entry here only when two distinct {@code EntityType} values should visually
     * group. Other candidates if vanilla adds them: {@code zombified_piglin → piglin},
     * {@code wither_skeleton → skeleton}, {@code husk → zombie}.
     */
    private static final Map<EntityType<?>, EntityType<?>> FAMILY_OVERRIDES = Map.of(
        EntityType.MOOSHROOM, EntityType.COW
    );

    /**
     * Where each entity is positioned at construction time. The entity is never added to
     * the level, so this is purely a placeholder for {@code Entity.setPos}; the renderer
     * uses {@code (0, 0, 0)} as its anchor regardless.
     */
    static final net.minecraft.core.BlockPos SPAWN_POS = new net.minecraft.core.BlockPos(0, 300, 0);

    private final List<EntityType<?>> targets;
    private final EntityFrameRenderer frameRenderer;
    private Map<EntityType<?>, EntityFrameRenderer.FamilyFit> familyFits;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private EntitySweeper(List<EntityType<?>> targets) {
        this.targets = targets;
        this.frameRenderer = new EntityFrameRenderer();
        this.index = 0;
    }

    public static EntitySweeper build() {
        Set<String> filter = HarnessConfig.TARGETS.isBlank()
            ? Set.of()
            : Set.of(HarnessConfig.TARGETS.split("\\s*,\\s*"));

        List<EntityType<?>> selected = new ArrayList<>();
        int total = 0;
        int living = 0;
        for (Holder.Reference<EntityType<?>> holder : BuiltInRegistries.ENTITY_TYPE.listElements().toList()) {
            total++;
            EntityType<?> type = holder.value();
            Identifier typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            // 26.1's EntityType.getBaseClass() returns Entity for all types; filter by
            // MobCategory != MISC instead. MISC contains items, vehicles, projectiles, and
            // a handful of LivingEntity subclasses (ArmorStand, ...) that are deliberately
            // not categorised as mobs - those go through MISC_ALLOWLIST so they still get
            // rendered while item/lightning/etc are still excluded.
            if (type.getCategory() == MobCategory.MISC && !MISC_ALLOWLIST.contains(typeKey)) continue;
            living++;

            String id = typeKey.toString();
            if (!filter.isEmpty() && !filter.contains(id)) continue;

            selected.add(type);
        }
        LOG.info("EntitySweeper built: {} targets (total={}, living={}, filter='{}')",
            selected.size(), total, living, HarnessConfig.TARGETS);
        return new EntitySweeper(selected);
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (client.level == null) return;
        // Pre-pass: build every (entity, variant) target once, measure its screen bounds,
        // group by family root (mooshroom rolls into cow's family via FAMILY_OVERRIDES,
        // variants automatically share their EntityType's family), and union per-family.
        // Each family then resolves to a single FamilyFit that every member of that family
        // renders with - constant scale + canvas across the family means shared geometry
        // is byte-identical across variants. Skipped when running the pitch-roll-sweep
        // diagnostic (which deliberately uses fit-to-canvas-per-frame, not family-locked).
        if (familyFits == null && !HarnessConfig.PITCH_ROLL_SWEEP) {
            familyFits = computeFamilyFits(client);
        }
        if (index >= targets.size()) { finish(); return; }

        EntityType<?> type = targets.get(index);
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String safeName = id.getNamespace() + "__" + id.getPath();

        try {
            ResourceKey<? extends Registry<?>> variantRegistryKey = VARIANT_REGISTRIES.get(type);
            if (variantRegistryKey != null && !HarnessConfig.PITCH_ROLL_SWEEP) {
                int variantsRendered = renderAllVariants(client, type, variantRegistryKey, safeName);
                if (variantsRendered == 0) {
                    LOG.warn("EntitySweeper: no variants rendered for {} (registry empty?)", id);
                    skipped++;
                } else {
                    rendered += variantsRendered;
                }
            } else {
                Entity entity = type.create(client.level, EntitySpawnReason.LOAD);
                if (entity == null) {
                    LOG.warn("EntitySweeper: type.create returned null for {}", id);
                    skipped++;
                } else {
                    zeroRotations(entity);
                    if (HarnessConfig.PITCH_ROLL_SWEEP) {
                        renderPitchRollSweep(client, entity, safeName);
                    } else {
                        Path out = HarnessConfig.OUTPUT_DIR.resolve("entities").resolve(safeName + ".png");
                        EntityFrameRenderer.FamilyFit fit = familyFits.get(familyRoot(type));
                        if (fit == null) {
                            LOG.warn("EntitySweeper: no family fit for {} (pre-pass missed it?)", id);
                            skipped++;
                        } else {
                            frameRenderer.renderAndWrite(client, entity, fit, out);
                            rendered++;
                        }
                    }
                }
            }
        } catch (IOException ex) {
            LOG.error("EntitySweeper: PNG write failed for {}", id, ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("EntitySweeper: render failed for {}", id, ex);
            failed++;
        }

        index++;
        if (index >= targets.size()) finish();
    }

    /**
     * Pre-pass: builds every (entity, variant) target once, measures bounds, groups by
     * family root, and computes a {@link EntityFrameRenderer.FamilyFit} per family.
     * <p>
     * A "family" is keyed by the family root - either the entity type itself or, for
     * cross-EntityType groupings like mooshroom, the {@link #FAMILY_OVERRIDES override}
     * target. Variants of the same EntityType automatically join the same family because
     * they all key on the same root. The family bounds are the union of every member's
     * screen-space bounds; the family canvas is {@code unionWidth × unionHeight ×
     * pixelsPerBlock} (rounded up); the family anchor is the union centre.
     * <p>
     * Failures (e.g. {@code create} returning null, an exception measuring bounds) are
     * logged and the target is skipped from the family bounds - the family canvas may end
     * up sized for fewer variants than expected, but the run continues. If a family ends
     * up with no measurable members, no fit is recorded and the main pass logs a "no
     * family fit" warning when it tries to render.
     */
    private Map<EntityType<?>, EntityFrameRenderer.FamilyFit> computeFamilyFits(Minecraft client) {
        Map<EntityType<?>, EntityFrameRenderer.EntityBounds> familyBounds = new HashMap<>();
        long t0 = System.nanoTime();
        int measured = 0;
        for (EntityType<?> type : targets) {
            ResourceKey<? extends Registry<?>> variantRegistryKey = VARIANT_REGISTRIES.get(type);
            EntityType<?> family = familyRoot(type);
            if (variantRegistryKey != null) {
                Registry<?> registry = client.level.registryAccess().lookupOrThrow(variantRegistryKey);
                for (Identifier variantId : registry.keySet()) {
                    EntityFrameRenderer.EntityBounds bounds = measureVariant(client, type, variantId);
                    if (bounds == null) continue;
                    familyBounds.merge(family, bounds, EntityFrameRenderer.EntityBounds::union);
                    measured++;
                }
            } else {
                EntityFrameRenderer.EntityBounds bounds = measureType(client, type);
                if (bounds == null) continue;
                familyBounds.merge(family, bounds, EntityFrameRenderer.EntityBounds::union);
                measured++;
            }
        }
        Map<EntityType<?>, EntityFrameRenderer.FamilyFit> fits = new HashMap<>();
        for (Map.Entry<EntityType<?>, EntityFrameRenderer.EntityBounds> entry : familyBounds.entrySet()) {
            EntityFrameRenderer.EntityBounds b = entry.getValue();
            int canvasW = Math.max(1, (int) Math.ceil(b.width() * HarnessConfig.PIXELS_PER_BLOCK));
            int canvasH = Math.max(1, (int) Math.ceil(b.height() * HarnessConfig.PIXELS_PER_BLOCK));
            float scale = HarnessConfig.PIXELS_PER_BLOCK;
            // Cap oversized canvases (ender_dragon, full-scale wither, giant) by uniformly
            // shrinking the canvas + scale so the longer side equals MAX_CANVAS_SIZE. The
            // anchor is in entity-local screen coords and unaffected by canvas size; only
            // the canvas-pixel mapping changes. Within-family parity still holds (every
            // member of the family uses the same scale); cross-family parity above the cap
            // does not, but that was already only approximate.
            int longest = Math.max(canvasW, canvasH);
            if (longest > HarnessConfig.MAX_CANVAS_SIZE) {
                float shrink = (float) HarnessConfig.MAX_CANVAS_SIZE / longest;
                canvasW = Math.max(1, (int) Math.ceil(canvasW * shrink));
                canvasH = Math.max(1, (int) Math.ceil(canvasH * shrink));
                scale *= shrink;
            }
            float anchorX = (b.minX() + b.maxX()) / 2.0f;
            float anchorY = (b.minY() + b.maxY()) / 2.0f;
            fits.put(entry.getKey(), new EntityFrameRenderer.FamilyFit(canvasW, canvasH, scale, anchorX, anchorY));
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("EntitySweeper: family-fit pre-pass measured {} (entity, variant) pairs in {} families ({} ms)",
            measured, fits.size(), elapsedMs);
        return fits;
    }

    private EntityFrameRenderer.EntityBounds measureType(Minecraft client, EntityType<?> type) {
        try {
            Entity entity = type.create(client.level, EntitySpawnReason.LOAD);
            if (entity == null) return null;
            zeroRotations(entity);
            return frameRenderer.measureBounds(client, entity);
        } catch (RuntimeException ex) {
            LOG.warn("EntitySweeper: measureBounds failed for {}: {}", type, ex.toString());
            return null;
        }
    }

    private EntityFrameRenderer.EntityBounds measureVariant(Minecraft client, EntityType<?> type, Identifier variantId) {
        try {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("variant", variantId.toString());
            Entity entity = EntityType.loadEntityRecursive(type, nbt, client.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity == null) return null;
            zeroRotations(entity);
            return frameRenderer.measureBounds(client, entity);
        } catch (RuntimeException ex) {
            LOG.warn("EntitySweeper: measureBounds failed for {} variant {}: {}", type, variantId, ex.toString());
            return null;
        }
    }

    private static EntityType<?> familyRoot(EntityType<?> type) {
        return FAMILY_OVERRIDES.getOrDefault(type, type);
    }

    /**
     * Renders one PNG per registered variant of a variant-bearing entity type. The entity
     * is reconstructed for each variant via
     * {@link EntityType#loadEntityRecursive(EntityType, CompoundTag, net.minecraft.world.level.Level,
     * EntitySpawnReason, EntityProcessor) loadEntityRecursive} with an NBT
     * {@code {"variant": "<ns>:<id>"}} payload - that's the same code path vanilla uses on
     * world-load to apply the persisted variant, so the resulting entity is fully indistinguishable
     * from a server-spawned variant pick.
     * <p>
     * Variants are walked in sorted-id order so the output filenames have stable, alphabetised
     * order across runs (helps diff-based regression checks). Each PNG lands at
     * {@code entities/<ns>__<entity>_<variantPath>.png}; the variant's namespace is dropped
     * from the filename because every vanilla variant lives in {@code minecraft:*} - the day a
     * non-vanilla variant ships, this method should switch to {@code _<ns>_<path>}.
     *
     * @return the number of variants successfully rendered
     */
    private int renderAllVariants(
        Minecraft client,
        EntityType<?> type,
        ResourceKey<? extends Registry<?>> variantRegistryKey,
        String baseName
    ) throws IOException {
        Registry<?> registry = client.level.registryAccess().lookupOrThrow(variantRegistryKey);
        EntityFrameRenderer.FamilyFit fit = familyFits.get(familyRoot(type));
        if (fit == null) {
            LOG.warn("EntitySweeper: no family fit for variant entity {} (pre-pass missed it?)", type);
            return 0;
        }
        int success = 0;
        for (Identifier variantId : new TreeSet<>(registry.keySet())) {
            CompoundTag nbt = new CompoundTag();
            nbt.putString("variant", variantId.toString());
            Entity entity = EntityType.loadEntityRecursive(type, nbt, client.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity == null) {
                LOG.warn("EntitySweeper: loadEntityRecursive returned null for {} variant={}", baseName, variantId);
                continue;
            }
            zeroRotations(entity);
            String safeName = baseName + "_" + variantId.getPath();
            Path out = HarnessConfig.OUTPUT_DIR.resolve("entities").resolve(safeName + ".png");
            frameRenderer.renderAndWrite(client, entity, fit, out);
            success++;
        }
        return success;
    }

    /**
     * Diagnostic: renders the entity at every {@code (pitch, roll)} combination, both
     * stepped 0° to 345° in 15° increments (24 × 24 = 576 outputs). Yaw is held at the
     * {@code ISO_ROTATION}-locked value. Each PNG is named
     * {@code <safeName>_pNNN_rNNN.png} so a file browser sorted by name shows pitch as
     * the outer dimension and roll as the inner dimension.
     * <p>
     * Uses the legacy fit-to-canvas mode (square {@link HarnessConfig#IMAGE_SIZE} canvas,
     * scaled to the entity's local bounds) because the whole point of the sweep is to see
     * the full silhouette at every angle - family-locked sizing would crop frames where
     * the model rotates outside the family bounds.
     */
    private void renderPitchRollSweep(Minecraft client, Entity entity, String safeName) throws IOException {
        Path dir = HarnessConfig.OUTPUT_DIR.resolve("entities-pitch-roll-sweep");
        org.joml.Vector3f euler = new org.joml.Vector3f();
        EntityFrameRenderer.ISO_ROTATION.getEulerAnglesXYZ(euler);
        float yawRad = euler.y;
        int frames = 0;
        for (int pitchDeg = 0; pitchDeg < 360; pitchDeg += 15) {
            for (int rollDeg = 0; rollDeg < 360; rollDeg += 15) {
                Quaternionf rot = new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(pitchDeg),
                    yawRad,
                    (float) Math.toRadians(rollDeg));
                String fileName = String.format("%s_p%03d_r%03d.png", safeName, pitchDeg, rollDeg);
                Path out = dir.resolve(fileName);
                frameRenderer.renderAndWrite(client, entity, HarnessConfig.IMAGE_SIZE, out, rot);
                frames++;
            }
        }
        LOG.info("EntitySweeper: pitch-roll sweep done for {} ({} frames -> {})", safeName, frames, dir);
    }

    /**
     * Forces the entity's rotation state to zero before render-state extraction so the
     * iso pose comes purely from {@code EntityFrameRenderer.ISO_ROTATION}, not from
     * whatever yaw / pitch the entity initialized with. Position doesn't matter (the
     * renderer anchors at origin) but sometimes initialization sets it to something
     * unexpected, so snap to {@code (0, 0, 0)} too.
     */
    private static void zeroRotations(Entity entity) {
        entity.snapTo(0, 0, 0, 0f, 0f);
        entity.setDeltaMovement(0, 0, 0);
        if (entity instanceof LivingEntity living) {
            living.setYBodyRot(0f);
            living.setYHeadRot(0f);
            living.yBodyRotO = 0f;
            living.yHeadRotO = 0f;
            living.yRotO = 0f;
            living.xRotO = 0f;
        }
    }

    private void finish() {
        LOG.info("EntitySweeper: done. rendered={}, skipped={}, failed={}, total={}",
            rendered, skipped, failed, targets.size());
        done = true;
        // Same reasoning as BlockSweeper.finish: the last entity's PNG-write callback is
        // still pending when we reach finish(), so closing the GPU textures here would
        // crash the callback. Leak ~3 MiB until JVM exit.
    }

    @Override
    public void close() {
        // Same reasoning as finish(): leak the frame renderer's textures rather than risk
        // racing in-flight readbacks.
    }
}
