package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.ZombieVillagerRenderer;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins every harness-rendered zombie villager's {@link ZombieVillagerRenderState#villagerData
 * villagerData} to the {@link Villager#createDefaultVillagerData defaultVillagerData}
 * (type {@code PLAINS}, profession {@code NONE}, level {@code 1}), gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * {@link ZombieVillager#initializeVillagerData} randomly assigns a profession via
 * {@code BuiltInRegistries.VILLAGER_PROFESSION.getRandom(this.random)} and derives the type
 * from the spawn biome via {@code VillagerType.byBiome(level.getBiome(blockPosition))}. The
 * transient {@code EntityType.create} zombie villager the harness spawns ends up with a
 * different {@link net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer
 * VillagerProfessionLayer} render every run - sometimes farmer (straw hat), sometimes
 * librarian (book), sometimes butcher (apron), etc. This non-determinism breaks
 * byte-stability of {@code zombie_villager.png} across renders AND prevents the
 * asset-renderer pipeline from reproducing the harness output: asset-renderer pins the
 * type=plains overlay (matching {@link Villager#createDefaultVillagerData Villager}'s
 * documented default for the non-zombie variant) but can't predict which random profession
 * the harness rolled.
 *
 * <p>Fix: at {@code extractRenderState} RETURN, overwrite {@code state.villagerData} with
 * {@link Villager#createDefaultVillagerData} - the SAME default the regular {@link Villager}
 * exposes at spawn before any biome / profession assignment runs. {@link
 * net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer VillagerProfessionLayer}
 * then renders only the {@code type/plains} texture pass and skips the profession +
 * profession-level passes (NONE profession bails on the {@code !profession.is(NONE)} gate).
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when asset-renderer gains profession-overlay rendering AND a way to pick which
 * profession to mirror</b> - at that point the harness's random profession could be matched
 * by the asset-renderer's per-entity texture override.
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins so non-harness
 * consumers of this jar keep vanilla zombie-villager profession randomisation intact.
 *
 * @see PhantomStateMixin same {@code @At("RETURN")} pattern - subclass writes villagerData
 *     after super.extractRenderState, so we inject at the subclass return point.
 */
@Mixin(ZombieVillagerRenderer.class)
public abstract class ZombieVillagerStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/zombie/ZombieVillager;Lnet/minecraft/client/renderer/entity/state/ZombieVillagerRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$pinDefaultVillagerData(ZombieVillager entity, ZombieVillagerRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.villagerData = Villager.createDefaultVillagerData();
    }
}
