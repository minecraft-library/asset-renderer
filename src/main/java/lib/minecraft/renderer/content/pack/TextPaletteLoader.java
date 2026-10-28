package lib.minecraft.renderer.content.pack;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.pack.TextPalette;
import lib.minecraft.renderer.content.read.PackSubtree.Subtree;
import lib.minecraft.renderer.content.read.PackSubtree;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the colour swap a pack's text shader runs over every text vertex colour into the
 * {@link TextPalette} a stack's text is drawn through.
 * <p>
 * <b>Which file is read.</b> The 26.1 program, {@code minecraft:shaders/core/rendertype_text.vsh}, where
 * its winning copy is a pack's above vanilla; otherwise {@code minecraft:shaders/core/text.vsh}, the
 * program a newer client runs, where a pack above vanilla ships it; otherwise none. A winning copy is
 * the one {@link PackSubtree}'s walk leaves last, so the highest pack, its overlays and every
 * {@code filter.block} decide it as the client's resource manager does. Vanilla's own program is never
 * read: it swaps nothing.
 * <p>
 * <b>Which shape is read.</b> Exactly one, and nothing else is taken for it. The program's imports are
 * followed as the client's preprocessor follows them - {@code <ns:path>} from
 * {@code <ns>:shaders/include/}, {@code "path"} from the program's own directory, each file once and a
 * repeat importing nothing - and every import must resolve. The program's {@code main} is vanilla's
 * with one statement added, {@code vec4 V = F(Color)}, outside every preprocessor condition, and every
 * {@code vertexColor} it writes reads {@code V}. {@code F} switches over its colour's
 * {@code SEL(P.rgb)}, each case returning {@code H(0xRRGGBB, P.a)} and the default the colour itself;
 * {@code SEL} packs the red, green and blue into one 24-bit key; and each {@code H} either rebuilds the
 * colour from its argument's channels over 255, or from those channels each integer-divided by 4 first.
 * The helpers are told apart by their bodies rather than their names. No pack above vanilla may ship
 * the program's fragment shader, and no preprocessor directive but {@code #version} and the
 * conditionals may appear. A program whose {@code main} is vanilla's own, every {@code vertexColor}
 * reading {@code Color}, reads as a table of no swaps.
 * <p>
 * <b>A shader of another shape is reported, never guessed at.</b> One line on standard error names the
 * pack, the file and the first rule the shape broke, and the palette answers empty - distinct from
 * absent, which no pack above vanilla shipping a text shader answers - so every text draw keeps the
 * colours it is given.
 */
@UtilityClass
public class TextPaletteLoader {

    /** The program 26.1 draws its text with. */
    private static final @NotNull ResourceId PROGRAM = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "shaders/core/rendertype_text.vsh");

    /** The program a newer client draws its text with. */
    private static final @NotNull ResourceId NEWER_PROGRAM = new ResourceId(ResourceId.DEFAULT_NAMESPACE, "shaders/core/text.vsh");

    /** The directory an angle-bracket import is read from, under its namespace. */
    private static final @NotNull String INCLUDES = "shaders/include/";

    /** An import directive opening its line, quoted or angle-bracketed, as the client's preprocessor matches one. */
    private static final @NotNull Pattern IMPORT = Pattern.compile("(?m)^[ \\t]*#[ \\t]*moj_import[ \\t]*(?:\"(.*)\"|<(.*)>)");

    /** A preprocessor directive's name. */
    private static final @NotNull Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w*)");

    /**
     * One GLSL token - an identifier, a number, a two- or three-character operator, or any other single
     * character. A template's placeholders lex as identifiers opening on {@code $}.
     */
    private static final @NotNull Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_]*"
        + "|0[xX][0-9A-Fa-f]+[uU]?"
        + "|[0-9]+\\.?[0-9]*(?:[eE][+-]?[0-9]+)?[fFuU]?"
        + "|\\.[0-9]+(?:[eE][+-]?[0-9]+)?[fF]?"
        + "|<<=|>>=|<<|>>|\\+\\+|--|&&|\\|\\||\\^\\^|[-+*/%&|^<>=!]="
        + "|\\S");

    /** A GLSL identifier. */
    private static final @NotNull Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** A 24-bit colour written as six hex digits. */
    private static final @NotNull Pattern HEX_RGB = Pattern.compile("0[xX]([0-9A-Fa-f]{6})");

    /** The token standing for a line opening a preprocessor condition, which no source text lexes as. */
    private static final @NotNull String OPEN = "\0#if";

    /** The token standing for a line closing a preprocessor condition, which no source text lexes as. */
    private static final @NotNull String CLOSE = "\0#endif";

    /** The prefix of a template placeholder binding a six-digit hex colour rather than an identifier. */
    private static final @NotNull String HEX_PLACEHOLDER = "$hex";

    /** Vanilla's position statement. */
    private static final @NotNull List<String> POSITION = lex("gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0)");

    /** Vanilla's spherical fog distance statement. */
    private static final @NotNull List<String> SPHERICAL = lex("sphericalVertexDistance = fog_spherical_distance(Position)");

    /** Vanilla's cylindrical fog distance statement. */
    private static final @NotNull List<String> CYLINDRICAL = lex("cylindricalVertexDistance = fog_cylindrical_distance(Position)");

    /** Vanilla's texture coordinate statement. */
    private static final @NotNull List<String> TEXTURE = lex("texCoord0 = UV0");

    /** What a lit {@code vertexColor} statement multiplies its colour by. */
    private static final @NotNull List<String> LIGHTMAP = lex("* sample_lightmap(Sampler2, UV2)");

    /** The swap function's body up to its first case. */
    private static final @NotNull List<String> SWITCH_HEAD = lex("{ int $I = $SEL($P.rgb); switch ($I) {");

    /** One case of the swap function's switch. */
    private static final @NotNull List<String> SWITCH_CASE = lex("case $hexKey: return $H($hexSwap, $P.a);");

    /** The swap function's body from its default on. */
    private static final @NotNull List<String> SWITCH_TAIL = lex("default: return $P; } }");

    /** The body of the function packing a colour's red, green and blue into one 24-bit key. */
    private static final @NotNull List<String> SELECTOR = lex("{ int $X = int($C.r * 255.0); int $Y = int($C.g * 255.0); "
        + "int $Z = int($C.b * 255.0); return ($X << 16) + ($Y << 8) + ($Z); }");

    /** The body of a helper rebuilding a colour from its argument's channels. */
    private static final @NotNull List<String> NORMAL = lex("{ return vec4(($C >> 16 & 0xFF) / 255.0, ($C >> 8 & 0xFF) / 255.0, "
        + "($C & 0xFF) / 255.0, $A); }");

    /** The body of a helper rebuilding a colour from its argument's channels, each integer-divided by 4. */
    private static final @NotNull List<String> SHADOW = lex("{ return vec4((($C >> 16 & 0xFF) / 4) / 255.0, (($C >> 8 & 0xFF) / 4) / 255.0, "
        + "(($C & 0xFF) / 4) / 255.0, $A); }");

    /**
     * Lists every shader program and include across a stack, in resolution order - every
     * {@code .vsh}, {@code .fsh} and {@code .glsl} under each namespace's {@code shaders/}, a later
     * root and a later pack winning.
     *
     * @param ascending the stack's packs, vanilla first and the highest-priority pack last
     * @return the shader files, in resolution order
     */
    public static @NotNull ConcurrentList<PackSubtree.Entry> shaderFiles(@NotNull List<ResourcePack> ascending) {
        return PackSubtree.walk(ascending, Subtree.of("shaders", ".vsh"), Subtree.of("shaders", ".fsh"), Subtree.of("shaders", ".glsl"));
    }

    /**
     * Reads the colour swap the text shader of the pack drawing a stack's text holds.
     *
     * @param ascending the stack's packs, vanilla first and the highest-priority pack last
     * @return the palette; empty where that shader is of another shape than the one read, which one
     *     line on standard error reports, and absent where no pack above vanilla ships a text shader
     */
    public static @NotNull Possible<TextPalette> load(@NotNull List<ResourcePack> ascending) {
        Map<String, PackSubtree.Entry> winners = new HashMap<>();
        for (PackSubtree.Entry entry : shaderFiles(ascending))
            winners.put(entry.namespace() + ":" + entry.resourcePath(), entry);

        Optional<PackSubtree.Entry> found = Optional.ofNullable(winners.get(PROGRAM.id()))
            .filter(TextPaletteLoader::aboveVanilla)
            .or(() -> Optional.ofNullable(winners.get(NEWER_PROGRAM.id())).filter(TextPaletteLoader::aboveVanilla));
        if (found.isEmpty()) return Possible.absent();

        PackSubtree.Entry program = found.get();
        ResourceId id = new ResourceId(program.namespace(), program.resourcePath());
        try {
            return Possible.of(new TextPalette(id, program.pack().id(), swaps(program, winners)));
        } catch (ContentException ex) {
            System.err.printf("Pack '%s': text shader '%s' is no colour swap this renderer reads - %s; its text draws in the colours it is given%n",
                program.pack().id(), id, ex.getMessage());
            return Possible.empty();
        }
    }

    /**
     * Reads the swap table out of a text program, refusing every departure from the one shape read.
     *
     * @param program the program's winning file
     * @param winners every shader file's winning copy, keyed {@code <namespace>:<path>}
     * @return each swapped colour's 24-bit rgb and the rgb drawn in its place, empty for vanilla's own
     *     program
     * @throws ContentException naming the first rule the shape broke
     */
    private static @NotNull ConcurrentMap<Integer, Integer> swaps(@NotNull PackSubtree.Entry program,
                                                                  @NotNull Map<String, PackSubtree.Entry> winners) {
        String fragment = program.namespace() + ":" + program.resourcePath().replaceFirst("\\.vsh$", ".fsh");
        PackSubtree.Entry shippedFragment = winners.get(fragment);
        if (shippedFragment != null && aboveVanilla(shippedFragment))
            throw new ContentException("Fragment program '%s' is shipped by pack '%s'", fragment, shippedFragment.pack().id());

        String directory = directoryOf(program.resourcePath());
        List<String> tokens = tokenize(expand(program, "", program.namespace(), directory, winners, new HashSet<>()));
        List<Definition> definitions = definitions(tokens);

        Optional<SwapCall> call = mainCall(only(definitions, "main", "void", List.of()));
        if (call.isEmpty()) return Concurrent.newUnmodifiableMap();

        Definition swap = only(definitions, call.get().function(), "vec4", List.of("vec4"));
        Map<String, String> head = new HashMap<>(Map.of("$P", swap.parameterName(0)));
        List<String> body = swap.body();
        int from = SWITCH_HEAD.size();
        int to = body.size() - SWITCH_TAIL.size();
        if (to < from || !matches(body, 0, SWITCH_HEAD, head) || !matches(body, to, SWITCH_TAIL, new HashMap<>(head)))
            throw new ContentException("Function '%s' is no switch over its colour's key ending in a default returning the colour", swap.name());
        if ((to - from) % SWITCH_CASE.size() != 0)
            throw new ContentException("Function '%s' holds a case of another form than 'case 0xRRGGBB: return H(0xRRGGBB, %s.a);'", swap.name(), swap.parameterName(0));

        Definition selector = only(definitions, head.get("$SEL"), "int", List.of("vec3"));
        if (!matchesWhole(selector.body(), SELECTOR, Map.of("$C", selector.parameterName(0))))
            throw new ContentException("Function '%s' is no packing of a colour's red, green and blue into one 24-bit key", selector.name());

        Map<String, Boolean> quartered = new HashMap<>();
        Map<Integer, Integer> swaps = new LinkedHashMap<>();
        for (int at = from; at < to; at += SWITCH_CASE.size()) {
            Map<String, String> branch = new HashMap<>(head);
            if (!matches(body, at, SWITCH_CASE, branch))
                throw new ContentException("Function '%s' holds a case of another form than 'case 0xRRGGBB: return H(0xRRGGBB, %s.a);'", swap.name(), swap.parameterName(0));

            int key = hex(branch.get("$hexKey"));
            int drawn = hex(branch.get("$hexSwap"));
            String helper = branch.get("$H");
            if (!quartered.containsKey(helper))
                quartered.put(helper, quarters(only(definitions, helper, "vec4", List.of("int", "float"))));
            if (swaps.put(key, quartered.get(helper) ? quarter(drawn) : drawn) != null)
                throw new ContentException("Function '%s' holds two cases for '%06X'", swap.name(), key);
        }
        return Concurrent.newUnmodifiableMap(swaps);
    }

    /**
     * Reads a text program's {@code main}: vanilla's statements, and at most one added statement
     * declaring the swapped colour, which every {@code vertexColor} it writes must then read.
     *
     * @param main the program's {@code main}
     * @return the function the added statement calls, empty where {@code main} is vanilla's own
     * @throws ContentException naming the first statement of another form
     */
    private static @NotNull Optional<SwapCall> mainCall(@NotNull Definition main) {
        List<Statement> statements = statements(main);
        Set<String> named = new HashSet<>();
        Optional<SwapCall> call = Optional.empty();
        List<String> reads = new ArrayList<>();
        for (Statement statement : statements) {
            List<String> tokens = statement.tokens();
            boolean writesColor = writesVertexColor(tokens);
            if (call.isPresent() && !writesColor && tokens.contains(call.get().variable()))
                throw new ContentException("Statement '%s' of main names the swapped colour '%s'", String.join(" ", tokens), call.get().variable());

            if (tokens.equals(POSITION) || tokens.equals(SPHERICAL) || tokens.equals(CYLINDRICAL) || tokens.equals(TEXTURE)) {
                named.addAll(tokens);
                continue;
            }
            if (writesColor) {
                named.addAll(tokens);
                reads.add(tokens.get(2));
                continue;
            }
            if (declaresSwap(tokens)) {
                if (call.isPresent())
                    throw new ContentException("Main declares the swapped colour twice");
                if (statement.nesting() != 0)
                    throw new ContentException("Main declares the swapped colour '%s' under a preprocessor condition", tokens.get(1));
                if (named.contains(tokens.get(1)))
                    throw new ContentException("Main names '%s' before it declares it the swapped colour", tokens.get(1));

                call = Optional.of(new SwapCall(tokens.get(1), tokens.get(3)));
                continue;
            }
            throw new ContentException("Statement '%s' of main is none of vanilla's and no swap of 'Color'", String.join(" ", tokens));
        }

        if (reads.isEmpty())
            throw new ContentException("Main writes no 'vertexColor'");
        String expected = call.map(SwapCall::variable).orElse("Color");
        for (String read : reads)
            if (!read.equals(expected))
                throw new ContentException("Main writes 'vertexColor' from '%s' rather than '%s'", read, expected);
        return call;
    }

    /**
     * Splits a function's body into its statements, each with the depth of preprocessor conditions it
     * sits under. A body holding a block of its own, or a condition opening or closing inside a
     * statement, is of another shape.
     *
     * @param function the function
     * @return the statements, in order
     * @throws ContentException if the body holds a block or an unbalanced condition
     */
    private static @NotNull List<Statement> statements(@NotNull Definition function) {
        List<Statement> statements = new ArrayList<>();
        List<String> statement = new ArrayList<>();
        int nesting = 0;
        for (String token : function.body().subList(1, function.body().size() - 1)) {
            if (token.equals(OPEN) || token.equals(CLOSE)) {
                nesting += token.equals(OPEN) ? 1 : -1;
                if (!statement.isEmpty() || nesting < 0)
                    throw new ContentException("Function '%s' opens or closes a preprocessor condition inside a statement", function.name());
                continue;
            }
            if (token.equals("{") || token.equals("}"))
                throw new ContentException("Function '%s' holds a block", function.name());
            if (token.equals(";")) {
                statements.add(new Statement(List.copyOf(statement), nesting));
                statement.clear();
                continue;
            }
            statement.add(token);
        }
        if (!statement.isEmpty() || nesting != 0)
            throw new ContentException("Function '%s' ends inside a statement or a preprocessor condition", function.name());
        return statements;
    }

    /**
     * Answers whether a statement writes {@code vertexColor} as vanilla does - a colour alone, or a
     * colour times the lightmap sample.
     *
     * @param tokens the statement's tokens
     * @return whether it does
     */
    private static boolean writesVertexColor(@NotNull List<String> tokens) {
        if (tokens.size() < 3 || !tokens.get(0).equals("vertexColor") || !tokens.get(1).equals("=") || !isIdentifier(tokens.get(2)))
            return false;
        return tokens.size() == 3 || tokens.subList(3, tokens.size()).equals(LIGHTMAP);
    }

    /**
     * Answers whether a statement declares the swapped colour, {@code vec4 V = F(Color)}.
     *
     * @param tokens the statement's tokens
     * @return whether it does
     */
    private static boolean declaresSwap(@NotNull List<String> tokens) {
        return tokens.size() == 7 && tokens.get(0).equals("vec4") && isIdentifier(tokens.get(1)) && tokens.get(2).equals("=")
            && isIdentifier(tokens.get(3)) && tokens.get(4).equals("(") && tokens.get(5).equals("Color") && tokens.get(6).equals(")");
    }

    /**
     * Reads which of the two helper bodies a swap case calls.
     *
     * @param helper the helper
     * @return whether it quarters each channel before rebuilding the colour
     * @throws ContentException if its body is neither
     */
    private static boolean quarters(@NotNull Definition helper) {
        Map<String, String> parameters = Map.of("$C", helper.parameterName(0), "$A", helper.parameterName(1));
        if (matchesWhole(helper.body(), NORMAL, parameters)) return false;
        if (matchesWhole(helper.body(), SHADOW, parameters)) return true;
        throw new ContentException("Function '%s' rebuilds a colour from neither its argument's channels nor those channels quartered", helper.name());
    }

    /**
     * Answers a 24-bit colour with each channel integer-divided by 4, as the shadow helper rebuilds it.
     *
     * @param rgb the colour
     * @return the colour quartered per channel
     */
    private static int quarter(int rgb) {
        return (((rgb >> 16) & 0xFF) / 4) << 16 | (((rgb >> 8) & 0xFF) / 4) << 8 | (rgb & 0xFF) / 4;
    }

    /**
     * Reads a six-digit hex colour token.
     *
     * @param token the token, {@code 0xRRGGBB}
     * @return the 24-bit colour
     */
    private static int hex(@NotNull String token) {
        return Integer.parseInt(token.substring(2), 16);
    }

    /**
     * Answers whether a file is shipped by a pack above vanilla.
     *
     * @param entry the file
     * @return whether its pack is not vanilla's
     */
    private static boolean aboveVanilla(@NotNull PackSubtree.Entry entry) {
        return !entry.pack().id().equals(PackId.VANILLA);
    }

    /**
     * Expands a shader file as the client's preprocessor does: comments dropped, and each import opening
     * its line replaced by the winning copy of the file it names, expanded in turn. An angle-bracket
     * import, {@code <ns:path>} or {@code <path>} under {@code minecraft}, names {@code <ns>:shaders/include/<path>};
     * a quoted one names its path under the program's own directory, a quoted import's own quoted
     * imports adding its directory to theirs. A file already imported imports nothing.
     *
     * @param file the file to expand
     * @param directory the directory the client adds ahead of each import this file makes - empty
     *     unless a quoted import reached it
     * @param namespace the program's namespace
     * @param programDirectory the program's own directory, which every quoted import resolves under
     * @param winners every shader file's winning copy, keyed {@code <namespace>:<path>}
     * @param imported every file imported so far
     * @return the expanded source, comment-free
     * @throws ContentException if an import names a file no pack ships, or climbs out of its namespace
     */
    private static @NotNull String expand(@NotNull PackSubtree.Entry file, @NotNull String directory, @NotNull String namespace,
                                          @NotNull String programDirectory, @NotNull Map<String, PackSubtree.Entry> winners,
                                          @NotNull Set<String> imported) {
        String source = withoutComments(new String(file.container().bytes(file.entryPath()).orElseThrow(), StandardCharsets.UTF_8));
        StringBuilder expanded = new StringBuilder(source.length());
        Matcher matcher = IMPORT.matcher(source);
        int last = 0;
        while (matcher.find()) {
            expanded.append(source, last, matcher.start());
            last = matcher.end();

            boolean quoted = matcher.group(1) != null;
            String path = directory + (quoted ? matcher.group(1) : matcher.group(2));
            String key;
            if (quoted) {
                key = namespace + ":" + normalize(programDirectory + path);
            } else {
                ResourceId include = ResourceId.parse(path);
                key = include.namespace() + ":" + INCLUDES + include.name();
            }
            if (!imported.add(key)) continue;

            PackSubtree.Entry target = winners.get(key);
            if (target == null)
                throw new ContentException("Import '%s' names a file no pack ships", key);
            expanded.append('\n')
                .append(expand(target, quoted ? directoryOf(path) : "", namespace, programDirectory, winners, imported))
                .append('\n');
        }
        return expanded.append(source, last, source.length()).toString();
    }

    /**
     * Drops a source's comments, each block comment standing as one space as the GLSL preprocessor
     * reads it.
     *
     * @param source the source
     * @return the source without comments
     */
    private static @NotNull String withoutComments(@NotNull String source) {
        StringBuilder kept = new StringBuilder(source.length());
        int at = 0;
        while (at < source.length()) {
            if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? source.length() : end + 2;
                kept.append(' ');
            } else if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                at = end < 0 ? source.length() : end;
            } else {
                kept.append(source.charAt(at++));
            }
        }
        return kept.toString();
    }

    /**
     * Answers the directory part of a resource path, its trailing slash kept.
     *
     * @param path the path
     * @return everything up to and including its last slash, empty where it holds none
     */
    private static @NotNull String directoryOf(@NotNull String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }

    /**
     * Resolves a resource path's {@code .} and {@code ..} segments and drops its empty ones.
     *
     * @param path the path
     * @return the normalized path
     * @throws ContentException if a {@code ..} climbs above the namespace's root
     */
    private static @NotNull String normalize(@NotNull String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (!segment.equals("..")) {
                segments.addLast(segment);
                continue;
            }
            if (segments.isEmpty())
                throw new ContentException("Import '%s' climbs out of its namespace", path);
            segments.removeLast();
        }
        return String.join("/", segments);
    }

    /**
     * Lexes an expanded source into its tokens, each line opening or closing a preprocessor condition
     * standing as one {@link #OPEN} or {@link #CLOSE} token. An {@code #elif} or {@code #else} line
     * stands as nothing, so what follows it sits under the same condition, and a {@code #version} line
     * stands as nothing.
     *
     * @param source the expanded source
     * @return its tokens
     * @throws ContentException if it holds any other directive, or a condition left open or closed twice
     */
    private static @NotNull List<String> tokenize(@NotNull String source) {
        List<String> tokens = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        int depth = 0;
        for (String line : source.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.startsWith("#")) {
                code.append(line).append('\n');
                continue;
            }

            tokens.addAll(lex(code.toString()));
            code.setLength(0);
            Matcher directive = DIRECTIVE.matcher(stripped);
            String name = directive.lookingAt() ? directive.group(1) : "";
            switch (name) {
                case "version", "elif", "else" -> { }
                case "if", "ifdef", "ifndef" -> {
                    tokens.add(OPEN);
                    depth++;
                }
                case "endif" -> {
                    tokens.add(CLOSE);
                    if (--depth < 0)
                        throw new ContentException("Directive '#endif' closes no condition");
                }
                default -> throw new ContentException("Directive '#%s' is none the shape reads", name);
            }
        }
        if (depth != 0)
            throw new ContentException("A preprocessor condition is left open");
        tokens.addAll(lex(code.toString()));
        return tokens;
    }

    /**
     * Lexes source text with no directive in it into its tokens.
     *
     * @param text the text
     * @return its tokens
     */
    private static @NotNull List<String> lex(@NotNull String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find())
            tokens.add(matcher.group());
        return tokens;
    }

    /**
     * Collects every function a program's top level defines, each with the depth of preprocessor
     * conditions its body opens under. A block that is no function's body - a uniform block, a struct,
     * an initializer - is passed over.
     *
     * @param tokens the program's tokens
     * @return the function definitions, in order
     * @throws ContentException if a brace is left unmatched
     */
    private static @NotNull List<Definition> definitions(@NotNull List<String> tokens) {
        List<Definition> definitions = new ArrayList<>();
        List<String> header = new ArrayList<>();
        int nesting = 0;
        int at = 0;
        while (at < tokens.size()) {
            String token = tokens.get(at);
            if (token.equals(OPEN) || token.equals(CLOSE)) {
                nesting += token.equals(OPEN) ? 1 : -1;
                at++;
            } else if (token.equals(";")) {
                header.clear();
                at++;
            } else if (token.equals("{")) {
                int end = closing(tokens, at);
                List<String> body = List.copyOf(tokens.subList(at, end + 1));
                definition(header, body, nesting).ifPresent(definitions::add);
                for (String inner : body)
                    nesting += inner.equals(OPEN) ? 1 : inner.equals(CLOSE) ? -1 : 0;
                header.clear();
                at = end + 1;
            } else {
                header.add(token);
                at++;
            }
        }
        return definitions;
    }

    /**
     * Answers the index of the brace closing the one at {@code open}.
     *
     * @param tokens the tokens
     * @param open the index of an opening brace
     * @return the index of its closing brace
     * @throws ContentException if none closes it
     */
    private static int closing(@NotNull List<String> tokens, int open) {
        int depth = 0;
        for (int at = open; at < tokens.size(); at++) {
            if (tokens.get(at).equals("{")) depth++;
            else if (tokens.get(at).equals("}") && --depth == 0) return at;
        }
        throw new ContentException("A brace is left unclosed");
    }

    /**
     * Reads a function definition off the tokens ahead of a block - {@code TYPE NAME ( PARAMETERS )}.
     *
     * @param header the tokens since the last top-level declaration ended
     * @param body the block, its braces included
     * @param nesting the depth of preprocessor conditions the block opens under
     * @return the definition, empty where the header is no function's
     */
    private static @NotNull Optional<Definition> definition(@NotNull List<String> header, @NotNull List<String> body, int nesting) {
        if (header.size() < 4 || !isIdentifier(header.get(0)) || !isIdentifier(header.get(1))
            || !header.get(2).equals("(") || !header.getLast().equals(")"))
            return Optional.empty();

        List<String> inside = header.subList(3, header.size() - 1);
        if (inside.contains("(") || inside.contains(")")) return Optional.empty();

        List<List<String>> parameters = new ArrayList<>();
        if (!inside.isEmpty() && !inside.equals(List.of("void"))) {
            List<String> parameter = new ArrayList<>();
            for (String token : inside) {
                if (!token.equals(",")) {
                    parameter.add(token);
                    continue;
                }
                parameters.add(List.copyOf(parameter));
                parameter.clear();
            }
            parameters.add(List.copyOf(parameter));
        }
        return Optional.of(new Definition(header.get(0), header.get(1), List.copyOf(parameters), body, nesting));
    }

    /**
     * Finds the one definition of a function the shape calls.
     *
     * @param definitions every function the program defines
     * @param name the function's name
     * @param returnType the type it must return
     * @param parameterTypes the types of its parameters, each a {@code TYPE NAME} pair
     * @return the definition
     * @throws ContentException if no definition, or more than one, takes those parameters, or the one
     *     that does returns another type or sits under a preprocessor condition
     */
    private static @NotNull Definition only(@NotNull List<Definition> definitions, @NotNull String name,
                                            @NotNull String returnType, @NotNull List<String> parameterTypes) {
        List<Definition> found = definitions.stream()
            .filter(definition -> definition.name().equals(name) && definition.parameterTypes().equals(Optional.of(parameterTypes)))
            .toList();
        String signature = name + "(" + String.join(", ", parameterTypes) + ")";
        if (found.size() != 1)
            throw new ContentException("Function '%s' is defined %d times rather than once", signature, found.size());
        Definition definition = found.getFirst();
        if (!definition.returnType().equals(returnType))
            throw new ContentException("Function '%s' returns '%s' rather than '%s'", signature, definition.returnType(), returnType);
        if (definition.nesting() != 0)
            throw new ContentException("Function '%s' is defined under a preprocessor condition", signature);
        return definition;
    }

    /**
     * Matches a run of tokens against a template, binding each placeholder - an identifier opening on
     * {@code $} - on its first occurrence and holding it to that binding after. A placeholder opening
     * on {@code $hex} binds a six-digit hex colour; every other binds an identifier no other
     * placeholder holds.
     *
     * @param actual the tokens
     * @param from where in them the run opens
     * @param template the template's tokens
     * @param bound the bindings so far, which the match extends
     * @return whether the run matches
     */
    private static boolean matches(@NotNull List<String> actual, int from, @NotNull List<String> template, @NotNull Map<String, String> bound) {
        if (from < 0 || from + template.size() > actual.size()) return false;

        for (int at = 0; at < template.size(); at++) {
            String expected = template.get(at);
            String token = actual.get(from + at);
            if (!expected.startsWith("$")) {
                if (!expected.equals(token)) return false;
                continue;
            }

            String binding = bound.get(expected);
            if (binding != null) {
                if (!binding.equals(token)) return false;
                continue;
            }
            boolean colour = expected.startsWith(HEX_PLACEHOLDER);
            if (!(colour ? HEX_RGB : IDENTIFIER).matcher(token).matches()) return false;
            if (!colour && bound.entrySet().stream().anyMatch(entry -> !entry.getKey().startsWith(HEX_PLACEHOLDER) && entry.getValue().equals(token)))
                return false;
            bound.put(expected, token);
        }
        return true;
    }

    /**
     * Matches every token of a body against a template.
     *
     * @param actual the tokens
     * @param template the template's tokens
     * @param bound the bindings the template opens with
     * @return whether the body is the template, whole
     */
    private static boolean matchesWhole(@NotNull List<String> actual, @NotNull List<String> template, @NotNull Map<String, String> bound) {
        return actual.size() == template.size() && matches(actual, 0, template, new HashMap<>(bound));
    }

    /**
     * Answers whether a token is a GLSL identifier.
     *
     * @param token the token
     * @return whether it is
     */
    private static boolean isIdentifier(@NotNull String token) {
        return IDENTIFIER.matcher(token).matches();
    }

    /**
     * One function a program defines at its top level.
     *
     * @param returnType the type it returns
     * @param name its name
     * @param parameters each parameter's tokens
     * @param body its body, braces included
     * @param nesting the depth of preprocessor conditions its body opens under
     */
    private record Definition(@NotNull String returnType, @NotNull String name, @NotNull List<List<String>> parameters,
                              @NotNull List<String> body, int nesting) {

        /**
         * Answers the type of each parameter, where each is a bare {@code TYPE NAME} pair.
         *
         * @return the parameter types, empty where a parameter carries a qualifier or an array size
         */
        @NotNull Optional<List<String>> parameterTypes() {
            if (this.parameters.stream().anyMatch(parameter -> parameter.size() != 2)) return Optional.empty();
            return Optional.of(this.parameters.stream().map(List::getFirst).toList());
        }

        /**
         * Answers one parameter's name.
         *
         * @param index the parameter's position
         * @return its name
         */
        @NotNull String parameterName(int index) {
            return this.parameters.get(index).getLast();
        }

    }

    /**
     * One statement of a function's body.
     *
     * @param tokens its tokens, its semicolon left off
     * @param nesting the depth of preprocessor conditions it sits under
     */
    private record Statement(@NotNull List<String> tokens, int nesting) {}

    /**
     * The statement {@code main} adds to vanilla's, {@code vec4 V = F(Color)}.
     *
     * @param variable the swapped colour it declares, {@code V}
     * @param function the function it calls, {@code F}
     */
    private record SwapCall(@NotNull String variable, @NotNull String function) {}

}
