package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.ShellIndex;
import lib.minecraft.renderer.bake.armor.WornBox;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.ArmorOptions;
import lib.minecraft.renderer.call.request.ArmorPiece;
import lib.minecraft.renderer.call.request.ArmorTrim;
import lib.minecraft.renderer.call.request.BannerLayer;
import lib.minecraft.renderer.call.request.DecorationOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.call.request.FluidOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.PortalOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.index.CitResult;
import lib.minecraft.renderer.content.index.GlintPolicy;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.FaceTextures;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.fixture.PackFixtures;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import lib.minecraft.renderer.support.RecordingContext;
import lib.minecraft.renderer.vanilla.BannerPattern;
import lib.minecraft.renderer.vanilla.DyeColor;
import lib.minecraft.renderer.vanilla.FluidTextures;
import lib.minecraft.renderer.vanilla.PortalPalette;
import lib.minecraft.renderer.vanilla.appearance.Age;
import lib.minecraft.renderer.vanilla.equipment.ArmorMaterial;
import lib.minecraft.renderer.vanilla.equipment.ArmorSlot;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.renderer.vanilla.mesh.HumanoidPart;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of what every texture family draws for a texture it names that no pack supplies, or that a
 * pack ships as a file that cannot be decoded: an entity's base, overlay, carried-block and group-member
 * textures, the equipment and worn-armour layers and a pack rule's armour tile, the elytra wings and a
 * pack rule's wing tile, the cape, the player's skin, a banner pattern, a trim, the glint, a fluid's
 * still face and the portal shader's noise.
 * <p>
 * Each render is exactly the one it draws where the texture IS the checkerboard, and the id is reported
 * once in the words of what was wrong with it. A missing texture is the vanilla stack with the id
 * hidden, since the renderer re-extracts a file deleted from it; an unreadable one is a temporary pack
 * holding a zero-byte copy, layered over the vanilla stack and decoded by the real pack reader.
 * <p>
 * A reader cropping a sheet by texel coordinates - a skin, an armour sheet, a cape, a banner mask - is
 * held further: what it draws for a missing texture is the stand-in already laid across the sheet it
 * declares, and it covers every pixel a fully opaque sheet would.
 * <p>
 * Nothing misses on a vanilla stack, so no parity artifact sees any of this. The reporting sets are
 * static and live as long as the process, so each id is broken in each state by one test alone.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A texture a family names that no pack can supply draws the checkerboard")
class MissingTextureFamilyTest {

    /**
     * A cape no pack ships, worn behind the torso.
     */
    private static final String CAPE = "minecraft:entity/missing_texture_family/cape";

    /**
     * A cape no pack ships, worn on the elytra wings.
     */
    private static final String WINGS_CAPE = "minecraft:entity/missing_texture_family/wings_cape";

    /**
     * A cape no pack ships, whose sheet coverage is measured.
     */
    private static final String SHEET_CAPE = "minecraft:entity/missing_texture_family/sheet_cape";

    /**
     * The worn-armour tile a pack rule names, which no pack ships.
     */
    private static final String ARMOR_TILE = "minecraft:missing_texture_family/armor_tile";

    /**
     * The wing tile a pack rule names, which no pack ships.
     */
    private static final String WING_TILE = "minecraft:missing_texture_family/wing_tile";

    /**
     * The baby texture the temperate pig's row names.
     */
    private static final String PIG_BABY = "minecraft:entity/pig/pig_temperate_baby";

    /**
     * The adult texture the temperate pig's row names.
     */
    private static final String PIG_ADULT = "minecraft:entity/pig/pig_temperate";

    /**
     * The default wide-arm skin a player naming none wears.
     */
    private static final String STEVE = "minecraft:entity/player/wide/steve";

    /**
     * The item trim's grayscale pattern for a chestplate.
     */
    private static final String CHESTPLATE_TRIM = "minecraft:trims/items/chestplate_trim";

    /**
     * The palette key every trim permutes through.
     */
    private static final String TRIM_PALETTE_KEY = "minecraft:trims/color_palettes/trim_palette";

    /**
     * The opaque colour a cape or pack-rule tile is drawn in where one is served.
     */
    private static final int SERVED = 0xFF3366CC;

    /**
     * The canvas every render here is drawn at - large enough to tell two pictures apart.
     */
    private static final OutputOptions OUTPUT = OutputOptions.builder().canvasSize(64).build();

    @TempDir
    static Path brokenRoot;

    /**
     * The vanilla stack every family reads.
     */
    private static RendererContext vanilla;

    /**
     * A pack holding a zero-byte copy of every texture a family here breaks, and nothing else.
     */
    private static PackStack broken;

    /**
     * Stages the zero-byte pack - one empty file under every id a family breaks - and indexes it with
     * the real indexer, so a lookup it serves is decoded by the real pack reader.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void stage() throws IOException {
        vanilla = ClientAssetsExtension.context();

        List<String> ids = Stream.concat(
                families().map(Family::textureId),
                Stream.of(WING_TILE, PIG_BABY, STEVE, TRIM_PALETTE_KEY))
            .toList();
        for (String id : ids) {
            ResourceId parsed = ResourceId.parse(id);
            Path file = brokenRoot.resolve("assets").resolve(parsed.namespace()).resolve("textures")
                .resolve(parsed.name() + ".png");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }

        ResourcePack pack = new ResourcePack(
            PackId.VANILLA, PackFixtures.directory(brokenRoot), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        broken = PackStack.of(Concurrent.newList(pack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(pack))));
    }

    // ------------------------------------------------------------------------------------
    // The families.
    // ------------------------------------------------------------------------------------

    /**
     * Every family, each with the one texture it breaks and a render that reads it.
     *
     * @return the families
     */
    private static @NotNull Stream<Family> families() {
        return Stream.of(
            new Family("entity base", "minecraft:entity/creeper/creeper", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:creeper")), true),
            new Family("entity overlay", "minecraft:entity/spider/spider_eyes", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:spider")), true),
            new Family("carried block", "minecraft:block/red_mushroom", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:mooshroom")), true),
            // Measured for the canvas and never drawn, so only the report shows it was read.
            new Family("group member", "minecraft:entity/camel/camel_husk", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:camel")
                    .fitMode(EntityOptions.FitMode.GROUP_BOUNDS).pixelsPerBlock(16)), false),
            new Family("equipment layer", "minecraft:entity/equipment/pig_saddle/saddle", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:pig")
                    .appearance(AppearanceOptions.builder().equipment(Map.of("saddle", "saddle")).build())), true),
            new Family("worn armour layer", "minecraft:entity/equipment/humanoid/iron", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder().chestplate(ArmorPiece.of(ArmorMaterial.IRON)).build())), true),
            new Family("pack-rule armour tile", ARMOR_TILE, MissingTextureFamilyTest::armorTileServed,
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(ArmorPiece.of(ArmorMaterial.IRON))
                        .items(Map.of(ArmorSlot.CHESTPLATE, ItemContext.ofItem("minecraft:iron_chestplate")))
                        .build())), true),
            new Family("worn trim", "minecraft:trims/entity/humanoid/sentry", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(ArmorPiece.of(ArmorMaterial.IRON, ArmorTrim.Color.GOLD, ArmorTrim.Pattern.SENTRY))
                        .build())), true),
            new Family("armour glint", "minecraft:misc/enchanted_glint_armor", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(new ArmorPiece(ArmorMaterial.IRON, Optional.empty(), Optional.empty(), true))
                        .build())), true),
            new Family("elytra wings", "minecraft:entity/equipment/wings/elytra", UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .appearance(AppearanceOptions.builder().elytra(true).build())), true),
            new Family("cape", CAPE, context -> context.withTexture(CAPE, solid(64, 32)),
                context -> player(context, player()
                    .skin(SkinOptions.builder().cape(TextureOptions.builder().id(CAPE).build()).renderCape(true).build())), true),
            new Family("cape on the wings", WINGS_CAPE, context -> context.withTexture(WINGS_CAPE, solid(64, 32)),
                context -> player(context, player()
                    .skin(SkinOptions.builder()
                        .cape(TextureOptions.builder().id(WINGS_CAPE).build())
                        .renderCape(true)
                        .renderElytra(true)
                        .build())), true),
            new Family("player skin", "minecraft:entity/player/wide/zuri", UnaryOperator.identity(),
                context -> player(context, player()
                    .type(PlayerOptions.Type.SKULL)
                    .skin(SkinOptions.builder()
                        .skin(TextureOptions.builder().id("minecraft:entity/player/wide/zuri").build())
                        .build())), true),
            // The held shield is where an item draws its pattern stack through the banner composite.
            new Family("banner pattern", "minecraft:entity/shield/creeper", UnaryOperator.identity(),
                context -> item(context, item("minecraft:shield")
                    .type(ItemOptions.Type.HELD_3D)
                    .decoration(DecorationOptions.builder()
                        .bannerLayers(Concurrent.newList(new BannerLayer(
                            context.findBannerPattern("minecraft:creeper").orElseThrow(), DyeColor.Vanilla.RED)))
                        .build())), true),
            new Family("item trim", CHESTPLATE_TRIM, UnaryOperator.identity(),
                context -> item(context, trimmedChestplate()), true),
            new Family("item glint", "minecraft:misc/enchanted_glint_item", UnaryOperator.identity(),
                context -> item(context, item("minecraft:diamond_sword")
                    .enchanted(true).animateGlint(false)), true),
            // A full fluid cube draws its still texture on the top the pose shows.
            new Family("fluid still", FluidTextures.WATER_STILL_TEXTURE_ID, UnaryOperator.identity(),
                context -> new FluidRenderer(context).render(FluidOptions.builder()
                    .fluid(FluidOptions.Fluid.WATER)
                    .output(OUTPUT)
                    .build()), true),
            // The portal shader samples its noise texture across every face it bakes.
            new Family("portal noise", PortalPalette.END_PORTAL_NOISE_TEXTURE_ID, UnaryOperator.identity(),
                context -> new PortalRenderer(context).render(PortalOptions.builder()
                    .portal(PortalOptions.Portal.END_PORTAL)
                    .output(OUTPUT)
                    .build()), true)
        );
    }

    @TestFactory
    @DisplayName("each family's texture is one the vanilla stack serves and the zero-byte pack answers empty")
    @NotNull Stream<DynamicTest> eachFamilyBreaksAServedTexture() {
        return perFamily(family -> {
            RendererContext intact = family.intact().apply(vanilla);

            assertThat(family + " is served", intact.resolveTexture(family.textureId()).isPresent(), is(true));
            assertThat(family + " decodes to nothing from the zero-byte pack",
                broken.pixels(ResourceId.parse(family.textureId())).getState(), is(Possible.State.EMPTY));
        });
    }

    @TestFactory
    @DisplayName("a texture no pack supplies draws the checkerboard, reported once as missing")
    @NotNull Stream<DynamicTest> aMissingTextureDrawsTheCheckerboard() {
        return perFamily(family -> assertDrawsTheCheckerboard(family, intact -> intact.hiding(family.textureId()),
            "Missing texture '" + family.textureId() + "' - drawing the checkerboard"));
    }

    @TestFactory
    @DisplayName("a zero-byte texture draws the checkerboard, reported once as unreadable")
    @NotNull Stream<DynamicTest> anUnreadableTextureDrawsTheCheckerboard() {
        return perFamily(family -> assertDrawsTheCheckerboard(family, intact -> unreadable(intact, family.textureId()),
            "Unreadable texture '" + family.textureId() + "' - drawing the checkerboard"));
    }

    @Test
    @DisplayName("a baby whose named baby texture is missing or unreadable draws the checkerboard, never the adult texture")
    void aMissingBabyTextureIsNotTheAdultTexture() {
        Render baby = context -> entity(context, entity("minecraft:pig")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build()));
        PixelBuffer adult = vanilla.resolveTexture(PIG_ADULT).orElseThrow();
        List<Object> checkerboard = picture(baby.draw(vanilla.withTexture(PIG_BABY, MissingSprite.sprite())));

        List<Object> drawn = picture(baby.draw(vanilla.hiding(PIG_BABY)));

        assertThat(drawn, is(checkerboard));
        assertThat("the adult texture does not stand in for the baby's",
            drawn, is(not(picture(baby.draw(vanilla.withTexture(PIG_BABY, adult))))));
        assertThat("an unreadable baby texture draws it too",
            picture(baby.draw(unreadable(vanilla, PIG_BABY))), is(checkerboard));
    }

    @Test
    @DisplayName("a row that names no texture draws the empty frame")
    void aRowNamingNoTextureDrawsTheEmptyFrame() {
        Entity creeper = vanilla.findEntity("minecraft:creeper").orElseThrow();
        Entity untextured = new Entity(creeper.id(), creeper.model(), creeper.overlays(), creeper.blockOverlays(),
            creeper.baseTintArgb(), creeper.rendererScale(),
            new Entity.Axes(creeper.axes().baby(), creeper.axes().shape(), Entity.Variation.none(),
                creeper.axes().size(), creeper.axes().variant()),
            creeper.layers(), creeper.members(), creeper.styles(), creeper.pose());
        assertThat("the row names no texture", untextured.textureRef().isEmpty(), is(true));
        RendererContext context = vanilla.withEntities(Map.of("custom:untextured", untextured));

        ImageData image = new EntityRenderer(context).render(entity("custom:untextured").build()).image();

        assertThat("one frame", image.getFrames().size(), is(1));
        ImageFrame frame = image.getFrames().getFirst();
        assertThat("one pixel wide", frame.pixels().width(), is(1));
        assertThat("one pixel high", frame.pixels().height(), is(1));
        assertThat("and clear", RenderDigest.firstFramePixels(image), is(new int[] { 0 }));
    }

    @Test
    @DisplayName("the default skin a player naming none wears draws the checkerboard where no pack supplies it or it cannot be read")
    void theDefaultSkinDrawsTheCheckerboard() {
        Render plain = context -> player(context, player().type(PlayerOptions.Type.SKULL));
        List<Object> checkerboard = picture(plain.draw(vanilla.withTexture(STEVE, MissingSprite.sprite())));

        assertThat("missing", picture(plain.draw(vanilla.hiding(STEVE))), is(checkerboard));
        assertThat("unreadable", picture(plain.draw(unreadable(vanilla, STEVE))), is(checkerboard));
    }

    @Test
    @DisplayName("a trim whose palette key is missing draws the same whole checkerboard as one whose pattern is")
    void aTrimMissingAnyInputIsTheCheckerboardAsAWhole() {
        Render trimmed = context -> item(context, trimmedChestplate());

        assertThat(picture(trimmed.draw(vanilla.hiding(TRIM_PALETTE_KEY))),
            is(picture(trimmed.draw(vanilla.withTexture(CHESTPLATE_TRIM, MissingSprite.sprite())))));
        assertThat("the overlay is the stand-in itself rather than a permutation of it",
            TrimKit.permuteFrom(vanilla.hiding(TRIM_PALETTE_KEY).withMissingTexture(), CHESTPLATE_TRIM, "gold")
                .orElseThrow(), is(sameInstance(MissingSprite.sprite())));
    }

    @Test
    @DisplayName("a pack rule's wing tile no pack supplies is the checkerboard rather than the equipment wing, whatever context the kit is handed")
    void aMissingWingTileIsTheCheckerboard() {
        CitResult tile = new CitResult(Possible.of(ResourceId.parse(WING_TILE)), Concurrent.newMap(),
            Possible.empty(), GlintPolicy.DEFAULT);
        Optional<ItemContext> elytra = Optional.of(ItemContext.ofItem("minecraft:elytra"));
        RendererContext missing = RecordingContext.over(vanilla).answeringArmorOverride(tile);
        RendererContext unreadable = RecordingContext.over(unreadable(vanilla, WING_TILE)).answeringArmorOverride(tile);

        String reported = errDuring(() -> assertThat(
            ElytraKit.wingsTexture(missing.withMissingTexture(), elytra, 0).orElseThrow(),
            is(sameInstance(MissingSprite.sprite()))));
        assertThat(occurrences(reported, "Missing texture '" + WING_TILE + "' - drawing the checkerboard"), is(1));
        assertThat(ElytraKit.wingsTexture(unreadable.withMissingTexture(), elytra, 0).orElseThrow(),
            is(sameInstance(MissingSprite.sprite())));

        // The kit reads through the wrapper itself, so a context read bare draws the stand-in too.
        assertThat("missing, read bare", ElytraKit.wingsTexture(missing, elytra, 0).orElseThrow(),
            is(sameInstance(MissingSprite.sprite())));
        assertThat("unreadable, read bare", ElytraKit.wingsTexture(unreadable, elytra, 0).orElseThrow(),
            is(sameInstance(MissingSprite.sprite())));
    }

    // ------------------------------------------------------------------------------------
    // A reader cropping a sheet by texel coordinates sees the stand-in across the whole sheet.
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a missing skin covers every part and overlay it reads, as the stand-in across the skin sheet")
    void aMissingSkinCoversItsSheet() {
        String skinId = "minecraft:entity/player/wide/kai";
        assertThat("the skin is served", vanilla.resolveTexture(skinId).isPresent(), is(true));

        for (PlayerOptions.Dimension dimension : PlayerOptions.Dimension.values()) {
            assertCoversItsSheet(dimension + " skin", context -> player(context, player()
                .type(PlayerOptions.Type.FULL)
                .dimension(dimension)
                .skin(SkinOptions.builder().skin(TextureOptions.builder().id(skinId).build()).build())), skinId, 64, 64);
        }
    }

    @Test
    @DisplayName("a missing armour layer covers every box it dresses, as the stand-in across the sheet the wearer declares")
    void aMissingArmourLayerCoversItsSheet() {
        String sheet = "minecraft:entity/equipment/humanoid/gold";
        assertThat("the sheet is served", vanilla.resolveTexture(sheet).isPresent(), is(true));
        ArmorOptions armour = ArmorOptions.builder()
            .helmet(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .chestplate(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .boots(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .build();

        assertCoversItsSheet("adult zombie", context -> entity(context, entity("minecraft:zombie")
            .armor(armour)), sheet, 64, 32);
        assertCoversItsSheet("baby zombie", context -> entity(context, entity("minecraft:zombie")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .armor(armour)), sheet, 64, 64);
        for (PlayerOptions.Dimension dimension : PlayerOptions.Dimension.values()) {
            assertCoversItsSheet(dimension + " player", context -> player(context, player()
                .type(PlayerOptions.Type.FULL)
                .dimension(dimension)
                .armor(armour)), sheet, 64, 32);
        }
    }

    @Test
    @DisplayName("a missing cape covers every face of the cape, as the stand-in across the cape sheet")
    void aMissingCapeCoversItsSheet() {
        assertCoversItsSheet("cape", context -> player(context, player()
            .skin(SkinOptions.builder().cape(TextureOptions.builder().id(SHEET_CAPE).build()).renderCape(true).build())),
            SHEET_CAPE, 64, 32);
    }

    @Test
    @DisplayName("every face a worn shell or the player's body crops from the stand-in is covered, adult and baby shells alike")
    void everyCroppedArmourFaceIsCovered() {
        Entity zombie = vanilla.findEntity("minecraft:zombie").orElseThrow();
        List<Shell> shells = List.of(zombie.humanoidArmor().orElseThrow(),
            AppearanceOptions.builder().age(Age.BABY).build().resolve(zombie).humanoidArmor().orElseThrow());
        List<WornBox> rows = new ArrayList<>();
        for (Shell shell : shells)
            rows.addAll(ShellIndex.of(shell).parts());
        for (HumanoidPart part : HumanoidPart.values())
            rows.add(new WornBox.Body(part, false, Concurrent.newSet(), part.box(1f)));
        rows.add(new WornBox.Body(HumanoidPart.HEAD, true, Concurrent.newSet(), HumanoidPart.HEAD.box(1f)));

        for (WornBox row : rows) {
            FaceTextures faces = row.textures(MissingSprite.sprite());
            for (Face face : Face.values())
                for (int argb : RenderDigest.pixels(faces.byFace(face)))
                    assertThat(row.trace() + " " + face + " is covered", argb >>> 24, is(0xFF));
        }
    }

    @Test
    @DisplayName("a missing banner mask covers the whole sheet, as the stand-in across it, tinted by the layer's dye")
    void aMissingBannerMaskCoversItsSheet() {
        BannerPattern creeper = vanilla.findBannerPattern("minecraft:creeper").orElseThrow();
        String mask = BannerKit.Variant.BANNER_BLOCK_3D.textureFor(creeper.assetId());
        assertThat("the mask is served", vanilla.resolveTexture(mask).isPresent(), is(true));
        ConcurrentList<BannerLayer> layers = Concurrent.newList(new BannerLayer(creeper, DyeColor.Vanilla.RED));
        int field = DyeColor.Vanilla.WHITE.argb();

        PixelBuffer drawn = BannerKit.composite2D(
            vanilla.hiding(mask).withMissingTexture(), field, layers, BannerKit.Variant.BANNER_BLOCK_3D);
        PixelBuffer reference = BannerKit.composite2D(
            vanilla.withTexture(mask, MissingSprite.stretchedTo(MissingSprite.sprite(), drawn.width(), drawn.height())),
            field, layers, BannerKit.Variant.BANNER_BLOCK_3D);

        assertThat("the sheet is the banner's own", List.of(drawn.width(), drawn.height()), is(List.of(64, 64)));
        assertThat(RenderDigest.pixels(drawn), is(RenderDigest.pixels(reference)));
        for (int argb : RenderDigest.pixels(drawn))
            assertThat("no texel is left bare to the field", argb, is(not(field)));
    }

    // ------------------------------------------------------------------------------------

    /**
     * One dynamic test per family, named for it.
     *
     * @param body what each family is held to
     * @return the tests
     */
    private static @NotNull Stream<DynamicTest> perFamily(@NotNull Consumer<Family> body) {
        return families().map(family -> DynamicTest.dynamicTest(family.name(), () -> body.accept(family)));
    }

    /**
     * Asserts a family draws exactly the render it draws where its texture is the checkerboard; that the
     * picture is not its intact one, for a family that draws the texture; and that the render reports the
     * id once in the expected words.
     *
     * @param family the family under test
     * @param breaking how the intact context is broken
     * @param report the report the break should make, once
     */
    private static void assertDrawsTheCheckerboard(
        @NotNull Family family, @NotNull UnaryOperator<RendererContext> breaking, @NotNull String report) {
        RendererContext intact = family.intact().apply(vanilla);
        List<Object> reference = picture(family.render().draw(intact.withTexture(family.textureId(), MissingSprite.sprite())));
        AtomicReference<RenderResult> drawn = new AtomicReference<>();

        String reported = errDuring(() -> drawn.set(family.render().draw(breaking.apply(intact))));

        assertThat(family + " draws what the checkerboard draws", picture(drawn.get()), is(reference));
        if (family.drawn())
            assertThat(family + " draws the texture it names", reference, is(not(picture(family.render().draw(intact)))));
        assertThat(family + " reports once", occurrences(reported, report), is(1));
    }

    /**
     * Asserts a render reading one texture by texel crops draws, where that texture is missing, exactly
     * what it draws where the texture is the stand-in already stretched across the sheet the reader
     * declares, and covers every pixel a fully opaque sheet of that size covers.
     *
     * @param label what is rendered, for the failure message
     * @param render the render reading the texture
     * @param textureId the texture made missing
     * @param width the width of the sheet the reader declares
     * @param height the height of the sheet the reader declares
     */
    private static void assertCoversItsSheet(
        @NotNull String label, @NotNull Render render, @NotNull String textureId, int width, int height) {
        RenderResult drawn = render.draw(vanilla.hiding(textureId));
        PixelBuffer stretched = MissingSprite.stretchedTo(MissingSprite.sprite(), width, height);

        assertThat(label + " draws the stand-in across its sheet", picture(drawn),
            is(picture(render.draw(vanilla.withTexture(textureId, stretched)))));
        assertThat(label + " leaves no transparent remainder", coverage(drawn),
            is(coverage(render.draw(vanilla.withTexture(textureId, solid(width, height))))));
    }

    /**
     * Spells a render's first frame as its size and which of its pixels hold any alpha.
     *
     * @param rendered the render
     * @return the first frame's width, height and covered pixels
     */
    private static @NotNull List<Object> coverage(@NotNull RenderResult rendered) {
        ImageData image = rendered.image();
        ImageFrame first = image.getFrames().getFirst();
        int[] pixels = RenderDigest.firstFramePixels(image);
        BitSet covered = new BitSet(pixels.length);
        for (int i = 0; i < pixels.length; i++)
            if (pixels[i] >>> 24 != 0) covered.set(i);

        return List.of(first.pixels().width(), first.pixels().height(), covered);
    }

    /**
     * The given context with one texture read from the zero-byte pack instead: that id answers empty,
     * and every other id the context beneath answers.
     *
     * @param context the context beneath
     * @param textureId the texture whose zero-byte copy is read
     * @return the context reading that texture's zero-byte copy
     */
    private static @NotNull RendererContext unreadable(@NotNull RendererContext context, @NotNull String textureId) {
        ResourceId broke = ResourceId.parse(textureId);
        return context.withTextures(id -> ResourceId.parse(id).equals(broke) ? broken.pixels(broke) : Possible.absent());
    }

    /**
     * The vanilla stack with a pack rule naming {@link #ARMOR_TILE} for every worn-armour layer, and that
     * tile served.
     *
     * @param context the vanilla stack
     * @return the context serving the rule and its tile
     */
    private static @NotNull RendererContext armorTileServed(@NotNull RendererContext context) {
        CitResult tile = new CitResult(Possible.of(ResourceId.parse(ARMOR_TILE)), Concurrent.newMap(),
            Possible.empty(), GlintPolicy.DEFAULT);
        return RecordingContext.over(context.withTexture(ARMOR_TILE, solid(64, 32))).answeringArmorOverride(tile);
    }

    /**
     * A trimmed iron chestplate's icon options.
     *
     * @return the options
     */
    private static @NotNull ItemOptions.Builder trimmedChestplate() {
        return item("minecraft:iron_chestplate")
            .decoration(DecorationOptions.builder()
                .trimSlot(ArmorSlot.CHESTPLATE)
                .trimColor(ArmorTrim.Color.GOLD)
                .build());
    }

    private static @NotNull EntityOptions.Builder entity(@NotNull String id) {
        return EntityOptions.builder().entityId(id).output(OUTPUT);
    }

    private static @NotNull RenderResult entity(@NotNull RendererContext context, @NotNull EntityOptions.Builder options) {
        return new EntityRenderer(context).render(options.build());
    }

    private static @NotNull PlayerOptions.Builder player() {
        return PlayerOptions.builder()
            .type(PlayerOptions.Type.BUST)
            .dimension(PlayerOptions.Dimension.THREE_D)
            .output(OUTPUT);
    }

    private static @NotNull RenderResult player(@NotNull RendererContext context, @NotNull PlayerOptions.Builder options) {
        return new PlayerRenderer(context).render(options.build());
    }

    private static @NotNull ItemOptions.Builder item(@NotNull String id) {
        return ItemOptions.builder().itemId(id);
    }

    private static @NotNull RenderResult item(@NotNull RendererContext context, @NotNull ItemOptions.Builder options) {
        return new ItemRenderer(context).render(options.build());
    }

    /**
     * An opaque buffer of the served colour.
     *
     * @param width the buffer width
     * @param height the buffer height
     * @return the buffer
     */
    private static @NotNull PixelBuffer solid(int width, int height) {
        PixelBuffer buffer = PixelBuffer.create(width, height);
        buffer.fill(SERVED);
        return buffer;
    }

    /**
     * Spells a render as its frame count, its first frame's size and every frame's pixels, so two renders
     * compare by all three.
     *
     * @param rendered the render
     * @return the render's picture
     */
    private static @NotNull List<Object> picture(@NotNull RenderResult rendered) {
        ImageData image = rendered.image();
        ImageFrame first = image.getFrames().getFirst();
        return List.of(image.getFrames().size(), first.pixels().width(), first.pixels().height(),
            RenderDigest.frameCrcs(image));
    }

    /**
     * Counts the times a line appears in captured output.
     *
     * @param output the captured output
     * @param line the line looked for
     * @return how many times it appears
     */
    private static int occurrences(@NotNull String output, @NotNull String line) {
        int count = 0;
        for (int at = output.indexOf(line); at >= 0; at = output.indexOf(line, at + line.length()))
            count++;
        return count;
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static @NotNull String errDuring(@NotNull Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * Draws one family's subject.
     */
    @FunctionalInterface
    private interface Render {

        /**
         * Draws the subject through a context.
         *
         * @param context the context the render reads
         * @return the render
         */
        @NotNull RenderResult draw(@NotNull RendererContext context);

    }

    /**
     * One texture family: the texture it breaks, what serves it intact, and the render reading it.
     *
     * @param name the family's name, which the parameterised display reads
     * @param textureId the texture the family breaks
     * @param intact what the vanilla stack is wrapped in for the texture to be served
     * @param render the render reading the texture
     * @param drawn whether the render draws the texture, rather than only measuring it
     */
    private record Family(
        @NotNull String name,
        @NotNull String textureId,
        @NotNull UnaryOperator<RendererContext> intact,
        @NotNull Render render,
        boolean drawn
    ) {

        @Override
        public @NotNull String toString() {
            return this.name;
        }

    }

}
