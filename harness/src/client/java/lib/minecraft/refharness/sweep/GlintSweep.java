package lib.minecraft.refharness.sweep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lib.minecraft.refharness.EntityFrameRenderer;
import lib.minecraft.refharness.GlintClock;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.ItemFrameRenderer;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Animated-glint reference sweep. Renders each glint subject as a deterministic sequence of
 * {@link #FRAME_COUNT} frames, stepping the glint clock through the shared schedule
 * {@code t_N = N * STEP_MILLIS} so every frame's glint phase matches the asset-renderer side. One
 * PNG per frame lands under a per-subject directory.
 *
 * <p>Two subject kinds:
 * <ul>
 *   <li><b>GUI items</b> - the 7 always-foil items (enchanted_book, written_book, ...). They glint
 *       intrinsically, so a plain stack through the vanilla GUI item pipeline shows the item
 *       glint.</li>
 *   <li><b>Worn leather armor</b> (diagnostic) - an armor stand equipped with one glint-forced
 *       leather piece, rendered at the entity iso pose so the distinct <em>armor</em> glint fires.
 *       Byte-parity with the asset side is out of scope, since the pose and model both differ; this
 *       exists so the armor-glint animation can be eyeballed side by side.</li>
 * </ul>
 *
 * <p><b>The {@link #FRAME_COUNT} and {@link #STEP_MILLIS} constants must match the asset-renderer
 * glint parity sweep.</b>
 */
public final class GlintSweep implements Sweep<GlintSweep.Frame> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Frames per glint subject. MUST match the asset-renderer glint parity frame count. */
    public static final int FRAME_COUNT = 30;

    /**
     * Glint-time step, in vanilla post-speed milliseconds, between frames. The product of the two
     * constants spans the glint loop exactly once. MUST match the asset-renderer step.
     */
    public static final long STEP_MILLIS = 1_000L;

    /** Subject A: the 7 always-foil GUI items. */
    private static final List<String> GUI_GLINT_ITEMS = List.of(
        "minecraft:enchanted_book",
        "minecraft:written_book",
        "minecraft:enchanted_golden_apple",
        "minecraft:experience_bottle",
        "minecraft:nether_star",
        "minecraft:debug_stick",
        "minecraft:end_crystal"
    );

    /**
     * Subject B (diagnostic): the 4 worn leather-armor pieces, in the order they are rendered. A
     * list rather than the slot map's own iteration order, which is unspecified.
     */
    private static final List<String> LEATHER_ARMOR = List.of(
        "minecraft:leather_helmet",
        "minecraft:leather_chestplate",
        "minecraft:leather_leggings",
        "minecraft:leather_boots"
    );

    /** The equipment slot each leather piece is worn in. */
    private static final Map<String, EquipmentSlot> ARMOR_SLOTS = Map.of(
        "minecraft:leather_helmet", EquipmentSlot.HEAD,
        "minecraft:leather_chestplate", EquipmentSlot.CHEST,
        "minecraft:leather_leggings", EquipmentSlot.LEGS,
        "minecraft:leather_boots", EquipmentSlot.FEET
    );

    private final ItemFrameRenderer itemRenderer = new ItemFrameRenderer();
    private final EntityFrameRenderer entityRenderer = new EntityFrameRenderer();

    /** The armor stand each armor subject is rendered on, built once and reused across its frames. */
    private final Map<String, Entity> armorStands = new java.util.HashMap<>();

    /**
     * One frame of one glint subject.
     *
     * @param id the subject's item id
     * @param armorSlot the slot to wear it in, or {@code null} for a GUI item subject
     * @param frame the frame index within the subject's sequence
     */
    public record Frame(String id, EquipmentSlot armorSlot, int frame) {

        /** The subject's directory name. */
        private String safeName() {
            return id.replace(":", "__");
        }
    }

    @Override
    public String outputDir() {
        return "glint";
    }

    @Override
    public List<Frame> enumerate(SweepContext ctx) {
        List<Frame> selected = new ArrayList<>();
        for (String id : GUI_GLINT_ITEMS) {
            if (!ctx.targets().accepts(id)) continue;
            for (int frame = 0; frame < FRAME_COUNT; frame++) selected.add(new Frame(id, null, frame));
        }
        for (String id : LEATHER_ARMOR) {
            if (!ctx.targets().accepts(id)) continue;
            for (int frame = 0; frame < FRAME_COUNT; frame++)
                selected.add(new Frame(id, ARMOR_SLOTS.get(id), frame));
        }
        LOG.info("GlintSweep built: {} frames across {} frames per subject", selected.size(), FRAME_COUNT);
        return selected;
    }

    /**
     * Dumps the GUI glint items' real items-atlas sprite-UV rects before the first frame. The atlas
     * is stitched by then, and writing the rects lets the asset side reproduce vanilla's exact
     * sampling UV offline, isolating every other glint factor from that one unknown.
     */
    @Override
    public void prepare(SweepContext ctx, List<Frame> subjects) {
        dumpAtlasUv(ctx.client());
    }

    /** Drives the glint phase from the shared schedule, so this frame's phase is reproducible. */
    @Override
    public void beforeSubject(SweepContext ctx, Frame subject) {
        GlintClock.overrideT = (long) subject.frame() * STEP_MILLIS;
    }

    /** Releases the pinned glint phase back to the wall clock. */
    @Override
    public void afterSweep(SweepContext ctx) {
        GlintClock.overrideT = -1;
    }

    @Override
    public RefKey key(Frame subject) {
        return RefKey.named(String.format("frame_%03d", subject.frame())).in(subject.safeName());
    }

    @Override
    public Canvas canvas(SweepContext ctx, Frame subject) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    @Override
    public boolean render(SweepContext ctx, Frame subject, Canvas canvas, Path out) throws IOException {
        if (subject.armorSlot() == null)
            return itemRenderer.render(ctx.client(), new ItemStack(item(subject.id())), canvas, out);
        return entityRenderer.render(ctx.client(), armorStand(ctx, subject), canvas, out);
    }

    /**
     * Returns the armor stand for one armor subject, wearing its glint-forced piece. Built on the
     * subject's first frame and reused for the rest, so only the glint clock changes between them.
     */
    private Entity armorStand(SweepContext ctx, Frame subject) {
        return armorStands.computeIfAbsent(subject.id(), id -> {
            Entity stand = EntityType.ARMOR_STAND.create(ctx.level(), EntitySpawnReason.LOAD);
            if (stand == null) throw new IllegalStateException("Could not create armor_stand for '" + id + "'");
            ItemStack piece = new ItemStack(item(id));
            // Force the foil so the armor glint render type fires, exactly as the asset side forces
            // it on the worn leather piece.
            piece.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            ((LivingEntity) stand).setItemSlot(subject.armorSlot(), piece);
            return stand;
        });
    }

    /** Resolves an item from its namespaced id. */
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private void dumpAtlasUv(Minecraft client) {
        // The atlas manager keys atlases by atlas id, not texture location, so resolve by matching
        // location() rather than guessing the id key.
        TextureAtlas[] found = new TextureAtlas[1];
        client.getAtlasManager().forEach((id, atlas) -> {
            if (atlas.location().equals(TextureAtlas.LOCATION_ITEMS)) found[0] = atlas;
        });
        if (found[0] == null) {
            LOG.error("glint atlas-uv: items atlas ({}) not found", TextureAtlas.LOCATION_ITEMS);
            return;
        }
        TextureAtlas atlas = found[0];
        JsonObject root = new JsonObject();
        for (String id : GUI_GLINT_ITEMS) {
            String path = id.substring(id.indexOf(':') + 1);
            TextureAtlasSprite sprite = atlas.getSprite(Identifier.parse("minecraft:item/" + path));
            JsonObject rect = new JsonObject();
            rect.addProperty("u0", sprite.getU0());
            rect.addProperty("v0", sprite.getV0());
            rect.addProperty("u1", sprite.getU1());
            rect.addProperty("v1", sprite.getV1());
            root.add(id, rect);
            LOG.info("glint atlas-uv {}: u[{},{}] v[{},{}] sprite={}", id,
                sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), sprite);
        }
        Path out = HarnessConfig.OUTPUT_DIR.resolve("glint").resolve("atlas_uv.json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            LOG.info("glint atlas-uv written: {}", out);
        } catch (IOException ex) {
            LOG.error("glint atlas-uv write failed: {}", out, ex);
        }
    }
}
