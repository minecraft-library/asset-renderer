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
- **Acquisition checks three inputs and refuses each as a `ClientException`.** The version is held to
  a version id's shape in `ClientOptions.vanillaRoot()`, the one place every path here is resolved
  from, so it names exactly one directory under `<cacheRoot>/vanilla`. The pattern is drawn around
  the ids the Piston manifest publishes, a dozen of which carry a space, so widen it from the
  manifest rather than from memory. A download is streamed to a file of its own beside `client.jar`
  and renamed over it only when it is the size and SHA-1 the Piston metadata declares, so a short,
  long or altered download never becomes the cache, and a version the manifest does not hold creates
  nothing in it. And a vanilla pack entry whose name carries a `..` segment, or that would resolve
  outside the pack root, refuses the whole read, in memory and extracted alike, because no client jar
  Mojang built has one.
- **A jar already in the cache is not re-verified.** Nothing recorded its hash when it was written,
  and fetching the metadata to learn it would put a network round trip on the warm path, which the
  fast suite and every offline run depend on never opening a socket. `forceDownload` replaces a
  doubted jar with a verified one. What the entry check bounds is what a jar somebody else put there
  can do: it is read, and never written outside the pack root. That check reads names and paths, not
  links, so the cache directory itself is still trusted to be the caller's own.

## Decisions that stay closed

- Do not re-parent `ToolingException` under `RendererException` - `AtlasRenderer`'s skip-and-continue
  catches exist so one bad model never aborts a batch, and a missing client-jar class must abort it.
  `ClientException` stays off the root for the same reason, and acquisition leaves a
  `MojangApiException` unwrapped, since it carries the HTTP status, headers and body a wrapper would
  bury. A texture fetch inside a render is one subject's read instead, so `SkinFetch` wraps either
  failure in `ContentException`, which that catch skips.
