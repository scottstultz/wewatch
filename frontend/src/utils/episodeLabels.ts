// Shared episode/air-date labels (#321). formatUpcomingDate moved here from
// LibraryPage when the Home "Returning this week" panel needed the identical format —
// the two surfaces showing the same air date in different words would read as a bug.

export function formatUpcomingDate(dateStr: string): string {
  try {
    const d = new Date(dateStr + 'T00:00:00')
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  } catch {
    return dateStr
  }
}

export function formatEpisodeCode(season: number, episode: number): string {
  return `S${season}E${episode}`
}
