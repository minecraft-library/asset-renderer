# Reading the GPU's coordinate: the probe

Load when running the harness's texture-coordinate probe or reading what it wrote. The rules it
measured are [TexelFetch.md](TexelFetch.md). Indexed from [`CLAUDE.md`](../../CLAUDE.md).

The harness's texture-coordinate probe (`TexCoordProbeMixin`, run as [the harness's probes](../harness/Probes.md) say) is how
everything in [What the reference GPU does](TexelFetch.md#what-the-reference-gpu-does) is known, and the way to re-measure it after a client
bump.

- **What it covers.** It patches `core/entity`, the fragment program every entity render type compiles:
  cutout, translucent, eyes, armour, the breeze's wind and the energy swirl, each by its defines. The
  block sweep draws its blocks through two of those render types, so it reads every sweep's subjects
  that way - the entities, the player and armour sweeps, and the blocks. What another program draws -
  an item through its own, the glint, a shadow, a leash - is not a coordinate.
- **What a pixel holds.** The interpolated coordinate's whole `float`, one axis per boot: the low 24
  bits in red, green and blue, high byte first, and the top byte in alpha with its high bit flipped.
  Decode as `((a ^ 0x80) << 24) | (r << 16) | (g << 8) | b`. `+0.0` reads alpha `0x80` and `[0, 2)`
  reads `0x80..0xBF`; the shader writes `-0.0` as `+0.0`, so `(0, 0, 0, 0)` is the cleared background
  and nothing else. Alpha `0xFF` is a magnitude of at least `2^127`, so an opaque pixel another
  program drew is recognisable; a part-transparent one writes its own alpha and cannot be told from a
  coordinate by the pixel alone.
- **Which fragment it is.** The shader runs vanilla's own `main` first, so every fragment vanilla
  discards is discarded, and `TexCoordProbeBlendMixin` draws every pipeline with blending off while
  the probe is armed - so a pixel is the last fragment that passed the depth test, an additive pass
  included, written as it is. A pass with a texture matrix reports the transformed coordinate, the
  one it samples.
- **Reading a boundary.** Pair each pixel with the texel java read there - the `[PX]` pixel trace,
  `-Dasset.entity.pixel.dump=x0,y0,x1,y1`, logs it on every `WRITE` - and measure the card's value in
  float steps from the boundary `k / n` it sits nearest: `0` is on, negative is under. A disagreement
  of more than one texel is a different surface or a thin face rather than a boundary, and the
  coordinate the probe holds names the face of the sheet it came from.
