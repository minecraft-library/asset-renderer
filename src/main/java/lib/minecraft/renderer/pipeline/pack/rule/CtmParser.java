package lib.minecraft.renderer.pipeline.pack.rule;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.PackId;
import lib.minecraft.renderer.asset.pack.rule.BlockMatch;
import lib.minecraft.renderer.asset.pack.rule.CtmExtras;
import lib.minecraft.renderer.asset.pack.rule.CtmMethod;
import lib.minecraft.renderer.asset.pack.rule.CtmRule;
import lib.minecraft.renderer.asset.pack.rule.CtmTarget;
import lib.minecraft.renderer.asset.pack.rule.TileRef;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Parses one OptiFine / MCPatcher CTM {@code .properties} file into a {@link CtmRule}. Fully typed and
 * validated at parse yet never evaluated: CTM renders nothing. Ranges unroll ({@code 0-46} to 47
 * tiles), {@code faces=top} maps to the {@code TOP} face never ALL, and a per-method tile-count
 * mismatch rejects the rule fail-closed. Filename inference gives {@code block_<name>.properties} a
 * block target and {@code <name>.properties} a tile target.
 */
@Parity(claim = "pack-rule-layer")
@UtilityClass
public class CtmParser {

    /** The minecraft namespace every unqualified id defaults to. */
    private static final @NotNull String MINECRAFT = "minecraft";

    /** A purely numeric inclusive range token, e.g. {@code 0-46}. */
    private static final @NotNull Pattern NUMERIC_RANGE = Pattern.compile("\\d+-\\d+");

    /** The four horizontal faces the {@code sides} alias expands to. */
    private static final @NotNull EnumSet<Face> SIDES =
        EnumSet.of(Face.NORTH, Face.SOUTH, Face.EAST, Face.WEST);

    /**
     * Parses one CTM file, returning empty (with a diagnostic) when the method is unknown, a face is
     * unrecognised, or the tile count does not match the method.
     *
     * @param props the loaded properties
     * @param ruleId the pack-relative {@code .properties} id
     * @param pack the owning pack
     * @param propsDir the file's directory, relative to {@code assets/minecraft/}
     * @param basename the {@code .properties} basename, for filename inference
     * @return the parsed rule, or empty when rejected
     */
    public static @NotNull Optional<CtmRule> parse(
        @NotNull Properties props, @NotNull ResourceId ruleId, @NotNull PackId pack,
        @NotNull String propsDir, @NotNull String basename
    ) {
        try {
            return Optional.of(parseOrThrow(props, ruleId, pack, propsDir, basename));
        } catch (RuleRejection rejection) {
            RuleDiagnostics.reject(pack, ruleId, rejection);
            return Optional.empty();
        }
    }

    private static @NotNull CtmRule parseOrThrow(
        @NotNull Properties props, @NotNull ResourceId ruleId, @NotNull PackId pack,
        @NotNull String propsDir, @NotNull String basename
    ) {
        String methodRaw = props.getProperty("method", "ctm");
        CtmMethod method = CtmMethod.parse(methodRaw)
            .orElseThrow(() -> new RuleRejection("method", methodRaw, "unknown CTM method"));

        int weight = parseInt(props, "weight", 0);
        int width = parseInt(props, "width", 0);
        int height = parseInt(props, "height", 0);

        EnumSet<Face> faces = parseFaceSet(props.getProperty("faces"))
            .orElseThrow(() -> new RuleRejection("faces", props.getProperty("faces"), "unknown face token"));

        CtmTarget target = parseTarget(props, basename);
        ConcurrentList<TileRef> tiles = parseTiles(props.getProperty("tiles"), propsDir);
        validateTileCount(method, tiles.size(), width, height);
        CtmExtras extras = parseExtras(props, width, height);

        return new CtmRule(ruleId, pack, target, method, tiles, faces, weight, extras);
    }

    // --- faces --------------------------------------------------------------------------------

    /**
     * Parses a {@code faces=} value into the exact face set. An absent or blank value defaults to all
     * six faces; {@code sides} expands to the four horizontals and {@code all} to all six; an unknown
     * token returns empty so the caller can reject the rule fail-closed rather than falling back to
     * every face.
     *
     * <p><b>The whole OptiFine dialect lives here and reaches no further.</b> The grammar spells two of
     * the six differently - {@code top} and {@code bottom} where the renderer says {@link Face#UP} and
     * {@link Face#DOWN} - and the other four happen to agree with vanilla's own model-JSON keys. The
     * table is written out for all six rather than deferring the four to {@link Face#fromName}, so one
     * method never quietly serves two grammars and a pack-format change touches this file alone.
     *
     * @param raw the raw {@code faces} value, or {@code null} when the key is absent
     * @return the resolved face set, or empty when a token is unrecognised
     */
    private static @NotNull Optional<EnumSet<Face>> parseFaceSet(String raw) {
        if (raw == null || raw.isBlank()) return Optional.of(EnumSet.allOf(Face.class));

        EnumSet<Face> faces = EnumSet.noneOf(Face.class);
        for (String token : raw.trim().toLowerCase().split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            switch (token) {
                case "sides" -> faces.addAll(SIDES);
                case "all" -> faces.addAll(EnumSet.allOf(Face.class));
                case "north" -> faces.add(Face.NORTH);
                case "south" -> faces.add(Face.SOUTH);
                case "east" -> faces.add(Face.EAST);
                case "west" -> faces.add(Face.WEST);
                case "top" -> faces.add(Face.UP);
                case "bottom" -> faces.add(Face.DOWN);
                default -> {
                    return Optional.empty();
                }
            }
        }
        return faces.isEmpty() ? Optional.of(EnumSet.allOf(Face.class)) : Optional.of(faces);
    }

    // --- target -------------------------------------------------------------------------------

    private static @NotNull CtmTarget parseTarget(@NotNull Properties props, @NotNull String basename) {
        String matchTiles = props.getProperty("matchTiles");
        if (matchTiles != null && !matchTiles.isBlank()) return new CtmTarget.Tiles(splitNames(matchTiles));

        String matchBlocks = props.getProperty("matchBlocks");
        if (matchBlocks != null && !matchBlocks.isBlank()) return new CtmTarget.Blocks(parseBlockMatches(matchBlocks));

        // Filename inference: block_<name> -> block target, else tile target.
        if (basename.startsWith("block_"))
            return new CtmTarget.Blocks(Concurrent.newList(new BlockMatch(new ResourceId(MINECRAFT, basename.substring("block_".length())), Concurrent.newMap())));
        return new CtmTarget.Tiles(Concurrent.newList(basename));
    }

    private static @NotNull ConcurrentList<String> splitNames(@NotNull String value) {
        List<String> names = new ArrayList<>();
        for (String token : value.trim().split("\\s+"))
            if (!token.isBlank()) names.add(token);
        return Concurrent.adoptList(names).toUnmodifiable();
    }

    private static @NotNull ConcurrentList<BlockMatch> parseBlockMatches(@NotNull String value) {
        List<BlockMatch> matches = new ArrayList<>();
        for (String entry : value.trim().split("\\s+"))
            if (!entry.isBlank()) matches.add(parseBlockMatch(entry));
        return Concurrent.adoptList(matches).toUnmodifiable();
    }

    /**
     * Parses one {@code matchBlocks} entry - {@code minecraft:oak_stairs:facing=east,west:half=bottom}.
     * Leading {@code :}-separated segments with no {@code =} form the block id (so a namespaced id
     * survives); the remaining {@code key=v1,v2} segments become state-property filters.
     */
    private static @NotNull BlockMatch parseBlockMatch(@NotNull String entry) {
        String[] segments = entry.split(":");
        List<String> idSegments = new ArrayList<>();
        int i = 0;
        while (i < segments.length && !segments[i].contains("=")) {
            idSegments.add(segments[i]);
            i++;
        }
        HashMap<String, ConcurrentList<String>> properties = new HashMap<>();
        for (; i < segments.length; i++) {
            int eq = segments[i].indexOf('=');
            if (eq < 0) continue;
            String key = segments[i].substring(0, eq);
            ArrayList<String> values = new ArrayList<>();
            for (String value : segments[i].substring(eq + 1).split(","))
                if (!value.isBlank()) values.add(value);
            properties.put(key, Concurrent.adoptList(values).toUnmodifiable());
        }
        String id = String.join(":", idSegments);
        ResourceId block = id.contains(":") ? ResourceId.parse(id) : new ResourceId(MINECRAFT, id);
        return new BlockMatch(block, Concurrent.adoptMap(properties).toUnmodifiable());
    }

    // --- tiles --------------------------------------------------------------------------------

    private static @NotNull ConcurrentList<TileRef> parseTiles(String value, @NotNull String propsDir) {
        List<TileRef> tiles = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            for (String token : value.trim().split("\\s+")) {
                if (token.isBlank()) continue;
                switch (token) {
                    case "<skip>" -> tiles.add(new TileRef.Skip());
                    case "<default>" -> tiles.add(new TileRef.Default());
                    default -> {
                        if (NUMERIC_RANGE.matcher(token).matches()) expandRange(token, propsDir, tiles);
                        else tiles.add(new TileRef.Texture(resolveTile(token, propsDir)));
                    }
                }
            }
        }
        return Concurrent.adoptList(tiles).toUnmodifiable();
    }

    /** Unrolls a {@code a-b} numeric range into per-index {@link TileRef.Texture} entries. */
    private static void expandRange(@NotNull String token, @NotNull String propsDir, @NotNull List<TileRef> out) {
        int dash = token.indexOf('-');
        int from = Integer.parseInt(token.substring(0, dash));
        int to = Integer.parseInt(token.substring(dash + 1));
        if (to < from) throw new RuleRejection("tiles", token, "descending tile range");
        for (int i = from; i <= to; i++) out.add(new TileRef.Texture(resolveTile(Integer.toString(i), propsDir)));
    }

    /** Resolves one tile token relative to the {@code .properties} directory, folding a {@code textures/} prefix into the id. */
    private static @NotNull ResourceId resolveTile(@NotNull String token, @NotNull String propsDir) {
        String t = token.endsWith(".png") ? token.substring(0, token.length() - 4) : token;
        if (t.contains(":")) return ResourceId.parse(t);
        String path;
        if (t.startsWith("./")) path = join(propsDir, t.substring(2));
        else if (t.contains("/")) path = t;
        else path = join(propsDir, t);
        if (path.startsWith("textures/")) return new ResourceId(MINECRAFT, path.substring("textures/".length()));
        return new ResourceId(MINECRAFT, path);
    }

    private static void validateTileCount(@NotNull CtmMethod method, int size, int width, int height) {
        int expected = method.expectedTileCount(width, height);
        if (expected >= 0 && size != expected)
            throw new RuleRejection("tiles", Integer.toString(size), "method " + method + " needs " + expected + " tiles, got " + size);
        if (expected < 0 && size < 1)
            throw new RuleRejection("tiles", "0", "method " + method + " needs at least one tile");
    }

    // --- extras -------------------------------------------------------------------------------

    private static @NotNull CtmExtras parseExtras(@NotNull Properties props, int width, int height) {
        HashMap<Integer, String> compact = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("ctm.")) continue;
            String indexPart = key.substring("ctm.".length());
            try {
                compact.put(Integer.parseInt(indexPart), props.getProperty(key));
            } catch (NumberFormatException ignored) {
                // A non-numeric ctm.<x> key is not a compact replacement - leave it unstored.
            }
        }
        boolean innerSeams = Boolean.parseBoolean(props.getProperty("innerSeams", "false"));
        int tintIndex = parseInt(props, "tintIndex", -1);
        Optional<String> tintBlock = optional(props.getProperty("tintBlock"));
        Optional<String> layer = optional(props.getProperty("layer"));
        return new CtmExtras(Concurrent.adoptMap(compact).toUnmodifiable(), innerSeams, tintIndex, tintBlock, layer, width, height);
    }

    // --- shared -------------------------------------------------------------------------------

    private static int parseInt(@NotNull Properties props, @NotNull String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new RuleRejection(key, raw, "not an integer");
        }
    }

    private static @NotNull Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static @NotNull String join(@NotNull String dir, @NotNull String tail) {
        return dir.isEmpty() ? tail : dir + "/" + tail;
    }

}
