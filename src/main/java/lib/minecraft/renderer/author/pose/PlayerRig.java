package lib.minecraft.renderer.author.pose;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.asset.pose.EntityPose;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The player's row source - synthesizes the {@code minecraft:player} definition the shipped
 * tables never carry, so the player renders, poses and takes installed styles through the
 * entity path as a carried peer of shipped rows.
 *
 * <p>The row's mesh is the wide-arm humanoid geometry as resolved on the shipped zombie row -
 * the canonical seven bones ({@code head}, {@code hat}, {@code body}, two arms, two legs) -
 * rebuilt over a fresh bone map so the rig never aliases the shipped instance. The row carries
 * {@link StyleCatalog#BIND_ONLY} and {@link EntityPose#NONE}: no stride or idle expression
 * exists on it, so an installed style's absolute writes rebase against the authored mesh alone
 * and rest is the authored bind pose.
 *
 * <p>The state axis declares the one state every definition rests in, mapping the shipped wide
 * Steve ref - a bare render resolves {@code minecraft:entity/player/wide/steve}, the sheet the
 * vanilla pack ships. A caller's own skin registers through {@link StyleRegistrar#skin(byte[])}
 * and answers under {@link #SKIN_TEXTURE_ID} instead. Either way the sheet owed is the modern
 * 64x64 layout, whose head, hat and limb origins are the ones the mesh's cube UVs normalize
 * against; only the hat draws a second layer, the humanoid mesh carrying no jacket, sleeve or
 * pants bones, and the slim 3px-arm mesh has no row here. A skin is a texture image, never a
 * profile - resolving a username or UUID to a sheet is the caller's, on the far side of the
 * options bag.
 *
 * <p>Registration is ordinary - the rig is a row like any other, put into a caller-copied
 * definitions map before the registrar opens over it, and every style a humanoid row takes
 * installs on it unchanged.
 */
@Parity(subject = Subject.ENTITY)
public final class PlayerRig {

    /**
     * The namespaced id the synthesized row registers and renders under.
     */
    public static final @NotNull String ENTITY_ID = "minecraft:player";

    /**
     * The state-axis ref a registered caller skin rides - {@code $}-marked like every coined
     * identifier, so no pack id can collide with it, vanilla resource paths carrying no
     * {@code $}.
     */
    public static final @NotNull String SKIN_REF = "player/style$skin";

    /**
     * The reserved texture id a registered caller skin answers under - {@link #SKIN_REF}
     * qualified exactly as the render qualifies every state-axis ref.
     */
    public static final @NotNull String SKIN_TEXTURE_ID = "minecraft:entity/" + SKIN_REF;

    /**
     * The shipped ref a bare render resolves - the modern wide-arm Steve sheet.
     */
    static final @NotNull String STEVE_REF = "player/wide/steve";

    /**
     * The shipped row whose resolved geometry is the wide-arm humanoid mesh.
     */
    private static final @NotNull String MESH_SOURCE_ID = "minecraft:zombie";

    private PlayerRig() {}

    /**
     * Synthesizes the player row - a copy of the wide-arm humanoid mesh under the bind-only
     * catalog and the empty pose, the state axis declaring the shipped Steve ref. Each call
     * answers a fresh row, so no two definition maps share one.
     *
     * @return the synthesized {@code minecraft:player} row
     * @throws IllegalStateException if the shipped tables carry no row to copy the mesh from
     */
    public static @NotNull Entity entityRow() {
        Entity source = EntityModelLoader.load().get(MESH_SOURCE_ID);
        if (source == null)
            throw new IllegalStateException(String.format(
                "The shipped tables carry no '%s' row, so no wide-arm humanoid mesh exists to copy", MESH_SOURCE_ID));
        return Entity.builder()
            .id(ResourceId.parse(ENTITY_ID))
            .model(copied(source.model()))
            .overlays(Concurrent.newUnmodifiableList())
            .blockOverlays(Concurrent.newUnmodifiableList())
            .baseTintArgb(0xFFFFFFFF)
            .rendererScale(1f)
            .axes(new Entity.Axes(Optional.empty(), Optional.empty(), Concurrent.newUnmodifiableList(),
                Entity.Axis.none(), steveState(), Entity.Axis.none(), Entity.Axis.none()))
            .layers(new Entity.Layers(Concurrent.newUnmodifiableList(), Optional.empty()))
            .members(Concurrent.newUnmodifiableList())
            .styles(StyleCatalog.BIND_ONLY)
            .pose(EntityPose.NONE)
            .build();
    }

    /**
     * The same geometry over a fresh bone map, so the rig never aliases the shipped mesh instance.
     */
    private static @NotNull EntityModelData copied(@NotNull EntityModelData model) {
        return new EntityModelData(model.getTextureSize(),
            Concurrent.adoptLinkedMap(new LinkedHashMap<>(model.getBones())), model.isCull());
    }

    /**
     * The one-state axis every bare render reads - the base state declaring the Steve ref.
     */
    private static @NotNull Entity.Axis<String, String> steveState() {
        return new Entity.Axis<>(
            Concurrent.newUnmodifiableMap(Map.of(Entity.BASE_STATE, STEVE_REF)),
            Optional.of(Entity.BASE_STATE));
    }

}
