# Test suites

Load when deciding which suite a test belongs in, installing one of the cache extensions, or reading
why a fast class was abandoned rather than run. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

**What the tag separates is the NETWORK, not the cache.** The fast suite reads the client assets as a
matter of course: a test that needs them installs `ClientAssetsExtension`, which reads the vanilla pack
out of the client jar cached at the root `ClientOptions` itself defaults to, once per JVM, and ABANDONS
the class where no jar is cached - so a fast run cannot download. The Minecraft fonts go the same
way: a class that renders text installs `MinecraftFontsExtension`, which reads them from the test classpath,
`cache/fonts` or the text library's per-user cache and ABANDONS the class where none holds them,
since the library's own fallback clones `font-generator` over the network. Four classes keep the tag,
for what they need beyond those caches: `ClientAcquisitionIntegrationTest` is the acquisition's own
test and the one place the client's cold path runs, `FontGenerationIntegrationTest` the one place the
font generator runs, `PackContainerCatsSampleTest` needs the sample packs under
`cache/asset-renderer/packs`, and `ReferenceKeyRoundTripTest` needs the harness reference tree. A pack read from the cache by path cannot download, so `HypixelPlusReachTest` reads
`cache/texturepacks/Hypixel+ 0.23.4 for 1.21.8.zip` in the fast suite and ABANDONS the class where
the zip is absent. `ClientJarGuardTest` and `FontCacheGuardTest` are the tests that FAIL on an
absent client jar or font cache, so a suite thinned by assumption says so once rather than reporting
green over coverage it skipped; nothing fails on an absent pack. `SlowTagRuleTest` holds that rule
against the sources.

**The slow suite runs only when asked for.** `./gradlew slowTest` is scheduled by no `check`, no
`test` and no parity capture - no stored artifact names it as a producer - so the four classes that
keep the tag run on a hand invocation and nowhere else.

**A capture runs the pin writers, not the fast suite.** `parityPinTest` is `test` filtered to the
classes `parityPinWriters` names in `gradle/parity.gradle.kts` - each one writes a self-captured
row, and each is the class `reach.py` roots that row at - and it is those rows' producer, so the
rest of the fast suite runs in no capture and is its own step before a commit. A new class that
writes a self-captured row joins that list and `ROOTS` together, which `ParityTaskWiringTest` holds;
a writer the list leaves out is a row every capture records UNPRODUCED.
