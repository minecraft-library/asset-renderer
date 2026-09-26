package lib.minecraft.renderer.content.client;

import api.simplified.mojang.MojangContract;
import api.simplified.mojang.request.MojangDomain;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

/**
 * The Mojang texture download a player render reads a URL-sourced skin, cape or elytra sheet through.
 */
@UtilityClass
@Parity(claim = "engine-renders", mode = Mode.DEMOTE)
@Parity(claim = "player-geometry")
public class SkinFetch {

    /**
     * Reads a Mojang skin or cape texture by extracting the trailing path segment from the URL
     * (the texture hash) and streaming the PNG bytes through {@link ClientAcquisition#mojang() ClientAcquisition.mojang()}'s
     * {@link MojangContract#downloadTexture(String) downloadTexture}.
     * <p>
     * The URL format is the {@code http://textures.minecraft.net/texture/<hash>} pattern Mojang's
     * session API returns in {@code MojangProperties}; the routing and rate limiting come from
     * the contract's {@link MojangDomain#MINECRAFT_TEXTURES} entry.
     *
     * @param url the Mojang texture URL to read
     * @return the PNG bytes the URL serves
     * @throws ClientException if the texture cannot be streamed
     */
    public static byte @NotNull [] fetchTexture(@NotNull String url) {
        String hash = url.substring(url.lastIndexOf('/') + 1);
        try (InputStream stream = ClientAcquisition.mojang().downloadTexture(hash)) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new ClientException(ex, "Failed to fetch texture from '%s'", url);
        }
    }

}
