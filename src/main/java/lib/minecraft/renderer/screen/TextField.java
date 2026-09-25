package lib.minecraft.renderer.screen;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.data.StaticImageData;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.engine.frame.FrameLayer;
import lib.minecraft.renderer.engine.frame.FramePlacement;
import lib.minecraft.renderer.engine.layer.LayerStack;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.request.MenuOptions;
import lib.minecraft.renderer.slot.MenuSlot;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The widget an editable screen field is: what the caller's text cuts down to, the tail of it the
 * well shows, and the caret marking where typing would continue.
 * <p>
 * The field's well is chrome and its text is a render, the same split a button's face takes: the
 * {@link Window} sinks the well in its own inks and this puts the text in it.
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
     * The insert caret's own extent - one Minecraft pixel wide, opening a pixel above the glyph
     * cells and running four below them, which is the bar vanilla fills rather than a glyph.
     */
    private static final int CARET_RISE = 1, CARET_HEIGHT = 11;

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
     * @param stack the layer stack to append to
     */
    public static void placeFieldText(
        @NotNull MenuOptions options,
        @NotNull MenuLayout layout,
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

        PixelBuffer buffer = PixelBuffer.create(layout.width() * PX_SCALE, layout.height() * PX_SCALE);
        MinecraftGraphics g = new MinecraftGraphics(buffer);
        int textX = field.get().x() + well.inset().x();
        int textY = field.get().y() + well.inset().y();
        int baseline = textY + MinecraftFont.Vanilla.REGULAR.metrics().getAscentMcPixels();
        int drawn = shown.isEmpty() ? 0
            : TextKit.drawLine(g, plain(shown), textX, baseline, well.argb(), 0L, 0L, true);

        if (options.isCaret()) drawCaret(g, buffer, typed, textX + drawn, textY, baseline, well);

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
     * Draws the caret in whichever of its two forms the text's length selects.
     * <p>
     * Vanilla appends an underscore glyph after the text, and stops being able to once the field is
     * full - there is no position past the last character to append at - so at the cap it fills a
     * bar beside it instead. The glyph carries the text's drop shadow and the bar carries none,
     * being a fill rather than a draw.
     */
    private static void drawCaret(
        @NotNull MinecraftGraphics g, @NotNull PixelBuffer buffer,
        String typed, int caretX, int textY, int baseline, @NotNull Mark.TextWell well
    ) {
        if (typed.length() < well.maxLength()) {
            TextKit.drawLine(g, plain("_"), caretX, baseline, well.argb(), 0L, 0L, true);
            return;
        }

        for (int y = 0; y < CARET_HEIGHT * PX_SCALE; y++) {
            int py = (textY - CARET_RISE) * PX_SCALE + y;
            if (py < 0 || py >= buffer.height()) continue;

            for (int x = 0; x < PX_SCALE; x++) {
                int px = caretX * PX_SCALE + x;
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
        return LineSegment.builder().withSegments(new ColorSegment(text)).build();
    }

}
