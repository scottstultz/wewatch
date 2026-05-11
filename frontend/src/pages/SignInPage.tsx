import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'

function SignInPage() {
  const { token, handleCredential } = useAuth()
  const navigate = useNavigate()
  const buttonRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (token) {
      navigate('/home', { replace: true })
      return
    }

    const initGsi = () => {
      if (!buttonRef.current) return
      google.accounts.id.initialize({
        client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID as string,
        callback: (response) => {
          handleCredential(response.credential)
          navigate('/home', { replace: true })
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

  return (
    <div className="sign-in-page">
      <div className="sign-in-card">
        <div className="sign-in-brand">
          <p className="brand-kicker">WeWatch</p>
          <h1 className="sign-in-title">Pick something worth watching.</h1>
        </div>
        <p className="sign-in-prompt">Sign in to continue</p>
        <div ref={buttonRef} className="sign-in-button-wrap" />
      </div>
    </div>
  )
}

export default SignInPage
