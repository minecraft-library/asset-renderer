package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.PoseState;
import lib.minecraft.refharness.api.AppearanceRequest;
import lib.minecraft.refharness.api.Bounds;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Measures an entity's screen-space silhouette by walking its model the way vanilla draws it.
 *
 * <p>The walk mirrors the render chain exactly - the renderer's own {@code setupRotations} and
 * {@code scale} overrides, the living-entity submit chain, every active render layer and every
 * block overlay - but stops at the geometry rather than submitting it, so a subject can be sized
 * before any GPU work happens.
 *
 * <p>Polygons whose four vertex UVs all land on fully transparent texels are skipped. A flat plane
 * cube such as a warden tendril or a wither ribcage has an authored extent far larger than its
 * visible texture region, and letting the empty extent into the bounds shifts the rendered entity
 * off centre.
 *
 * <p>Reflection is unavoidable here: the vanilla hooks this needs - {@code getTextureLocation},
 * {@code setupRotations}, {@code scale}, a renderer's model field, a living renderer's layer list -
 * are all protected or private on their declaring classes.
 */
@Parity(claim = "harness-entity-frame", mode = Mode.DEMOTE)
final class EntityBoundsWalker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Diagnostic per-triangle screen-coord trace rectangle parsed from
     * {@code -Dentity.pixel.dump=x0,y0,x1,y1}. Mirrors the asset-renderer
     * {@code ModelEngine.PIXEL_DUMP_RECT} parser so both sides share one prop. When non-null and
     * non-empty, {@link #dumpTrianglesIfRequested} walks every visible polygon (primary model
     * + active layers) through the same canvas-fit + LER pose chain {@code dispatcher.submit}
     * uses internally, triangulates each quad as {@code (v0,v1,v2)+(v0,v2,v3)} to match
     * {@code EntityGeometryKit.contributeTriangles}, and emits one {@code [PX] TRI} line per
     * triangle whose projected bbox intersects the rect. Per-pixel ground truth still requires
     * a GPU stencil pass; this dump only surfaces which triangles cross the rect with what
     * screen-space corners, which is enough to distinguish chain-drift (different coords) from
     * rasterizer-coverage (same coords, different pixel pick) for the witch x=21 residual.
     */
    private static final int @org.jetbrains.annotations.Nullable [] PIXEL_DUMP_RECT = parsePixelDumpRect();

    private static int @org.jetbrains.annotations.Nullable [] parsePixelDumpRect() {
        String prop = System.getProperty("entity.pixel.dump");
        if (prop == null || prop.isBlank()) return null;
        String[] parts = prop.split(",");
        if (parts.length != 4) {
            System.out.println("[PX] malformed entity.pixel.dump: '" + prop + "' (expected x0,y0,x1,y1)");
            return null;
        }
        try {
            int x0 = Integer.parseInt(parts[0].trim());
            int y0 = Integer.parseInt(parts[1].trim());
            int x1 = Integer.parseInt(parts[2].trim());
            int y1 = Integer.parseInt(parts[3].trim());
            System.out.println("[PX-HEADER] " + String.join("\t",
                "stage", "debugTag",
                "s0x", "s0y", "s1x", "s1y", "s2x", "s2y",
                "p0x", "p0y", "p0z", "p1x", "p1y", "p1z", "p2x", "p2y", "p2z"));
            System.out.println("[PX] harness dump rect=" + x0 + "," + y0 + "-" + x1 + "," + y1);
            return new int[]{ x0, y0, x1, y1 };
        } catch (NumberFormatException nfe) {
            System.out.println("[PX] malformed entity.pixel.dump numbers: '" + prop + "'");
            return null;
        }
    }

    /**
     * Cache of loaded entity textures keyed by their resource location. Read by the bounds walker
     * to skip polygons whose four vertex UVs all land on transparent pixels (warden tendrils,
     * wither ribcage planes, etc) - those cubes are flat planes whose authored model extent far
     * exceeds the visible texture region, and including the empty extent in the screen bounds
     * shifts the rendered entity off-centre. Entries are owned by this walker and closed in
     * {@link #close}.
     */
    private final Map<Identifier, NativeImage> textureCache = new HashMap<>();

    /**
     * Whether the walk poses each model it measures before measuring it.
     *
     * <p>The measurement has to agree with the render or the canvas is fitted to a pose vanilla
     * then does not draw, so this is the render path's own question asked from here: the freeze
     * suppresses {@code setupAnim} on the submit side, and a run that does not freeze - a live
     * client, or the animated reference run - lets it through. Both sites read it, because a body
     * and its layers are posed by one decision.
     */
    private static boolean posesBeforeWalking() {
        return !Boolean.getBoolean("refharness.headless") || PoseState.posed();
    }

    /**
     * Walks the entity's model parts through the same transform chain we'll use for
     * rendering and collects the min/max screen-space coordinates of every visible
     * polygon vertex. Used to derive an exact-fit scale + centering offset.
     */
    Bounds computeScreenBounds(EntityRenderer<?, ?> renderer, EntityRenderState state, Quaternionf rotation) {
        ScreenBounds bounds = accumulateScreenBounds(renderer, state, rotation);
        return new Bounds(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY);
    }

    private ScreenBounds accumulateScreenBounds(EntityRenderer<?, ?> renderer, EntityRenderState state, Quaternionf rotation) {
        Model<?> model = tryGetModel(renderer, state);
        if (model == null) {
            float w = state.boundingBoxWidth;
            float h = state.boundingBoxHeight;
            return new ScreenBounds(-w / 2, w / 2, -h, 0);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Model rawModel = model;
        if (posesBeforeWalking()) {
            try {
                rawModel.setupAnim(state);
            } catch (RuntimeException ignored) {
                // Some renderers' setupAnim assumes additional state fields; if they fail,
                // bounds will still be measured from the model's rest pose.
            }
        }
        // Under the freeze {@link SkipSetupAnimMixin} suppresses {@code setupAnim} on the
        // submit-side render path so the primary body renders at its authored bind pose.
        // The bounds walker must agree with the render path or the canvas-fit math anchors
        // off a pose vanilla then doesn't draw. Worse, {@code setupAnim} MUTATES the model's
        // bone rotations - calling it here for a bounds-only measurement would leave the
        // model in the setupAnim'd pose, and the {@code SkipSetupAnimMixin}-protected submit
        // would then queue a deferred render reading those mutated bones. Result before this
        // skip: vanilla zombie / husk / drowned / piglin / villager all rendered with the
        // iconic forward-arm pose (because {@code AbstractZombieModel.animateZombieArms}
        // unconditionally rotates arms at zero state with {@code armDrop = -pi/2.25 ~ -80
        // degrees}), even though the submit-side {@code setupAnim} was being skipped. The
        // resulting silhouette ran ~38% wider than asset-renderer's bind-pose render and the
        // family-fit canvas absorbed the asymmetric extra extent. Skipping {@code setupAnim}
        // here too keeps both bounds AND render aligned to the bind pose.
        //
        // An animated run is that same rule read the other way: the render path poses the model, so
        // the walk has to pose it too, and it is one call on one model rather than two poses that
        // could differ - the mutation the paragraph above warns about is what the render then
        // reads. The tick both of them pose at is the one the clock holds.

        PoseStack ps = new PoseStack();
        ps.scale(1.0f, 1.0f, -1.0f);  // chirality compensation - matches renderAndWrite
        ps.mulPose(rotation);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> && state instanceof LivingEntityRenderState living) {
            // Mirror vanilla LivingEntityRenderer.submit's setupRotations + scale stack
            // so the bounds sit in the same frame as the render. Body-rot is zero (we
            // zeroed it before extractRenderState). setupRotations is virtual - several
            // subclass renderers (SquidRenderer, ShulkerRenderer, FoxRenderer, ...) replace
            // it with their own pose math that adds translates on top of the base Y180
            // rotation. Hardcoding the base behaviour here would make bounds disagree with
            // render on those entities (squid's tentacles fall below the canvas because
            // its setupRotations adds translate(0, -1.2, 0) that bounds didn't see), so
            // dispatch virtually via reflection to pick up whichever override applies.
            ps.scale(living.scale, living.scale, living.scale);
            invokeRendererSetupRotations(renderer, state, ps, /*bodyRot*/ 0.0f, living.scale);
            ps.scale(-1.0f, -1.0f, 1.0f);
            invokeRendererScale(renderer, state, ps);
            ps.translate(0.0f, -1.501f, 0.0f);
        } else if (renderer.getClass().getSimpleName().equals("EnderDragonRenderer")) {
            // EnderDragonRenderer extends EntityRenderer directly (not LivingEntityRenderer)
            // so the standard LER chain above doesn't apply, but submit() still does its own
            // pose-stack preamble before submitting the model: a yaw rotation based on the
            // historical sample at index 7, an x-rotation based on the y-delta between
            // historical samples 5 and 10, then translate(0, 0, 1), scale(-1, -1, 1), and
            // translate(0, -1.501, 0). The historical rotations are zero for our transient
            // entity (the entity never tickets, so flightHistory's 64 samples are all the
            // {y=0, yRot=0} default the constructor fills with) but the chirality scale and
            // y-offset translate are unconditional - without them, bounds are computed in
            // un-flipped X/Y and the rendered dragon ends up positioned outside the bounds-
            // derived canvas (clipping the wings/head off the top while leaving an empty
            // band at the bottom, which is the canonical symptom users report).
            ps.translate(0.0f, 0.0f, 1.0f);
            ps.scale(-1.0f, -1.0f, 1.0f);
            ps.translate(0.0f, -1.501f, 0.0f);
        }

        // Resolve the renderer's primary texture so polygons whose four UV samples all hit
        // transparent pixels (warden's flat tendril plane cubes, wither's ribcage plane, etc)
        // can be skipped - their authored cube extents are far larger than what's drawn, and
        // including the empty extent shifts the bounds centre off the visible silhouette.
        NativeImage texture = entityTexture(renderer, state);

        ScreenBounds bounds = new ScreenBounds();
        Consumer<? super org.joml.Vector3fc> expand = vec -> bounds.expand(vec.x(), vec.y());
        walkVisibleExtents(model.root(), ps, texture, expand);

        // Feature-renderer overlay models (sheep wool, mooshroom mushroom-cow's body overlay,
        // armor, glowing eyes, leash hat, ...) submit additional geometry on top of the primary
        // model that is NOT reachable from {@code model.root()}. Without including those layers
        // the fit-to-canvas scale is set from the bare body alone, leaving the overlay to clip
        // past the canvas edge - sheep wool is the canonical case (the wool layer inflates the
        // body cubes by ~0.5 model units so the silhouette grows by ~3% in each direction).
        // Walk every {@code EntityModel}-shaped field on each layer with the same pose stack
        // so the overlay extents fold into the bounds. Layers that render block geometry
        // instead of an EntityModel (mooshroom mushrooms, copper-golem's flower) are not
        // covered by this pass and need separate per-layer handling.
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            walkLayerExtents(ler, state, ps, texture, expand);
            walkBlockOverlayExtents(ler, state, ps, expand);
        }
        return bounds;
    }

    /**
     * Walks every {@code EntityModel}/{@code Model}-typed field on each registered
     * {@link RenderLayer} of {@code renderer} through the same pose stack the primary model
     * uses, expanding the supplied bounds accumulator with each polygon vertex. Reflectively
     * accesses {@link LivingEntityRenderer}'s {@code layers} list (it is {@code protected})
     * and each layer's instance fields (each layer subclass holds its overlay model in a
     * differently-named private field - {@code adultModel}/{@code babyModel} on
     * {@code SheepWoolLayer}, plain {@code model} on most others).
     * <p>
     * Each found model is dual-purposed: {@code setupAnim(state)} is invoked first so the
     * overlay tracks the same per-tick state as the primary model, then the model's
     * {@link Model#root() root} is fed into {@link #walkVisibleExtents}. Errors from
     * {@code setupAnim} are swallowed because some layers' models assume state fields that
     * a transient zero-state entity doesn't populate - in that case the overlay's rest pose
     * is what gets measured, which is what we want anyway.
     */
    private void walkLayerExtents(
        LivingEntityRenderer<?, ?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        NativeImage texture,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        boolean poses = posesBeforeWalking();
        List<? extends RenderLayer<?, ?>> layers = layersOf(renderer);
        for (RenderLayer<?, ?> layer : layers) {
            if (!isLayerActiveForState(layer, state)) continue;
            // An equipment / decor layer is measured through its OWN equipment texture, not the wearer's
            // body texture: the two disagree on which of the shell's texels are opaque, so the body
            // texture over-pads a body-shaped saddle mesh and crops a dedicated one. A layer that carries
            // no equipment texture (every non-equipment overlay) falls back to the body texture unchanged.
            NativeImage layerTexture = equipmentTexture(layer, state);
            if (layerTexture == null) {
                // An equipment layer - one that declares an EquipmentClientInfo.LayerType - which
                // resolves no equipment texture is dressing an item this subject is not wearing. A
                // saddled horse still carries its body-armour layer and an armoured horse its saddle
                // layer; only the worn one resolves a texture, and vanilla draws nothing for the other.
                // Falling back to the body texture would measure the empty layer's body-shaped,
                // inflated mesh as opaque and grow the canvas past what actually renders, so an
                // equipment layer with no texture contributes no bounds. A true overlay - one with no
                // LayerType, such as a sheep's wool or a mooshroom's body - has no equipment texture by
                // nature and still falls back to the wearer's body texture.
                if (reflectLayerType(layer, state) != null) continue;
                layerTexture = texture;
            }
            for (Model<?> layerModel : findLayerModels(layer, state)) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Model raw = layerModel;
                // Same reasoning as the primary-model {@code setupAnim} skip in
                // {@link #computeScreenBounds}: under the freeze the render-side
                // {@code setupAnim} call is no-op'd, and calling it here would mutate the
                // layer model into a pose vanilla then doesn't draw. Plus most layer
                // {@code setupAnim} reads from {@code state.rightArmPose} /
                // {@code state.leftArmPose} / {@code state.isAggressive}, all of which are
                // either equipment-driven (zero at headless) or already frozen by
                // {@link FreezeAnimationStateMixin}. An animated run poses a layer for the same
                // reason it poses the body: the render is about to.
                if (poses) {
                    try {
                        raw.setupAnim(state);
                    } catch (RuntimeException ignored) {
                    }
                }
                walkVisibleExtents(layerModel.root(), ps, layerTexture, expand);
            }
        }
    }

    /**
     * Includes block-model layers (mooshroom mushrooms, snow-golem carved_pumpkin) in the bounds.
     * {@link #walkLayerExtents} only covers {@code EntityModel}-typed layer fields, so block layers
     * - which render a vanilla block model rather than an {@code EntityModel} - were skipped, and
     * the family-fit canvas cropped the overlay. The Java pipeline measures these block overlays
     * alpha-tight (opaque texels only) and grows its canvas to fit them; matching that here keeps
     * the reference PNG's canvas identical and uncropped.
     *
     * <p>Faithful capture: for each active layer with no {@code EntityModel} field (the block /
     * item layers - equipment/held-item layers are already filtered by
     * {@link #isLayerActiveForState}), invoke the layer's {@code submit} through a {@link Proxy}
     * {@link SubmitNodeCollector} that intercepts {@code submitBlockModel} and no-ops everything
     * else. Vanilla applies the exact head-bone + layer pose and the block's own transformation to
     * the pose stack before the {@code submitBlockModel} call, so the captured pose is the full
     * transform; empty blocks (iron-golem flower / enderman carried block at zero state) early-return
     * from {@code submit} and capture nothing. Each captured {@link BakedQuad} is then walked
     * alpha-tight against its sprite via {@link #contributeBlockQuadExtents}.
     */
    private static void walkBlockOverlayExtents(
        LivingEntityRenderer<?, ?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder finder = blockAtlasSpriteFinder();
        if (finder == null) return;
        // The layer reads its bones off the renderer's own mutable model field rather than off
        // anything handed in, so the walk has to pin that field the way tryGetModel already pins what
        // it measures directly - see pinSelectedModel.
        Model<?> selected = tryGetModel(renderer, state);
        Model<?> previous = pinSelectedModel(renderer, selected);
        try {
            for (RenderLayer<?, ?> layer : layersOf(renderer)) {
                if (!isLayerActiveForState(layer, state)) continue;
                if (!findLayerModels(layer, state).isEmpty()) continue; // EntityModel layer - handled by walkLayerExtents
                captureBlockSubmits(layer, state, ps, finder, expand);
            }
        } finally {
            if (previous != null) pinSelectedModel(renderer, previous);
        }
    }

    /**
     * Points {@code LivingEntityRenderer.model} at the model this subject selects for the duration of
     * a block-overlay walk, and answers the one it replaced so the caller can put it back.
     *
     * <p><b>{@link #tryGetModel} makes what the walk measures directly independent of render order;
     * this is the same fix for what a LAYER measures.</b> A block-model layer is invoked through its
     * own {@code submit}, and it reaches its anchor bone through {@code getParentModel()} - the
     * renderer's mutable field - rather than through anything the walk passes in. So the two paths
     * disagreed whenever that field held another subject's model, which {@code AgeableMobRenderer}
     * leaves it holding: its {@code submit} assigns {@code isBaby ? babyModel : adultModel} and never
     * puts it back.
     *
     * <p>Measured on the mooshroom, the only subject in the corpus whose model composes block
     * geometry. Its layer places three mushrooms - two at constants, and one through
     * {@code ModelPart.translateAndRotate} on a bone it reads off that field. Measuring an adult
     * after a baby had rendered moved that third mushroom by {@code 0.1252} of a block and took the
     * canvas from {@code 388x564} to {@code 386x564}; the other two never moved, and neither did any
     * cube the walk measures itself. It cost nothing while each posed sweep booted its own client,
     * because the sweep that renders a baby mooshroom was in a different JVM.
     *
     * @param renderer the renderer whose field to pin
     * @param selected the model this subject selects, or {@code null} to leave the field alone
     * @return the model the field held, or {@code null} if it was not pinned
     */
    private static Model<?> pinSelectedModel(EntityRenderer<?, ?> renderer, Model<?> selected) {
        if (selected == null || !(renderer instanceof LivingEntityRenderer<?, ?, ?> living)) return null;
        try {
            Field field = LivingEntityRenderer.class.getDeclaredField("model");
            field.setAccessible(true);
            Object held = field.get(living);
            if (held == selected) return null;
            field.set(living, selected);
            return held instanceof Model<?> model ? model : null;
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Invokes {@code layer.submit(...)} with a proxy {@link SubmitNodeCollector} that intercepts each
     * {@code submitBlockModel(poseStack, ..., Mesh, ...)} call and contributes that block model's
     * alpha-tight extents inline (returning harmless defaults for every other collector method). The
     * Fabric renderer API reroutes the block geometry into a {@code Mesh} argument rather than the
     * vanilla {@code BlockStateModelPart} list, so the geometry is read from the mesh's
     * {@link QuadView}s (converted to
     * {@link BakedQuad} via the block-atlas {@code SpriteFinder}); the captured pose already includes
     * the head-bone / layer pose and the block's own transformation. Empty blocks (iron-golem flower /
     * enderman carried block at zero state) early-return from {@code submit} and contribute nothing.
     */
    private static void captureBlockSubmits(
        RenderLayer<?, ?> layer, EntityRenderState state, PoseStack ps,
        net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder finder,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        Method submit = findLayerSubmit(layer, state);
        if (submit == null) return;
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("submitBlockModel") && args != null) {
                PoseStack pose = null;
                net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView mesh = null;
                for (Object a : args) {
                    if (a instanceof PoseStack p) pose = p;
                    else if (a instanceof net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView m) mesh = m;
                }
                if (pose != null && mesh != null) {
                    org.joml.Matrix4fc snap = new org.joml.Matrix4f(pose.last().pose());
                    mesh.forEach(quad -> {
                        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = finder.find(quad);
                        if (sprite != null)
                            contributeBlockQuadExtents(quad.toBakedQuad(sprite), snap, expand);
                    });
                }
            }
            if (method.getName().equals("order")) return proxy; // OrderedSubmitNodeCollector fluent no-op
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) return false;
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            if (ret == float.class) return 0f;
            if (ret == double.class) return 0d;
            if (ret == void.class || !ret.isPrimitive()) return null;
            return (byte) 0;
        };
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
            SubmitNodeCollector.class.getClassLoader(),
            new Class<?>[]{ SubmitNodeCollector.class },
            handler);
        try {
            submit.setAccessible(true);
            // submit(PoseStack, SubmitNodeCollector, int packedLight, S state, float, float)
            submit.invoke(layer, ps, collector, PipScope.FULL_BRIGHT_LIGHT, state, 0.0f, 0.0f);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static final Field MODEL_MANAGER_ATLAS_MANAGER;
    static {
        Field f = null;
        try {
            f = net.minecraft.client.resources.model.ModelManager.class.getDeclaredField("atlasManager");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) {
        }
        MODEL_MANAGER_ATLAS_MANAGER = f;
    }

    /**
     * Resolves the {@code SpriteFinder} for the block atlas (Fabric interface-injected on
     * {@link TextureAtlas}), used to recover each mesh quad's
     * sprite for alpha sampling. Returns {@code null} when the atlas manager can't be reached.
     */
    private static net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder blockAtlasSpriteFinder() {
        if (MODEL_MANAGER_ATLAS_MANAGER == null) return null;
        try {
            Object am = MODEL_MANAGER_ATLAS_MANAGER.get(Minecraft.getInstance().getModelManager());
            if (!(am instanceof net.minecraft.client.resources.model.sprite.AtlasManager atlasManager)) return null;
            // The atlas is keyed by its config id, not its texture path, so look it up by matching
            // location() == LOCATION_BLOCKS rather than getAtlasOrThrow(LOCATION_BLOCKS).
            net.minecraft.client.renderer.texture.TextureAtlas[] blockAtlas = { null };
            atlasManager.forEach((id, atlas) -> {
                if (net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS.equals(atlas.location()))
                    blockAtlas[0] = atlas;
            });
            if (blockAtlas[0] == null) return null;
            return ((net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas) (Object) blockAtlas[0]).spriteFinder();
        } catch (IllegalAccessException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Finds a layer's typed {@code submit(PoseStack, SubmitNodeCollector, int, S, float, float)}
     * override - the one whose 4th parameter is compatible with {@code state} (the
     * {@code EntityRenderState} bridge overload never carries block geometry). Returns {@code null}
     * when no matching override exists.
     */
    private static Method findLayerSubmit(RenderLayer<?, ?> layer, EntityRenderState state) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("submit") || m.getParameterCount() != 6) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[0] != PoseStack.class || p[1] != SubmitNodeCollector.class || p[2] != int.class) continue;
                if (p[4] != float.class || p[5] != float.class) continue;
                if (!p[3].isInstance(state)) continue;
                if (p[3] == EntityRenderState.class) continue; // skip the bridge overload
                return m;
            }
        }
        return null;
    }

    /**
     * Alpha-tight extent contribution for one block {@link BakedQuad}. Unpacks the quad's four
     * atlas UVs, converts them to sprite-local pixel space, walks the opaque sub-rectangle via
     * {@link SpriteContents#isTransparent}, bilinearly maps
     * the opaque corners back through the quad's four transformed vertices, and feeds them to
     * {@code expand}. Fully-transparent quads contribute nothing.
     */
    private static void contributeBlockQuadExtents(
        net.minecraft.client.resources.model.geometry.BakedQuad quad,
        org.joml.Matrix4fc pose,
        Consumer<? super org.joml.Vector3fc> expand
    ) {
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = quad.materialInfo().sprite();
        if (sprite == null) return;
        net.minecraft.client.renderer.texture.SpriteContents contents = sprite.contents();
        int W = contents.width();
        int H = contents.height();
        if (W <= 0 || H <= 0) return;

        // Transform the four vertices through the captured pose (block space -> entity-local screen).
        Vector3f[] pos = new Vector3f[4];
        float[] su = new float[4];
        float[] sv = new float[4];
        // The mesh quad's packed UVs are ATLAS coordinates. Convert to sprite-local [0, 1] by the
        // sprite's atlas bounds. The two packed components are atlas (horizontal, vertical) but the
        // renderer's u()/v() axes are transposed relative to the sprite's U/V, so assign each packed
        // component to its axis by which atlas range ([u0,u1] vs [v0,v1]) it falls in - those ranges
        // are disjoint for a stitched sprite. Fully-degenerate sprites (du/dv == 0) contribute nothing.
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        float du = u1 - u0, dv = v1 - v0;
        if (du == 0f || dv == 0f) return;
        float uLo = Math.min(u0, u1), uHi = Math.max(u0, u1), vLo = Math.min(v0, v1), vHi = Math.max(v0, v1);
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = quad.position(i);
            pos[i] = pose.transformPosition(p.x(), p.y(), p.z(), new Vector3f());
            long packed = quad.packedUV(i);
            float a = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
            float b = Float.intBitsToFloat((int) (packed >>> 32));
            boolean aIsU = a >= uLo - 1e-4f && a <= uHi + 1e-4f && !(a >= vLo - 1e-4f && a <= vHi + 1e-4f);
            float atlasU = aIsU ? a : b;
            float atlasV = aIsU ? b : a;
            su[i] = (atlasU - u0) / du;
            sv[i] = (atlasV - v0) / dv;
        }

        float uMin = Math.min(Math.min(su[0], su[1]), Math.min(su[2], su[3]));
        float uMax = Math.max(Math.max(su[0], su[1]), Math.max(su[2], su[3]));
        float vMin = Math.min(Math.min(sv[0], sv[1]), Math.min(sv[2], sv[3]));
        float vMax = Math.max(Math.max(sv[0], sv[1]), Math.max(sv[2], sv[3]));
        if (uMin == uMax || vMin == vMax) return;

        // Classify the four vertices into the sprite-UV rect corners (BL/BR/TR/TL).
        Vector3f bl = null, br = null, tr = null, tl = null;
        float eps = 1e-4f;
        for (int i = 0; i < 4; i++) {
            boolean atUMin = Math.abs(su[i] - uMin) < eps, atUMax = Math.abs(su[i] - uMax) < eps;
            boolean atVMin = Math.abs(sv[i] - vMin) < eps, atVMax = Math.abs(sv[i] - vMax) < eps;
            if (atUMin && atVMin) bl = pos[i];
            else if (atUMax && atVMin) br = pos[i];
            else if (atUMax && atVMax) tr = pos[i];
            else if (atUMin && atVMax) tl = pos[i];
        }
        if (bl == null || br == null || tr == null || tl == null) {
            for (Vector3f p : pos) expand.accept(p);
            return;
        }

        // Same texel-overlap bounds as contributePolygonExtents / contributeFaceAlphaTight.
        int pxMin = clampPixel((int) Math.floor(uMin * W), W);
        int pxMax = clampPixel((int) Math.ceil(uMax * W) - 1, W);
        int pyMin = clampPixel((int) Math.floor(vMin * H), H);
        int pyMax = clampPixel((int) Math.ceil(vMax * H) - 1, H);
        int firstPx = Integer.MAX_VALUE, lastPx = Integer.MIN_VALUE, firstPy = Integer.MAX_VALUE, lastPy = Integer.MIN_VALUE;
        for (int py = pyMin; py <= pyMax; py++) {
            for (int px = pxMin; px <= pxMax; px++) {
                if (contents.isTransparent(0, px, py)) continue;
                if (px < firstPx) firstPx = px;
                if (px > lastPx) lastPx = px;
                if (py < firstPy) firstPy = py;
                if (py > lastPy) lastPy = py;
            }
        }
        if (firstPx == Integer.MAX_VALUE) return; // fully transparent

        float oUMin = Math.max(uMin, (float) firstPx / W);
        float oUMax = Math.min(uMax, (float) (lastPx + 1) / W);
        float oVMin = Math.max(vMin, (float) firstPy / H);
        float oVMax = Math.min(vMax, (float) (lastPy + 1) / H);
        expand.accept(bilinearCorner(oUMin, oVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMax, oVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMax, oVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl));
        expand.accept(bilinearCorner(oUMin, oVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl));
    }

    /**
     * Bilinearly interpolates the four transformed quad corners at sprite-UV fraction
     * {@code (u, v)} within {@code [uMin, uMax] x [vMin, vMax]}. The corners are already in
     * entity-local screen space, so no further transform is applied.
     */
    private static Vector3f bilinearCorner(
        float u, float v, float uMin, float uMax, float vMin, float vMax,
        Vector3f bl, Vector3f br, Vector3f tr, Vector3f tl
    ) {
        float s = (u - uMin) / (uMax - uMin);
        float t = (v - vMin) / (vMax - vMin);
        float w00 = (1 - s) * (1 - t), w10 = s * (1 - t), w11 = s * t, w01 = (1 - s) * t;
        return new Vector3f(
            w00 * bl.x() + w10 * br.x() + w11 * tr.x() + w01 * tl.x(),
            w00 * bl.y() + w10 * br.y() + w11 * tr.y() + w01 * tl.y(),
            w00 * bl.z() + w10 * br.z() + w11 * tr.z() + w01 * tl.z());
    }

    /**
     * Class-name suffixes / exact matches for {@link RenderLayer} subclasses whose
     * {@code submit()} renders nothing for the zero-state entity the harness uses (no
     * equipment, no held items, no stuck arrows, no special poses). The family-fit pre-pass
     * walks each layer's {@code Model<?>} fields to pad bounds; including these layers
     * over-pads the canvas with margin around invisible geometry, because the model exists
     * (constructed at layer-init time) but is never rasterised at zero state. Silhouette-bbox
     * audit 2026-05-15 confirmed: wolf_pale / pig_warm / horse / skeleton_horse have vanilla
     * canvases with 5-47px transparent margin from layer-model bounds that never render.
     * <p>
     * Patterns:
     * <ul>
     *   <li>{@code *ArmorLayer} - HumanoidArmorLayer, WolfArmorLayer, HorseArmorLayer,
     *       LeatherHorseArmorLayer, ... - gates on {@code state.equipment.X.isEmpty()} or
     *       analogous variant-armor fields, all empty at zero state</li>
     *   <li>{@code *EquipmentLayer} - generic equipment overlay base class on horses, wolves,
     *       skeletons in 1.21+; same gating shape</li>
     *   <li>{@code ItemInHandLayer} / {@code PlayerItemInHandLayer} - submits the held-item
     *       model; bails when both hands hold {@code ItemStack.EMPTY}</li>
     *   <li>{@code ElytraLayer} - gates on the elytra equipment slot being non-empty</li>
     *   <li>{@code SaddleLayer} / {@code HorseSaddleLayer} - pig / equine saddle, gates on
     *       {@code state.saddle.isEmpty()}</li>
     *   <li>{@code *CollarLayer} - wolf / cat dyed collar, gates on {@code state.isTame}</li>
     *   <li>{@code StuckInBodyLayer} - parent of arrow / bee-stinger layers, gates on stack
     *       count {@code > 0}</li>
     *   <li>{@code TridentLayer} / {@code ShieldLayer} / {@code BeeStingerLayer} -
     *       held-item variants for specific items, all empty at zero state</li>
     *   <li>{@code SnowLayer} (powdered-snow stack) / {@code LlamaDecorLayer} (carpet) -
     *       cosmetic state overlays, all absent at zero state</li>
     *   <li>{@code ParrotOnShoulderLayer} - player-only; zero state has no parrot</li>
     *   <li>{@code CapeLayer} / {@code Deadmau5EarsLayer} / {@code EarsLayer} - player cosmetic
     *       overlays, zero state has none</li>
     *   <li>{@code DolphinCarryingItemLayer} - dolphin-specific held item</li>
     *   <li>{@code FoxHeldItemLayer} - fox-specific held item</li>
     * </ul>
     * The match walks the layer's class hierarchy so subclasses still hit the gate. If a
     * vanilla refactor renames a class, the default-true branch preserves current
     * behaviour (over-padded canvases) until the list is updated.
     */
    private static final java.util.Set<String> NO_RENDER_LAYER_SUFFIXES = java.util.Set.of(
        "ArmorLayer",
        "EquipmentLayer",
        "ItemInHandLayer",
        "ElytraLayer",
        "WingsLayer",  // 1.21+ rename of ElytraLayer; HumanoidMobRenderer adds it for every humanoid
        "CustomHeadLayer",  // renders mob-head / pumpkin / dragon-head equipment; HumanoidMobRenderer adds it for every humanoid
        "SaddleLayer",
        "CollarLayer",
        "StuckInBodyLayer",
        "TridentLayer",
        "ShieldLayer",
        "BeeStingerLayer",
        "SnowLayer",
        "LlamaDecorLayer",
        "ParrotOnShoulderLayer",
        "CapeLayer",
        "Deadmau5EarsLayer",
        "EarsLayer",
        "DolphinCarryingItemLayer",
        "FoxHeldItemLayer",
        "RopesLayer"  // happy_ghast, gated on state.isLeashHolder AND bodyItem.is(HARNESSES) - the
                      // equipment probe answers the second half, isLayerActiveForState the first.
                      // Its adultModel/babyModel are the whole body at CubeDeformation(0.2) on the
                      // 4x transformer, so walking them past either half oversizes the canvas
    );

    /**
     * Checks whether a layer would actually render for the given state. Returns {@code true}
     * by default - we walk the layer's geometry. Returns {@code false} when the layer's submit
     * is known to skip rendering for this state, so the layer's model shouldn't pad bounds.
     * <p>
     * Three gate families:
     * <ol>
     *   <li><b>{@code EnergySwirlLayer}</b> (charged creeper, wither armor electric overlay)
     *       - reflectively invokes {@code isPowered(state)} to detect the un-powered case.
     *       Specific because the charged-creeper mesh is visibly larger than the body, so
     *       over-padding here used to ~2x the silhouette.</li>
     *   <li><b>Per-layer state gates</b> - a layer whose own {@code submit} bails on a render-state
     *       flag is asked that flag through {@link #stateFlag}: a wool layer on a sheared subject, a
     *       ropes layer on a subject holding no leash, a wool undercoat on a baby. Named
     *       individually rather than derived, because the flag a layer reads is the layer's own. A
     *       layer that instead holds one mesh per age is not gated here at all - it is still drawn,
     *       just from its other field, so {@link #pickAgeModelName} picks rather than rejects.</li>
     *   <li><b>{@link #NO_RENDER_LAYER_SUFFIXES Equipment-driven layers}</b> - any layer in
     *       the well-known no-render-at-zero-state class list returns {@code false}. Catches
     *       HumanoidArmorLayer / ItemInHandLayer / ElytraLayer / SaddleLayer / etc. without
     *       reflectively probing per-state-class equipment fields.</li>
     * </ol>
     * Falls back to walking the layer when reflection / class-lookup fails - over-padded
     * bounds is preferable to clipped bounds. Other conditional layers
     * ({@code LivingEntityEmissiveLayer} with alpha=0, etc.) aren't handled here; add cases
     * as new entities surface bounds-padding regressions.
     */
    private static boolean isLayerActiveForState(RenderLayer<?, ?> layer, EntityRenderState state) {
        // The powered probe is asked first, over the whole hierarchy, because one swirl layer is also
        // named for armour: the wither's is a WitherArmorLayer, and the suffix list answers for it
        // before the probe can run - leaving a charged wither's aura drawn and never measured, so its
        // reference is cropped by exactly the aura it exists to show.
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getSimpleName().equals("EnergySwirlLayer")) continue;
            try {
                Method m = c.getDeclaredMethod("isPowered", EntityRenderState.class);
                m.setAccessible(true);
                Object result = m.invoke(layer, state);
                return !(result instanceof Boolean powered) || powered;
            } catch (ReflectiveOperationException ignored) {
                return true;
            }
        }
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String simpleName = c.getSimpleName();
            // A sheared subject's wool layer bails at the top of its own submit, so walking it would
            // measure a coat that is not drawn and leave the reference framed for a woolly sheep.
            if (simpleName.endsWith("WoolLayer") && stateFlag(state, "isSheared", false)) return false;
            // The undercoat holds ONE mesh and it is the adult's, so the age rule above has no pair to
            // pick between - and its own submit returns outright on a baby. Walking it anyway measured
            // a full-grown coat around a lamb and reserved the adult's width for it.
            if (simpleName.endsWith("WoolUndercoatLayer")
                && state instanceof LivingEntityRenderState living && living.isBaby) return false;
            // Wearing a harness is only half of the ropes layer's gate - it draws for a subject that
            // is holding a leash as well, and none of these subjects holds one. The equipment half
            // alone would measure the ropes mesh, which is the whole body at CubeDeformation(0.2) on
            // a 4x transformer, and reserve a canvas nothing draws into.
            if (simpleName.endsWith("RopesLayer") && !stateFlag(state, "isLeashHolder", true)) return false;
            for (String suffix : NO_RENDER_LAYER_SUFFIXES) {
                if (!simpleName.endsWith(suffix)) continue;
                // The equipment-driven ones stop being unconditional for a reference that asked for
                // equipment and is wearing it. Everything else in the list stays rejected outright,
                // including the collar - a collar is drawn on the body's own mesh, so measuring it
                // would grow a canvas by nothing and move the references that already exist.
                return EQUIPMENT_LAYER_SUFFIXES.contains(suffix)
                    && AppearanceRequest.selectsAny(EQUIPMENT_AXES)
                    && carriesEquipment(state);
            }
        }
        return true;
    }

    /**
     * The subset of {@link #NO_RENDER_LAYER_SUFFIXES} that draws only for a subject wearing
     * something, and whose mesh therefore has to be measured once a subject is.
     *
     * <p>An equipment shell stands proud of the skin, so a reference that wears one and is framed to
     * the bare body is cropped by exactly the equipment it exists to show. Rejecting these outright is
     * right for a subject wearing nothing, which every subject was until equipment could be selected.
     */
    private static final java.util.Set<String> EQUIPMENT_LAYER_SUFFIXES = java.util.Set.of(
        "ArmorLayer",
        "EquipmentLayer",
        "SaddleLayer",
        "LlamaDecorLayer",
        "RopesLayer");

    /**
     * The axes whose references are the ones this measures equipment for.
     *
     * <p>Scoped to a named request rather than applied wherever a subject happens to be wearing
     * something, because two sweeps outside the reference tree equip theirs deliberately and frame
     * them with a reserved margin instead of a measurement. Widening the walk to them would reframe
     * every one of their diagnostics as a side effect of a change that is about the entity tree.
     */
    private static final String[] EQUIPMENT_AXES = {"equip", "armor"};

    /**
     * Whether the subject is wearing anything at all, read off the render state's own item slots
     * rather than off what the harness asked for - so a subject that selects nothing answers no
     * however it was built, which is what keeps every existing reference where it is.
     */
    private static boolean carriesEquipment(EntityRenderState state) {
        for (Class<?> c = state.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(state) instanceof ItemStack stack && !stack.isEmpty()) return true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A slot this walker cannot read is treated as empty, which is the answer every
                    // subject gave before equipment could be selected.
                }
            }
        }
        return false;
    }

    /**
     * Reads a boolean off the render state by the name vanilla gives it, so a layer gate here is
     * asked of the same field the layer's own {@code submit} reads rather than of what the harness
     * asked for - the two are set by different routes, and only the field answers for the draw.
     *
     * <p>A state a vanilla refactor has moved the field off answers {@code whenUnreadable}, which
     * every caller passes as the value that keeps walking the layer - over-padded bounds are
     * preferable to clipped ones, the same fallback the rest of this gate takes.
     *
     * @param state the render state the layer would be drawn from
     * @param fieldName the vanilla field name the layer's own gate reads
     * @param whenUnreadable the answer to give when the state carries no such field
     * @return whether the field is set, or {@code whenUnreadable} when it cannot be read
     */
    private static boolean stateFlag(EntityRenderState state, String fieldName, boolean whenUnreadable) {
        Field field = findFieldByName(state.getClass(), fieldName);
        if (field == null) return whenUnreadable;
        try {
            field.setAccessible(true);
            return field.get(state) instanceof Boolean flag ? flag : whenUnreadable;
        } catch (ReflectiveOperationException ignored) {
            return whenUnreadable;
        }
    }

    private static final Field LIVING_RENDERER_LAYERS;
    static {
        try {
            LIVING_RENDERER_LAYERS = LivingEntityRenderer.class.getDeclaredField("layers");
            LIVING_RENDERER_LAYERS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("LivingEntityRenderer.layers field not found - vanilla refactored its layer storage", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<? extends RenderLayer<?, ?>> layersOf(LivingEntityRenderer<?, ?, ?> renderer) {
        try {
            Object value = LIVING_RENDERER_LAYERS.get(renderer);
            return value instanceof List<?> list
                ? (List<? extends RenderLayer<?, ?>>) list
                : Collections.emptyList();
        } catch (IllegalAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns every {@link Model} instance the layer's {@code submit()} would render for the
     * given {@code state}. Walks the layer's own class fields plus its superclass fields up to
     * (but not including) {@link RenderLayer} - the base class only holds the parent renderer
     * reference, never an overlay model.
     *
     * <p>Most layers carry exactly one {@code Model<?>} field and submit it unconditionally; for
     * those the field walk returns the right Model directly. A few layers carry multiple Model
     * fields and dispatch between them inside {@code submit()} - the layer renders ONE of them
     * per call based on the {@link EntityRenderState}. Walking all of them for bounds would
     * over-pad the canvas with extents from a Model that {@code submit()} never reaches at the
     * current state. The harness needs to mirror the gate so bounds and render agree.
     *
     * <p>Known multi-model layers (handled below):
     * <ul>
     *   <li><b>An adult mesh and a baby mesh</b> - gated on {@code state.isBaby}, and recognised by
     *       vanilla's own field naming rather than by a list of classes: {@code adultModel} /
     *       {@code babyModel} on {@code SheepWoolLayer}, {@code model} / {@code babyModel} on
     *       {@code DrownedOuterLayer}, {@code noHatModel} / {@code noHatBabyModel} on
     *       {@code VillagerProfessionLayer}. Reading the convention is what keeps a layer added in a
     *       later version measured against the mesh its {@code submit} actually draws. Getting this
     *       wrong is not a rounding error: a baby measured against the adult mesh sits in a canvas
     *       whose margin nothing ever paints, and the parity metric centres the two images inside
     *       their union, so two identical silhouettes come out vertically misaligned.</li>
     *   <li>{@code TropicalFishPatternLayer} - {@code modelSmall} vs {@code modelLarge}, gated
     *       on {@code state.pattern.base()}. The default pattern (KOB) is in {@code SMALL}, so
     *       only {@code modelSmall} renders for the headless tropical fish; the previous "walk
     *       both" behaviour over-padded the canvas by one Y-pixel because modelLarge's left_fin
     *       reaches further up the Y axis than any modelSmall opaque texel does. The asset-
     *       renderer's submit-side pipeline picks the same single Model and produced a tighter
     *       canvas, so vanilla's PNG used to land 103x94 while the asset-renderer's came in
     *       103x93 - this gate brings the harness onto the same single-Model bound as the
     *       in-game renderer and the parity-test compare-target.</li>
     * </ul>
     * When the layer isn't on the recognised multi-model list, the full Model field set is
     * returned so any future layer with a single Model field "just works" (single field = single
     * Model = no over-pad risk).
     */
    private static List<Model<?>> findLayerModels(RenderLayer<?, ?> layer, EntityRenderState state) {
        List<Model<?>> models = new java.util.ArrayList<>();
        // Walk every Model<?> field by name so the multi-model gate below can pick by field name
        // instead of by index (field declaration order is JVM-stable per class but easier to
        // reason about with explicit names).
        java.util.LinkedHashMap<String, Model<?>> fieldModels = new java.util.LinkedHashMap<>();
        Class<?> cls = layer.getClass();
        while (cls != null && cls != RenderLayer.class && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(layer);
                    if (Model.class.isAssignableFrom(f.getType())) {
                        if (value instanceof Model<?> m) fieldModels.putIfAbsent(f.getName(), m);
                        continue;
                    }
                    // Worn armour is held as a set of four meshes rather than as one field, so a walk
                    // that only looked for a Model found nothing on the armour layer and measured a
                    // body with no armour standing proud of it. The set is a record, so its
                    // components are the meshes; naming them by field and slot keeps the picker below
                    // able to tell them apart.
                    //
                    // The component is admitted on what it HOLDS, not on what it declares: the set is
                    // generic in its mesh type, so every component erases to Object and a declared-type
                    // test rejects all four. That is what left the armour unmeasured while the gate
                    // above was already open for it.
                    if (value != null && value.getClass().isRecord()) {
                        for (java.lang.reflect.RecordComponent component : value.getClass().getRecordComponents()) {
                            java.lang.reflect.Method accessor = component.getAccessor();
                            accessor.setAccessible(true);
                            if (accessor.invoke(value) instanceof Model<?> m)
                                fieldModels.putIfAbsent(f.getName() + "." + component.getName(), m);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        if (fieldModels.isEmpty()) return models;
        java.util.LinkedHashMap<String, Model<?>> ofAge = atAge(state, fieldModels);
        String activeName = pickActiveModelName(layer, state, ofAge.keySet());
        if (activeName != null) {
            Model<?> active = ofAge.get(activeName);
            if (active != null) {
                models.add(active);
                return models;
            }
        }
        models.addAll(ofAge.values());
        return models;
    }

    /**
     * The models of the age {@code state} is at, when the layer names both ages, else all of them.
     *
     * <p>The age pick is by <b>group</b> rather than by model, where a group is one field of the
     * layer: a field holding a single mesh is its own group, and a field holding a set of them is one
     * group of four. A layer naming an adult mesh beside a baby one therefore resolves to exactly the
     * one mesh it always did, while the armour layer resolves to all four of the age it wears - which
     * is what its {@code submit} draws, one slot at a time.
     *
     * @param state the render state being measured
     * @param fieldModels the layer's models keyed by field, in declaration order
     * @return the age-matching subset, or {@code fieldModels} when the layer names only one age
     */
    private static java.util.LinkedHashMap<String, Model<?>> atAge(
        EntityRenderState state, java.util.LinkedHashMap<String, Model<?>> fieldModels
    ) {
        java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
        for (String name : fieldModels.keySet()) groups.add(groupOf(name));
        String chosen = pickAgeModelName(state, groups);
        if (chosen == null) return fieldModels;
        java.util.LinkedHashMap<String, Model<?>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Model<?>> entry : fieldModels.entrySet())
            if (chosen.equals(groupOf(entry.getKey()))) out.put(entry.getKey(), entry.getValue());
        return out.isEmpty() ? fieldModels : out;
    }

    /** The field a model name belongs to - the whole name, or the part before a set component's dot. */
    private static String groupOf(String modelName) {
        int dot = modelName.indexOf('.');
        return dot < 0 ? modelName : modelName.substring(0, dot);
    }

    /**
     * For layers whose {@code submit()} picks one of several Model fields based on state, returns
     * the field name of the model that submit() would render at {@code state}. Returns
     * {@code null} for layers not on the recognised multi-model list - the caller then falls back
     * to walking every Model field.
     *
     * <p>Detection is by layer class simple name so subclasses that don't override the gate also
     * pick up the parent's selection rule. Reflection over {@code state} accessors keeps the
     * recognised set decoupled from concrete state types (the harness doesn't link against
     * {@code TropicalFishRenderState} or {@code TropicalFish.Pattern} directly).
     */
    private static @org.jetbrains.annotations.Nullable String pickActiveModelName(
        RenderLayer<?, ?> layer, EntityRenderState state, java.util.Set<String> fieldNames
    ) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            if ("TropicalFishPatternLayer".equals(simple)) {
                // state.pattern is a TropicalFish$Pattern enum; pattern.base() returns
                // TropicalFish$Pattern$Base, an enum with SMALL / LARGE values. Resolve via
                // reflection so the harness's bounds module doesn't pull in the fish package.
                try {
                    java.lang.reflect.Field patternField = state.getClass().getField("pattern");
                    Object pattern = patternField.get(state);
                    if (pattern != null) {
                        Object base = pattern.getClass().getMethod("base").invoke(pattern);
                        String baseName = base == null ? "" : base.toString();
                        if ("SMALL".equals(baseName)) return "modelSmall";
                        if ("LARGE".equals(baseName)) return "modelLarge";
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                // Default to the small model when the state probe fails - matches vanilla's
                // DEFAULT_VARIANT (KOB, WHITE, WHITE) whose base is SMALL.
                if (fieldNames.contains("modelSmall")) return "modelSmall";
            }
        }
        return null;
    }

    /**
     * For a layer holding both an adult and a baby mesh, returns the field name of the one its
     * {@code submit} would draw at {@code state}; {@code null} when the layer names no baby mesh,
     * leaving the caller's other rules to answer.
     *
     * <p>Vanilla names the pair rather than flagging it - {@code babyModel} beside {@code model} or
     * {@code adultModel}, {@code noHatBabyModel} beside {@code noHatModel} - so the baby mesh is the
     * field whose name says baby and the adult is the first that does not. Matching on the name
     * keeps every such layer covered at once, including ones a later version adds; matching on the
     * class would cover only the ones already known to be wrong.
     *
     * @param state the render state being measured
     * @param fieldNames the layer's {@code Model} field names, in declaration order
     * @return the field name of the age-appropriate mesh, or {@code null} when the layer names none
     */
    private static @org.jetbrains.annotations.Nullable String pickAgeModelName(
        EntityRenderState state, java.util.Set<String> fieldNames
    ) {
        String baby = null;
        String adult = null;
        for (String name : fieldNames) {
            if (name.toLowerCase(Locale.ROOT).contains("baby")) {
                if (baby == null) baby = name;
            } else if (adult == null) adult = name;
        }
        if (baby == null || adult == null) return null;
        return state instanceof LivingEntityRenderState living && living.isBaby ? baby : adult;
    }

    /**
     * Returns the renderer's primary texture as a {@link NativeImage}, suitable for alpha
     * sampling. Looks up {@code getTextureLocation(state)} reflectively because vanilla
     * declares it {@code protected} on every {@link EntityRenderer} subclass; results are
     * cached in {@link #textureCache} keyed by resource location so repeat renders on the
     * same entity (variant sweeps, side-by-side captures) don't reopen the resource each call.
     * Returns {@code null} when the renderer hides the method, the location does not resolve
     * to a resource, or the PNG fails to decode - the bounds walker degrades to including all
     * polygons in that case.
     */
    private NativeImage entityTexture(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Identifier location = invokeGetTextureLocation(renderer, state);
        return location == null ? null : loadTexture(location);
    }

    /**
     * Loads a texture PNG as a {@link NativeImage} for alpha sampling, cached in
     * {@link #textureCache} keyed by resource location so repeat renders on the same texture
     * (variant sweeps, an equipment layer shared across family members) don't reopen the resource.
     * Returns {@code null} when the location resolves to no resource or the PNG fails to decode.
     *
     * @param location the texture resource location
     * @return the decoded image, or {@code null}
     */
    private NativeImage loadTexture(Identifier location) {
        NativeImage cached = textureCache.get(location);
        if (cached != null) return cached;
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(location).orElse(null);
            if (resource == null) return null;
            NativeImage image;
            try (InputStream stream = resource.open()) {
                image = NativeImage.read(stream);
            }
            textureCache.put(location, image);
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * The texture an equipment / decor layer draws its overlay mesh with - a plain equipment PNG at
     * {@code textures/entity/equipment/<layerType>/<material>.png}, the same file vanilla's
     * {@code EquipmentClientInfo.Layer.getTextureLocation} resolves and the asset renderer samples.
     * Resolved from the layer's own {@code LayerType} field and the worn item's equipment-asset id.
     * <p>
     * The bounds walk must sample the overlay through THIS texture, not the wearer's body texture: a
     * saddle is a few opaque straps over a mostly-transparent sheet, so measuring its body-shaped mesh
     * against the opaque body over-pads the canvas; a dedicated saddle mesh (the camel's pommel) maps
     * to transparent body texels and is dropped, cropping the canvas. Both are the wrong silhouette.
     *
     * @param layer the render layer being walked
     * @param state the render state carrying the worn item
     * @return the equipment texture, or {@code null} when the layer carries none (a non-equipment
     *     layer, or an unresolved asset) so the caller falls back to the body texture
     */
    private NativeImage equipmentTexture(RenderLayer<?, ?> layer, EntityRenderState state) {
        EquipmentClientInfo.LayerType layerType = reflectLayerType(layer, state);
        if (layerType == null) return null;
        ItemStack worn = firstWornItem(state);
        if (worn == null || worn.isEmpty()) return null;
        var equippable = worn.get(DataComponents.EQUIPPABLE);
        if (equippable == null) return null;
        var assetKey = equippable.assetId().orElse(null);
        if (assetKey == null) return null;
        // Resolve the texture through vanilla's own EquipmentClientInfo, exactly as the render does: the
        // layer's texture id is not always the asset id (a llama carpet's asset is white_carpet but its
        // texture is llama_body/white), so the id can't be pasted into the path directly.
        EquipmentAssetManager assets = equipmentAssetManager(layer);
        if (assets == null) return null;
        EquipmentClientInfo info = assets.get(assetKey);
        List<EquipmentClientInfo.Layer> layers = info.getLayers(layerType);
        if (layers.isEmpty()) return null;
        Identifier texture = layers.getFirst().getTextureLocation(layerType);
        NativeImage image = loadTexture(texture);
        if (image != null && RESOLVED_EQUIPMENT_TEXTURES.add(texture))
            LOG.info("equipment bounds texture: {} -> {}", layer.getClass().getSimpleName(), texture);
        return image;
    }

    /**
     * The equipment asset manager an equipment layer resolves its textures through - reflected off the
     * {@link EquipmentLayerRenderer} every equipment layer holds. The one global instance, reached via
     * the layer so no client-wide accessor is needed.
     *
     * @param layer the render layer
     * @return the asset manager, or {@code null} when the layer holds no equipment renderer
     */
    private static EquipmentAssetManager equipmentAssetManager(RenderLayer<?, ?> layer) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (!EquipmentLayerRenderer.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object renderer = f.get(layer);
                    if (renderer == null) continue;
                    for (Class<?> rc = renderer.getClass(); rc != null && rc != Object.class; rc = rc.getSuperclass())
                        for (Field rf : rc.getDeclaredFields()) {
                            if (!EquipmentAssetManager.class.isAssignableFrom(rf.getType())) continue;
                            rf.setAccessible(true);
                            if (rf.get(renderer) instanceof EquipmentAssetManager assets) return assets;
                        }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        return null;
    }

    /** Equipment textures logged once each, so a full sweep prints one line per distinct shell. */
    private static final java.util.Set<Identifier> RESOLVED_EQUIPMENT_TEXTURES = Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * The layer's {@code EquipmentClientInfo.LayerType} - the equipment sub-directory (SimpleEquipmentLayer
     * / *ArmorLayer / decor layers all hold one). A layer with no such field is not an equipment layer.
     *
     * @param layer the render layer
     * @return the layer type, or {@code null} when the layer declares none
     */
    private static EquipmentClientInfo.LayerType reflectLayerType(RenderLayer<?, ?> layer, EntityRenderState state) {
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (!EquipmentClientInfo.LayerType.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(layer) instanceof EquipmentClientInfo.LayerType type) return type;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        // Worn humanoid armour picks its layer per slot inside submit, so there is no one field and no
        // one answer - but for measuring there is: a baby reads the baby sheet in all four slots, and an
        // adult's leggings sheet paints a subset of the boxes its chestplate and boots already reserve
        // on the layer-1 sheet, so the union either way is the layer-1 one.
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            if (c.getSimpleName().equals("HumanoidArmorLayer"))
                return state instanceof LivingEntityRenderState living && living.isBaby
                    ? EquipmentClientInfo.LayerType.HUMANOID_BABY
                    : EquipmentClientInfo.LayerType.HUMANOID;
        // A layer that hardcodes its LayerType inline in submit rather than storing it in a field
        // (the wolf's body armour, the llama's carpet) can't be reflected off an instance; name it.
        for (Class<?> c = layer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String name = HARDCODED_LAYER_TYPES.get(c.getSimpleName());
            if (name != null) return EquipmentClientInfo.LayerType.valueOf(name);
        }
        return null;
    }

    /** Layer classes that hardcode their {@code LayerType} in a method body, mapped to that type's enum name. */
    private static final Map<String, String> HARDCODED_LAYER_TYPES = Map.of(
        "WolfArmorLayer", "WOLF_BODY",
        "LlamaDecorLayer", "LLAMA_BODY");

    /**
     * The first non-empty {@link ItemStack} slot on the render state - the item an equipment layer
     * draws. Each equipment reference selects exactly one slot, so the one worn item is unambiguous.
     *
     * @param state the render state
     * @return the worn stack, or {@code null} when the subject wears nothing readable
     */
    private static ItemStack firstWornItem(EntityRenderState state) {
        for (Class<?> c = state.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field field : c.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(state) instanceof ItemStack stack && !stack.isEmpty()) return stack;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        return null;
    }

    /**
     * Reflectively invokes the renderer's {@code getTextureLocation(state)} method. Walks the
     * class hierarchy (LivingEntityRenderer, MobRenderer, AgeableMobRenderer subclasses define
     * it at varying depths) and matches by name + state-compatible parameter. Returns
     * {@code null} when no compatible override exists - block-entity-style renderers and the
     * ender dragon's custom path do not declare it.
     */
    private static Identifier invokeGetTextureLocation(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("getTextureLocation") || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(state)) continue;
                if (!Identifier.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(renderer, state);
                    return value instanceof Identifier rl ? rl : null;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static final Field MODEL_PART_CHILDREN;
    private static final Field MODEL_PART_CUBES;
    static {
        try {
            MODEL_PART_CHILDREN = ModelPart.class.getDeclaredField("children");
            MODEL_PART_CHILDREN.setAccessible(true);
            MODEL_PART_CUBES = ModelPart.class.getDeclaredField("cubes");
            MODEL_PART_CUBES.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("ModelPart structure changed in this Minecraft version - children/cubes fields not found", e);
        }
    }

    /**
     * Emits one {@code [PX] TRI} line per visible-polygon triangle whose projected bbox
     * intersects {@link #PIXEL_DUMP_RECT}, for diagnostic comparison against
     * {@code ModelEngine}'s same-named line. Builds the full canvas-fit + chirality + iso
     * + LER chain pose stack vanilla's {@code dispatcher.submit} composes internally so the
     * emitted {@code s0/s1/s2} coordinates are in the same pixel-space frame the asset-renderer
     * side emits. Walks both the primary model and every active layer (matching the bounds
     * walker's coverage). Triangulation is fixed at {@code (v0,v1,v2)+(v0,v2,v3)} to match
     * {@code EntityGeometryKit.contributeTriangles}.
     * <p>
     * No-op when {@link #PIXEL_DUMP_RECT} is unset.
     */
    void dumpTrianglesIfRequested(
        EntityRenderer<?, ?> renderer,
        EntityRenderState state,
        float translateX,
        float translateY,
        float canvasScale,
        Quaternionf effectiveRotation
    ) {
        if (PIXEL_DUMP_RECT == null) return;
        Model<?> model = tryGetModel(renderer, state);
        if (model == null) return;

        PoseStack ps = new PoseStack();
        ps.translate(translateX, translateY, 0.0f);
        ps.scale(canvasScale, canvasScale, canvasScale);
        // Chirality compensation - mirrors renderInternal exactly.
        ps.scale(1.0f, 1.0f, -1.0f);
        ps.mulPose(effectiveRotation);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> && state instanceof LivingEntityRenderState living) {
            ps.scale(living.scale, living.scale, living.scale);
            invokeRendererSetupRotations(renderer, state, ps, /*bodyRot*/ 0.0f, living.scale);
            ps.scale(-1.0f, -1.0f, 1.0f);
            invokeRendererScale(renderer, state, ps);
            ps.translate(0.0f, -1.501f, 0.0f);
        } else if (renderer.getClass().getSimpleName().equals("EnderDragonRenderer")) {
            ps.translate(0.0f, 0.0f, 1.0f);
            ps.scale(-1.0f, -1.0f, 1.0f);
            ps.translate(0.0f, -1.501f, 0.0f);
        }

        walkPolyTrianglesImpl(model.root(), "root", ps);
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            boolean headless = Boolean.getBoolean("refharness.headless");
            List<? extends RenderLayer<?, ?>> layers = layersOf(ler);
            for (RenderLayer<?, ?> layer : layers) {
                if (!isLayerActiveForState(layer, state)) continue;
                for (Model<?> layerModel : findLayerModels(layer, state)) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Model raw = layerModel;
                    if (!headless) {
                        try { raw.setupAnim(state); } catch (RuntimeException ignored) {}
                    }
                    walkPolyTrianglesImpl(layerModel.root(), "layer:" + layer.getClass().getSimpleName(), ps);
                }
            }
        }
    }

    private static void walkPolyTrianglesImpl(ModelPart part, String bonePath, PoseStack ps) {
        if (!part.visible) return;
        Map<String, ModelPart> children = childrenOf(part);
        List<ModelPart.Cube> cubes = cubesOf(part);
        if (cubes.isEmpty() && children.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            PoseStack.Pose pose = ps.last();
            int cubeIndex = 0;
            for (ModelPart.Cube cube : cubes) {
                int polyIndex = 0;
                for (ModelPart.Polygon polygon : cube.polygons) {
                    emitPolygonTriangles(polygon, pose, bonePath, cubeIndex, polyIndex);
                    polyIndex++;
                }
                cubeIndex++;
            }
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            walkPolyTrianglesImpl(entry.getValue(), bonePath + "/" + entry.getKey(), ps);
        }
        ps.popPose();
    }

    /**
     * Transforms a polygon's 4 vertices through {@code pose}, triangulates as
     * {@code (v0,v1,v2)+(v0,v2,v3)}, and emits one {@code [PX] TRI} line per triangle whose
     * screen-space bbox intersects {@link #PIXEL_DUMP_RECT}. Coordinates are emitted in
     * post-pose pixel-space - the ortho with {@code invertY=true} maps math-Y to screen-Y so
     * pose-space-Y == screen-Y (origin at top, increasing downward), matching the
     * asset-renderer's {@code s0/s1/s2} convention.
     */
    private static void emitPolygonTriangles(ModelPart.Polygon polygon, PoseStack.Pose pose,
                                              String bonePath, int cubeIndex, int polyIndex) {
        ModelPart.Vertex[] verts = polygon.vertices();
        if (verts.length < 4) return;
        Vector3f v0 = pose.pose().transformPosition(verts[0].worldX(), verts[0].worldY(), verts[0].worldZ(), new Vector3f());
        Vector3f v1 = pose.pose().transformPosition(verts[1].worldX(), verts[1].worldY(), verts[1].worldZ(), new Vector3f());
        Vector3f v2 = pose.pose().transformPosition(verts[2].worldX(), verts[2].worldY(), verts[2].worldZ(), new Vector3f());
        Vector3f v3 = pose.pose().transformPosition(verts[3].worldX(), verts[3].worldY(), verts[3].worldZ(), new Vector3f());

        String tagA = bonePath + "/cube" + cubeIndex + "/poly" + polyIndex + "/triA";
        String tagB = bonePath + "/cube" + cubeIndex + "/poly" + polyIndex + "/triB";
        emitTriangleIfIntersects(tagA, v0, v1, v2);
        emitTriangleIfIntersects(tagB, v0, v2, v3);
    }

    private static void emitTriangleIfIntersects(String debugTag, Vector3f s0, Vector3f s1, Vector3f s2) {
        float minX = Math.min(s0.x(), Math.min(s1.x(), s2.x()));
        float maxX = Math.max(s0.x(), Math.max(s1.x(), s2.x()));
        float minY = Math.min(s0.y(), Math.min(s1.y(), s2.y()));
        float maxY = Math.max(s0.y(), Math.max(s1.y(), s2.y()));
        if (maxX < PIXEL_DUMP_RECT[0] || minX > PIXEL_DUMP_RECT[2]) return;
        if (maxY < PIXEL_DUMP_RECT[1] || minY > PIXEL_DUMP_RECT[3]) return;
        // Screen coords ARE pose-space x/y after the canvas-fit + LER chain; depth is z.
        // The harness emits one combined line per triangle to match ModelEngine.projectTriangle.
        System.out.println("[PX]\tTRI\t" + debugTag
            + "\ts0=" + s0.x() + "," + s0.y()
            + "\ts1=" + s1.x() + "," + s1.y()
            + "\ts2=" + s2.x() + "," + s2.y()
            + "\tp0=" + s0.x() + "," + s0.y() + "," + s0.z()
            + "\tp1=" + s1.x() + "," + s1.y() + "," + s1.z()
            + "\tp2=" + s2.x() + "," + s2.y() + "," + s2.z());
    }

    /**
     * Custom hierarchy walker that mirrors {@link ModelPart#render}'s visibility filtering:
     * skips parts where {@code visible} is false, skips own cubes where {@code skipDraw}
     * is true. Vanilla's {@link ModelPart#getExtentsForGui} ignores both flags, so it
     * would include polygons that {@code render()} skips.
     * <p>
     * Additionally, when {@code texture} is non-null, polygons whose four corner UVs all
     * sample fully-transparent pixels are excluded from the bounds. This catches plane-style
     * cubes whose authored extent dwarfs the visible texture region (warden's
     * {@code left_tendril}/{@code right_tendril}, wither ribcage planes) and would otherwise
     * pad the bounds by an unrendered margin and shift the on-canvas centre. Mirrors
     * vanilla's per-pixel alpha discard at the rasteriser level - if every vertex of a quad
     * lands on alpha=0, the rasteriser would discard every pixel inside that quad too.
     * Polygons with opaque corners but transparent interior (rare for cube-based MC entity
     * textures) still expand the bounds.
     */
    private static void walkVisibleExtents(ModelPart part, PoseStack ps, NativeImage texture, Consumer<? super org.joml.Vector3fc> output) {
        walkVisibleExtentsImpl(part, "root", ps, texture, output);
    }

    private static void walkVisibleExtentsImpl(ModelPart part, String bonePath, PoseStack ps, NativeImage texture, Consumer<? super org.joml.Vector3fc> output) {
        if (!part.visible) return;
        Map<String, ModelPart> children = childrenOf(part);
        List<ModelPart.Cube> cubes = cubesOf(part);
        if (cubes.isEmpty() && children.isEmpty()) return;
        ps.pushPose();
        part.translateAndRotate(ps);
        if (!part.skipDraw) {
            PoseStack.Pose pose = ps.last();
            int cubeIndex = 0;
            for (ModelPart.Cube cube : cubes) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    contributePolygonExtents(polygon, pose, texture, output, bonePath, cubeIndex, cube);
                }
                cubeIndex++;
            }
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            walkVisibleExtentsImpl(entry.getValue(), bonePath + "/" + entry.getKey(), ps, texture, output);
        }
        ps.popPose();
    }

    /**
     * Expands the bounds accumulator with positions inside {@code polygon} that map to
     * opaque texels on {@code texture}. Replaces an older binary include/exclude filter
     * (which contributed all 4 vertex corners whenever <em>any</em> sampled texel was
     * opaque, ballooning bounds on plane-cubes like the warden's tendril where a small
     * sticker shared a 16×16 quad with mostly-transparent pixels) and a sparser 5×5 sample
     * version (which under-cut bounds at polygon edges when the corner texels happened to
     * be transparent - the bee wings, frog feet, and warden tendril tips all visibly clipped).
     * <p>
     * Algorithm: walk every texel inside the polygon's UV bounding box, accumulate the
     * tightest sub-rectangle that contains all opaque texels (snapped to texel edges so an
     * opaque texel at (px, py) extends its UV contribution to {@code [px/W, (px+1)/W] ×
     * [py/H, (py+1)/H]}, capturing the texel's full footprint). Then bilinearly interpolate
     * the four corners of that opaque sub-rectangle through the polygon's four vertex
     * positions and feed those four 3D positions to the bounds accumulator. Net effect:
     * <ul>
     *   <li>Fully opaque polygons: opaque box equals polygon UV box - the four corners are
     *       just the polygon's four vertices, identical to the legacy 4-corner behaviour.</li>
     *   <li>Polygons with a sparse opaque sticker (warden tendrils, etc.): opaque box is
     *       tight to the sticker's texels, four corners reflect just the sticker extent.</li>
     *   <li>Fully transparent polygons: no opaque texels, polygon contributes nothing.</li>
     * </ul>
     * <p>
     * Identifies the four vertices' corner roles (BL/BR/TR/TL) by their (u, v) values matching
     * the polygon's UV-axis-aligned bounding box. If the polygon's UVs aren't axis-aligned
     * (rare for vanilla cube faces), classification fails and we fall back to contributing the
     * 4 raw vertex positions.
     * <p>
     * Cost is O(W × H) per polygon's UV box (e.g. 256 alpha lookups for a 16×16 face). The
     * harness pre-pass measures ~5000 polygons across 100 entities, so total alpha-walk work
     * is ~1.3M lookups - tens of milliseconds in practice, dominated by setupAnim and pose
     * stack work.
     */
    private static final boolean BOUNDS_DUMP = Boolean.getBoolean("refharness.boundsDump");

    private static void contributePolygonExtents(ModelPart.Polygon polygon, PoseStack.Pose pose, NativeImage texture, Consumer<? super org.joml.Vector3fc> output,
                                                  String bonePath, int cubeIndex, ModelPart.Cube cube) {
        if (texture == null) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            return;
        }
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            return;
        }
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY, vMax = Float.NEGATIVE_INFINITY;
        for (ModelPart.Vertex vertex : polygon.vertices()) {
            uMin = Math.min(uMin, vertex.u());
            uMax = Math.max(uMax, vertex.u());
            vMin = Math.min(vMin, vertex.v());
            vMax = Math.max(vMax, vertex.v());
        }
        String dumpPrefix = BOUNDS_DUMP
            ? String.format("[BD] bone=%s cube=%d cubebbox=(%g,%g,%g)-(%g,%g,%g)",
                bonePath, cubeIndex,
                cube.minX, cube.minY, cube.minZ,
                cube.maxX, cube.maxY, cube.maxZ)
            : null;
        // Skip degenerate (zero-area) polygons. Plane cubes (e.g. warden tendril at 16×16×0)
        // contribute four zero-area edge polygons whose UVs collapse to a line; they render
        // no pixels, but their 4 vertex positions span the full cube extent. Dropping them
        // is bounds-preserving because the visible-face polygons already cover the screen
        // extent through their per-opaque-texel contributions.
        if (uMin == uMax || vMin == vMax) {
            if (dumpPrefix != null) System.out.println(dumpPrefix + " DEGEN_UV");
            return;
        }
        // Classify the 4 vertices by which corner of the UV rect they sit on.
        ModelPart.Vertex[] verts = polygon.vertices();
        ModelPart.Vertex bl = null, br = null, tr = null, tl = null;
        float eps = 1e-4f;
        for (ModelPart.Vertex v : verts) {
            boolean atUMin = Math.abs(v.u() - uMin) < eps;
            boolean atUMax = Math.abs(v.u() - uMax) < eps;
            boolean atVMin = Math.abs(v.v() - vMin) < eps;
            boolean atVMax = Math.abs(v.v() - vMax) < eps;
            if (atUMin && atVMin) bl = v;
            else if (atUMax && atVMin) br = v;
            else if (atUMax && atVMax) tr = v;
            else if (atUMin && atVMax) tl = v;
        }
        if (bl == null || br == null || tr == null || tl == null) {
            for (ModelPart.Vertex vertex : verts) {
                Vector3f p = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
                output.accept(p);
            }
            if (dumpPrefix != null) System.out.println(dumpPrefix + " NON_AXIS_UV_FALLBACK_4_CORNERS");
            return;
        }
        // Walk every texel inside the polygon's UV box and accumulate the opaque-texel bbox.
        // Each texel at (px, py) covers the half-open UV rect [px/W, (px+1)/W) x
        // [py/H, (py+1)/H), so the inclusive upper bound is ceil(uMax*W) - 1, not
        // floor(uMax*W). The floor form over-includes the adjacent texel row when uMax*W
        // (or vMax*H) lands exactly on an integer boundary - which happens whenever a face
        // UV is texel-aligned, i.e. for every standard 16x16 / 32x32 / 64x64 cube face
        // including all HumanoidModel sleeves / pants / jacket outer-layer faces. This was
        // surfaced by the asset-renderer side's identical bug: piglin_brute's right_sleeve
        // WEST face has vMax = 48/64 = 0.75 exactly; with floor() the walker admitted texel
        // row 48 which is part of the adjacent left_arm UP face, making the (otherwise
        // transparent) sleeve "look" opaque and contribute its inflated extent to the
        // bounds.
        int pxMin = clampPixel((int) Math.floor(uMin * width), width);
        int pxMax = clampPixel((int) Math.ceil(uMax * width) - 1, width);
        int pyMin = clampPixel((int) Math.floor(vMin * height), height);
        int pyMax = clampPixel((int) Math.ceil(vMax * height) - 1, height);
        int firstOpaquePx = Integer.MAX_VALUE, lastOpaquePx = Integer.MIN_VALUE;
        int firstOpaquePy = Integer.MAX_VALUE, lastOpaquePy = Integer.MIN_VALUE;
        for (int py = pyMin; py <= pyMax; py++) {
            for (int px = pxMin; px <= pxMax; px++) {
                int alpha = (texture.getPixel(px, py) >>> 24) & 0xFF;
                if (alpha == 0) continue;
                if (px < firstOpaquePx) firstOpaquePx = px;
                if (px > lastOpaquePx) lastOpaquePx = px;
                if (py < firstOpaquePy) firstOpaquePy = py;
                if (py > lastOpaquePy) lastOpaquePy = py;
            }
        }
        if (firstOpaquePx == Integer.MAX_VALUE) {
            if (dumpPrefix != null) System.out.printf("%s uv_px=%d,%d,%d,%d ALL_TRANSPARENT%n",
                dumpPrefix, pxMin, pyMin, pxMax, pyMax);
            return;
        }
        // Convert opaque-pixel range to UV-space using texel edges (a texel at (px, py)
        // covers UV [px/W, (px+1)/W] × [py/H, (py+1)/H]). Clamp the resulting UV box to the
        // polygon's UV range so we don't overshoot the geometry.
        float opaqueUMin = Math.max(uMin, (float) firstOpaquePx / width);
        float opaqueUMax = Math.min(uMax, (float) (lastOpaquePx + 1) / width);
        float opaqueVMin = Math.max(vMin, (float) firstOpaquePy / height);
        float opaqueVMax = Math.min(vMax, (float) (lastOpaquePy + 1) / height);
        // Contribute the four bilinear-interpolated corners of the opaque sub-rect.
        Vector3f cBl = contributeBilinearCorner(opaqueUMin, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cBr = contributeBilinearCorner(opaqueUMax, opaqueVMin, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cTr = contributeBilinearCorner(opaqueUMax, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        Vector3f cTl = contributeBilinearCorner(opaqueUMin, opaqueVMax, uMin, uMax, vMin, vMax, bl, br, tr, tl, pose, output);
        if (dumpPrefix != null) System.out.printf(
            "%s uv_px=%d,%d,%d,%d opaque_px=%d,%d,%d,%d screen_bl=(%g,%g,%g) screen_br=(%g,%g,%g) screen_tr=(%g,%g,%g) screen_tl=(%g,%g,%g)%n",
            dumpPrefix,
            pxMin, pyMin, pxMax, pyMax,
            firstOpaquePx, firstOpaquePy, lastOpaquePx, lastOpaquePy,
            cBl.x(), cBl.y(), cBl.z(),
            cBr.x(), cBr.y(), cBr.z(),
            cTr.x(), cTr.y(), cTr.z(),
            cTl.x(), cTl.y(), cTl.z());
    }

    private static Vector3f contributeBilinearCorner(
        float u, float v,
        float uMin, float uMax, float vMin, float vMax,
        ModelPart.Vertex bl, ModelPart.Vertex br, ModelPart.Vertex tr, ModelPart.Vertex tl,
        PoseStack.Pose pose, Consumer<? super org.joml.Vector3fc> output
    ) {
        float s = (u - uMin) / (uMax - uMin);
        float t = (v - vMin) / (vMax - vMin);
        float w00 = (1 - s) * (1 - t);
        float w10 = s * (1 - t);
        float w11 = s * t;
        float w01 = (1 - s) * t;
        float px = w00 * bl.worldX() + w10 * br.worldX() + w11 * tr.worldX() + w01 * tl.worldX();
        float py = w00 * bl.worldY() + w10 * br.worldY() + w11 * tr.worldY() + w01 * tl.worldY();
        float pz = w00 * bl.worldZ() + w10 * br.worldZ() + w11 * tr.worldZ() + w01 * tl.worldZ();
        Vector3f p = pose.pose().transformPosition(px, py, pz, new Vector3f());
        output.accept(p);
        return p;
    }

    /**
     * Legacy binary include/exclude polygon filter, retained as a reference. Replaced in the
     * walk path by {@link #contributePolygonExtents}, which keeps a similar 5×5 sampling
     * approach but contributes <em>per-opaque-sample</em> bilinearly-interpolated positions
     * to the bounds instead of the full 4 vertex corners. That's strictly tighter for
     * polygons with sparse opaque regions (warden tendrils) while matching the legacy
     * behaviour for fully-opaque polygons (every sample is opaque, all 25 contribute, the
     * union of their bilinear positions equals the 4-corner bounds).
     */
    @SuppressWarnings("unused")
    private static boolean polygonAllTransparent(ModelPart.Polygon polygon, NativeImage texture) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) return false;
        float uMin = Float.POSITIVE_INFINITY, uMax = Float.NEGATIVE_INFINITY;
        float vMin = Float.POSITIVE_INFINITY, vMax = Float.NEGATIVE_INFINITY;
        for (ModelPart.Vertex vertex : polygon.vertices()) {
            uMin = Math.min(uMin, vertex.u());
            uMax = Math.max(uMax, vertex.u());
            vMin = Math.min(vMin, vertex.v());
            vMax = Math.max(vMax, vertex.v());
        }
        // Sample on a 5x5 grid spanning the UV box (inclusive of edges). Step=1/4 puts a
        // sample at each corner, each edge midpoint, the four edge quarter-points, and the
        // four interior quarter-points. 25 samples is overkill for huge faces but makes the
        // tiny-face case robust against off-by-one alpha-edge artifacts.
        for (int j = 0; j <= 4; j++) {
            float v = vMin + (vMax - vMin) * (j / 4.0f);
            int y = clampPixel((int) Math.floor(v * height), height);
            for (int i = 0; i <= 4; i++) {
                float u = uMin + (uMax - uMin) * (i / 4.0f);
                int x = clampPixel((int) Math.floor(u * width), width);
                int alpha = (texture.getPixel(x, y) >>> 24) & 0xFF;
                if (alpha != 0) return false;
            }
        }
        return true;
    }

    private static int clampPixel(int value, int size) {
        if (value < 0) return 0;
        if (value >= size) return size - 1;
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> childrenOf(ModelPart part) {
        try {
            return (Map<String, ModelPart>) MODEL_PART_CHILDREN.get(part);
        } catch (IllegalAccessException e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> cubesOf(ModelPart part) {
        try {
            return (List<ModelPart.Cube>) MODEL_PART_CUBES.get(part);
        } catch (IllegalAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Calls the renderer's {@code setupRotations(state, poseStack, bodyRot, scale)} override
     * on the given pose stack, mirroring vanilla's virtual dispatch in
     * {@link LivingEntityRenderer#submit}. Several renderers replace the base implementation
     * with custom pose math (squid translates the body up before rotation and back down by
     * a different amount, shulker rotates around its peek pivot, fox tilts when sleeping,
     * etc.); without this dispatch the bounds walker silently uses the base
     * {@code mulPose(Y, 180-bodyRot)} and disagrees with render whenever an override adds a
     * translation - the canonical symptom is squid tentacles falling below the canvas.
     * <p>
     * Walks the class hierarchy looking for a {@code setupRotations} method whose first
     * argument is state-compatible and whose remaining three arguments are
     * {@code (PoseStack, float, float)}. Both the bridge variant
     * ({@code (LivingEntityRenderState, ...)}) and the narrowed variant
     * ({@code (SquidRenderState, ...)}) live on the renderer class - either resolves to the
     * same final method via the standard JVM bridge so we accept the first match.
     * <p>
     * Falls back to {@code mulPose(YP.rotationDegrees(180 - bodyRot))} on reflective failure
     * so the iso-pose calibration still holds for renderers that hide the method or throw
     * during pose extraction.
     */
    private static void invokeRendererSetupRotations(
        EntityRenderer<?, ?> renderer,
        EntityRenderState state,
        PoseStack ps,
        float bodyRot,
        float scale
    ) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("setupRotations") || m.getParameterCount() != 4) continue;
                Class<?>[] params = m.getParameterTypes();
                if (!params[0].isInstance(state) || params[1] != PoseStack.class) continue;
                if (params[2] != float.class || params[3] != float.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(renderer, state, ps, bodyRot, scale);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        ps.mulPose(Axis.YP.rotationDegrees(180.0f - bodyRot));
    }

    /**
     * Calls the renderer's {@code scale(state, poseStack)} override on the given
     * pose stack, mirroring what vanilla's {@link LivingEntityRenderer#submit} does.
     * Reached via reflection because the method is protected; falls back to no-op if
     * the class hierarchy walk doesn't find it.
     */
    private static void invokeRendererScale(EntityRenderer<?, ?> renderer, EntityRenderState state, PoseStack ps) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("scale") || m.getParameterCount() != 2) continue;
                Class<?>[] params = m.getParameterTypes();
                if (!params[0].isInstance(state) || params[1] != PoseStack.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(renderer, state, ps);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Resolves the entity model from a renderer. {@link LivingEntityRenderer} exposes it
     * via {@code getModel()}; for everything else we walk the class hierarchy looking for
     * a field whose declared type is a {@link Model} subclass.
     * <p>
     * For variant-using renderers ({@code CowRenderer}, {@code PigRenderer},
     * {@code ChickenRenderer}) the renderer keeps a {@code Map<ModelType, AdultAndBabyModelPair>}
     * instead of a single model and mutates {@code this.model} inside {@code submit} to the
     * variant-specific instance before rendering. The bounds walker runs <em>before</em>
     * any submit, so {@code getModel()} returns the constructor's default - which has
     * different geometry than the actual variant being rendered (cow_warm uses
     * {@code WarmCowModel} with bigger horns; the default {@code CowModel} has none).
     * Resolves the variant-specific model up front via {@link #tryResolveVariantModel} so
     * bounds reflect the actual rendered geometry.
     * <p>
     * {@code AgeableMobRenderer} mutates {@code this.model} the same way, picking
     * {@code babyModel} or {@code adultModel} from {@code state.isBaby} at the top of its
     * {@code submit}. Since the walker runs first, {@code getModel()} hands back whichever age
     * the <em>previous</em> subject rendered - so a baby measured against the adult mesh sits
     * inside a canvas sized for a full-grown body, and an adult measured against the baby mesh
     * is scaled up until it clips the canvas edges. {@link #tryResolveAgeModel} replicates the
     * pick, making the measurement independent of render order.
     */
    private static Model<?> tryGetModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        Model<?> variantModel = tryResolveVariantModel(renderer, state);
        if (variantModel != null) return variantModel;
        Model<?> shapeModel = tryResolveShapeModel(renderer, state);
        if (shapeModel != null) return shapeModel;
        Model<?> sizeModel = tryResolveSizeModel(renderer, state);
        if (sizeModel != null) return sizeModel;
        Model<?> ageModel = tryResolveAgeModel(renderer, state);
        if (ageModel != null) return ageModel;
        if (renderer instanceof LivingEntityRenderer<?, ?, ?> ler) {
            return ler.getModel();
        }
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Model.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object value = f.get(renderer);
                        if (value instanceof Model<?> m) return m;
                    } catch (IllegalAccessException ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Resolves the age-specific model for {@code AgeableMobRenderer} subclasses by replicating the
     * {@code this.model = state.isBaby ? babyModel : adultModel} assignment its {@code submit} makes
     * before rendering. Reads the two private fields off the {@code AgeableMobRenderer} declaration
     * itself, so subclasses inherit it. Returns {@code null} for any renderer that is not one -
     * the caller then falls back to {@code getModel()}.
     *
     * @param renderer the entity renderer being measured
     * @param state the render state whose {@code isBaby} drives the pick
     * @return the model the render will actually draw, or {@code null} when the renderer has no age pair
     */
    private static Model<?> tryResolveAgeModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        if (!(state instanceof LivingEntityRenderState living)) return null;
        for (Class<?> c = renderer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getSimpleName().equals("AgeableMobRenderer")) continue;
            try {
                Field field = c.getDeclaredField(living.isBaby ? "babyModel" : "adultModel");
                field.setAccessible(true);
                return field.get(renderer) instanceof Model<?> m ? m : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Forces the visibility of named bones on the mesh a subject will be measured and drawn with, and
     * returns what they were so the caller can put them back.
     *
     * <p>The harness renders the authored bind pose, which means {@code setupAnim} never runs - and
     * {@code setupAnim} is where vanilla turns a chest, a horn, an egg belly or a base plate on and
     * off from its render state. Setting the state field the model would have read therefore changes
     * nothing; the flag has to be written onto the part itself, and it has to be written before the
     * bounds walk as well as before the draw, or the selection is framed against the mesh it is not.
     *
     * <p>A bone the mesh does not carry throws. A silently skipped pin renders the default under a
     * name that claims a bone was toggled, which is a reference that cannot fail.
     *
     * @param renderer the renderer whose model will be drawn
     * @param state the render state the model is resolved through
     * @param pins the bones to force, mapped to the visibility to force them to
     * @return each pinned part with the visibility it had, for restoring
     */
    public Map<ModelPart, Boolean> pinBones(EntityRenderer<?, ?> renderer, EntityRenderState state,
                                            Map<String, Boolean> pins) {
        if (pins.isEmpty()) return Map.of();
        Model<?> model = tryGetModel(renderer, state);
        if (model == null) throw new IllegalStateException("No model to pin bones on");
        Map<ModelPart, Boolean> previous = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> pin : pins.entrySet()) {
            ModelPart part = findPart(model.root(), pin.getKey());
            if (part == null)
                throw new IllegalStateException("No bone '" + pin.getKey() + "' on "
                    + model.getClass().getSimpleName());
            previous.put(part, part.visible);
            part.visible = pin.getValue();
        }
        return previous;
    }

    /**
     * Which mesh each size-bearing renderer keeps per size, and the render-state field that picks
     * between them.
     *
     * <p>Named rather than derived: these renderers hold plain {@code Model} fields and choose
     * inside {@code submit}, so there is nothing about the field itself that says which size it is.
     * Guessing from declaration order would measure the wrong mesh the first time vanilla reorders
     * them, and measuring the wrong mesh is the failure that reports framing rather than the render.
     *
     * @param stateField the render-state field naming the size, read as an integer, as an ordinal,
     *     or as the flag a two-mesh renderer picks on
     * @param modelFields the renderer's meshes, smallest first
     */
    private record SizeModels(String stateField, List<String> modelFields) {}

    private static final Map<String, SizeModels> SIZE_MODELS = Map.of(
        "PufferfishRenderer", new SizeModels("puffState", List.of("small", "mid", "big")),
        "SalmonRenderer", new SizeModels("variant",
            List.of("smallSalmonModel", "mediumSalmonModel", "largeSalmonModel")),
        "ArmorStandRenderer", new SizeModels("isSmall", List.of("smallModel", "bigModel")));

    /**
     * Resolves the size-specific mesh for a renderer that keeps one per size.
     *
     * <p>The pick happens inside {@code submit}, which the walker runs before, so {@code getModel()}
     * hands back the constructor's mesh for every size - a deflated pufferfish measured against the
     * fully puffed one, and a small salmon against a medium. Both sizes would then be framed for a
     * body they are not drawn on.
     *
     * @param renderer the entity renderer being measured
     * @param state the render state whose size drives the pick
     * @return the mesh the render will actually draw, or {@code null} for a renderer with no size set
     */
    private static Model<?> tryResolveSizeModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        SizeModels models = SIZE_MODELS.get(renderer.getClass().getSimpleName());
        if (models == null) return null;
        try {
            Field selector = findFieldByName(state.getClass(), models.stateField());
            if (selector == null) return null;
            selector.setAccessible(true);
            Object value = selector.get(state);
            int index = switch (value) {
                case Integer size -> size;
                case Enum<?> size -> size.ordinal();
                // A two-mesh renderer picks on a flag rather than a size, so the flag indexes the
                // list the same way an ordinal does - set means the smaller mesh, which is where
                // "smallest first" puts it.
                case Boolean small -> small ? 0 : 1;
                case null, default -> -1;
            };
            if (index < 0 || index >= models.modelFields().size()) return null;
            Field field = findFieldByName(renderer.getClass(), models.modelFields().get(index));
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(renderer) instanceof Model<?> model ? model : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** Finds a bone anywhere below {@code part}, by the name its mesh gives it. */
    private static ModelPart findPart(ModelPart part, String name) {
        for (Map.Entry<String, ModelPart> child : childrenOf(part).entrySet()) {
            if (child.getKey().equals(name)) return child.getValue();
            ModelPart found = findPart(child.getValue(), name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolves the body-shape model for a renderer that keeps two meshes and picks between them from
     * the pattern its render state carries - the tropical fish, whose twelve patterns are six on a
     * small body and six on a large one.
     *
     * <p>The pick happens inside {@code submit}, which the walker runs before, so {@code getModel()}
     * hands back the constructor's model - the small one - for every pattern. Six of the twelve would
     * then be measured against a body they are not drawn on, and cropped to it.
     *
     * @param renderer the entity renderer being measured
     * @param state the render state whose pattern drives the pick
     * @return the model the render will actually draw, or {@code null} when the renderer has no shape
     *     pair
     */
    private static Model<?> tryResolveShapeModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        try {
            Field patternField = findFieldByName(state.getClass(), "pattern");
            Field smallModel = findFieldByName(renderer.getClass(), "smallModel");
            Field largeModel = findFieldByName(renderer.getClass(), "largeModel");
            if (patternField == null || smallModel == null || largeModel == null) return null;
            patternField.setAccessible(true);
            Object pattern = patternField.get(state);
            if (pattern == null) return null;
            Method base = findMethodByName(pattern.getClass(), "base", 0);
            if (base == null) return null;
            base.setAccessible(true);
            Object shape = base.invoke(pattern);
            Field chosen = shape instanceof Enum<?> named && named.name().equals("LARGE") ? largeModel : smallModel;
            chosen.setAccessible(true);
            return chosen.get(renderer) instanceof Model<?> model ? model : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Resolves the variant-specific model for variant-using renderers (Cow, Pig, Chicken in
     * 26.1) by replicating their submit-time selection logic reflectively. Each such
     * renderer keeps a {@code Map<ModelType, AdultAndBabyModelPair<Model>>} field where the
     * key comes from {@code state.variant.modelAndTexture().model()}; the pair returns adult
     * or baby via {@code getModel(boolean)}. Returns {@code null} for renderers that don't
     * fit this pattern; the caller falls back to {@code renderer.getModel()}.
     */
    private static Model<?> tryResolveVariantModel(EntityRenderer<?, ?> renderer, EntityRenderState state) {
        try {
            Field variantField = findFieldByName(state.getClass(), "variant");
            if (variantField == null) return null;
            variantField.setAccessible(true);
            Object variant = variantField.get(state);
            if (variant == null) return null;
            Method modelAndTexture = findMethodByName(variant.getClass(), "modelAndTexture", 0);
            if (modelAndTexture == null) return null;
            modelAndTexture.setAccessible(true);
            Object mat = modelAndTexture.invoke(variant);
            if (mat == null) return null;
            Method modelKeyGetter = findMethodByName(mat.getClass(), "model", 0);
            if (modelKeyGetter == null) return null;
            modelKeyGetter.setAccessible(true);
            Object modelKey = modelKeyGetter.invoke(mat);
            if (modelKey == null) return null;
            Field modelsField = findFieldByName(renderer.getClass(), "models");
            if (modelsField == null || !Map.class.isAssignableFrom(modelsField.getType())) return null;
            modelsField.setAccessible(true);
            Object pair = ((Map<?, ?>) modelsField.get(renderer)).get(modelKey);
            if (pair == null) return null;
            // Two model-map shapes exist. Cow / pig / chicken key on an
            // {@code AdultAndBabyModelPair} (getModel(boolean) picks adult vs baby); zombie_nautilus
            // keys directly on the {@code NautilusModel} value ({@code Map<ModelType, NautilusModel>}).
            // Without this direct-model branch the pair-only path fails for zombie_nautilus and the
            // caller falls back to the DEFAULT NautilusModel - which has none of the WARM variant's
            // coral bones - so the bounds pre-pass sizes the canvas to the bare shell and the coral
            // (the ZombieNautilusCoralModel's corals sub-tree) is cropped in the reference PNG.
            if (pair instanceof Model<?> directModel) return directModel;
            Method getModel = findMethodByName(pair.getClass(), "getModel", 1);
            if (getModel == null) return null;
            getModel.setAccessible(true);
            boolean isBaby = state instanceof LivingEntityRenderState lrs && lrs.isBaby;
            Object model = getModel.invoke(pair, isBaby);
            return model instanceof Model<?> m ? m : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field findFieldByName(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
        }
        return null;
    }

    private static Method findMethodByName(Class<?> cls, String name, int arity) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
            }
        }
        return null;
    }

    /**
     * Mutable 2D bounds accumulator.
     */
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class ScreenBounds {
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        ScreenBounds() {}

        void expand(float x, float y) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        float width() { return maxX - minX; }
        float height() { return maxY - minY; }
    }

    @Override
    public void close() {
        for (NativeImage image : textureCache.values()) {
            image.close();
        }
        textureCache.clear();
    }
}
