import { Navigate, Outlet, Route, Routes } from 'react-router-dom'

import AppLayout from './components/AppLayout'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import DiscoverPage from './pages/DiscoverPage'
import HomePage from './pages/HomePage'
import LibraryPage from './pages/LibraryPage'
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
          <Route element={<AppLayout />}>
            <Route index element={<Navigate to="/home" replace />} />
            <Route path="/home" element={<HomePage />} />
            <Route path="/discover" element={<DiscoverPage />} />
            <Route path="/library" element={<LibraryPage />} />
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  )
}

export default App
