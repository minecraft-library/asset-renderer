package dev.sbs.renderer.text.segment;

import com.google.gson.JsonObject;
import dev.sbs.renderer.text.ChatFormat;
import dev.sbs.renderer.text.MinecraftFont;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.util.StringUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Getter
@Setter
@ToString
public class ColorSegment {

    protected @NotNull String text;
    protected @NotNull Optional<ChatFormat> color = Optional.empty();
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
     * flags. Used by {@code LoreRenderer} and any other caller that needs to pick a font
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
        ColorSegment currentObject = segmentSupplier.get();
        StringBuilder text = new StringBuilder();

        for (int i = 0; i < legacyText.length(); i++) {
            char charAtIndex = legacyText.charAt(i);

            if (charAtIndex == ChatFormat.SECTION_SYMBOL || charAtIndex == symbolSubstitute) {
                if ((i + 1) > legacyText.length() - 1)
                    continue; // do nothing.

                // peek at the next character.
                char peek = legacyText.charAt(i + 1);

                if (ChatFormat.isValid(peek)) {
                    i += 1; // if valid
                    if (!text.isEmpty()) {
                        currentObject.setText(text.toString()); // create a new text object
                        builder.withSegments(currentObject); // append the current object.
                        currentObject = segmentSupplier.get(); // reset the current object.
                        text.setLength(0); // reset the buffer
                    }

                    ChatFormat color = Objects.requireNonNull(ChatFormat.of(peek));

                    switch (color) {
                        case OBFUSCATED:
                            currentObject.setObfuscated(true);
                            break;
                        case BOLD:
                            currentObject.setBold(true);
                            break;
                        case STRIKETHROUGH:
                            currentObject.setStrikethrough(true);
                            break;
                        case ITALIC:
                            currentObject.setItalic(true);
                            break;
                        case UNDERLINE:
                            currentObject.setUnderlined(true);
                            break;
                        case RESET:
                            // Reset everything.
                            currentObject.setColor(ChatFormat.WHITE);
                            currentObject.setObfuscated(false);
                            currentObject.setBold(false);
                            currentObject.setItalic(false);
                            currentObject.setUnderlined(false);
                            currentObject.setStrikethrough(false);
                            break;
                        default:
                            // emulate Minecraft's behavior of dropping styles that do not yet have an object.
                            currentObject = segmentSupplier.get();
                            currentObject.setColor(color);
                            break;
                    }
                } else
                    text.append(charAtIndex);
            } else
                text.append(charAtIndex);
        }

        // whatever we were working on when the loop exited
        currentObject.setText(text.toString());
        builder.withSegments(currentObject);

        return builder.build();
    }

    public void setColor(@Nullable ChatFormat color) {
        this.setColor(Optional.ofNullable(Objects.isNull(color) || !color.isColor() ? null : color));
    }

    public void setColor(@NotNull Optional<ChatFormat> color) {
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
        protected Optional<ChatFormat> color = Optional.empty();
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

        public Builder withColor(@Nullable ChatFormat color) {
            return this.withColor(Optional.ofNullable(color));
        }

        public Builder withColor(@NotNull Optional<ChatFormat> color) {
            this.color = color.filter(ChatFormat::isColor);
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
