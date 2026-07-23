package lib.minecraft.refharness.api;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;

/**
 * Renders one subject kind to a PNG through the shared picture-in-picture target.
 *
 * @param <S> the subject this renderer draws - a block state, an item stack, an entity, a player
 *            scope
 */
public interface FrameRenderer<S> extends AutoCloseable {

    /**
     * Renders one subject and writes its PNG.
     *
     * @param client the running client
     * @param subject the subject to draw
     * @param canvas the canvas to draw onto
     * @param out the output path
     * @return whether a PNG was written; {@code false} means this renderer declined the subject and
     *         the caller may fall back to another
     * @throws IOException if the PNG write fails
     */
    boolean render(Minecraft client, S subject, Canvas canvas, Path out) throws IOException;

    @Override
    void close();
}
