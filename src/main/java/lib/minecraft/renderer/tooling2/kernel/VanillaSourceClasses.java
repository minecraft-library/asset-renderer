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

    }

    /** Member-name pattern grammar [C3]: createRoots, addBox, texOffs, scaling, ... */
    public static final class Methods {

        private Methods() {
        }

    }

    /** Descriptor composer [C4] - no hand-assembled descriptors survive anywhere. */
    public static final class Descs {

        private Descs() {
        }

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
