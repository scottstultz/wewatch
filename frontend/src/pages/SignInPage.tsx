import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { exchangeToken, registerUser } from '../services/api'

type AuthMode = 'signin' | 'register'

function SignInPage() {
  const { token, handleCredential } = useAuth()
  const navigate = useNavigate()
  const buttonRef = useRef<HTMLDivElement>(null)

  const [mode, setMode] = useState<AuthMode>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (token) {
      navigate('/home', { replace: true })
      return
    }

    const initGsi = () => {
      if (!buttonRef.current) return
      google.accounts.id.initialize({
        client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID as string,
        callback: async (response) => {
          try {
            const weWatchToken = await exchangeToken('google', response.credential)
            handleCredential(weWatchToken)
            navigate('/home', { replace: true })
          } catch {
            setError('Sign-in failed. Please try again.')
          }
        },
      })
      google.accounts.id.renderButton(buttonRef.current, {
        theme: 'filled_black',
        size: 'large',
        shape: 'rectangular',
        text: 'signin_with',
        width: 240,
      })
    }

    if (typeof google !== 'undefined') {
      initGsi()
    } else {
      const script = document.querySelector<HTMLScriptElement>(
        'script[src*="accounts.google.com/gsi/client"]',
      )
      script?.addEventListener('load', initGsi)
      return () => script?.removeEventListener('load', initGsi)
    }
  }, [token, handleCredential, navigate])

  function switchMode(newMode: AuthMode) {
    setMode(newMode)
    setError(null)
    setFieldError(null)
    setEmail('')
    setPassword('')
    setConfirmPassword('')
    setDisplayName('')
  }

  async function handleSignIn(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)

    try {
      const credential = JSON.stringify({ email, password })
      const weWatchToken = await exchangeToken('email', credential)
      handleCredential(weWatchToken)
      navigate('/home', { replace: true })
    } catch (err) {
      const status = (err as Error & { status?: number }).status
      if (status === 401) {
        setError('Invalid email or password.')
      } else {
        setError('Sign-in failed. Please try again.')
      }
    } finally {
      setLoading(false)
    }
  }

  async function handleRegister(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setFieldError(null)

    if (password.length < 8) {
      setFieldError('Password must be at least 8 characters.')
      return
    }
    if (password !== confirmPassword) {
      setFieldError('Passwords do not match.')
      return
    }

    setLoading(true)

    try {
      const weWatchToken = await registerUser(email, displayName, password)
      handleCredential(weWatchToken)
      navigate('/home', { replace: true })
    } catch (err) {
      const status = (err as Error & { status?: number }).status
      if (status === 409) {
        setError('An account with this email already exists.')
      } else {
        setError('Registration failed. Please try again.')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="sign-in-page">
      <div className="sign-in-card">
        <div className="sign-in-brand">
          <p className="brand-kicker">WeWatch</p>
          <h1 className="sign-in-title">Pick something worth watching.</h1>
        </div>

        <p className="sign-in-prompt">
          {mode === 'signin' ? 'Sign in to continue' : 'Create your account'}
        </p>

        {error && <p className="sign-in-error">{error}</p>}

        {mode === 'signin' ? (
          <form className="sign-in-form" onSubmit={handleSignIn}>
            <input
              className="sign-in-input"
              type="email"
              placeholder="Email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
            />
            <input
              className="sign-in-input"
              type="password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
            />
            <button className="sign-in-submit" type="submit" disabled={loading}>
              {loading ? 'Signing in…' : 'Sign In'}
            </button>
          </form>
        ) : (
          <form className="sign-in-form" onSubmit={handleRegister}>
            <input
              className="sign-in-input"
              type="email"
              placeholder="Email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
            />
            <input
              className="sign-in-input"
              type="text"
              placeholder="Display name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              required
              autoComplete="name"
            />
            <input
              className="sign-in-input"
              type="password"
              placeholder="Password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
                setFieldError(null)
              }}
              required
              minLength={8}
              autoComplete="new-password"
            />
            <input
              className="sign-in-input"
              type="password"
              placeholder="Confirm password"
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value)
                setFieldError(null)
              }}
              required
              autoComplete="new-password"
            />
            {fieldError && <p className="sign-in-field-error">{fieldError}</p>}
            <button className="sign-in-submit" type="submit" disabled={loading}>
              {loading ? 'Creating account…' : 'Create Account'}
            </button>
          </form>
        )}

        <p className="sign-in-toggle">
          {mode === 'signin' ? (
            <>
              Don&rsquo;t have an account?{' '}
              <button type="button" className="sign-in-toggle-btn" onClick={() => switchMode('register')}>
                Create one
              </button>
            </>
          ) : (
            <>
              Already have an account?{' '}
              <button type="button" className="sign-in-toggle-btn" onClick={() => switchMode('signin')}>
                Sign in
              </button>
            </>
          )}
        </p>

        <div className="sign-in-divider">
          <span>or continue with</span>
        </div>

        <div ref={buttonRef} className="sign-in-button-wrap" />
      </div>
    </div>
  )
}

export default SignInPage
