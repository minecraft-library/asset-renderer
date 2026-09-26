/**
 * Vanilla's screen and tooltip measurements - the figures, in Minecraft pixels, the client lays its
 * GUI out by.
 *
 * <p>{@link lib.minecraft.renderer.vanilla.gui.ScreenMetrics ScreenMetrics} is where a container screen
 * puts its cells: the bands above and below its own grid, that grid's rows, columns and origin, where
 * its title starts, and the cells and marks it places by hand. Its factories are the shipped containers
 * measured against the client - chest, shulker box, hopper, dispenser, crafting table and anvil - and a
 * plain grid for a screen with no art of its own, and
 * {@link lib.minecraft.renderer.vanilla.gui.ScreenMetrics#layout(boolean) layout} turns one into every
 * cell's position, with or without the player's inventory below it.
 *
 * <p>A member that is not a measurement does not belong here. Painting a window and drawing a layout
 * onto a buffer are {@link lib.minecraft.renderer.screen screen}'s; this package only says where things
 * are.
 *
 * <p><b>Parity.</b> Every member declares its own claims.
 */
package lib.minecraft.renderer.vanilla.gui;
