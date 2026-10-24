# Test suites

Load when deciding which suite a test belongs in, installing one of the cache extensions, or reading
why a fast class was abandoned rather than run. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

**What the tag separates is the NETWORK, not the cache.** The fast suite reads the client assets as a
matter of course: a test that needs them installs `ClientAssetsExtension`, which reads the vanilla pack
out of the client jar cached at the root `ClientOptions` itself defaults to, once per JVM, and ABANDONS
the class where no jar is cached - so a fast run cannot download. The Minecraft fonts go the same
way: a class that renders text installs `MinecraftFontsExtension`, which reads them from the test classpath,
`cache/fonts` or the text library's per-user cache and ABANDONS the class where none holds them,
since the library's own fallback clones `font-generator` over the network. Five classes keep the tag,
for what they need beyond those caches: `ClientAcquisitionIntegrationTest` is the acquisition's own
test and the one place the client's cold path runs, `FontGenerationIntegrationTest` the one place the
font generator runs, `PackAcquisitionIntegrationTest` and `PackContainerCatsSampleTest` need the
sample packs under `cache/asset-renderer/packs`, and `ReferenceKeyRoundTripTest` needs the harness
reference tree. A pack read from the cache by path cannot download, so `HypixelPlusReachTest` reads
`cache/texturepacks/Hypixel+ 0.23.4 for 1.21.8.zip` in the fast suite and ABANDONS the class where
the zip is absent. `ClientJarGuardTest` and `FontCacheGuardTest` are the tests that FAIL on an
absent client jar or font cache, so a suite thinned by assumption says so once rather than reporting
green over coverage it skipped; nothing fails on an absent pack. `SlowTagRuleTest` holds that rule
against the sources.
