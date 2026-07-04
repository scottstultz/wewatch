import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * Top-level safety net. A render/rehydration error anywhere below unmounts the
 * whole React tree, leaving an empty #root — on mobile this surfaces as a blank
 * (black) screen when a frozen/bfcached tab is restored with stale state.
 * Rather than let that happen, we render a minimal reload prompt (#242).
 */
class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Uncaught error in React tree:', error, info)
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div className="error-boundary">
          <h1>Something went wrong</h1>
          <p>The app hit an unexpected error. Reloading usually fixes it.</p>
          <button type="button" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

export default ErrorBoundary
