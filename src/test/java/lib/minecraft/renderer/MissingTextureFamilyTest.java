package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.equipment.Shell;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.bake.armor.ElytraKit;
import lib.minecraft.renderer.bake.armor.ShellIndex;
import lib.minecraft.renderer.bake.armor.WornBox;
import lib.minecraft.renderer.bake.texture.BannerKit;
import lib.minecraft.renderer.bake.texture.TrimKit;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.ArmorOptions;
import lib.minecraft.renderer.call.request.ArmorPiece;
import lib.minecraft.renderer.call.request.ArmorTrim;
import lib.minecraft.renderer.call.request.BannerLayer;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.request.DecorationOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.call.request.FluidOptions;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.PortalOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
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
import lib.minecraft.renderer.support.MinecraftFontsExtension;
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
import lib.minecraft.text.TextSegment;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Coverage of what every texture family draws for a texture it names that no pack supplies, or that a
 * pack ships as a file that cannot be decoded: an entity's base, overlay, carried-block and group-member
 * textures and a texture named in its place, the equipment and worn-armour layers and a pack rule's
 * armour tile, the elytra wings, a pack rule's wing tile and a wing source named by id, the cape, the
 * player's skin and the default one, a banner pattern, a trim, the glint, a fluid's still face, the
 * portal shader's noise and end sky, a skull's and the copper golem statue's texture, a menu's container
 * sheet and the book's page texture.
 * <p>
 * Each family names the read the client makes of its texture: an atlas sprite, which the sprite loader
 * refuses for an animation section the client's codec refuses; a plain texture, which the client's
 * texture manager loads by its file with its sidecar's texture section alone, so such a section costs it
 * nothing; or a paletted permutation's input, which the client reads with no sidecar. After its zero-byte
 * break, each sprite and plain family is drawn once more with its texture beside a refused animation -
 * the whole file on a plain read, the checkerboard on a sprite read - and each whose drawing can tell a
 * whole strip from its first frame is drawn with a valid one: its first frame at rest where its read site
 * plays a table, as an atlas sprite and the plain reads that sample a table do, and the whole strip where
 * it never did. A plain read that plays a table is drawn over two frames a tick apart as well, its second
 * frame the strip's second, so a site that stops stepping its tick shows. A staged texture is the
 * family's own at rest, so a family that draws it is held to its intact picture.
 * <p>
 * Each render is exactly the one it draws where the texture IS the checkerboard. A render that draws the
 * texture prints the id once in the words of what was wrong with it, and names it in its result as the
 * one stand-in drawn - absent where no pack supplies it, empty where its file cannot be decoded - while
 * one that only measures it reads it, prints nothing and names none. A missing texture is the vanilla
 * stack with the id hidden, since the renderer re-extracts a file deleted from it; an unreadable one is a
 * temporary pack holding a zero-byte copy, layered over the vanilla stack and decoded by the real pack
 * reader.
 * <p>
 * A reader cropping a sheet by texel coordinates - a skin, an armour sheet, a cape, a banner mask - is
 * held further: what it draws for a missing texture is the stand-in already laid across the sheet it
 * declares, and it covers every pixel a fully opaque sheet would.
 * <p>
 * Nothing misses on a vanilla stack, so no parity artifact sees any of this. The reporting sets are
 * static and live as long as the process, so each id is broken in each state by one test alone.
 */
@ExtendWith(ClientAssetsExtension.class)
@ExtendWith(MinecraftFontsExtension.class)
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
     * An elytra wing source no pack ships, named through the skin options.
     */
    private static final String ELYTRA_SOURCE = "minecraft:entity/missing_texture_family/elytra_source";

    /**
     * The book's page texture.
     */
    private static final String BOOK = "minecraft:gui/book";

    /**
     * The container sheet the hopper's window is composed from, which no other test class breaks.
     */
    private static final String HOPPER_SHEET = "minecraft:gui/container/hopper";

    /**
     * The opaque colour a cape or pack-rule tile is drawn in where one is served.
     */
    private static final int SERVED = 0xFF3366CC;

    /**
     * The colour every two-frame strip the valid-animation stack ships gives its lower frame wherever its
     * upper frame is opaque, the rest copied, so the two frames cover the same texels and a canvas fitted
     * to what a render draws is one size over either.
     */
    private static final int LOWER_FRAME = 0xFF11AA22;

    /**
     * Two frames one tick apart.
     */
    private static final AnimationOptions TWO_TICKS = AnimationOptions.builder().frameCount(2).ticksPerFrame(1).build();

    /**
     * Both states a texture is broken in: missing, and served with no pixels.
     */
    private static final Set<Possible.State> BOTH = Set.of(Possible.State.ABSENT, Possible.State.EMPTY);

    /**
     * The families whose read site samples a playback table over a plain read, so a valid animation
     * draws its first frame at rest and its strip a frame a tick; each family's render takes a timing.
     */
    private static final Set<String> FLIPBOOK_SITES = Set.of(
        "entity base", "entity overlay", "entity texture override", "equipment layer", "elytra wings",
        "skull", "copper golem statue");

    /**
     * The families whose read site samples no playback table and spreads its UVs over the whole texture,
     * so a valid animation's strip draws whole, as the client draws a plain texture.
     */
    private static final Set<String> WHOLE_SITES = Set.of(
        "book page", "container sheet", "armour glint", "item glint", "portal noise", "end sky",
        "cape on the wings", "elytra source");

    /**
     * The canvas every render here is drawn at - large enough to tell two pictures apart.
     */
    private static final OutputOptions OUTPUT = OutputOptions.builder().canvasSize(64).build();

    @TempDir
    static Path brokenRoot;

    @TempDir
    static Path refusedRoot;

    @TempDir
    static Path animatedRoot;

    /**
     * The vanilla stack every family reads.
     */
    private static RendererContext vanilla;

    /**
     * A pack holding a zero-byte copy of every texture a family here breaks, and nothing else.
     */
    private static PackStack broken;

    /**
     * A pack holding every atlas-sprite and plain family's texture, as its intact render draws it at rest,
     * beside an animation section the client's codec refuses.
     */
    private static PackStack refused;

    /**
     * A pack holding every atlas-sprite and plain family's texture, as its intact render draws it at rest,
     * over a copy of it below recoloured where it is opaque, beside a valid animation of those two frames.
     */
    private static PackStack animated;

    /**
     * Each two-frame strip the valid-animation pack ships, decoded, by family texture id.
     */
    private static final Map<String, PixelBuffer> STRIPS = new HashMap<>();

    /**
     * The lower frame of each strip the valid-animation pack ships, by family texture id.
     */
    private static final Map<String, PixelBuffer> LOWER_FRAMES = new HashMap<>();

    /**
     * Stages the zero-byte pack - one empty file under every id a family breaks - and the two packs of
     * sidecars, and indexes each with the real indexer, so a lookup each serves is decoded by the real
     * pack reader. A staged texture is each family's own read of its intact context at rest, the first
     * frame of one that animates, encoded losslessly, which this asserts by decoding it back.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void stage() throws IOException {
        vanilla = ClientAssetsExtension.context();

        List<String> ids = Stream.concat(
                families().map(Family::textureId),
                Stream.of(WING_TILE, PIG_BABY, TRIM_PALETTE_KEY))
            .toList();
        for (String id : ids)
            Files.write(textureFile(brokenRoot, id), new byte[0]);
        broken = indexed(brokenRoot);
        // Each zero-byte file is decoded here on both reads, so the pack reader's own line for it prints
        // once now rather than inside whichever test reads it first.
        errDuring(() -> ids.stream().map(ResourceId::parse).forEach(id -> {
            broken.pixels(id);
            broken.plainPixels(id);
        }));

        for (Family family : families().filter(family -> family.read() != Read.PERMUTATION_INPUT).toList()) {
            RendererContext intact = family.intact().apply(vanilla);
            String id = family.textureId();
            Possible<PixelBuffer> read = family.read() == Read.PLAIN ? intact.resolvePlainTexture(id) : intact.resolveTexture(id);
            PixelBuffer atRest = Flipbook.atTick(read, intact.findFlipbook(id), 0).orElseThrow();
            byte[] still = png(atRest);
            assertThat(family + "'s staged texture decodes back to its intact one",
                RenderDigest.pixels(ImageFactory.fromByteArray(still).toPixelBuffer()), is(RenderDigest.pixels(atRest)));
            Files.write(textureFile(refusedRoot, id), still);
            Files.writeString(sidecarFile(refusedRoot, id), "{\"animation\":{\"frametime\":0}}");

            PixelBuffer lower = PixelBuffer.straight(atRest.width(), atRest.height());
            PixelBuffer strip = PixelBuffer.straight(atRest.width(), atRest.height() * 2);
            for (int y = 0; y < atRest.height(); y++) {
                for (int x = 0; x < atRest.width(); x++) {
                    int argb = atRest.getPixel(x, y);
                    lower.setPixel(x, y, argb >>> 24 == 0xFF ? LOWER_FRAME : argb);
                    strip.setPixel(x, y, argb);
                    strip.setPixel(x, y + atRest.height(), lower.getPixel(x, y));
                }
            }
            byte[] strips = png(strip);
            assertThat(family + "'s staged strip decodes back to the one built",
                RenderDigest.pixels(ImageFactory.fromByteArray(strips).toPixelBuffer()), is(RenderDigest.pixels(strip)));
            Files.write(textureFile(animatedRoot, id), strips);
            Files.writeString(sidecarFile(animatedRoot, id), "{\"animation\":{\"height\":" + atRest.height() + "}}");
            STRIPS.put(id, strip);
            LOWER_FRAMES.put(id, lower);
        }
        refused = indexed(refusedRoot);
        animated = indexed(animatedRoot);
    }

    /**
     * Answers the file a texture id names under a pack root, making its directories.
     *
     * @param root the pack root
     * @param textureId the texture id
     * @return the PNG's path
     * @throws IOException if making a directory fails
     */
    private static @NotNull Path textureFile(@NotNull Path root, @NotNull String textureId) throws IOException {
        ResourceId parsed = ResourceId.parse(textureId);
        Path file = root.resolve("assets").resolve(parsed.namespace()).resolve("textures").resolve(parsed.name() + ".png");
        Files.createDirectories(file.getParent());
        return file;
    }

    /**
     * Answers the sidecar beside the file a texture id names under a pack root.
     *
     * @param root the pack root
     * @param textureId the texture id
     * @return the sidecar's path
     * @throws IOException if making a directory fails
     */
    private static @NotNull Path sidecarFile(@NotNull Path root, @NotNull String textureId) throws IOException {
        Path file = textureFile(root, textureId);
        return file.resolveSibling(file.getFileName() + ".mcmeta");
    }

    /**
     * Indexes a staged pack, taking vanilla's place at the bottom of a stack of its own.
     *
     * @param root the pack root
     * @return the indexed stack
     */
    private static @NotNull PackStack indexed(@NotNull Path root) {
        ResourcePack pack = new ResourcePack(
            PackId.VANILLA, PackFixtures.directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        return PackStack.of(Concurrent.newList(pack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(pack))));
    }

    /**
     * Encodes a buffer as a PNG.
     *
     * @param buffer the pixels
     * @return the PNG bytes
     * @throws IOException if encoding fails
     */
    private static byte @NotNull [] png(@NotNull PixelBuffer buffer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffer.toBufferedImage(), "PNG", out);
        return out.toByteArray();
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
            new Family("entity base", "minecraft:entity/creeper/creeper", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> entity(context, entity("minecraft:creeper").animation(animation)), true),
            new Family("entity overlay", "minecraft:entity/spider/spider_eyes", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> entity(context, entity("minecraft:spider").animation(animation)), true),
            new Family("carried block", "minecraft:block/red_mushroom", Read.SPRITE, BOTH, UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:mooshroom")), true),
            // Measured for the canvas and never drawn: the render reads it, and neither prints it nor
            // names it in the result, since the picture does not hold it.
            new Family("group member", "minecraft:entity/camel/camel_husk", Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:camel")
                    .fitMode(EntityOptions.FitMode.GROUP_BOUNDS).pixelsPerBlock(16)), false),
            new Family("equipment layer", "minecraft:entity/equipment/pig_saddle/saddle", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> entity(context, entity("minecraft:pig")
                    .appearance(AppearanceOptions.builder().equipment(Map.of("saddle", "saddle")).build())
                    .animation(animation)), true),
            new Family("worn armour layer", "minecraft:entity/equipment/humanoid/iron", Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder().chestplate(ArmorPiece.of(ArmorMaterial.IRON)).build())), true),
            new Family("pack-rule armour tile", ARMOR_TILE, Read.PLAIN, BOTH, MissingTextureFamilyTest::armorTileServed,
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(ArmorPiece.of(ArmorMaterial.IRON))
                        .items(Map.of(ArmorSlot.CHESTPLATE, ItemContext.ofItem("minecraft:iron_chestplate")))
                        .build())), true),
            new Family("worn trim", "minecraft:trims/entity/humanoid/sentry", Read.PERMUTATION_INPUT, BOTH, UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(ArmorPiece.of(ArmorMaterial.IRON, ArmorTrim.Color.GOLD, ArmorTrim.Pattern.SENTRY))
                        .build())), true),
            new Family("armour glint", "minecraft:misc/enchanted_glint_armor", Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> entity(context, entity("minecraft:zombie")
                    .armor(ArmorOptions.builder()
                        .chestplate(new ArmorPiece(ArmorMaterial.IRON, Optional.empty(), Optional.empty(), true))
                        .build())), true),
            new Family("elytra wings", "minecraft:entity/equipment/wings/elytra", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> entity(context, entity("minecraft:zombie")
                    .appearance(AppearanceOptions.builder().elytra(true).build())
                    .animation(animation)), true),
            new Family("cape", CAPE, Read.PLAIN, BOTH, context -> context.withTexture(CAPE, solid(64, 32)),
                context -> player(context, player()
                    .skin(SkinOptions.builder().cape(TextureOptions.builder().id(CAPE).build()).renderCape(true).build())), true),
            new Family("cape on the wings", WINGS_CAPE, Read.PLAIN, BOTH, context -> context.withTexture(WINGS_CAPE, solid(64, 32)),
                context -> player(context, player()
                    .skin(SkinOptions.builder()
                        .cape(TextureOptions.builder().id(WINGS_CAPE).build())
                        .renderCape(true)
                        .renderElytra(true)
                        .build())), true),
            new Family("player skin", "minecraft:entity/player/wide/zuri", Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> player(context, player()
                    .type(PlayerOptions.Type.SKULL)
                    .skin(SkinOptions.builder()
                        .skin(TextureOptions.builder().id("minecraft:entity/player/wide/zuri").build())
                        .build())), true),
            // The held shield is where an item draws its pattern stack through the banner composite.
            new Family("banner pattern", "minecraft:entity/shield/creeper", Read.SPRITE, BOTH, UnaryOperator.identity(),
                context -> item(context, item("minecraft:shield")
                    .type(ItemOptions.Type.HELD_3D)
                    .decoration(DecorationOptions.builder()
                        .bannerLayers(Concurrent.newList(new BannerLayer(
                            context.findBannerPattern("minecraft:creeper").orElseThrow(), DyeColor.Vanilla.RED)))
                        .build())), true),
            new Family("item trim", CHESTPLATE_TRIM, Read.PERMUTATION_INPUT, BOTH, UnaryOperator.identity(),
                context -> item(context, trimmedChestplate()), true),
            new Family("item glint", "minecraft:misc/enchanted_glint_item", Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> item(context, item("minecraft:diamond_sword")
                    .enchanted(true).animateGlint(false)), true),
            // A full fluid cube draws its still texture on the top the pose shows.
            new Family("fluid still", FluidTextures.WATER_STILL_TEXTURE_ID, Read.SPRITE, BOTH, UnaryOperator.identity(),
                context -> new FluidRenderer(context).render(FluidOptions.builder()
                    .fluid(FluidOptions.Fluid.WATER)
                    .output(OUTPUT)
                    .build()), true),
            // The portal shader samples its noise texture across every face it bakes.
            new Family("portal noise", PortalPalette.END_PORTAL_NOISE_TEXTURE_ID, Read.PLAIN, BOTH, UnaryOperator.identity(),
                MissingTextureFamilyTest::endPortal, true),
            // TextRendererTest's book cases break the book texture in both states, so it is broken here in neither.
            new Family("book page", BOOK, Read.PLAIN, Set.of(), UnaryOperator.identity(),
                context -> new TextRenderer(context).render(TextOptions.builder()
                    .style(TextOptions.Style.BOOK)
                    .page(TextSegment.literal(""))
                    .build()), true),
            new Family("container sheet", HOPPER_SHEET, Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> new MenuRenderer(context).render(MenuOptions.builder()
                    .type(MenuOptions.Type.HOPPER)
                    .chromeSprite(Optional.of(ResourceId.parse(HOPPER_SHEET)))
                    .build()), true),
            // AtlasRendererMissingTextureTest hides the end sky, so it is broken here unreadable alone.
            new Family("end sky", PortalPalette.END_SKY_TEXTURE_ID, Read.PLAIN, Set.of(Possible.State.EMPTY), UnaryOperator.identity(),
                MissingTextureFamilyTest::endPortal, true),
            new Family("skull", "minecraft:entity/skeleton/skeleton", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> block(context, "minecraft:skeleton_skull", animation), true),
            new Family("copper golem statue", "minecraft:entity/copper_golem/copper_golem", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> block(context, "minecraft:copper_golem_statue", animation), true),
            new Family("entity texture override", "minecraft:entity/zombie/husk", Read.PLAIN, BOTH, UnaryOperator.identity(),
                (Ticking) (context, animation) -> entity(context, entity("minecraft:zombie")
                    .textureId("minecraft:entity/zombie/husk")
                    .animation(animation)), true),
            new Family("default skin", STEVE, Read.PLAIN, BOTH, UnaryOperator.identity(),
                context -> player(context, player().type(PlayerOptions.Type.SKULL)), true),
            new Family("elytra source", ELYTRA_SOURCE, Read.PLAIN, BOTH, context -> context.withTexture(ELYTRA_SOURCE, solid(64, 32)),
                context -> player(context, player()
                    .skin(SkinOptions.builder()
                        .elytra(TextureOptions.builder().id(ELYTRA_SOURCE).build())
                        .renderElytra(true)
                        .build())), true)
        );
    }

    @TestFactory
    @DisplayName("each family's texture is one the vanilla stack serves and the zero-byte pack answers empty")
    @NotNull Stream<DynamicTest> eachFamilyBreaksAServedTexture() {
        return perFamily(family -> true, family -> {
            RendererContext intact = family.intact().apply(vanilla);
            ResourceId id = ResourceId.parse(family.textureId());
            boolean plain = family.read() == Read.PLAIN;

            assertThat(family + " is served", (plain ? intact.resolvePlainTexture(family.textureId())
                : intact.resolveTexture(family.textureId())).isPresent(), is(true));
            assertThat(family + " decodes to nothing from the zero-byte pack",
                (plain ? broken.plainPixels(id) : broken.pixels(id)).getState(), is(Possible.State.EMPTY));
        });
    }

    @TestFactory
    @DisplayName("a texture no pack supplies draws the checkerboard, reported once as missing")
    @NotNull Stream<DynamicTest> aMissingTextureDrawsTheCheckerboard() {
        return perFamily(family -> family.breaks().contains(Possible.State.ABSENT),
            family -> assertDrawsTheCheckerboard(family, intact -> intact.hiding(family.textureId()),
                Possible.State.ABSENT, "Missing texture '" + family.textureId() + "' - drawing the checkerboard"));
    }

    @TestFactory
    @DisplayName("a zero-byte texture draws the checkerboard, reported once as unreadable, and a refused animation loses only an atlas sprite")
    @NotNull Stream<DynamicContainer> anUnreadableTextureDrawsTheCheckerboard() {
        // The refused animation runs after the zero-byte break, in one container, because a sprite read
        // refusing it reports the id unreadable as the zero-byte break does, and only the first report prints.
        return families().filter(family -> family.breaks().contains(Possible.State.EMPTY)).map(family -> {
            DynamicTest zeroBytes = DynamicTest.dynamicTest("zero bytes", () -> assertDrawsTheCheckerboard(family,
                intact -> unreadable(intact, family.textureId()),
                Possible.State.EMPTY, "Unreadable texture '" + family.textureId() + "' - drawing the checkerboard"));
            Stream<DynamicTest> tests = family.read() == Read.PERMUTATION_INPUT
                ? Stream.of(zeroBytes)
                : Stream.of(zeroBytes, DynamicTest.dynamicTest("a refused animation", () -> assertRefusedAnimation(family)));
            return DynamicContainer.dynamicContainer(family.name(), tests);
        });
    }

    @TestFactory
    @DisplayName("a valid animation draws what its read site drew before: its first frame at rest and a frame a tick where the site plays a table, the whole strip where it never did")
    @NotNull Stream<DynamicTest> aValidAnimationDrawsWhatItsReadSiteDrewBefore() {
        return perFamily(family -> family.read() == Read.SPRITE || FLIPBOOK_SITES.contains(family.name())
            || WHOLE_SITES.contains(family.name()), family -> {
            RendererContext intact = family.intact().apply(vanilla);
            String id = family.textureId();
            List<Object> atRest = picture(family.render().draw(intact));
            List<Object> wholeStrip = picture(family.render().draw(intact.withTexture(id, STRIPS.get(id))));
            Staged staged = staged(intact, id, animated);
            List<Object> drawn = picture(family.render().draw(staged));

            if (WHOLE_SITES.contains(family.name())) {
                assertThat(family + " draws the whole strip, as the client draws a plain texture", drawn, is(wholeStrip));
                assertThat(family + "'s whole strip is not its first frame", drawn, is(not(atRest)));
            } else {
                assertThat(family + " draws the strip's first frame at rest", drawn, is(atRest));
                if (family.read() == Read.PLAIN)
                    assertThat(family + "'s first frame is not the whole strip", drawn, is(not(wholeStrip)));
                if (FLIPBOOK_SITES.contains(family.name()))
                    assertPlaysItsStrip(family, intact, staged);
            }
        });
    }

    @Test
    @DisplayName("a baby whose named baby texture is missing or unreadable draws the checkerboard, never the adult texture")
    void aMissingBabyTextureIsNotTheAdultTexture() {
        Render baby = context -> entity(context, entity("minecraft:pig")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build()));
        PixelBuffer adult = vanilla.resolveTexture(PIG_ADULT).orElseThrow();
        List<Object> checkerboard = picture(baby.draw(vanilla.withTexture(PIG_BABY, MissingSprite.sprite())));

        RenderResult missing = baby.draw(vanilla.hiding(PIG_BABY));
        List<Object> drawn = picture(missing);

        assertThat(drawn, is(checkerboard));
        assertThat("the result names the baby texture", missing.substitutions(),
            contains(Substitution.texture(PIG_BABY, Possible.State.ABSENT)));
        assertThat("the adult texture does not stand in for the baby's",
            drawn, is(not(picture(baby.draw(vanilla.withTexture(PIG_BABY, adult))))));

        RenderResult zeroByte = baby.draw(unreadable(vanilla, PIG_BABY));
        assertThat("an unreadable baby texture draws it too", picture(zeroByte), is(checkerboard));
        assertThat("and is named unreadable", zeroByte.substitutions(),
            contains(Substitution.texture(PIG_BABY, Possible.State.EMPTY)));
    }

    @Test
    @DisplayName("a row that names no texture draws the empty frame")
    void aRowNamingNoTextureDrawsTheEmptyFrame() {
        Entity creeper = vanilla.findEntity("minecraft:creeper").orElseThrow();
        Entity untextured = new Entity(creeper.id(), creeper.model(), creeper.overlays(), creeper.blockOverlays(),
            creeper.baseTintArgb(), creeper.rendererScale(),
            new Entity.Axes(creeper.axes().baby(), creeper.axes().shape(), Entity.Variation.none(),
                creeper.axes().size(), creeper.axes().variant(), creeper.axes().carried(), creeper.axes().equipment()),
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
    @DisplayName("a trim whose palette key is missing draws the same whole checkerboard as one whose pattern is")
    void aTrimMissingAnyInputIsTheCheckerboardAsAWhole() {
        Render trimmed = context -> item(context, trimmedChestplate());
        RenderResult paletteless = trimmed.draw(vanilla.hiding(TRIM_PALETTE_KEY));

        assertThat(picture(paletteless),
            is(picture(trimmed.draw(vanilla.withTexture(CHESTPLATE_TRIM, MissingSprite.sprite())))));
        assertThat("the result names the palette key, not the pattern it never permuted", paletteless.substitutions(),
            contains(Substitution.texture(TRIM_PALETTE_KEY, Possible.State.ABSENT)));
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
        String babySheet = "minecraft:entity/equipment/humanoid_baby/gold";
        assertThat("the sheet is served", vanilla.resolveTexture(sheet).isPresent(), is(true));
        assertThat("the baby sheet is served", vanilla.resolveTexture(babySheet).isPresent(), is(true));
        ArmorOptions armour = ArmorOptions.builder()
            .helmet(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .chestplate(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .boots(ArmorPiece.of(ArmorMaterial.GOLDEN))
            .build();

        assertCoversItsSheet("adult zombie", context -> entity(context, entity("minecraft:zombie")
            .armor(armour)), sheet, 64, 32);
        // A baby dresses every slot from its own sheet.
        assertCoversItsSheet("baby zombie", context -> entity(context, entity("minecraft:zombie")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .armor(armour)), babySheet, 64, 64);
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
     * One dynamic test per family a filter admits, named for it.
     *
     * @param which the families held
     * @param body what each family is held to
     * @return the tests
     */
    private static @NotNull Stream<DynamicTest> perFamily(@NotNull Predicate<Family> which, @NotNull Consumer<Family> body) {
        return families().filter(which).map(family -> DynamicTest.dynamicTest(family.name(), () -> body.accept(family)));
    }

    /**
     * Asserts a family whose texture ships beside an animation section the client's codec refuses draws
     * as the read its site takes draws it: a plain texture's whole file, its intact picture, naming no
     * stand-in - the refused section playing no table at a site that samples one - and an atlas sprite's
     * checkerboard, named as served with no pixels. A family only measuring the texture reads it plainly,
     * never as a sprite, draws its intact picture and prints nothing.
     *
     * @param family the family under test
     */
    private static void assertRefusedAnimation(@NotNull Family family) {
        RendererContext intact = family.intact().apply(vanilla);
        String id = family.textureId();
        Staged staged = staged(intact, id, refused);
        AtomicReference<RenderResult> drawn = new AtomicReference<>();

        String reported = errDuring(() -> drawn.set(family.render().draw(staged)));

        if (family.read() == Read.SPRITE) {
            assertThat(family + " draws the checkerboard", picture(drawn.get()),
                is(picture(family.render().draw(intact.withTexture(id, MissingSprite.sprite())))));
            assertThat(family + " names its texture unreadable", drawn.get().substitutions(),
                contains(Substitution.texture(id, Possible.State.EMPTY)));
            return;
        }

        assertThat(family + " draws its texture", picture(drawn.get()), is(picture(family.render().draw(intact))));
        assertThat(family + " names no stand-in", drawn.get().substitutions(), is(empty()));
        if (!family.drawn()) {
            assertThat(family + " reads the texture it measures plainly", staged.plainReads(), hasItem(id));
            assertThat(family + " never reads it as a sprite", staged.spriteReads(), not(hasItem(id)));
            assertThat(family + " prints nothing", reported, is(emptyString()));
        }
    }

    /**
     * Asserts a family whose read site plays a table over a plain read steps a valid strip with the tick
     * each frame is drawn at: of two frames one tick apart, the first is the first of two intact frames,
     * and the second the second of two frames over the strip's lower frame served whole. A site that
     * drops its tick, or freezes it, draws the upper frame twice.
     *
     * @param family the family under test, whose render takes a timing
     * @param intact the context serving the family's texture intact
     * @param staged the context serving the family's texture as a valid two-frame strip
     */
    private static void assertPlaysItsStrip(@NotNull Family family, @NotNull RendererContext intact, @NotNull RendererContext staged) {
        Ticking render = assertInstanceOf(Ticking.class, family.render(), family + " draws at a timing");
        String id = family.textureId();
        List<Long> drawn = RenderDigest.frameCrcs(render.draw(staged, TWO_TICKS).image());
        List<Long> upper = RenderDigest.frameCrcs(render.draw(intact, TWO_TICKS).image());
        List<Long> lower = RenderDigest.frameCrcs(render.draw(intact.withTexture(id, LOWER_FRAMES.get(id)), TWO_TICKS).image());

        assertThat(family + " draws two frames", drawn.size(), is(2));
        assertThat(family + "'s first frame is the strip's upper frame", drawn.getFirst(), is(upper.getFirst()));
        assertThat(family + "'s second frame is the strip's lower frame", drawn.getLast(), is(lower.getLast()));
        assertThat(family + "'s two frames differ", drawn.getLast(), is(not(drawn.getFirst())));
    }

    /**
     * Wraps the given context so one texture is answered out of a staged stack on all five texture
     * lookups - the sprite read, the plain read and the playback table as the stack answers them, and the
     * sidecar and its animation off the stack's row - and every other id as the context beneath answers it.
     *
     * @param intact the context beneath
     * @param textureId the texture the stack answers
     * @param stack the staged stack
     * @return the context, recording which read each id is asked of
     */
    private static @NotNull Staged staged(@NotNull RendererContext intact, @NotNull String textureId, @NotNull PackStack stack) {
        return new Staged(intact, ResourceId.parse(textureId), stack, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * A context answering one texture out of a staged stack, as the production context answers a texture
     * out of its own, and recording which read each id is asked of.
     *
     * @param delegate the context every other id is answered by
     * @param staged the texture the stack answers
     * @param stack the staged stack
     * @param spriteReads every id the sprite read is asked for, in call order
     * @param plainReads every id the plain read is asked for, in call order
     */
    private record Staged(
        @NotNull RendererContext delegate,
        @NotNull ResourceId staged,
        @NotNull PackStack stack,
        @NotNull List<String> spriteReads,
        @NotNull List<String> plainReads
    ) implements RendererContext.Forwarding {

        /** {@inheritDoc} */
        @Override
        public @NotNull Possible<PixelBuffer> resolveTexture(@NotNull String textureId) {
            this.spriteReads.add(textureId);
            return isStaged(textureId) ? this.stack.pixels(this.staged) : this.delegate.resolveTexture(textureId);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Possible<PixelBuffer> resolvePlainTexture(@NotNull String textureId) {
            this.plainReads.add(textureId);
            return isStaged(textureId) ? this.stack.plainPixels(this.staged) : this.delegate.resolvePlainTexture(textureId);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Possible<Flipbook> findFlipbook(@NotNull String textureId) {
            return isStaged(textureId) ? this.stack.flipbook(this.staged) : this.delegate.findFlipbook(textureId);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Possible<MCMeta> findMeta(@NotNull String textureId) {
            if (!isStaged(textureId)) return this.delegate.findMeta(textureId);
            return this.stack.indexed(this.staged)
                .map(row -> row.meta().orAbsent(Possible::empty))
                .orElseGet(Possible::absent);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Possible<MCMeta.Animation> findAnimation(@NotNull String textureId) {
            if (!isStaged(textureId)) return this.delegate.findAnimation(textureId);
            return findMeta(textureId).flatMap(meta -> meta.animation().orAbsent(Possible::empty));
        }

        /**
         * Answers whether an id names the staged texture.
         *
         * @param textureId the id asked about
         * @return whether the stack answers it
         */
        private boolean isStaged(@NotNull String textureId) {
            return ResourceId.parse(textureId).equals(this.staged);
        }

    }

    /**
     * Asserts a family draws exactly the render it draws where its texture is the checkerboard; that a
     * family drawing the texture draws a picture other than its intact one, that its result names the
     * texture, in the state the break leaves it in, as its one stand-in, and that the render prints the
     * id once in the expected words; and that a family only measuring the texture reads it, yet prints
     * nothing and names none, since it draws none.
     *
     * @param family the family under test
     * @param breaking how the intact context is broken
     * @param state the state the break leaves the texture in - absent where it is hidden, empty where
     *     its file cannot be decoded
     * @param report the report the break should make, once, where the family draws the texture
     */
    private static void assertDrawsTheCheckerboard(
        @NotNull Family family, @NotNull UnaryOperator<RendererContext> breaking, Possible.@NotNull State state,
        @NotNull String report) {
        RendererContext intact = family.intact().apply(vanilla);
        List<Object> reference = picture(family.render().draw(intact.withTexture(family.textureId(), MissingSprite.sprite())));
        RecordingContext reading = RecordingContext.over(breaking.apply(intact));
        AtomicReference<RenderResult> drawn = new AtomicReference<>();

        String reported = errDuring(() -> drawn.set(family.render().draw(reading)));

        assertThat(family + " draws what the checkerboard draws", picture(drawn.get()), is(reference));
        if (family.drawn()) {
            assertThat(family + " draws the texture it names", reference, is(not(picture(family.render().draw(intact)))));
            assertThat(family + " names its texture", drawn.get().substitutions(),
                contains(Substitution.texture(family.textureId(), state)));
            assertThat(family + " reports once", occurrences(reported, report), is(1));
        } else {
            assertThat(family + " reads the texture it only measures", reading.getResolved(), hasItem(family.textureId()));
            assertThat(family + " names no stand-in for a texture it only measures", drawn.get().substitutions(), is(empty()));
            assertThat(family + " prints nothing for a texture it only measures", reported, is(emptyString()));
        }
    }

    /**
     * Asserts a render reading one texture by texel crops draws, where that texture is missing, exactly
     * what it draws where the texture is the stand-in already stretched across the sheet the reader
     * declares, covers every pixel a fully opaque sheet of that size covers, and names the texture as
     * the one stand-in drawn.
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
        assertThat(label + " names the missing texture", drawn.substitutions(),
            contains(Substitution.texture(textureId, Possible.State.ABSENT)));
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

    private static @NotNull RenderResult block(@NotNull RendererContext context, @NotNull String id, @NotNull AnimationOptions animation) {
        return new BlockRenderer(context).render(BlockOptions.builder().blockId(id).output(OUTPUT).animation(animation).build());
    }

    private static @NotNull RenderResult endPortal(@NotNull RendererContext context) {
        return new PortalRenderer(context).render(PortalOptions.builder()
            .portal(PortalOptions.Portal.END_PORTAL)
            .output(OUTPUT)
            .build());
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
        PixelBuffer buffer = PixelBuffer.straight(width, height);
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
     * Draws one family's subject at an animation timing, for a family whose read site plays a table: its
     * one render is the subject at the default timing, one frame at rest.
     */
    @FunctionalInterface
    private interface Ticking extends Render {

        /**
         * Draws the subject through a context at an animation timing.
         *
         * @param context the context the render reads
         * @param animation the timing the frames are drawn at
         * @return the render
         */
        @NotNull RenderResult draw(@NotNull RendererContext context, @NotNull AnimationOptions animation);

        /** {@inheritDoc} */
        @Override
        default @NotNull RenderResult draw(@NotNull RendererContext context) {
            return this.draw(context, AnimationOptions.defaults());
        }

    }

    /**
     * Which of the client's reads takes a family's texture.
     */
    private enum Read {

        /** An atlas sprite, read with the sprite loader's sidecar rules. */
        SPRITE,

        /** A plain texture, which the client's texture manager loads by its file, its sidecar's texture section alone. */
        PLAIN,

        /** An input of a paletted permutation, which the client reads with no sidecar and the renderer reads as an atlas sprite. */
        PERMUTATION_INPUT

    }

    /**
     * One texture family: the texture it breaks, the read that takes it, what serves it intact, and the
     * render reading it.
     *
     * @param name the family's name, which the parameterised display reads
     * @param textureId the texture the family breaks
     * @param read which of the client's reads takes the texture
     * @param breaks the states the missing and unreadable factories break the texture in, which are the
     *     states no other test class breaks it in, the reporting sets being static
     * @param intact what the vanilla stack is wrapped in for the texture to be served
     * @param render the render reading the texture
     * @param drawn whether the render draws the texture, rather than only measuring it
     */
    private record Family(
        @NotNull String name,
        @NotNull String textureId,
        @NotNull Read read,
        @NotNull Set<Possible.State> breaks,
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
