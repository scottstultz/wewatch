import type { Genre, GenreCatalog, TitleType } from '../types/api'

// One medium's real TMDB catalog, name-sorted (#384). Discover browses TMDB rather
// than a loaded list, so its picker offers the whole catalog — presentGenres below
// answers the different question the Library asks ("what's on these entries?").
//
// Scoped by medium and never merged, because AND across media is not expressible:
// TV has no Romance, Horror or Thriller at all, and its Action & Adventure (10759)
// is a different genre from a movie's Action (28). A cross-medium list would need
// an OR nested inside the AND, whose precedence TMDB doesn't document.
export function catalogFor(catalog: GenreCatalog | null, type: TitleType): Genre[] {
  if (!catalog) return []
  return [...(type === 'TV' ? catalog.tv : catalog.movie)]
    .sort((a, b) => a.name.localeCompare(b.name))
}

// TMDB's movie and TV catalogs flattened to one id → name map (#382). Movie wins a
// collision, which is the same precedence StatsService.genreNames() has used since #323 —
// keep them agreed, or the same id reads one way in the Library and another on Stats.
//
// Most ids are shared and identical across the two catalogs (35 is Comedy in both), so a
// merge is mostly a no-op. The ones that genuinely differ — movie 28 "Action" vs TV 10759
// "Action & Adventure" — stay two distinct entries on purpose: they are different genres
// with different ids, and collapsing them by hand would let a filter claim a movie carries
// a TV-only genre.
export function mergeGenreCatalog(catalog: GenreCatalog | null): Map<number, string> {
  const merged = new Map<number, string>()
  if (!catalog) return merged
  for (const genre of [...catalog.movie, ...catalog.tv]) {
    if (!merged.has(genre.id)) merged.set(genre.id, genre.name)
  }
  return merged
}

// The genres actually present across a set of entries' genreIds, labelled and sorted by
// name. Ids with no name in the catalog are dropped — a checkbox reading "18" is worse than
// no checkbox — which also means an empty result covers every degraded case at once: no
// genres on these entries, nothing cached yet, or the catalog fetch having failed.
export function presentGenres(
  genreIdsPerEntry: number[][],
  names: Map<number, string>,
): Genre[] {
  const present = new Set<number>()
  for (const ids of genreIdsPerEntry) {
    for (const id of ids) present.add(id)
  }
  return [...present]
    .filter(id => names.has(id))
    .map(id => ({ id, name: names.get(id) as string }))
    .sort((a, b) => a.name.localeCompare(b.name))
}
