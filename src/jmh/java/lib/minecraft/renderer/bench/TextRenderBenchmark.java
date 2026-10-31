package lib.minecraft.renderer.bench;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.text.LineSegment;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

import java.util.concurrent.TimeUnit;

/**
 * Text render benchmark across the three shapes a {@link TextRenderer} render takes, one
 * {@link TextRenderer#render} per operation:
 * <ul>
 * <li>{@link #loreSpriteBox() loreSpriteBox} - the five-line {@link #LORE_LEGACY lore tooltip} in the
 * {@link ChromeStyle#SPRITE sprite box}, the vanilla pack's {@code tooltip/background} and
 * {@code tooltip/frame} sprites nine-sliced by a renderer bound to the trial's pipeline context</li>
 * <li>{@link #loreCodeDrawnBox() loreCodeDrawnBox} - the same tooltip in the
 * {@link ChromeStyle#PROCEDURAL code-drawn box}, vanilla's background fill and gradient ring drawn from
 * their colours by a renderer holding no context</li>
 * <li>{@link #chatLine() chatLine} - the one-line {@link #CHAT_LEGACY chat message} as
 * {@link TextOptions.Style#CHAT chat text} on a transparent canvas ending at its ink, drawn by the
 * renderer holding no context - the glyph rows alone, with no box under them</li>
 * </ul>
 * minecraft-text lays every glyph pixel on its own, so the chat line measures what the glyph rows cost
 * and each tooltip adds what its box costs over five lines of them. No line carries obfuscated text or a
 * scrolling gradient, so every render is one still frame. Each benchmark answers its render, which JMH
 * consumes so the draw is not eliminated as dead code.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class TextRenderBenchmark extends AbstractRendererBenchmark {

    /**
     * A weapon-style tooltip of five lines - a title, two stat rows, a white stat roll carrying a
     * {@code ✦}, and a bold rarity footer - in ampersand-coded legacy form.
     */
    private static final String LORE_LEGACY = String.join("\n",
        "&6Aspect of the End",
        "&7Damage: &c+100",
        "&7Strength: &c+100",
        "&f+5 ✦ Speed",
        "&9&lRARE SWORD"
    );

    /** A one-line chat message in five colours, in ampersand-coded legacy form. */
    private static final String CHAT_LEGACY = "&e[NPC] Jerry&f: &7Welcome to your island, &bSteve&7! &a+5 ✦ Speed";

    /** Text renderer bound to the trial's pipeline context, through which the sprite box resolves its pair. */
    private TextRenderer contextRenderer;

    /** Text renderer holding no context, which draws the code-drawn box and chat text. */
    private TextRenderer plainRenderer;

    /** The lore tooltip in the sprite box, built once per trial. */
    private TextOptions loreSprite;

    /** The lore tooltip in the code-drawn box, built once per trial. */
    private TextOptions loreCodeDrawn;

    /** The chat line, built once per trial. */
    private TextOptions chat;

    /** {@inheritDoc} */
    @Override
    protected void onSetupTrial() {
        this.contextRenderer = new TextRenderer(context());
        this.plainRenderer = new TextRenderer();

        ConcurrentList<LineSegment> lore = LineSegment.fromLegacy(LORE_LEGACY, '&');
        this.loreSprite = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lore)
            .chromeStyle(ChromeStyle.SPRITE)
            .build();
        this.loreCodeDrawn = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lore)
            .chromeStyle(ChromeStyle.PROCEDURAL)
            .build();
        this.chat = TextOptions.builder()
            .style(TextOptions.Style.CHAT)
            .lines(LineSegment.fromLegacy(CHAT_LEGACY, '&'))
            .build();
    }

    /**
     * Renders the lore tooltip in the sprite box.
     *
     * @return the render
     */
    @Benchmark
    public RenderResult loreSpriteBox() {
        return this.contextRenderer.render(this.loreSprite);
    }

    /**
     * Renders the lore tooltip in the code-drawn box.
     *
     * @return the render
     */
    @Benchmark
    public RenderResult loreCodeDrawnBox() {
        return this.plainRenderer.render(this.loreCodeDrawn);
    }

    /**
     * Renders the chat line.
     *
     * @return the render
     */
    @Benchmark
    public RenderResult chatLine() {
        return this.plainRenderer.render(this.chat);
    }

}
