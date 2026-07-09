package lib.minecraft.renderer.tooling2.kernel;

import org.jetbrains.annotations.NotNull;

/**
 * THE only home for vanilla class / ASM / member-name strings in tooling2 (SPINE decision 6) -
 * one class, nested static groups, populated per flow session as each stray literal migrates
 * in.
 *
 * <p>Stays elsewhere BY DESIGN: JVM/JDK-spec constants in {@link AsmKit} [C8], FastTrig
 * constants nested in the geometry parser [C10], the JOML/math surface in the transform
 * walker [C12] - none are vanilla names. Entity- and block-specific constants that are
 * DERIVABLE do not live here either - they are derivations or SPINE 2.1 policy rows.
 */
public final class VanillaSourceClasses {

    private VanillaSourceClasses() {
    }

    /** Vanilla class internal names + compositional package roots [C1] + migrated strays [C2]. */
    public static final class Types {

        private Types() {
        }

        /** The model package root - the P29 invokestatic-follow gate's positive side. */
        public static final @NotNull String CLIENT_MODEL_ROOT = "net/minecraft/client/model/";

        /** The geometry-primitive package root - the P29 gate's negative side (never followed). */
        public static final @NotNull String CLIENT_MODEL_GEOM_ROOT = CLIENT_MODEL_ROOT + "geom/";

        private static final @NotNull String MODEL_BUILDERS_ROOT = CLIENT_MODEL_GEOM_ROOT + "builders/";

        /** {@code PartPose} - pivot / rotation / scale factories. */
        public static final @NotNull String PART_POSE = CLIENT_MODEL_GEOM_ROOT + "PartPose";

        /** {@code PartNames} - the indexed bone-name helper class. */
        public static final @NotNull String PART_NAMES = CLIENT_MODEL_GEOM_ROOT + "PartNames";

        /** {@code LayerDefinition} - the mesh + texture-dimension carrier. */
        public static final @NotNull String LAYER_DEFINITION = MODEL_BUILDERS_ROOT + "LayerDefinition";

        /** {@code MeshTransformer} - layer-level scale / mesh-mutation wraps. */
        public static final @NotNull String MESH_TRANSFORMER = MODEL_BUILDERS_ROOT + "MeshTransformer";

        /** {@code CubeListBuilder} - the cube-chain builder. */
        public static final @NotNull String CUBE_LIST_BUILDER = MODEL_BUILDERS_ROOT + "CubeListBuilder";

        /** {@code CubeDeformation} - the inflate carrier. */
        public static final @NotNull String CUBE_DEFORMATION = MODEL_BUILDERS_ROOT + "CubeDeformation";

        /** {@code PartDefinition} - the bone-tree node. */
        public static final @NotNull String PART_DEFINITION = MODEL_BUILDERS_ROOT + "PartDefinition";

        /** {@code Mth} - vanilla's 65536-entry table trig. */
        public static final @NotNull String MTH = "net/minecraft/util/Mth";

        /** {@code RandomSource} - vanilla's seeded random factory. */
        public static final @NotNull String RANDOM_SOURCE = "net/minecraft/util/RandomSource";

    }

    /** Member-name pattern grammar [C3]: createRoots, addBox, texOffs, scaling, ... */
    public static final class Methods {

        private Methods() {
        }

        /** {@code MeshTransformer.scaling(F)} - the layer-scale factory. */
        public static final @NotNull String SCALING = "scaling";

    }

    /** Descriptor composer [C4] - no hand-assembled descriptors survive anywhere. */
    public static final class Descs {

        private Descs() {
        }

        /** The {@code MeshTransformer} field / return-type reference descriptor. */
        public static final @NotNull String MESH_TRANSFORMER_REF = ref(Types.MESH_TRANSFORMER);

        /**
         * Composes a method descriptor from already-valid type descriptors.
         *
         * @param ret the return-type descriptor ({@code V}, {@code F}, {@code Lx/Y;})
         * @param params the parameter-type descriptors in order
         * @return the composed method descriptor
         */
        public static @NotNull String of(@NotNull String ret, @NotNull String @NotNull ... params) {
            StringBuilder out = new StringBuilder("(");
            for (String param : params) out.append(param);
            return out.append(')').append(ret).toString();
        }

        /**
         * Wraps a class internal name as a reference-type descriptor ({@code L<name>;}).
         *
         * @param internalName the class internal name
         * @return the reference descriptor
         */
        public static @NotNull String ref(@NotNull String internalName) {
            return "L" + internalName + ";";
        }

    }

    /** Jar resource-path grammar [C5]: textures/entity/, data/minecraft/, equipment/, colormap/. */
    public static final class Paths {

        private Paths() {
        }

    }

    /** Vanilla data-schema keys [C6]: asset_id, spawn_conditions, minecraft:select, display.gui. */
    public static final class DataKeys {

        private DataKeys() {
        }

    }

    /** Shader defines [C7]: NO_CARDINAL_LIGHTING, TRANSLUCENT, withShaderDefine. */
    public static final class Defines {

        private Defines() {
        }

    }

}
