# What a pack can reach

Load when touching `content/container` - `PackContainer`, how a `Directory` reads - the name a pack
gives an overlay directory - `PackRoot.isOverlayDirectory` - or a place where text a pack or a request
wrote is joined into a path, `PackStack.probeInPack` being the one there is. Indexed from
[`CLAUDE.md`](../../CLAUDE.md).

A pack is the files under its own root and nothing else. Three of the four container kinds hold that
by construction: a zip read into memory, a zip re-opened per read and a Catharsis archive look a path
up as a key, and a key they do not hold is absent however it is spelled. A `Directory` resolves a path
against the file system, where a path can name a file the pack does not own. So the rule is held in
three places, each standing without the other two.

- **The container.** `PackContainer.isEntryPath` is the test of a path: relative, no `..` segment, no
  backslash, no NUL. A `Directory` answers a string that fails it as absent - `bytes` empty, `exists`
  false, `entries` nothing - and one the platform reads as rooted or cannot spell the same way. The
  refusal is made on the string, before the disk is asked anything, and nothing is raised, because a
  probe is how a loader asks. A segment that only starts or ends with dots is a name like any other,
  and an empty or `.` segment reads as the file system reads it, as both did before.
- **A link.** A file a `Directory` finds is served only where its real path, every link followed, lies
  under the root's. A link that stays inside the pack reads as the file it names, a root that is
  itself reached through a link is still the root, and a link that leads out names nothing: absent to
  a read and to a probe, and left out of a listing. The client turns away, whole, a pack directory
  holding a link whose target is not on a list its user keeps; here the pack loads and only what the
  link names is lost. A file that is found pays for the check and one that is not pays nothing. A
  listing pays for its base and for each link it meets and for no plain file, since its walk follows
  no link and so cannot leave a base it started inside.
- **An overlay's directory.** `PackRoot.isOverlayDirectory` is the client's own rule for the name,
  `[-_a-zA-Z0-9.]+` in the 26.1 jar's `OverlayMetadataSection`, less one case that rule lets through:
  a name of dots alone, which is the pack root or the directory holding the pack rather than a
  directory the pack ships. `MCMetaParser.readOverlays` holds a vanilla `overlays` entry to it and
  `PackAcquisition.addCatharsisOverlays` a `fabric:overlays` one. An entry that fails is skipped with a
  line on standard error and the entries beside it stand, as a malformed entry already did.
- **An id that becomes a path.** A texture id's name is text a pack or a request wrote. It is looked
  up as a key wherever the texture index decides, which is every id under a live namespace, and it is
  joined into a path in one place: `PackStack.probeInPack`, for an id whose prefix names a pack. The
  join tests `isEntryPath` on the path it built, and an id that fails is a texture no pack ships, which
  a render draws as the checkerboard and reports as [MissingTextures](MissingTextures.md) says. The
  test is made for every container kind, so an id resolves the same whether its pack is a directory or
  an archive.

## Decisions that stay closed

- Do not validate an id in `ResourceId`. It is built for ids that are never paths - a pack's own id,
  an entity, a request's subject - and a record's constructor has no answer but to throw, which turns
  a pack's malformed string into an exception at whatever depth first builds one. The id is tested
  where it becomes a path, and the container holds the rule under every caller.
- Do not hold an entry path or a texture id to the client's lower-case alphabet. A directory pack with
  an upper-case file name loads here where the client refuses it, and this rule is about where a path
  leads, not how it is spelled.
- Do not raise on a refused path. `bytes` and `exists` are how a loader asks whether a pack ships a
  file, and a path that leads out of the pack is a file the pack does not ship.
- Do not keep one of the container's two checks and drop the other. The test on the string alone
  follows a link; the real-path test alone asks the disk about a place outside the pack before it
  refuses it.
- Do not follow a link out of a directory pack, whoever made the link. A directory arrives from a
  clone or an unpacked archive as readily as from its author, and both carry links.
