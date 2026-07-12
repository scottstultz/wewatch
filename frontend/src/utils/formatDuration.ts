// A single title's runtime as "2h 16m" / "58m"; null when TMDB has no runtime for it,
// so callers can drop the field rather than print a zero they can't stand behind.
export function formatRuntime(minutes: number | null | undefined): string | null {
  if (!minutes || minutes <= 0) return null
  const hours = Math.floor(minutes / 60)
  const mins = minutes % 60
  if (hours === 0) return `${mins}m`
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`
}

// A cumulative total as "412h 20m" (#323). Unlike formatRuntime this never returns null:
// a stat tile always shows a number, and a genuine zero — nothing watched yet — is a
// meaningful answer rather than missing data. Hours are not rolled into days: "412h" is
// the brag, "17d 4h" reads like a prison sentence.
export function formatWatchTime(minutes: number | null | undefined): string {
  if (!minutes || minutes <= 0) return '0m'
  const hours = Math.floor(minutes / 60)
  const mins = minutes % 60
  if (hours === 0) return `${mins}m`
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`
}
