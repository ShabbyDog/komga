These changes exist only in ShabbyFork and are not part of upstream Komga.
They are listed newest first, and are included in every build of this fork.

## Build

### Load translations without vite-plugin-dir2json

Upstream loads the compiled translation files through `vite-plugin-dir2json`, which
writes Windows path separators into the code it generates:

    const __9__json__ = () => import("\src\i18n\uk.json")

`\s` and `\i` are not valid escape sequences, so `vite build` cannot parse it and no
bundle can be produced on Windows at all.

They are now loaded with Vite's own glob import, which returns the same record of
lazy import functions without the platform bug. Behaviour is unchanged everywhere.

## Read list import

### Rank matches by the issue year from the CBL

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
