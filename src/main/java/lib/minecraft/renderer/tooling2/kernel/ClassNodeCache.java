package lib.minecraft.renderer.tooling2.kernel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The SOLE {@link ZipFile} owner of tooling2 - class loads plus the jar resource API whose
 * absence caused every legacy cache bypass (SPINE decision 3).
 *
 * <p>One cache instance is bound to one jar for its lifetime, opened by
 * {@code ToolingPipeline.openSession} and closed by {@code ToolingSession.close()}. No
 * {@code zip()} accessor exists and {@code java.util.zip} imports are illegal outside this
 * class - flows and resolvers see classes and resources only through this surface.
 *
 * <p>ONE error contract everywhere: a missing class or entry is {@code null} (absence is
 * cached via the {@link #MISSING} sentinel so repeat lookups never re-touch the jar),
 * {@link #require} throws, and any IO failure is a {@link ToolingException} - a cache-backed
 * read hitting IO is a real failure, never an absence.
 *
 * <p>The node map is a {@link ConcurrentHashMap} keyed by internal name; concurrent callers
 * race on first load but later readers see one stable {@link ClassNode} per key. ASM's
 * {@code ClassNode} is mutable but the tooling convention is read-only access, so handing
 * the same node to multiple readers is safe under that convention.
 */
public final class ClassNodeCache implements AutoCloseable {

    /** JVM class-file entry suffix inside the jar. */
    private static final @NotNull String CLASS_SUFFIX = ".class";

    /**
     * Sentinel stored under keys whose class is not in the jar, distinguishable by identity so
     * {@code computeIfAbsent} records absence without re-running the loader on the next lookup.
     * Never escapes the cache - callers receive {@code null}.
     */
    private static final @NotNull ClassNode MISSING = new ClassNode();

    private final @NotNull ZipFile zip;
    private final @NotNull ConcurrentMap<String, ClassNode> nodes = new ConcurrentHashMap<>();

    private ClassNodeCache(@NotNull ZipFile zip) {
        this.zip = zip;
    }

    /**
     * Opens a cache over the given jar - THE only {@code new ZipFile} in tooling2.
     *
     * @param jar the client-jar path
     * @return the cache bound to the opened jar
     * @throws ToolingException if the jar cannot be opened
     */
    public static @NotNull ClassNodeCache open(@NotNull Path jar) {
        try {
            return new ClassNodeCache(new ZipFile(jar.toFile()));
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to open client jar '%s'", jar);
        }
    }

    /**
     * Returns the {@link ClassNode} for {@code internalName}, loading and caching the parse on
     * first lookup. The ASM parse flags ({@code SKIP_DEBUG | SKIP_FRAMES}) are declared once,
     * here. Returns {@code null} when the jar has no matching entry; absence is also cached.
     *
     * @param internalName the class's JVM internal name (e.g. {@code net/minecraft/X})
     * @return the cached or freshly-parsed node, or {@code null} when absent from the jar
     * @throws ToolingException if the entry exists but cannot be read
     */
    public @Nullable ClassNode load(@NotNull String internalName) {
        ClassNode cached = this.nodes.computeIfAbsent(internalName, key -> {
            ZipEntry entry = this.zip.getEntry(key + CLASS_SUFFIX);
            if (entry == null) return MISSING;
            try (InputStream stream = this.zip.getInputStream(entry)) {
                ClassNode classNode = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return classNode;
            } catch (IOException ex) {
                throw new ToolingException(ex, "Failed to read class '%s' from jar", key);
            }
        });
        return cached == MISSING ? null : cached;
    }

    /**
     * Like {@link #load} but throws when the class is missing, for callers whose walk cannot
     * continue without it.
     *
     * @param internalName the class's JVM internal name
     * @param context a short label identifying the caller in the error message
     * @return the populated node
     * @throws ToolingException if the class is not in the jar or cannot be read
     */
    public @NotNull ClassNode require(@NotNull String internalName, @NotNull String context) {
        ClassNode classNode = load(internalName);
        if (classNode == null)
            throw new ToolingException("Missing class '%s' (%s)", internalName, context);
        return classNode;
    }

    /**
     * Returns whether the jar contains an entry at {@code entryPath} - the existence probe
     * behind {@code %}-template skips and {@code _baby} sibling checks.
     *
     * @param entryPath the jar entry path (e.g. {@code assets/minecraft/textures/entity/wolf/wolf.png})
     * @return {@code true} when the entry exists
     */
    public boolean hasEntry(@NotNull String entryPath) {
        return this.zip.getEntry(entryPath) != null;
    }

    /**
     * Lists jar entries starting with {@code prefix} and ending with {@code suffix}, in zip
     * directory order (stable for a given jar - callers sort when order is contractual).
     * Directory entries are skipped.
     *
     * @param prefix the required entry-path prefix
     * @param suffix the required entry-path suffix (e.g. {@code .json}, {@code .class})
     * @return the matching entry paths in zip directory order
     */
    public @NotNull List<String> list(@NotNull String prefix, @NotNull String suffix) {
        List<String> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = this.zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            if (name.startsWith(prefix) && name.endsWith(suffix)) out.add(name);
        }
        return out;
    }

    /**
     * Reads a jar entry's raw bytes (colormap PNGs and friends), or {@code null} when the
     * entry is absent.
     *
     * @param entryPath the jar entry path
     * @return the entry bytes, or {@code null} when absent
     * @throws ToolingException if the entry exists but cannot be read
     */
    public byte @Nullable [] readBytes(@NotNull String entryPath) {
        ZipEntry entry = this.zip.getEntry(entryPath);
        if (entry == null) return null;
        try (InputStream stream = this.zip.getInputStream(entry)) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to read entry '%s' from jar", entryPath);
        }
    }

    /**
     * Reads and parses a JSON jar entry (variant / item-model / blockstate JSONs), or
     * {@code null} when the entry is absent.
     *
     * @param entryPath the jar entry path
     * @return the parsed node, or {@code null} when absent
     * @throws ToolingException if the entry exists but cannot be read or parsed
     */
    public @Nullable JsonNode readJson(@NotNull String entryPath) {
        byte[] bytes = readBytes(entryPath);
        return bytes == null ? null : JsonNode.parse(bytes);
    }

    /**
     * The number of distinct internal names looked up since open (including misses).
     * Diagnostics only.
     */
    public int size() {
        return this.nodes.size();
    }

    @Override
    public void close() {
        try {
            this.zip.close();
        } catch (IOException ex) {
            throw new ToolingException(ex, "Failed to close client jar");
        }
    }

}
