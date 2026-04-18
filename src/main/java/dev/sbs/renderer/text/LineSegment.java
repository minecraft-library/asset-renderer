package dev.sbs.renderer.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.StreamUtil;
import dev.simplified.util.StringUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class LineSegment {

    private final @NotNull ConcurrentList<ColorSegment> segments;

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Explode the {@link #getSegments()} into single-words for use in a dynamic newline system.
     */
    public @NotNull ConcurrentList<ColorSegment> explode() {
        return this.getSegments()
            .stream()
            .flatMap(segment -> segment.explode().stream())
            .collect(Concurrent.toList());
    }

    public int length() {
        return this.getSegments()
            .stream()
            .mapToInt(colorSegment -> StringUtil.length(colorSegment.getText()))
            .sum();
    }

    public @NotNull JsonElement toJson() {
        JsonArray rootArray = new JsonArray();
        rootArray.add("");
        this.getSegments().forEach(segment -> rootArray.add(segment.toJson()));
        return rootArray;
    }

    /**
     * This function takes in a legacy text string and converts it into a collection of {@link LineSegment}.
     * <p>
     * Legacy text strings use the {@link ChatFormat#SECTION_SYMBOL}. Many keyboards do not have this symbol however,
     * which is probably why it was chosen. To get around this, it is common practice to substitute
     * the symbol for another, then translate it later. Often '&' is used, but this can differ from person
     * to person. In case the string does not have a {@link ChatFormat#SECTION_SYMBOL}, the method also checks for the
     * {@param characterSubstitute}
     *
     * @param legacyText The text to make into an object
     * @param symbolSubstitute The character substitute
     * @return A collection of LineSegments representing the legacy text.
     */
    public static @NotNull ConcurrentList<LineSegment> fromLegacy(@NotNull String legacyText, char symbolSubstitute) {
        return StreamUtil.ofArrays(legacyText.split("(\r?\n|\\\\n)", -1))
            .map(line -> TextSegment.fromLegacy(line, symbolSubstitute))
            .collect(Concurrent.toList());
    }

    public static class Builder {

        private final ConcurrentList<ColorSegment> segments = Concurrent.newList();

        public Builder withSegments(@NotNull ColorSegment... segments) {
            return this.withSegments(Arrays.asList(segments));
        }

        public Builder withSegments(@NotNull Iterable<ColorSegment> segments) {
            segments.forEach(this.segments::add);
            return this;
        }

        public @NotNull LineSegment build() {
            return new LineSegment(
                this.segments.toUnmodifiable()
            );
        }

    }

}
