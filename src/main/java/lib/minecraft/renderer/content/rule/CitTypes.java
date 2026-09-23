package lib.minecraft.renderer.content.rule;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.asset.rule.CitType;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.vanilla.equipment.LayerType;
import org.jetbrains.annotations.NotNull;

/**
 * The CIT grammar's reading of the vanilla equipment layers - which {@code type=} a resource pack
 * retextures a worn layer through.
 */
@UtilityClass
@Parity(claim = "pack-rule-layer")
@Parity(subject = {Subject.ENTITY, Subject.PLAYER})
public final class CitTypes {

    /**
     * The CIT retexture subject a resource pack addresses a layer through - {@code type=elytra} for the
     * wings and {@code type=armor} for the other eighteen, which is the only split OptiFine's own
     * {@code type=} vocabulary makes across the layers.
     *
     * @param layerType the equipment layer
     * @return the CIT subject a pack retextures the layer as
     */
    public static @NotNull CitType of(@NotNull LayerType layerType) {
        return layerType == LayerType.WINGS ? CitType.ELYTRA : CitType.ARMOR;
    }

}
