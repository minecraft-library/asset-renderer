package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.option.Age;
import lib.minecraft.renderer.option.CopperWeathering;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.HorseMarking;
import lib.minecraft.renderer.option.IronGolemCrackiness;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lib.minecraft.renderer.option.VillagerLevel;
import lib.minecraft.renderer.option.VillagerProfession;
import lib.minecraft.renderer.option.VillagerType;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.DyeColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads and writes the names the reference renders are filed under.
 *
 * <p>A name is a head, then zero or more {@code ~}-separated tokens:
 *
 * <pre>
 *   &lt;namespace&gt;__&lt;entity-path&gt;[_&lt;coat&gt;] ( "~" &lt;axis&gt; "=" &lt;value&gt; )*
 * </pre>
 *
 * <p>Three characters carry structure and nothing else may: {@code ~} separates tokens, {@code =}
 * separates an axis from its value, and {@code %} introduces a two-hex-digit escape for any value
 * character outside {@code [a-z0-9_.-]}. None of the three is legal in a Minecraft resource id, so
 * no id can collide with the structure. Underscore is content everywhere except the head.
 *
 * <p>The head is the one place that is not self-delimiting: {@code minecraft__wolf_pale} could be
 * the entity {@code wolf} in its {@code pale} coat or an entity named {@code wolf_pale}. The loaded
 * entity index settles it - a model with no variant axis takes the whole path as its id, and
 * otherwise the longest variant family whose remainder is one of its declared options wins.
 *
 * <p>Tokens are emitted in ascending order of the whole {@code axis=value} text, compared as bytes.
 * One rule, no table: the alphabet is ASCII, so byte order and string order agree, and the two
 * implementations of this grammar - this one and the harness's - cannot drift apart on ordering.
 * That also makes the repeatable axes self-ordering, so an unordered set or map round-trips exactly.
 *
 * <p>A token is written only when it differs from the default, and the default is the model's own
 * where the model declares one. Both halves matter: without the first, every name carries every
 * axis; without the second, a subject at its declared size has two names. A name that means two
 * things, or two names that mean one thing, cannot be a gate.
 *
 * <p>Names are lowercase and the parser is case-sensitive. Two names differing only in case would
 * collide on a case-insensitive filesystem while passing on a case-sensitive one - a silent
 * overwrite that depends on which machine ran the sweep.
 */
public final class AppearanceCodec {

    /** The default namespace, and the only one any subject uses today. */
    private static final @NotNull String NAMESPACE = "minecraft";

    /** Separates the namespace from the path in a head, and stands in for {@code :} inside a value. */
    private static final @NotNull String NAMESPACE_SEPARATOR = "__";

    /** The value that selects a baby; adults are the default and are never written. */
    private static final @NotNull String BABY = "baby";

    /** The {@code carried} value that drops a model's fixed block overlays. */
    private static final @NotNull String CARRIED_NONE = "none";

    /** Separates an equipment slot from its material, and an armour slot from its material. */
    private static final char SUB_KEY = '.';

    /** The entity index this codec resolves heads against - its only oracle. */
    private final @NotNull Map<String, Entity> entities;

    private AppearanceCodec(@NotNull Map<String, Entity> entities) {
        this.entities = entities;
    }

    /**
     * Returns a codec that resolves heads against the loaded entity index.
     *
     * @param entities the entity definitions the pipeline loaded
     * @return the codec
     */
    public static @NotNull AppearanceCodec of(@NotNull ConcurrentMap<String, Entity> entities) {
        return new AppearanceCodec(Map.copyOf(entities));
    }

    /**
     * Returns a codec over an explicit index, for tests that do not want to load the pipeline.
     *
     * @param entities the entity definitions to resolve heads against
     * @return the codec
     */
    public static @NotNull AppearanceCodec over(@NotNull Map<String, Entity> entities) {
        return new AppearanceCodec(Map.copyOf(entities));
    }

    // ------------------------------------------------------------------------------------
    // parse
    // ------------------------------------------------------------------------------------

    /**
     * Reads a reference file name.
     *
     * @param fileName the name, with or without its {@code .png} suffix
     * @return the key it names, or the reason it names nothing
     */
    public @NotNull AppearanceKey.Result parse(@NotNull String fileName) {
        // Strip the literal suffix rather than splitting on the last dot - a value may contain one.
        String name = fileName.endsWith(".png")
            ? fileName.substring(0, fileName.length() - ".png".length())
            : fileName;
        if (name.isEmpty()) return malformed(fileName, "empty name");

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') return malformed(fileName, "uppercase '" + c + "' at " + i);
        }

        List<String> parts = List.of(name.split("~", -1));
        Head head = resolveHead(parts.getFirst());
        if (head == null) return malformed(fileName, "head '" + parts.getFirst() + "' resolves to no model");

        EntityAppearance.EntityAppearanceBuilder appearance = EntityAppearance.builder();
        head.variant().ifPresent(v -> appearance.variant(Optional.of(v)));
        Set<String> toggles = new TreeSet<>();
        Map<String, String> equipment = new TreeMap<>();
        Map<TintAxis, DyeColor> tints = new LinkedHashMap<>();
        // Boxed holders: the token loop below is a lambda-free for, but these two are read after it
        // and assigned inside it, so a plain local would not be effectively final for the map at the
        // end. One-element arrays keep the loop straightforwardly imperative.
        ArmorMaterial[] armorMaterial = new ArmorMaterial[1];
        Integer[] armorDye = new Integer[1];
        Set<String> seenTokens = new TreeSet<>();
        Set<AppearanceKey.Axis> seenAxes = java.util.EnumSet.noneOf(AppearanceKey.Axis.class);

        Entity model = this.entities.get(head.entityId());
        for (String token : parts.subList(1, parts.size())) {
            if (!seenTokens.add(token)) return malformed(fileName, "duplicate token '" + token + "'");
            int split = token.indexOf('=');
            if (split < 0) return malformed(fileName, "token '" + token + "' has no '='");
            String axisName = token.substring(0, split);
            String raw = token.substring(split + 1);

            Optional<AppearanceKey.Axis> maybeAxis = AppearanceKey.Axis.ofToken(axisName);
            if (maybeAxis.isEmpty()) return malformed(fileName, "unknown axis '" + axisName + "'");
            AppearanceKey.Axis axis = maybeAxis.get();
            if (!axis.repeatable() && !seenAxes.add(axis))
                return malformed(fileName, "axis '" + axisName + "' repeated");

            String value;
            try {
                value = unescape(raw);
            } catch (IllegalArgumentException ex) {
                return malformed(fileName, "token '" + token + "': " + ex.getMessage());
            }

            switch (axis) {
                case AGE -> {
                    if (!BABY.equals(value)) return malformed(fileName, "age must be '" + BABY + "', got '" + value + "'");
                    appearance.age(Age.BABY);
                }
                case STATE -> {
                    if (model != null && !model.axes().stateTextures().containsKey(value))
                        return malformed(fileName, "'" + head.entityId() + "' declares no state '" + value + "'");
                    appearance.state(Optional.of(value));
                }
                case SIZE -> {
                    Optional<Size> size = enumOf(Size.class, value);
                    if (size.isEmpty()) return malformed(fileName, "unknown size '" + value + "'");
                    appearance.size(size);
                }
                case CARRIED -> appearance.carried(Optional.of(
                    CARRIED_NONE.equals(value) ? CARRIED_NONE : value.replace(NAMESPACE_SEPARATOR, ":")));
                case PATTERN -> {
                    Optional<TropicalFishPattern> pattern = enumOf(TropicalFishPattern.class, value);
                    if (pattern.isEmpty()) return malformed(fileName, "unknown pattern '" + value + "'");
                    appearance.pattern(pattern);
                }
                case MARKINGS -> {
                    Optional<HorseMarking> marking = enumOf(HorseMarking.class, value);
                    if (marking.isEmpty()) return malformed(fileName, "unknown markings '" + value + "'");
                    appearance.markings(marking.get());
                }
                case CRACKINESS -> {
                    Optional<IronGolemCrackiness> crack = enumOf(IronGolemCrackiness.class, value);
                    if (crack.isEmpty()) return malformed(fileName, "unknown crackiness '" + value + "'");
                    appearance.crackiness(crack.get());
                }
                case WEATHERING -> {
                    Optional<CopperWeathering> weathering = enumOf(CopperWeathering.class, value);
                    if (weathering.isEmpty()) return malformed(fileName, "unknown weathering '" + value + "'");
                    appearance.weathering(weathering.get());
                }
                case VILLAGER_TYPE -> {
                    Optional<VillagerType> type = enumOf(VillagerType.class, value);
                    if (type.isEmpty()) return malformed(fileName, "unknown villager_type '" + value + "'");
                    appearance.villagerType(type.get());
                }
                case VILLAGER_PROFESSION -> {
                    Optional<VillagerProfession> profession = enumOf(VillagerProfession.class, value);
                    if (profession.isEmpty()) return malformed(fileName, "unknown villager_profession '" + value + "'");
                    appearance.villagerProfession(profession.get());
                }
                case VILLAGER_LEVEL -> {
                    Optional<VillagerLevel> level = enumOf(VillagerLevel.class, value);
                    if (level.isEmpty()) return malformed(fileName, "unknown villager_level '" + value + "'");
                    appearance.villagerLevel(level);
                }
                case SHEARED -> {
                    if (!"true".equals(value)) return malformed(fileName, "sheared must be 'true'");
                    appearance.sheared(true);
                }
                case CHARGED -> {
                    if (!"true".equals(value)) return malformed(fileName, "charged must be 'true'");
                    appearance.charged(true);
                }
                case ELYTRA -> {
                    if (!"true".equals(value)) return malformed(fileName, "elytra must be 'true'");
                    appearance.elytra(true);
                }
                case TOGGLE -> {
                    if (value.isEmpty()) return malformed(fileName, "toggle needs a name");
                    toggles.add(value);
                }
                case EQUIP -> {
                    int sub = value.indexOf(SUB_KEY);
                    String slot = sub < 0 ? value : value.substring(0, sub);
                    String material = sub < 0 ? "" : value.substring(sub + 1);
                    if (slot.isEmpty()) return malformed(fileName, "equip needs a slot");
                    if (equipment.put(slot, material) != null)
                        return malformed(fileName, "equip slot '" + slot + "' named twice");
                }
                case BASE_COLOR, PATTERN_COLOR, WOOL_COLOR, COLLAR_COLOR, EQUIPMENT_COLOR -> {
                    Optional<DyeColor> dye = dye(value);
                    if (dye.isEmpty()) return malformed(fileName, "unknown dye '" + value + "'");
                    tints.put(TintAxis.ofToken(axisName).orElseThrow(), dye.get());
                }
                case ARMOR -> {
                    Optional<ArmorMaterial> material = enumOf(ArmorMaterial.class, value);
                    if (material.isEmpty()) return malformed(fileName, "unknown armor material '" + value + "'");
                    armorMaterial[0] = material.get();
                }
                case ARMOR_DYE -> {
                    if (value.length() != 6 || !isLowerHex(value))
                        return malformed(fileName, "armor_dye must be six lowercase hex digits, got '" + value + "'");
                    armorDye[0] = Integer.parseInt(value, 16);
                }
            }
        }

        if (!toggles.isEmpty()) appearance.toggles(Set.copyOf(toggles));
        if (!equipment.isEmpty()) appearance.equipment(Map.copyOf(equipment));
        if (!tints.isEmpty()) appearance.tints(Map.copyOf(tints));
        if (armorDye[0] != null && armorMaterial[0] == null)
            return malformed(fileName, "armor_dye without armor");

        Optional<ArmorOptions> armor = Optional.ofNullable(armorMaterial[0])
            .map(material -> uniformArmor(material, Optional.ofNullable(armorDye[0])));
        return new AppearanceKey.Result.Parsed(
            new AppearanceKey(head.entityId(), head.variant(), appearance.build(), armor));
    }

    // ------------------------------------------------------------------------------------
    // format
    // ------------------------------------------------------------------------------------

    /**
     * Writes the name a key is filed under.
     *
     * @param key the render to name
     * @return its file name, without the {@code .png} suffix
     */
    public @NotNull String format(@NotNull AppearanceKey key) {
        StringBuilder head = new StringBuilder(key.entityId().replace(":", NAMESPACE_SEPARATOR));
        key.variant().ifPresent(v -> head.append('_').append(v));

        Entity model = this.entities.get(key.entityId());
        EntityAppearance appearance = key.appearance();
        List<String> tokens = new ArrayList<>();

        if (appearance.isBaby()) tokens.add(token(AppearanceKey.Axis.AGE, BABY));
        appearance.getState()
            .filter(state -> !declaredDefault(model, Entity.Axes::stateDefault).equals(Optional.of(state)))
            .ifPresent(state -> tokens.add(token(AppearanceKey.Axis.STATE, state)));
        appearance.getSize()
            .map(size -> size.name().toLowerCase(Locale.ROOT))
            .filter(size -> !declaredDefault(model, Entity.Axes::sizeDefault).equals(Optional.of(size)))
            .ifPresent(size -> tokens.add(token(AppearanceKey.Axis.SIZE, size)));
        appearance.getCarried().ifPresent(carried ->
            tokens.add(token(AppearanceKey.Axis.CARRIED, carried.replace(":", NAMESPACE_SEPARATOR))));
        appearance.getPattern().ifPresent(pattern ->
            tokens.add(token(AppearanceKey.Axis.PATTERN, pattern.name())));

        if (appearance.getMarkings() != HorseMarking.NONE)
            tokens.add(token(AppearanceKey.Axis.MARKINGS, appearance.getMarkings().name()));
        if (appearance.getCrackiness() != IronGolemCrackiness.NONE)
            tokens.add(token(AppearanceKey.Axis.CRACKINESS, appearance.getCrackiness().name()));
        if (appearance.getWeathering() != CopperWeathering.UNAFFECTED)
            tokens.add(token(AppearanceKey.Axis.WEATHERING, appearance.getWeathering().name()));
        if (appearance.getVillagerType() != VillagerType.PLAINS)
            tokens.add(token(AppearanceKey.Axis.VILLAGER_TYPE, appearance.getVillagerType().name()));
        if (appearance.getVillagerProfession() != VillagerProfession.NONE)
            tokens.add(token(AppearanceKey.Axis.VILLAGER_PROFESSION, appearance.getVillagerProfession().name()));
        appearance.getVillagerLevel().ifPresent(level ->
            tokens.add(token(AppearanceKey.Axis.VILLAGER_LEVEL, level.name())));
        if (appearance.isSheared()) tokens.add(token(AppearanceKey.Axis.SHEARED, "true"));
        if (appearance.isCharged()) tokens.add(token(AppearanceKey.Axis.CHARGED, "true"));
        if (appearance.isElytra()) tokens.add(token(AppearanceKey.Axis.ELYTRA, "true"));

        for (String toggle : appearance.getToggles())
            tokens.add(token(AppearanceKey.Axis.TOGGLE, toggle));
        for (Map.Entry<String, String> slot : appearance.getEquipment().entrySet())
            tokens.add(token(AppearanceKey.Axis.EQUIP, slot.getValue().isEmpty()
                ? slot.getKey()
                : slot.getKey() + SUB_KEY + slot.getValue()));
        for (Map.Entry<TintAxis, DyeColor> tint : appearance.getTints().entrySet())
            tokens.add(tint.getKey().token() + "=" + escape(dyeName(tint.getValue())));

        key.armor().flatMap(ArmorOptions::getHelmet).ifPresent(piece -> {
            tokens.add(token(AppearanceKey.Axis.ARMOR, piece.material().name()));
            piece.dyeColor().ifPresent(dye ->
                tokens.add(token(AppearanceKey.Axis.ARMOR_DYE, String.format(Locale.ROOT, "%06x", dye & 0xFFFFFF))));
        });

        tokens.sort(String::compareTo);
        StringBuilder out = new StringBuilder(head);
        for (String token : tokens) out.append('~').append(token);
        return out.toString();
    }

    // ------------------------------------------------------------------------------------
    // head resolution
    // ------------------------------------------------------------------------------------

    /** A parsed head: which entity, and which of its coats. */
    private record Head(@NotNull String entityId, @NotNull Optional<String> variant) {}

    /**
     * Resolves a head against the loaded index.
     *
     * <p>A model with no variant axis takes the whole path as its id. Otherwise the longest variant
     * family whose remainder is one of its own declared options wins, which is what keeps a name like
     * {@code zombie_nautilus_warm} from binding to a shorter family that happens to be a prefix.
     */
    private @NotNull Head resolveHead(@NotNull String head) {
        int split = head.indexOf(NAMESPACE_SEPARATOR);
        if (split < 0) return null;
        String namespace = head.substring(0, split);
        String rest = head.substring(split + NAMESPACE_SEPARATOR.length());
        if (namespace.isEmpty() || rest.isEmpty()) return null;

        String direct = namespace + ":" + rest;
        Entity plain = this.entities.get(direct);
        if (plain != null && plain.axes().variants().isEmpty()) return new Head(direct, Optional.empty());

        String bestId = null;
        String bestOption = null;
        for (Map.Entry<String, Entity> entry : this.entities.entrySet()) {
            Map<String, Entity> options = entry.getValue().axes().variants();
            if (options.isEmpty()) continue;
            String localPath = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            String prefix = localPath + "_";
            if (!rest.startsWith(prefix)) continue;
            String option = rest.substring(prefix.length());
            if (!options.containsKey(option)) continue;
            if (bestId == null || localPath.length() > bestId.length() - namespace.length() - 1) {
                bestId = entry.getKey();
                bestOption = option;
            }
        }
        if (bestId != null) return new Head(bestId, Optional.of(bestOption));
        return null;
    }

    // ------------------------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------------------------

    private static @NotNull AppearanceKey.Result malformed(@NotNull String name, @NotNull String reason) {
        return new AppearanceKey.Result.Malformed(name, reason);
    }

    private static @NotNull String token(@NotNull AppearanceKey.Axis axis, @NotNull String value) {
        return axis.token() + "=" + escape(value.toLowerCase(Locale.ROOT));
    }

    private static @NotNull Optional<String> declaredDefault(
        Entity model, @NotNull java.util.function.Function<Entity.Axes, Optional<String>> axis) {
        return model == null ? Optional.empty() : axis.apply(model.axes());
    }

    private static <E extends Enum<E>> @NotNull Optional<E> enumOf(@NotNull Class<E> type, @NotNull String value) {
        for (E constant : type.getEnumConstants())
            if (constant.name().toLowerCase(Locale.ROOT).equals(value)) return Optional.of(constant);
        return Optional.empty();
    }

    private static @NotNull Optional<DyeColor> dye(@NotNull String value) {
        if (value.startsWith("#")) {
            String hex = value.substring(1);
            if (hex.length() != 6 || !isLowerHex(hex)) return Optional.empty();
            return Optional.of(new DyeColor.Custom(0xFF000000 | Integer.parseInt(hex, 16)));
        }
        return Optional.ofNullable(DyeColor.Vanilla.ofName(value.toUpperCase(Locale.ROOT)));
    }

    private static @NotNull String dyeName(@NotNull DyeColor dye) {
        if (dye instanceof DyeColor.Vanilla vanilla) return vanilla.name().toLowerCase(Locale.ROOT);
        return String.format(Locale.ROOT, "#%06x", dye.argb() & 0xFFFFFF);
    }

    /** Builds the four-slot uniform armour set the {@code armor} token names. */
    private static @NotNull ArmorOptions uniformArmor(@NotNull ArmorMaterial material, @NotNull Optional<Integer> dye) {
        ArmorPiece piece = dye
            .map(rgb -> new ArmorPiece(material, Optional.empty(), Optional.empty(), Optional.of(0xFF000000 | rgb), false))
            .orElseGet(() -> ArmorPiece.of(material));
        return ArmorOptions.builder()
            .helmet(Optional.of(piece))
            .chestplate(Optional.of(piece))
            .leggings(Optional.of(piece))
            .boots(Optional.of(piece))
            .build();
    }

    private static boolean isLowerHex(@NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Percent-encodes every character a value may not carry raw. */
    private static @NotNull String escape(@NotNull String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')
                out.append(c);
            else
                out.append(String.format(Locale.ROOT, "%%%02x", b & 0xFF));
        }
        return out.toString();
    }

    /** Decodes {@code %XX} escapes, rejecting a {@code %} that does not introduce one. */
    private static @NotNull String unescape(@NotNull String value) {
        if (value.indexOf('%') < 0) return value;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '%') { out.write(c); continue; }
            if (i + 2 >= value.length())
                throw new IllegalArgumentException("truncated escape at " + i);
            String hex = value.substring(i + 1, i + 3);
            if (!isLowerHex(hex))
                throw new IllegalArgumentException("escape '%" + hex + "' is not two lowercase hex digits");
            out.write(Integer.parseInt(hex, 16));
            i += 2;
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
