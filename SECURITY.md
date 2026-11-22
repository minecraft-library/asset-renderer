# Security policy

## Supported versions

There are no numbered releases yet. The library is published from the `master` branch through JitPack, and security fixes land on `master`. If you depend on a pinned commit, move to a newer one to pick up a fix.

## Reporting a vulnerability

Please don't open a public issue or a pull request that describes a security problem.

Report it privately on GitHub instead: open the repository's Security tab and choose "Report a vulnerability". That starts a private advisory that only the maintainers can read.

If the button isn't there, open an issue saying only that you have a security report and need a private way to send it. Leave the details out of the issue.

A good report names the input involved (a resource pack, a render request or something else), says what the library did with it, and includes the smallest input that shows the problem. The commit you tested and your JDK version help too.

## What to expect

A maintainer will reply on the advisory, and the fix is worked out there in private. The advisory is published once the fix is on `master`. You are credited in it unless you ask not to be.

## Trust model

The library treats two kinds of input as untrusted:

- Resource packs, whether a zip, a directory or a `.cats` file. Every file inside one may have been written by someone hostile.
- Render requests: the ids, NBT, text, sizes and skin references that arrive through the options builders. An integrator may be passing these straight through from its own users.

It trusts the rest:

- `ClientOptions`, which holds the Minecraft version, the cache root and the other client settings. The integrator sets these, and they are not meant to carry values from end users.
- The cache directory. The client jar and anything else read back from the cache is taken as genuine, so keep the directory writable only by the process that owns it.
- The build and the developer tooling. Gradle tasks, the generators, the parity toolkit and the reference harness run on a developer's own checkout, with arguments the developer typed.

A pack or a request can be malformed or hostile, and the worst it should manage is one of the library's documented exceptions. Anything beyond that is a vulnerability, and we want to hear about it.

A problem that needs a hostile `ClientOptions` value, write access to the cache or a crafted build argument sits outside that line. It is still a bug, and a normal issue is the right place for it.
