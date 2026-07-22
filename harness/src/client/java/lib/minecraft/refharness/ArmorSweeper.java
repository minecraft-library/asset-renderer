package lib.minecraft.refharness;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven sweep over a small roster of <b>armored</b> mobs, adult and baby. The main
 * {@link EntitySweeper} builds every entity at its default appearance - which equips nothing and is
 * never a baby - so worn armor has no vanilla ground truth at all, and vanilla's separate baby armor
 * model (a distinct mesh with its own {@code humanoid_baby} texture unwrap, not the adult sheet
 * stretched onto a small body) has never been rendered here.
 *
 * <p>Each subject is a transient {@link EntityType#create(net.minecraft.world.level.Level,
 * EntitySpawnReason) create}d entity, rotation-zeroed exactly as the main sweep does, then aged and
 * equipped through vanilla's own public setters before render-state extraction. Every subject is
 * rendered at the shared iso pose to {@code armor/<ns>__<id>_<material>[-dye<rgb>][_baby].png}.
 *
 * <p>Sizing is per-subject on a square {@link HarnessConfig#IMAGE_SIZE} canvas, deliberately
 * <b>under</b>-filled by {@link #BODY_FILL}. The bounds walker measures the body only - vanilla's
 * armor layer holds an {@code ArmorModelSet} rather than a plain {@code Model} field, so the layer
 * walk finds no mesh to expand the bounds with - while the armor itself is an inflated shell that
 * stands proud of the skin. Fitting the body edge-to-edge would therefore crop the armor. The
 * margin is free here: the roster is a handful of one-off diagnostics rather than a byte-stable
 * reference set, and the consuming diff crops and aligns both sides by silhouette anyway.
 */
public final class ArmorSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Leather dye applied to the dyed-leather subjects; matches the asset side's diff roster. */
    private static final int LEATHER_DYE_RGB = 0xB04030;

    /**
     * Fraction of the canvas the measured <em>body</em> bounds fill, leaving the rest as margin for
     * the armor shell the bounds walker cannot see. Vanilla's outer armor deformation is one model
     * unit per side on a body around thirty model units tall, so a tenth of the canvas is several
     * times the headroom actually needed.
     */
    private static final float BODY_FILL = 0.8f;

    /**
     * The armor roster. Each entry names an armor material by its four humanoid pieces; a subject
     * pairs one of these with an entity type and an age. Kept tiny on purpose - the point is to
     * measure how far the baby render sits from vanilla, not to enumerate materials.
     */
    private enum Material {

        IRON(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Optional.empty()),
        LEATHER(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
            Optional.of(LEATHER_DYE_RGB));

        private final Item helmet;
        private final Item chestplate;
        private final Item leggings;
        private final Item boots;
        private final Optional<Integer> dyeRgb;

        Material(Item helmet, Item chestplate, Item leggings, Item boots, Optional<Integer> dyeRgb) {
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.dyeRgb = dyeRgb;
        }

        /** Filename fragment: the lowercased material, plus the dye when one applies. */
        private String suffix() {
            String base = "_" + name().toLowerCase(Locale.ROOT);
            return dyeRgb.map(rgb -> base + String.format(Locale.ROOT, "-dye%06x", rgb)).orElse(base);
        }

        private Item forSlot(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD -> helmet;
                case CHEST -> chestplate;
                case LEGS -> leggings;
                case FEET -> boots;
                default -> throw new IllegalArgumentException("Not an armor slot: " + slot);
            };
        }

        /** Builds the stack for one slot, dyed when the material carries a dye. */
        private ItemStack stack(EquipmentSlot slot) {
            ItemStack stack = new ItemStack(forSlot(slot));
            dyeRgb.ifPresent(rgb -> stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb)));
            return stack;
        }
    }

    /** The four humanoid armor slots, in the order vanilla's armor layer submits them. */
    private static final List<EquipmentSlot> ARMOR_SLOTS =
        List.of(EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD);

    /**
     * One armored render. The adult entries are the control: any divergence they show is the armor
     * path in general, so whatever the baby entries show on top of it is the baby-model gap.
     */
    private record Subject(EntityType<?> type, Material material, boolean baby) {

        private String fileName() {
            net.minecraft.resources.Identifier id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return id.getNamespace() + "__" + id.getPath() + material.suffix() + (baby ? "_baby" : "");
        }
    }

    private static final List<Subject> SUBJECTS = List.of(
        new Subject(EntityType.ZOMBIE, Material.IRON, false),
        new Subject(EntityType.ZOMBIE, Material.IRON, true),
        new Subject(EntityType.ZOMBIE, Material.LEATHER, false),
        new Subject(EntityType.ZOMBIE, Material.LEATHER, true),
        new Subject(EntityType.PIGLIN, Material.IRON, false),
        new Subject(EntityType.PIGLIN, Material.IRON, true),
        new Subject(EntityType.PIGLIN, Material.LEATHER, true));

    private final EntityFrameRenderer frameRenderer;
    private int index;
    private int rendered;
    private int skipped;
    private int failed;
    private boolean done;

    private ArmorSweeper() {
        this.frameRenderer = new EntityFrameRenderer();
    }

    public static ArmorSweeper build() {
        LOG.info("ArmorSweeper built: {} subjects", SUBJECTS.size());
        return new ArmorSweeper();
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (client.level == null) return;
        if (index >= SUBJECTS.size()) { finish(); return; }

        Subject subject = SUBJECTS.get(index);
        try {
            Entity entity = subject.type().create(client.level, EntitySpawnReason.LOAD);
            if (entity == null) {
                LOG.warn("ArmorSweeper: type.create returned null for {}", subject.fileName());
                skipped++;
            } else {
                EntitySweeper.zeroRotations(entity);
                if (subject.baby() && !setBaby(entity)) {
                    LOG.warn("ArmorSweeper: {} has no setBaby(boolean) - skipping the baby subject",
                        subject.fileName());
                    skipped++;
                } else {
                    equip(entity, subject.material());
                    Path out = HarnessConfig.OUTPUT_DIR.resolve("armor").resolve(subject.fileName() + ".png");
                    frameRenderer.renderAndWrite(client, entity, marginFit(client, entity), out);
                    rendered++;
                }
            }
        } catch (IOException ex) {
            LOG.error("ArmorSweeper: PNG write failed for {}", subject.fileName(), ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("ArmorSweeper: render failed for {}", subject.fileName(), ex);
            failed++;
        }

        index++;
        if (index >= SUBJECTS.size()) finish();
    }

    /**
     * Builds the square canvas fit for one subject: the entity's own measured bounds scaled to
     * {@link #BODY_FILL} of an {@link HarnessConfig#IMAGE_SIZE} canvas, anchored on the bounds
     * midpoint. The reserved margin is what keeps the armor shell inside the frame.
     *
     * @param client the running client, for the render dispatcher the bounds walk goes through
     * @param entity the aged, equipped subject
     * @return the canvas fit to render it with
     */
    private EntityFrameRenderer.FamilyFit marginFit(Minecraft client, Entity entity) {
        EntityFrameRenderer.EntityBounds bounds = frameRenderer.measureBounds(client, entity);
        int canvas = HarnessConfig.IMAGE_SIZE;
        float width = bounds.width();
        float height = bounds.height();
        float scale = (width <= 0 || height <= 0)
            ? canvas
            : BODY_FILL * Math.min(canvas / width, canvas / height);
        return new EntityFrameRenderer.FamilyFit(canvas, canvas, scale,
            (bounds.minX() + bounds.maxX()) / 2.0f,
            (bounds.minY() + bounds.maxY()) / 2.0f);
    }

    /**
     * Puts a full set of {@code material} armor on the entity through
     * {@link LivingEntity#setItemSlot}, the same setter the server uses when a mob spawns with
     * gear, so the render state extracts exactly as it would in world.
     */
    private static void equip(Entity entity, Material material) {
        if (!(entity instanceof LivingEntity living)) return;
        for (EquipmentSlot slot : ARMOR_SLOTS)
            living.setItemSlot(slot, material.stack(slot));
    }

    /**
     * Ages the entity down through its own {@code setBaby(boolean)}. The method is public on every
     * humanoid that has a baby form (zombie and its variants, piglin) but is declared per class
     * with no shared supertype, so it is invoked reflectively. Returns whether the entity had one.
     */
    private static boolean setBaby(Entity entity) {
        try {
            Method setBaby = entity.getClass().getMethod("setBaby", boolean.class);
            setBaby.invoke(entity, true);
            return true;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private void finish() {
        LOG.info("ArmorSweeper: done. rendered={}, skipped={}, failed={}, total={}",
            rendered, skipped, failed, SUBJECTS.size());
        done = true;
        // Same reasoning as the other sweepers: the last render's PNG-write callback is still
        // pending, so closing the GPU textures here would crash it. Leak until JVM exit.
    }

    @Override
    public void close() {
        // Leak the frame renderer's textures rather than race in-flight readbacks (see finish()).
    }
}
