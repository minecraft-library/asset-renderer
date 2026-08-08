package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Appearance;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.EntityFrameRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sweep over a small roster of <b>armored</b> mobs, adult and baby. The main entity sweep builds
 * every entity at its default appearance - which equips nothing and is never a baby - so worn armor
 * has no vanilla ground truth at all, and vanilla's separate baby armor model (a distinct mesh with
 * its own texture unwrap, not the adult sheet stretched onto a small body) has never been rendered
 * here.
 *
 * <p>Each subject is a transient entity, rotation-zeroed exactly as the main sweep does, then aged
 * and equipped through vanilla's own public setters before render-state extraction.
 *
 * <p>Sizing is per subject on a square canvas, deliberately <b>under</b>-filled by
 * {@link #BODY_FILL}. The bounds walker measures the body only - vanilla's armor layer holds an
 * armor model set rather than a plain model field, so the layer walk finds no mesh to expand the
 * bounds with - while the armor itself is an inflated shell that stands proud of the skin. Fitting
 * the body edge to edge would therefore crop the armor. The margin is free here: the roster is a
 * handful of one-off diagnostics rather than a byte-stable reference set, and the consuming diff
 * crops and aligns both sides by silhouette anyway.
 */
public final class ArmorSweep implements Sweep<ArmorSweep.Subject> {

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
            String base = name().toLowerCase(Locale.ROOT);
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
     *
     * @param type the entity to equip
     * @param material the armor set to put on it
     * @param baby whether to age the subject down first
     */
    public record Subject(EntityType<?> type, Material material, boolean baby) {}

    private static final List<Subject> SUBJECTS = List.of(
        new Subject(EntityType.ZOMBIE, Material.IRON, false),
        new Subject(EntityType.ZOMBIE, Material.IRON, true),
        new Subject(EntityType.ZOMBIE, Material.LEATHER, false),
        new Subject(EntityType.ZOMBIE, Material.LEATHER, true),
        new Subject(EntityType.PIGLIN, Material.IRON, false),
        new Subject(EntityType.PIGLIN, Material.IRON, true),
        new Subject(EntityType.PIGLIN, Material.LEATHER, true));

    private final EntityFrameRenderer frameRenderer = new EntityFrameRenderer();

    /** The subject built by {@link #canvas}, reused by {@link #render} so it is only equipped once. */
    private Entity prepared;

    @Override
    public String outputDir() {
        return "armor";
    }

    @Override
    public List<Subject> enumerate(SweepContext ctx) {
        LOG.info("ArmorSweep built: {} subjects", SUBJECTS.size());
        return SUBJECTS;
    }

    @Override
    public RefKey key(Subject subject) {
        RefKey key = RefKey.of(BuiltInRegistries.ENTITY_TYPE.getKey(subject.type()))
            .with(subject.material().suffix());
        return subject.baby() ? key.with("baby") : key;
    }

    /**
     * Builds, ages and equips the subject, then measures its body bounds into a canvas fit at
     * {@link #BODY_FILL}. The reserved margin is what keeps the armor shell inside the frame.
     */
    @Override
    public Canvas canvas(SweepContext ctx, Subject subject) {
        Appearance appearance = subject.baby() ? Appearance.DEFAULT.asBaby() : Appearance.DEFAULT;
        prepared = AppearanceApplier.build(ctx, subject.type(), appearance);
        if (prepared == null) return Canvas.square(HarnessConfig.IMAGE_SIZE);
        equip(prepared, subject.material());

        Bounds bounds = frameRenderer.measureBounds(ctx.client(), prepared);
        int canvas = HarnessConfig.IMAGE_SIZE;
        float width = bounds.width();
        float height = bounds.height();
        float scale = (width <= 0 || height <= 0)
            ? canvas
            : BODY_FILL * Math.min(canvas / width, canvas / height);
        return Canvas.of(canvas, canvas, new Canvas.Fit(scale, bounds.centerX(), bounds.centerY()));
    }

    @Override
    public boolean render(SweepContext ctx, Subject subject, Canvas canvas, Path out) throws IOException {
        if (prepared == null) return false;
        Entity entity = prepared;
        prepared = null;
        return frameRenderer.render(ctx.client(), entity, canvas, out);
    }

    /**
     * Returns {@code false} - the roster is hard-coded rather than drawn from a registry, so
     * honouring the allowlist would make any scoped run write no armor reference at all.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }

    /**
     * Puts a full set of {@code material} armor on the entity through
     * {@link LivingEntity#setItemSlot}, the same setter the server uses when a mob spawns with gear,
     * so the render state extracts exactly as it would in world.
     */
    private static void equip(Entity entity, Material material) {
        if (!(entity instanceof LivingEntity living)) return;
        for (EquipmentSlot slot : ARMOR_SLOTS)
            living.setItemSlot(slot, material.stack(slot));
    }

}
