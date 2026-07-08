# WeWatch frontend

React + TypeScript + Vite single-page app. See the repository root for how the
full stack runs locally.

```bash
npm run dev     # dev server (proxies /api to localhost:8080)
npm run build   # type-check (tsc -b) + production build
npm run lint    # eslint
```

## Tests

Unit/component tests use [Vitest](https://vitest.dev) with
[React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
on jsdom (#287). CI runs them before the build; a failing test fails the build.

```bash
npm test            # run the suite once
npm run test:watch  # watch mode
```

Conventions:

- **Location**: tests are colocated with their subject as `<name>.test.ts(x)`
  (e.g. `src/pages/DiscoverPage.test.tsx` next to `DiscoverPage.tsx`).
- **Shared setup** lives in `src/test/setup.ts` (jest-dom matchers, automatic
  cleanup, storage/`ResizeObserver` shims for jsdom and Node 22+).
- **API mocking happens at the service layer**, never by stubbing global
  `fetch`: page tests partially mock `contexts/AuthContext` so `useApi`
  returns a plain object of `vi.fn()` methods, and `AuthContext` tests mock
  `services/api`'s `getCurrentUser`. Tests should survive transport changes.
- **Contexts**: prefer rendering the real provider (e.g. `WatchlistProvider`)
  on top of the mocked API over mocking the context hook itself.
- Explicit imports from `vitest` (`describe`, `it`, `expect`, `vi`) — globals
  are off.
