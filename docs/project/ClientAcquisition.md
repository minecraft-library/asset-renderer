# Client acquisition

Load when touching `content/client` - `ClientAcquisition`, `ClientOptions`, `ClientAssets` - or the
exception hierarchy a batch renderer's skip-and-continue reads: `RendererException`,
`ClientException`, `ToolingException`. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

- Client-jar acquisition is `lib.minecraft.renderer.content.client` in this source tree -
  `ClientAcquisition`, `ClientOptions`, `ClientAssets`. It is the one place in
  the repo that touches the network, and it raises `ClientException` off `RuntimeException` rather
  than `RendererException`, so a batch renderer's skip-and-continue cannot swallow a client that
  failed to acquire. A request the Mojang API fails surfaces as that API's own
  `MojangApiException`, unwrapped; `ClientException` is what acquisition raises for the rest.
  `acquire()` caches the jar at `<cacheRoot>/vanilla/<version>/client.jar` and hands back the vanilla
  pack as a `PackContainer.Live` - the jar's `assets/minecraft` and `data/minecraft` trees read into
  memory once, plus the `pack.mcmeta` it synthesises from `version.json`. Nothing is extracted unless
  `ClientOptions.extractAssets` asks, and then into `<cacheRoot>/vanilla/<version>/pack`, beside the
  jar and the harness `references/` rather than around them.

## Decisions that stay closed

- Do not re-parent `ToolingException` under `RendererException` - `AtlasRenderer`'s skip-and-continue
  catches exist so one bad model never aborts a batch, and a missing client-jar class must abort it.
  `ClientException` stays off the root for the same reason, and acquisition leaves a
  `MojangApiException` unwrapped, since it carries the HTTP status, headers and body a wrapper would
  bury. A texture fetch inside a render is one subject's read instead, so `SkinFetch` wraps either
  failure in `ContentException`, which that catch skips.
