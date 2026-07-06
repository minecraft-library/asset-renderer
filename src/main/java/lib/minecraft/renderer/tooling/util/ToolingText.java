package lib.minecraft.renderer.tooling.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Small naming-convention text helpers shared by the tooling resolvers. These carry no bytecode
 * knowledge, so they deliberately do not live in {@link AsmKit} (whose charter is ASM primitives
 * only).
 *
 * <p><b>Interim home.</b> The {@code dev.simplified.util.StringUtil} dependency exposes
 * {@code toCamelCase} but no snake-case converter, and its {@code splitByCharacterTypeCamelCase}
 * handles acronyms / digits differently, so it is not a drop-in for {@link #camelToSnake}. Adding a
 * {@code toSnakeCase} to {@code StringUtil} would mean re-pinning its jitpack snapshot across the
 * whole codebase; until that cleanup happens these helpers live here.
 * TODO: upstream {@code toSnakeCase} into {@code StringUtil} and delete this class at the next re-pin.
 */
@UtilityClass
public final class ToolingText {

    /**
     * Converts a camelCase / PascalCase identifier to snake_case by lower-casing each uppercase
     * letter and prefixing interior ones with an underscore ({@code "ColdCow"} -&gt;
     * {@code "cold_cow"}, {@code "rightArm"} -&gt; {@code "right_arm"}, {@code "cow"} -&gt;
     * {@code "cow"}). A leading uppercase letter is lower-cased without a leading underscore; digits
     * and existing underscores pass through unchanged.
     *
     * @param camel the camelCase or PascalCase identifier
     * @return the snake_case form
     */
    public static @NotNull String camelToSnake(@NotNull String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

}
