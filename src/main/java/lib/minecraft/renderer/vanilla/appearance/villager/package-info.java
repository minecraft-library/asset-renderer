/**
 * The villager's three rosters - the biome type, the profession and the trade level its clothing
 * passes are chosen by.
 *
 * <p>{@link lib.minecraft.renderer.vanilla.appearance.villager.VillagerType VillagerType} is the seven
 * biome robes the base clothing pass draws, with an adult and a baby sub-path.
 * {@link lib.minecraft.renderer.vanilla.appearance.villager.VillagerProfession VillagerProfession} is
 * the professions whose clothes and hat draw over the robe - {@code NONE} draws nothing, and neither it
 * nor {@code NITWIT} wears a badge.
 * {@link lib.minecraft.renderer.vanilla.appearance.villager.VillagerLevel VillagerLevel} is the five
 * badge tiers, the first being the floor vanilla clamps every level to. Each answers a sub-path relative
 * to the entity's texture prefix, so one roster serves both the villager and the zombie villager.
 *
 * <p>A fourth roster does not belong here. The axes a villager shares with other subjects, its age
 * among them, are {@link lib.minecraft.renderer.vanilla.appearance appearance}'s.
 */
package lib.minecraft.renderer.vanilla.appearance.villager;
