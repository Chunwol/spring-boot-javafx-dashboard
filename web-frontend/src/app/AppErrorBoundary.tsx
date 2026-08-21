import { Component } from 'react'
import type { ErrorInfo, PropsWithChildren, ReactNode } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'

interface AppErrorBoundaryState {
  error: Error | null
}

export class AppErrorBoundary extends Component<PropsWithChildren, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled frontend error', error, info)
  }

  private reload = () => {
    window.location.reload()
  }

  render(): ReactNode {
    if (!this.state.error) return this.props.children

    return (
      <main className="fatal-error" role="alert">
        <AlertTriangle aria-hidden="true" />
        <h1>화면을 표시하지 못했습니다</h1>
        <p>일시적인 화면 오류가 발생했습니다. 새로고침하면 현재 페이지를 다시 불러옵니다.</p>
        <button className="button primary" type="button" onClick={this.reload}>
          <RefreshCw size={16} aria-hidden="true" />
          새로고침
        </button>
      </main>
    )
  }
}
