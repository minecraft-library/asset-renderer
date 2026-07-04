package lib.minecraft.renderer.tooling.util;

import com.google.gson.Gson;
import dev.simplified.gson.GsonSettings;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

/**
 * Shared Gson instances for the tooling JSON writers - one place to keep the on-disk formatting
 * identical across every generated {@code renderer/*.json} resource.
 */
@UtilityClass
public class ToolingJson {

    /**
     * Pretty-printing Gson with HTML escaping disabled, carrying the renderer's registered type
     * adapters. Every tooling writer that rewrites a generated resource shares this single instance so
     * their formatting (indentation, {@code <}/{@code >}/{@code &} escaping) can never drift apart.
     */
    public static final @NotNull Gson PRETTY =
        GsonSettings.defaults().mutate().isPrettyPrint().isHtmlEscaping(false).build().create();

    /**
     * Pretty-printing Gson leaving Gson's default HTML-safe escaping ON (writes {@code <}/{@code >}/
     * {@code &}/{@code =}/{@code '} as {@code \\uXXXX}). Used by the diagnostic writers, whose output
     * is scratch (not a bundled resource); kept distinct from {@link #PRETTY} so their escaping stays
     * unchanged.
     */
    public static final @NotNull Gson PRETTY_HTML_SAFE =
        GsonSettings.defaults().mutate().isPrettyPrint().build().create();

}
