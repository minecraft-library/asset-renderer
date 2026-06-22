package lib.minecraft.refharness;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tick-driven animated-glint reference sweep. Renders each glint subject as a deterministic sequence
 * of {@link #FRAME_COUNT} frames, stepping {@link GlintClock#overrideT} through the shared schedule
 * {@code t_N = N * STEP_MILLIS} so every frame's glint phase matches the asset-renderer side
 * ({@code GlintKit.applyGlintAtTimes}). One PNG per frame lands at
 * {@code references/glint/<namespace>__<path>/frame_NNN.png}.
 *
 * <p>Two subject kinds:
 * <ul>
 *   <li><b>GUI items</b> - the 7 always-foil items (enchanted_book, written_book, ...). They glint
 *       intrinsically via {@code DataComponents.ENCHANTMENT_GLINT_OVERRIDE}, so a plain
 *       {@link ItemStack} through {@link ItemFrameRenderer} (the vanilla GUI item pipeline) shows the
 *       item glint ({@code enchanted_glint_item.png}, scale 8.0).</li>
 *   <li><b>Worn leather armor</b> (diagnostic) - an {@code armor_stand} equipped with one
 *       glint-forced leather piece, rendered through {@link EntityFrameRenderer} at the entity iso
 *       pose, so the distinct <em>armor</em> glint ({@code enchanted_glint_armor.png}, scale 0.16)
 *       fires. Byte-parity with the asset side is out of scope (different pose / model); this exists
 *       so the armor-glint animation can be eyeballed side-by-side.</li>
 * </ul>
 *
 * <p>Renders exactly one frame per client tick - the frame renderers reuse a single PIP color texture
 * whose readback completes asynchronously, so firing the next render before the prior readback lands
 * would corrupt it (same reason {@link ItemSweeper} renders one item per tick).
 *
 * <p><b>The {@link #FRAME_COUNT} / {@link #STEP_MILLIS} constants must match
 * {@code TestGlintParityVanilla} in asset-renderer.</b>
 */
public final class GlintSweeper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** Frames per glint subject. MUST match asset-renderer {@code TestGlintParityVanilla.FRAME_COUNT}. */
    public static final int FRAME_COUNT = 30;

    /**
     * Glint-time step (vanilla post-{@code glintSpeed} millis) between frames. {@code FRAME_COUNT *
     * STEP_MILLIS = 30_000} spans the V-loop exactly once. MUST match asset-renderer
     * {@code TestGlintParityVanilla.STEP_MILLIS}.
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

    /** Subject B (diagnostic): the 4 worn leather-armor pieces, mapped to their equipment slot. */
    private static final Map<String, EquipmentSlot> LEATHER_ARMOR = Map.of(
        "minecraft:leather_helmet", EquipmentSlot.HEAD,
        "minecraft:leather_chestplate", EquipmentSlot.CHEST,
        "minecraft:leather_leggings", EquipmentSlot.LEGS,
        "minecraft:leather_boots", EquipmentSlot.FEET
    );

    private final List<Subject> subjects;
    private final ItemFrameRenderer itemRenderer;
    private final EntityFrameRenderer entityRenderer;
    private int subjectIndex;
    private int frameIndex;
    private int rendered;
    private int failed;
    private boolean done;
    private boolean atlasUvDumped;

    private GlintSweeper(List<Subject> subjects) {
        this.subjects = subjects;
        this.itemRenderer = new ItemFrameRenderer();
        this.entityRenderer = new EntityFrameRenderer();
    }

    public static GlintSweeper build() {
        // Honour the same -PrefharnessTargets allowlist the other sweepers use, so glint iteration
        // can scope to a single subject (e.g. -PrefharnessTargets=minecraft:nether_star).
        List<String> filter = HarnessConfig.TARGETS.isBlank()
            ? List.of()
            : List.of(HarnessConfig.TARGETS.split("\\s*,\\s*"));

        List<Subject> selected = new ArrayList<>();
        for (String id : GUI_GLINT_ITEMS) {
            if (filter.isEmpty() || filter.contains(id)) selected.add(new ItemSubject(id));
        }
        // Deterministic armor order (Map is unordered): helmet, chestplate, leggings, boots.
        for (String id : List.of("minecraft:leather_helmet", "minecraft:leather_chestplate",
            "minecraft:leather_leggings", "minecraft:leather_boots")) {
            if (filter.isEmpty() || filter.contains(id)) selected.add(new ArmorSubject(id, LEATHER_ARMOR.get(id)));
        }
        LOG.info("GlintSweeper built: {} subjects x {} frames (filter='{}')",
            selected.size(), FRAME_COUNT, HarnessConfig.TARGETS);
        return new GlintSweeper(selected);
    }

    public boolean isDone() {
        return done;
    }

    public void step(Minecraft client) {
        if (done) return;
        if (subjectIndex >= subjects.size()) { finish(); return; }
        // The atlas is stitched by the time the first tick fires; dump the GUI items' real sprite-UV
        // rects once so the asset side can sample the glint through vanilla's exact UV0 (atlas-UV
        // validation mode), isolating every other glint factor from the one offline unknown.
        if (!atlasUvDumped) { dumpAtlasUv(client); atlasUvDumped = true; }

        Subject subject = subjects.get(subjectIndex);
        Path out = HarnessConfig.OUTPUT_DIR.resolve("glint").resolve(subject.safeName())
            .resolve(String.format("frame_%03d.png", frameIndex));

        // Drive the glint phase from the shared schedule, then render this single frame.
        GlintClock.overrideT = (long) frameIndex * STEP_MILLIS;
        try {
            subject.renderFrame(this, client, out);
            rendered++;
        } catch (IOException ex) {
            LOG.error("GlintSweeper: PNG write failed for {} frame {}", subject.safeName(), frameIndex, ex);
            failed++;
        } catch (RuntimeException ex) {
            LOG.error("GlintSweeper: render failed for {} frame {}", subject.safeName(), frameIndex, ex);
            failed++;
        }

        frameIndex++;
        if (frameIndex >= FRAME_COUNT) {
            frameIndex = 0;
            subjectIndex++;
        }
        if (subjectIndex >= subjects.size()) finish();
    }

    private void finish() {
        GlintClock.overrideT = -1;
        LOG.info("GlintSweeper: done. rendered={}, failed={}, subjects={}", rendered, failed, subjects.size());
        done = true;
        // Same reasoning as ItemSweeper.finish(): the last frame's PNG-write callback is still
        // pending, so closing the GPU textures here would crash it. Leak until JVM exit.
    }

    @Override
    public void close() {
        GlintClock.overrideT = -1;
    }

    /** Resolves an item from its {@code minecraft:...} id. */
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    /**
     * Dumps the GUI glint items' real items-atlas sprite-UV rects to {@code glint/atlas_uv.json}. The
     * generated item models for these 7 items each carry a single {@code item/<name>} sprite, whose
     * normalized atlas rect {@code (u0,v0,u1,v1)} is exactly the {@code UV0} vanilla's glint shader
     * samples through. Writing it lets the asset side reproduce that UV0 offline and verify all other
     * glint factors (scroll / rotation / sampling / blend / alpha) against this byte-stable reference.
     */
    private void dumpAtlasUv(Minecraft client) {
        // AtlasManager keys atlases by atlas id, not texture location, so resolve by matching
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

    /** One glint render subject - either a GUI item or a worn-armor diagnostic. */
    private interface Subject {
        String safeName();

        void renderFrame(GlintSweeper sweeper, Minecraft client, Path out) throws IOException;
    }

    /** Subject A: an always-foil GUI item rendered through the vanilla inventory icon pipeline. */
    private record ItemSubject(String id) implements Subject {
        @Override
        public String safeName() {
            return id.replace(":", "__");
        }

        @Override
        public void renderFrame(GlintSweeper sweeper, Minecraft client, Path out) throws IOException {
            sweeper.itemRenderer.renderAndWrite(client, new ItemStack(item(id)), HarnessConfig.IMAGE_SIZE, out);
        }
    }

    /**
     * Subject B (diagnostic): an {@code armor_stand} wearing one glint-forced leather piece, rendered
     * at the entity iso pose so the armor glint fires. The entity is built once and reused across
     * frames (only {@link GlintClock} changes between them).
     */
    private static final class ArmorSubject implements Subject {
        private final String id;
        private final EquipmentSlot slot;
        private Entity armorStand;

        private ArmorSubject(String id, EquipmentSlot slot) {
            this.id = id;
            this.slot = slot;
        }

        @Override
        public String safeName() {
            return id.replace(":", "__");
        }

        @Override
        public void renderFrame(GlintSweeper sweeper, Minecraft client, Path out) throws IOException {
            if (armorStand == null) {
                armorStand = EntityType.ARMOR_STAND.create(client.level, EntitySpawnReason.LOAD);
                if (armorStand == null) throw new IllegalStateException("could not create armor_stand for " + id);
                ItemStack piece = new ItemStack(item(id));
                // Force the foil so the armor glint render type (enchanted_glint_armor, scale 0.16)
                // fires, exactly as the asset side forces it on the worn leather piece.
                piece.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                ((net.minecraft.world.entity.LivingEntity) armorStand).setItemSlot(slot, piece);
            }
            sweeper.entityRenderer.renderAndWrite(client, armorStand, HarnessConfig.IMAGE_SIZE, out);
        }
    }
}
