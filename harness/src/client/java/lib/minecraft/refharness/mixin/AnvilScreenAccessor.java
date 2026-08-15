package lib.minecraft.refharness.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Opens the anvil's name field so a sweep can type into it.
 *
 * <p>The field is built in {@code subInit} and kept private, there being no reason for anything
 * outside the screen to reach it. A menu subject that renders a named anvil has one: the text is the
 * subject, and every other route to it changes something else as well - putting a named item in the
 * input slot fills the slot and flips the field to its enabled sprite, which would make two subjects
 * differ in three ways rather than in the one being measured.
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
