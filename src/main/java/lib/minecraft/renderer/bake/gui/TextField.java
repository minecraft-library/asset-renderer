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
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The widget an editable screen field is: what the caller's text cuts down to, the tail of it the
 * well shows, and the caret marking where typing would continue.
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
     * The insert caret's own extent - one Minecraft pixel wide, opening a pixel above the glyph cell
     * and running two below its eight rows, which is the bar vanilla fills from {@code y - 1} to
     * {@code y + 10} rather than a glyph.
     */
    private static final int CARET_RISE = 1, CARET_HEIGHT = 11;

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
        String shown = visibleTail(typed, well.innerWidth());
        if (shown.isEmpty() && !options.isCaret()) return;

        PixelBuffer buffer = PixelBuffer.canvas(layout.width() * PX_SCALE, layout.height() * PX_SCALE);
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int penX = field.get().x() + well.inset().x();
        int textY = field.get().y() + well.inset().y();
        int baseline = textY + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        if (!shown.isEmpty()) {
            LineSegment text = plain(shown);
            TextKit.drawLine(g, text, penX, baseline, well.argb(), palette, 0, 0L, 0L, true);
            penX += TextKit.measureLineMcPixels(text) + PEN_STEP;
        }

        if (options.isCaret()) drawCaret(g, buffer, typed, penX, textY, baseline, well, palette);

        FramePlacement placement = new FramePlacement(0, 0, StaticImageData.of(buffer.toBufferedImage()));
        stack.append(MenuSlot.TEXT, sink -> sink.add(placement));
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
     * The part of the text a field shows - its longest tail that fits, because a field scrolls to
     * keep the end of what was typed in view rather than the beginning.
     *
     * @param typed what the field holds
     * @param innerWidth how wide the text may run, in Minecraft pixels
     * @return the visible tail, empty where even the last character does not fit
     */
    public static @NotNull String visibleTail(@NotNull String typed, int innerWidth) {
        for (int from = 0; from < typed.length(); from++) {
            String tail = typed.substring(from);
            if (TextKit.measureLineMcPixels(plain(tail)) <= innerWidth) return tail;
        }

        return "";
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

        for (int y = 0; y < CARET_HEIGHT * PX_SCALE; y++) {
            int py = (textY - CARET_RISE) * PX_SCALE + y;
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
