import { NavLink, Outlet } from 'react-router-dom'

const navigationItems = [
  { to: '/home', label: 'Home' },
  { to: '/discover', label: 'Discover' },
  { to: '/library', label: 'Library' },
  { to: '/profile', label: 'Profile' },
]

function AppLayout() {
  return (
    <div className="shell">
      <aside className="sidebar" aria-label="Primary">
        <div className="brand-block">
          <p className="brand-kicker">WeWatch</p>
          <h1 className="brand-title">Pick something worth watching.</h1>
        </div>
        <nav className="nav-stack">
          {navigationItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                isActive ? 'nav-link nav-link-active' : 'nav-link'
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="shell-main">
        <header className="mobile-header">
          <div>
            <p className="brand-kicker">WeWatch</p>
            <h1 className="mobile-title">Your watch queue</h1>
          </div>
        </header>

        <main className="page-frame">
          <Outlet />
        </main>

        <nav className="mobile-nav" aria-label="Primary">
          {navigationItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                isActive ? 'mobile-nav-link mobile-nav-link-active' : 'mobile-nav-link'
              }
            >
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  )
}

export default AppLayout
