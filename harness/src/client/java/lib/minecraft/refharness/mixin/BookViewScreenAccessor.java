package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker of the book screen's own drawing of a page's text, which lets a sweep draw a page without
 * its page number.
 *
 * <p>The visit is private to {@code BookViewScreen}: the screen's state extract calls it to draw the
 * page, and its click handler to find the span under the pointer. The flag is the click handler's,
 * and the visit reads it at one place, the jump over the "Page N of M" line, so a visit handed a
 * drawing collector with the flag set draws every line of the page as the screen's own frame draws it
 * and the page number not at all. The invoker reads and changes nothing until it is called.
 */
@Mixin(BookViewScreen.class)
public interface BookViewScreenAccessor {

    /**
     * Visits the current page's text - the page-number line first unless {@code clickOnly} is set, then
     * each line the page wraps to, up to the fourteen a page holds.
     *
     * @param collector the collector the text is handed to
     * @param clickOnly whether to leave out the page-number line, which the client sets only to find a
     *     clicked span
     */
    @Invoker("visitText")
    void refharness$visitText(ActiveTextCollector collector, boolean clickOnly);
}
