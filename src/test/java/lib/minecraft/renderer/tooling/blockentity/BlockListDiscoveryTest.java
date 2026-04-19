package lib.minecraft.renderer.tooling.blockentity;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Fast mutation tests for {@link BlockListDiscovery}. Each nested test class builds synthetic
 * client-jar bytecode via {@link ClassWriter} targeting one shared primitive or per-family
 * adapter. The mutation discipline is consistent with {@link SourceDiscoveryTest}: every test
 * builds a baseline, asserts the expected output, then mutates the input and asserts the
 * output changes - proving the walker is driven by the bytecode rather than a hardcoded table.
 *
 * <p>Helpers at the bottom of the file construct the skeleton classes shared across tests;
 * each nested class typically rebuilds only the bytecode it needs to exercise.
 */
@DisplayName("BlockListDiscovery (bytecode-driven)")
class BlockListDiscoveryTest {

    @TempDir Path tempDir;

    // ------------------------------------------------------------------------------------------
    // Shared primitive tests
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("validBlocks(zip, beField)")
    class ValidBlocksTests {

        @Test
        @DisplayName("captures GETSTATICs between LDC id and PUTSTATIC field")
        void captures() throws IOException {
            // <clinit>: ldc "chest"; getstatic Blocks.CHEST; getstatic Blocks.COPPER_CHEST;
            //           putstatic BlockEntityType.CHEST
            byte[] beType = buildBlockEntityTypeClass(builder -> {
                builder.ldc("chest");
                builder.getstaticBlocks("CHEST");
                builder.getstaticBlocks("COPPER_CHEST");
                builder.putstaticBeType("CHEST");
            });
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                List<String> out = BlockListDiscovery.validBlocks(zf, "CHEST");
                assertThat(out, contains("CHEST", "COPPER_CHEST"));
            }
        }

        @Test
        @DisplayName("mutating GETSTATIC list changes the output order")
        void mutateOrder() throws IOException {
            byte[] beType = buildBlockEntityTypeClass(builder -> {
                builder.ldc("chest");
                builder.getstaticBlocks("COPPER_CHEST");  // swapped order
                builder.getstaticBlocks("CHEST");
                builder.putstaticBeType("CHEST");
            });
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                List<String> out = BlockListDiscovery.validBlocks(zf, "CHEST");
                assertThat("GETSTATIC order preserved", out, contains("COPPER_CHEST", "CHEST"));
            }
        }

        @Test
        @DisplayName("unknown BE field returns empty list")
        void unknownField() throws IOException {
            byte[] beType = buildBlockEntityTypeClass(builder -> {
                builder.ldc("chest");
                builder.getstaticBlocks("CHEST");
                builder.putstaticBeType("CHEST");
            });
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                assertThat(BlockListDiscovery.validBlocks(zf, "NOT_A_FIELD"), empty());
            }
        }
    }

    @Nested
    @DisplayName("walkDyeColorNames")
    class WalkDyeColorNamesTests {

        @Test
        @DisplayName("extracts (FIELD -> serialized_name) pairs in declaration order")
        void extractsPairs() throws IOException {
            byte[] dyeColor = buildTwoLdcEnumClass("net/minecraft/world/item/DyeColor",
                List.of(new EnumEntry("WHITE", "white"), new EnumEntry("BLACK", "black")));
            Path jar = writeJar(Map.of("net/minecraft/world/item/DyeColor", dyeColor));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkDyeColorNames(zf);
                assertThat(out.entrySet(), hasSize(2));
                assertThat(out, hasEntry("WHITE", "white"));
                assertThat(out, hasEntry("BLACK", "black"));
                // Order preserved
                assertThat(List.copyOf(out.keySet()), contains("WHITE", "BLACK"));
            }
        }

        @Test
        @DisplayName("renaming the serialized LDC flips the map value")
        void renameLdc() throws IOException {
            byte[] dyeColor = buildTwoLdcEnumClass("net/minecraft/world/item/DyeColor",
                List.of(new EnumEntry("WHITE", "off_white")));  // changed from "white"
            Path jar = writeJar(Map.of("net/minecraft/world/item/DyeColor", dyeColor));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkDyeColorNames(zf);
                assertThat("mutated second LDC surfaces as map value", out, hasEntry("WHITE", "off_white"));
            }
        }
    }

    @Nested
    @DisplayName("walkWoodTypeNames")
    class WalkWoodTypeNamesTests {

        @Test
        @DisplayName("extracts (FIELD -> name) from single-LDC record <clinit>")
        void extractsSingleLdc() throws IOException {
            byte[] woodType = buildSingleLdcRecordClass("net/minecraft/world/level/block/state/properties/WoodType",
                List.of("oak", "spruce"),
                List.of("OAK", "SPRUCE"));
            Path jar = writeJar(Map.of("net/minecraft/world/level/block/state/properties/WoodType", woodType));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkWoodTypeNames(zf);
                assertThat(out, hasEntry("OAK", "oak"));
                assertThat(out, hasEntry("SPRUCE", "spruce"));
            }
        }

        @Test
        @DisplayName("mutating LDC arg changes the map value")
        void mutateLdc() throws IOException {
            byte[] woodType = buildSingleLdcRecordClass("net/minecraft/world/level/block/state/properties/WoodType",
                List.of("redwood"),
                List.of("OAK"));
            Path jar = writeJar(Map.of("net/minecraft/world/level/block/state/properties/WoodType", woodType));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkWoodTypeNames(zf);
                assertThat("mutated LDC surfaces in map value", out, hasEntry("OAK", "redwood"));
            }
        }
    }

    @Nested
    @DisplayName("walkBellAttachTypesOrder")
    class WalkBellAttachTypesOrderTests {

        @Test
        @DisplayName("returns field names in declaration order")
        void orderPreserved() throws IOException {
            byte[] bell = buildTwoLdcEnumClass("net/minecraft/world/level/block/state/properties/BellAttachType",
                List.of(
                    new EnumEntry("FLOOR", "floor"),
                    new EnumEntry("CEILING", "ceiling"),
                    new EnumEntry("SINGLE_WALL", "single_wall"),
                    new EnumEntry("DOUBLE_WALL", "double_wall")
                ));
            Path jar = writeJar(Map.of("net/minecraft/world/level/block/state/properties/BellAttachType", bell));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                List<String> out = BlockListDiscovery.walkBellAttachTypesOrder(zf);
                assertThat(out, contains("FLOOR", "CEILING", "SINGLE_WALL", "DOUBLE_WALL"));
            }
        }

        @Test
        @DisplayName("dropping an entry shrinks the list")
        void dropEntry() throws IOException {
            byte[] bell = buildTwoLdcEnumClass("net/minecraft/world/level/block/state/properties/BellAttachType",
                List.of(
                    new EnumEntry("FLOOR", "floor"),
                    new EnumEntry("CEILING", "ceiling")
                ));
            Path jar = writeJar(Map.of("net/minecraft/world/level/block/state/properties/BellAttachType", bell));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                assertThat(BlockListDiscovery.walkBellAttachTypesOrder(zf), hasSize(2));
            }
        }
    }

    @Nested
    @DisplayName("walkChestSpecialRendererVariants")
    class WalkChestVariantsTests {

        @Test
        @DisplayName("captures (FIELD -> ldc) for both createDefaultTextures and withDefaultNamespace shapes")
        void capturesBothShapes() throws IOException {
            // Two variants: one createDefaultTextures (REGULAR -> "normal") and one
            // withDefaultNamespace (ENDER_CHEST -> "ender").
            byte[] chestRenderer = buildChestSpecialRenderer(List.of(
                new ChestVariant("ENDER_CHEST", "ender", "withDefaultNamespace"),
                new ChestVariant("REGULAR", "normal", "createDefaultTextures"),
                new ChestVariant("COPPER_UNAFFECTED", "copper", "createDefaultTextures")
            ));
            Path jar = writeJar(Map.of("net/minecraft/client/renderer/special/ChestSpecialRenderer", chestRenderer));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkChestSpecialRendererVariants(zf);
                assertThat(out, hasEntry("ENDER_CHEST", "ender"));
                assertThat(out, hasEntry("REGULAR", "normal"));
                assertThat(out, hasEntry("COPPER_UNAFFECTED", "copper"));
            }
        }

        @Test
        @DisplayName("renaming a variant LDC flips the map value")
        void mutateLdc() throws IOException {
            byte[] chestRenderer = buildChestSpecialRenderer(List.of(
                new ChestVariant("REGULAR", "fancy_normal", "createDefaultTextures")  // changed
            ));
            Path jar = writeJar(Map.of("net/minecraft/client/renderer/special/ChestSpecialRenderer", chestRenderer));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkChestSpecialRendererVariants(zf);
                assertThat(out, hasEntry("REGULAR", "fancy_normal"));
            }
        }
    }

    @Nested
    @DisplayName("walkCopperGolemOxidationLevels")
    class WalkCopperGolemTests {

        @Test
        @DisplayName("captures (FIELD -> canonical texture path) with textures/ + .png stripped")
        void captures() throws IOException {
            byte[] cls = buildCopperGolemOxidationLevels(List.of(
                new OxidationEntry("UNAFFECTED", "textures/entity/copper_golem/copper_golem.png"),
                new OxidationEntry("EXPOSED", "textures/entity/copper_golem/copper_golem_exposed.png")
            ));
            Path jar = writeJar(Map.of("net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevels", cls));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkCopperGolemOxidationLevels(zf);
                assertThat("textures/ prefix and .png suffix stripped",
                    out, hasEntry("UNAFFECTED", "entity/copper_golem/copper_golem"));
                assertThat(out, hasEntry("EXPOSED", "entity/copper_golem/copper_golem_exposed"));
            }
        }

        @Test
        @DisplayName("mutating the LDC path mutates the stripped result")
        void mutateLdc() throws IOException {
            byte[] cls = buildCopperGolemOxidationLevels(List.of(
                new OxidationEntry("UNAFFECTED", "textures/entity/redstone_golem/body.png")
            ));
            Path jar = writeJar(Map.of("net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevels", cls));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkCopperGolemOxidationLevels(zf);
                assertThat(out, hasEntry("UNAFFECTED", "entity/redstone_golem/body"));
            }
        }
    }

    @Nested
    @DisplayName("walkSkullSkinMap")
    class WalkSkullSkinMapTests {

        @Test
        @DisplayName("captures (SkullBlock$Types.X -> stripped texture path)")
        void captures() throws IOException {
            byte[] skullRenderer = buildSkullBlockRenderer(List.of(
                new SkullSkin("SKELETON", "textures/entity/skeleton/skeleton.png", false),
                new SkullSkin("WITHER_SKELETON", "textures/entity/skeleton/wither_skeleton.png", false)
            ));
            Path jar = writeJar(Map.of(
                "net/minecraft/client/renderer/blockentity/SkullBlockRenderer", skullRenderer,
                "net/minecraft/world/level/block/SkullBlock$Types", emptyClass("net/minecraft/world/level/block/SkullBlock$Types")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkSkullSkinMap(zf, new Diagnostics());
                assertThat(out, hasEntry("SKELETON", "entity/skeleton/skeleton"));
                assertThat(out, hasEntry("WITHER_SKELETON", "entity/skeleton/wither_skeleton"));
            }
        }

        @Test
        @DisplayName("PLAYER entry chases DefaultPlayerSkin.getDefaultSkin + <clinit>")
        void playerFollow() throws IOException {
            byte[] skullRenderer = buildSkullBlockRenderer(List.of(
                new SkullSkin("PLAYER", null, true)  // uses DefaultPlayerSkin.getDefaultTexture()
            ));
            // DefaultPlayerSkin with getDefaultSkin returning DEFAULT_SKINS[2] and <clinit> storing
            // "entity/player/fake/path" at index 2.
            byte[] playerSkin = buildDefaultPlayerSkin(2, Map.of(2, "entity/player/fake/path"));
            Path jar = writeJar(Map.of(
                "net/minecraft/client/renderer/blockentity/SkullBlockRenderer", skullRenderer,
                "net/minecraft/world/level/block/SkullBlock$Types", emptyClass("net/minecraft/world/level/block/SkullBlock$Types"),
                "net/minecraft/client/resources/DefaultPlayerSkin", playerSkin
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, String> out = BlockListDiscovery.walkSkullSkinMap(zf, new Diagnostics());
                // PLAYER traces to "entity/player/fake/path" via the DEFAULT_SKINS[2] walk.
                assertThat("PLAYER resolves to LDC at DefaultPlayerSkin index 2",
                    out, hasEntry("PLAYER", "entity/player/fake/path"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Per-family adapter tests
    // ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("discover() on synthetic jar")
    class DiscoverTests {

        @Test
        @DisplayName("empty dispatch returns empty map when no BE types exist")
        void emptyDispatch() throws IOException {
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", emptyClassWithClinit("net/minecraft/world/level/block/entity/BlockEntityType"),
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, BlockListDiscovery.EntityBlockMapping> out = BlockListDiscovery.discover(zf, new Diagnostics());
                // Banner adapter always emits empty sub-model entities; others return empty mappings.
                for (BlockListDiscovery.EntityBlockMapping mapping : out.values())
                    assertThat("empty jar -> all mappings empty blocks", mapping.blocks(), empty());
            }
        }
    }

    @Nested
    @DisplayName("Bell adapter")
    class BellTests {

        @Test
        @DisplayName("emits 4 faux block ids per BELL_ATTACH_SUFFIX")
        void fourFauxIds() throws IOException {
            byte[] beType = buildBlockEntityTypeClass(b -> {
                b.ldc("bell");
                b.getstaticBlocks("BELL");
                b.putstaticBeType("BELL");
            });
            byte[] bellRenderer = buildBellRenderer("bell/bell_body");
            byte[] bellAttach = buildTwoLdcEnumClass("net/minecraft/world/level/block/state/properties/BellAttachType",
                List.of(
                    new EnumEntry("FLOOR", "floor"),
                    new EnumEntry("CEILING", "ceiling"),
                    new EnumEntry("SINGLE_WALL", "single_wall"),
                    new EnumEntry("DOUBLE_WALL", "double_wall")
                ));
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks"),
                "net/minecraft/client/renderer/blockentity/BellRenderer", bellRenderer,
                "net/minecraft/world/level/block/state/properties/BellAttachType", bellAttach
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, BlockListDiscovery.EntityBlockMapping> out = BlockListDiscovery.discover(zf, new Diagnostics());
                BlockListDiscovery.EntityBlockMapping bell = out.get("minecraft:bell_body");
                assertThat(bell, notNullValue());
                assertThat(bell.blocks(), hasSize(4));
                assertThat("BellAttachType order -> bell_floor, bell_ceiling, bell_wall, bell_between_walls",
                    bell.blocks().stream().map(BlockListDiscovery.BlockMapping::blockId).toList(),
                    contains("minecraft:bell_floor", "minecraft:bell_ceiling", "minecraft:bell_wall", "minecraft:bell_between_walls"));
                // All 4 share the bell_body texture (prepended with entity/ by the mapper base).
                for (BlockListDiscovery.BlockMapping bm : bell.blocks())
                    assertThat(bm.textureId(), equalTo("minecraft:entity/bell/bell_body"));
            }
        }
    }

    @Nested
    @DisplayName("Decorated pot adapter")
    class DecoratedPotTests {

        @Test
        @DisplayName("emits pot + sides with (0,0,0) offset and base/side textures")
        void potSplit() throws IOException {
            byte[] beType = buildBlockEntityTypeClass(b -> {
                b.ldc("decorated_pot");
                b.getstaticBlocks("DECORATED_POT");
                b.putstaticBeType("DECORATED_POT");
            });
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks")
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, BlockListDiscovery.EntityBlockMapping> out = BlockListDiscovery.discover(zf, new Diagnostics());
                BlockListDiscovery.EntityBlockMapping pot = out.get("minecraft:decorated_pot");
                assertThat(pot, notNullValue());
                assertThat(pot.blocks(), hasSize(1));
                assertThat(pot.blocks().get(0).blockId(), equalTo("minecraft:decorated_pot"));
                assertThat(pot.blocks().get(0).textureId(), equalTo("minecraft:entity/decorated_pot/decorated_pot_base"));
                assertThat(pot.parts(), notNullValue());
                assertThat(pot.parts(), hasSize(1));
                assertThat(pot.parts().get(0).model(), equalTo("minecraft:decorated_pot_sides"));
                assertThat(pot.parts().get(0).offset(), equalTo(new int[]{ 0, 0, 0 }));
                assertThat(pot.parts().get(0).texture(), equalTo("minecraft:entity/decorated_pot/decorated_pot_side"));

                BlockListDiscovery.EntityBlockMapping sides = out.get("minecraft:decorated_pot_sides");
                assertThat(sides, notNullValue());
                assertThat(sides.blocks(), empty());
                assertThat(sides.parts(), nullValue());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shared bytecode builders
    // ------------------------------------------------------------------------------------------

    /**
     * @param name the enum constant field name (e.g. {@code "WHITE"})
     * @param serializedName the string LDC that becomes the map value
     */
    private record EnumEntry(String name, String serializedName) {}

    /**
     * @param field the chest-variant field name (e.g. {@code "REGULAR"})
     * @param ldc the string LDC bound to the variant
     * @param shape either {@code "createDefaultTextures"} or {@code "withDefaultNamespace"}
     */
    private record ChestVariant(String field, String ldc, String shape) {}

    private record OxidationEntry(String field, String fullTexturePath) {}

    /**
     * @param type the SkullBlock$Types field (e.g. {@code "SKELETON"})
     * @param texture the textures/... path LDC (null for PLAYER when usePlayerFollow is true)
     * @param usePlayerFollow when true, emit {@code INVOKESTATIC DefaultPlayerSkin.getDefaultTexture}
     *     instead of an LDC
     */
    private record SkullSkin(String type, String texture, boolean usePlayerFollow) {}

    /**
     * Minimal BlockEntityType class builder. The lambda receives a
     * {@link BlockEntityTypeClinitBuilder} that emits ldc/getstaticBlocks/putstaticBeType
     * instructions into the class's {@code <clinit>}.
     */
    private interface BlockEntityTypeClinitBuilder {
        void ldc(String id);
        void getstaticBlocks(String field);
        void putstaticBeType(String field);
    }

    private static byte[] buildBlockEntityTypeClass(@NotNull java.util.function.Consumer<BlockEntityTypeClinitBuilder> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/entity/BlockEntityType", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        java.util.Set<String> declaredFields = new java.util.LinkedHashSet<>();
        BlockEntityTypeClinitBuilder builder = new BlockEntityTypeClinitBuilder() {
            @Override public void ldc(String id) {
                mv.visitLdcInsn(id);
                // Bury the LDC with a POP so stack stays sensible; the walker still sees the LDC.
                mv.visitInsn(Opcodes.POP);
            }
            @Override public void getstaticBlocks(String field) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/block/Blocks", field, "Lnet/minecraft/world/level/block/Block;");
                mv.visitInsn(Opcodes.POP);
            }
            @Override public void putstaticBeType(String field) {
                declaredFields.add(field);
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, "net/minecraft/world/level/block/entity/BlockEntityType", field,
                    "Lnet/minecraft/world/level/block/entity/BlockEntityType;");
            }
        };
        body.accept(builder);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        // Declare each field referenced by PUTSTATIC.
        for (String field : declaredFields)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field,
                "Lnet/minecraft/world/level/block/entity/BlockEntityType;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds an enum-shaped {@code <clinit>} with {@code NEW enum; DUP; LDC NAME; iconst_N;
     * iconst_N; LDC name; INVOKESPECIAL; PUTSTATIC FIELD} per entry. Produces a valid
     * {@code <clinit>} that our {@code walkEnumSerializedNames} walker recognises.
     */
    private static byte[] buildTwoLdcEnumClass(@NotNull String internalName, @NotNull List<EnumEntry> entries) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        for (EnumEntry e : entries)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, e.name, "L" + internalName + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        int idx = 0;
        for (EnumEntry e : entries) {
            mv.visitTypeInsn(Opcodes.NEW, internalName);
            mv.visitInsn(Opcodes.POP);                                 // pretend we've populated it
            mv.visitLdcInsn(e.name);                                   // first LDC (enum name)
            mv.visitInsn(Opcodes.POP);
            mv.visitIntInsn(Opcodes.BIPUSH, idx);
            mv.visitInsn(Opcodes.POP);
            mv.visitLdcInsn(e.serializedName);                         // second LDC (name())
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, e.name, "L" + internalName + ";");
            idx++;
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a record-shaped {@code <clinit>} with {@code NEW; DUP; LDC name; ...; INVOKESPECIAL;
     * PUTSTATIC FIELD} per entry. Only ONE LDC between NEW and PUTSTATIC - the walker uses the
     * first one as the record's {@code name} field.
     */
    private static byte[] buildSingleLdcRecordClass(@NotNull String internalName, @NotNull List<String> names, @NotNull List<String> fields) {
        assert names.size() == fields.size();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        for (String field : fields)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "L" + internalName + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (int i = 0; i < names.size(); i++) {
            mv.visitTypeInsn(Opcodes.NEW, internalName);
            mv.visitInsn(Opcodes.POP);
            mv.visitLdcInsn(names.get(i));
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, fields.get(i), "L" + internalName + ";");
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildChestSpecialRenderer(@NotNull List<ChestVariant> variants) {
        String cls = "net/minecraft/client/renderer/special/ChestSpecialRenderer";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        for (ChestVariant v : variants) {
            String desc = v.shape.equals("withDefaultNamespace")
                ? "Lnet/minecraft/resources/Identifier;"
                : "Lnet/minecraft/client/renderer/MultiblockChestResources;";
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, v.field, desc, null, null).visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (ChestVariant v : variants) {
            mv.visitLdcInsn(v.ldc);
            if (v.shape.equals("withDefaultNamespace")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/resources/Identifier",
                    "withDefaultNamespace", "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, cls, v.field, "Lnet/minecraft/resources/Identifier;");
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, cls, "createDefaultTextures",
                    "(Ljava/lang/String;)Lnet/minecraft/client/renderer/MultiblockChestResources;", false);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, cls, v.field, "Lnet/minecraft/client/renderer/MultiblockChestResources;");
            }
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildCopperGolemOxidationLevels(@NotNull List<OxidationEntry> entries) {
        String cls = "net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevels";
        String itemCls = "net/minecraft/world/entity/animal/golem/CopperGolemOxidationLevel";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        for (OxidationEntry e : entries)
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, e.field,
                "L" + itemCls + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        for (OxidationEntry e : entries) {
            mv.visitTypeInsn(Opcodes.NEW, itemCls);
            mv.visitInsn(Opcodes.POP);
            mv.visitLdcInsn(e.fullTexturePath);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, cls, e.field, "L" + itemCls + ";");
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildSkullBlockRenderer(@NotNull List<SkullSkin> skins) {
        String cls = "net/minecraft/client/renderer/blockentity/SkullBlockRenderer";
        String typesCls = "net/minecraft/world/level/block/SkullBlock$Types";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        // The walker looks for lambda$static$<N> with (Ljava/util/HashMap;)V.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            "lambda$static$0", "(Ljava/util/HashMap;)V", null, null);
        mv.visitCode();
        for (SkullSkin s : skins) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, typesCls, s.type, "L" + typesCls + ";");
            if (s.usePlayerFollow) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "net/minecraft/client/resources/DefaultPlayerSkin", "getDefaultTexture",
                    "()Lnet/minecraft/resources/Identifier;", false);
            } else {
                mv.visitLdcInsn(s.texture);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "net/minecraft/resources/Identifier", "withDefaultNamespace",
                    "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;", false);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a minimal {@code DefaultPlayerSkin} with (a) {@code getDefaultSkin} returning
     * {@code DEFAULT_SKINS[targetIndex]}; and (b) {@code <clinit>} populating
     * {@code DEFAULT_SKINS} with the supplied {@code indexToLdc} entries. All other entries
     * are filled with a placeholder so the array size matches the max index + 1.
     */
    private static byte[] buildDefaultPlayerSkin(int targetIndex, @NotNull Map<Integer, String> indexToLdc) {
        String cls = "net/minecraft/client/resources/DefaultPlayerSkin";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "DEFAULT_SKINS",
            "[Lnet/minecraft/world/entity/player/PlayerSkin;", null, null).visitEnd();

        // getDefaultSkin()
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getDefaultSkin",
            "()Lnet/minecraft/world/entity/player/PlayerSkin;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, cls, "DEFAULT_SKINS", "[Lnet/minecraft/world/entity/player/PlayerSkin;");
        mv.visitIntInsn(Opcodes.BIPUSH, targetIndex);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();

        // <clinit>
        int size = indexToLdc.keySet().stream().mapToInt(Integer::intValue).max().orElse(targetIndex) + 1;
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, size);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/world/entity/player/PlayerSkin");
        for (int i = 0; i < size; i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            mv.visitLdcInsn(indexToLdc.getOrDefault(i, "entity/player/wide/placeholder"));
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitFieldInsn(Opcodes.PUTSTATIC, cls, "DEFAULT_SKINS", "[Lnet/minecraft/world/entity/player/PlayerSkin;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(5, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildBellRenderer(@NotNull String ldcString) {
        String cls = "net/minecraft/client/renderer/blockentity/BellRenderer";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BELL_TEXTURE",
            "Lnet/minecraft/client/resources/model/sprite/SpriteId;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(ldcString);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, cls, "BELL_TEXTURE",
            "Lnet/minecraft/client/resources/model/sprite/SpriteId;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // Skeleton / plumbing
    // ------------------------------------------------------------------------------------------

    private static byte[] emptyClass(@NotNull String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClassWithClinit(@NotNull String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Path writeJar(@NotNull Map<String, byte[]> classes) throws IOException {
        Path jar = tempDir.resolve("synthetic-" + System.nanoTime() + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return jar;
    }

}
