package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the anvil's name field so a sweep can type into it, or silence it while it fills the slots.
 *
 * <p>The field is built in {@code subInit} and kept private, there being no reason for anything
 * outside the screen to reach it. Two kinds of menu subject have one. A named anvil types into it:
 * the text is the subject, and every other route to it changes something else as well - putting a
 * named item in the input slot fills the slot and flips the field to its enabled sprite, which would
 * make two subjects differ in three ways rather than in the one being measured. A filled anvil drops
 * its responder before it fills the slots: the screen names the field after the item placed in the
 * first input, and the responder would answer that by renaming through the menu, which recomputes
 * the menu's result, and by sending a rename packet.
 */
@Mixin(AnvilScreen.class)
public interface AnvilScreenAccessor {

    /**
     * The name field.
     *
     * @return the edit box the anvil renames through
     */
    @Accessor("name")
    EditBox refharness$name();
}
