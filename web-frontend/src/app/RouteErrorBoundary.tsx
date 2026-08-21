import { useEffect } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { isRouteErrorResponse, useRouteError } from 'react-router-dom'

const CHUNK_RELOAD_KEY = 'classroom-dashboard:chunk-reload'
const DYNAMIC_IMPORT_ERRORS = [
  'failed to fetch dynamically imported module',
  'error loading dynamically imported module',
  'importing a module script failed',
  'loading chunk',
]

function errorMessage(error: unknown) {
  if (error instanceof Error) return `${error.name}: ${error.message}`
  if (isRouteErrorResponse(error)) return `${error.status}: ${error.statusText || error.data || 'Route error'}`
  return String(error ?? '')
}

export function dynamicImportErrorSignature(error: unknown) {
  const message = errorMessage(error).trim()
  const normalized = message.toLocaleLowerCase()
  if (!DYNAMIC_IMPORT_ERRORS.some((pattern) => normalized.includes(pattern))) return null

  const moduleUrl = message.match(/https?:\/\/[^\s)]+\.js(?:\?[^\s)]*)?/i)?.[0]
  return moduleUrl || message.slice(0, 512)
}

export function claimChunkReload(error: unknown, storage: Pick<Storage, 'getItem' | 'setItem'>) {
  const signature = dynamicImportErrorSignature(error)
  if (!signature || storage.getItem(CHUNK_RELOAD_KEY) === signature) return false
  storage.setItem(CHUNK_RELOAD_KEY, signature)
  return true
}

export function RouteErrorBoundary() {
  const error = useRouteError()
  const chunkSignature = dynamicImportErrorSignature(error)

  useEffect(() => {
    if (!chunkSignature) return
    try {
      if (claimChunkReload(error, window.sessionStorage)) window.location.reload()
    } catch {
      // Storage can be unavailable in restricted browser modes; manual retry remains available.
    }
  }, [chunkSignature, error])

  const reload = () => window.location.reload()

  return (
    <main className="fatal-error" role="alert">
      {chunkSignature ? <RefreshCw aria-hidden="true" /> : <AlertTriangle aria-hidden="true" />}
      <h1>{chunkSignature ? '최신 화면을 다시 불러오고 있습니다' : '화면을 표시하지 못했습니다'}</h1>
      <p>
        {chunkSignature
          ? '배포 중 변경된 화면 파일을 감지했습니다. 자동으로 복구되지 않으면 아래 버튼을 눌러 주세요.'
          : '일시적인 화면 오류가 발생했습니다. 현재 페이지를 다시 불러와 주세요.'}
      </p>
      <button className="button primary" type="button" onClick={reload}>
        <RefreshCw size={16} aria-hidden="true" />
        다시 불러오기
      </button>
    </main>
  )
}
