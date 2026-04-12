package dev.sbs.renderer.text;

import com.google.gson.JsonObject;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

@Getter
@Setter
@ToString
public class ColorSegment {

    protected @NotNull String text;
    protected @NotNull Optional<ChatColor> color = Optional.empty();
    protected boolean italic, bold, underlined, obfuscated, strikethrough;

    public ColorSegment(@NotNull String text) {
        this.setText(text);
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Explode the {@link #getText()} into single-words for use in a dynamic newline system.
     */
    public @NotNull ConcurrentList<ColorSegment> explode() {
        return Arrays.stream(StringUtil.split(this.getText(), " "))
            .map(word -> this.mutate().withText(word).build())
            .collect(Concurrent.toList());
    }

    public static @NotNull Builder from(@NotNull ColorSegment colorSegment) {
        return builder()
            .withText(colorSegment.getText())
            .withColor(colorSegment.getColor())
            .isItalic(colorSegment.isItalic())
            .isBold(colorSegment.isBold())
            .isUnderlined(colorSegment.isUnderlined())
            .isObfuscated(colorSegment.isObfuscated())
            .isStrikethrough(colorSegment.isStrikethrough());
    }

    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText) {
        return fromLegacy(legacyText, '&');
    }

    /**
     * This function takes in a legacy text string and converts it into a {@link ColorSegment}.
     * <p>
     * Legacy text strings use the {@link ChatFormat#SECTION_SYMBOL}. Many keyboards do not have this symbol however,
     * which is probably why it was chosen. To get around this, it is common practice to substitute
     * the symbol for another, then translate it later. Often '&' is used, but this can differ from person
     * to person. In case the string does not have a {@link ChatFormat#SECTION_SYMBOL}, the method also checks for the
     * {@param characterSubstitute}
     *
     * @param legacyText The text to make into an object
     * @param symbolSubstitute The character substitute
     * @return A LineSegment representing the legacy text.
     */
    public static @NotNull LineSegment fromLegacy(@NotNull String legacyText, char symbolSubstitute) {
        return fromLegacyHandler(legacyText, symbolSubstitute, () -> new ColorSegment(""));
    }

    public @NotNull Builder mutate() {
        return from(this);
    }

    /**
     * Resolves the {@link MinecraftFont.Style} corresponding to this segment's bold and italic
     * flags. Used by {@code TextRenderer} and any other caller that needs to pick a font
     * variant from a styled segment without coupling the font enum back to this class.
     *
     * @return the matching font style, or {@link MinecraftFont.Style#REGULAR} when neither
     * bold nor italic is set
     */
    public @NotNull MinecraftFont.Style fontStyle() {
        return MinecraftFont.Style.of((this.bold ? 1 : 0) + (this.italic ? 2 : 0));
    }

    protected static @NotNull LineSegment fromLegacyHandler(@NotNull String legacyText, char symbolSubstitute, @NotNull Supplier<? extends ColorSegment> segmentSupplier) {
        LineSegment.Builder builder = LineSegment.builder();
        ColorSegment current = segmentSupplier.get();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < legacyText.length(); i++) {
            char ch = legacyText.charAt(i);

            if ((ch != ChatFormat.SECTION_SYMBOL && ch != symbolSubstitute) || i + 1 >= legacyText.length()) {
                buf.append(ch);
                continue;
            }

            char peek = legacyText.charAt(++i);

            // Try color first, then format
            ChatColor color = ChatColor.of(peek);
            ChatFormat format = color == null ? ChatFormat.of(peek) : null;

            if (color == null && format == null) {
                buf.append(ch);
                i--; // un-consume the peek
                continue;
            }

            // Flush buffered text before applying the new code
            if (!buf.isEmpty()) {
                current.setText(buf.toString());
                builder.withSegments(current);
                current = segmentSupplier.get();
                buf.setLength(0);
            }

            if (color != null) {
                // Color codes reset all styles (vanilla behavior)
                current = segmentSupplier.get();
                current.setColor(color);
            } else if (format == ChatFormat.RESET) {
                current.setColor(ChatColor.WHITE);
                current.setObfuscated(false);
                current.setBold(false);
                current.setItalic(false);
                current.setUnderlined(false);
                current.setStrikethrough(false);
            } else {
                switch (format) {
                    case OBFUSCATED -> current.setObfuscated(true);
                    case BOLD -> current.setBold(true);
                    case STRIKETHROUGH -> current.setStrikethrough(true);
                    case UNDERLINE -> current.setUnderlined(true);
                    case ITALIC -> current.setItalic(true);
                    default -> {}
                }
            }
        }

        current.setText(buf.toString());
        builder.withSegments(current);
        return builder.build();
    }

    public void setColor(@Nullable ChatColor color) {
        this.color = Optional.ofNullable(color);
    }

    public void setColor(@NotNull Optional<ChatColor> color) {
        this.color = color;
    }

    public void setText(@NotNull String value) {
        this.text = StringUtil.defaultIfEmpty(value, "")
            .replaceAll("(?<!\\\\)'", "’") // Handle Unescaped Windows Apostrophe
            .replaceAll("\\\\'", "'"); // Remove Escaped Backslash
    }

    public @NotNull JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("text", this.getText());
        this.getColor().ifPresent(color -> object.addProperty("color", color.toJsonString()));
        if (this.isItalic()) object.addProperty("italic", true);
        if (this.isBold()) object.addProperty("bold", true);
        if (this.isUnderlined()) object.addProperty("underlined", true);
        if (this.isObfuscated()) object.addProperty("obfuscated", true);
        if (this.isStrikethrough()) object.addProperty("strikethrough", true);

        return object;
    }

    public @NotNull String toLegacy() {
        return this.toLegacy(ChatFormat.SECTION_SYMBOL);
    }

    /**
     * Takes an {@link ColorSegment} and transforms it into a legacy string.
     *
     * @param substitute The substitute character to use if you do not want to use {@link ChatFormat#SECTION_SYMBOL}
     * @return A legacy string representation of a text object
     */
    public @NotNull String toLegacy(char substitute) {
        return this.toLegacyBuilder(substitute).toString();
    }

    protected @NotNull StringBuilder toLegacyBuilder() {
        return this.toLegacyBuilder(ChatFormat.SECTION_SYMBOL);
    }

    protected @NotNull StringBuilder toLegacyBuilder(char symbol) {
        StringBuilder builder = new StringBuilder();
        this.getColor().ifPresent(color -> builder.append(symbol).append(color.getCode()));
        if (this.isObfuscated()) builder.append(symbol).append(ChatFormat.OBFUSCATED.getCode());
        if (this.isBold()) builder.append(symbol).append(ChatFormat.BOLD.getCode());
        if (this.isStrikethrough()) builder.append(symbol).append(ChatFormat.STRIKETHROUGH.getCode());
        if (this.isUnderlined()) builder.append(symbol).append(ChatFormat.UNDERLINE.getCode());
        if (this.isItalic()) builder.append(symbol).append(ChatFormat.ITALIC.getCode());

        this.getColor().ifPresent(color -> {
            builder.setLength(0);
            builder.append(symbol).append(ChatFormat.RESET.getCode());
        });

        if (StringUtil.isNotEmpty(this.getText()))
            builder.append(this.getText());

        return builder;
    }

    public @Nullable TextSegment toTextObject() {
        return TextSegment.fromJson(this.toJson());
    }

    public static class Builder {

        protected String text = "";
        protected Optional<ChatColor> color = Optional.empty();
        protected boolean italic, bold, underlined, obfuscated, strikethrough;

        public Builder isBold() {
            return this.isBold(true);
        }

        public Builder isBold(boolean value) {
            this.bold = value;
            return this;
        }

        public Builder isItalic() {
            return this.isItalic(true);
        }

        public Builder isItalic(boolean value) {
            this.italic = value;
            return this;
        }

        public Builder isObfuscated() {
            return this.isObfuscated(true);
        }

        public Builder isObfuscated(boolean value) {
            this.obfuscated = value;
            return this;
        }

        public Builder isStrikethrough() {
            return this.isStrikethrough(true);
        }

        public Builder isStrikethrough(boolean value) {
            this.strikethrough = value;
            return this;
        }

        public Builder isUnderlined() {
            return this.isUnderlined(true);
        }

        public Builder isUnderlined(boolean value) {
            this.underlined = value;
            return this;
        }

        public Builder withColor(@Nullable ChatColor color) {
            return this.withColor(Optional.ofNullable(color));
        }

        public Builder withColor(@NotNull Optional<ChatColor> color) {
            this.color = color;
            return this;
        }

        public Builder withText(@Nullable String text) {
            return this.withText(Optional.ofNullable(text));
        }

        public Builder withText(@NotNull Optional<String> text) {
            this.text = text.filter(StringUtil::isNotEmpty).orElse("");
            return this;
        }

        public @NotNull ColorSegment build() {
            ColorSegment colorSegment = new ColorSegment(this.text);
            colorSegment.setColor(this.color);
            colorSegment.setObfuscated(this.obfuscated);
            colorSegment.setItalic(this.italic);
            colorSegment.setBold(this.bold);
            colorSegment.setUnderlined(this.underlined);
            colorSegment.setStrikethrough(this.strikethrough);
            return colorSegment;
        }

    }

}
