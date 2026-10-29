# Menus

Load when touching `ScreenMetrics`, `MenuLayout`, `MenuRenderer`, `Window`, `Mark` or `bake.gui`.
Indexed from [`CLAUDE.md`](../../CLAUDE.md).

One arithmetic and one painter. `ScreenMetrics` is where a shipped container puts its cells,
`MenuLayout` is the arithmetic between that and a `Window`, and `MenuRenderer` places content on what the layout
produced - so what a caller chooses is which screen the client ships, what goes in its cells and what
paints its chrome, and none of the three is a render path of its own. Everything is in Minecraft
pixels and reaches output pixels only through `MinecraftFont.MC_PIXEL_SCALE`, which is what keeps the
chrome exact rather than resampled.

- **A screen's declared height is read off its own construction, never off how it is blitted.** A
  chest and a shulker box each declare a pixel they never draw and reach it by different routes: the
  chest's is the source row its second blit skips, the shulker box's is a whole blit of art one
  shorter than the `(176, 167)` it constructs itself with. Both position the player's label from the
  declared height, so a slack inferred from the blit count puts one of them a pixel high. The hopper,
  dispenser and crafting table declare exactly what they draw.
- **The canvas is the drawn height, never the declared one.** `ContainerScreen` composes a chest from
  two blits totalling `rows*18 + 113` against a declared `rows*18 + 114`, blit B reading source
  `v=126` while the container half ends at row 124, so the declared box's bottom scanline is never
  painted and everything below the container rows sits a pixel higher than the sheet.
- The frame is four 4x4 corner blocks and four bars of a **1 mcPx period**, and nothing about it is
  per-menu - one frame reproduces the 4 px ring of every shipped container at its own height with no
  differing pixel. A panel of any extent is those same corners and longer bars, which is what makes a
  width the client ships no sheet for renderable and testable against one it does.
- A panel is refused below the larger **per axis** of two independent floors: `Window.minimum()` is
  what the art needs to paint a frame and `MenuLayout.minimum(ScreenMetrics)` is what the screen needs
  to hold a cell. Neither implies the other, and reading one refuses almost nothing - vanilla's drawn
  geometry closes at eight Minecraft pixels square, which a chest of no rows and no columns clears
  with nowhere to put a cell, while a window sliced from art can want more room than a screen full of
  them.
- `MinecraftFontMetrics.getAscent()` answers **output** pixels where `TextKit.drawLine` takes
  Minecraft ones. Use `getAscentMcPixels()`; never divide at a call site.
- **Text is laid by minecraft-text as the client lays it, and `TextKit` never blends.** Each glyph and
  bar pixel takes its colour's alpha, none at alpha 25 or less is laid, as the client's text shader
  discards below 0.1, and the rest blend `NORMAL` over the canvas. `TextKit` hands the graphics
  Minecraft-pixel geometry and nothing else: a strikethrough or underline bar goes through
  `MinecraftGraphics#fillEffect`, never the inherited `fillRect`, which works in output pixels and
  overwrites what it covers. A run's strikethrough and underline run from one Minecraft pixel left of
  the run to its end - the client opens the first glyph of every run of one style at `x - 1` - the
  strikethrough across 3.5 to 4.5 and the underline across 8 to 9 Minecraft pixels below the line's
  top, and a line's bars are laid after every glyph of the line, each bar's shadow copy first, as the
  client lays a prepared text's glyphs and then its effects. `TextKit` walks a line twice for it,
  glyphs then bars; a gradient run's bars follow the same rule in `GradientKit`, a per-letter run's in
  each letter's colour.
- **A run's colours are the client's font's.** A segment naming a colour draws its red, green and
  blue in the line colour's alpha, as `getTextColor` keeps it. Its shadow is the segment's shadow
  colour where it names one - that colour's alpha scaled by a see-through line's and floored - drawn
  whether or not the caller asks for a shadow; otherwise it is the text colour at a quarter, drawn
  where the caller asks for one. A shadow colour of `0` draws no shadow pass, as the client emits no
  shadow quad for it, and the library's cut lays nothing of any other at alpha 25 or less. A gradient
  run derives its shadow by the same rule from the colour each letter is drawn in, so a shadow colour
  it names is drawn as a solid run's is, through the graphics. Its letters and bands are opaque, and a
  banded run's are tinted pixel by pixel by `GradientKit` itself, since minecraft-text lays no colour
  per pixel.
- **A run's font is its segment's own.** `TextKit` takes it from the segment - its font id, the
  default font where it names none, in its bold and italic style, as minecraft-text's
  `TextSegment#resolveFont` answers - and that one font is what the run's obfuscation picks from,
  what its glyphs are drawn in and what its width and ink are measured in; a gradient run is handed
  the same font. An id the text library draws no font for draws the client's missing glyph for every
  codepoint, a hollow box five Minecraft pixels wide on an advance of six. The text library draws the
  alt fonts in one face, so a bold or italic `minecraft:alt` run is drawn upright and unbolded where
  the client offsets and slants it, and their codepoints outside the client's sheet draw the default
  font's glyph where the client draws its missing one.
- A window carries its own ink and is handed no palette: a `Window.Theme` holds one and a
  `Window.Sliced` is already coloured, so passing one would mean the arm that cannot honour it
  ignoring the argument. A caller names the theme as a `ThemeStyle`, and `Window.Theme.of` resolves
  it where the chrome is painted. Only the vanilla palette is measured against shipped art; the rest are
  authored, and none of them inks text, so a caller choosing a dark theme sets the label colour too.
- The player's section is an option a caller asks for, and it is nine cells at the margin whatever
  the panel width. Every parity subject arms it, because both gates compare against a panel that has
  one - the shipped art and the client's own screen.
- **A mark the panel would have drawn itself re-inks; anything else carries its own inks.** The
  arrow, the plus and a button's bevel are shapes in the palette's roles, so a re-inked panel carries
  re-inked marks. The anvil's hammer is a picture and the inside of its name field is an input
  widget, and a palette has nothing to say about either - the same line a button's face already sits
  on, its item being full-colour over a bevel that re-inks. The field's **outer** ring is what proves
  the line rather than breaking it: those two inks are a cell's own bit for bit, so the well sinks
  into whatever panel it is cut into while its olive stays the widget's.
- **A mark's identity is a measurement and its paint is the window's.** `Mark` - which mark, where an
  icon opens on its face, the text run its well takes, and where a screen places one - sits beside
  `ScreenMetrics`, whose factories name it. What a mark paints and how big it comes out are
  `bake.gui`'s, keyed by the identity through one switch with no default, so a mark added without a
  painter does not compile and the table placing marks names nothing that paints them.
- **The anvil's art cannot be its own oracle.** Where its name field goes the shipped panel holds a
  110x16 rectangle of flat red the client covers on every draw and never once shows, so a window
  sliced from that texture paints the red. That is why the field is drawn from rules - not to keep
  its ink free, which is the reason every other mark is declared, but because the art is a hole. It
  reproduces the shipped text-field sprite whole, and the panel's remaining residual is exactly that
  rectangle.
- A field is chrome and content split the way a button is: the window sinks the well, the renderer
  puts the text in it. The text is drawn plain rather than parsed for format codes, because the
  client's own field filters them out of what can be typed, and it carries the drop shadow a
  container's labels decline - it is a widget's text and not the panel's.
- **A field shows the end of what was typed rather than the start**, and its caret has two forms the
  value's length picks between: vanilla appends a `_` glyph after the text and cannot once the field
  is full, so at the cap it fills an unshadowed bar beside it instead. The anvil caps at 50, so a
  name at exactly that length is what draws the second form. The caret is a frozen instant of
  something that blinks on the wall clock, so it is a caller's choice rather than a phase.
- **Two gates answer for a menu and neither substitutes for the other.** The shipped-art oracle is
  byte-exact about chrome, costs seconds and boots no client; the harness menu sweep renders each
  screen through the client's own GUI pipeline and is the only one that sees what a composed screen
  adds - its labels, its slots through vanilla's GUI item atlas, and the composition itself.
