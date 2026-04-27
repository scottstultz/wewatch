import { Navigate, Route, Routes } from 'react-router-dom'

import AppLayout from './components/AppLayout'
import DiscoverPage from './pages/DiscoverPage'
import HomePage from './pages/HomePage'
import LibraryPage from './pages/LibraryPage'
import ProfilePage from './pages/ProfilePage'

function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Navigate to="/home" replace />} />
        <Route path="/home" element={<HomePage />} />
        <Route path="/discover" element={<DiscoverPage />} />
        <Route path="/library" element={<LibraryPage />} />
        <Route path="/profile" element={<ProfilePage />} />
      </Route>
    </Routes>
  )
}

export default App
