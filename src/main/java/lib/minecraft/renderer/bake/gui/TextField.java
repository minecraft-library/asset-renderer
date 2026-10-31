package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.slot.MenuSlot;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftFontMetrics;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The widget an editable screen field is: what the caller's text cuts down to, the part of it the
 * well shows, and the caret marking where typing would continue - or the highlight band marking the
 * text that runs past the well, where the text has scrolled the caret out of view.
 * <p>
 * The field's well is chrome and its text is a render: the {@link Window} draws the well - from rules
 * in its own inks, or as the GUI sprite the client blits over named art - and this puts the text in it
 * in either window, as the client draws its field's text over the field's sprite.
 */
@UtilityClass
@Parity(claim = "menu-closure", mode = Mode.DEMOTE)
public class TextField {

    /**
     * Output pixels one Minecraft pixel occupies on a side. It is the font's, because text drawn at
     * any other scale would not line up with the panel its well is sunk into.
     */
    private static final int PX_SCALE = MinecraftFont.MC_PIXEL_SCALE;

    /**
     * The rows a field's two fills cover - the insert caret's bar and the highlight band, both from a
     * pixel above the glyph cell to two below its eight rows, which is the client's fills from
     * {@code y - 1} to {@code y + 10}.
     */
    private static final int FILL_RISE = 1, FILL_HEIGHT = 11;

    /**
     * How many times the client's field scrolls to its cursor when it is set to a text whole - once
     * placing the cursor at the end, once placing its highlight on the way, and once more placing the
     * highlight again.
     */
    private static final int SET_VALUE_SCROLLS = 3;

    /**
     * The colour the client's field adds to mark text running past it - a blue its additive
     * text-highlight pipeline adds to whatever lies under it, with the colour inversion it can lay
     * first switched off, as the anvil's is.
     */
    private static final int HIGHLIGHT_ARGB = 0xFF0000FF;

    /**
     * How far inside the shown text's {@code Font#width} the highlight band opens, in Minecraft pixels -
     * the client's band starts on the column the last glyph's advance leaves for its shadow.
     */
    private static final int BAND_LEAD = 1;

    /**
     * How far the field's pen moves past a text it draws, in Minecraft pixels - the client's field
     * steps one past its text's {@code Font#width}, so an appended caret opens a pixel past the text's
     * advance, one empty column after the last glyph's shadow, and a full field's bar steps back the
     * same pixel onto the text's own end.
     */
    private static final int PEN_STEP = 1;

    /**
     * Draws what a screen's text field holds and the caret marking where typing would continue.
     * <p>
     * Nothing is drawn for a screen that has no field, which is every screen but the anvil.
     * <p>
     * The field shows its text as the client's shows a text it is set to whole, from where that
     * scroll leaves its start. Where the scroll leaves the cursor out of view the client draws no
     * caret, whatever its blink, and adds a blue band over the field from a pixel inside the shown
     * text's end to the field's right edge instead - laid as the client lays it, added to what lies
     * under it - and the band, being a fill rather than text, keeps its colour under a pack's text
     * palette.
     * <p>
     * The text is drawn plain rather than parsed for format codes, because the client's own field
     * filters them out of what can be typed, and it carries a drop shadow where a container's labels
     * decline one - it is a widget's text and not the panel's.
     *
     * @param options the menu render options, supplying the text and whether a caret is drawn
     * @param layout the laid-out panel, carrying the field among its marks
     * @param palette the colours the stack's text shader draws in place of the field text's own
     * @param stack the layer stack to append to
     */
    public static void placeFieldText(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
        @NotNull TextPalette palette,
        @NotNull LayerStack<FrameLayer> stack
    ) {
        Optional<Mark.Placement> field = layout.marks().stream()
            .filter(mark -> mark.kind().textWell().isPresent())
            .findFirst();
        if (field.isEmpty()) return;

        Mark.TextWell well = field.get().kind().textWell().orElseThrow();
        String typed = typedInto(options.getFieldText(), well.maxLength());
        int displayPos = displayPos(typed, well.innerWidth());
        String shown = TextSegment.literal(typed).resolveFont().metrics()
            .stringHeadByWidthMcPixels(typed.substring(displayPos), well.innerWidth());
        boolean cursorInView = typed.length() - displayPos <= shown.length();
        if (shown.isEmpty() && !options.isCaret()) return;

        PixelBuffer buffer = PixelBuffer.canvas(layout.width() * PX_SCALE, layout.height() * PX_SCALE);
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int textX = field.get().x() + well.inset().x();
        int textY = field.get().y() + well.inset().y();
        int baseline = textY + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int shownWidth = TextKit.measureLineMcPixels(plain(shown));
        int penX = textX;
        if (!shown.isEmpty()) {
            TextKit.drawLine(g, plain(shown), textX, baseline, well.argb(), palette, 0, 0L, 0L, true);
            penX += shownWidth + PEN_STEP;
        }

        if (cursorInView && options.isCaret()) drawCaret(g, buffer, typed, penX, textY, baseline, well, palette);

        FramePlacement text = new FramePlacement(0, 0, StaticImageData.of(buffer.toBufferedImage()));
        if (cursorInView) {
            stack.append(MenuSlot.TEXT, sink -> sink.add(text));
            return;
        }

        FramePlacement band = highlightBand(textX + shownWidth - BAND_LEAD, textX + well.innerWidth(), textY);
        stack.append(MenuSlot.TEXT, sink -> {
            sink.add(text);
            sink.add(band);
        });
    }

    /**
     * What a field actually holds, which is the caller's text cut to the field's own cap the way the
     * client's own cuts anything typed past it.
     *
     * @param text what the caller asked for
     * @param maxLength how many characters the field accepts
     * @return the text the field holds
     */
    public static @NotNull String typedInto(@NotNull String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * Finds the first character a field shows once it is set to a text whole, as the client's field
     * is when an item placed in the anvil names it - {@code EditBox#setValue} scrolls a fresh field
     * toward the cursor at the text's end three times, each pass moving the field's start past the
     * characters of the head it showed that did not fit.
     * <p>
     * The client's scroll has two further arms, for a cursor standing at or before the field's start.
     * From a fresh field with the cursor at the end they act only on an empty text, which they leave
     * at its start, or past a character wider than the whole field, which no glyph is, so they are not
     * walked.
     *
     * @param typed what the field holds
     * @param innerWidth how wide the text may run, in Minecraft pixels
     * @return the index of the first character shown
     */
    public static int displayPos(@NotNull String typed, int innerWidth) {
        MinecraftFontMetrics metrics = TextSegment.literal(typed).resolveFont().metrics();
        int displayPos = 0;

        for (int pass = 0; pass < SET_VALUE_SCROLLS; pass++) {
            int end = displayPos + metrics.stringHeadByWidthMcPixels(typed.substring(displayPos), innerWidth).length();
            displayPos += typed.length() - end;
        }

        return displayPos;
    }

    /**
     * Builds the highlight band the client's field adds over the text running past it - filled in
     * its blue over the rows the caret's bar fills, and placed to be added to what lies under it.
     *
     * @param fromX the band's left edge, in Minecraft pixels
     * @param toX the band's right edge, exclusive, in Minecraft pixels
     * @param textY the top of the field's glyph cell, in Minecraft pixels
     * @return the band's additive placement
     */
    private static @NotNull FramePlacement highlightBand(int fromX, int toX, int textY) {
        PixelBuffer band = PixelBuffer.canvas((toX - fromX) * PX_SCALE, FILL_HEIGHT * PX_SCALE);
        band.fill(HIGHLIGHT_ARGB);
        return new FramePlacement(fromX * PX_SCALE, (textY - FILL_RISE) * PX_SCALE, StaticImageData.of(band)).additively();
    }

    /**
     * Draws the caret in whichever of its two forms the text's length selects, from where the field's
     * pen stands.
     * <p>
     * The pen is at the text's origin in an empty field and a Minecraft pixel past the text's width
     * after any, the client's field stepping its pen one past the text it draws. Vanilla appends an
     * underscore glyph at the pen, and stops being able to once the field is full - there is no
     * position past the last character to append at - so at the cap it steps the pen back that pixel
     * and fills a bar there, on the text's own end. The glyph carries the text's drop shadow and the
     * bar carries none, being a fill rather than a draw, and keeps the well's own colour, the palette
     * swapping only what is drawn as text.
     *
     * @param g the graphics drawing the glyph form into the buffer
     * @param buffer the field text's layer, which the bar form is filled into
     * @param typed what the field holds, whose length against the cap selects the form
     * @param penX where the field's pen stands after its text, in Minecraft pixels
     * @param textY the top of the field's glyph cell, in Minecraft pixels
     * @param baseline the field text's baseline, in Minecraft pixels
     * @param well the field's well, supplying its cap and colour
     * @param palette the colours the stack's text shader draws in place of the glyph form's own
     */
    private static void drawCaret(
        @NotNull MinecraftGraphics g, @NotNull PixelBuffer buffer,
        String typed, int penX, int textY, int baseline, @NotNull Mark.TextWell well, @NotNull TextPalette palette
    ) {
        if (typed.length() < well.maxLength()) {
            TextKit.drawLine(g, plain("_"), penX, baseline, well.argb(), palette, 0, 0L, 0L, true);
            return;
        }

        for (int y = 0; y < FILL_HEIGHT * PX_SCALE; y++) {
            int py = (textY - FILL_RISE) * PX_SCALE + y;
            if (py < 0 || py >= buffer.height()) continue;

            for (int x = 0; x < PX_SCALE; x++) {
                int px = (penX - PEN_STEP) * PX_SCALE + x;
                if (px < 0 || px >= buffer.width()) continue;
                buffer.setPixel(px, py, well.argb());
            }
        }
    }

    /**
     * One run of unstyled text as a line, for the draws that take a caller's characters as they
     * arrived rather than as a format string.
     */
    private static @NotNull LineSegment plain(@NotNull String text) {
        return LineSegment.builder().segments(Concurrent.newList(TextSegment.literal(text))).build();
    }

}
