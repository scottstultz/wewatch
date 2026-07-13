import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Like manifest.test.ts, this asserts on a file rather than a component, so it reads straight off
// disk rather than going through the #287 mocked-ApiClient setup. Paths come from the Vitest cwd
// (the frontend project root) because import.meta.url is not a file:// URL under jsdom.
const projectFile = (name: string) => resolve(process.cwd(), name)

const nginxConf = readFileSync(projectFile('nginx.conf.template'), 'utf-8')
const indexHtml = readFileSync(projectFile('index.html'), 'utf-8')

/** The quoted value of an `add_header <name> "<value>" always;` line, or null if absent. */
function header(name: string): string | null {
  const match = nginxConf.match(new RegExp(`add_header\\s+${name}\\s+"([^"]*)"\\s+always;`))
  return match ? match[1] : null
}

const csp = header('Content-Security-Policy') ?? ''

/** The sources listed for one CSP directive, e.g. directive('img-src') -> ["'self'", 'data:', …]. */
function directive(name: string): string[] {
  const found = csp.split(';').find((part) => part.trim().startsWith(`${name} `))
  return found ? found.trim().split(/\s+/).slice(1) : []
}

describe('nginx security headers', () => {
  it('sends every header #337 requires, on error responses too', () => {
    expect(csp).not.toBe('')
    expect(header('X-Content-Type-Options')).toBe('nosniff')
    expect(header('X-Frame-Options')).toBe('DENY')
    expect(header('Referrer-Policy')).toBe('strict-origin-when-cross-origin')
    // HSTS is the one variable value: it is gated on X-Forwarded-Proto, since Railway terminates
    // TLS upstream and the request nginx sees is always plain http.
    expect(nginxConf).toContain('add_header Strict-Transport-Security $hsts_header always;')
    expect(nginxConf).toMatch(/map \$http_x_forwarded_proto \$hsts_header \{/)
  })

  it('locks down the directives an XSS foothold would need', () => {
    expect(directive('default-src')).toEqual(["'self'"])
    expect(directive('frame-ancestors')).toEqual(["'none'"])
    expect(directive('object-src')).toEqual(["'none'"])
    expect(directive('base-uri')).toEqual(["'self'"])

    // style-src has to carry 'unsafe-inline' (React style attributes with runtime-computed widths).
    // script-src must never follow it there -- that is the directive doing the actual work.
    expect(directive('script-src')).not.toContain("'unsafe-inline'")
    expect(directive('script-src')).not.toContain("'unsafe-eval'")
  })

  it('allows the image sources the backend hands the frontend', () => {
    // Posters, stills, cast photos and provider logos are all absolute image.tmdb.org URLs built
    // in TmdbClient.java; data: is the inline chevron SVG in index.css.
    expect(directive('img-src')).toContain('https://image.tmdb.org')
    expect(directive('img-src')).toContain('data:')
  })

  // The CSP is an allowlist maintained by hand, and a blocked script fails only in the built
  // container -- never in `npm run dev`, which serves no CSP. So a new third-party <script> or
  // <link> in index.html would ship green and break in production. This is the guard.
  it('allows every third-party origin index.html loads', () => {
    const origins = [...indexHtml.matchAll(/(?:href|src)="(https:\/\/[^/"]+)/g)].map((m) => m[1])
    expect(origins.length).toBeGreaterThan(0)

    const missing = [...new Set(origins)].filter((origin) => !csp.includes(origin))
    expect(missing, 'index.html loads an origin the CSP does not allow').toEqual([])
  })

  // nginx replaces the inherited add_header set the moment a location declares one of its own, so
  // an add_header inside any location block below would silently strip every header above it.
  it('declares no add_header inside a location block', () => {
    const directives = nginxConf.replace(/^\s*#.*$/gm, '') // the word also appears in the comments
    const locations = directives.split(/^\s*location\b/m).slice(1)
    expect(locations.length).toBeGreaterThan(0)

    for (const block of locations) {
      const body = block.split(/^\s*\}/m)[0]
      expect(body, 'an add_header here drops the ones declared in `server`').not.toContain(
        'add_header',
      )
    }
  })
})
