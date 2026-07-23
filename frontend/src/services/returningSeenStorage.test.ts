import { beforeEach, describe, expect, it } from 'vitest'
import { episodeKey, getSeenReturning, storeSeenReturning } from './returningSeenStorage'
import type { ReturningEpisode } from '../types/api'

const episode = (entryId: number, seasonNumber: number, episodeNumber: number): ReturningEpisode => ({
  entryId,
  externalId: '95396',
  externalSource: 'tmdb',
  showName: 'Severance',
  posterUrl: null,
  seasonNumber,
  episodeNumber,
  episodeName: null,
  airDate: '2026-07-17',
  runtimeMinutes: 52,
})

describe('returningSeenStorage (#360)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('keys an episode by entry + season + episode', () => {
    expect(episodeKey(episode(10, 2, 4))).toBe('10:2:4')
  })

  it('round-trips a stored seen-set for a watchlist', () => {
    storeSeenReturning(1, ['10:2:4', '11:1:1'])
    expect(getSeenReturning(1)).toEqual(new Set(['10:2:4', '11:1:1']))
  })

  it('scopes the marker per watchlist id', () => {
    storeSeenReturning(1, ['10:2:4'])
    expect(getSeenReturning(2)).toEqual(new Set())
  })

  it('returns an empty set when nothing is stored', () => {
    expect(getSeenReturning(1)).toEqual(new Set())
  })

  it('returns an empty set for malformed storage', () => {
    localStorage.setItem('wewatch_returning_seen_1', 'not json')
    expect(getSeenReturning(1)).toEqual(new Set())
  })

  it('overwrites rather than unions on store', () => {
    storeSeenReturning(1, ['10:2:4'])
    storeSeenReturning(1, ['11:1:1'])
    expect(getSeenReturning(1)).toEqual(new Set(['11:1:1']))
  })
})
