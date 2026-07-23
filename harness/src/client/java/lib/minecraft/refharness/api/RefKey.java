package lib.minecraft.refharness.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * A rendered subject's output name, held as its parts rather than built by string concatenation.
 *
 * <p>The rendered form is {@code <namespace>__<base>} followed by one {@code _<qualifier>} per
 * qualifier, or a bare {@code <base>} when there is no namespace. Qualifiers are ordered and are
 * appended in order, because a name can carry more than one and their order is part of the name.
 *
 * @param directories directory segments below the sweep's own output directory, outermost first
 * @param namespace the identifier namespace, or empty for a name with no namespace
 * @param base the name's stem
 * @param qualifiers the ordered suffixes appended to the stem
 */
public record RefKey(List<String> directories, Optional<String> namespace,
                     String base, List<String> qualifiers) {

    /** Canonicalises both lists so a key is immutable and comparable by value. */
    public RefKey {
        directories = List.copyOf(directories);
        qualifiers = List.copyOf(qualifiers);
    }

    /**
     * Returns the key for an identifier - {@code <namespace>__<path>}.
     *
     * @param id the subject's registry identifier
     * @return the key
     */
    public static RefKey of(Identifier id) {
        return new RefKey(List.of(), Optional.of(id.getNamespace()), id.getPath(), List.of());
    }

    /**
     * Returns a key with no namespace, which renders as the bare base name.
     *
     * @param base the name's stem
     * @return the key
     */
    public static RefKey named(String base) {
        return new RefKey(List.of(), Optional.empty(), base, List.of());
    }

    /**
     * Returns this key with one more qualifier appended after any it already carries.
     *
     * @param qualifier the suffix to append, without its leading separator
     * @return the extended key
     */
    public RefKey with(String qualifier) {
        List<String> extended = new ArrayList<>(qualifiers);
        extended.add(qualifier);
        return new RefKey(directories, namespace, base, extended);
    }

    /**
     * Returns this key nested one directory deeper, inside {@code directory}.
     *
     * @param directory the directory segment to prepend
     * @return the nested key
     */
    public RefKey in(String directory) {
        List<String> nested = new ArrayList<>(directories);
        nested.add(directory);
        return new RefKey(nested, namespace, base, qualifiers);
    }

    /** Returns the file name without its extension. */
    public String fileName() {
        StringBuilder name = new StringBuilder();
        namespace.ifPresent(ns -> name.append(ns).append("__"));
        name.append(base);
        for (String qualifier : qualifiers) name.append('_').append(qualifier);
        return name.toString();
    }

    /**
     * Resolves this key to a PNG path below a sweep's output directory.
     *
     * @param sweepRoot the sweep's output directory
     * @return the path to write
     */
    public Path resolve(Path sweepRoot) {
        Path path = sweepRoot;
        for (String directory : directories) path = path.resolve(directory);
        return path.resolve(fileName() + ".png");
    }
}
