These changes exist only in ShabbyFork and are not part of upstream Komga.
They are listed newest first, and are included in every build of this fork.

Each entry records the fork build it first shipped in. A fork build is named
`<upstream version>-ShabbyFork-build<n>`, so several builds made against the same
upstream release can be told apart. Builds made before the numbering was introduced
are named `<upstream version>-ShabbyFork`, with no number.

## Build

### Number each fork build

*Added in 1.27.1-ShabbyFork-build1.*

A fork build was named after the upstream release it was built on and nothing else, so two
builds made against the same upstream release had the same name. Once downloaded or deployed
there was no way to tell which was which.

The runnable jar is now `komga-<version>-ShabbyFork-build<n>.jar`, and the version shown in
the UI carries the same suffix, so the build a server is running is visible without going
back to the file it was started from.

The number comes from the fork release tags that already exist for that upstream version: the
highest `-build<n>` plus one. Tagging a release is what advances it, so rebuilding a release
that has not been tagged yet keeps the number it already has. Because the tags are scoped to the
upstream version, the first build on a new upstream release starts again at `build1`.

### Load translations without vite-plugin-dir2json

*Added in 1.27.0-ShabbyFork.*

Upstream loads the compiled translation files through `vite-plugin-dir2json`, which
writes Windows path separators into the code it generates:

    const __9__json__ = () => import("\src\i18n\uk.json")

`\s` and `\i` are not valid escape sequences, so `vite build` cannot parse it and no
bundle can be produced on Windows at all.

They are now loaded with Vite's own glob import, which returns the same record of
lazy import functions without the platform bug. Behaviour is unchanged everywhere.

## Archive formats

### Support 7z compressed comic books

*Added in 1.27.0-ShabbyFork.*

Komga reads CBZ and CBR, but treated 7z archives as unsupported, so a `.cb7` or a
comic compressed with 7-Zip could not be read at all.

7z archives are now read like any other comic: `.cb7` and `.7z` files are picked up
when a library is scanned, and a file compressed with 7-Zip is recognised by its
content whatever it is named. They can also be converted to CBZ, the same way CBR
can.

Encrypted 7z archives are reported as unsupported, as encrypted RAR archives already
were.

Note that 7z archives are usually *solid*, which means a page cannot be read without
decompressing everything before it. Reading such a comic page by page is noticeably
slower than CBZ, so converting to CBZ is worth it for anything read often.

## Read list import

### Rank matches by the issue year from the CBL

*Added in 1.27.0-ShabbyFork.*

A book in a ComicRack reading list also carries the year the issue itself was
released, in the `Year` element. It was parsed but never read, so books sharing
a number within a series came back in no particular order, and a list that omits
the series year got no ranking at all.

The requested issue year is now compared against the release date of the matched
books. It orders the books within a series, and a series holding a book released
that year ranks higher, which tells apart series sharing a title even when the
list carries no series year.

Matched books now carry their release date, which is also added to the match API
response.

A request with neither year is returned untouched, and so is a match that no year
can corroborate. Sorting is stable and no match is removed.

### Rank matches by the series year from the CBL

*Added in 1.27.0-ShabbyFork.*

A book in a ComicRack reading list carries the year its series started, in the
`Volume` element. The generated request looks for both the plain series name and
the name with the volume appended, like `Batman (2016)`, so in a library holding
several series with the same name every one of them came back as a match, in no
particular order.

Clients select the first match by default, so a book from the wrong series was
silently imported.

Matches are now sorted so that the series corroborated by the requested year comes
first. A volume appended to the title outranks the release date of the first book
of the series, being the more explicit signal.

Sorting is stable and no match is removed, so any series can still be picked by hand.
