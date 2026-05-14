package lib.minecraft.renderer.visual;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.engine.IsometricEngine;
import lib.minecraft.renderer.engine.RenderEngine;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.geometry.Box;
import lib.minecraft.renderer.geometry.EulerRotation;
import lib.minecraft.renderer.geometry.PerspectiveParams;
import lib.minecraft.renderer.geometry.VisibleTriangle;
import lib.minecraft.renderer.kit.EntityGeometryKit;
import lib.minecraft.renderer.kit.EntityGeometryKitJava;
import lib.minecraft.renderer.options.EntityOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Per-bone parity harness for a single entity. Renders each bone in isolation through both
 * pipelines (a stripped-down model containing exactly one bone) and writes the bedrock + java
 * PNG pair plus a bone-by-bone delta report. Use this to isolate which specific bones'
 * positions / rotations / orientations diverge between the two pipelines.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:entityBodyPartParity -PentityId=minecraft:cave_spider
 * [-PrenderSize=256] [-Dentity.flipY=false]}. The {@code entity.flipX/Y/Z} and
 * {@code entity.flipNX/NY/NZ} system properties on {@link EntityGeometryKitJava} let the
 * developer sweep axis combinations without recompiling.
 *
 * <p>Output goes to {@code cache/visual/entity-bodypart/<entityId>/} with one bedrock PNG and
 * one Java PNG per bone, plus a {@code bodypart-report.tsv} sorted by mean ARGB delta.
 */
@UtilityClass
public final class TestEntityBodyPartParity {

    /** Output root - per-entity subdirectory keeps the file count manageable when iterating. */
    private static final Path OUTPUT_ROOT = Path.of("cache/visual/entity-bodypart");

    /** Default render size; smaller than the standard 512 to keep iteration fast. */
    private static final int DEFAULT_SIZE = 256;

    /** Default entity to compare when no {@code -PentityId} is supplied. */
    private static final String DEFAULT_ENTITY = "minecraft:cave_spider";

    /**
     * Runs the per-bone parity sweep for one entity.
     *
     * @param args {@code args[0]} optional render size; {@code args[1]} optional entity id
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        String entityId = args.length > 1 ? args[1] : DEFAULT_ENTITY;

        Path outDir = OUTPUT_ROOT.resolve(entityId.replace(':', '_'));
        Files.createDirectories(outDir);

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ConcurrentMap<String, EntityModelLoader.EntityDefinition> javaEntities = EntityModelLoader.loadJava();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models_java.json missing - run :asset-renderer:entityModelsJava first");
            return;
        }

        Optional<Entity> bedrockEntity = context.findEntity(entityId);
        EntityModelLoader.EntityDefinition javaEntity = javaEntities.get(entityId);
        if (bedrockEntity.isEmpty() || javaEntity == null) {
            System.err.printf("Entity '%s' missing from one of the pipelines (bedrock=%s, java=%s) - aborting%n",
                entityId, bedrockEntity.isPresent(), javaEntity != null);
            return;
        }

        Optional<PixelBuffer> bedrockTex = resolveTexture(context, bedrockEntity.get().getTextureRef().orElse(null), bedrockEntity.get().isForceOpaque());
        Optional<PixelBuffer> javaTex = resolveTexture(context, javaEntity.textureRef().orElse(null), javaEntity.forceOpaque());
        if (bedrockTex.isEmpty() || javaTex.isEmpty()) {
            System.err.printf("Texture missing (bedrock=%s java=%s) - aborting%n", bedrockTex.isPresent(), javaTex.isPresent());
            return;
        }

        // Auto-fit each bone to the FULL model bounds so that per-bone renders share the same
        // scale + offset as the full-mob render. Without shared bounds each single-bone image
        // would auto-fit to its own bone, defeating the purpose of side-by-side spatial diffing.
        Box bedrockBounds = EntityGeometryKit.computeBounds(bedrockEntity.get().getModel());
        Box javaBounds = EntityGeometryKitJava.computeBounds(javaEntity.model());
        System.out.printf("  Bounds: bedrock=[%.1f..%.1f, %.1f..%.1f, %.1f..%.1f] java=[%.1f..%.1f, %.1f..%.1f, %.1f..%.1f]%n",
            bedrockBounds.minX(), bedrockBounds.maxX(),
            bedrockBounds.minY(), bedrockBounds.maxY(),
            bedrockBounds.minZ(), bedrockBounds.maxZ(),
            javaBounds.minX(), javaBounds.maxX(),
            javaBounds.minY(), javaBounds.maxY(),
            javaBounds.minZ(), javaBounds.maxZ());

        Set<String> boneNames = new LinkedHashSet<>();
        boneNames.addAll(bedrockEntity.get().getModel().getBones().keySet());
        boneNames.addAll(javaEntity.model().getBones().keySet());

        System.out.printf("Per-bone parity for %s at %dx%d (%d bones, output %s)%n",
            entityId, size, size, boneNames.size(), outDir.toAbsolutePath());
        System.out.printf("  Switches: flipX=%s flipY=%s flipZ=%s flipNX=%s flipNY=%s flipNZ=%s translateByPivot=%s%n",
            System.getProperty("entity.flipX", "false"),
            System.getProperty("entity.flipY", "true"),
            System.getProperty("entity.flipZ", "false"),
            System.getProperty("entity.flipNX", "false"),
            System.getProperty("entity.flipNY", "true"),
            System.getProperty("entity.flipNZ", "false"),
            System.getProperty("entity.translateByPivot", "true"));

        StringBuilder report = new StringBuilder();
        report.append("bone\tin_bedrock\tin_java\tmean_argb_delta\tjava_cov\tbedrock_cov\n");

        for (String bone : boneNames) {
            boolean inBedrock = bedrockEntity.get().getModel().getBones().containsKey(bone);
            boolean inJava = javaEntity.model().getBones().containsKey(bone);

            ImageData bedrockImg = inBedrock
                ? renderSingleBoneBedrock(context, bedrockEntity.get(), bone, bedrockBounds, bedrockTex.get(), size)
                : blankImage(size);
            ImageData javaImg = inJava
                ? renderSingleBoneJava(context, javaEntity, bone, javaBounds, javaTex.get(), size)
                : blankImage(size);

            BufferedImage bedrockBuf = bedrockImg.toBufferedImage();
            BufferedImage javaBuf = javaImg.toBufferedImage();
            ImageIO.write(bedrockBuf, "PNG", new File(outDir.toFile(), bone + "_bedrock.png"));
            ImageIO.write(javaBuf, "PNG", new File(outDir.toFile(), bone + "_java.png"));
            // Diff image: pixel value = ARGB delta amplified 4x so a small numeric delta shows
            // up clearly. Magenta tinge = pure-alpha mismatch (only bedrock or only java
            // covered the pixel); white-ish = colour difference at a covered pixel.
            BufferedImage diffBuf = diffImage(bedrockBuf, javaBuf);
            ImageIO.write(diffBuf, "PNG", new File(outDir.toFile(), bone + "_diff.png"));

            Stats stats = compare(bedrockBuf, javaBuf);
            report.append(String.format("%s\t%s\t%s\t%.4f\t%.4f\t%.4f%n",
                bone, inBedrock, inJava, stats.meanDelta(), stats.javaCov(), stats.bedrockCov()));
            System.out.printf("  %-25s bed=%s java=%s delta=%6.2f java-cov=%5.2f%% bed-cov=%5.2f%%%n",
                bone, inBedrock, inJava, stats.meanDelta(),
                stats.javaCov() * 100, stats.bedrockCov() * 100);
        }

        Files.writeString(outDir.resolve("bodypart-report.tsv"), report.toString());
        System.out.println("Report: " + outDir.resolve("bodypart-report.tsv").toAbsolutePath());
    }

    private static @NotNull Optional<PixelBuffer> resolveTexture(@NotNull PipelineRendererContext context, String textureRef, boolean forceOpaque) {
        if (textureRef == null) return Optional.empty();
        Optional<PixelBuffer> loaded = context.resolveBedrockEntityTexture(textureRef);
        if (loaded.isEmpty()) loaded = context.resolveTexture("minecraft:entity/" + textureRef);
        if (loaded.isPresent() && forceOpaque) {
            int[] src = loaded.get().data();
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) {
                int p = src[i];
                int a = ColorMath.alpha(p);
                out[i] = (a > 0 && a < 255) ? ColorMath.withAlpha(p, 255) : p;
            }
            loaded = Optional.of(PixelBuffer.of(out, loaded.get().width(), loaded.get().height()));
        }
        return loaded;
    }

    /** Renders one bone of the bedrock model in the same screen-space frame as the full render. */
    private static @NotNull ImageData renderSingleBoneBedrock(
        @NotNull PipelineRendererContext context,
        @NotNull Entity entity,
        @NotNull String boneName,
        @NotNull Box sharedBounds,
        @NotNull PixelBuffer texture,
        int size
    ) {
        EntityModelData stripped = stripToSingleBone(entity.getModel(), boneName);
        if (stripped.getBones().isEmpty()) return blankImage(size);

        EntityGeometryKit.BuildResult build = EntityGeometryKit.buildTriangles(stripped, texture, sharedBounds);
        return rasterize(context, build.triangles(), entity.getModel().getInventoryYRotation(), size);
    }

    /** Renders one bone of the Java model in the same screen-space frame as the full render. */
    private static @NotNull ImageData renderSingleBoneJava(
        @NotNull PipelineRendererContext context,
        @NotNull EntityModelLoader.EntityDefinition def,
        @NotNull String boneName,
        @NotNull Box sharedBounds,
        @NotNull PixelBuffer texture,
        int size
    ) {
        EntityModelData stripped = stripToSingleBone(def.model(), boneName);
        if (stripped.getBones().isEmpty()) return blankImage(size);

        EntityGeometryKit.BuildResult build = EntityGeometryKitJava.buildTriangles(stripped, texture, sharedBounds);
        return rasterize(context, build.triangles(), def.model().getInventoryYRotation(), size);
    }

    /** Copies all top-level fields from {@code source} but only includes the named bone in the bones map. */
    private static @NotNull EntityModelData stripToSingleBone(@NotNull EntityModelData source, @NotNull String boneName) {
        EntityModelData stripped = new EntityModelData(
            source.getTextureWidth(),
            source.getTextureHeight(),
            source.getInventoryYRotation(),
            dev.simplified.collection.Concurrent.newLinkedMap()
        );
        EntityModelData.Bone bone = source.getBones().get(boneName);
        if (bone != null) stripped.getBones().put(boneName, bone);
        return stripped;
    }

    /** Rasterizes the triangle list with the standard iso pose, returning the final {@link ImageData}. */
    private static @NotNull ImageData rasterize(
        @NotNull PipelineRendererContext context,
        @NotNull ConcurrentList<VisibleTriangle> triangles,
        float inventoryYRotation,
        int size
    ) {
        PixelBuffer buffer = PixelBuffer.create(size, size);
        if (triangles.isEmpty()) return RenderEngine.staticFrame(buffer);
        IsometricEngine engine = IsometricEngine.standard(context);
        EulerRotation effective = new EulerRotation(0f, inventoryYRotation, 0f);
        engine.rasterize(triangles, buffer, PerspectiveParams.ISOMETRIC_BLOCK, effective);
        buffer.applyFxaa();
        return RenderEngine.staticFrame(buffer);
    }

    /** Compares two same-size images returning mean per-pixel ARGB delta + coverage stats. */
    private static @NotNull Stats compare(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        long delta = 0L;
        long aCov = 0L;
        long bCov = 0L;
        long count = (long) w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                delta += Math.abs(ColorMath.alpha(pa) - ColorMath.alpha(pb))
                    + Math.abs(ColorMath.red(pa) - ColorMath.red(pb))
                    + Math.abs(ColorMath.green(pa) - ColorMath.green(pb))
                    + Math.abs(ColorMath.blue(pa) - ColorMath.blue(pb));
                if (ColorMath.alpha(pa) > 0) aCov++;
                if (ColorMath.alpha(pb) > 0) bCov++;
            }
        }
        return new Stats((double) delta / count, (double) bCov / count, (double) aCov / count);
    }

    /** Returns a fully-transparent square image of the given size. */
    private static @NotNull ImageData blankImage(int size) {
        return RenderEngine.staticFrame(PixelBuffer.create(size, size));
    }

    /**
     * Builds a per-pixel diff image: where bedrock and java agree the pixel is fully
     * transparent; where they disagree the pixel encodes the absolute ARGB delta amplified
     * 4x so small numeric differences are visible. Coverage-only mismatches (one image has
     * alpha, the other does not) get a magenta tint to distinguish them from colour
     * differences at covered pixels.
     */
    private static @NotNull BufferedImage diffImage(@NotNull BufferedImage a, @NotNull BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int aa = ColorMath.alpha(pa);
                int ab = ColorMath.alpha(pb);
                int dr = Math.abs(ColorMath.red(pa) - ColorMath.red(pb));
                int dg = Math.abs(ColorMath.green(pa) - ColorMath.green(pb));
                int db = Math.abs(ColorMath.blue(pa) - ColorMath.blue(pb));
                int da = Math.abs(aa - ab);
                if (da == 0 && dr == 0 && dg == 0 && db == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                if ((aa == 0) ^ (ab == 0)) {
                    out.setRGB(x, y, 0xFFFF00FF);
                    continue;
                }
                int amp = 4;
                int rr = Math.min(255, dr * amp);
                int gg = Math.min(255, dg * amp);
                int bb = Math.min(255, db * amp);
                out.setRGB(x, y, (255 << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
        return out;
    }

    /** Mean delta + per-side alpha coverage for one bone. */
    private record Stats(double meanDelta, double bedrockCov, double javaCov) {}

}
