package lib.minecraft.renderer.visual;

import api.simplified.mojang.exception.MojangApiException;
import api.simplified.mojang.response.MojangProfile;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.Background;
import dev.simplified.image.ImageData;
import dev.simplified.image.codec.gif.GifImageWriter;
import dev.simplified.image.codec.gif.GifWriteOptions;
import lib.minecraft.renderer.PlayerRenderer;
import lib.minecraft.renderer.engine.camera.Facing;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.PlayerOptions;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.ArmorTrim;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.option.spec.SkinOptions;
import lib.minecraft.renderer.option.spec.TextureOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.tensor.EulerRotation;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Visual sweep of every {@link PlayerRenderer} option, written as labelled contact sheets to
 * {@code cache/visual/player-render/} for eyeballing. This is a <b>functional / visual</b> tool
 * ("does it render and look right") - there is no parity gate; worn-armor entity-pose parity is a
 * separate effort and out of scope (see {@code notes/player-parity.md}).
 *
 * <p>Each sheet is a grid of labelled cells over a checkerboard (so transparent renders stay
 * visible). The sweep covers the scope x dimension grid, the player head in 3D under every
 * {@link lib.minecraft.renderer.engine.camera.Projection}, the overlay / cape / supersample+FXAA /
 * rotation / background toggles, every armor material on every slot (2D and 3D), dyed leather, a
 * representative trim set, a vanilla-vs-pack armor comparison (with {@code -Ppack}), and a live
 * {@code account} sheet that renders a real player's skin + cape from their Mojang profile. The
 * {@code glint-per-slot} output is the exception to the contact-sheet format: it writes one
 * animated GIF per enchanted-iron slot (2D + 3D) so the scrolling foil is watchable.
 *
 * <p>The sweep skin is the vanilla {@code entity/player/wide/steve} texture id (offline); the sweep
 * cape is a synthesized 64x32 texture since the vanilla pack ships none. The {@code account} sheet
 * is the exception - it streams a live skin + cape through {@link ClientAcquisition#mojang()} and is skipped
 * (not fatal) when offline. 3D cells render at {@link #SSAA}x supersampling for crisp edges.
 *
 * <p>Usage:
 * {@code ./gradlew :asset-renderer:playerRender [-PrenderSize=256] [-Psheets=core-matrix,toggles,account,...] [-Ppack[=<url>]] [-Paccount=<username>]}.
 */
@UtilityClass
public final class TestPlayerRender {

    /** Output root for the per-sheet PNGs and individual cell renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/player-render");

    /** Offline, pack-resolvable default skin texture id. */
    private static final String SKIN_ID = "minecraft:entity/player/wide/steve";

    /** Supersample factor for the sweep so 3D cells stay crisp now that {@code antiAlias} defaults off. */
    private static final int SSAA = 4;

    /** Account whose live skin + cape the {@code account} sheet renders (requires network). */
    private static final String ACCOUNT_USERNAME = "CraftedFury";

    /**
     * Runs the player-render sweep.
     *
     * @param args optional {@code key=value} tokens: {@code size=NNN} (or a bare integer) sets the
     *     per-cell render size; {@code sheets=a,b,c} restricts which sheets render; {@code pack} (or
     *     {@code pack=<url>}) enables the vanilla-vs-pack armor sheet; {@code account=<username>}
     *     overrides the live-account username (defaults to {@link #ACCOUNT_USERNAME})
     * @throws IOException if an output directory or file cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Map<String, String> opts = parseArgs(args);
        int size = Integer.parseInt(opts.getOrDefault("size", "256"));
        List<String> sheetFilter = opts.containsKey("sheets")
            ? List.of(opts.get("sheets").split(","))
            : List.of();

        Files.createDirectories(OUTPUT_DIR);
        PipelineRendererContext context = buildContext(Concurrent.newList());
        PlayerRenderer renderer = new PlayerRenderer(context);

        Map<String, List<Cell>> sheets = new LinkedHashMap<>();
        sheets.put("core-matrix", coreMatrix(size));
        sheets.put("projections", projections(size));
        sheets.put("facing", facing(size));
        sheets.put("toggles", toggles(size));
        sheets.put("armor-3d", armorMaterials(size, PlayerOptions.Dimension.THREE_D));
        sheets.put("armor-2d", armorMaterials(size, PlayerOptions.Dimension.TWO_D));
        sheets.put("armor-per-slot", armorPerSlot(size));
        sheets.put("leather-dye", leatherDye(size));
        sheets.put("trims", trims(size));

        for (var entry : sheets.entrySet()) {
            if (!sheetFilter.isEmpty() && !sheetFilter.contains(entry.getKey())) continue;
            renderSheet(renderer, entry.getKey(), entry.getValue(), size);
        }

        // Animated glint GIFs (enchanted iron per slot, 2D + 3D) beside the armor-per-slot sheet.
        if (sheetFilter.isEmpty() || sheetFilter.contains("glint-per-slot"))
            renderGlintGifs(renderer, "glint-per-slot", glintPerSlot(size));

        // Live-account sheet (CraftedFury skin + cape). Built lazily so the Mojang fetch only runs
        // when the sheet is in scope, and skipped (not fatal) when the network/profile is unavailable.
        if (sheetFilter.isEmpty() || sheetFilter.contains("account")) {
            String username = opts.getOrDefault("account", ACCOUNT_USERNAME);
            try {
                renderSheet(renderer, "account", account(size, username), size);
            } catch (Exception ex) {
                System.err.println("account sheet skipped (" + username + "): " + ex.getMessage());
            }
        }

        System.out.printf("%nDone. Sheets under %s%n", OUTPUT_DIR.toAbsolutePath());
    }

    // ---------------------------------------------------------------------------------------
    // Sheet definitions.
    // ---------------------------------------------------------------------------------------

    /** Scope x dimension grid - the six base renders with the default skin. */
    private static @NotNull List<Cell> coreMatrix(int size) {
        List<Cell> cells = new ArrayList<>();
        for (PlayerOptions.Type type : PlayerOptions.Type.values())
            for (PlayerOptions.Dimension dim : PlayerOptions.Dimension.values())
                cells.add(new Cell(type + " " + dim, base(size).type(type).dimension(dim).build()));
        return cells;
    }

    /**
     * The player head (SKULL) rendered in 3D under every {@link Projection} - one cell per catalog
     * entry, so each camera pose + lens resolves and rasterizes. A functional check that the whole
     * projection taxonomy is wired end to end; the head is the smallest scope that still shows top /
     * front / side faces, making pose and clipping issues obvious.
     */
    private static @NotNull List<Cell> projections(int size) {
        List<Cell> cells = new ArrayList<>();
        for (Projection projection : Projection.values())
            cells.add(new Cell(projection.name().toLowerCase(),
                base(size).type(PlayerOptions.Type.SKULL).dimension(PlayerOptions.Dimension.THREE_D)
                    .output(sweepRender(size).mutate().projection(projection).build()).build()));
        return cells;
    }

    /**
     * The {@link Facing} view toggles: the head under {@link Projection#PORTRAIT} for each of the four
     * mirrored / flipped combinations, then {@link Projection#CAVALIER} default vs mirrored - the oblique
     * case that only mirrors via the lens shear flip (its yaw-180 pose makes {@code 360 - yaw} a no-op).
     */
    private static @NotNull List<Cell> facing(int size) {
        List<Cell> cells = new ArrayList<>();
        for (Facing f : new Facing[]{Facing.DEFAULT, Facing.MIRRORED, Facing.FLIPPED, Facing.MIRRORED_FLIPPED})
            cells.add(new Cell("portrait " + (f.mirrored() ? "M" : "-") + (f.flipped() ? "F" : "-"),
                base(size).type(PlayerOptions.Type.SKULL).dimension(PlayerOptions.Dimension.THREE_D)
                    .output(sweepRender(size).mutate().projection(Projection.PORTRAIT).facing(f).build()).build()));
        cells.add(new Cell("cavalier default", base(size).type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.THREE_D).output(sweepRender(size).mutate().projection(Projection.CAVALIER).build()).build()));
        cells.add(new Cell("cavalier mirrored", base(size).type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.THREE_D).output(sweepRender(size).mutate().projection(Projection.CAVALIER).facing(Facing.MIRRORED).build()).build()));
        return cells;
    }

    /** Overlay / cape / anti-alias / rotation / background toggles on the FULL body. */
    private static @NotNull List<Cell> toggles(int size) {
        byte[] cape = capeTexture();
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell("FULL 3D base", base(size).type(PlayerOptions.Type.FULL).build()));
        cells.add(new Cell("overlay off", base(size).type(PlayerOptions.Type.FULL).skin(skinBase().renderOverlay(false).build()).build()));
        // The cape sits on the anatomical back, so the default front view hides it; the rear view
        // (180 yaw) turns the back to the camera to show it renders.
        cells.add(new Cell("cape (rear)", base(size).type(PlayerOptions.Type.FULL)
            .skin(skinBase().cape(TextureOptions.builder().bytes(Optional.of(cape)).build()).renderCape(true).build()).output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 180f, 0f)).build()).build()));
        cells.add(new Cell("cape (front)", base(size).type(PlayerOptions.Type.FULL)
            .skin(skinBase().cape(TextureOptions.builder().bytes(Optional.of(cape)).build()).renderCape(true).build()).build()));
        // Elytra supersedes the cape and shows the cape design (use_player_texture); rear view since the
        // wings sit on the anatomical back.
        cells.add(new Cell("elytra only (3/4)", base(size).type(PlayerOptions.Type.FULL)
            .skin(skinBase().renderElytra(true).build())
            .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 135f, 0f)).build()).build()));
        cells.add(new Cell("elytra+cape (3/4)", base(size).type(PlayerOptions.Type.FULL)
            .skin(skinBase().cape(TextureOptions.builder().bytes(Optional.of(cape)).build()).renderCape(true).renderElytra(true).build())
            .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 135f, 0f)).build()).build()));
        cells.add(new Cell("ssaa 1 (raw)", base(size).type(PlayerOptions.Type.FULL).output(sweepRender(size).mutate().supersample(1).build()).build()));
        cells.add(new Cell("ssaa 4 + fxaa", base(size).type(PlayerOptions.Type.FULL).output(sweepRender(size).mutate().antiAlias(true).build()).build()));
        cells.add(new Cell("rot 0/0/0 (front)", base(size).type(PlayerOptions.Type.FULL)
            .output(sweepRender(size).mutate().rotation(EulerRotation.NONE).build()).build()));
        cells.add(new Cell("rot 15/210/0", base(size).type(PlayerOptions.Type.FULL)
            .output(sweepRender(size).mutate().rotation(new EulerRotation(15f, 210f, 0f)).build()).build()));
        cells.add(new Cell("bg solid grey", base(size).type(PlayerOptions.Type.FULL)
            .background(Background.solid(0xFF40444C)).build()));
        cells.add(new Cell("bg checkerboard", base(size).type(PlayerOptions.Type.FULL)
            .background(Background.checkerboard()).build()));
        cells.add(new Cell("2D bg solid", base(size).type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.TWO_D).background(Background.solid(0xFF40444C)).build()));
        return cells;
    }

    /** Every armor material with all four slots equipped, in the given dimension. */
    private static @NotNull List<Cell> armorMaterials(int size, @NotNull PlayerOptions.Dimension dim) {
        List<Cell> cells = new ArrayList<>();
        for (ArmorMaterial material : ArmorMaterial.values())
            cells.add(new Cell(material.name().toLowerCase() + " " + dim,
                allSlots(base(size).type(PlayerOptions.Type.FULL).dimension(dim), ArmorPiece.of(material)).build()));
        return cells;
    }

    /** Iron armor equipped one slot at a time - verifies each slot covers the right body parts. */
    private static @NotNull List<Cell> armorPerSlot(int size) {
        ArmorPiece iron = ArmorPiece.of(ArmorMaterial.IRON);
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell("helmet only 3D", base(size).type(PlayerOptions.Type.FULL).armor(ArmorOptions.builder().helmet(Optional.of(iron)).build()).build()));
        cells.add(new Cell("chestplate only 3D", base(size).type(PlayerOptions.Type.FULL).armor(ArmorOptions.builder().chestplate(Optional.of(iron)).build()).build()));
        cells.add(new Cell("leggings only 3D", base(size).type(PlayerOptions.Type.FULL).armor(ArmorOptions.builder().leggings(Optional.of(iron)).build()).build()));
        cells.add(new Cell("boots only 3D", base(size).type(PlayerOptions.Type.FULL).armor(ArmorOptions.builder().boots(Optional.of(iron)).build()).build()));
        cells.add(new Cell("helmet only 2D", base(size).type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.TWO_D).armor(ArmorOptions.builder().helmet(Optional.of(iron)).build()).build()));
        cells.add(new Cell("chestplate only 2D", base(size).type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.TWO_D).armor(ArmorOptions.builder().chestplate(Optional.of(iron)).build()).build()));
        cells.add(new Cell("leggings only 2D", base(size).type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.TWO_D).armor(ArmorOptions.builder().leggings(Optional.of(iron)).build()).build()));
        cells.add(new Cell("boots only 2D", base(size).type(PlayerOptions.Type.FULL)
            .dimension(PlayerOptions.Dimension.TWO_D).armor(ArmorOptions.builder().boots(Optional.of(iron)).build()).build()));
        cells.add(new Cell("enchanted iron 3D", allSlots(base(size).type(PlayerOptions.Type.FULL),
            new ArmorPiece(ArmorMaterial.IRON, Optional.empty(), Optional.empty(), true)).build()));
        return cells;
    }

    /**
     * Enchanted-iron mirror of {@link #armorPerSlot}: each slot alone (plus all slots) in both 2D
     * and 3D, with the piece enchanted so the animated foil renders. Output as GIFs so the scrolling
     * glint is watchable on each slot in both dimensions.
     */
    private static @NotNull List<Cell> glintPerSlot(int size) {
        ArmorPiece iron = new ArmorPiece(ArmorMaterial.IRON, Optional.empty(), Optional.empty(), true);
        List<Cell> cells = new ArrayList<>();
        for (PlayerOptions.Dimension dim : PlayerOptions.Dimension.values()) {
            String d = dim == PlayerOptions.Dimension.THREE_D ? "3D" : "2D";
            cells.add(new Cell("helmet " + d, base(size).type(PlayerOptions.Type.FULL).dimension(dim).armor(ArmorOptions.builder().helmet(Optional.of(iron)).build()).build()));
            cells.add(new Cell("chestplate " + d, base(size).type(PlayerOptions.Type.FULL).dimension(dim).armor(ArmorOptions.builder().chestplate(Optional.of(iron)).build()).build()));
            cells.add(new Cell("leggings " + d, base(size).type(PlayerOptions.Type.FULL).dimension(dim).armor(ArmorOptions.builder().leggings(Optional.of(iron)).build()).build()));
            cells.add(new Cell("boots " + d, base(size).type(PlayerOptions.Type.FULL).dimension(dim).armor(ArmorOptions.builder().boots(Optional.of(iron)).build()).build()));
            cells.add(new Cell("all slots " + d, allSlots(base(size).type(PlayerOptions.Type.FULL).dimension(dim), iron).build()));
        }
        return cells;
    }

    /** Dyed-leather sweep: default tint plus several dye colours, in 3D and 2D. */
    private static @NotNull List<Cell> leatherDye(int size) {
        List<Cell> cells = new ArrayList<>();
        String[] names = {"default", "red", "blue", "green", "orange", "cyan"};
        // null = no dye override (vanilla default brown); boxed so the 0xFF.. sign bit isn't a sentinel.
        Integer[] dyes = {null, 0xFFB02E26, 0xFF3C44AA, 0xFF5E7C16, 0xFFF9801D, 0xFF169C9C};
        for (PlayerOptions.Dimension dim : PlayerOptions.Dimension.values())
            for (int i = 0; i < names.length; i++) {
                ArmorPiece piece = dyes[i] == null
                    ? ArmorPiece.of(ArmorMaterial.LEATHER)
                    : ArmorPiece.dyedLeather(dyes[i]);
                cells.add(new Cell(names[i] + " " + dim,
                    allSlots(base(size).type(PlayerOptions.Type.FULL).dimension(dim), piece).build()));
            }
        return cells;
    }

    /** Representative trim sweep: patterns on diamond armor, plus a per-slot trim spread. */
    private static @NotNull List<Cell> trims(int size) {
        List<Cell> cells = new ArrayList<>();
        ArmorTrim.Pattern[] patterns = {
            ArmorTrim.Pattern.SENTRY, ArmorTrim.Pattern.WARD, ArmorTrim.Pattern.SPIRE,
            ArmorTrim.Pattern.WILD, ArmorTrim.Pattern.EYE, ArmorTrim.Pattern.COAST
        };
        ArmorTrim.Color[] colors = {
            ArmorTrim.Color.GOLD, ArmorTrim.Color.IRON_DARKER, ArmorTrim.Color.AMETHYST,
            ArmorTrim.Color.EMERALD, ArmorTrim.Color.REDSTONE, ArmorTrim.Color.LAPIS
        };
        for (int i = 0; i < patterns.length; i++) {
            ArmorPiece piece = ArmorPiece.of(ArmorMaterial.DIAMOND, colors[i], patterns[i]);
            cells.add(new Cell(patterns[i].name().toLowerCase() + "/" + colors[i].name().toLowerCase(),
                allSlots(base(size).type(PlayerOptions.Type.FULL), piece).build()));
        }
        // Same pattern across every colour on a netherite chestplate, to eyeball the palette permutation.
        for (ArmorTrim.Color color : new ArmorTrim.Color[]{
            ArmorTrim.Color.COPPER, ArmorTrim.Color.GOLD, ArmorTrim.Color.DIAMOND, ArmorTrim.Color.NETHERITE_DARKER}) {
            ArmorPiece piece = ArmorPiece.of(ArmorMaterial.NETHERITE, color, ArmorTrim.Pattern.SILENCE);
            cells.add(new Cell("silence/" + color.name().toLowerCase() + " chest",
                base(size).type(PlayerOptions.Type.FULL).armor(ArmorOptions.builder().chestplate(Optional.of(piece)).build()).build()));
        }
        // Trim on every slot at once.
        cells.add(new Cell("all slots tide/copper",
            allSlots(base(size).type(PlayerOptions.Type.FULL),
                ArmorPiece.of(ArmorMaterial.IRON, ArmorTrim.Color.COPPER, ArmorTrim.Pattern.TIDE)).build()));
        return cells;
    }

    /**
     * Live-account sheet: resolves a username's Mojang profile through {@link ClientAcquisition#mojang()},
     * then renders its real skin (and cape, if the account has one) across scopes. The cape is shown
     * from the rear and at a 3/4 angle since the front-facing default hides the anatomical back.
     * The skin / cape URLs stream through the same {@link ClientAcquisition#mojang()} contract at render time
     * (see {@code PlayerRenderer.resolveSkin}/{@code resolveCape}). Requires network; the caller
     * skips the sheet on failure.
     */
    private static @NotNull List<Cell> account(int size, @NotNull String username) throws MojangApiException {
        MojangProfile profile = ClientAcquisition.mojang().getMojangProfile(username);
        MojangProfile.Textures textures = profile.getTextures();
        Optional<String> skinUrl = textures.getSkin().map(value -> value.getUrl());
        Optional<String> capeUrl = textures.getCape().map(value -> value.getUrl());
        System.out.printf("  %s: skin=%s cape=%s slim=%s%n",
            username, skinUrl.isPresent(), capeUrl.isPresent(), textures.isSlim());

        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(username + " full", accountBase(size, skinUrl).type(PlayerOptions.Type.FULL).build()));
        cells.add(new Cell(username + " bust", accountBase(size, skinUrl).type(PlayerOptions.Type.BUST).build()));
        cells.add(new Cell(username + " head", accountBase(size, skinUrl).type(PlayerOptions.Type.SKULL).build()));
        cells.add(new Cell(username + " 2D", accountBase(size, skinUrl)
            .type(PlayerOptions.Type.FULL).dimension(PlayerOptions.Dimension.TWO_D).build()));
        // Elytra using the static wing skin (no cape source), so the wings show regardless of the account.
        cells.add(new Cell(username + " elytra (3/4)", accountBase(size, skinUrl)
            .type(PlayerOptions.Type.FULL).skin(accountSkinBase(skinUrl).renderElytra(true).build())
            .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 135f, 0f)).build()).build()));
        if (capeUrl.isPresent()) {
            cells.add(new Cell(username + " cape (rear)", accountBase(size, skinUrl)
                .type(PlayerOptions.Type.FULL).skin(accountSkinBase(skinUrl).cape(TextureOptions.builder().url(capeUrl).build()).renderCape(true).build())
                .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 180f, 0f)).build()).build()));
            cells.add(new Cell(username + " cape (3/4)", accountBase(size, skinUrl)
                .type(PlayerOptions.Type.FULL).skin(accountSkinBase(skinUrl).cape(TextureOptions.builder().url(capeUrl).build()).renderCape(true).build())
                .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 150f, 0f)).build()).build()));
            // The wearer's cape textures the elytra (use_player_texture) and supersedes the flat cape.
            cells.add(new Cell(username + " elytra+cape (3/4)", accountBase(size, skinUrl)
                .type(PlayerOptions.Type.FULL).skin(accountSkinBase(skinUrl).cape(TextureOptions.builder().url(capeUrl).build()).renderCape(true).renderElytra(true).build())
                .output(sweepRender(size).mutate().rotation(new EulerRotation(0f, 135f, 0f)).build()).build()));
        } else {
            System.out.println("  (account has no cape)");
        }
        return cells;
    }

    /** Builder seeded with the account's live skin URL (or the default skin when the account has none). */
    private static PlayerOptions.@NotNull PlayerOptionsBuilder accountBase(int size, @NotNull Optional<String> skinUrl) {
        return PlayerOptions.builder().output(sweepRender(size)).skin(accountSkinBase(skinUrl).build());
    }

    /**
     * A {@link SkinOptions} builder seeded with the account's live skin URL, or the default skin id
     * when the account has none.
     */
    private static SkinOptions.SkinOptionsBuilder accountSkinBase(@NotNull Optional<String> skinUrl) {
        TextureOptions skin = skinUrl.isPresent()
            ? TextureOptions.builder().url(skinUrl).build()
            : TextureOptions.builder().id(Optional.of(SKIN_ID)).build();
        return SkinOptions.builder().skin(skin);
    }

    // ---------------------------------------------------------------------------------------
    // Rendering + sheet assembly.
    // ---------------------------------------------------------------------------------------

    /** Renders every cell in a sheet, writes individual PNGs, then a grid contact sheet. */
    private static void renderSheet(
        @NotNull PlayerRenderer renderer,
        @NotNull String name,
        @NotNull List<Cell> defs,
        int size
    ) throws IOException {
        System.out.printf("Sheet %-16s (%d cells)...%n", name, defs.size());
        List<Cell> rendered = new ArrayList<>(defs.size());
        Path dir = OUTPUT_DIR.resolve(name);
        Files.createDirectories(dir);
        for (Cell cell : defs) {
            BufferedImage img;
            try {
                img = render(renderer, cell.options());
                ImageIO.write(img, "PNG", dir.resolve(safe(cell.label()) + ".png").toFile());
            } catch (Exception ex) {
                System.err.printf("    %-28s FAILED: %s%n", cell.label(), ex.getMessage());
                img = failureCell(size, cell.label(), ex);
            }
            rendered.add(new Cell(cell.label(), img));
        }
        writeGrid(name, rendered, columnsFor(defs.size()), size);
    }

    /** GIF encoder preset: infinite loop, transparent background (matches the glint-parity tool). */
    private static final GifWriteOptions GIF_OPTIONS = GifWriteOptions.builder()
        .withLoopCount(0).isTransparent(true).withAlphaThreshold(8).build();

    /**
     * Renders each config to an animated GIF under {@code <name>/<label>.gif}. Each piece is
     * enchanted, so {@link PlayerRenderer#render} returns the animated glint frames directly; the
     * GIF captures the scrolling foil. Warns when a render comes back non-animated (glint absent).
     */
    private static void renderGlintGifs(
        @NotNull PlayerRenderer renderer,
        @NotNull String name,
        @NotNull List<Cell> configs
    ) throws IOException {
        System.out.printf("Sheet %-16s (%d gifs)...%n", name, configs.size());
        Path dir = OUTPUT_DIR.resolve(name);
        Files.createDirectories(dir);
        GifImageWriter writer = new GifImageWriter();
        for (Cell cell : configs) {
            try {
                ImageData image = renderer.render(cell.options());
                if (!image.isAnimated())
                    System.err.printf("    %-24s NOT animated (%d frame) - glint missing%n",
                        cell.label(), image.getFrames().size());
                Files.write(dir.resolve(safe(cell.label()) + ".gif"), writer.write(image, GIF_OPTIONS));
            } catch (Exception ex) {
                System.err.printf("    %-24s FAILED: %s%n", cell.label(), ex.getMessage());
            }
        }
        System.out.println("  Wrote " + dir.toAbsolutePath());
    }

    private static @NotNull BufferedImage render(@NotNull PlayerRenderer renderer, @NotNull PlayerOptions options) {
        ImageData image = renderer.render(options);
        return image.toBufferedImage();
    }

    /** Lays out pre-rendered cells in a labelled grid over a checkerboard and writes the sheet PNG. */
    private static void writeGrid(@NotNull String name, @NotNull List<Cell> cells, int cols, int size) throws IOException {
        int cell = Math.min(size, 192);
        int gap = 4;
        int labelH = 18;
        int titleH = 24;
        int rows = (cells.size() + cols - 1) / cols;
        int width = gap + cols * (cell + gap);
        int height = titleH + gap + rows * (cell + labelH + gap);
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(new Color(24, 24, 28));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            g.drawString("player-render / " + name + "  (" + cells.size() + " cells)", gap, 17);

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int i = 0; i < cells.size(); i++) {
                int cx = gap + (i % cols) * (cell + gap);
                int cy = titleH + gap + (i / cols) * (cell + labelH + gap);
                drawChecker(g, cx, cy, cell);
                g.drawImage(cells.get(i).image(), cx, cy, cell, cell, null);
                g.setColor(new Color(210, 210, 220));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                drawCentered(g, cells.get(i).label(), cx, cell, cy + cell + 13);
            }
        } finally {
            g.dispose();
        }
        File out = OUTPUT_DIR.resolve(name + ".png").toFile();
        ImageIO.write(sheet, "PNG", out);
        System.out.println("  Wrote " + out.getAbsolutePath());
    }

    private static void drawChecker(@NotNull Graphics2D g, int x, int y, int size) {
        int s = 8;
        for (int yy = 0; yy < size; yy += s)
            for (int xx = 0; xx < size; xx += s) {
                boolean dark = ((xx / s) + (yy / s)) % 2 == 0;
                g.setColor(dark ? new Color(60, 60, 66) : new Color(86, 86, 94));
                g.fillRect(x + xx, y + yy, Math.min(s, size - xx), Math.min(s, size - yy));
            }
    }

    private static void drawCentered(@NotNull Graphics2D g, @NotNull String text, int x, int width, int baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (width - fm.stringWidth(text)) / 2, baselineY);
    }

    private static @NotNull BufferedImage failureCell(int size, @NotNull String label, @NotNull Exception ex) {
        int s = Math.min(size, 192);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(120, 30, 30));
            g.fillRect(0, 0, s, s);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.drawString("FAILED", 6, 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            String msg = String.valueOf(ex.getMessage());
            g.drawString(msg.length() > 30 ? msg.substring(0, 30) : msg, 6, 34);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    private static PlayerOptions.@NotNull PlayerOptionsBuilder base(int size) {
        return PlayerOptions.builder()
            .skin(skinBase().build())
            .output(sweepRender(size));
    }

    /** A {@link SkinOptions} builder seeded with the sweep's default (Steve) skin source. */
    private static SkinOptions.SkinOptionsBuilder skinBase() {
        return SkinOptions.builder().skin(TextureOptions.builder().id(Optional.of(SKIN_ID)).build());
    }

    /** The shared sweep render frame - the per-cell size at {@link #SSAA}x supersample. */
    private static @NotNull OutputOptions sweepRender(int size) {
        return OutputOptions.builder().canvasSize(size).supersample(SSAA).build();
    }

    private static PlayerOptions.@NotNull PlayerOptionsBuilder allSlots(
        PlayerOptions.@NotNull PlayerOptionsBuilder builder,
        @NotNull ArmorPiece piece
    ) {
        return builder
            .armor(ArmorOptions.builder()
                .helmet(Optional.of(piece))
                .chestplate(Optional.of(piece))
                .leggings(Optional.of(piece))
                .boots(Optional.of(piece))
                .build());
    }

    private static @NotNull PipelineRendererContext buildContext(@NotNull ConcurrentList<File> userPacks) {
        ClientOptions options = ClientOptions.defaults().mutate().texturePacks(userPacks).build();
        try {
            return PipelineRendererContext.of(ClientAcquisition.acquire(options));
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
    }

    private static @NotNull Map<String, String> parseArgs(String @NotNull [] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.isBlank()) continue;
            int eq = arg.indexOf('=');
            if (eq >= 0) map.put(arg.substring(0, eq), arg.substring(eq + 1));
            else if (arg.chars().allMatch(Character::isDigit)) map.put("size", arg);
            else map.put(arg, "");
        }
        return map;
    }

    private static int columnsFor(int count) {
        return count <= 4 ? count : count <= 6 ? 3 : count <= 12 ? 6 : 6;
    }

    private static @NotNull String safe(@NotNull String label) {
        return label.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /**
     * Synthesizes a recognizable 64x32 cape texture (vanilla ships none): navy field with a red top
     * band and a gold centre stripe painted on <b>both</b> broad faces (the south crop at x 1..10 and
     * the north crop at x 12..21) so the design reads whichever face the iso pose presents. The whole
     * atlas is filled and the elytra wing region (texOffs {@code 22,0}) carries a gold cross-hatch, so a
     * caped player wearing an elytra ({@code use_player_texture}) shows the cape design on the wings -
     * an elytra-compatible cape, matching the real 64x32 capes that ship both designs.
     */
    private static byte[] capeTexture() {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++)
                img.setRGB(x, y, 0xFF1A2E6E);
        paintCapeFace(img, 1);   // south broad face (x 1..10)
        paintCapeFace(img, 12);  // north broad face (x 12..21)
        // Elytra wing region (a 10x20x2 box unwrapped at 22,0 spans x 22..46, y 0..22): gold cross-hatch
        // so the cape-textured wings are visibly the cape, not the static elytra skin.
        for (int y = 0; y < 22; y++)
            for (int x = 22; x < 46; x++)
                if (((x + y) % 5) < 2) img.setRGB(x, y, 0xFFFED83D);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", bos);
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Paints the red-band + gold-stripe cape design into the 10x16 face starting at {@code x0}. */
    private static void paintCapeFace(@NotNull BufferedImage img, int x0) {
        for (int y = 1; y <= 16; y++)
            for (int dx = 0; dx < 10; dx++) {
                int rgb = 0xFF22368A;
                if (y <= 3) rgb = 0xFFB02E26;            // red top band
                else if (dx == 4 || dx == 5) rgb = 0xFFFED83D; // gold centre stripe
                img.setRGB(x0 + dx, y, rgb);
            }
    }

    /**
     * A single labelled grid cell - either a deferred {@link PlayerOptions} (rendered later during
     * sheet assembly) or an already-rendered image.
     *
     * @param label caption drawn beneath the cell and used to derive its PNG filename
     * @param options render request to execute, or {@code null} when the cell holds a pre-rendered image
     * @param image pre-rendered image, or {@code null} when the cell defers to {@code options}
     */
    private record Cell(@NotNull String label, PlayerOptions options, BufferedImage image) {
        Cell(@NotNull String label, @NotNull PlayerOptions options) {
            this(label, options, null);
        }

        Cell(@NotNull String label, @NotNull BufferedImage image) {
            this(label, null, image);
        }
    }

}
