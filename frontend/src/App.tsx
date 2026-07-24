import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'

import AppLayout from './components/AppLayout'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { ReturningNotificationsProvider } from './contexts/ReturningNotificationsContext'
import { WatchlistProvider } from './contexts/WatchlistContext'
import DiscoverPage from './pages/DiscoverPage'
import HomePage from './pages/HomePage'
import LibraryPage from './pages/LibraryPage'
import PersonPage from './pages/PersonPage'
import ShowDetailPage from './pages/ShowDetailPage'
import TitleDetailPage from './pages/TitleDetailPage'
import ProfilePage from './pages/ProfilePage'
import StatsPage from './pages/StatsPage'
import SignInPage from './pages/SignInPage'

function ProtectedRoute() {
  const { token } = useAuth()
  const location = useLocation()
  // Carry the attempted location so SignInPage can return the user there after
  // they sign in, instead of dumping everyone on /home (#308).
  return token ? <Outlet /> : <Navigate to="/sign-in" state={{ from: location }} replace />
}

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/sign-in" element={<SignInPage />} />
        <Route element={<ProtectedRoute />}>
          <Route
            element={
              <WatchlistProvider>
                <ReturningNotificationsProvider>
                  <AppLayout />
                </ReturningNotificationsProvider>
              </WatchlistProvider>
            }
          >
            <Route index element={<Navigate to="/home" replace />} />
            <Route path="/home" element={<HomePage />} />
            <Route path="/discover" element={<DiscoverPage />} />
            <Route path="/library" element={<LibraryPage />} />
            <Route path="/library/:entryId" element={<ShowDetailPage />} />
            <Route path="/title/:type/:source/:externalId" element={<TitleDetailPage />} />
            {/* Reached from a cast tile, not the nav bar (#305) */}
            <Route path="/person/:personId" element={<PersonPage />} />
            <Route path="/stats" element={<StatsPage />} />
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  )
}

export default App
