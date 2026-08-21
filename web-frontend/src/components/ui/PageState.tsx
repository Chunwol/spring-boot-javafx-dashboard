import { AlertCircle, Inbox } from 'lucide-react'
import { Spinner } from './Spinner'

export function PageLoading({ label = '데이터를 불러오는 중입니다.' }: { label?: string }) {
  return <div className="page-state"><Spinner /><p>{label}</p></div>
}

export function PageError({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="page-state page-state-error" role="alert">
      <AlertCircle size={22} />
      <p>{message}</p>
      {onRetry && <button className="button secondary" onClick={onRetry}>다시 시도</button>}
    </div>
  )
}

export function EmptyState({ message }: { message: string }) {
  return <div className="page-state"><Inbox size={22} /><p>{message}</p></div>
}
