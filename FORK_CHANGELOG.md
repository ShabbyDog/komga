These changes exist only in ShabbyFork and are not part of upstream Komga.
They are listed newest first, and are included in every build of this fork.

Each entry records the fork build it first shipped in. A fork build is named
`<upstream version>-ShabbyFork-build<n>`, so several builds made against the same
upstream release can be told apart. Builds made before the numbering was introduced
are named `<upstream version>-ShabbyFork`, with no number.

## Reading lists

### Record which reading list a read was made in

*Added in 1.29.2-ShabbyFork-build3.*

Komga records how far you have read a book, but not where you were reading it from, so a book
read as part of a reading list looks exactly like one read in its series. Clients that want to
treat the two differently, keeping list reading out of a series' Keep reading and On deck, had
to track that themselves, on each device separately.

The server now keeps it. A client marks progress through
`PATCH /api/v1/fork/books/{bookId}/read-progress`, which takes the same body as Komga's own
endpoint plus an optional `readListId`: the read is recorded as made in that reading list, or,
without one, as an ordinary series read. The reading list must contain the book.

The context belongs to that exact progress. If the progress changes any other way, through
stock Komga, Kobo or KOReader sync, or another app, the read counts as a series read again, so
a client that does not know about contexts can never leave a wrong one behind.

`GET /api/v1/fork/capabilities` lists what a ShabbyFork server offers, starting with
`read-context`, so clients can tell a fork from stock Komga and enable each feature only where
it exists. Stock Komga's endpoints are unchanged.

### Import reading-list contexts kept by a client

*Added in 1.29.2-ShabbyFork-build4.*

A client that tracked reading-list reads itself, before the server could, can hand that history
over once with `POST /api/v1/fork/read-contexts`, offered as `read-context-import`. Each
context names the book, the reading list, and the progress the client recorded for that read.

The server takes a context only if your current progress on the book is still that read, the
same page and completion. If the progress has changed since, on any client, the read stays a
series read rather than being guessed at. The reading list must contain the book, and you must
be able to see both. The response says how many were taken and how many skipped.

### Dashboard rows that keep reading lists apart

*Added in 1.29.2-ShabbyFork-build3.*

Komga's Keep reading and On deck treat every read as a series read. Reading a crossover event
through a reading list therefore filled Keep reading with issues you were reading in the list,
and finishing one put that series' next issue On deck, though you had no plans to read the
series. And On deck mixed two different things: series you are partway through, and series
you had finished until a new issue arrived.

Four endpoints give clients rows built on the reading-list context above. Each starts from
Komga's own query for the row, so what is in progress or on deck, and what you are allowed to
see, stays exactly Komga's, and each returns the same page of books or reading lists as
Komga's endpoints do:

- `GET /api/v1/fork/books/keep-reading`: books in progress, most recently read first, without
  those you are reading through a reading list.
- `GET /api/v1/fork/books/on-deck`: Komga's On deck, without series whose most recently
  finished book was read through a reading list, and without the series below.
- `GET /api/v1/fork/books/new-on-deck`: series you had finished before new books arrived, where
  every book you have not finished was added after you last finished one there.
- `GET /api/v1/fork/readlists/in-progress`: reading lists you have read at least one book
  through and not finished, most recently read first. A book in two lists, read through one,
  starts only that one.

They are offered as `dashboard-rows` in the capabilities.

Both web UIs use them on a fork server. Their Keep Reading and On Deck rows become the fork's, and two new rows, New Books On Deck and Reading Lists In Progress, are part of the default dashboard and can be added to a saved one from its section editor. The web reader tells the server when a book is read through a reading list, so the rows know. Against stock Komga both UIs behave exactly as before, and a layout saved on a fork keeps its fork rows, hidden, until the server is a fork again.

## Database

### Keep the database usable by stock Komga

*Added in 1.29.2-ShabbyFork-build2.*

Switching a server back to stock Komga is meant to be as simple as swapping the jar, but it
was not: stock Komga refused to start on a database this fork had used. The option to scan a
library when its files change had been added as an ordinary Komga database migration, and
Komga will not start on a database whose migration history lists one it does not know.

The fork now keeps everything of its own in a separate file, `shabbyfork.sqlite`, next to
`database.sqlite` in the config directory. Stock Komga never opens it, so `database.sqlite`
stays exactly what stock expects, and a server can go back to stock and return to the fork
without either one complaining.

The first start of this build moves the scan-on-change option of every library into the new
file and removes the old column and its history entry from `database.sqlite`. Nothing needs
doing by hand, but back up the config directory first: an older fork build will not start on the
moved database, so going back to one means restoring that backup (the automated deploy's
rollback does this). Stock Komga and this build onwards start on it either way.

Back up `shabbyfork.sqlite` together with the other databases from now on.

## Scanning

### Scan a library when its files change

*Added in 1.27.1-ShabbyFork-build1.*

A library was only scanned on a timer, so a comic added just after a scan sat unnoticed until
the next one came round — up to six hours later on the default interval. Shortening the
interval only traded that delay for repeated scans of a library that had not changed.

A library can now be told to **scan when the files change**, in the Scanner section of its
settings. The library folder is watched, and a change anywhere below it schedules a scan.

Watching is done by the platform's own mechanism: FSEvents on macOS, inotify on Linux, and
ReadDirectoryChangesW on Windows — so no polling, and no cost while nothing is happening.

A scan does not start on the first event. Copying a large comic in produces a steady burst of
events, and the file is not complete until the burst ends, so each change restarts a one minute
countdown and only the quiet at the end triggers the scan. Copying a batch of files therefore
costs a single scan rather than one per file.

The option works alongside the scan interval rather than replacing it, so a periodic scan can
still be kept as a safety net for changes the watcher misses — a network share, for instance,
where the operating system reports nothing to the machine running Komga.

Two notes for large libraries. On Linux, each watched folder consumes an inotify watch, and a
library with more folders than `fs.inotify.max_user_watches` allows will fail to watch; the
limit is raised with `sysctl fs.inotify.max_user_watches`. And if the library also converts to
CBZ or repairs extensions, the scan changes files itself, which schedules one more scan that
finds nothing to do.

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

## Updates screen

### Show the fork's changelog on the updates screen

*Added in 1.27.0-ShabbyFork.*

The updates screen lists the releases of upstream Komga, which say nothing about what this fork
adds, so a fork server gave no way to see which of its features came from the fork.

Both the web UI and the new UI now show this changelog above the list of upstream releases.
It is built into the jar, so it always describes the build the server is running.

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
