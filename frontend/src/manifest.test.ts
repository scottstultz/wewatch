import { describe, expect, it } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

// These assert on files, not components, so they read straight off disk rather than
// going through the #287 mocked-ApiClient setup. Under jsdom import.meta.url is not a
// file:// URL, so paths come from the Vitest cwd (the frontend project root).
const publicFile = (name: string) => resolve(process.cwd(), 'public', name)

const manifest = JSON.parse(readFileSync(publicFile('manifest.webmanifest'), 'utf-8'))
const indexHtml = readFileSync(resolve(process.cwd(), 'index.html'), 'utf-8')

const THEME_COLOR = '#0f1720'

/** Width and height live in the PNG's IHDR, bytes 16-24. Saves pulling in an image library. */
function pngSize(path: string): string {
  const header = readFileSync(path).subarray(16, 24)
  return `${header.readUInt32BE(0)}x${header.readUInt32BE(4)}`
}

describe('PWA manifest', () => {
  it('declares what a browser needs to offer an install', () => {
    expect(manifest.name).toBe('WeWatch')
    expect(manifest.short_name).toBe('WeWatch')
    expect(manifest.display).toBe('standalone')
    // "/" and not "/home": the index route already redirects, and it is the one entry point
    // that stays correct whether or not the user is signed in.
    expect(manifest.start_url).toBe('/')
    expect(manifest.theme_color).toBe(THEME_COLOR)
    expect(manifest.background_color).toBe(THEME_COLOR)
  })

  it('ships an icon at every size it advertises', () => {
    expect(manifest.icons.length).toBeGreaterThan(0)

    for (const icon of manifest.icons) {
      const path = publicFile(icon.src.replace(/^\//, ''))
      expect(existsSync(path), `${icon.src} is missing from public/`).toBe(true)
      expect(pngSize(path), `${icon.src} is not ${icon.sizes}`).toBe(icon.sizes)
    }

    // Chrome will not treat the app as installable without both of these.
    const any = manifest.icons.filter((i: { purpose: string }) => i.purpose === 'any')
    expect(any.map((i: { sizes: string }) => i.sizes)).toContain('512x512')
    expect(manifest.icons.some((i: { purpose: string }) => i.purpose === 'maskable')).toBe(true)
  })

  it('agrees with the theme-color meta tag', () => {
    expect(indexHtml).toContain(`<meta name="theme-color" content="${THEME_COLOR}" />`)
    expect(indexHtml).toContain('<link rel="manifest" href="/manifest.webmanifest" />')
  })

  // The /favicon.svg link sat in index.html pointing at nothing for months. It went unnoticed
  // because nginx's SPA fallback (try_files ... /index.html) answers a missing asset with a 200
  // and a page of HTML, so a broken icon never 404s -- it just quietly decodes as garbage.
  it('has no index.html asset link pointing at a file that does not exist', () => {
    const links = [...indexHtml.matchAll(/(?:href|src)="(\/[^"]+)"/g)].map((m) => m[1])
    expect(links.length).toBeGreaterThan(0)

    const missing = links
      .filter((link) => !link.startsWith('/src/')) // bundled by Vite, not served from public/
      .filter((link) => !existsSync(publicFile(link.replace(/^\//, ''))))

    expect(missing).toEqual([])
  })
})
