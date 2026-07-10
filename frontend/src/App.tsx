import { Navigate, Outlet, Route, Routes } from 'react-router-dom'

import AppLayout from './components/AppLayout'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { WatchlistProvider } from './contexts/WatchlistContext'
import DiscoverPage from './pages/DiscoverPage'
import HomePage from './pages/HomePage'
import LibraryPage from './pages/LibraryPage'
import PersonPage from './pages/PersonPage'
import ShowDetailPage from './pages/ShowDetailPage'
import TitleDetailPage from './pages/TitleDetailPage'
import ProfilePage from './pages/ProfilePage'
import SignInPage from './pages/SignInPage'

function ProtectedRoute() {
  const { token } = useAuth()
  return token ? <Outlet /> : <Navigate to="/sign-in" replace />
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
                <AppLayout />
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
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  )
}

export default App
