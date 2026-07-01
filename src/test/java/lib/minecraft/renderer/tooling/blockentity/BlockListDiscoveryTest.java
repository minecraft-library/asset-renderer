package lib.minecraft.renderer.tooling.blockentity;

import lib.minecraft.renderer.tooling.util.Diagnostics;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Fast mutation tests for {@link BlockListDiscovery}. Each nested test class hand-assembles a
 * synthetic client jar - one or more classes whose {@code <clinit>} (or lambda) bytecode is emitted
 * via {@link ClassWriter} to reproduce the exact shape a real walker anchors on - then feeds it
 * through one shared primitive walker or per-family adapter and asserts the extracted table.
 *
 * <p>The mutation discipline mirrors {@link SourceDiscoveryTest}: every walker is pinned by a
 * baseline case (build the canonical shape, assert the expected output) plus at least one mutation
 * case (perturb an LDC or reorder the GETSTATICs, assert the output tracks the change). A hardcoded
 * lookup table would pass the baseline but fail the mutation - so these prove the output is
 * genuinely bytecode-derived.
 *
 * <p>Nested classes group by target: the {@code Shared primitive} block covers the low-level
 * {@code validBlocks} / {@code walkXxx} extractors that every family reuses; the
 * {@code Per-family adapter} block covers whole {@link BlockListDiscovery#discover} families
 * (bell, decorated pot). The {@code Shared bytecode builders} at the bottom synthesize the skeleton
 * classes; each test rebuilds only the bytecode it needs.
 */
@DisplayName("BlockListDiscovery (bytecode-driven)")
class BlockListDiscoveryTest {

    /** Per-test scratch directory into which {@link #writeJar} materializes each synthetic jar. */
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

        /**
         * Pins the PLAYER follow chain: a {@code getDefaultTexture} call (no LDC) forces the walker
         * to resolve the index from {@code getDefaultSkin}'s {@code aaload}, then read the
         * {@code DEFAULT_SKINS} entry at that index out of the {@code <clinit>} - here index 2.
         */
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

        /**
         * Pins that a jar with a valid-but-empty {@code BlockEntityType.<clinit>} yields a mapping
         * per family whose {@code blocks} list is empty - no family fabricates a block binding when
         * its BE type has no valid blocks.
         */
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
        @DisplayName("binds the additive bell_body to the single minecraft:bell block")
        void singleBellBinding() throws IOException {
            byte[] beType = buildBlockEntityTypeClass(b -> {
                b.ldc("bell");
                b.getstaticBlocks("BELL");
                b.putstaticBeType("BELL");
            });
            byte[] bellRenderer = buildBellRenderer("bell/bell_body");
            Path jar = writeJar(Map.of(
                "net/minecraft/world/level/block/entity/BlockEntityType", beType,
                "net/minecraft/world/level/block/Blocks", emptyClass("net/minecraft/world/level/block/Blocks"),
                "net/minecraft/client/renderer/blockentity/BellRenderer", bellRenderer
            ));
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Map<String, BlockListDiscovery.EntityBlockMapping> out = BlockListDiscovery.discover(zf, new Diagnostics());
                BlockListDiscovery.EntityBlockMapping bell = out.get("minecraft:bell_body");
                assertThat(bell, notNullValue());
                assertThat(bell.blocks(), hasSize(1));
                // BellRenderer.submit draws the bell_body with the raw PoseStack (no per-attachment
                // offset), so the additive overlay binds to the single minecraft:bell block.
                BlockListDiscovery.BlockMapping mapping = bell.blocks().getFirst();
                assertThat(mapping.blockId(), equalTo("minecraft:bell"));
                // Texture is prepended with entity/ by the mapper base.
                assertThat(mapping.textureId(), equalTo("minecraft:entity/bell/bell_body"));
            }
        }
    }

    @Nested
    @DisplayName("Decorated pot adapter")
    class DecoratedPotTests {

        /**
         * Pins the pot / sides split: {@code minecraft:decorated_pot} carries the base texture plus a
         * single {@code decorated_pot_sides} {@link BlockListDiscovery.PartRef} at offset
         * {@code (0,0,0)} with the side texture, while the {@code decorated_pot_sides} entity id is a
         * blocks-empty, parts-null sub-model referenced only through that part.
         */
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
     * One standard-Java-enum {@code <clinit>} constant fixture (two LDCs: NAME then serialized name).
     *
     * @param name the enum constant field name (e.g. {@code "WHITE"}), emitted as the first LDC and
     *     the closing PUTSTATIC field
     * @param serializedName the second LDC that the walker binds as the map value
     */
    private record EnumEntry(String name, String serializedName) {}

    /**
     * One {@code ChestSpecialRenderer.<clinit>} variant fixture.
     *
     * @param field the chest-variant field name (e.g. {@code "REGULAR"})
     * @param ldc the string LDC bound to the variant
     * @param shape either {@code "createDefaultTextures"} (MultiblockChestResources) or
     *     {@code "withDefaultNamespace"} (single Identifier, as ENDER_CHEST uses)
     */
    private record ChestVariant(String field, String ldc, String shape) {}

    /**
     * One {@code CopperGolemOxidationLevels.<clinit>} entry fixture.
     *
     * @param field the weather-state field name (e.g. {@code "UNAFFECTED"})
     * @param fullTexturePath the raw {@code textures/....png} body-texture LDC, before the walker
     *     strips its {@code textures/} prefix and {@code .png} suffix
     */
    private record OxidationEntry(String field, String fullTexturePath) {}

    /**
     * One {@code SkullBlockRenderer.lambda$static$0} skin-map entry fixture.
     *
     * @param type the {@code SkullBlock$Types} field (e.g. {@code "SKELETON"})
     * @param texture the {@code textures/...} path LDC, or {@code null} for PLAYER when
     *     {@code usePlayerFollow} is true
     * @param usePlayerFollow when true, emit {@code INVOKESTATIC DefaultPlayerSkin.getDefaultTexture}
     *     instead of an LDC - forcing the walker to chase the player-skin follow chain
     */
    private record SkullSkin(String type, String texture, boolean usePlayerFollow) {}

    /**
     * Fluent emitter for {@code BlockEntityType.<clinit>} instructions. The lambda passed to
     * {@link #buildBlockEntityTypeClass} receives one of these and calls
     * {@code ldc}/{@code getstaticBlocks}/{@code putstaticBeType} to reproduce the
     * {@code LDC id ... GETSTATIC Blocks.X ... PUTSTATIC BlockEntityType.Y} shape the
     * {@link BlockListDiscovery#validBlocks} walker anchors on.
     */
    private interface BlockEntityTypeClinitBuilder {
        /**
         * Emits an {@code LDC id} - the anchor that starts a BE type's init block.
         *
         * @param id the block-entity string id (e.g. {@code "chest"})
         */
        void ldc(String id);

        /**
         * Emits a {@code GETSTATIC Blocks.field} - one entry of the current BE type's valid-blocks list.
         *
         * @param field the {@code Blocks} field name (e.g. {@code "CHEST"})
         */
        void getstaticBlocks(String field);

        /**
         * Emits a {@code PUTSTATIC BlockEntityType.field} - the anchor that closes the init block and
         * binds the preceding GETSTATICs to that BE type field.
         *
         * @param field the {@code BlockEntityType} field name (e.g. {@code "CHEST"})
         */
        void putstaticBeType(String field);
    }

    /**
     * Builds a synthetic {@code BlockEntityType} class whose {@code <clinit>} contains exactly the
     * instructions the {@code body} lambda emits via {@link BlockEntityTypeClinitBuilder}. Each
     * distinct {@code putstaticBeType} field is also declared on the class so the emitted PUTSTATICs
     * verify. The instruction stream is only walked, never executed, so pushed values are POP'd off
     * immediately to keep the stack balanced.
     *
     * @param body callback that emits the {@code <clinit>} body
     * @return the class bytes
     */
    private static byte[] buildBlockEntityTypeClass(@NotNull Consumer<BlockEntityTypeClinitBuilder> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/level/block/entity/BlockEntityType", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Set<String> declaredFields = new LinkedHashSet<>();
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

    /**
     * Builds a synthetic {@code ChestSpecialRenderer} whose {@code <clinit>} emits, per variant, an
     * {@code LDC ldc} followed by either {@code INVOKESTATIC createDefaultTextures} (the
     * MultiblockChestResources shape) or {@code INVOKESTATIC Identifier.withDefaultNamespace} (the
     * single-Identifier shape), then {@code PUTSTATIC field}. Exercises both texture-base shapes the
     * {@link BlockListDiscovery#walkChestSpecialRendererVariants} walker must recognise.
     *
     * @param variants the variants to emit, in order
     * @return the class bytes
     */
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

    /**
     * Builds a synthetic {@code CopperGolemOxidationLevels} whose {@code <clinit>} emits, per entry,
     * {@code NEW CopperGolemOxidationLevel; LDC fullTexturePath; PUTSTATIC field}. The walker
     * captures the first {@code textures/....png} LDC after each NEW as the body texture, so the
     * fixture's single LDC per entry is what surfaces in the map (stripped of prefix/suffix).
     *
     * @param entries the oxidation-level entries to emit, in order
     * @return the class bytes
     */
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

    /**
     * Builds a synthetic {@code SkullBlockRenderer} carrying the {@code lambda$static$0(HashMap)}
     * skin-map populator. Per skin it emits {@code ALOAD 0; GETSTATIC SkullBlock$Types.type}, then
     * either {@code LDC texture; INVOKESTATIC Identifier.withDefaultNamespace} or (when
     * {@code usePlayerFollow}) {@code INVOKESTATIC DefaultPlayerSkin.getDefaultTexture}, closed by
     * {@code INVOKEVIRTUAL HashMap.put; POP} - the exact shape {@link BlockListDiscovery#walkSkullSkinMap}
     * anchors on.
     *
     * @param skins the skin entries to emit, in order
     * @return the class bytes
     */
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

    /**
     * Builds a synthetic {@code BellRenderer} whose {@code <clinit>} emits
     * {@code LDC ldcString; PUTSTATIC BELL_TEXTURE} - the single texture LDC the bell adapter's
     * {@code resolveBellTexture} reads to texture the {@code bell_body} overlay.
     *
     * @param ldcString the bell-body texture base LDC (e.g. {@code "bell/bell_body"})
     * @return the class bytes
     */
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

    /**
     * Builds a bare class with no {@code <clinit>} - a placeholder so a class the walker resolves by
     * name (e.g. {@code Blocks}, {@code SkullBlock$Types}) exists in the jar without contributing any
     * instructions.
     *
     * @param internalName the class's JVM internal name
     * @return the class bytes
     */
    private static byte[] emptyClass(@NotNull String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a class with an empty (return-only) {@code <clinit>} - exercises the walker's handling
     * of a BE type whose init block contains no valid-blocks anchors.
     *
     * @param internalName the class's JVM internal name
     * @return the class bytes
     */
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

    /**
     * Writes the supplied {@code (internalName -> classBytes)} entries into a fresh jar under
     * {@link #tempDir}, appending {@code .class} to each internal name. The nanosecond-suffixed file
     * name keeps concurrent / repeated calls from colliding within a single test class instance.
     *
     * @param classes the classes to bundle, keyed by JVM internal name
     * @return the path to the written jar
     * @throws IOException if writing the jar fails
     */
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
