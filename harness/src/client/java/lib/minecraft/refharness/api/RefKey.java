package lib.minecraft.refharness.api;

import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A rendered subject's output name, held as its parts rather than built by string concatenation.
 *
 * <p>The rendered form is {@code <namespace>__<base>} followed by one {@code _<qualifier>} per
 * qualifier, then one {@code ~<axis>=<value>} per token, or a bare {@code <base>} when there is no
 * namespace. Qualifiers are ordered and are appended in order, because a name can carry more than
 * one and their order is part of the name.
 *
 * <p>Tokens are different: they are emitted in ascending order of the whole {@code axis=value} text,
 * compared as bytes, whatever order they were added in. One rule and no table, so this writer and the
 * reader on the asset-renderer side cannot drift apart on ordering - and an appearance built from an
 * unordered set or map still has exactly one spelling.
 *
 * @param directories directory segments below the sweep's own output directory, outermost first
 * @param namespace the identifier namespace, or empty for a name with no namespace
 * @param base the name's stem
 * @param qualifiers the ordered suffixes appended to the stem
 * @param tokens the {@code axis=value} selections, emitted in ascending byte order
 */
public record RefKey(List<String> directories, Optional<String> namespace,
                     String base, List<String> qualifiers, List<String> tokens) {

    /** Canonicalises the lists so a key is immutable and comparable by value. */
    public RefKey {
        directories = List.copyOf(directories);
        qualifiers = List.copyOf(qualifiers);
        tokens = List.copyOf(tokens);
    }

    /**
     * Returns the key for an identifier - {@code <namespace>__<path>}.
     *
     * @param id the subject's registry identifier
     * @return the key
     */
    public static RefKey of(Identifier id) {
        return new RefKey(List.of(), Optional.of(id.getNamespace()), id.getPath(), List.of(), List.of());
    }

    /**
     * Returns a key with no namespace, which renders as the bare base name.
     *
     * @param base the name's stem
     * @return the key
     */
    public static RefKey named(String base) {
        return new RefKey(List.of(), Optional.empty(), base, List.of(), List.of());
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
        return new RefKey(directories, namespace, base, extended, tokens);
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
        return new RefKey(nested, namespace, base, qualifiers, tokens);
    }

    /**
     * Returns this key carrying one more appearance selection.
     *
     * @param axis the axis being selected
     * @param value the option selected
     * @return the extended key
     */
    public RefKey token(String axis, String value) {
        List<String> extended = new ArrayList<>(tokens);
        extended.add(axis + "=" + value);
        return new RefKey(directories, namespace, base, qualifiers, extended);
    }

    /** Returns the file name without its extension. */
    public String fileName() {
        StringBuilder name = new StringBuilder();
        namespace.ifPresent(ns -> name.append(ns).append("__"));
        name.append(base);
        for (String qualifier : qualifiers) name.append('_').append(qualifier);
        for (String token : tokens.stream().sorted().toList()) name.append('~').append(token);
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
