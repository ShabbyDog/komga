/**
 * Lazy loaders for the translation messages, keyed by locale code (e.g. `fr`).
 *
 * ShabbyFork: upstream loads these through `vite-plugin-dir2json`, which writes
 * Windows path separators into the code it generates:
 *
 *     const __9__json__ = () => import("\src\i18n\uk.json")
 *
 * `\s` and `\i` are not valid escape sequences, so `vite build` cannot parse it
 * and no bundle can be produced on Windows at all. Vite's own glob import yields
 * the same shape -- a record of dynamic import functions -- without the bug.
 */
const modules = import.meta.glob<{ default: Record<string, string> }>('../../i18n/*.json')

const localeMessages: Record<string, () => Promise<{ default: Record<string, string> }>> =
  Object.fromEntries(
    Object.entries(modules).map(([path, load]) => [
      path.slice(path.lastIndexOf('/') + 1).replace(/\.json$/, ''),
      load,
    ]),
  )

export default localeMessages
