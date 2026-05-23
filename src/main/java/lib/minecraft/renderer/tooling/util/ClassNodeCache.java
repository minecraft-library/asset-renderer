package lib.minecraft.renderer.tooling.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipFile;

/**
 * Per-context cache of {@link ClassNode} parses keyed by JVM internal name. The tooling
 * entity pipeline re-resolves the same renderer / model / layer classes across multiple
 * resolver passes; without a cache every pass re-reads and re-parses the jar entry.
 *
 * <p>A {@link #MISSING} sentinel records "this class is not in the jar" so absent classes
 * also cache - the second lookup returns the sentinel instead of touching the jar again.
 * Callers receive {@code null} for missing classes (the sentinel never escapes the cache).
 *
 * <p>The map is a {@link ConcurrentHashMap} keyed by internal name; concurrent callers
 * race on first load but later readers see one stable {@code ClassNode} per key. ASM's
 * {@code ClassNode} is mutable but the tooling convention is read-only access, so handing
 * the same node to multiple readers is safe under that convention.
 */
public final class ClassNodeCache {

    /**
     * Sentinel stored under keys whose class is not in the jar. Distinguishable from a
     * normal {@link ClassNode} by identity so {@code computeIfAbsent} can record absence
     * without re-running the loader on the next lookup.
     */
    private static final @NotNull ClassNode MISSING = new ClassNode();

    private final @NotNull ConcurrentMap<String, ClassNode> nodes = new ConcurrentHashMap<>();

    /**
     * Returns the {@link ClassNode} for {@code internalName}, loading and caching the parse
     * on first lookup. Returns {@code null} when the jar has no matching entry; absence is
     * also cached so repeat lookups for missing classes do not re-touch the jar.
     *
     * @param zip the jar to load from on cache miss
     * @param internalName the class's JVM internal name (e.g. {@code net/minecraft/X})
     * @return the cached or freshly-parsed node, or {@code null} when absent from the jar
     */
    public @Nullable ClassNode load(@NotNull ZipFile zip, @NotNull String internalName) {
        ClassNode cached = this.nodes.computeIfAbsent(internalName, key -> {
            ClassNode loaded = AsmKit.loadClass(zip, key);
            return loaded != null ? loaded : MISSING;
        });
        return cached == MISSING ? null : cached;
    }

    /**
     * Returns the number of distinct internal names looked up since construction (including
     * misses). Intended for diagnostics / summary printing.
     */
    public int size() {
        return this.nodes.size();
    }

}
