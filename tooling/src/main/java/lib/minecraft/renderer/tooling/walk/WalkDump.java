package lib.minecraft.renderer.tooling.walk;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * The engine's state dump - a per-cascade-event emission armed by {@code -Dasset.walk.dump}.
 * This is deliberately not a diagnostics channel: nothing here appends a diagnostics entry, so
 * the strict gate's counts and the flow log are untouched by construction. The property value
 * names a file to append to; any other non-empty arming value ({@code stderr}, {@code true})
 * emits to stderr. One line per event, carrying the event kind, the current node, the firing
 * stage and every installed cell's content.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class WalkDump {

    /** The arming property, in the auto-forwarded {@code asset.*} namespace. */
    static final @NotNull String PROPERTY = "asset.walk.dump";

    /** Test seam - replaces the emission target; {@code null} restores property-driven emission. */
    static @Nullable Consumer<String> sink;

    private final @NotNull Consumer<String> target;

    /**
     * The dump for one run, or {@code null} when unarmed. The property is read once here, so an
     * unarmed walk carries no per-element check beyond a null test.
     *
     * @return the armed dump, or {@code null}
     */
    static @Nullable WalkDump armed() {
        if (sink != null) return new WalkDump(sink);
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isEmpty()) return null;
        if ("stderr".equals(value) || "true".equals(value)) return new WalkDump(System.err::println);
        Path path = Path.of(value);
        return new WalkDump(line -> {
            try {
                Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The dump is an inspection aid; a failed write must not alter the walk.
            }
        });
    }

    void claim(@NotNull AbstractInsnNode node, @NotNull String stage, @NotNull String cells) {
        emit("claim", node, stage, cells);
    }

    void commit(@NotNull AbstractInsnNode node, @NotNull String stage, @NotNull String cells) {
        emit("commit", node, stage, cells);
    }

    void reset(@NotNull AbstractInsnNode node, @NotNull String stage, @NotNull String cells) {
        emit("reset", node, stage, cells);
    }

    void fallThrough(@NotNull AbstractInsnNode node, @NotNull String cells) {
        emit("fall-through", node, "-", cells);
    }

    private void emit(@NotNull String event, @NotNull AbstractInsnNode node, @NotNull String stage, @NotNull String cells) {
        this.target.accept(event + " op=" + node.getOpcode() + " node=" + node.getClass().getSimpleName()
            + " stage=" + stage + " " + cells);
    }

}
