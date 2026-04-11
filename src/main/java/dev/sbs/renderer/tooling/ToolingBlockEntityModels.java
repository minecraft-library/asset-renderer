package dev.sbs.renderer.tooling;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.pipeline.AssetPipelineOptions;
import dev.sbs.renderer.pipeline.client.ClientJarDownloader;
import dev.sbs.renderer.pipeline.client.HttpFetcher;
import dev.sbs.renderer.pipeline.loader.BlockEntityModelLoader;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Entry point invoked by the {@code generateBlockEntityModels} Gradle task.
 * <p>
 * Downloads the deobfuscated Minecraft client jar, parses block entity model classes via
 * {@link Parser}, and writes the result to
 * {@code src/main/resources/renderer/block_entity_models.json}. The runtime pipeline reads
 * the JSON via {@link dev.sbs.renderer.pipeline.loader.BlockEntityModelLoader}.
 */
@UtilityClass
public final class ToolingBlockEntityModels {

    private static final @NotNull Path OUTPUT_PATH = Path.of("src/main/resources/renderer/block_entity_models.json");

    /**
     * Maps block ids to (entity model id, default entity texture path). Shulker boxes reference
     * the mob entity model from {@code entity_models.json} rather than a block entity model.
     */
    private static final @NotNull List<BlockMapping> BLOCK_MAPPINGS = List.of(
        // Shulker boxes use the existing shulker mob model
        mapping("minecraft:shulker_box",  "minecraft:shulker_box", "minecraft:entity/shulker/shulker"),
        mapping("minecraft:white_shulker_box",      "minecraft:shulker_box", "minecraft:entity/shulker/shulker_white"),
        mapping("minecraft:orange_shulker_box",     "minecraft:shulker_box", "minecraft:entity/shulker/shulker_orange"),
        mapping("minecraft:magenta_shulker_box",    "minecraft:shulker_box", "minecraft:entity/shulker/shulker_magenta"),
        mapping("minecraft:light_blue_shulker_box", "minecraft:shulker_box", "minecraft:entity/shulker/shulker_light_blue"),
        mapping("minecraft:yellow_shulker_box",     "minecraft:shulker_box", "minecraft:entity/shulker/shulker_yellow"),
        mapping("minecraft:lime_shulker_box",       "minecraft:shulker_box", "minecraft:entity/shulker/shulker_lime"),
        mapping("minecraft:pink_shulker_box",       "minecraft:shulker_box", "minecraft:entity/shulker/shulker_pink"),
        mapping("minecraft:gray_shulker_box",       "minecraft:shulker_box", "minecraft:entity/shulker/shulker_gray"),
        mapping("minecraft:light_gray_shulker_box", "minecraft:shulker_box", "minecraft:entity/shulker/shulker_light_gray"),
        mapping("minecraft:cyan_shulker_box",       "minecraft:shulker_box", "minecraft:entity/shulker/shulker_cyan"),
        mapping("minecraft:purple_shulker_box",     "minecraft:shulker_box", "minecraft:entity/shulker/shulker_purple"),
        mapping("minecraft:blue_shulker_box",       "minecraft:shulker_box", "minecraft:entity/shulker/shulker_blue"),
        mapping("minecraft:brown_shulker_box",      "minecraft:shulker_box", "minecraft:entity/shulker/shulker_brown"),
        mapping("minecraft:green_shulker_box",      "minecraft:shulker_box", "minecraft:entity/shulker/shulker_green"),
        mapping("minecraft:red_shulker_box",        "minecraft:shulker_box", "minecraft:entity/shulker/shulker_red"),
        mapping("minecraft:black_shulker_box",      "minecraft:shulker_box", "minecraft:entity/shulker/shulker_black"),

        // Chests
        mapping("minecraft:chest",         "minecraft:chest", "minecraft:entity/chest/normal"),
        mapping("minecraft:trapped_chest", "minecraft:chest", "minecraft:entity/chest/trapped"),
        mapping("minecraft:ender_chest",   "minecraft:chest", "minecraft:entity/chest/ender"),

        // Beds (16 colors)
        mapping("minecraft:white_bed",      "minecraft:bed_head", "minecraft:entity/bed/white"),
        mapping("minecraft:orange_bed",     "minecraft:bed_head", "minecraft:entity/bed/orange"),
        mapping("minecraft:magenta_bed",    "minecraft:bed_head", "minecraft:entity/bed/magenta"),
        mapping("minecraft:light_blue_bed", "minecraft:bed_head", "minecraft:entity/bed/light_blue"),
        mapping("minecraft:yellow_bed",     "minecraft:bed_head", "minecraft:entity/bed/yellow"),
        mapping("minecraft:lime_bed",       "minecraft:bed_head", "minecraft:entity/bed/lime"),
        mapping("minecraft:pink_bed",       "minecraft:bed_head", "minecraft:entity/bed/pink"),
        mapping("minecraft:gray_bed",       "minecraft:bed_head", "minecraft:entity/bed/gray"),
        mapping("minecraft:light_gray_bed", "minecraft:bed_head", "minecraft:entity/bed/light_gray"),
        mapping("minecraft:cyan_bed",       "minecraft:bed_head", "minecraft:entity/bed/cyan"),
        mapping("minecraft:purple_bed",     "minecraft:bed_head", "minecraft:entity/bed/purple"),
        mapping("minecraft:blue_bed",       "minecraft:bed_head", "minecraft:entity/bed/blue"),
        mapping("minecraft:brown_bed",      "minecraft:bed_head", "minecraft:entity/bed/brown"),
        mapping("minecraft:green_bed",      "minecraft:bed_head", "minecraft:entity/bed/green"),
        mapping("minecraft:red_bed",        "minecraft:bed_head", "minecraft:entity/bed/red"),
        mapping("minecraft:black_bed",      "minecraft:bed_head", "minecraft:entity/bed/black"),

        // Signs (12 wood types)
        mapping("minecraft:oak_sign",      "minecraft:sign", "minecraft:entity/signs/oak"),
        mapping("minecraft:spruce_sign",   "minecraft:sign", "minecraft:entity/signs/spruce"),
        mapping("minecraft:birch_sign",    "minecraft:sign", "minecraft:entity/signs/birch"),
        mapping("minecraft:jungle_sign",   "minecraft:sign", "minecraft:entity/signs/jungle"),
        mapping("minecraft:acacia_sign",   "minecraft:sign", "minecraft:entity/signs/acacia"),
        mapping("minecraft:dark_oak_sign", "minecraft:sign", "minecraft:entity/signs/dark_oak"),
        mapping("minecraft:crimson_sign",  "minecraft:sign", "minecraft:entity/signs/crimson"),
        mapping("minecraft:warped_sign",   "minecraft:sign", "minecraft:entity/signs/warped"),
        mapping("minecraft:mangrove_sign", "minecraft:sign", "minecraft:entity/signs/mangrove"),
        mapping("minecraft:bamboo_sign",   "minecraft:sign", "minecraft:entity/signs/bamboo"),
        mapping("minecraft:cherry_sign",   "minecraft:sign", "minecraft:entity/signs/cherry"),
        mapping("minecraft:pale_oak_sign", "minecraft:sign", "minecraft:entity/signs/pale_oak"),

        // Hanging signs (12 wood types)
        mapping("minecraft:oak_hanging_sign",      "minecraft:hanging_sign", "minecraft:entity/signs/hanging/oak"),
        mapping("minecraft:spruce_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/spruce"),
        mapping("minecraft:birch_hanging_sign",    "minecraft:hanging_sign", "minecraft:entity/signs/hanging/birch"),
        mapping("minecraft:jungle_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/jungle"),
        mapping("minecraft:acacia_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/acacia"),
        mapping("minecraft:dark_oak_hanging_sign", "minecraft:hanging_sign", "minecraft:entity/signs/hanging/dark_oak"),
        mapping("minecraft:crimson_hanging_sign",  "minecraft:hanging_sign", "minecraft:entity/signs/hanging/crimson"),
        mapping("minecraft:warped_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/warped"),
        mapping("minecraft:mangrove_hanging_sign", "minecraft:hanging_sign", "minecraft:entity/signs/hanging/mangrove"),
        mapping("minecraft:bamboo_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/bamboo"),
        mapping("minecraft:cherry_hanging_sign",   "minecraft:hanging_sign", "minecraft:entity/signs/hanging/cherry"),
        mapping("minecraft:pale_oak_hanging_sign", "minecraft:hanging_sign", "minecraft:entity/signs/hanging/pale_oak"),

        // Conduit
        mapping("minecraft:conduit", "minecraft:conduit", "minecraft:entity/conduit/base")
    );

    private record BlockMapping(@NotNull String blockId, @NotNull String modelId, @NotNull String textureId) {}

    private static @NotNull BlockMapping mapping(@NotNull String blockId, @NotNull String modelId, @NotNull String textureId) {
        return new BlockMapping(blockId, modelId, textureId);
    }

    public static void main(String @NotNull [] args) throws IOException {
        AssetPipelineOptions options = AssetPipelineOptions.defaults();
        Path jarPath = ClientJarDownloader.download(options, new HttpFetcher());

        System.out.println("Parsing block entity models from client jar...");
        ConcurrentMap<String, JsonObject> models = Parser.parse(jarPath);
        System.out.printf("Extracted %d block entity models%n", models.size());

        Files.createDirectories(OUTPUT_PATH.getParent());
        Files.writeString(OUTPUT_PATH, buildJson(models, options.getVersion()));
        System.out.println("Wrote " + OUTPUT_PATH.toAbsolutePath());
    }

    private static @NotNull String buildJson(@NotNull ConcurrentMap<String, JsonObject> models, @NotNull String mcVersion) {
        JsonObject root = new JsonObject();
        root.addProperty("//", "Generated by ToolingBlockEntityModels. From net.minecraft.client.**.class$*Layer/Mesh(). Run the tooling/blockEntityModels Gradle task to refresh.");
        root.addProperty("source_version", mcVersion);

        JsonObject modelsObj = new JsonObject();
        models.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> modelsObj.add(entry.getKey(), entry.getValue()));
        root.add("models", modelsObj);

        JsonObject mappingsObj = new JsonObject();
        for (BlockMapping mapping : BLOCK_MAPPINGS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("model", mapping.modelId);
            entry.addProperty("texture", mapping.textureId);
            mappingsObj.add(mapping.blockId, entry);
        }
        root.add("mappings", mappingsObj);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
    }

    /**
     * An ASM bytecode walker that extracts block entity model geometry from the deobfuscated
     * Minecraft 26.1 client jar. Parses the {@code createSingleBodyLayer()} / {@code createBodyLayer()}
     * methods of model classes to extract cube definitions, UV offsets, pivot points, and texture
     * dimensions into {@code EntityModelData}-compatible JSON.
     * <p>
     * The parser tracks a numeric literal stack and recognises the builder-chain pattern used by
     * vanilla model classes:
     * <pre><code>
     * root.addOrReplaceChild("name",
     *     CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d),
     *     PartPose.offset(px, py, pz));
     * </code></pre>
     * Each {@code addOrReplaceChild} call emits a bone with its cubes. The texture dimensions are
     * extracted from the final {@code LayerDefinition.create(mesh, texW, texH)} call.
     *
     * @see BlockEntityModelLoader
     */
    @UtilityClass
    static class Parser {

        private static final @NotNull String CUBE_LIST_BUILDER = "net/minecraft/client/model/geom/builders/CubeListBuilder";
        private static final @NotNull String PART_POSE = "net/minecraft/client/model/geom/PartPose";
        private static final @NotNull String PART_DEFINITION = "net/minecraft/client/model/geom/builders/PartDefinition";
        private static final @NotNull String LAYER_DEFINITION = "net/minecraft/client/model/geom/builders/LayerDefinition";

        /**
         * Block entity model sources: class entry path, method name, and output entity id.
         * Methods that take parameters (like {@code createFlagLayer(boolean)}) are parsed with
         * default values - conditional branches are ignored (first path taken).
         */
        private static final @NotNull List<Source> SOURCES = List.of(
            new Source("net/minecraft/client/model/object/chest/ChestModel.class", "createSingleBodyLayer", "minecraft:chest"),
            new Source("net/minecraft/client/model/object/banner/BannerFlagModel.class", "createFlagLayer", "minecraft:banner"),
            new Source("net/minecraft/client/renderer/blockentity/BedRenderer.class", "createHeadLayer", "minecraft:bed_head"),
            new Source("net/minecraft/client/renderer/blockentity/BedRenderer.class", "createFootLayer", "minecraft:bed_foot"),
            new Source("net/minecraft/client/model/monster/shulker/ShulkerModel.class", "createShellMesh", "minecraft:shulker_box"),
            new Source("net/minecraft/client/renderer/blockentity/StandingSignRenderer.class", "createSignLayer", "minecraft:sign"),
            new Source("net/minecraft/client/renderer/blockentity/HangingSignRenderer.class", "createHangingSignLayer", "minecraft:hanging_sign"),
            new Source("net/minecraft/client/renderer/blockentity/ConduitRenderer.class", "createShellLayer", "minecraft:conduit")
        );

        private record Source(@NotNull String classEntry, @NotNull String methodName, @NotNull String entityId) {}

        /**
         * Parses all known block entity model classes from the supplied client jar and returns
         * the extracted models as serialised JSON objects keyed by entity id.
         *
         * @param jarPath the deobfuscated client jar (MC 26.1+)
         * @return a map of entity id to model JSON
         */
        public static @NotNull ConcurrentMap<String, JsonObject> parse(@NotNull Path jarPath) {
            ConcurrentMap<String, JsonObject> results = Concurrent.newMap();

            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                for (Source source : SOURCES) {
                    ZipEntry entry = zip.getEntry(source.classEntry);
                    if (entry == null) {
                        System.err.printf("  skipped %s: class not found in jar%n", source.entityId);
                        continue;
                    }

                    try (InputStream stream = zip.getInputStream(entry)) {
                        byte[] classBytes = stream.readAllBytes();
                        ClassNode classNode = new ClassNode();
                        new ClassReader(classBytes).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                        MethodNode method = classNode.methods.stream()
                            .filter(m -> m.name.equals(source.methodName))
                            .findFirst()
                            .orElse(null);

                        if (method == null) {
                            System.err.printf("  skipped %s: method '%s' not found%n", source.entityId, source.methodName);
                            continue;
                        }

                        JsonObject model = parseLayerMethod(method.instructions);
                        if (model != null)
                            results.put(source.entityId, model);

                    } catch (Exception ex) {
                        System.err.printf("  skipped %s: %s%n", source.entityId, ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                throw new AssetPipelineException(ex, "Failed to read client jar '%s'", jarPath);
            }

            return results;
        }

        /**
         * Parses a single layer-creation method's bytecode and extracts the model geometry.
         */
        private static @Nullable JsonObject parseLayerMethod(@NotNull InsnList instructions) {
            ConcurrentList<Number> numStack = Concurrent.newList();
            @Nullable String pendingPartName = null;
            int pendingTexU = 0;
            int pendingTexV = 0;

            // Per-cube state (accumulated across texOffs + addBox chains)
            ConcurrentList<float[]> pendingCubes = Concurrent.newList();
            int[] pendingUv = {0, 0};

            // Per-part state
            float[] pendingPivot = {0, 0, 0};
            float[] pendingRotation = {0, 0, 0};

            // Accumulated parts
            JsonObject bones = new JsonObject();
            int texWidth = 64;
            int texHeight = 64;

            for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
                // Track numeric literals
                Number literal = readNumericLiteral(node);
                if (literal != null) {
                    numStack.add(literal);
                    if (numStack.size() > 12)
                        numStack.removeFirst();
                    continue;
                }

                int opcode = node.getOpcode();

                if (node instanceof FieldInsnNode fieldInsn && opcode == Opcodes.GETSTATIC) {
                    if (fieldInsn.owner.equals(PART_POSE) && fieldInsn.name.equals("ZERO")) {
                        pendingPivot = new float[]{0, 0, 0};
                        pendingRotation = new float[]{0, 0, 0};
                    }
                } else if (node instanceof MethodInsnNode methodInsn) {
                    if (methodInsn.owner.equals(CUBE_LIST_BUILDER)) {
                        if (methodInsn.name.equals("texOffs") && methodInsn.desc.startsWith("(II")) {
                            pendingUv[1] = popInt(numStack);
                            pendingUv[0] = popInt(numStack);
                        } else if (methodInsn.name.equals("addBox") && methodInsn.desc.startsWith("(FFFFFF")) {
                            float d = popFloat(numStack);
                            float h = popFloat(numStack);
                            float w = popFloat(numStack);
                            float z = popFloat(numStack);
                            float y = popFloat(numStack);
                            float x = popFloat(numStack);
                            pendingCubes.add(new float[]{x, y, z, w, h, d, pendingUv[0], pendingUv[1]});
                        }
                    } else if (methodInsn.owner.equals(PART_POSE)) {
                        if (methodInsn.name.equals("offset") && methodInsn.desc.startsWith("(FFF")) {
                            float pz = popFloat(numStack);
                            float py = popFloat(numStack);
                            float px = popFloat(numStack);
                            pendingPivot = new float[]{px, py, pz};
                            pendingRotation = new float[]{0, 0, 0};
                        } else if (methodInsn.name.equals("offsetAndRotation") && methodInsn.desc.startsWith("(FFFFFF")) {
                            float rz = popFloat(numStack);
                            float ry = popFloat(numStack);
                            float rx = popFloat(numStack);
                            float pz = popFloat(numStack);
                            float py = popFloat(numStack);
                            float px = popFloat(numStack);
                            pendingPivot = new float[]{px, py, pz};
                            pendingRotation = new float[]{
                                (float) Math.toDegrees(rx),
                                (float) Math.toDegrees(ry),
                                (float) Math.toDegrees(rz)
                            };
                        }
                    } else if (methodInsn.owner.equals(PART_DEFINITION) && methodInsn.name.equals("addOrReplaceChild")) {
                        // The part name was pushed as an ldc String before the CubeListBuilder chain.
                        // Find it by scanning backward from numStack context.
                        if (pendingPartName != null && !pendingCubes.isEmpty()) {
                            bones.add(pendingPartName, buildBone(pendingPivot, pendingRotation, pendingCubes));
                        }
                        pendingPartName = null;
                        pendingCubes = Concurrent.newList();
                        pendingPivot = new float[]{0, 0, 0};
                        pendingRotation = new float[]{0, 0, 0};
                        pendingUv = new int[]{0, 0};
                    } else if (methodInsn.owner.equals(LAYER_DEFINITION) && methodInsn.name.equals("create")) {
                        texHeight = popInt(numStack);
                        texWidth = popInt(numStack);
                    }
                } else if (node instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    // Track the last string literal as potential part name
                    pendingPartName = s;
                }
            }

            if (bones.isEmpty()) return null;

            JsonObject model = new JsonObject();
            model.addProperty("textureWidth", texWidth);
            model.addProperty("textureHeight", texHeight);
            model.addProperty("negate_y", false);
            model.add("bones", bones);
            return model;
        }

        private static @NotNull JsonObject buildBone(float @NotNull [] pivot, float @NotNull [] rotation, @NotNull ConcurrentList<float[]> cubes) {
            JsonObject bone = new JsonObject();
            bone.add("pivot", floatArray(pivot));
            bone.add("rotation", floatArray(rotation));

            JsonArray cubeArray = new JsonArray();
            for (float[] c : cubes) {
                JsonObject cube = new JsonObject();
                cube.add("origin", floatArray(c[0], c[1], c[2]));
                cube.add("size", floatArray(c[3], c[4], c[5]));

                JsonArray uv = new JsonArray();
                uv.add((int) c[6]);
                uv.add((int) c[7]);
                cube.add("uv", uv);

                cube.addProperty("inflate", 0.0);
                cube.addProperty("mirror", false);
                cube.add("face_uv", new JsonObject());
                cubeArray.add(cube);
            }
            bone.add("cubes", cubeArray);
            return bone;
        }

        private static @NotNull JsonArray floatArray(float @NotNull ... values) {
            JsonArray arr = new JsonArray();
            for (float v : values) arr.add(v);
            return arr;
        }

        private static @Nullable Number readNumericLiteral(@NotNull AbstractInsnNode node) {
            int opcode = node.getOpcode();

            // iconst_m1 through iconst_5
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                return opcode - Opcodes.ICONST_0;

            // fconst_0 through fconst_2
            if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2)
                return (float) (opcode - Opcodes.FCONST_0);

            // bipush, sipush
            if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && node instanceof IntInsnNode intInsn)
                return intInsn.operand;

            // ldc int or float
            if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Integer || ldc.cst instanceof Float)
                    return (Number) ldc.cst;
            }

            return null;
        }

        private static int popInt(@NotNull ConcurrentList<Number> stack) {
            if (stack.isEmpty()) return 0;
            return stack.remove(stack.size() - 1).intValue();
        }

        private static float popFloat(@NotNull ConcurrentList<Number> stack) {
            if (stack.isEmpty()) return 0f;
            return stack.remove(stack.size() - 1).floatValue();
        }

    }

}
