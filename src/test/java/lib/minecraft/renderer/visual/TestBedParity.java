package lib.minecraft.renderer.visual;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentLinkedMap;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.asset.model.ModelElement;
import lib.minecraft.renderer.asset.model.ModelFace;
import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.engine.camera.Lens;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.engine.kit.BlockGeometryKit;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tensor.Matrix4f;
import lib.minecraft.renderer.tensor.Vector3f;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Side-by-side visual comparison of the block-entity subjects (bed, chest) rendered two ways: the
 * {@code minecraft:red_bed} / {@code minecraft:chest} block-entity path through the production
 * {@link BlockRenderer} against a from-scratch render built directly off the mc-assets hand-curated
 * block-model JSON (treated as ground truth). Both are rasterized with the same iso engine so the
 * comparison isolates model-geometry / UV differences, not projection differences.
 *
 * <p>The mc-assets path parses each JSON's {@code elements} into {@link ModelElement}s, resolves the
 * entity texture, builds triangles via {@link BlockGeometryKit#buildFromElements}, applies a
 * subject-specific Y rotation (bed = 90 degrees to point the pillow top-right; chest = identity),
 * recentres / rescales the bed (it spans two blocks), then rasterizes through {@link ModelEngine}
 * under {@link Projection#VANILLA_ISO} with {@link Lens#NONE}. No entity pipeline is involved on that
 * side - it is a pure block-model render.
 *
 * <p>Four PNGs land under {@code cache/visual/bed-parity/}: {@code pipeline_red_bed.png},
 * {@code mc_assets_red_bed.png}, {@code pipeline_chest.png}, {@code mc_assets_chest.png}. Unlike the
 * other parity tools here there is no diff/metric pass - the eye does the comparison across the
 * pipeline-vs-mc_assets pairs.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:bedParity [-PrenderSize=1024]}.
 */
@UtilityClass
public final class TestBedParity {

    /** Root of the mc-assets repository's hand-curated bed block-model JSONs used as ground truth. */
    private static final Path MC_ASSETS_BED = Path.of(
        "cache/mc-assets/mc-assets-main/custom/blockentities/latest/blockModels/block/bed"
    );

    /**
     * Runs the comparison matrix (pipeline vs mc-assets, for both red_bed and chest).
     *
     * @param args {@code args[0]} is an optional square edge length in pixels (defaults to 1024)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 1024;

        ClientAssets result = ClientAcquisition.acquire(ClientOptions.defaults());
        PipelineRendererContext context = PipelineRendererContext.of(result);
        BlockRenderer renderer = new BlockRenderer(context);
        Path outputDir = Path.of("cache/visual/bed-parity");
        Files.createDirectories(outputDir);

        // 1. ClientAcquisition entity model version
        render(renderer, "minecraft:red_bed", size, outputDir.resolve("pipeline_red_bed.png"));

        // 2. mc-assets ground truth (block model elements)
        renderMcAssetsBed(context, size, outputDir.resolve("mc_assets_red_bed.png"));

        // 3. ClientAcquisition chest
        render(renderer, "minecraft:chest", size, outputDir.resolve("pipeline_chest.png"));

        // 4. mc-assets chest ground truth
        renderMcAssetsChest(context, size, outputDir.resolve("mc_assets_chest.png"));

        System.out.println("Done. Compare pipeline vs mc_assets versions.");
    }

    /**
     * Renders one block through the pipeline's entity-model path and writes it to {@code out}.
     * Runtime exceptions are caught and reported to {@code stderr} so a single failure doesn't
     * abort the whole comparison matrix.
     */
    private static void render(@NotNull BlockRenderer renderer, @NotNull String blockId, int size, @NotNull Path out) throws IOException {
        System.out.printf("Rendering %s (pipeline)...%n", blockId);
        try {
            ImageData img = renderer.render(BlockOptions.builder()
                .blockId(blockId).type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder().canvasSize(size).supersample(4).antiAlias(true).build()).build());
            ImageIO.write(img.toBufferedImage(), "PNG", out.toFile());
            System.out.println("  Wrote " + out);
        } catch (Exception ex) {
            System.err.printf("  FAILED: %s%n", ex.getMessage());
        }
    }

    /**
     * Loads mc-assets bed_head + bed_foot JSON, merges elements (foot Z+16), resolves the
     * bed texture, builds triangles via {@link BlockGeometryKit#buildFromElements}, and rasterizes
     * with the standard isometric engine. Pure block model rendering - no entity pipeline.
     */
    private static void renderMcAssetsBed(@NotNull PipelineRendererContext context, int size, @NotNull Path out) throws IOException {
        System.out.println("Rendering mc-assets bed (block model path)...");

        Gson gson = GsonSettings.defaults().create();
        JsonObject headJson = gson.fromJson(Files.readString(MC_ASSETS_BED.resolve("bed_head.json")), JsonObject.class);
        JsonObject footJson = gson.fromJson(Files.readString(MC_ASSETS_BED.resolve("bed_foot.json")), JsonObject.class);

        Optional<PixelBuffer> texOpt = context.resolveTexture("minecraft:entity/bed/red");
        if (texOpt.isEmpty()) { System.err.println("  FAILED: texture not found"); return; }
        PixelBuffer texture = texOpt.get();

        // Parse and merge elements from both halves
        ConcurrentList<ModelElement> elements = Concurrent.newList();
        parseElements(headJson.getAsJsonArray("elements"), elements, 0f);
        parseElements(footJson.getAsJsonArray("elements"), elements, 16f);

        // Build triangles - "#bed" maps to the bed texture
        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#bed", texture);
        ConcurrentList<VisibleTriangle> triangles = BlockGeometryKit.buildFromElements(elements, faceTextures, ColorMath.WHITE);

        System.out.printf("  Built %d triangles from mc-assets bed model%n", triangles.size());
        if (triangles.isEmpty()) { System.err.println("  FAILED: zero triangles"); return; }

        // Rotate 90° around Y to orient pillow toward top-right in isometric view
        Matrix4f rotY = Matrix4f.createRotationY(
            (float) Math.toRadians(90));
        ConcurrentList<VisibleTriangle> rotated = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            rotated.add(new VisibleTriangle(
                Vector3f.transform(t.position0(), rotY),
                Vector3f.transform(t.position1(), rotY),
                Vector3f.transform(t.position2(), rotY),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(),
                Vector3f.normalize(
                    Vector3f.transformNormal(t.normal(), rotY)),
                t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
            ));
        }

        // Re-center and scale: the combined bed spans 2 blocks, so buildFromElements' single-block
        // centering is off. Compute AABB and recenter + scale to fit 0.9 extent.
        // Larger fit extent since the bed spans 2 blocks - 1.4 fills the tile well
        ConcurrentList<VisibleTriangle> centered = recenterAndFit(rotated, 1.4f);

        // Rasterize with standard isometric engine
        ModelEngine engine = new ModelEngine(context, Projection.VANILLA_ISO.resolve().camera().withLens(Lens.NONE));
        PixelBuffer buffer = PixelBuffer.create(size, size);
        engine.rasterize(centered, buffer);
        ImageIO.write(buffer.toBufferedImage(), "PNG", out.toFile());
        System.out.println("  Wrote " + out);
    }

    /**
     * Renders the mc-assets chest.json via the block model path with a 180° Y rotation
     * matching vanilla ChestRenderer's inventory yaw.
     */
    private static void renderMcAssetsChest(@NotNull PipelineRendererContext context, int size, @NotNull Path out) throws IOException {
        System.out.println("Rendering mc-assets chest (block model path)...");

        Path chestJson = Path.of("cache/mc-assets/mc-assets-main/custom/blockentities/latest/blockModels/block/chest.json");
        JsonObject json = GsonSettings.defaults().create().fromJson(Files.readString(chestJson), JsonObject.class);

        Optional<PixelBuffer> texOpt = context.resolveTexture("minecraft:entity/chest/normal");
        if (texOpt.isEmpty()) { System.err.println("  FAILED: texture not found"); return; }

        ConcurrentList<ModelElement> elements = Concurrent.newList();
        parseElements(json.getAsJsonArray("elements"), elements, 0f);

        ConcurrentMap<String, PixelBuffer> faceTextures = Concurrent.newMap();
        faceTextures.put("#chest", texOpt.get());
        ConcurrentList<VisibleTriangle> triangles = BlockGeometryKit.buildFromElements(elements, faceTextures, ColorMath.WHITE);

        System.out.printf("  Built %d triangles from mc-assets chest model%n", triangles.size());
        if (triangles.isEmpty()) { System.err.println("  FAILED: zero triangles"); return; }

        // No rotation needed - mc-assets chest already faces the correct direction
        Matrix4f rotY = Matrix4f.IDENTITY;
        ConcurrentList<VisibleTriangle> rotated = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            rotated.add(new VisibleTriangle(
                Vector3f.transform(t.position0(), rotY),
                Vector3f.transform(t.position1(), rotY),
                Vector3f.transform(t.position2(), rotY),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(),
                Vector3f.normalize(
                    Vector3f.transformNormal(t.normal(), rotY)),
                t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
            ));
        }

        ModelEngine engine = new ModelEngine(context, Projection.VANILLA_ISO.resolve().camera().withLens(Lens.NONE));
        PixelBuffer buffer = PixelBuffer.create(size, size);
        engine.rasterize(rotated, buffer);
        ImageIO.write(buffer.toBufferedImage(), "PNG", out.toFile());
        System.out.println("  Wrote " + out);
    }

    /**
     * Parses mc-assets JSON elements into {@link ModelElement} objects. mc-assets UVs are in
     * texture-pixel space (0-64 for a 64x64 texture). Our renderer expects 0-16 block-pixel
     * space (divided by 16 to get 0-1 normalized). Conversion: {@code our_uv = mc_uv / 4}.
     */
    private static void parseElements(@NotNull JsonArray arr, @NotNull ConcurrentList<ModelElement> out, float zOffset) {
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            float[] from = readF3(obj, "from");
            float[] to = readF3(obj, "to");
            from[2] += zOffset;
            to[2] += zOffset;

            ConcurrentLinkedMap<String, ModelFace> faces = Concurrent.newLinkedMap();
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("faces").entrySet()) {
                JsonObject f = e.getValue().getAsJsonObject();
                JsonArray uv = f.getAsJsonArray("uv");

                // Use reflection-free approach: set fields via Gson roundtrip
                JsonObject faceJson = new JsonObject();
                faceJson.addProperty("texture", f.get("texture").getAsString());
                // mc-assets UV is in texture-pixel space on a 64x64 texture (0-16 range,
                // fractional values). Our block model UV format is 0-16 integer space that
                // gets divided by 16 to produce 0-1 normalized. Since the entity texture is
                // 64x64, and the UV values from mc-assets already represent pixel positions
                // divided by 4 (they use 0-16 range for a 64px texture), we pass them directly.
                JsonArray blockUv = new JsonArray();
                blockUv.add(uv.get(0).getAsFloat());
                blockUv.add(uv.get(1).getAsFloat());
                blockUv.add(uv.get(2).getAsFloat());
                blockUv.add(uv.get(3).getAsFloat());
                faceJson.add("uv", blockUv);
                if (f.has("rotation"))
                    faceJson.addProperty("rotation", f.get("rotation").getAsInt());

                ModelFace face = GsonSettings.defaults().create().fromJson(faceJson, ModelFace.class);
                faces.put(e.getKey(), face);
            }

            JsonArray fromArr = new JsonArray(); fromArr.add(from[0]); fromArr.add(from[1]); fromArr.add(from[2]);
            JsonArray toArr = new JsonArray(); toArr.add(to[0]); toArr.add(to[1]); toArr.add(to[2]);

            JsonObject fullElem = new JsonObject();
            fullElem.add("from", fromArr);
            fullElem.add("to", toArr);
            JsonObject facesJson = new JsonObject();
            for (Map.Entry<String, ModelFace> fe : faces.entrySet()) {
                facesJson.add(fe.getKey(), GsonSettings.defaults().create().toJsonTree(fe.getValue()));
            }
            fullElem.add("faces", facesJson);

            ModelElement element = GsonSettings.defaults().create().fromJson(fullElem, ModelElement.class);
            out.add(element);
        }
    }

    /**
     * Recomputes the triangle AABB, centres it at origin, and scales it so the longest axis
     * spans {@code fitExtent}. Used on the merged bed model which covers two blocks and would
     * otherwise extend past the standard single-block framing.
     */
    private static @NotNull ConcurrentList<VisibleTriangle> recenterAndFit(
        @NotNull ConcurrentList<VisibleTriangle> triangles, float fitExtent
    ) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (VisibleTriangle t : triangles) {
            for (Vector3f v : new Vector3f[]{ t.position0(), t.position1(), t.position2() }) {
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float extent = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ);
        float scale = extent > 0.001f ? fitExtent / extent : 1f;

        ConcurrentList<VisibleTriangle> out = Concurrent.newList();
        for (VisibleTriangle t : triangles) {
            out.add(new VisibleTriangle(
                scaleV(t.position0(), cx, cy, cz, scale),
                scaleV(t.position1(), cx, cy, cz, scale),
                scaleV(t.position2(), cx, cy, cz, scale),
                t.uv0(), t.uv1(), t.uv2(),
                t.texture(), t.tintArgb(), t.normal(), t.shading(), new SurfaceTraits(t.traits().cullBackFaces(), t.traits().emissive(), false, false)
            ));
        }
        return out;
    }

    /** Subtracts {@code (cx, cy, cz)} from {@code v}, then uniformly scales by {@code s}. */
    private static @NotNull Vector3f scaleV(
        @NotNull Vector3f v, float cx, float cy, float cz, float s
    ) {
        return new Vector3f((v.x() - cx) * s, (v.y() - cy) * s, (v.z() - cz) * s);
    }

    /** Reads a 3-float array field from {@code obj}. */
    private static float[] readF3(@NotNull JsonObject obj, @NotNull String key) {
        JsonArray a = obj.getAsJsonArray(key);
        return new float[]{ a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat() };
    }

}
