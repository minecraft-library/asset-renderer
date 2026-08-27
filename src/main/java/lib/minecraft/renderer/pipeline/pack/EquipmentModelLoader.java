package lib.minecraft.renderer.pipeline.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.PackStack;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.equipment.EquipmentModel;
import lib.minecraft.renderer.asset.equipment.LayerType;
import lib.minecraft.renderer.client.VanillaSourcePaths;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses each pack's {@code assets/<ns>/equipment/*.json} into an asset-id-keyed index for the
 * data-driven equipment texture-resolution surface. Unlike the concatenating atlas sources, equipment
 * files <b>replace</b> per asset id across the stack ascending, so a higher pack's {@code iron.json}
 * shadows the vanilla one wholesale (matching vanilla's per-file asset override).
 *
 * <p>Not a direct {@link EquipmentModel} deserialize: Gson keys an enum map by constant name, while the
 * json keys the layer map by the serialized id, so the file is read into a String-keyed
 * {@link RawEquipmentFile} and assembled through {@link LayerType#findById} - the same raw-then-assemble
 * split the entity model loader uses. A vanilla-only stack yields the 45 vanilla equipment assets.
 *
 * <p><b>The index reports what it parsed and applies no default.</b> An id the stack ships no file for
 * is simply absent; turning that absence into {@link EquipmentModel#MISSING} is
 * {@code RendererContext.resolveEquipmentLayers}' job, at the one seam that serves the index. So the
 * map is ordered for determinism rather than for lookup, and iteration order never reaches a render.
 *
 * <p><b>Parity.</b> Reached only across the pipeline context, which is wiring, so no producer root
 * reaches it. It loads the worn-equipment models, which only a wearer draws.
 */
@UtilityClass
@Parity(subject = {Subject.ENTITY, Subject.PLAYER})
public class EquipmentModelLoader {

    private static final @NotNull Gson GSON = GsonSettings.defaults().create();

    /** The equipment-asset subtree every pack in the stack contributes. */
    private static final @NotNull PackSubtree.Subtree EQUIPMENT =
        PackSubtree.Subtree.of(VanillaSourcePaths.EQUIPMENT_SUBDIR, ".json");

    /** The asset-id ordering the index is keyed under, so a stack iterates its ids sorted. */
    private static final @NotNull Comparator<ResourceId> BY_ASSET_ID = Comparator.comparing(ResourceId::id);

    /**
     * Loads the equipment-asset index across the whole pack stack, ascending (later packs replace
     * earlier per asset id).
     *
     * @param stack the resolved pack stack
     * @return the equipment-asset index, deterministically ordered by asset id
     */
    public static @NotNull ConcurrentMap<ResourceId, EquipmentModel> load(@NotNull PackStack stack) {
        return PackSubtree.walk(stack, EQUIPMENT)
            .stream()
            .map(EquipmentModelLoader::parseFile)
            .flatMap(Optional::stream)
            .collect(Concurrent.toTreeMap(BY_ASSET_ID, Map.Entry::getKey, Map.Entry::getValue, (lower, higher) -> higher))
            .toUnmodifiable();
    }

    /** Parses one equipment file into its model, keyed {@code <namespace>:<stem>}. */
    private static @NotNull Optional<Map.Entry<ResourceId, EquipmentModel>> parseFile(@NotNull PackSubtree.Entry entry) {
        String namespace = entry.namespace();
        String file = entry.entryPath();
        Optional<byte[]> bytes = entry.container().bytes(file);
        if (bytes.isEmpty()) return Optional.empty();
        String stem = file.substring(file.lastIndexOf('/') + 1, file.length() - ".json".length());
        return parse(new String(bytes.get(), StandardCharsets.UTF_8))
            .map(model -> Map.entry(new ResourceId(namespace, stem), model));
    }

    /**
     * Parses one equipment file's json into a model, empty on malformed input or an absent
     * {@code layers} map. Package-private for the loader unit tests; production goes through
     * {@link #load}.
     *
     * @param json the equipment file json
     * @return the parsed model, or empty when malformed or layer-less
     */
    static @NotNull Optional<EquipmentModel> parse(@NotNull String json) {
        try {
            RawEquipmentFile raw = GSON.fromJson(json, RawEquipmentFile.class);
            if (raw == null || raw.layers() == null) return Optional.empty();
            return Optional.of(assemble(raw));
        } catch (JsonSyntaxException ex) {
            System.err.printf("Skipping malformed equipment model: %s%n", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Assembles the String-keyed raw file into the enum-keyed model, dropping unknown layer types. */
    private static @NotNull EquipmentModel assemble(@NotNull RawEquipmentFile raw) {
        Map<LayerType, ConcurrentList<EquipmentModel.Layer>> layers = new EnumMap<>(LayerType.class);
        raw.layers().forEach((key, rawLayers) -> {
            if (rawLayers == null) return;
            Optional<LayerType> type = LayerType.findById(key);
            if (type.isEmpty()) {
                System.err.printf("Skipping unknown equipment layer type '%s'%n", key);
                return;
            }
            ConcurrentList<EquipmentModel.Layer> built = rawLayers.stream()
                .filter(rawLayer -> rawLayer != null && rawLayer.texture() != null)
                .map(EquipmentModelLoader::toLayer)
                .collect(Concurrent.toUnmodifiableList());
            if (!built.isEmpty()) layers.put(type.get(), built);
        });
        // Linked rather than hashed so the model iterates its layer types in the order the EnumMap
        // hands them over in, which is the enum's own.
        return new EquipmentModel(Concurrent.newUnmodifiableLinkedMap(layers));
    }

    /** Builds one runtime {@link EquipmentModel.Layer} from its raw json shape. */
    private static @NotNull EquipmentModel.Layer toLayer(@NotNull RawLayer rawLayer) {
        Optional<EquipmentModel.Dyeable> dyeable = rawLayer.dyeable() == null
            ? Optional.empty()
            : Optional.of(new EquipmentModel.Dyeable(Optional.ofNullable(rawLayer.dyeable().colorWhenUndyed())));
        return new EquipmentModel.Layer(ResourceId.parse(rawLayer.texture()), dyeable, rawLayer.usePlayerTexture());
    }

    /** One equipment file's payload: the {@code layers} map keyed by serialized layer-type id. */
    record RawEquipmentFile(@Nullable Map<String, List<RawLayer>> layers) {}

    /** One {@code layers[]} entry: the texture stem, optional dye, and the player-texture flag. */
    record RawLayer(@Nullable String texture,
                    @Nullable RawDyeable dyeable,
                    @SerializedName("use_player_texture") boolean usePlayerTexture) {}

    /** The {@code dyeable} object: the optional undyed fallback colour ({@code {}} means dyed-only). */
    record RawDyeable(@SerializedName("color_when_undyed") @Nullable Integer colorWhenUndyed) {}

}
