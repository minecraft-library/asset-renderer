# Menus

Load when touching `ScreenMetrics`, `MenuLayout`, `MenuRenderer`, `Window`, `Mark` or `bake.gui`.
Indexed from [`CLAUDE.md`](../../CLAUDE.md).

One arithmetic and one painter. `ScreenMetrics` is where a shipped container puts its cells,
`MenuLayout` is the arithmetic between that and a `Window`, and `MenuRenderer` places content on what the layout
produced - so what a caller chooses is which screen the client ships, what goes in its cells and what
paints its chrome, and none of the three is a render path of its own. Everything is in Minecraft
pixels and reaches output pixels at `MinecraftFont.MC_PIXEL_SCALE`: a window lays its own chrome one
Minecraft pixel to a block of that side, exact rather than resampled, and the GUI sprites it draws are
laid by `NineSliceKit` at output-pixel resolution.

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
- **Named art is the client's blits.** A screen the client ships carries the regions of its sheet it
  blits - `(0, 0, 176, imageHeight)` for the shulker box, hopper, dispenser, crafting table and anvil,
  and for a chest of one to six rows `(0, 0, 176, rows*18 + 17)` with `(0, 126, 176, 96)` under it - in
  the 256-texel space every container blit declares. `Window.Sliced` composes the named sheet from
  them, reading it whole as the client does, which never animates a container sheet. With the
  player's section the panel is what the client blits. Without it there is no client panel, and the
  composition is cut after the screen's own rows and closed by its last 7 rows, so every texel is
  still the art's. Either way the cells are the art's own and no slot sprite is drawn over them unless
  the caller names one. The panel is the sheet blits alone: what the client draws over them - the
  anvil's name field and error sprite, the crafting table's recipe-book button - is a widget and no
  part of it. The window draws two of them, the anvil's name field and its cross, as the GUI sprites
  the client blits over the sheet for what the anvil's slots hold. A screen the client ships no sheet
  for - a grid, a chest of other than one to six rows, the player's view - resizes the named texture,
  read at its own size and cut to the pixels it draws, and every cell is drawn with the named sprite
  or the client's `container/slot`; the 256-texel space is a property of the client's blits, and
  such a screen has none. The panel's own sidecar is never read, container art being blitted rather
  than nine-sliced. A sheet authored at another size for a shipped screen is read at one texel per
  Minecraft pixel, where the client draws it at full resolution.
- **A window sliced from art draws a GUI sprite as the client draws one.** A cell sprite, the
  anvil's field and its cross are read as the client's GUI atlas holds them - the missing texture for
  one no pack supplies, one that cannot be read and one whose `gui` section the client refuses - and
  laid by `NineSliceKit` with their `gui.scaling`. A sprite named over a cell the client never draws
  one at, such as the crafting table's result cell of 26, is stretched as the client stretches a GUI
  sprite, which no client screen shows. Every pixel a window lays itself, drawn or cut from art, is
  laid as the client lays a GUI texel: alpha 0 leaves what is under it and the rest blends `NORMAL`.
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
  the same font, and a banded run's bands lean by that font's `slant()` - a quarter Minecraft pixel
  per Minecraft pixel of height about the height 3 above the baseline in the italic faces, as the
  client slants an italic glyph, and not at all in an upright font - unless its gradient names a
  shear of its own, which leans them about the baseline. A pixel reads its band at its centre, the
  point the glyph raster inks it by, so the bands cut a slanted glyph's texel columns where they cut
  the upright glyph's. An id the text library draws no font for draws the client's missing glyph for
  every codepoint, a hollow box five Minecraft pixels wide on an advance of six. The text library
  draws the alt fonts in one face, so a bold or italic `minecraft:alt` run is drawn upright and
  unbolded where the client offsets and slants it, a banded one with upright bands under its upright
  glyphs, and their codepoints outside the client's sheet draw the default font's glyph where the
  client draws its missing one.
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
  re-inked marks. The anvil's hammer and the cross over its arrow are pictures and the inside of its
  name field, in either form, is an input widget, and a palette has nothing to say about any of them -
  the same line a button's face already sits on, its item being full-colour over a bevel that
  re-inks. The cross is stamped over the arrow the panel has already painted, so a re-inked panel
  keeps its own arrow under it. The field's **outer** ring is what proves the line rather than
  breaking it: those two inks are a cell's own bit for bit in both forms, so the well sinks into
  whatever panel it is cut into while its inside stays the widget's.
- **A mark's identity is a measurement and its paint is the window's.** `Mark` - which mark, where an
  icon opens on its face, the text run its well takes, and where a screen places one - sits beside
  `ScreenMetrics`, whose factories name it. What a mark paints and how big it comes out are
  `bake.gui`'s, keyed by the identity through one switch with no default, so a mark added without a
  painter does not compile and the table placing marks names nothing that paints them.
- **The anvil's art cannot be its own oracle.** Where its name field goes the shipped panel holds a
  110x16 rectangle of flat red the client covers on every draw with the field sprite it blits there.
  A theme draws the field from rules - not to keep its ink free, which is the reason every other mark
  is declared, but because the art is a hole - reproducing either field sprite whole, and the cross
  over its own arrow, so against the art its residual with every slot empty is exactly that
  rectangle; a window sliced from the art draws the sprites there, as the client does.
- **The anvil's field and cross follow its slots.** The client draws the enabled field while the
  first input holds an item and the disabled one otherwise, and the cross over the arrow while either
  input holds an item and the result none. `MenuOptions.screen` answers the anvil in that state - a
  slot holds an item where the caller's map names one, an item or a render, or where the fill names
  one, and air holds nothing, the client's stack of it being empty - so the theme, the sliced window
  and the field text read one screen. The field's text and caret are white in both states, as the
  client draws its one field.
- A field is chrome and content split the way a button is: the window draws the well, the renderer
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
