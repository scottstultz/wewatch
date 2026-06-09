import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import TmdbAttribution from './TmdbAttribution'
import WeWatchLogo from './WeWatchLogo'

const navigationItems = [
  { to: '/home', label: 'Home' },
  { to: '/discover', label: 'Discover' },
  { to: '/library', label: 'Library' },
  { to: '/profile', label: 'Profile' },
]

function AppLayout() {
  const { user, signOut } = useAuth()

  return (
    <div className="shell">
      <aside className="sidebar" aria-label="Primary">
        <div className="sidebar-top">
          <div className="brand-block">
            <WeWatchLogo onDark height={48} />
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
        </div>
        <div className="sidebar-bottom">
          <div className="sidebar-user">
            <span className="sidebar-user-name">{user?.name}</span>
            <button className="sign-out-btn" onClick={signOut}>Sign out</button>
          </div>
          <TmdbAttribution />
        </div>
      </aside>

      <div className="shell-main">
        <header className="mobile-header">
          <WeWatchLogo onDark height={36} />
          <button className="sign-out-btn sign-out-btn-mobile" onClick={signOut}>Sign out</button>
        </header>

        <main className="page-frame">
          <Outlet />
          <div className="tmdb-page-footer-mobile">
            <TmdbAttribution />
          </div>
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
