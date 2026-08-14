package lib.minecraft.refharness.frame;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.FrameRenderer;
import lib.minecraft.refharness.pip.PipScope;
import lib.minecraft.refharness.pip.PipTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a block as its inventory icon where vanilla has one, and as its 3D block model where
 * vanilla's icon is a flat sprite - both through the vanilla block-model rendering pipeline
 * ({@link SubmitNodeStorage#submitBlockModel}) at the block's authored {@code display.gui} pose.
 *
 * <p>Which geometry a block gets is {@link BlockIconGeometry#resolve}'s answer. A block whose item
 * definition is a plain {@code minecraft:model} root naming a block model draws the quads vanilla
 * baked for that item, at the identity model state - so no blockstate variant rotation and no
 * multipart assembly reaches it, which is what makes a stair present its riser to the camera and a
 * fence icon a post with two arms. Everything else - blocks whose item model uses
 * {@code item/generated} as its parent (rails, vines, ladders, lily_pad, seagrass, sculk_vein,
 * doors, hanging signs, ...), and dispatch-rooted or block-entity items - has no vanilla 3D icon at
 * all, and keeps this sweep's own render off {@link BlockStateModelSet}: the actual 3D block
 * geometry for its default state, a flat 2D billboard being no use as block ground truth.
 *
 * <p><b>The split is the contract.</b> An earlier form of this renderer took every block's geometry
 * from {@link BlockStateModelSet}, on the grounds that a block sweep wants 3D geometry rather than
 * an inventory icon. That is right for the sprite-icon blocks and wrong for the rest: it paired
 * in-world orientation with the inventory pose, a combination vanilla never draws, and it silently
 * cost 107 blocks their icon's orientation and 52 more their inventory model. The sweep's subject is
 * whatever asset-renderer's {@code BlockRenderer.ISOMETRIC_3D} draws, and that is the vanilla icon
 * wherever one exists.
 *
 * <p>Pose chain (col-vector form, applied right-to-left to a model vertex):
 * <pre>
 *   T(canvasW/2, canvasH/2, 0)  // center on canvas
 *   × S(canvasW, -canvasH, canvasW)  // 1 model unit -> canvasW pixels, Y-down -> Y-up
 *   × [block display.gui]  // ItemTransform.apply: T(translation) · R_XYZ(rot) · S(scale) · T(-0.5)
 * </pre>
 *
 * <p>The {@code display.gui} transform is the block's own authored one, resolved from its item
 * model by {@link BlockGuiTransform#resolve} and applied via {@link ItemTransform#apply} (which
 * emits the translation, {@code rotationXYZ}, scale, and {@code [0,1]} block-model centring in one
 * step). Stairs pose at {@code [30,135,0]}, fence gates at {@code [30,45,0]}, and standard full-cube
 * blocks inherit {@code block/block.json}'s {@code [30,225,0]} + scale {@code 0.625}; blocks with no
 * readable authored transform fall back to that same default
 * ({@link BlockGuiTransform#DEFAULT_BLOCK_GUI}).
 *
 * <p>Tints are resolved to vanilla's inventory (no-world) colour per tint index via
 * {@link #resolveInventoryTints}; biome-tinted blocks (grass_block, oak_leaves) render at the
 * colormap-default the held block draws rather than a live biome sample. See that method for the
 * sugar_cane exception (its inventory colour is white but the in-world block is grass-tinted).
 *
 * <p>Lifecycle mirrors {@link ItemFrameRenderer}: PIP textures are reused across calls while
 * the requested canvas stays the same size.
 */
public final class BlockFrameRenderer implements FrameRenderer<BlockState> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * Half-extent of the orthographic depth range. Block models are unit-scale, so the standard
     * range comfortably contains the posed model.
     *
     * <p>asset-renderer's {@code DepthMath.VANILLA_DEPTH_RANGE} holds this same value, and so
     * does every other {@link FrameRenderer} in this build. Changing it means editing all of them in
     * one commit.
     */
    private static final float DEPTH_RANGE = 1000.0f;

    /**
     * Empty tint-layer array, used for blocks with no registered {@link BlockTintSource} (the vast
     * majority). Biome-tinted and constant-tinted blocks resolve a real array via
     * {@link #resolveInventoryTints} instead; see that method for why the inventory (no-world)
     * colour is the correct ground truth.
     */
    private static final int[] NO_TINTS = new int[0];

    private final PipTarget pip = new PipTarget("block", DEPTH_RANGE);
    // Pinned-to-index-0 random so weighted variant lists (bedrock/stone/netherrack rotations)
    // always emit variants[0], matching asset-renderer's BlockStateLoader.parseVariants pick.
    // A live RandomSource.create() baked a random rotation into the reference, rotating the texture
    // noise relative to the asset on an otherwise byte-matching silhouette. See FirstVariantRandomSource.
    private final RandomSource random = new FirstVariantRandomSource();
    private final List<BlockStateModelPart> partsScratch = new ArrayList<>();

    /**
     * Renders the given {@code state} as an iso-pose block icon and writes the result PNG to
     * {@code out}. The block's {@link BlockStateModel} is looked up from the active
     * {@link Minecraft#getModelManager() ModelManager} and submitted directly via
     * {@link SubmitNodeStorage#submitBlockModel}, so the output reflects the actual 3D block
     * geometry regardless of how the inventory item model would have routed it.
     *
     * @param client the active client; supplies the model manager, feature dispatcher,
     *               lighting, and buffer source
     * @param state the block state to render; defaults via {@code block.defaultBlockState()}
     * @param canvas the canvas to draw onto
     * @param out where to write the PNG; parent directories are created on demand
     * @return whether a PNG was written; a state with no model or no parts is declined
     * @throws IOException if the PNG file write fails
     */
    @Override
    public boolean render(Minecraft client, BlockState state, Canvas canvas, Path out) throws IOException {
        BlockStateModelSet modelSet = client.getModelManager().getBlockStateModelSet();
        BlockStateModel model = modelSet.get(state);
        if (model == null) {
            LOG.warn("BlockFrameRenderer: no BlockStateModel for {}", state);
            return false;
        }

        partsScratch.clear();
        model.collectParts(random, partsScratch);
        if (partsScratch.isEmpty()) {
            LOG.warn("BlockFrameRenderer: empty parts list for {}", state);
            return false;
        }

        // Where vanilla bakes a block-model icon, that bake IS the subject.
        BlockIconGeometry.swapIn(client, state, partsScratch);

        // tripwire_hook hard-coded shading fix (see CardinalSnapPart). Vanilla's putBakedQuad lights
        // each quad by BakedQuad.direction = FaceBakery.calculateFacing(verts), whose sub-ULP winding
        // magnitude snaps the hook's +/-45deg faces to a horizontal cardinal (NORTH -> 0.40, too dark).
        // In-game and asset-renderer render those faces bright (UP); re-snap the directions so the
        // reference matches. Same "make the harness render the in-world appearance" pattern as the
        // sugar_cane tint and shade:false / EntityBlock-3D fixes.
        if (state.is(Blocks.TRIPWIRE_HOOK)) {
            for (int i = 0; i < partsScratch.size(); i++)
                partsScratch.set(i, new CardinalSnapPart(partsScratch.get(i)));
        }

        pip.draw(client, canvas, out, scope -> {
            // The block's own authored display.gui, falling back to the standard [30,225,0]+0.625
            // iso pose when the item model exposes no readable transform. ItemTransform.apply emits
            // T(translation) · R_XYZ(rot) · S(scale) · T(-0.5,-0.5,-0.5) in one step, subsuming the
            // former fixed rotation + 0.625 scale + [0,1] block-model centring translate.
            ItemTransform guiTransform = BlockGuiTransform.resolve(client, state);

            PoseStack poseStack = scope.guiPose();
            guiTransform.apply(false, poseStack.last());

            // ITEMS_3D = inventory iso pose lighting (same setup the item path uses for block
            // items). Matches the lighting frame the asset-renderer entity pipeline already
            // mirrors via its L_kit pattern.
            scope.lighting().setupFor(Lighting.Entry.ITEMS_3D);

            // Sheet choice per block, mirroring vanilla's chunk render-type routing. A block's
            // baked quads carry BakedQuad.FLAG_TRANSLUCENT (OR-folded into each part's
            // materialFlags) when its render type is translucent - stained_glass, ice,
            // slime_block, honey_block, tinted_glass, etc. Those must use translucentBlockSheet
            // so the reference alpha-blends the way real vanilla does (source-over over the
            // transparent canvas), matching asset-renderer's per-texel alpha blend; cutout would
            // write the texel colour straight (opaque) and diverge. cutoutBlockSheet covers
            // solid + cutout (the opaque majority), so it stays the default.
            boolean translucent = false;
            for (BlockStateModelPart part : partsScratch)
                if ((part.materialFlags() & BakedQuad.FLAG_TRANSLUCENT) != 0) {
                    translucent = true;
                    break;
                }
            RenderType renderType = translucent ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet();

            // Resolve biome / constant tints to vanilla's INVENTORY colour (no world context), the
            // same value vanilla bakes into a block-item GUI icon. Without this, grass / leaves /
            // vine etc. rendered at their raw grayscale texture while asset-renderer tints them.
            int[] tints = resolveInventoryTints(client, state);

            scope.storage().submitBlockModel(poseStack, renderType, partsScratch, tints,
                PipScope.FULL_BRIGHT_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            return true;
        });
        return true;
    }

    /**
     * Resolves the per-tint-index colour array vanilla bakes into a block-item GUI icon.
     *
     * <p>Vanilla 26.1 resolves block tints through {@link BlockTintSource}: {@code color(state)} is
     * the no-world-context "in hand" colour (a block-item icon, a held block), while
     * {@code colorInWorld(state, level, pos)} samples the actual biome. The GUI inventory icon uses
     * {@code color(state)}, which for grass / foliage returns the colormap DEFAULT
     * ({@code GrassColor.getDefaultColor()} = colormap centre, temperature 0.5 / downfall 1.0) and
     * for the constant-tint blocks (birch / spruce leaves, lily_pad) returns their fixed colour.
     * That is the value asset-renderer must match, so the reference uses it rather than a biome
     * sample. {@link BlockColors#getTintSources} returns one source per tint index in index order
     * (see {@code ModelBlockRenderer}); blocks with no source get {@link #NO_TINTS}.
     *
     * <p><b>sugar_cane exception.</b> A handful of tint sources return the untinted-white sentinel
     * ({@code -1}) from {@code color(state)} because vanilla deliberately leaves their <i>held
     * item</i> uncolored - {@code BlockTintSources.sugarCane()} is the only such source on a block
     * that appears in this 3D block-parity sweep. The in-world block, however, IS grass-tinted
     * ({@code colorInWorld -> BiomeColors.getAverageGrassColor}). Since this reference renders the
     * in-world 3D block (not the inventory icon), the grass tint is the correct ground truth, so we
     * substitute the grass colormap default ({@link GrassColor#getDefaultColor()} {@code = get(0.5,
     * 1.0)}) - the same value tall_grass / fern resolve through {@code grass()} and asset-renderer
     * applies via its {@code INVENTORY_DEFAULT_BIOME}. (water / waterParticles share the white
     * sentinel but are fluids, not in this block sweep.)
     */
    private static int[] resolveInventoryTints(Minecraft client, BlockState state) {
        BlockColors blockColors = client.getBlockColors();
        List<BlockTintSource> sources = blockColors.getTintSources(state);
        if (sources.isEmpty()) return NO_TINTS;
        int[] tints = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            int color = sources.get(i).color(state);
            if (color == -1 && state.is(Blocks.SUGAR_CANE)) color = GrassColor.getDefaultColor();
            tints[i] = color;
        }
        return tints;
    }

    /**
     * Near-tie epsilon for {@link #assetCardinalDirection}. The hook's faces are exactly 45deg, so
     * their two candidate cardinals' dot products differ only by float noise (~1e-7 on a normalized
     * normal); any value well above that and well below the gap to a genuinely-different cardinal
     * works.
     */
    private static final float CARDINAL_TIE_EPSILON = 1.0e-3f;

    /**
     * Re-derives the cardinal a quad should shade by, matching asset-renderer's
     * {@code BlockRenderer.closestCardinalUnitVec}. Vanilla's {@code FaceBakery.findClosestDirection}
     * breaks an exactly-45deg tie on the sub-ULP winding magnitude of the baked normal, which for
     * tripwire_hook's tilted faces lands on a horizontal cardinal (NORTH, shaded 0.40). asset-renderer
     * (and the in-world block) snap such a face to the earlier {@code Direction.values()} axis (UP),
     * shaded full-bright. Replicate that by iterating the cardinals in declaration order and keeping
     * the first whose dot is within {@link #CARDINAL_TIE_EPSILON} of the maximum.
     *
     * @param quad the baked quad whose shading cardinal to recompute
     * @return the re-snapped cardinal, or the quad's existing direction for a degenerate normal
     */
    private static Direction assetCardinalDirection(BakedQuad quad) {
        Vector3fc p0 = quad.position0();
        Vector3f normal = new Vector3f(quad.position1()).sub(p0)
            .cross(new Vector3f(quad.position2()).sub(p0));
        if (!normal.isFinite() || normal.lengthSquared() < 1.0e-12f) return quad.direction();
        normal.normalize();
        Direction best = null;
        float bestDot = 0f;
        for (Direction dir : Direction.values()) {
            float dot = normal.dot(dir.getUnitVec3f());
            if (dot >= 0f && dot > bestDot + CARDINAL_TIE_EPSILON) {
                bestDot = dot;
                best = dir;
            }
        }
        return best != null ? best : quad.direction();
    }

    /**
     * Delegating {@link BlockStateModelPart} that rewrites each quad's shading direction via
     * {@link #assetCardinalDirection}. Only the {@code direction} changes - positions, UVs, and
     * material are untouched, so geometry and back-face culling (which keys off the vertex winding,
     * not the stored direction) are identical; only the per-face diffuse cardinal differs.
     */
    private record CardinalSnapPart(BlockStateModelPart delegate) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction face) {
            List<BakedQuad> src = delegate.getQuads(face);
            if (src.isEmpty()) return src;
            List<BakedQuad> out = new ArrayList<>(src.size());
            for (BakedQuad q : src) {
                Direction snapped = assetCardinalDirection(q);
                out.add(snapped == q.direction() ? q : new BakedQuad(
                    q.position0(), q.position1(), q.position2(), q.position3(),
                    q.packedUV0(), q.packedUV1(), q.packedUV2(), q.packedUV3(),
                    snapped, q.materialInfo()));
            }
            return out;
        }

        @Override
        public boolean useAmbientOcclusion() { return delegate.useAmbientOcclusion(); }

        @Override
        public Material.Baked particleMaterial() { return delegate.particleMaterial(); }

        @Override
        public int materialFlags() { return delegate.materialFlags(); }
    }

    @Override
    public void close() {
        pip.close();
    }
}
