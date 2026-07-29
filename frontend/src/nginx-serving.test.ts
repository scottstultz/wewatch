import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Like manifest.test.ts and security-headers.test.ts, this asserts on a file rather than a
// component, so it reads straight off disk rather than going through the #287 mocked-ApiClient
// setup. The path comes from the Vitest cwd (the frontend project root) because import.meta.url is
// not a file:// URL under jsdom.
//
// None of what this file pins is observable in `npm run dev` -- Vite serves the app itself and
// there is no nginx in the loop -- so the config can only break in the built container, i.e. in
// production. That is what makes it worth testing as text.
const nginxConf = readFileSync(resolve(process.cwd(), 'nginx.conf.template'), 'utf-8')
const dockerfile = readFileSync(resolve(process.cwd(), 'Dockerfile'), 'utf-8')

/** Every directive the config actually declares. Comments go first: they name these same directives. */
const directives = nginxConf.replace(/^\s*#.*$/gm, '')

/** The media types listed in `gzip_types ...;`. */
const gzipTypes = (directives.match(/gzip_types\s+([^;]+);/)?.[1] ?? '').trim().split(/\s+/)

/** The body of `map $uri $cache_control { ... }` -- the cache policy table. */
const cacheMap = directives.match(/map \$uri \$cache_control \{([^}]*)\}/)?.[1] ?? ''

/** The body of `map $http_x_forwarded_proto $forwarded_proto { ... }` -- the #348 proto table. */
const protoMap =
  directives.match(/map \$http_x_forwarded_proto \$forwarded_proto \{([^}]*)\}/)?.[1] ?? ''

/** The body of `map "${TRUST_EDGE_REAL_IP}:$http_x_real_ip" $client_ip { ... }` -- the #408 table. */
const clientIpMap =
  directives.match(
    /map "\$\{TRUST_EDGE_REAL_IP\}:\$http_x_real_ip" \$client_ip \{([^}]*)\}/,
  )?.[1] ?? ''

/**
 * The body of a location block, e.g. locationBody('/assets/'). Ends at the first line that closes a
 * block rather than at the first `}` character: /api/ contains `${BACKEND_URL}`, so a lazy match up
 * to the next brace stops one line into the body and silently reports it as almost empty.
 */
function locationBody(path: string): string {
  const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const opening = directives.match(new RegExp(`location ${escaped} \\{`))
  if (opening?.index === undefined) return ''
  return directives.slice(opening.index + opening[0].length).split(/^\s*\}/m)[0]
}

/** The value $cache_control takes for one map key, e.g. cachePolicy('~^/assets/'). */
function cachePolicy(key: string): string | null {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return cacheMap.match(new RegExp(`^\\s*${escaped}\\s+"([^"]*)"`, 'm'))?.[1] ?? null
}

describe('nginx compression (#347)', () => {
  it('turns gzip on, which the nginx:alpine base image does not', () => {
    // The stock nginx.conf ships `#gzip on;`, so before #347 every cold load pulled the whole
    // bundle uncompressed.
    expect(directives).toMatch(/^\s*gzip on;/m)
    // Shared caches must key on Accept-Encoding or they hand a gzipped body to a client that did
    // not ask for one.
    expect(directives).toMatch(/gzip_vary on;/)
  })

  it('compresses every media type the app actually serves', () => {
    // Both JavaScript types on purpose: nginx 1.31.2's mime.types maps .js to
    // application/javascript, but nginx has moved on this before. Naming only the current one means
    // a base-image bump silently stops compressing the biggest file we serve -- and nothing here
    // would go red. image/svg+xml is favicon.svg; application/manifest+json is the #324 manifest.
    expect(gzipTypes).toContain('application/javascript')
    expect(gzipTypes).toContain('text/javascript')
    expect(gzipTypes).toContain('text/css')
    expect(gzipTypes).toContain('application/json')
    expect(gzipTypes).toContain('image/svg+xml')
    expect(gzipTypes).toContain('application/manifest+json')

    // text/html is compressed implicitly whenever gzip is on and is the one type gzip_types cannot
    // control -- listing it would imply this list is the whole story.
    expect(gzipTypes).not.toContain('text/html')
  })

  it('compresses every custom media type the config declares', () => {
    // .webmanifest is absent from nginx's mime.types, so the manifest location declares its type by
    // hand (#324). A custom type that gzip_types does not also name ships uncompressed and nobody
    // notices; this couples the two lists.
    const declared = [...directives.matchAll(/types\s*\{([^}]*)\}/g)].flatMap((block) =>
      block[1]
        .split(';')
        .map((mapping) => mapping.trim().split(/\s+/)[0])
        .filter(Boolean),
    )
    expect(declared.length).toBeGreaterThan(0)

    const uncompressed = declared.filter((type) => !gzipTypes.includes(type))
    expect(uncompressed, 'a type declared in a `types` block is missing from gzip_types').toEqual([])
  })

  it('does not leave gzip_proxied at its default', () => {
    // The default (`off`) skips compression for any request carrying a Via header -- i.e. anything
    // arriving through an edge proxy such as Railway's. At the default this whole change would work
    // under local docker compose and compress nothing in production.
    expect(directives).toMatch(/gzip_proxied\s+any;/)
  })
})

describe('nginx cache policy (#347)', () => {
  it('caches the content-hashed bundle immutably', () => {
    // Vite hashes these filenames, so the URL changes whenever the bytes do: they can never be
    // stale. `immutable` is the part that stops a reload from revalidating them anyway.
    const assets = cachePolicy('~^/assets/') ?? ''
    expect(assets).toContain('max-age=31536000')
    expect(assets).toContain('immutable')
    expect(assets).toContain('public')
  })

  it('keeps index.html revalidating, so a deploy lands on the next navigation', () => {
    // index.html names the hashed bundles, so it is the one file a deploy must invalidate. no-cache
    // still caches -- it revalidates before use -- rather than pinning a stale document for the
    // length of some heuristic freshness window.
    expect(cachePolicy('default')).toBe('no-cache')
    expect(cachePolicy('default')).not.toContain('max-age')
  })

  it('adds nothing to API responses, which carry Spring Security’s own policy', () => {
    // nginx omits an add_header whose value is empty (the same trick the HSTS header uses). Spring
    // sends "no-cache, no-store, max-age=0, must-revalidate"; anything we added would either
    // duplicate the header or weaken it into something a browser may write to disk.
    expect(cachePolicy('~^/api/')).toBe('')
  })

  it('declares Cache-Control in `server`, and deliberately without `always`', () => {
    // In `server` because a location that declares an add_header replaces the whole inherited set
    // and would strip the #337 CSP (security-headers.test.ts guards that in general). Without
    // `always` because add_header then applies to 2xx/3xx only: a year-long policy on an error
    // response is how a bad deploy pins a 404 in someone's browser.
    expect(directives).toMatch(/add_header Cache-Control \$cache_control;/)
    expect(directives).not.toMatch(/add_header Cache-Control[^;]*always/)
    expect(locationBody('/assets/')).not.toContain('add_header')
  })

  it('404s a missing hashed asset instead of falling back to index.html', () => {
    // Without its own location the bundle falls into the SPA fallback, and a missing /assets/ file
    // answers 200 + index.html -- the browser then parses a page of HTML as JavaScript. Harmless
    // enough before #347; now that this prefix carries a year-long immutable policy, it would be
    // cached. A 404 carries no cache policy at all (see above), so a bad deploy leaves no residue.
    expect(locationBody('/assets/')).toContain('=404')
    expect(locationBody('/assets/')).not.toContain('index.html')
  })
})

describe('nginx forwarded protocol (#348)', () => {
  it('forwards the edge’s protocol to the backend rather than $scheme', () => {
    // TLS ends at Railway's edge, so $scheme inside this container is http for every request ever
    // made -- sending it as X-Forwarded-Proto tells the backend that plain http is all anyone uses.
    // Nothing reads the header server-side today; the first feature that builds an absolute URL or
    // sets a secure cookie from it would inherit the lie.
    expect(locationBody('/api/')).toContain('proxy_set_header X-Forwarded-Proto $forwarded_proto;')
    expect(locationBody('/api/')).not.toMatch(/X-Forwarded-Proto\s+\$scheme/)
  })

  it('falls back to the real scheme when no edge sits in front of us', () => {
    // docker compose publishes 3000:80 straight at nginx, so there is no X-Forwarded-Proto on the
    // request at all and the map has to degrade to what this container actually spoke.
    expect(protoMap).toMatch(/default\s+\$scheme;/)
  })

  it('can only ever emit http or https, never a client-supplied string', () => {
    // The value is handed to the backend, so a pass-through map (`default $http_x_forwarded_proto`)
    // would let a caller put arbitrary bytes in front of whatever eventually reads the header. Only
    // the protocol regex may produce a value; everything else lands on the $scheme default above.
    const sources = protoMap
      .split(';')
      .map((line) => line.trim().split(/\s+/)[0])
      .filter(Boolean)
    expect(sources).toEqual(['"~^(?<edge_proto>https?)\\b"', 'default'])
  })

  it('keys HSTS off the same normalized value, so the two cannot drift', () => {
    // Reading the raw header a second time is how the /api/ proxy and the HSTS header ended up
    // disagreeing about the same request in the first place. It also means a chained value
    // ("https, http") misses the exact-match `https` key and silently drops HSTS in production.
    expect(directives).toMatch(/map \$forwarded_proto \$hsts_header \{/)
    expect(directives).not.toMatch(/map \$http_x_forwarded_proto \$hsts_header \{/)
  })

  it('substitutes only ${BACKEND_URL}, so nginx’s own $-variables survive the build', () => {
    // envsubst with no argument replaces *every* $-token it can see: $scheme, $forwarded_proto,
    // $hsts_header and $http_x_forwarded_proto would all be blanked at image build time, and the
    // config that reaches nginx would be a different file from the one in this repo. The explicit
    // list is the only thing preventing that, and nothing pinned it before #348.
    const list = dockerfile.match(/envsubst\s+'([^']*)'/)?.[1] ?? ''
    expect(list, 'envsubst must be given an explicit variable list').not.toBe('')

    const allowed = new Set(list.match(/\$\w+/g) ?? [])
    const placeholders = new Set(
      [...directives.matchAll(/\$\{(\w+)\}/g)].map((match) => `$${match[1]}`),
    )
    expect(placeholders.size).toBeGreaterThan(0)

    const unsubstituted = [...placeholders].filter((name) => !allowed.has(name))
    expect(unsubstituted, 'the template expands a placeholder envsubst is not told about').toEqual(
      [],
    )
  })
})

describe('nginx client IP normalization (#408)', () => {
  it('gates X-Real-IP trust on a deployment-time literal, not header presence', () => {
    // A bare `map $http_x_real_ip $client_ip` would let a self-hosted client set its own
    // X-Real-IP and have nginx forward it as authoritative -- presence of the header is exactly
    // what a direct client controls in a topology with no edge in front of nginx. The source must
    // be the header concatenated with ${TRUST_EDGE_REAL_IP}, a literal only envsubst can set.
    expect(directives).toMatch(
      /map "\$\{TRUST_EDGE_REAL_IP\}:\$http_x_real_ip" \$client_ip \{/,
    )
  })

  it('falls back to $remote_addr for anything but an exact "1:" prefix', () => {
    expect(clientIpMap).toMatch(/default\s+\$remote_addr;/)

    // Only two branches: the default, and the strict "1:<value>" match -- nothing a client sends
    // (even a value starting with the digit "1") can produce a third path, because the ":"
    // delimiter means only the deploy-time literal can ever produce a leading "1:".
    const sources = clientIpMap
      .split(';')
      .map((line) => line.trim().split(/\s+/)[0])
      .filter(Boolean)
    expect(sources).toEqual(['default', '"~^1:(?<v>.+)$"'])
  })

  it('overwrites, rather than appends to, both X-Real-IP and X-Forwarded-For', () => {
    // nginx has already resolved the one correct value (or fallen back to its own peer), so
    // forwarding the edge's own irrelevant internal hop chain on top of it serves nothing -- and
    // a single overwritten value is exactly the shape ClientIpResolver's existing right-to-left
    // walk (#336) already handles correctly, so no backend change was needed.
    expect(locationBody('/api/')).toContain('proxy_set_header X-Real-IP $client_ip;')
    expect(locationBody('/api/')).toContain('proxy_set_header X-Forwarded-For $client_ip;')
    expect(locationBody('/api/')).not.toMatch(/X-Real-IP\s+\$remote_addr/)
    expect(locationBody('/api/')).not.toMatch(/X-Forwarded-For\s+\$proxy_add_x_forwarded_for/)
  })
})
