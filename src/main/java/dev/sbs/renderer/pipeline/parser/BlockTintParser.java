package dev.sbs.renderer.pipeline.parser;

import dev.sbs.renderer.biome.BiomeTintTarget;
import dev.sbs.renderer.exception.AssetPipelineException;
import dev.sbs.renderer.model.Block;
import dev.sbs.renderer.pipeline.loader.BlockTintsLoader;
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
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An ASM bytecode walker that parses
 * {@code net.minecraft.client.color.block.BlockColors$createDefault()} from a deobfuscated
 * Minecraft client jar and translates its registration calls into {@link Block.Tint} entities.
 * <p>
 * The parser only handles deobfuscated jars - that is, MC 26.1 and later, where Mojang ships
 * source-equivalent class names. Older obfuscated jars cannot be parsed without a remapping
 * pass, which would require multiple bytecode-shape variants and per-version mapping plumbing
 * that the project explicitly does not pursue. The runtime pipeline reads
 * {@code /renderer/block_tints.json} from the classpath instead; this parser is invoked only
 * by the {@code generateVanillaTints} Gradle task to refresh that resource on a version bump.
 * <p>
 * Parsing approach: load the class through ASM's tree model, walk
 * {@code createDefault()}'s {@link InsnList} tracking three pieces of pending state:
 * <ul>
 * <li>the last {@code BlockTintSources.X()} factory call (the tint source),</li>
 * <li>any {@code ldc}/{@code bipush}/{@code iconst} integers preceding a {@code constant(int)}
 *     or {@code constant(int, int)} call (the ARGB values), and</li>
 * <li>every {@code GETSTATIC Blocks.X} field reference (the affected blocks).</li>
 * </ul>
 * When a {@code register(List, Block[])} call is hit, one {@link Block.Tint} is emitted per
 * collected block - provided the source method is one we recognise - and the running state is
 * reset. Multi-source registrations (recognisable by the 2-arg {@code List.of} overload) are
 * skipped because the atlas renderer cannot tint individual sprite layers.
 *
 * @see BlockTintsLoader
 * @see Block.Tint
 * @see BiomeTintTarget
 */
@UtilityClass
public class BlockTintParser {

    private static final @NotNull String BLOCK_COLORS_CLASS_ENTRY = "net/minecraft/client/color/block/BlockColors.class";
    private static final @NotNull String BLOCK_COLORS_INTERNAL_NAME = "net/minecraft/client/color/block/BlockColors";
    private static final @NotNull String BLOCK_TINT_SOURCES_INTERNAL_NAME = "net/minecraft/client/color/block/BlockTintSources";
    private static final @NotNull String BLOCKS_INTERNAL_NAME = "net/minecraft/world/level/block/Blocks";
    private static final @NotNull String CREATE_DEFAULT_METHOD_NAME = "createDefault";
    private static final @NotNull String REGISTER_METHOD_NAME = "register";
    private static final @NotNull String LIST_INTERNAL_NAME = "java/util/List";
    private static final @NotNull String LIST_OF_SINGLE_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/util/List;";

    /**
     * Maps the short name of a {@code BlockTintSources.X()} factory method to the corresponding
     * {@link BiomeTintTarget}. Sources whose tint depends on dynamic per-block state - water,
     * waterParticles, redstone, stem - are not in the map and are silently dropped because the
     * atlas renderer cannot resolve them at static-render time.
     */
    private static final @NotNull ConcurrentMap<String, BiomeTintTarget> SUPPORTED_SOURCES = buildSupportedSources();

    private static @NotNull ConcurrentMap<String, BiomeTintTarget> buildSupportedSources() {
        ConcurrentMap<String, BiomeTintTarget> map = Concurrent.newMap();
        // GRASS colormap sources - the BlockTintSources helper distinguishes several grass
        // variants (grass, grassBlock with its top-face-only sampling, sugarCane's biome
        // modifier, doubleTallGrass for large_fern / tall_grass), all of which sample the grass
        // colormap at render time. For atlas rendering they collapse to the same target.
        map.put("grass", BiomeTintTarget.GRASS);
        map.put("grassBlock", BiomeTintTarget.GRASS);
        map.put("sugarCane", BiomeTintTarget.GRASS);
        map.put("doubleTallGrass", BiomeTintTarget.GRASS);
        map.put("foliage", BiomeTintTarget.FOLIAGE);
        map.put("dryFoliage", BiomeTintTarget.DRY_FOLIAGE);
        return map;
    }

    /**
     * Parses the vanilla block tint table out of {@code BlockColors.createDefault()} in the
     * supplied client jar.
     *
     * @param jarPath the deobfuscated client jar to read from (MC 26.1+)
     * @return a map of block id to tint binding
     * @throws AssetPipelineException if the jar cannot be read, the BlockColors class is not
     *     present (jar is obfuscated or from an unsupported version), or {@code createDefault}
     *     is missing
     */
    public static @NotNull ConcurrentMap<String, Block.Tint> parse(@NotNull Path jarPath) {
        byte[] classBytes = readClassBytes(jarPath);
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        MethodNode createDefault = classNode.methods.stream()
            .filter(m -> m.name.equals(CREATE_DEFAULT_METHOD_NAME))
            .findFirst()
            .orElseThrow(() -> new AssetPipelineException(
                "BlockColors class does not expose a '%s' method - jar may be obfuscated or from an unsupported version",
                CREATE_DEFAULT_METHOD_NAME
            ));

        return parseCreateDefault(createDefault.instructions);
    }

    /**
     * Opens the client jar as a zip archive and reads the raw bytes of
     * {@code net/minecraft/client/color/block/BlockColors.class}. Caller decodes via ASM.
     */
    private static byte @NotNull [] readClassBytes(@NotNull Path jarPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry(BLOCK_COLORS_CLASS_ENTRY);
            if (entry == null)
                throw new AssetPipelineException(
                    "Jar '%s' does not contain '%s' - the jar is either obfuscated (pre-26.1) or from an unsupported version",
                    jarPath, BLOCK_COLORS_CLASS_ENTRY
                );

            try (InputStream stream = zip.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        } catch (IOException ex) {
            throw new AssetPipelineException(ex, "Failed to read BlockColors class from jar '%s'", jarPath);
        }
    }

    /**
     * Walks the {@code createDefault()} instruction list and emits a {@link Block.Tint} per
     * recognised {@code (source, blocks[])} registration. See the class-level javadoc for the
     * recognition rules.
     */
    private static @NotNull ConcurrentMap<String, Block.Tint> parseCreateDefault(@NotNull InsnList instructions) {
        ConcurrentMap<String, Block.Tint> tints = Concurrent.newMap();

        @Nullable String pendingSource = null;
        int pendingConstantA = 0;
        int pendingConstantB = 0;
        int pendingConstantCount = 0;
        int pendingSourceLayers = 0;
        ConcurrentList<String> pendingBlocks = Concurrent.newList();
        ConcurrentList<Integer> intLiteralStack = Concurrent.newList();

        for (AbstractInsnNode node = instructions.getFirst(); node != null; node = node.getNext()) {
            int opcode = node.getOpcode();

            // Track integer literals so we can consume them when a constant(...) factory call
            // appears. BlockTintSources.constant(int) is preceded by one int; constant(int, int)
            // by two. Only the last few literals matter because intervening bytecode (ANEWARRAY,
            // AASTORE, etc.) never pushes ints that would survive to the constant() call.
            Integer literal = readIntLiteral(node);
            if (literal != null) {
                intLiteralStack.add(literal);

                if (intLiteralStack.size() > 4)
                    intLiteralStack.removeFirst();

                continue;
            }

            switch (node) {
                case FieldInsnNode fieldInsn when opcode == Opcodes.GETSTATIC -> {
                    if (fieldInsn.owner.equals(BLOCKS_INTERNAL_NAME))
                        pendingBlocks.add(blockIdFromField(fieldInsn.name));
                }
                case MethodInsnNode methodInsn when opcode == Opcodes.INVOKESTATIC -> {
                    if (methodInsn.owner.equals(BLOCK_TINT_SOURCES_INTERNAL_NAME)) {
                        pendingSource = methodInsn.name;
                        pendingSourceLayers++;

                        if (methodInsn.name.equals("constant") && methodInsn.desc.startsWith("(I")) {
                            if (methodInsn.desc.equals("(I)Lnet/minecraft/client/color/block/BlockTintSource;")) {
                                pendingConstantA = popLastLiteral(intLiteralStack);
                                pendingConstantCount = 1;
                            } else if (methodInsn.desc.equals("(II)Lnet/minecraft/client/color/block/BlockTintSource;")) {
                                pendingConstantB = popLastLiteral(intLiteralStack);
                                pendingConstantA = popLastLiteral(intLiteralStack);
                                pendingConstantCount = 2;
                            }
                        }
                    } else if (methodInsn.owner.equals(LIST_INTERNAL_NAME) && methodInsn.name.equals("of")
                        && !methodInsn.desc.equals(LIST_OF_SINGLE_DESCRIPTOR)) {
                        // Multi-argument List.of call means the register() block composes multiple
                        // sources (e.g. [BLANK_LAYER, grass()] for pink_petals). Skip it.
                        pendingSource = null;
                    }
                }
                case MethodInsnNode methodInsn when opcode == Opcodes.INVOKEVIRTUAL && methodInsn.owner.equals(BLOCK_COLORS_INTERNAL_NAME) && methodInsn.name.equals(REGISTER_METHOD_NAME) -> {

                    if (pendingSource != null && pendingSourceLayers == 1 && !pendingBlocks.isEmpty())
                        emitTints(tints, pendingSource, pendingConstantA, pendingConstantB, pendingConstantCount, pendingBlocks);

                    pendingSource = null;
                    pendingConstantA = 0;
                    pendingConstantB = 0;
                    pendingConstantCount = 0;
                    pendingSourceLayers = 0;
                    pendingBlocks.clear();
                    intLiteralStack.clear();
                }
                default -> { }
            }

        }

        return tints;
    }

    /**
     * Decodes an int literal from a bytecode instruction, returning {@code null} for nodes that
     * do not push a compile-time integer constant onto the stack.
     */
    private static @Nullable Integer readIntLiteral(@NotNull AbstractInsnNode node) {
        int opcode = node.getOpcode();

        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            return opcode - Opcodes.ICONST_0;

        if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && node instanceof IntInsnNode intInsn)
            return intInsn.operand;

        if (opcode == Opcodes.LDC && node instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value)
            return value;

        return null;
    }

    /**
     * Pops the most recent value off the running integer-literal stack, returning {@code 0}
     * when the stack is empty. Used to grab the {@code ldc}/{@code bipush} constants that
     * preceded a {@code BlockTintSources.constant(int)} or {@code constant(int, int)} call.
     */
    private static int popLastLiteral(@NotNull ConcurrentList<Integer> stack) {
        if (stack.isEmpty()) return 0;
        return stack.remove(stack.size() - 1);
    }

    /**
     * Emits one {@link Block.Tint} per block for the current pending registration, after
     * verifying the source method is one the renderer knows how to sample. Unsupported sources
     * (water, waterParticles, redstone, stem) are silently dropped.
     */
    private static void emitTints(
        @NotNull ConcurrentMap<String, Block.Tint> tints,
        @NotNull String sourceMethod,
        int constantA,
        int constantB,
        int constantCount,
        @NotNull ConcurrentList<String> blocks
    ) {
        BiomeTintTarget target;
        Optional<Integer> constant = Optional.empty();

        if (sourceMethod.equals("constant")) {
            target = BiomeTintTarget.CONSTANT;
            // BlockTintSources.constant(int, int) is the two-colour overload used for lily_pad:
            // first int is the in-world colour, second is the GUI default. Atlas rendering is
            // item-perspective, so the GUI default wins.
            int argb = constantCount == 2 ? constantB : constantA;
            constant = Optional.of(argb);
        } else {
            BiomeTintTarget mapped = SUPPORTED_SOURCES.get(sourceMethod);
            if (mapped == null) return;
            target = mapped;
        }

        Block.Tint tint = new Block.Tint(target, constant);
        for (String blockId : blocks)
            tints.put(blockId, tint);
    }

    /**
     * Derives a namespaced block id from the {@code Blocks.X} static field name.
     * Convention is straight {@code toLowerCase()}: {@code Blocks.GRASS_BLOCK} - &gt;
     * {@code minecraft:grass_block}.
     */
    private static @NotNull String blockIdFromField(@NotNull String fieldName) {
        return "minecraft:" + fieldName.toLowerCase();
    }

}
